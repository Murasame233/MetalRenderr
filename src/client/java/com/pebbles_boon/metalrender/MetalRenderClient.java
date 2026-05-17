package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.command.MetalRenderCommands;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.unified.MetalRenderCoordinator;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.sodium.backend.SodiumMetalInterface;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class MetalRenderClient implements ClientModInitializer {
  private static final int FPS_PRIORITY_SIMULATION_DISTANCE = 5;
  private static MetalRenderClient instance;
  private static MetalRenderer renderer;
  private static MetalRenderConfig config;
  private static MetalRenderCoordinator coordinator;
  private static MeshShaderBackend meshShaderBackend;
  private static SodiumMetalInterface sodiumInterface;
  private static MetalWorldRenderer worldRenderer;
<<<<<<< HEAD
  private static boolean metalAvailable = false;
=======
  private static boolean metalUp;
  private static boolean cfgWasOn;
  private static boolean cfgSyncPending;
  private static boolean runtimeApplyPending;
  private static boolean levelRendererRefreshPending;
  private static boolean worldRendererRefreshPending;
  private static boolean debugEntryStatusSet;
>>>>>>> e028af4 (checkpoint, WIP)

  @Override
  public void onInitializeClient() {
    if (StartupBlocker.shouldBlockStartup()) {
      return;
    }
    instance = this;
    MetalLogger.info("MetalRender can render");
    config = MetalRenderConfig.load();
    if (MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("debug logging is logging");
    }
    MetalRenderCommands.register();
    if (!config.enableMetalRendering) {
<<<<<<< HEAD
      MetalLogger.info("MetalRender was killed by [user] using config menu");
      return;
=======
      MetalLogger.info("metalrender was sniped");
>>>>>>> e028af4 (checkpoint, WIP)
    }




    ClientTickEvents.START_CLIENT_TICK.register(client -> {
<<<<<<< HEAD
      if (renderer == null) {
        initializeMetal(client);
=======
      var mc = Minecraft.getInstance();
      if (!debugEntryStatusSet && mc != null) {
        MetalDebugEntry.show(mc);
        debugEntryStatusSet = true;
      }
      if (cfgSyncPending) {
        cfgSyncPending = false;
        syncCfg(mc);
      }
      applyDeferredRuntimeChanges(mc);
      applyFpsPriorityMode(mc);
      syncCfg(mc);
      if (config != null && config.enableMetalRendering && renderer == null && mc != null) {
        initMetal(mc);
>>>>>>> e028af4 (checkpoint, WIP)
      }
    });
  }

<<<<<<< HEAD
  private static void initializeMetal(net.minecraft.client.MinecraftClient client) {
=======
  public static void requestDeferredApply(boolean requestCfgSync,
      boolean refreshLevelRenderer,
      boolean refreshWorldRenderer) {
    runtimeApplyPending = true;
    cfgSyncPending |= requestCfgSync;
    levelRendererRefreshPending |= refreshLevelRenderer;
    worldRendererRefreshPending |= refreshWorldRenderer;
  }

  private static void applyDeferredRuntimeChanges(Minecraft mc) {
    if (!runtimeApplyPending || config == null) {
      return;
    }

    runtimeApplyPending = false;
    if (NativeBridge.isLibLoaded()) {
      boolean useArgBufs = config.enableArgumentBuffers || config.enableIndirectCommandBuffers;
      NativeBridge.nSetFeatureFlags(
          config.enableIndirectCommandBuffers,
          config.enableMeshShaders,
          useArgBufs,
          config.enableProgrammableBlending,
          config.enableMemorylessTargets);
    }

    MetalWorldRenderer wr = worldRenderer;
    if (wr != null) {
      wr.applyFeatureConfig(config);
    }

    if (levelRendererRefreshPending && mc != null && mc.levelRenderer != null) {
      mc.levelRenderer.allChanged();
    }
    levelRendererRefreshPending = false;

    if (worldRendererRefreshPending) {
      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nFlushFrames();
      }
      if (wr != null) {
        wr.onConfigScreenClosed();
      }
    }
    worldRendererRefreshPending = false;
  }

  public static void syncCfg(Minecraft mc) {
    boolean cfgOn = config != null && config.enableMetalRendering;
    if (cfgOn == cfgWasOn || mc == null) {
      return;
    }
    cfgWasOn = cfgOn;

    if (!cfgOn) {
      if (worldRenderer != null) {
        drainRenderer();
        worldRenderer.onWorldUnload();
        worldRenderer = null;
      }
      return;
    }

    if (renderer == null || !metalUp) {
      initMetal(mc);
    } else if (worldRenderer == null) {
      worldRenderer = new MetalWorldRenderer();
    }

    if (worldRenderer != null && mc.level != null) {
      worldRenderer.onWorldLoad();
      worldRenderer.onConfigScreenClosed();
    }
  }

  private static void applyFpsPriorityMode(Minecraft mc) {
    if (mc == null || mc.options == null || config == null || !config.prioritizeFpsOverTps) {
      return;
    }
    try {
      if (mc.options.simulationDistance().get() > FPS_PRIORITY_SIMULATION_DISTANCE) {
        mc.options.simulationDistance().set(FPS_PRIORITY_SIMULATION_DISTANCE);
        mc.options.save();
      }
    } catch (Exception ignored) {
    }
  }

  private static void drainRenderer() {
    if (renderer == null || !renderer.isAvailable() || !NativeBridge.isLibLoaded()) {
      return;
    }
    try {
      NativeBridge.nFlushFrames();
      NativeBridge.nWaitForRender(renderer.getHandle());
    } catch (Throwable t) {
      MetalLogger.warn("failed to drain Metal renderer before lifecycle change: %s", t.getMessage());
    }
  }

  public static void openSettingsScreen(Minecraft mc) {
    if (mc == null) {
      return;
    }
    try {
      mc.execute(() -> mc.setScreen(new MetalRenderSettingsScreen(mc.screen)));
      MetalLogger.info("Opened MetalRender settings screen.");
    } catch (Exception e) {
      MetalLogger.warn("failed to open MetalRender settings screen: %s", e.getMessage());
    }
  }

  private static void initMetal(Minecraft mc) {
>>>>>>> e028af4 (checkpoint, WIP)
    try {
      NativeBridge.loadLibrary();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.error("got lost finding non-existent native library", e);
      return;
    }
    try {
      if (MetalHardwareChecker.isMetalSupported()) {
        renderer = new MetalRenderer();


        int w = 0, h = 0;
        if (client.getWindow() != null) {
          w = client.getWindow().getFramebufferWidth();
          h = client.getWindow().getFramebufferHeight();
        }
        renderer.init(w, h);
        metalAvailable = renderer.isAvailable();
        if (metalAvailable) {
          coordinator = new MetalRenderCoordinator();
          coordinator.initialize();
          worldRenderer = new MetalWorldRenderer();
          meshShaderBackend = new MeshShaderBackend();
          meshShaderBackend.initialize();
          MetalLogger.info("Metal ready: " +
              MetalHardwareChecker.getDeviceName());
        }
      } else {
        MetalLogger.warn("computer lazy cant even get metal");
      }
    } catch (Exception e) {
      MetalLogger.error("Failure", e);
      metalAvailable = false;
    }
  }

  public static MetalRenderClient getInstance() {
    return instance;
  }

  public static MetalRenderer getRenderer() {
    return renderer;
  }

  public static MetalRenderConfig getConfig() {
    return config;
  }

  public static MetalRenderCoordinator getCoordinator() {
    return coordinator;
  }

  public static MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public static boolean isMetalAvailable() {
    return metalAvailable;
  }

  public static boolean isEnabled() {
    return metalAvailable && renderer != null && renderer.isAvailable();
  }

  public static MetalWorldRenderer getWorldRenderer() {
    return worldRenderer;
  }

  public static SodiumMetalInterface getSodiumInterface() {
    if (sodiumInterface == null) {
      sodiumInterface = new SodiumMetalInterface();
    }
    return sodiumInterface;
  }

  public static boolean isSodiumLoaded() {
    try {
      return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(
          "sodium");
    } catch (Exception e) {
      return false;
    }
  }
}
