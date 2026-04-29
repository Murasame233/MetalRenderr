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
  private static MetalRenderClient instance;
  private static MetalRenderer renderer;
  private static MetalRenderConfig config;
  private static MetalRenderCoordinator coordinator;
  private static MeshShaderBackend meshShaderBackend;
  private static SodiumMetalInterface sodiumInterface;
  private static MetalWorldRenderer worldRenderer;
  private static boolean metalAvailable = false;

  @Override
  public void onInitializeClient() {
    instance = this;
    MetalLogger.info("MetalRender v0.1.7ing...");
    config = MetalRenderConfig.load();
    if (MetalRenderConfig.isDeepDebugActive()) {
      MetalLogger.info("Deep Debug Mode active for this run; detailed telemetry will be written to latest.log");
    }
    MetalRenderCommands.register();
    if (!config.enableMetalRendering) {
      MetalLogger.info("MetalRender was killed by [user] using config menu");
      return;
    }




    ClientTickEvents.START_CLIENT_TICK.register(client -> {
      if (renderer == null) {
        initializeMetal(client);
      }
    });
  }

  private static void initializeMetal(net.minecraft.client.MinecraftClient client) {
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
