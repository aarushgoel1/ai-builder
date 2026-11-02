package com.example.aiworldedit;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class AiWorldEditClient implements ClientModInitializer {

    private static int providerChanged(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src,
                                       java.util.function.Consumer<Config> change) {
        String err = Config.update(change);
        if (err != null) src.sendError(Text.literal("[AI] " + err));
        src.sendFeedback(Text.literal("[AI] Now using " + Config.get().activeModel() + ".")
            .formatted(Formatting.GREEN));
        return 1;
    }

    @Override
    public void onInitializeClient() {
        String err = Config.load();
        if (err != null) System.err.println("[AI WorldEdit] " + err);

        CommandExecutor.init();
        BuildOrchestrator.init();
        BuildLibrary.stateTableSource = WorldScanner::stateTable;
        BuildStudy.colorSource = WorldScanner::mapColors;
        try {
            for (String m : BuildLibrary.load()) System.out.println("[AI WorldEdit] " + m);
        } catch (Throwable t) {
            System.err.println("[AI WorldEdit] Couldn't load saved builds: " + t);
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("aibuild")
                .then(argument("description", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        BuildOrchestrator.handleBuild(ctx.getSource(),
                            StringArgumentType.getString(ctx, "description"));
                        return 1;
                    })));

            dispatcher.register(literal("aichat")
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        BuildOrchestrator.handleChat(ctx.getSource(),
                            StringArgumentType.getString(ctx, "message"));
                        return 1;
                    })));

            // Screenshot -> AI critique -> fix commands. Optional notes.
            dispatcher.register(literal("aifix")
                .executes(ctx -> {
                    BuildOrchestrator.handleFix(ctx.getSource(), null);
                    return 1;
                })
                .then(argument("notes", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        BuildOrchestrator.handleFix(ctx.getSource(),
                            StringArgumentType.getString(ctx, "notes"));
                        return 1;
                    })));

            // /airef <https link | file in config/aiworldedit/references> | list | clear | on | off
            dispatcher.register(literal("airef")
                .then(argument("source", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        BuildOrchestrator.addReference(ctx.getSource(), StringArgumentType.getString(ctx, "source"));
                        return 1;
                    })));

            // /ailib                      -> list saved builds
            // /ailib place <name> [90|180|270], /ailib info <name>, /ailib reload
            dispatcher.register(literal("ailib")
                .executes(ctx -> {
                    BuildOrchestrator.library(ctx.getSource(), "");
                    return 1;
                })
                .then(argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        BuildOrchestrator.library(ctx.getSource(), StringArgumentType.getString(ctx, "args"));
                        return 1;
                    })));

            dispatcher.register(literal("aiconfirm").executes(ctx -> {
                BuildOrchestrator.confirm(ctx.getSource());
                return 1;
            }));

            dispatcher.register(literal("aistop").executes(ctx -> {
                BuildOrchestrator.stop(ctx.getSource());
                return 1;
            }));

            dispatcher.register(literal("aiundo").executes(ctx -> {
                BuildOrchestrator.undo(ctx.getSource());
                return 1;
            }));

            dispatcher.register(literal("aiforget").executes(ctx -> {
                AiClient.clearMemory();
                ctx.getSource().sendFeedback(Text.literal("[AI] Conversation and last-build memory cleared.")
                    .formatted(Formatting.YELLOW));
                return 1;
            }));

            // /aiprovider            -> show what's in use
            // /aiprovider gemini     -> cloud
            // /aiprovider ollama [model] -> local, optionally switching the model
            dispatcher.register(literal("aiprovider")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("[AI] Using " + Config.get().activeModel()
                        + ". /aiprovider gemini | claude [model] | ollama [model]").formatted(Formatting.AQUA));
                    return 1;
                })
                .then(literal("gemini").executes(ctx -> {
                    return providerChanged(ctx.getSource(), c -> c.provider = "gemini");
                }))
                .then(literal("claude")
                    .executes(ctx -> {
                        return providerChanged(ctx.getSource(), c -> c.provider = "claude");
                    })
                    .then(argument("model", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String m = StringArgumentType.getString(ctx, "model").trim();
                            return providerChanged(ctx.getSource(), c -> { c.provider = "claude"; c.claudeModel = m; });
                        })))
                .then(literal("ollama")
                    .executes(ctx -> {
                        return providerChanged(ctx.getSource(), c -> c.provider = "ollama");
                    })
                    .then(argument("model", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String m = StringArgumentType.getString(ctx, "model").trim();
                            return providerChanged(ctx.getSource(), c -> { c.provider = "ollama"; c.ollamaModel = m; });
                        }))));

            dispatcher.register(literal("aireload").executes(ctx -> {
                String e = Config.load();
                if (e != null) ctx.getSource().sendError(Text.literal("[AI] " + e));
                else ctx.getSource().sendFeedback(Text.literal("[AI] Config reloaded (using "
                    + Config.get().activeModel() + ").").formatted(Formatting.GREEN));
                return 1;
            }));
        });
    }
}
