package com.pebbles_boon.metalrender.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

public final class MetalRenderCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    literal("metalrender")

                            .then(literal("help").executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("status").executes(ctx -> {
                                sendStatus(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("cache")
                                    .then(literal("clear").executes(ctx -> {
                                        cacheClear(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("reload").executes(ctx -> {
                                reloadWorld(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("restart").executes(ctx -> {
                                restart(ctx.getSource());
                                return 1;
                            }))

                            .then(literal("lod")
                                    .then(literal("enable").executes(ctx -> {
                                        MetalRenderConfig.setLodEnabled(true);
                                        invalidateAllMeshes();
                                        msg(ctx.getSource(), "§aLOD system enabled. Rebuilding all meshes...");
                                        return 1;
                                    }))
                                    .then(literal("disable").executes(ctx -> {
                                        MetalRenderConfig.setLodEnabled(false);
                                        invalidateAllMeshes();
                                        msg(ctx.getSource(), "§cLOD system disabled. Rebuilding all meshes at LOD0...");
                                        return 1;
                                    }))
                                    .then(literal("reset").executes(ctx -> {
                                        MetalRenderConfig.setLod1Distance(8);
                                        MetalRenderConfig.setLod2Distance(16);
                                        MetalRenderConfig.setLod3Distance(24);
                                        MetalRenderConfig.setLod4Distance(32);
                                        invalidateAllMeshes();
                                        msg(ctx.getSource(),
                                                "§eLOD distances reset to defaults (4/8/12/16). Rebuilding...");
                                        return 1;
                                    }))
                                    .then(literal("threshold")
                                            .then(argument("distance",
                                                    IntegerArgumentType.integer(1, 100))
                                                    .executes(ctx -> {
                                                        int d = IntegerArgumentType.getInteger(ctx,
                                                                "distance");
                                                        MetalRenderConfig.setLod1Distance(d);
                                                        invalidateAllMeshes();
                                                        msg(ctx.getSource(),
                                                                "§eLOD near threshold set to " + d
                                                                        + " chunks. Rebuilding...");
                                                        return 1;
                                                    }))))

                            .then(literal("config")
                                    .then(literal("save").executes(ctx -> {
                                        MetalRenderConfig cfg = MetalRenderClient.getConfig();
                                        if (cfg != null)
                                            cfg.save();
                                        msg(ctx.getSource(), "§aConfig saved to disk.");
                                        return 1;
                                    }))
                                    .then(literal("reload").executes(ctx -> {

                                        msg(ctx.getSource(),
                                                "§eConfig reloaded. Some changes may require /metalrender restart.");
                                        return 1;
                                    }))
                                    .then(literal("reset").executes(ctx -> {
                                        resetConfig(ctx.getSource());
                                        return 1;
                                    })))

                            .then(literal("performance")
                                    .then(literal("reset").executes(ctx -> {
                                        MetalRenderConfig.setResolutionScale(1.0f);
                                        msg(ctx.getSource(),
                                                "§ePerformance settings reset to defaults.");
                                        return 1;
                                    })))

                            .executes(ctx -> {
                                sendHelp(ctx.getSource());
                                return 1;
                            }));
        });
        MetalLogger.info("MetalRender commands registered.");
    }

    private static void msg(FabricClientCommandSource src, String text) {
        src.sendFeedback(Text.literal(text));
    }

    private static void sendHelp(FabricClientCommandSource src) {
        msg(src, "§6§l--- MetalRender Commands ---");
        msg(src, "§e/metalrender status §7- Show renderer status");
        msg(src, "§e/metalrender help §7- This help menu");
        msg(src, "§e/metalrender cache clear §7- Clear cache & restart renderer");
        msg(src, "§e/metalrender reload §7- Reload world renderer");
        msg(src, "§e/metalrender restart §7- Full renderer restart");
        msg(src, "§e/metalrender lod enable|disable §7- Toggle LOD system");
        msg(src, "§e/metalrender lod threshold <n> §7- Set LOD near threshold");
        msg(src, "§e/metalrender lod reset §7- Reset LOD to defaults");
        msg(src, "§e/metalrender config save|reload|reset §7- Config management");
        msg(src, "§e/metalrender performance reset §7- Reset perf settings");
    }

    private static void sendStatus(FabricClientCommandSource src) {
        boolean available = MetalRenderClient.isMetalAvailable();
        MetalRenderConfig cfg = MetalRenderClient.getConfig();
        boolean enabled = cfg != null && cfg.enableMetalRendering;

        msg(src, "§6§l--- MetalRender Status ---");
        msg(src, "§7Enabled: " + (enabled ? "§aYes" : "§cNo"));
        msg(src, "§7Hardware: "
                + (available ? "§a" + MetalHardwareChecker.getDeviceName() : "§cUnavailable"));
        msg(src, "§7LOD: " + (MetalRenderConfig.lodEnabled() ? "§aEnabled" : "§cDisabled")
                + " §7(L1=" + MetalRenderConfig.lod1Distance()
                + " L2=" + MetalRenderConfig.lod2Distance()
                + " L3=" + MetalRenderConfig.lod3Distance()
                + " L4=" + MetalRenderConfig.lod4Distance() + ")");
        msg(src, "§7Resolution scale: §f" + String.format("%.2fx", MetalRenderConfig.resolutionScale()));
        msg(src, "§7Frustum culling: "
                + (MetalRenderConfig.aggressiveFrustumCulling() ? "§aAggressive" : "§eNormal"));

        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            msg(src, "§7Mesh count: §f" + wr.getChunkMesher().getMeshCount());
            msg(src, "§7Pending: §f" + wr.getChunkMesher().getPendingCount());
        }
    }

    private static void cacheClear(FabricClientCommandSource src) {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
            msg(src, "§aCache cleared. Renderer restarting...");
        } else {
            msg(src, "§cWorld renderer not available.");
        }
    }

    private static void reloadWorld(FabricClientCommandSource src) {
        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc != null && mc.worldRenderer != null) {
                mc.worldRenderer.reload();
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aWorld renderer reloaded.");
        } catch (Exception e) {
            msg(src, "§cReload failed: " + e.getMessage());
        }
    }

    private static void restart(FabricClientCommandSource src) {
        try {
            MetalRenderConfig cfg = MetalRenderClient.getConfig();
            if (cfg != null) {
                cfg.enableMetalRendering = false;
                cfg.enableMetalRendering = true;
            }
            MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
            if (wr != null) {
                wr.getChunkMesher().clearAllMeshes();
            }
            msg(src, "§aMetalRender restarted.");
        } catch (Exception e) {
            msg(src, "§cRestart failed: " + e.getMessage());
        }
    }

    private static void resetConfig(FabricClientCommandSource src) {
        MetalRenderConfig.setLodEnabled(true);
        MetalRenderConfig.setLod1Distance(8);
        MetalRenderConfig.setLod2Distance(16);
        MetalRenderConfig.setLod3Distance(24);
        MetalRenderConfig.setLod4Distance(32);
        MetalRenderConfig.setResolutionScale(1.0f);
        MetalRenderConfig.setAggressiveFrustumCulling(false);
        MetalRenderConfig.setOcclusionCulling(false);
        MetalRenderConfig.setMirrorUploads(false);
        invalidateAllMeshes();
        msg(src, "§eAll settings reset to defaults. Rebuilding...");
    }

    private static void invalidateAllMeshes() {
        MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
        if (wr != null) {
            wr.getChunkMesher().clearAllMeshes();
        }
    }

    private MetalRenderCommands() {
    }
}
