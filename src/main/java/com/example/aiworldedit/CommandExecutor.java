package com.example.aiworldedit;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Sends queued WorldEdit commands a few ticks apart, shows progress in the
 * action bar, and remembers how many edits the last build made for /aiundo.
 * All methods run on the client thread.
 */
public final class CommandExecutor {
    private CommandExecutor() {}

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static int cooldown = 0;
    private static int total = 0;
    private static int sent = 0;
    /** Edits sent by the most recent build (including a partially stopped one). */
    private static int lastBuildEdits = 0;
    /** The selection WorldEdit has (to give big edits a little more time). */
    private static int[] sel1 = null, sel2 = null;
    /** Called once when a build's queue empties (not when stopped). */
    public static Runnable onFinished = null;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (QUEUE.isEmpty()) return;
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            ClientPlayerEntity player = client.player;
            if (player == null) return;
            ClientPlayNetworkHandler handler = player.networkHandler;
            if (handler == null) return;

            // Selection commands cost nothing: send them in the same tick as the edit that follows.
            String cmd;
            int guard = 0;
            do {
                cmd = QUEUE.poll(); // stored with its leading "//"
                if (cmd == null) break;
                handler.sendChatCommand(cmd.substring(1)); // "//set" -> "/set"
                if (cmd.startsWith("//pos1 ")) sel1 = coords(cmd);
                else if (cmd.startsWith("//pos2 ")) sel2 = coords(cmd);
                if (CommandValidator.isEdit(cmd)) lastBuildEdits++;
                sent++;
            } while (Config.get().fastSelections && !CommandValidator.isEdit(cmd) && !QUEUE.isEmpty() && ++guard < 4);
            // Big edits get a few extra ticks so the server (lighting, block updates) and the client keep up.
            int extra = 0;
            if (CommandValidator.isEdit(cmd) && sel1 != null && sel2 != null) {
                long vol = (long) (Math.abs(sel1[0] - sel2[0]) + 1) * (Math.abs(sel1[1] - sel2[1]) + 1) * (Math.abs(sel1[2] - sel2[2]) + 1);
                extra = (int) Math.min(10, vol / 4000);
            }
            cooldown = Math.max(0, Config.get().ticksBetweenCommands) + extra;

            if (QUEUE.isEmpty()) {
                player.sendMessage(Text.literal("[AI] Build finished (" + sent + " commands). /aiundo reverts it.")
                    .formatted(Formatting.GREEN), true);
                total = 0;
                sent = 0;
                Runnable r = onFinished;
                onFinished = null;
                if (r != null) r.run();
            } else {
                player.sendMessage(Text.literal("[AI] Building " + sent + "/" + total)
                    .formatted(Formatting.AQUA), true);
            }
        });

        // Don't run leftover commands in the next world/server you join.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CommandExecutor::clear));
    }

    /** Starts a new build. Commands must already be validated and keep their leading "//". */
    public static void startBuild(List<String> commands) {
        QUEUE.clear();
        lastBuildEdits = 0;
        boolean placement = Config.get().usePlacementPos1;
        if (placement) QUEUE.add("//placement pos1");
        QUEUE.addAll(commands);
        if (placement) QUEUE.add("//placement player");
        total = QUEUE.size();
        sent = 0;
        cooldown = 0;
    }

    private static int[] coords(String cmd) {
        String[] v = cmd.substring(7).trim().split(",");
        if (v.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void clear() {
        onFinished = null;
        QUEUE.clear();
        cooldown = 0;
        total = 0;
        sent = 0;
    }

    /** Stops a running build and restores normal WorldEdit placement. */
    public static void stop(ClientPlayerEntity player) {
        boolean wasRunning = !QUEUE.isEmpty();
        clear();
        if (wasRunning && Config.get().usePlacementPos1 && player != null && player.networkHandler != null) {
            player.networkHandler.sendChatCommand("/placement player");
        }
    }

    public static boolean isRunning() {
        return !QUEUE.isEmpty();
    }

    /** Returns how many edits will be undone, or 0 if there is nothing to undo. */
    public static int undoLastBuild(ClientPlayerEntity player) {
        stop(player);
        int n = lastBuildEdits;
        if (n <= 0 || player == null || player.networkHandler == null) return 0;
        player.networkHandler.sendChatCommand("/undo " + n);
        lastBuildEdits = 0;
        return n;
    }
}
