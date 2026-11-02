package com.example.aiworldedit;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Ties it together: picks the anchor, scans terrain, calls the AI off-thread,
 * validates the result, shows a preview, and hands commands to the executor.
 * Everything except the AI call runs on the client thread.
 */
public final class BuildOrchestrator {
    private BuildOrchestrator() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AI-WorldEdit-Request");
        t.setDaemon(true);
        return t;
    });

    /** Bumped by /aistop; any reply from an older generation is thrown away. */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile boolean busy = false;

    /** A validated plan waiting for /aiconfirm. */
    private record Pending(String description, BuildPlanner.Frame frame, CommandValidator.Result validated,
                           JsonArray components, List<String> rawCommands, boolean isEdit,
                           AiClient.References references) {}

    /** Images/links the player added with /airef, used by the next /aibuild. */
    private static final List<String> userRefSpecs = new java.util.ArrayList<>();
    private static Pending pending = null;
    private static int tickCounter = 0;

    // Screenshot capture for /aifix (needs a couple of frames with the HUD hidden).
    private static int screenshotCountdown = -1;
    private static boolean hudWasHidden = false;
    private static Runnable afterScreenshot = null;
    private static byte[] screenshotPng = null;

    // Automatic review after a build finishes (config autoReviewAfterBuild).
    private static int autoReviewCountdown = -1;
    private static FabricClientCommandSource autoReviewSource = null;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Never let a bug in this mod crash the game: report it and carry on.
            try {
                tickCounter++;
                if (pending != null && client.world != null && tickCounter % 10 == 0) {
                    drawOutline(client, pending.validated().bounds());
                }
                if (autoReviewCountdown > 0) {
                    autoReviewCountdown--;
                } else if (autoReviewCountdown == 0) {
                    autoReviewCountdown = -1;
                    if (autoReviewSource != null) handleFix(autoReviewSource, "automatic review after building");
                    autoReviewSource = null;
                }
                if (screenshotCountdown > 0) {
                    screenshotCountdown--;
                } else if (screenshotCountdown == 0) {
                    screenshotCountdown = -1;
                    captureScreenshot(client);
                }
            } catch (Throwable t) {
                screenshotCountdown = -1;
                afterScreenshot = null;
                busy = false;
                if (client.options != null) client.options.hudHidden = hudWasHidden;
                reportInternalError(client, t);
            }
        });
    }

    // ---------------- commands ----------------

    public static void handleBuild(FabricClientCommandSource source, String description) {
        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("[AI] No world loaded."));
            return;
        }
        if (!checkIdle(source)) return;

        BuildPlanner.Frame frame = new BuildPlanner.Frame(pickAnchor(mc, player),
            player.getHorizontalFacing().asString());
        String environment = WorldScanner.describe(mc.world, frame);

        source.sendFeedback(Text.literal("[AI] Planning \"" + description + "\" at "
            + frame.anchor.toShortString() + " facing " + frame.facing + " ("
            + Config.get().activeModel() + ")...").formatted(Formatting.AQUA));

        List<String> specs = new java.util.ArrayList<>(userRefSpecs);
        userRefSpecs.clear(); // player references are used for one build
        java.util.function.Consumer<String> status = msg -> MinecraftClient.getInstance().execute(() ->
            source.sendFeedback(Text.literal("[AI] " + msg).formatted(Formatting.GRAY)));
        run(source, () -> {
                List<ReferenceFinder.Ref> userRefs = new java.util.ArrayList<>();
                for (String spec : specs) {
                    try {
                        userRefs.add(ReferenceFinder.load(spec));
                    } catch (Exception e) {
                        status.accept("Skipping reference " + spec + ": " + e.getMessage());
                    }
                }
                return AiClient.generateBuild(description, frame, environment, userRefs, status);
            },
            result -> {
                showReferences(source, result.references());
                Pending p = toPending(source, description, frame, result, false);
                if (p == null) {
                    source.sendError(Text.literal("[AI] The plan had no usable parts."));
                    return;
                }
                source.sendFeedback(Text.literal("[AI] " + result.message()).formatted(Formatting.GREEN));
                if (Config.get().previewReview) previewThenOffer(source, description, frame, p);
                else offerOrRun(source, p);
            });
    }

    /** /ailib: list, info, reload, or place a saved build at the crosshair, front toward the player. */
    public static void library(FabricClientCommandSource source, String args) {
        String[] a = args.trim().split("\\s+");
        String sub = a[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("reload")) {
            for (String m : BuildLibrary.load()) source.sendFeedback(Text.literal("[AI] " + m).formatted(Formatting.GRAY));
            sub = "";
        }
        if (sub.isEmpty() || sub.equals("list")) {
            List<BuildLibrary.Entry> all = BuildLibrary.all();
            if (all.isEmpty()) {
                source.sendFeedback(Text.literal("[AI] No saved builds yet. Put .litematic or .schem files in "
                    + BuildLibrary.folder() + " and run /ailib reload.").formatted(Formatting.YELLOW));
                return;
            }
            source.sendFeedback(Text.literal("[AI] Saved builds (" + (Config.get().libraryLearn()
                ? "the AI studies them before designing; /ailib study <name> shows what it learned" : "learning is off")
                + "):").formatted(Formatting.AQUA));
            for (BuildLibrary.Entry e : all) {
                int[] s = e.localSize(0);
                source.sendFeedback(Text.literal("  " + e.name + "  " + s[0] + "x" + s[2] + ", " + s[1] + " tall, "
                    + e.blockCount + " blocks").formatted(Formatting.GRAY));
            }
            return;
        }
        if (a.length < 2) {
            source.sendError(Text.literal("[AI] Usage: /ailib study <name> | /ailib reload | /ailib place <name> [90|180|270]"));
            return;
        }
        BuildLibrary.Entry e = BuildLibrary.get(a[1]);
        if (e == null) {
            source.sendError(Text.literal("[AI] No saved build called " + a[1] + ". /ailib lists them."));
            return;
        }
        if (sub.equals("study")) {
            BuildStudy.Digest d = BuildStudy.digest(e);
            source.sendFeedback(Text.literal("[AI] " + d.text()).formatted(Formatting.AQUA));
            source.sendFeedback(Text.literal("  Learned palette \"" + e.name + "\": " + d.palette()).formatted(Formatting.GRAY));
            return;
        }
        if (sub.equals("info")) {
            source.sendFeedback(Text.literal("[AI] " + e.summary()).formatted(Formatting.AQUA));
            source.sendFeedback(Text.literal("  front: " + e.front + " side of the file, ground layer " + e.groundLayer
                + (e.author.isBlank() ? "" : ", by " + e.author)).formatted(Formatting.GRAY));
            return;
        }
        if (!sub.equals("place")) {
            source.sendError(Text.literal("[AI] Usage: /ailib place <name> [90|180|270] | /ailib info <name> | /ailib reload"));
            return;
        }
        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("[AI] No world loaded."));
            return;
        }
        if (!checkIdle(source)) return;
        int rotate = 0;
        if (a.length >= 3) {
            try { rotate = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
        }
        BuildPlanner.Frame frame = new BuildPlanner.Frame(pickAnchor(mc, player), player.getHorizontalFacing().asString());
        JsonArray comps = new JsonArray();
        com.google.gson.JsonObject c = new com.google.gson.JsonObject();
        c.addProperty("type", "library");
        c.addProperty("name", e.name);
        c.addProperty("x", 0);
        c.addProperty("z", 1);
        c.addProperty("rotate", rotate);
        comps.add(c);
        Pending p = toPending(source, "saved build " + e.name, frame,
            new AiClient.AiResult("Saved build " + e.name, comps, List.of()), false);
        if (p == null) {
            source.sendError(Text.literal("[AI] Couldn't place " + e.name + "."));
            return;
        }
        source.sendFeedback(Text.literal("[AI] Placing " + e.name + " (needs singleplayer or WorldEdit's schematics "
            + "folder on the server).").formatted(Formatting.GREEN));
        offerOrRun(source, p);
    }

    /** Simulates the plan, shows the AI text drawings of the result, and lets it correct the plan once. */
    private static void previewThenOffer(FabricClientCommandSource source, String description,
                                         BuildPlanner.Frame frame, Pending p) {
        String preview = simulatePreview(frame, p.validated());
        if (preview == null) {
            offerOrRun(source, p);
            return;
        }
        source.sendFeedback(Text.literal("[AI] Checking the plan in a simulated preview...").formatted(Formatting.AQUA));
        run(source, () -> AiClient.reviewPlan(description, frame, p.components().toString(), preview, p.references()),
            review -> {
                if (!review.message().isBlank()) {
                    source.sendFeedback(Text.literal("[AI] Review: " + review.message()).formatted(Formatting.LIGHT_PURPLE));
                }
                Pending revised = review.components().isEmpty() ? null
                    : toPending(source, description, frame, new AiClient.AiResult(review.message(),
                        review.components(), review.commands(), p.references()), false);
                offerOrRun(source, revised != null ? revised : p);
            });
    }

    /** Text drawings + checks of what the validated commands would produce on the real terrain. */
    private static String simulatePreview(BuildPlanner.Frame frame, CommandValidator.Result v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || v.bounds().isEmpty()) return null;
        int[] box = v.bounds().padded(2, 3);
        if (VoxelGrid.volume(box) > Config.get().maxReviewVolume) return null;
        VoxelGrid grid = WorldScanner.toGrid(mc.world, box);
        grid.simulate(v.commands());
        return BuildInspector.report(grid, frame, false, null);
    }

    /** Text drawings + checks of the real blocks in the last build's area, compared with the plan. */
    private static String inspectWorld(BuildPlanner.Frame frame) {
        MinecraftClient mc = MinecraftClient.getInstance();
        AiClient.BuildRecord last = AiClient.getLastBuild();
        if (mc.world == null || last == null || last.box() == null) return null;
        if (VoxelGrid.volume(last.box()) > Config.get().maxReviewVolume) return null;
        VoxelGrid actual = WorldScanner.toGrid(mc.world, last.box());
        VoxelGrid expected = actual.copy();
        expected.simulate(last.executed());
        BuildInspector.Diff diff = expected.diff(actual, frame);
        return BuildInspector.report(actual, frame, true, diff);
    }

    public static void handleChat(FabricClientCommandSource source, String message) {
        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("[AI] No world loaded."));
            return;
        }
        if (!checkIdle(source)) return;

        BuildPlanner.Frame frame = editFrame(mc, player);
        String environment = WorldScanner.describe(mc.world, frame);
        run(source, () -> AiClient.chat(message, frame, environment),
            result -> handleEditResult(source, "Edit: " + message, frame, result));
    }

    public static void handleFix(FabricClientCommandSource source, String notes) {
        ClientPlayerEntity player = source.getPlayer();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || mc.world == null) {
            source.sendError(Text.literal("[AI] No world loaded."));
            return;
        }
        if (!AiClient.hasLastBuild()) {
            source.sendError(Text.literal("[AI] Nothing to review yet. Build something with /aibuild first."));
            return;
        }
        if (!checkIdle(source)) return;

        source.sendFeedback(Text.literal("[AI] Taking a screenshot for review. Hold still...")
            .formatted(Formatting.AQUA));
        int gen = GENERATION.get();
        busy = true; // reserve while the screenshot is taken
        hudWasHidden = mc.options.hudHidden;
        mc.options.hudHidden = true;
        screenshotPng = null;
        screenshotCountdown = 3; // let a few frames render without the HUD
        afterScreenshot = () -> {
            if (gen != GENERATION.get()) return; // /aistop was used
            busy = false;
            byte[] png = screenshotPng;
            if (png == null) {
                source.sendFeedback(Text.literal("[AI] Couldn't take a screenshot on this Minecraft version; "
                    + "reviewing the build plan without one.").formatted(Formatting.GOLD));
            }
            BuildPlanner.Frame frame = editFrame(mc, player);
            String environment = WorldScanner.describe(mc.world, frame);
            String inspection = inspectWorld(frame);
            source.sendFeedback(Text.literal("[AI] Reviewing the build"
                + (inspection != null ? " (scanned " + "the blocks in the world)" : "") + "...").formatted(Formatting.AQUA));
            run(source, () -> AiClient.fix(notes, frame, environment, png, inspection),
                result -> handleEditResult(source, "Fix: " + (notes == null ? "visual review" : notes),
                    frame, result));
        };
    }

    /** /airef <link-or-file>: queue a reference image for the next /aibuild. */
    public static void addReference(FabricClientCommandSource source, String spec) {
        String s = spec.trim();
        if (s.equalsIgnoreCase("clear")) {
            userRefSpecs.clear();
            source.sendFeedback(Text.literal("[AI] Reference list cleared.").formatted(Formatting.YELLOW));
            return;
        }
        if (s.equalsIgnoreCase("list")) {
            source.sendFeedback(Text.literal("[AI] Queued references: " + (userRefSpecs.isEmpty() ? "none"
                : String.join(", ", userRefSpecs)) + ". Files go in " + ReferenceFinder.referenceFolder())
                .formatted(Formatting.AQUA));
            return;
        }
        if (s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off")) {
            boolean on = s.equalsIgnoreCase("on");
            Config.update(c -> c.referenceSearch = on);
            source.sendFeedback(Text.literal("[AI] Automatic reference search " + (Config.get().referenceSearch
                ? "on." : "off.")).formatted(Formatting.YELLOW));
            return;
        }
        if (userRefSpecs.size() >= 5) {
            source.sendError(Text.literal("[AI] You can queue up to 5 references. /airef clear to start over."));
            return;
        }
        userRefSpecs.add(s);
        if (!s.startsWith("http")) {
            try {
                java.nio.file.Files.createDirectories(ReferenceFinder.referenceFolder());
            } catch (Exception ignored) { }
        }
        source.sendFeedback(Text.literal("[AI] Reference " + userRefSpecs.size() + " queued for your next /aibuild"
            + (AiClient.supportsImages() ? "." : " (but the current model can't read images).")).formatted(Formatting.GREEN));
    }

    private static void showReferences(FabricClientCommandSource source, AiClient.References refs) {
        if (refs == null || refs.refs().isEmpty()) return;
        source.sendFeedback(Text.literal("[AI] Style references:").formatted(Formatting.AQUA));
        for (int i = 0; i < refs.refs().size(); i++) {
            source.sendFeedback(Text.literal("  " + (i + 1) + ") " + refs.refs().get(i).credit()).formatted(Formatting.GRAY));
        }
        // Full links go to the log (chat links need extra API that differs between versions).
        for (ReferenceFinder.Ref r : refs.refs()) System.out.println("[AI WorldEdit] Reference: " + r.credit() + " " + r.pageUrl());
    }

    public static void confirm(FabricClientCommandSource source) {
        if (pending == null) {
            source.sendError(Text.literal("[AI] Nothing waiting for confirmation."));
            return;
        }
        Pending p = pending;
        pending = null;
        start(source, p);
    }

    public static void stop(FabricClientCommandSource source) {
        GENERATION.incrementAndGet();
        busy = false;
        pending = null;
        autoReviewCountdown = -1;
        autoReviewSource = null;
        CommandExecutor.stop(source.getPlayer());
        source.sendFeedback(Text.literal("[AI] Stopped. Queue, pending plan and in-flight request cleared.")
            .formatted(Formatting.YELLOW));
    }

    public static void undo(FabricClientCommandSource source) {
        pending = null;
        int n = CommandExecutor.undoLastBuild(source.getPlayer());
        if (n == 0) {
            source.sendError(Text.literal("[AI] Nothing to undo."));
        } else {
            source.sendFeedback(Text.literal("[AI] Undoing " + n + " WorldEdit edits from the last build.")
                .formatted(Formatting.YELLOW));
        }
    }

    // ---------------- helpers ----------------

    /** Edits use the last build's frame so the AI can reuse its coordinates. */
    private static BuildPlanner.Frame editFrame(MinecraftClient mc, ClientPlayerEntity player) {
        AiClient.BuildRecord last = AiClient.getLastBuild();
        if (last != null) return new BuildPlanner.Frame(last.anchor(), last.facing());
        return new BuildPlanner.Frame(pickAnchor(mc, player), player.getHorizontalFacing().asString());
    }

    /** Expands components, adds raw commands, validates. Returns null if nothing usable. */
    private static Pending toPending(FabricClientCommandSource source, String label, BuildPlanner.Frame frame,
                                     AiClient.AiResult result, boolean isEdit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonArray components = new JsonArray();
        int max = Math.max(1, Config.get().maxComponents);
        for (int i = 0; i < result.components().size() && i < max; i++) components.add(result.components().get(i));

        // Style from the request ("medieval tavern" -> medieval); edits keep the original build's style.
        AiClient.BuildRecord lastRec = AiClient.getLastBuild();
        String style = StylePresets.detect(isEdit && lastRec != null ? lastRec.description() : label);
        if (style != null && !isEdit) {
            source.sendFeedback(Text.literal("[AI] Using the " + style + " style preset.").formatted(Formatting.GRAY));
        }
        // Climate from the request ("desert tavern") or the ground it stands on (sand, snow).
        String climate = StylePresets.detectClimate(isEdit && lastRec != null ? lastRec.description() : label);
        if (climate == null && style != null && mc.world != null) {
            try {
                climate = WorldScanner.climateAt(mc.world, frame.anchor);
            } catch (Throwable ignored) {
                // keep the default look
            }
        }
        if (climate != null && style != null && !isEdit) {
            source.sendFeedback(Text.literal("[AI] Adapting it to the " + climate + " surroundings.").formatted(Formatting.GRAY));
        }
        // Repeated houses are varied unless the player asked for identical ones.
        String req = (isEdit && lastRec != null ? lastRec.description() : label);
        BuildPlanner.allowIdentical = req != null && req.toLowerCase(java.util.Locale.ROOT)
            .matches("(?s).*\\b(identical|same|matching|copies|terrace[ds]?|row houses|uniform)\\b.*");
        BuildPlanner.Plan plan = BuildPlanner.expand(components, frame,
            mc.world == null ? null : WorldScanner.ground(mc.world), style, climate);
        if (!plan.problems().isEmpty()) {
            source.sendFeedback(Text.literal("[AI] Skipped " + plan.problems().size() + " plan part(s):")
                .formatted(Formatting.GOLD));
            for (int i = 0; i < Math.min(3, plan.problems().size()); i++) {
                source.sendFeedback(Text.literal("  " + plan.problems().get(i)).formatted(Formatting.GRAY));
            }
        }
        List<String> all = new java.util.ArrayList<>(plan.commands());
        all.addAll(result.commands());
        if (CommandValidator.knownBlocks == null) {
            try {
                CommandValidator.knownBlocks = WorldScanner.allBlockIds();
            } catch (Throwable t) {
                System.err.println("[AI WorldEdit] Couldn't read the block list: " + t);
            }
        }
        CommandValidator.Result v = CommandValidator.validate(all, frame.anchor);
        // Sand, gravel and concrete powder with air under them would fall while building: keep them grounded.
        if (mc.world != null && !v.bounds().isEmpty()) {
            try {
                int[] box = v.bounds().padded(1, 2);
                if (VoxelGrid.volume(box) <= Config.get().maxReviewVolume) {
                    List<String> safe = new java.util.ArrayList<>(v.commands());
                    if (BuildPhysics.gravitySafe(safe, WorldScanner.toGrid(mc.world, box)) > 0) {
                        v = new CommandValidator.Result(safe, v.rejected(), v.bounds(), v.editCount(), v.blockFixes());
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("[AI WorldEdit] Gravity check skipped: " + e);
            }
        }
        if (!v.blockFixes().isEmpty()) {
            List<String> shown = new java.util.ArrayList<>();
            for (var e : v.blockFixes().entrySet()) {
                if (shown.size() >= 6) break;
                shown.add(e.getKey() + " -> " + e.getValue());
            }
            source.sendFeedback(Text.literal("[AI] Fixed invalid block names: " + String.join(", ", shown)
                + (v.blockFixes().size() > 6 ? " (+" + (v.blockFixes().size() - 6) + " more)" : ""))
                .formatted(Formatting.GOLD));
        }
        if (v.commands().isEmpty()) {
            reportRejected(source, v);
            return null;
        }
        return new Pending(label, frame, v, components, result.commands(), isEdit, result.references());
    }

    private static void handleEditResult(FabricClientCommandSource source, String label,
                                         BuildPlanner.Frame frame, AiClient.AiResult result) {
        if (!result.message().isBlank()) {
            source.sendFeedback(Text.literal("[AI] " + result.message()).formatted(Formatting.LIGHT_PURPLE));
        }
        if (result.components().isEmpty() && result.commands().isEmpty()) return;
        Pending p = toPending(source, label, frame, result, true);
        if (p != null) offerOrRun(source, p);
    }

    private static boolean checkIdle(FabricClientCommandSource source) {
        if (busy) {
            source.sendError(Text.literal("[AI] Still waiting on the last request. /aistop to cancel it."));
            return false;
        }
        if (CommandExecutor.isRunning()) {
            source.sendError(Text.literal("[AI] A build is still running. /aistop to stop it."));
            return false;
        }
        return true;
    }

    /** Runs an AI call off-thread and delivers the result on the client thread, unless stopped. */
    private static void run(FabricClientCommandSource source, Supplier<AiClient.AiResult> call,
                            java.util.function.Consumer<AiClient.AiResult> onResult) {
        int gen = GENERATION.get();
        busy = true;
        CompletableFuture.supplyAsync(call, WORKER).whenComplete((result, error) ->
            MinecraftClient.getInstance().execute(() -> {
                if (gen != GENERATION.get()) return; // /aistop was used
                busy = false;
                if (error != null) {
                    source.sendError(Text.literal("[AI] " + rootMessage(error)));
                    return;
                }
                if (result == null) return;
                try {
                    onResult.accept(result);
                } catch (Throwable t) {
                    reportInternalError(MinecraftClient.getInstance(), t);
                }
            }));
    }

    private static void offerOrRun(FabricClientCommandSource source, Pending p) {
        reportRejected(source, p.validated());
        if (!Config.get().confirmBeforeBuild) {
            start(source, p);
            return;
        }
        pending = p;
        source.sendFeedback(Text.literal("[AI] Plan: " + p.components().size() + " parts, "
            + p.validated().commands().size() + " commands, area "
            + p.validated().bounds() + ". Outline shown with particles.").formatted(Formatting.AQUA));
        source.sendFeedback(Text.literal("[AI] /aiconfirm to build, /aistop to discard.")
            .formatted(Formatting.YELLOW));
    }

    private static void start(FabricClientCommandSource source, Pending p) {
        List<String> cmds = p.validated().commands();
        CommandExecutor.startBuild(cmds);
        saveCommandLog(p.description(), cmds);
        AiClient.BuildRecord last = AiClient.getLastBuild();
        if (p.isEdit() && last != null) {
            // Keep the original plan as context and append the edit's components.
            JsonArray plan = JsonParser.parseString(last.planJson()).getAsJsonArray();
            plan.addAll(p.components());
            List<String> raw = new java.util.ArrayList<>(last.rawCommands());
            raw.addAll(p.rawCommands());
            List<String> executed = new java.util.ArrayList<>(last.executed());
            executed.addAll(cmds);
            AiClient.recordBuild(new AiClient.BuildRecord(last.description() + " | " + p.description(),
                last.anchor(), last.facing(), last.bounds(), plan.toString(), raw, executed,
                union(last.box(), p.validated().bounds().padded(2, 2)), last.references()));
        } else {
            AiClient.recordBuild(new AiClient.BuildRecord(p.description(), p.frame().anchor, p.frame().facing,
                p.validated().bounds().toString(), p.components().toString(), p.rawCommands(),
                new java.util.ArrayList<>(cmds), p.validated().bounds().padded(2, 2), p.references()));
            if (Config.get().autoReviewAfterBuild) {
                CommandExecutor.onFinished = () -> {
                    autoReviewSource = source;
                    autoReviewCountdown = 40; // let the world update for ~2 seconds first
                };
            }
        }
        source.sendFeedback(Text.literal("[AI] Building " + cmds.size() + " commands. /aistop stops, /aiundo reverts.")
            .formatted(Formatting.AQUA));
    }

    /** Writes the exact commands of the latest build to config/aiworldedit/last-build.txt for troubleshooting. */
    private static void saveCommandLog(String description, List<String> cmds) {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aiworldedit");
            java.nio.file.Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("# ").append(description).append('\n');
            for (int i = 0; i < cmds.size(); i++) sb.append(i + 1).append(": ").append(cmds.get(i)).append('\n');
            java.nio.file.Files.writeString(dir.resolve("last-build.txt"), sb.toString());
        } catch (Exception ignored) {
            // troubleshooting aid only
        }
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
            Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
    }

    private static void reportRejected(FabricClientCommandSource source, CommandValidator.Result v) {
        List<String> r = v.rejected();
        if (r.isEmpty()) return;
        source.sendFeedback(Text.literal("[AI] Skipped " + r.size() + " unsafe/invalid command(s):")
            .formatted(Formatting.GOLD));
        for (int i = 0; i < Math.min(3, r.size()); i++) {
            String s = r.get(i);
            source.sendFeedback(Text.literal("  " + (s.length() > 120 ? s.substring(0, 120) + "..." : s))
                .formatted(Formatting.GRAY));
        }
    }

    /** The block you're looking at (up to crosshairRange), or your feet as a fallback. */
    private static BlockPos pickAnchor(MinecraftClient mc, ClientPlayerEntity player) {
        Config cfg = Config.get();
        if (cfg.anchorAtCrosshair) {
            Vec3d eye = player.getCameraPosVec(1.0f);
            Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(cfg.crosshairRange));
            HitResult hit = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                Direction side = bhr.getSide();
                // Looking at the ground -> build on top of it. Looking at a wall -> build in front of it.
                return side == Direction.UP ? bhr.getBlockPos().up() : bhr.getBlockPos().offset(side);
            }
        }
        return player.getBlockPos();
    }

    private static void drawOutline(MinecraftClient mc, CommandValidator.Box b) {
        if (b.isEmpty() || mc.world == null) return;
        double x0 = b.minX, y0 = b.minY, z0 = b.minZ;
        double x1 = b.maxX + 1, y1 = b.maxY + 1, z1 = b.maxZ + 1;
        double perimeter = 4.0 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(1.0, perimeter / 400.0); // cap particles per draw
        double[][] edges = {
            {x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
            {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
            {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (double[] e : edges) {
            double len = Math.abs(e[3] - e[0]) + Math.abs(e[4] - e[1]) + Math.abs(e[5] - e[2]);
            int n = (int) Math.max(1, len / step);
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                mc.world.addImportantParticleClient(ParticleTypes.END_ROD,
                    e[0] + (e[3] - e[0]) * t, e[1] + (e[4] - e[1]) * t, e[2] + (e[5] - e[2]) * t, 0, 0, 0);
            }
        }
    }

    /**
     * Since 1.21.5 screenshots are delivered to a callback (the GPU copy finishes later),
     * so the review continues from inside the callback.
     */
    private static void captureScreenshot(MinecraftClient mc) {
        try {
            ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), img -> finishScreenshot(mc, img));
        } catch (Throwable t) {
            System.err.println("[AI WorldEdit] Screenshot failed: " + t);
            finishScreenshot(mc, null);
        }
    }

    private static void finishScreenshot(MinecraftClient mc, NativeImage img) {
        Path tmp = null;
        try {
            if (img != null) {
                tmp = Files.createTempFile("aiworldedit-", ".png");
                img.writeTo(tmp);
                screenshotPng = Files.readAllBytes(tmp);
            } else {
                screenshotPng = null;
            }
        } catch (Throwable t) {
            screenshotPng = null;
            System.err.println("[AI WorldEdit] Screenshot failed: " + t);
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) { }
            }
            mc.options.hudHidden = hudWasHidden;
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            }
        }
        Runnable r = afterScreenshot;
        afterScreenshot = null;
        if (r != null) {
            mc.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    busy = false;
                    reportInternalError(mc, t);
                }
            });
        }
    }

    private static void reportInternalError(MinecraftClient mc, Throwable t) {
        System.err.println("[AI WorldEdit] Internal error:");
        t.printStackTrace();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal("[AI] Internal error: " + t
                + " (details in logs/latest.log)").formatted(Formatting.RED), false);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
