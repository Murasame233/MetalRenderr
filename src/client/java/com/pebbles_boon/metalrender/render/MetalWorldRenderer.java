package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.performance.PerformanceController;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.render.chunk.MetalChunkContext;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class MetalWorldRenderer {
  private static MetalWorldRenderer instance;
  private final FrustumCuller frustumCuller;
  private final MetalEntityRenderer entityRenderer;
  private final MetalParticleRenderer particleRenderer;
  private final CustomChunkMesher chunkMesher;
  private final MetalTextureManager textureManager;
  private final IOSurfaceBlitter ioSurfaceBlitter;
  private final Matrix4f projectionMatrix;
  private final Matrix4f modelViewMatrix;
  private final Vector3f reusableCamPos = new Vector3f();
  private final net.minecraft.util.math.BlockPos.Mutable reusableCamBlockPos = new net.minecraft.util.math.BlockPos.Mutable();
  private final Matrix4f reusableMetalProj = new Matrix4f();
  private final float[] reusableProjArr = new float[16];
  private final float[] reusableMvArr = new float[16];
  private boolean worldLoaded;
  private boolean renderingActive;
  private boolean texturesReady;
  private int frameCount;
  private int maxDrawnChunksPerFrame = 65536;
  private int lastDrawnChunkCount;
  private long lastDiagLogMs;
  private long outlineBufferHandle;
  private volatile long watchdogHeartbeat;
  private volatile boolean watchdogRunning;
  private Thread watchdogThread;
  private long jPruneAcc = 0, jBuildAcc = 0, jLodAcc = 0;
  private long lastPruneNs = 0, lastBuildNs = 0, lastLodNs = 0, lastPrepareTotalNs = 0;
  private int jProfCount = 0;
  private volatile boolean loadingMode = false;
  private volatile int loadingModePendingCount = 0;
  private volatile int loadingModeMeshCount = 0;
  private volatile int loadingModeNearestPendingRing = Integer.MAX_VALUE;

  private int loadingModeExitCooldown = 0;
  private float[] batchDrawData;
  private float[] batchPackedData;
  private final float[] sortTmp = new float[7];
  private boolean gpuDrivenEnabled;
  private MeshShaderBackend meshShaderBackend;
  private ByteBuffer subChunkUploadBuffer;
  private ByteBuffer chunkUniformsBuffer;
  private int subChunkUploadCapacity = 4096;
  private final float[] viewProjMatrix = new float[16];
  private final float[] projMatrixFlat = new float[16];
  private final float[] modelViewFlat = new float[16];
  private final float[] cameraPosFloat = new float[4];
  private final float[] frustumPlanesFlat = new float[24];
  private final int[] gpuCullStats = new int[5];
  private int lastGPUVisibleCount;
  private long lastThermalLogMs;
  private long cachedInhouseHandle = 0;

  private int postLoadRebuildCountdown = -1;
  private static final int LIGHT_SOURCE_REFRESH_FRAMES = 6;

  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.particleRenderer = new MetalParticleRenderer();
    this.chunkMesher = new CustomChunkMesher();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long device = renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    this.textureManager = new MetalTextureManager(device);
    this.ioSurfaceBlitter = new IOSurfaceBlitter();
    this.projectionMatrix = new Matrix4f();
    this.modelViewMatrix = new Matrix4f();
    instance = this;
  }

  public static MetalWorldRenderer getInstance() {
    return instance;
  }

  public void onWorldLoad() {
    worldLoaded = true;
    cachedInhouseHandle = 0;
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer != null && renderer.isAvailable()) {
      MinecraftClient client = MinecraftClient.getInstance();
      int w = client.getWindow().getFramebufferWidth();
      int h = client.getWindow().getFramebufferHeight();
      if (w > 0 && h > 0) {
        renderer.resize(w, h);
      }
      chunkMesher.initialize(renderer.getBackend().getDeviceHandle());




      chunkMesher.invalidateUVCache();



      postLoadRebuildCountdown = 180;
      entityRenderer.setDeviceAndPipeline(
          renderer.getBackend().getDeviceHandle(), 0);
      particleRenderer.setDeviceAndPipeline(
          renderer.getBackend().getDeviceHandle());
      renderingActive = true;
      entityRenderer.setActive(true);
      particleRenderer.setActive(true);
      texturesReady = false;
      long handle = renderer.getBackend().getDeviceHandle();

      com.pebbles_boon.metalrender.config.MetalRenderConfig cfg = MetalRenderClient.getConfig();
      if (cfg.enableMeshShaders) {
        meshShaderBackend = new MeshShaderBackend();
        meshShaderBackend.initialize();
      }



      applyFeatureConfig(cfg);
      MetalLogger.info("Metal world rendering activated (" + w + "x" + h + ")");
      MetalLogger.info("World load feature state: ICB=%b MeshShaders=%b OIT=%b ArgBuf=%b",
          gpuDrivenEnabled,
          cfg.enableMeshShaders && MetalHardwareChecker.supportsMeshShaders(),
          cfg.enableProgrammableBlending,
          cfg.enableArgumentBuffers);
      startWatchdog();
    }
  }

  private volatile boolean watchdogSuspend = false;
  private volatile long watchdogLastFrameCount = -1;


  public void applyFeatureConfig(
      com.pebbles_boon.metalrender.config.MetalRenderConfig cfg) {

    boolean wantICB = cfg.enableIndirectCommandBuffers;
    gpuDrivenEnabled = wantICB;
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long handle = renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    if (handle != 0) {
      NativeBridge.nSetGPUDrivenEnabled(handle, wantICB);
    }
    if (wantICB && subChunkUploadBuffer == null) {
      subChunkUploadBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 48)
          .order(ByteOrder.nativeOrder());
      chunkUniformsBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 16)
          .order(ByteOrder.nativeOrder());
    }

    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetFeatureFlags(
          cfg.enableIndirectCommandBuffers,
          cfg.enableMeshShaders,
          cfg.enableArgumentBuffers,
          cfg.enableProgrammableBlending,
          cfg.enableMemorylessTargets);
    }

    boolean wantMesh = cfg.enableMeshShaders && MetalHardwareChecker.supportsMeshShaders();
    if (wantMesh && (meshShaderBackend == null)) {
      meshShaderBackend = new MeshShaderBackend();
      meshShaderBackend.initialize();
    } else if (!wantMesh && meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    MetalLogger.info("applyFeatureConfig: ICB=%b mesh=%b argBuf=%b OIT=%b memoryless=%b",
        cfg.enableIndirectCommandBuffers, cfg.enableMeshShaders,
        cfg.enableArgumentBuffers, cfg.enableProgrammableBlending,
        cfg.enableMemorylessTargets);
  }


  public void onConfigScreenClosed() {





    chunkMesher.invalidateUVCache();
    chunkMesher.markAllDirty();



    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nFlushDeferredDeletions();
    }




    pendingBuildSet.clear();
    sortedBuildList.clear();
    sortedListDirty = true;




    lastScanPlayerCX = Integer.MIN_VALUE;
    lastScanPlayerCZ = Integer.MIN_VALUE;
    lastScanRenderDist = -1;




    scanFrameCounter = 59;
    scanFrontierRing = 0;


    loadingMode = true;
    MetalLogger.info("onConfigScreenClosed: mesh cache cleared, pending queue reset, full rescan forced");
  }

  private void startWatchdog() {
    stopWatchdog();
    watchdogHeartbeat = System.nanoTime();
    watchdogRunning = true;
    watchdogThread = new Thread(() -> {
      final long WATCHDOG_TIMEOUT_NS = 5_000_000_000L;
      final long WARMUP_NS = 10_000_000_000L;
      final long MIN_RESET_INTERVAL_NS = 10_000_000_000L;
      long startTime = System.nanoTime();
      long lastResetTime = 0;
      long stuckSinceNs = 0;
      long stuckFrameCount = -1;
      while (watchdogRunning) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          break;
        }
        if (System.nanoTime() - startTime < WARMUP_NS)
          continue;
        if (!renderingActive || !worldLoaded || frameCount < 120)
          continue;
        long now = System.nanoTime();
        long currentFrame = frameCount;
        if (currentFrame == stuckFrameCount) {
          long stuckDuration = now - stuckSinceNs;
          if (stuckDuration > WATCHDOG_TIMEOUT_NS) {
            if (now - lastResetTime < MIN_RESET_INTERVAL_NS)
              continue;
            MetalLogger.warn("WATCHDOG: Frame stuck at %d for %dms — resetting GPU state",
                currentFrame, stuckDuration / 1_000_000);
            try {
              NativeBridge.nWatchdogReset();
            } catch (Exception ex) {
              MetalLogger.error("WATCHDOG: Reset failed: %s", ex.getMessage());
            }
            watchdogHeartbeat = now;
            lastResetTime = now;
            stuckFrameCount = -1;
          }
        } else {
          stuckFrameCount = currentFrame;
          stuckSinceNs = now;
        }
      }
    }, "MetalRender-Watchdog");
    watchdogThread.setDaemon(true);
    watchdogThread.setPriority(Thread.MAX_PRIORITY);
    watchdogThread.start();
  }

  private void stopWatchdog() {
    watchdogRunning = false;
    if (watchdogThread != null) {
      watchdogThread.interrupt();
      try {
        watchdogThread.join(100);
      } catch (InterruptedException ignored) {
      }
      watchdogThread = null;
    }
  }

  public void onWorldUnload() {
    stopWatchdog();
    worldLoaded = false;
    renderingActive = false;
    texturesReady = false;
    entityRenderer.shutdown();
    particleRenderer.shutdown();
    textureManager.destroy();
    MetalRenderer _wuRenderer = MetalRenderClient.getRenderer();
    if (_wuRenderer != null && _wuRenderer.isAvailable()) {
      try {
        NativeBridge.nWaitForRender(_wuRenderer.getHandle());
      } catch (Exception _ignored) {
          }
    }
    ioSurfaceBlitter.destroy();
    chunkMesher.clear();
    frameCount = 0;
    lightRefreshFrames.clear();
    lightRefreshScratch.clear();
    lastDrawnChunkCount = 0;
    if (meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    gpuDrivenEnabled = false;
    subChunkUploadBuffer = null;
    chunkUniformsBuffer = null;
  }

  public boolean shouldRenderWithMetal() {
    return worldLoaded && renderingActive &&
        MetalRenderClient.isMetalAvailable() &&
        MetalRenderClient.getConfig().enableMetalRendering;
  }

  public void prepareMeshes() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;




    if (postLoadRebuildCountdown > 0) {
      postLoadRebuildCountdown--;
      if (postLoadRebuildCountdown == 0) {
        chunkMesher.invalidateUVCache();
        chunkMesher.markAllDirty();
        sortedListDirty = true;
        MetalLogger.info("Post-load deferred rebuild: UV cache invalidated, all meshes marked dirty");
      }
    }
    processLightRefreshes();
    MinecraftClient client = MinecraftClient.getInstance();
    int w = client.getWindow().getFramebufferWidth();
    int h = client.getWindow().getFramebufferHeight();
    renderer.resize(w, h);
    if (!texturesReady && frameCount > 2) {
      textureManager.loadBlockAtlas();
      textureManager.loadLightmap();
      texturesReady = textureManager.isBlockAtlasLoaded() &&
          textureManager.isLightmapLoaded();
    } else if (texturesReady && textureManager.isUsingFallbackBlockAtlas()) {


      if (frameCount % 30 == 0) {
        boolean wasFallback = textureManager.isUsingFallbackBlockAtlas();
        textureManager.loadBlockAtlas();


        if (wasFallback && !textureManager.isUsingFallbackBlockAtlas()) {
          chunkMesher.invalidateUVCache();
          chunkMesher.markAllDirty();
          sortedListDirty = true;
          MetalLogger.info("Atlas loaded: fallback → real, UV cache invalidated, all meshes marked dirty");
        }
      }
    } else if (texturesReady && !textureManager.isUsingFallbackBlockAtlas()) {


      if (frameCount % 3 == 0) {
        textureManager.updateBlockAtlas();
      }
    }
    long now = System.currentTimeMillis();
    long diagInterval = chunkMesher.getMeshCount() < 2000 ? 1000 : 5000;
    if (MetalRenderConfig.isDeepDebugActive() && now - lastDiagLogMs > diagInterval) {
      lastDiagLogMs = now;
      com.pebbles_boon.metalrender.config.MetalRenderConfig diagCfg = MetalRenderClient.getConfig();
      boolean meshShadersNow = NativeBridge.isLibLoaded() && NativeBridge.nAreMeshShadersActive();
      MetalLogger.info(
          "DiagWorld: texturesReady=" + texturesReady +
              ", atlasFallback=" + textureManager.isUsingFallbackBlockAtlas() +
              ", meshCount=" + chunkMesher.getMeshCount() +
              " | FEATURES: ICB=" + gpuDrivenEnabled +
              "(cfg=" + (diagCfg != null && diagCfg.enableIndirectCommandBuffers) + ")" +
              " MeshShaders=" + meshShadersNow +
              " OIT=" + (diagCfg != null && diagCfg.enableProgrammableBlending) +
              " ArgBuf=" + (diagCfg != null && diagCfg.enableArgumentBuffers) +
              " | pending=" + pendingBuildSet.size() +
              " loadingMode=" + loadingMode);
    }
    Camera camera = client.gameRenderer.getCamera();
    reusableCamPos.set((float) camera.getCameraPos().x,
        (float) camera.getCameraPos().y,
        (float) camera.getCameraPos().z);
    if (MetalRenderClient.getConfig().enableMetalRendering) {
      boolean camStatic = isCameraStatic(camera);
      int meshCnt = chunkMesher.getMeshCount();
      int inflightCnt = chunkMesher.getPendingCount();
      int pendingQueued = pendingBuildSet.size();
      int pendingSz = pendingQueued + inflightCnt;
      boolean wasLoading = loadingMode;
      if (!loadingMode) {




        if (loadingModeExitCooldown > 0) {
          loadingModeExitCooldown--;
        } else {
          boolean hasNearGap = loadingModeNearestPendingRing <= 3;
          loadingMode = (pendingQueued > 50 && hasNearGap)
              || (meshCnt < 200 && frameCount > 30);
        }
      } else {




        int renderDistForExit = lastScanRenderDist > 0 ? lastScanRenderDist : 16;
        int expectedMeshes = Math.min(3000, renderDistForExit * renderDistForExit * 6);
        boolean closeRingsFilled = loadingModeNearestPendingRing > 3;
        boolean canExit = (pendingQueued < 5 && inflightCnt < 40 && meshCnt >= expectedMeshes)
            || (pendingQueued == 0 && inflightCnt < 10 && meshCnt > 50)
            || (closeRingsFilled && inflightCnt < 80 && meshCnt > 200);
        if (canExit) {
          loadingMode = false;




          chunkMesher.markAllDirty();
          sortedBuildList.clear();
          sortedListDirty = true;


          loadingModeExitCooldown = 300;



          scanFrameCounter = 60;
          MetalLogger.info(
              "Loading mode EXIT: meshes=%d nearestPendingRing=%d cooldown=300, forcing full scan",
              meshCnt, loadingModeNearestPendingRing);


          if (lastScanRenderDist > 0) {
            NativeBridge.nSetRenderDistance((lastScanRenderDist + 2) * 16);
          }
        }
      }
      if (!wasLoading && loadingMode && pendingQueued > 50) {

        MetalLogger.info("Loading mode ENTER: pending=%d meshes=%d inflight=%d", pendingQueued, meshCnt,
            inflightCnt);
      }
      chunkMesher.setLoadingModeThreadBudget(loadingMode);
      loadingModePendingCount = pendingSz;
      loadingModeMeshCount = meshCnt;




      long prepBudgetNs = loadingMode ? 6_000_000L : 800_000L;
      long prepDeadline = System.nanoTime() + prepBudgetNs;
      long t0 = System.nanoTime();


      if (frameCount % 6 == 0) {
        pruneFarMeshes(client, reusableCamPos);
      }




      if (loadingMode && frameCount % 6 == 0 && client.player != null) {
        int px = client.player.getChunkPos().x;
        int pz = client.player.getChunkPos().z;
        int renderDist = client.options.getViewDistance().getValue();
        int minRing = Integer.MAX_VALUE;
        int scanned = 0;
        var _scanIter = pendingBuildSet.iterator();
        while (_scanIter.hasNext() && scanned < 1500) {
          long _key = _scanIter.nextLong();
          scanned++;
          int _kx = (int) ((_key >> 42) & 0x3FFFFF);
          if ((_kx & 0x200000) != 0)
            _kx |= ~0x3FFFFF;
          int _kz = (int) (_key & 0x3FFFFF);
          if ((_kz & 0x200000) != 0)
            _kz |= ~0x3FFFFF;
          int _ring = Math.max(Math.abs(_kx - px), Math.abs(_kz - pz));
          if (_ring < minRing)
            minRing = _ring;
        }
        loadingModeNearestPendingRing = minRing;
        int fullDist = (renderDist + 2) * 16;


        int horizonBlocks = minRing < Integer.MAX_VALUE
            ? Math.min((minRing + 3) * 16, fullDist)
            : fullDist;
        NativeBridge.nSetRenderDistance(horizonBlocks);
      }
      long t1 = System.nanoTime();


      if (System.nanoTime() < prepDeadline && (!camStatic || !pendingBuildSet.isEmpty())) {



        if (!loadingMode && !pendingBuildSet.isEmpty()) {
          int curSize = pendingBuildSet.size();
          if (curSize != lastPendingSetSize || frameCount % 32 == 0) {
            sortedListDirty = true;
            lastPendingSetSize = curSize;
          }
        }
        buildPendingChunkMeshes(client, prepDeadline);
      }
      long t2 = System.nanoTime();



      int lodInterval = camStatic ? 4 : 3;
      if (!loadingMode && System.nanoTime() < prepDeadline && frameCount % lodInterval == 0) {
        rebuildLodMeshes(client, prepDeadline);
      }
      long t3 = System.nanoTime();
      lastPruneNs = t1 - t0;
      lastBuildNs = t2 - t1;
      lastLodNs = t3 - t2;
      lastPrepareTotalNs = t3 - t0;
      jPruneAcc += (t1 - t0);
      jBuildAcc += (t2 - t1);
      jLodAcc += (t3 - t2);
      jProfCount++;
      if (jProfCount >= 240) {
        double pruneMs = jPruneAcc / 1e6 / jProfCount;
        double buildMs = jBuildAcc / 1e6 / jProfCount;
        double lodMs = jLodAcc / 1e6 / jProfCount;
        MetalLogger.deepInfo(
            "JAVA_PROFILE: prune=%.2fms build=%.2fms lod=%.2fms (avg/%d) pending=%d queued=%d meshes=%d",
            pruneMs, buildMs, lodMs, jProfCount, pendingBuildSet.size(), chunkMesher.getPendingCount(),
            chunkMesher.getMeshCount());
        jPruneAcc = 0;
        jBuildAcc = 0;
        jLodAcc = 0;
        jProfCount = 0;
      }
    }
  }

  private double prevCamX = Double.NaN, prevCamY, prevCamZ;
  private float prevCamYaw = Float.NaN, prevCamPitch;
  private boolean reusingTerrainFrame = false;
  private boolean cameraInWater = false;
  private boolean cameraInLava = false;
  private int cameraLightLevel = 15;
  private int lastMeshCountForStaticCheck = -1;
  private int lastMeshUpdateGen = -1;
  private volatile boolean terrainDirty = false;

  private long lastTerrainDrawNs = 0;

  private static final long STATIC_HOLD_NS = 500_000_000L;

  private boolean isCameraStatic(Camera camera) {
    double cx = camera.getCameraPos().x;
    double cy = camera.getCameraPos().y;
    double cz = camera.getCameraPos().z;
    float yaw = camera.getYaw();
    float pitch = camera.getPitch();

    boolean isStatic = Math.abs(cx - prevCamX) < 0.001 &&
        Math.abs(cy - prevCamY) < 0.001 &&
        Math.abs(cz - prevCamZ) < 0.001 &&
        Math.abs(yaw - prevCamYaw) < 0.01f &&
        Math.abs(pitch - prevCamPitch) < 0.01f;
    prevCamX = cx;
    prevCamY = cy;
    prevCamZ = cz;
    prevCamYaw = yaw;
    prevCamPitch = pitch;
    return isStatic;
  }

  public void beginFrame(Camera camera, float tickDelta, Matrix4f projection,
      Matrix4f modelView) {

    watchdogHeartbeat = System.nanoTime();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);
    reusableCamPos.set((float) camera.getCameraPos().x,
        (float) camera.getCameraPos().y,
        (float) camera.getCameraPos().z);
    frustumCuller.update(projectionMatrix, modelViewMatrix, reusableCamPos);
    lastDrawnChunkCount = 0;
    renderer.beginFrame(tickDelta);
    reusableMetalProj.set(projectionMatrix);
    reusableMetalProj.m02(0.5f * reusableMetalProj.m02() + 0.5f * reusableMetalProj.m03());
    reusableMetalProj.m12(0.5f * reusableMetalProj.m12() + 0.5f * reusableMetalProj.m13());
    reusableMetalProj.m22(0.5f * reusableMetalProj.m22() + 0.5f * reusableMetalProj.m23());
    reusableMetalProj.m32(0.5f * reusableMetalProj.m32() + 0.5f * reusableMetalProj.m33());


    float[] projArr = reusableProjArr;
    float[] mvArr = reusableMvArr;
    reusableMetalProj.get(projArr);
    modelViewMatrix.get(mvArr);
    NativeBridge.nSetFrameMatrices(renderer.getHandle(), projArr, mvArr,
        camera.getCameraPos().x, camera.getCameraPos().y,
        camera.getCameraPos().z);


    {
      MinecraftClient mc = MinecraftClient.getInstance();
      float skyBrightness = 1.0f;
      if (mc != null && mc.world != null) {
        long timeOfDay = mc.world.getTimeOfDay() % 24000L;

        float skyAngle = (timeOfDay / 24000.0f) - 0.25f;
        if (skyAngle < 0)
          skyAngle += 1.0f;
        float cos = (float) Math.cos(skyAngle * 2.0 * Math.PI);


        skyBrightness = cos * 0.48f + 0.52f;
        if (skyBrightness < 0.04f)
          skyBrightness = 0.04f;
        if (skyBrightness > 1.0f)
          skyBrightness = 1.0f;
      }
      NativeBridge.nSetSkyBrightness(renderer.getHandle(), skyBrightness);
    }
    if (texturesReady) {
      textureManager.updateLightmap();
      long blockAtlas = textureManager.getBlockAtlasTexture();
      if (blockAtlas != 0) {
        renderer.bindTexture(blockAtlas, 0);
      }
      long lightmap = textureManager.getLightmapTexture();
      if (lightmap != 0) {
        renderer.bindTexture(lightmap, 1);
      }
    }
    boolean cameraStatic = isCameraStatic(camera);
    boolean skipTerrainDraw = false;




    int currentMeshCount = chunkMesher.getMeshCount();
    boolean meshCountChanged = (currentMeshCount != lastMeshCountForStaticCheck);
    lastMeshCountForStaticCheck = currentMeshCount;

    int currentMeshGen = chunkMesher.getMeshUpdateGeneration();
    boolean meshContentUpdated = (currentMeshGen != lastMeshUpdateGen);
    lastMeshUpdateGen = currentMeshGen;
    boolean terrainWasDirty = terrainDirty;
    if (terrainWasDirty)
      terrainDirty = false;
    updateCameraFluidState();
    long nowNs = System.nanoTime();
    MetalRenderConfig activeCfg = MetalRenderClient.getConfig();
    boolean allowTerrainReuse = activeCfg != null
        && !activeCfg.enableMemorylessTargets
        && !loadingMode
        && !meshCountChanged
        && !meshContentUpdated
        && !terrainWasDirty
        && !entityRenderer.hasVisibleSubmergedEntities();
    boolean sceneIsStatic = cameraStatic && allowTerrainReuse;
    if (sceneIsStatic) {



      if (nowNs - lastTerrainDrawNs < STATIC_HOLD_NS) {
        skipTerrainDraw = true;
        reusingTerrainFrame = true;
        NativeBridge.nSetReuseTerrainFrame(true);
      } else {

        lastTerrainDrawNs = nowNs;
        reusingTerrainFrame = false;
        NativeBridge.nSetReuseTerrainFrame(false);
      }
    } else {
      lastTerrainDrawNs = nowNs;
      reusingTerrainFrame = false;
      NativeBridge.nSetReuseTerrainFrame(false);
    }
    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {
      if (MetalRenderClient.getConfig().enableMetalRendering) {
        if (cachedInhouseHandle == 0) {
          cachedInhouseHandle = renderer.getBackend().getInhousePipelineHandle();
        }
        long inhousePipeline = cachedInhouseHandle;
        if (inhousePipeline != 0) {
          NativeBridge.nSetPipelineState(frameCtx, inhousePipeline);
        }
        if (!skipTerrainDraw) {
          NativeBridge.nSetWaterFog(frameCtx, (cameraInWater || cameraInLava) ? 1.0f : 0.0f);
          entityRenderer.renderCapturedEntities(frameCtx, cameraInWater);
          long ibHandle = chunkMesher.getGlobalIndexBuffer();
          if (ibHandle != 0) {
            int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
            lastDrawnChunkCount = drawn;
            PerformanceController.accumulateChunkStats(drawn, drawn, 0, 0);
            if (MetalRenderConfig.isDeepDebugActive() && (frameCount < 10 || frameCount % 3000 == 0)) {
              MetalLogger.info("Frame %d: V18 native drew %d chunks (meshes=%d ib=%d)",
                  frameCount, drawn, chunkMesher.getMeshCount(), ibHandle);
            }



            com.pebbles_boon.metalrender.config.MetalRenderConfig localCfg = MetalRenderClient.getConfig();
            if (localCfg.enableProgrammableBlending && NativeBridge.isLibLoaded()) {
              NativeBridge.nDrawOITPass(frameCtx);
            }
          } else {
            if (MetalRenderConfig.isDeepDebugActive() && (frameCount < 10 || frameCount % 3000 == 0)) {
              MetalLogger.info("Frame %d: SKIPPED ibHandle=0", frameCount);
            }
            lastDrawnChunkCount = 0;
          }
        } else {
          if (MetalRenderConfig.isDeepDebugActive() && frameCount < 10) {
            MetalLogger.info("Frame %d: SKIPPED skipTerrainDraw=true (reusing=%b meshChg=%b loading=%b)",
                frameCount, reusingTerrainFrame, meshCountChanged, loadingMode);
          }
        }
      } else {
        if (MetalRenderConfig.isDeepDebugActive() && frameCount < 50) {
          MetalLogger.info("Frame %d: SKIPPED enableMetalRendering=false", frameCount);
        }
      }
    } else {
      if (MetalRenderConfig.isDeepDebugActive() && frameCount < 50) {
        MetalLogger.info("Frame %d: SKIPPED frameCtx=0", frameCount);
      }
    }
    logDeepDebugFrameTrace(skipTerrainDraw, frameCtx != 0);
  }

  private void logDeepDebugFrameTrace(boolean skipTerrainDraw, boolean frameContextReady) {
    if (!MetalRenderConfig.isDeepDebugActive() || (frameCount + 1) % 10 != 0) {
      return;
    }
    Runtime rt = Runtime.getRuntime();
    long usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long maxHeapMb = rt.maxMemory() / (1024 * 1024);
    long nativeAvailMb = NativeBridge.isLibLoaded() ? NativeBridge.nGetAvailableMemory() / (1024 * 1024) : -1;
    int hizMipCount = NativeBridge.isLibLoaded() ? NativeBridge.nGetHiZMipCount() : -1;
    int subChunkCapacity = subChunkUploadBuffer != null ? subChunkUploadBuffer.capacity() : 0;
    int uniformCapacity = chunkUniformsBuffer != null ? chunkUniformsBuffer.capacity() : 0;
    int builderThreads = chunkMesher.getBuilderThreadCount();
    int inflight = chunkMesher.getPendingCount();
    int meshes = chunkMesher.getMeshCount();
    int meshGeneration = chunkMesher.getMeshUpdateGeneration();
    MinecraftClient client = MinecraftClient.getInstance();
    int renderDistance = client != null ? client.options.getViewDistance().getValue() : -1;
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer != null && NativeBridge.isLibLoaded()) {
      lastGPUVisibleCount = NativeBridge.nGetGPUVisibleCount(renderer.getHandle());
      NativeBridge.nGetGPUCullStats(gpuCullStats);
    }
    com.pebbles_boon.metalrender.config.MetalRenderConfig cfg = MetalRenderClient.getConfig();
    MetalLogger.info(
        "[DBG_FRAME %d] prepMs=%.2f pruneMs=%.2f buildMs=%.2f lodMs=%.2f frameMs=%.2f avgFrameMs=%.2f fps=%.1f loading=%b nearestPendingRing=%s queued=%d inflight=%d meshes=%d meshGen=%d drawn=%d gpuVisible=%d frameCtx=%b skipTerrain=%b builderThreads=%d renderDist=%d",
        frameCount + 1,
        lastPrepareTotalNs / 1_000_000.0,
        lastPruneNs / 1_000_000.0,
        lastBuildNs / 1_000_000.0,
        lastLodNs / 1_000_000.0,
        PerformanceController.getLogger().getLastFrameTime(),
        PerformanceController.getLogger().getAvgFrameTime(),
        PerformanceController.getLogger().getCurrentFPS(),
        loadingMode,
        loadingModeNearestPendingRing == Integer.MAX_VALUE ? "none"
            : Integer.toString(loadingModeNearestPendingRing),
        pendingBuildSet.size(),
        inflight,
        meshes,
        meshGeneration,
        lastDrawnChunkCount,
        lastGPUVisibleCount,
        frameContextReady,
        skipTerrainDraw,
        builderThreads,
        renderDistance);
    MetalLogger.info(
        "[DBG_MEM %d] heapMb=%d/%d nativeAvailMb=%d subChunkBuf=%d uniformBuf=%d hizMips=%d gpuCull=[%d,%d,%d,%d,%d] gpuDriven=%b meshShaders=%b icb=%b argBuf=%b memoryless=%b",
        frameCount + 1,
        usedHeapMb,
        maxHeapMb,
        nativeAvailMb,
        subChunkCapacity,
        uniformCapacity,
        hizMipCount,
        gpuCullStats[0],
        gpuCullStats[1],
        gpuCullStats[2],
        gpuCullStats[3],
        gpuCullStats[4],
        gpuDrivenEnabled,
        NativeBridge.isLibLoaded() && NativeBridge.nAreMeshShadersActive(),
        NativeBridge.isLibLoaded() && NativeBridge.nIsGPUDrivenActive(),
        cfg != null && cfg.enableArgumentBuffers,
        cfg != null && cfg.enableMemorylessTargets);
  }

  private void updateCameraFluidState() {
    cameraInWater = false;
    cameraInLava = false;
    cameraLightLevel = 15;
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.world == null) {
        return;
      }
      Camera cam = mc.gameRenderer.getCamera();
      net.minecraft.util.math.Vec3d cameraPos = cam.getCameraPos();
      net.minecraft.util.math.BlockPos camBP = reusableCamBlockPos.set(
          (int) Math.floor(cameraPos.x),
          (int) Math.floor(cameraPos.y),
          (int) Math.floor(cameraPos.z));
      net.minecraft.block.enums.CameraSubmersionType submersionType = cam.getSubmersionType();
      cameraInWater = submersionType == net.minecraft.block.enums.CameraSubmersionType.WATER;
      cameraInLava = submersionType == net.minecraft.block.enums.CameraSubmersionType.LAVA;
      if (cameraInWater || cameraInLava) {
        cameraLightLevel = mc.world.getLightLevel(camBP);
      }
    } catch (Exception e) {
      cameraInWater = false;
      cameraInLava = false;
      cameraLightLevel = 15;
    }
  }

  private static void extractFrustumPlanes(Matrix4f vp, float[] out) {
    out[0] = vp.m03() + vp.m00();
    out[1] = vp.m13() + vp.m10();
    out[2] = vp.m23() + vp.m20();
    out[3] = vp.m33() + vp.m30();
    normalizePlane(out, 0);
    out[4] = vp.m03() - vp.m00();
    out[5] = vp.m13() - vp.m10();
    out[6] = vp.m23() - vp.m20();
    out[7] = vp.m33() - vp.m30();
    normalizePlane(out, 4);
    out[8] = vp.m03() + vp.m01();
    out[9] = vp.m13() + vp.m11();
    out[10] = vp.m23() + vp.m21();
    out[11] = vp.m33() + vp.m31();
    normalizePlane(out, 8);
    out[12] = vp.m03() - vp.m01();
    out[13] = vp.m13() - vp.m11();
    out[14] = vp.m23() - vp.m21();
    out[15] = vp.m33() - vp.m31();
    normalizePlane(out, 12);
    out[16] = vp.m03() + vp.m02();
    out[17] = vp.m13() + vp.m12();
    out[18] = vp.m23() + vp.m22();
    out[19] = vp.m33() + vp.m32();
    normalizePlane(out, 16);
    out[20] = vp.m03() - vp.m02();
    out[21] = vp.m13() - vp.m12();
    out[22] = vp.m23() - vp.m22();
    out[23] = vp.m33() - vp.m32();
    normalizePlane(out, 20);
  }

  private static void normalizePlane(float[] planes, int offset) {
    float a = planes[offset], b = planes[offset + 1], c = planes[offset + 2];
    float len = (float) Math.sqrt(a * a + b * b + c * c);
    if (len > 0.0f) {
      float invLen = 1.0f / len;
      planes[offset] *= invLen;
      planes[offset + 1] *= invLen;
      planes[offset + 2] *= invLen;
      planes[offset + 3] *= invLen;
    }
  }

  public void endFrame() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {
      updateCameraFluidState();
      if (reusingTerrainFrame) {
        NativeBridge.nSetWaterFog(frameCtx, (cameraInWater || cameraInLava) ? 1.0f : 0.0f);
        entityRenderer.renderCapturedEntities(frameCtx, cameraInWater);
      }
      NativeBridge.nSetWaterFog(frameCtx, (cameraInWater || cameraInLava) ? 1.0f : 0.0f);
      particleRenderer.renderCapturedParticles(frameCtx);
      renderBlockOutline(frameCtx);
      if (cameraInWater || cameraInLava) {
        renderUnderwaterOverlay(frameCtx, cameraInLava, cameraLightLevel);
      }
    }
    renderer.endFrame();
    frameCount++;
  }



  private void renderUnderwaterOverlay(long frameCtx, boolean isLava, int lightLevel) {
    try {

      float lightFade = lightLevel / 15.0f;

      float tintR = isLava ? 0.6f : 0.05f;
      float tintG = isLava ? 0.15f : 0.1f;
      float tintB = isLava ? 0.0f : 0.35f;

      float baseTintA = isLava ? 0.7f : 0.25f;
      float tintA = isLava ? baseTintA : baseTintA * (0.5f + 0.5f * (1.0f - lightFade));


      NativeBridge.nDrawOverlayQuad(frameCtx, tintR, tintG, tintB, tintA);
    } catch (Exception e) {

    }
  }


  private final float[] outlineVerts = new float[72 * 3];
  private final ByteBuffer outlineByteBuf = ByteBuffer.allocateDirect(72 * 3 * 4)
      .order(ByteOrder.nativeOrder());
  private final byte[] outlineUploadData = new byte[72 * 3 * 4];

  private void renderBlockOutline(long frameCtx) {
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.crosshairTarget == null)
        return;
      if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK)
        return;
      BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
      BlockPos pos = hit.getBlockPos();
      Camera cam = mc.gameRenderer.getCamera();
      float bx = (float) (pos.getX() - cam.getCameraPos().x);
      float by = (float) (pos.getY() - cam.getCameraPos().y);
      float bz = (float) (pos.getZ() - cam.getCameraPos().z);
      float e = 0.002f;
      float x0 = bx - e, y0 = by - e, z0 = bz - e;
      float x1 = bx + 1 + e, y1 = by + 1 + e, z1 = bz + 1 + e;
      float t = 0.008f;
      int vi = 0;
      vi = addThickEdge(outlineVerts, vi, x0, y0, z0, x1, y0, z0, t, 1);
      vi = addThickEdge(outlineVerts, vi, x1, y0, z0, x1, y0, z1, t, 1);
      vi = addThickEdge(outlineVerts, vi, x1, y0, z1, x0, y0, z1, t, 1);
      vi = addThickEdge(outlineVerts, vi, x0, y0, z1, x0, y0, z0, t, 1);
      vi = addThickEdge(outlineVerts, vi, x0, y1, z0, x1, y1, z0, t, 1);
      vi = addThickEdge(outlineVerts, vi, x1, y1, z0, x1, y1, z1, t, 1);
      vi = addThickEdge(outlineVerts, vi, x1, y1, z1, x0, y1, z1, t, 1);
      vi = addThickEdge(outlineVerts, vi, x0, y1, z1, x0, y1, z0, t, 1);
      vi = addThickEdge(outlineVerts, vi, x0, y0, z0, x0, y1, z0, t, 0);
      vi = addThickEdge(outlineVerts, vi, x1, y0, z0, x1, y1, z0, t, 2);
      vi = addThickEdge(outlineVerts, vi, x1, y0, z1, x1, y1, z1, t, 0);
      vi = addThickEdge(outlineVerts, vi, x0, y0, z1, x0, y1, z1, t, 2);
      int vertexCount = vi / 3;
      int dataLen = vi * 4;
      outlineByteBuf.clear();
      for (int i = 0; i < vi; i++)
        outlineByteBuf.putFloat(outlineVerts[i]);
      outlineByteBuf.flip();
      outlineByteBuf.get(outlineUploadData, 0, dataLen);
      outlineByteBuf.rewind();
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer == null)
        return;
      long device = renderer.getBackend().getDeviceHandle();
      if (outlineBufferHandle == 0 || dataLen > outlineBufferSize) {
        if (outlineBufferHandle != 0) {
          NativeBridge.nDestroyBuffer(outlineBufferHandle);
        }
        outlineBufferHandle = NativeBridge.nCreateBuffer(
            device, dataLen, NativeMemory.STORAGE_MODE_SHARED);
        outlineBufferSize = dataLen;
      }
      NativeBridge.nUploadBufferData(outlineBufferHandle, outlineUploadData, 0, dataLen);
      NativeBridge.nSetDebugColor(frameCtx, 0.0f, 0.0f, 0.0f, 0.5f);
      NativeBridge.nDrawTriangleBuffer(frameCtx, outlineBufferHandle, vertexCount);
    } catch (Exception e) {
      MetalLogger.error("[BlockOutline] Exception: %s", e.getMessage());
    }
  }

  private int outlineBufferSize = 0;

  private static int addThickEdge(float[] v, int vi,
      float ax, float ay, float az, float bx, float by, float bz,
      float t, int expandAxis) {
    float dx = 0, dy = 0, dz = 0;
    if (expandAxis == 0)
      dx = t;
    else if (expandAxis == 1)
      dy = t;
    else
      dz = t;
    float p0x = ax - dx, p0y = ay - dy, p0z = az - dz;
    float p1x = ax + dx, p1y = ay + dy, p1z = az + dz;
    float p2x = bx + dx, p2y = by + dy, p2z = bz + dz;
    float p3x = bx - dx, p3y = by - dy, p3z = bz - dz;
    v[vi++] = p0x;
    v[vi++] = p0y;
    v[vi++] = p0z;
    v[vi++] = p1x;
    v[vi++] = p1y;
    v[vi++] = p1z;
    v[vi++] = p2x;
    v[vi++] = p2y;
    v[vi++] = p2z;
    v[vi++] = p0x;
    v[vi++] = p0y;
    v[vi++] = p0z;
    v[vi++] = p2x;
    v[vi++] = p2y;
    v[vi++] = p2z;
    v[vi++] = p3x;
    v[vi++] = p3y;
    v[vi++] = p3z;
    return vi;
  }

  private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet pendingBuildSet = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
  private final it.unimi.dsi.fastutil.longs.LongArrayList sortedBuildList = new it.unimi.dsi.fastutil.longs.LongArrayList();
  private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightRefreshFrames = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
  private final it.unimi.dsi.fastutil.longs.LongArrayList lightRefreshScratch = new it.unimi.dsi.fastutil.longs.LongArrayList();

  private final java.util.ArrayList<CustomChunkMesher.ChunkMeshData> pruneRemoveList = new java.util.ArrayList<>();

  private int pruneOffset = 0;
  private boolean sortedListDirty = true;

  private int lastPendingSetSize = -1;
  private float cachedForwardX = 0, cachedForwardZ = 1;
  private int lastScanPlayerCX = Integer.MIN_VALUE, lastScanPlayerCZ = Integer.MIN_VALUE;
  private int lastScanRenderDist = -1;

  private int[] sortScratchKeys = new int[256];
  private long[] sortScratchKeyedIndices = new long[256];
  private long[] sortScratchReordered = new long[256];


  private long[] loadScratch = new long[128];
  private int[] loadScratchScore = new int[128];
  private int[] loadScratchRing = new int[128];

  private static long packChunkKey(int cx, int cy, int cz) {
    return ((long) (cx & 0x3FFFFF) << 42) | ((long) (cy & 0xFFFFF) << 22) | (cz & 0x3FFFFF);
  }

  private void scheduleImmediateSectionBuild(int cx, int cy, int cz,
      int playerChunkX, int playerChunkZ) {
    int dist = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ));
    int lod = com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(dist);
    chunkMesher.markDirty(cx, cy, cz);
    if (pendingBuildSet.add(packChunkKey(cx, cy, cz))) {
      sortedListDirty = true;
    }
    chunkMesher.buildMeshFromWorld(cx, cy, cz, lod, true);
  }

  private void queueLightRefresh(int cx, int cy, int cz) {
    long key = packChunkKey(cx, cy, cz);
    int remaining = lightRefreshFrames.get(key);
    if (remaining < LIGHT_SOURCE_REFRESH_FRAMES) {
      lightRefreshFrames.put(key, LIGHT_SOURCE_REFRESH_FRAMES);
    }
  }

  private void processLightRefreshes() {
    if (lightRefreshFrames.isEmpty()) {
      return;
    }
    lightRefreshScratch.clear();
    for (var entry : lightRefreshFrames.long2IntEntrySet()) {
      if (entry.getIntValue() > 0) {
        lightRefreshScratch.add(entry.getLongKey());
      }
    }
    for (int i = 0; i < lightRefreshScratch.size(); i++) {
      long key = lightRefreshScratch.getLong(i);
      int remaining = lightRefreshFrames.get(key);
      if (remaining <= 0) {
        lightRefreshFrames.remove(key);
        continue;
      }
      int cx = (int) ((key >> 42) & 0x3FFFFF);
      if ((cx & 0x200000) != 0)
        cx |= ~0x3FFFFF;
      int cy = (int) ((key >> 22) & 0xFFFFF);
      if ((cy & 0x80000) != 0)
        cy |= ~0xFFFFF;
      int cz = (int) (key & 0x3FFFFF);
      if ((cz & 0x200000) != 0)
        cz |= ~0x3FFFFF;
      scheduleLightSectionRebuild(cx, cy, cz);
      if (remaining == 1) {
        lightRefreshFrames.remove(key);
      } else {
        lightRefreshFrames.put(key, remaining - 1);
      }
    }
  }

  private void buildPendingChunkMeshes(MinecraftClient client, long prepDeadline) {




    if (client.getServer() != null && client.getServer().getAverageTickTime() > 60f) {

      scanForPendingChunks(client);
      return;
    }
    scanForPendingChunks(client);
    if (client.player != null) {
      int playerChunkX = client.player.getChunkPos().x;
      int playerChunkZ = client.player.getChunkPos().z;
      float yaw = client.player.getYaw();
      cachedForwardX = (float) -Math.sin(Math.toRadians(yaw));
      cachedForwardZ = (float) Math.cos(Math.toRadians(yaw));

      long remaining = prepDeadline - System.nanoTime();
      if (remaining > 500_000L) {
        long budget = Math.min(remaining, 1_000_000L);
        buildFromPendingSet(playerChunkX, playerChunkZ, budget);
      }
    }
  }

  private int scanFrameCounter = 0;
  private int scanFrontierRing = 0;

  private void scanForPendingChunks(MinecraftClient client) {
    ClientWorld world = client.world;
    if (world == null)
      return;
    if (client.player == null)
      return;
    int renderDist = client.options.getViewDistance().getValue();
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    boolean playerMovedChunk = (playerChunkX != lastScanPlayerCX ||
        playerChunkZ != lastScanPlayerCZ);
    boolean renderDistChanged = (renderDist != lastScanRenderDist);
    if (playerMovedChunk || renderDistChanged) {
      lastScanPlayerCX = playerChunkX;
      lastScanPlayerCZ = playerChunkZ;
      lastScanRenderDist = renderDist;


      NativeBridge.nSetRenderDistance((renderDist + 2) * 16);

      if (frameCount % 4 == 0)
        sortedListDirty = true;
      if (renderDistChanged) {
        pendingBuildSet.clear();
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, renderDist);
        scanFrontierRing = 0;
        scanFrameCounter = 0;
      } else {
        int closeRange = Math.min(8, renderDist);
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, closeRange);
        scanFrontierRing = 0;
      }


      if (playerMovedChunk && !loadingMode) {
        triggerImmediateLodUpgrades(world, playerChunkX, playerChunkZ);
      }
    }
    scanFrameCounter++;
    if (scanFrameCounter >= 60) {


      scanRingsInRange(world, playerChunkX, playerChunkZ, 0, renderDist);
      scanFrameCounter = 0;
      scanFrontierRing = 0;
    } else {
      int closeRange = Math.min(8, renderDist);

      int scanInterval = loadingMode ? 8 : 2;
      if (!playerMovedChunk && scanFrameCounter % scanInterval == 0) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, closeRange);
      }


      int frontierStart = Math.max(closeRange + 1, scanFrontierRing);
      int frontierEnd = Math.min(frontierStart + 8, renderDist);
      if (frontierStart <= renderDist) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, frontierStart, frontierEnd);
        scanFrontierRing = frontierEnd + 1;
        if (scanFrontierRing > renderDist) {
          scanFrontierRing = closeRange + 1;
        }
      }
    }
    logServerChunkAvailability(world, playerChunkX, playerChunkZ, renderDist);
  }


  private void triggerImmediateLodUpgrades(ClientWorld world, int playerChunkX, int playerChunkZ) {

    if (loadingMode)
      return;
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.lodEnabled())
      return;
    MinecraftClient client = MinecraftClient.getInstance();
    int playerChunkY = (client != null && client.player != null)
        ? ((int) Math.floor(client.player.getY()) >> 4)
        : 0;

    int scanRange = com.pebbles_boon.metalrender.config.MetalRenderConfig.lod4Distance() + 2;

    int renderDist = MinecraftClient.getInstance().options.getViewDistance().getValue();
    scanRange = Math.min(scanRange, renderDist);
    int rebuilt = 0;
    int maxRebuilds = 48;
    for (int dx = -scanRange; dx <= scanRange && rebuilt < maxRebuilds; dx++) {
      for (int dz = -scanRange; dz <= scanRange && rebuilt < maxRebuilds; dz++) {
        int cx = playerChunkX + dx;
        int cz = playerChunkZ + dz;
        int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
        int desiredLod = com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(chunkDist);
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null)
          continue;
        ChunkSection[] sections = chunk.getSectionArray();
        for (int sy = 0; sy < sections.length; sy++) {
          ChunkSection section = sections[sy];
          if (section == null || section.isEmpty())
            continue;
          int worldY = chunk.sectionIndexToCoord(sy);
          boolean needsLodChange = chunkMesher.needsLodRebuild(cx, worldY, cz,
              desiredLod);
          boolean needsFaceCullRefresh = chunkMesher.needsFaceCullRebuild(cx,
              worldY, cz, playerChunkX, playerChunkY, playerChunkZ);
          if (needsLodChange || needsFaceCullRefresh) {
            chunkMesher.buildMeshFromWorld(cx, worldY, cz, desiredLod, true);
            rebuilt++;
          }
        }
      }
    }
    if (rebuilt > 0) {
      MetalLogger.deepInfo("[LOD_IMMEDIATE] Triggered %d LOD upgrades for close range (range=%d)", rebuilt,
          scanRange);
    }
  }

  private void scanRingsInRange(ClientWorld world, int playerChunkX, int playerChunkZ,
      int startRing, int endRing) {
    for (int ring = startRing; ring <= endRing; ring++) {


      if (ring == 0) {
        scanChunkColumn(world, playerChunkX, playerChunkZ);
      } else {

        for (int dx = -ring; dx <= ring; dx++) {
          scanChunkColumn(world, playerChunkX + dx, playerChunkZ - ring);
          scanChunkColumn(world, playerChunkX + dx, playerChunkZ + ring);
        }

        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
          scanChunkColumn(world, playerChunkX - ring, playerChunkZ + dz);
          scanChunkColumn(world, playerChunkX + ring, playerChunkZ + dz);
        }
      }
    }
  }

  private void scanChunkColumn(ClientWorld world, int cx, int cz) {
    WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
    if (chunk == null)
      return;
    ChunkSection[] sections = chunk.getSectionArray();
    for (int sy = 0; sy < sections.length; sy++) {
      ChunkSection section = sections[sy];
      if (section == null || section.isEmpty())
        continue;
      int worldY = chunk.sectionIndexToCoord(sy);
      if (!chunkMesher.hasMesh(cx, worldY, cz)) {
        pendingBuildSet.add(packChunkKey(cx, worldY, cz));
      }
    }
  }

  private long lastChunkDiagMs = 0;

  private void logServerChunkAvailability(ClientWorld world, int playerChunkX, int playerChunkZ, int renderDist) {
    if (!MetalRenderConfig.isDeepDebugActive())
      return;
    long now = System.currentTimeMillis();
    if (now - lastChunkDiagMs < 5000)
      return;
    lastChunkDiagMs = now;
    int available = 0, total = 0;
    int maxRingAvail = 0;
    for (int ring = 0; ring <= renderDist; ring++) {
      int ringAvail = 0;
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;
          total++;
          if (world.getChunkManager().getWorldChunk(playerChunkX + dx, playerChunkZ + dz) != null) {
            available++;
            ringAvail++;
          }
        }
      }
      if (ringAvail > 0)
        maxRingAvail = ring;
    }
    MetalLogger.deepInfo("CHUNK_AVAIL: server=%d/%d (max_ring=%d) meshes=%d pending=%d",
        available, total, maxRingAvail, chunkMesher.getMeshCount(), pendingBuildSet.size());
  }

  public int buildMeshesDuringWait(long metalHandle) {
    watchdogHeartbeat = System.nanoTime();
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null || client.player == null || client.world == null)
      return 0;
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    int totalBuilt = 0;
    long totalBudgetNs = loadingMode ? 12_000_000L : 4_000_000L;
    long burstBudgetNs = loadingMode ? 6_000_000L : 2_000_000L;
    long minBuildNs = loadingMode ? totalBudgetNs : 1_500_000L;
    long startTime = System.nanoTime();
    long timeout = startTime + totalBudgetNs;
    while (System.nanoTime() < timeout) {
      long elapsed = System.nanoTime() - startTime;
      if (elapsed >= minBuildNs && NativeBridge.nIsFrameReady(metalHandle)) {
        break;
      }
      int built = buildFromPendingSet(playerChunkX, playerChunkZ, burstBudgetNs);
      if (built == 0)
        break;
      totalBuilt += built;
      watchdogHeartbeat = System.nanoTime();
    }
    return totalBuilt;
  }

  private int buildFromPendingSet(int playerChunkX, int playerChunkZ, long budgetNanos) {
    if (pendingBuildSet.isEmpty())
      return 0;





    if (loadingMode) {









      final int builderThreads = chunkMesher.getBuilderThreadCount();
      final int maxInflight = Math.max(16, builderThreads * 8);
      final int LOAD_SCAN = Math.max(64, builderThreads * 16);

      int alreadyQueued = chunkMesher.getPendingCount();
      if (alreadyQueued >= maxInflight)
        return 0;
      final int maxSubmit = Math.max(1,
          Math.min(builderThreads, maxInflight - alreadyQueued));
      long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
      int built = 0;


      if (loadScratch.length < LOAD_SCAN) {
        loadScratch = new long[LOAD_SCAN];
        loadScratchScore = new int[LOAD_SCAN];
        loadScratchRing = new int[LOAD_SCAN];
      }
      long[] scratch = loadScratch;
      int[] scratchScore = loadScratchScore;
      int[] scratchRing = loadScratchRing;
      int scratched = 0;
      int minRing = Integer.MAX_VALUE;
      var iter = pendingBuildSet.iterator();
      while (iter.hasNext() && scratched < LOAD_SCAN) {
        long key = iter.nextLong();
        iter.remove();
        int kx = (int) ((key >> 42) & 0x3FFFFF);
        if ((kx & 0x200000) != 0)
          kx |= ~0x3FFFFF;
        int kz = (int) (key & 0x3FFFFF);
        if ((kz & 0x200000) != 0)
          kz |= ~0x3FFFFF;
        int ring = Math.max(Math.abs(kx - playerChunkX), Math.abs(kz - playerChunkZ));
        int dist = Math.abs(kx - playerChunkX) + Math.abs(kz - playerChunkZ);
        float dot = (kx - playerChunkX) * cachedForwardX + (kz - playerChunkZ) * cachedForwardZ;
        int score = (int) (dist * 2 - dot);
        scratch[scratched] = key;
        scratchScore[scratched] = score;
        scratchRing[scratched] = ring;
        if (ring < minRing)
          minRing = ring;
        scratched++;
      }


      int filtered = 0;
      for (int i = 0; i < scratched; i++) {
        if (scratchRing[i] == minRing) {
          scratch[filtered] = scratch[i];
          scratchScore[filtered] = scratchScore[i];
          filtered++;
        } else {
          pendingBuildSet.add(scratch[i]);
        }
      }

      for (int i = 1; i < filtered; i++) {
        long kTmp = scratch[i];
        int sTmp = scratchScore[i];
        int j = i - 1;
        while (j >= 0 && scratchScore[j] > sTmp) {
          scratch[j + 1] = scratch[j];
          scratchScore[j + 1] = scratchScore[j];
          j--;
        }
        scratch[j + 1] = kTmp;
        scratchScore[j + 1] = sTmp;
      }

      int lastProcessedIdx = 0;
      for (int i = 0; i < filtered && built < maxSubmit; i++) {
        if (budgetNanos > 0 && built > 0 && System.nanoTime() >= deadline)
          break;
        lastProcessedIdx = i + 1;
        long key = scratch[i];
        int cx = (int) ((key >> 42) & 0x3FFFFF);
        if ((cx & 0x200000) != 0)
          cx |= ~0x3FFFFF;
        int cy = (int) ((key >> 22) & 0xFFFFF);
        if ((cy & 0x80000) != 0)
          cy |= ~0xFFFFF;
        int cz = (int) (key & 0x3FFFFF);
        if ((cz & 0x200000) != 0)
          cz |= ~0x3FFFFF;
        if (chunkMesher.hasMesh(cx, cy, cz))
          continue;
        int chunkDist = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ));
        int lodLevel = com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(chunkDist);



        if (com.pebbles_boon.metalrender.config.MetalRenderConfig.lodEnabled()
            && chunkDist > 2 && lodLevel < 1)
          lodLevel = 1;
        chunkMesher.buildMeshFromWorld(cx, cy, cz, lodLevel);
        built++;
      }

      for (int i = lastProcessedIdx; i < filtered; i++) {
        pendingBuildSet.add(scratch[i]);
      }
      sortedListDirty = true;
      return built;
    }





    boolean needsResync = sortedBuildList.isEmpty() && !pendingBuildSet.isEmpty();
    if (sortedListDirty || needsResync) {
      sortedBuildList.clear();
      sortedBuildList.addAll(pendingBuildSet);
      final int pcx = playerChunkX;
      final int pcz = playerChunkZ;


      final int sz = sortedBuildList.size();
      if (sortScratchKeys.length < sz) {
        sortScratchKeys = new int[sz];
        sortScratchKeyedIndices = new long[sz];
        sortScratchReordered = new long[sz];
      }
      final int[] sortKeys = sortScratchKeys;
      final long[] keyedIndices = sortScratchKeyedIndices;
      final long[] reordered = sortScratchReordered;
      for (int i = 0; i < sz; i++) {
        long k = sortedBuildList.getLong(i);
        int kx = (int) ((k >> 42) & 0x3FFFFF);
        if ((kx & 0x200000) != 0)
          kx |= ~0x3FFFFF;
        int kz = (int) (k & 0x3FFFFF);
        if ((kz & 0x200000) != 0)
          kz |= ~0x3FFFFF;
        int dist = Math.abs(kx - pcx) + Math.abs(kz - pcz);
        float dot = (kx - pcx) * cachedForwardX + (kz - pcz) * cachedForwardZ;
        sortKeys[i] = (int) (dist * 2 - dot);
      }


      for (int i = 0; i < sz; i++)
        keyedIndices[i] = ((long) sortKeys[i] << 32) | (i & 0xFFFFFFFFL);
      java.util.Arrays.sort(keyedIndices, 0, sz);
      for (int i = 0; i < sz; i++)
        reordered[i] = sortedBuildList.getLong((int) (keyedIndices[i] & 0xFFFFFFFFL));
      sortedBuildList.clear();
      for (int i = 0; i < sz; i++)
        sortedBuildList.add(reordered[i]);
      sortedListDirty = false;
    }
    long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    int builderThreads = chunkMesher.getBuilderThreadCount();
    int maxInflight = Math.max(24, builderThreads * 12);
    int availableSubmit = Math.max(0, maxInflight - chunkMesher.getPendingCount());
    if (availableSubmit == 0)
      return 0;
    int maxSubmit = Math.min(256, availableSubmit);
    int built = 0;
    int consumed = 0;
    int size = sortedBuildList.size();
    while (consumed < size && built < maxSubmit) {
      if (budgetNanos > 0 && built > 0 && System.nanoTime() >= deadline)
        break;
      long key = sortedBuildList.getLong(consumed);
      int cx = (int) ((key >> 42) & 0x3FFFFF);
      if ((cx & 0x200000) != 0)
        cx |= ~0x3FFFFF;
      int cy = (int) ((key >> 22) & 0xFFFFF);
      if ((cy & 0x80000) != 0)
        cy |= ~0xFFFFF;
      int cz = (int) (key & 0x3FFFFF);
      if ((cz & 0x200000) != 0)
        cz |= ~0x3FFFFF;
      consumed++;
      pendingBuildSet.remove(key);
      if (chunkMesher.hasMesh(cx, cy, cz)) {
        continue;
      }
      int chunkDist = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ));
      int lodLevel = com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(chunkDist);




      if (loadingMode && com.pebbles_boon.metalrender.config.MetalRenderConfig.lodEnabled()) {
        if (chunkDist > 4 && lodLevel < 2) {
          lodLevel = 2;
        } else if (lodLevel < 1) {
          lodLevel = 1;
        }
      }
      chunkMesher.buildMeshFromWorld(cx, cy, cz, lodLevel);
      built++;
    }


    if (consumed > 0) {
      sortedBuildList.removeElements(0, consumed);
    }
    return built;
  }

  private int lodScanOffset = 0;

  private void rebuildLodMeshes(MinecraftClient client, long prepDeadline) {

    if (loadingMode)
      return;
    if (!com.pebbles_boon.metalrender.config.MetalRenderConfig.lodEnabled())
      return;
    if (client.player == null || client.world == null)
      return;
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    int playerChunkY = (int) Math.floor(client.player.getY()) >> 4;
    int rebuilt = 0;

    long ownBudget = System.nanoTime() + 1_000_000L;
    long deadline = Math.min(ownBudget, prepDeadline);
    int maxScansPerPass = 2048;
    int maxRebuildsPerPass = 32;
    int scanned = 0;
    var allMeshes = chunkMesher.getAllMeshes();
    if (allMeshes.isEmpty())
      return;



    int safeScanOffset = Math.min(lodScanOffset, allMeshes.size());
    var iter = allMeshes.listIterator(safeScanOffset);
    while (iter.hasNext() && scanned < maxScansPerPass && rebuilt < maxRebuildsPerPass) {
      if (rebuilt > 0 && System.nanoTime() >= deadline)
        break;
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      scanned++;
      int dx = mesh.chunkX - playerChunkX;
      int dz = mesh.chunkZ - playerChunkZ;
      int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
      int desiredLod = com.pebbles_boon.metalrender.config.MetalRenderConfig
          .getLodLevel(chunkDist);
      boolean needsLodChange = chunkMesher.needsLodRebuild(mesh.chunkX,
          mesh.chunkY, mesh.chunkZ, desiredLod);
      boolean needsFaceCullRefresh = chunkMesher.needsFaceCullRebuild(
          mesh.chunkX, mesh.chunkY, mesh.chunkZ, playerChunkX, playerChunkY,
          playerChunkZ);
      if (needsLodChange || needsFaceCullRefresh) {
        chunkMesher.buildMeshFromWorld(mesh.chunkX, mesh.chunkY, mesh.chunkZ,
            desiredLod);
        rebuilt++;
      }
    }
    lodScanOffset += scanned;
    if (!iter.hasNext() || lodScanOffset >= allMeshes.size()) {
      lodScanOffset = 0;
    }
    if (rebuilt > 0) {
      MetalLogger.deepInfo("[LOD_REBUILD] Rebuilt %d meshes (scanned %d, offset %d)",
          rebuilt, scanned, lodScanOffset);
    }
  }

  public int getGLTextureForCompositing() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null)
      return 0;
    return renderer.getGLTextureId();
  }

  public FrustumCuller getFrustumCuller() {
    return frustumCuller;
  }

  public MetalEntityRenderer getEntityRenderer() {
    return entityRenderer;
  }

  public MetalParticleRenderer getParticleRenderer() {
    return particleRenderer;
  }

  public CustomChunkMesher getChunkMesher() {
    return chunkMesher;
  }

  public void setLastDrawnChunkCount(int c) {
    this.lastDrawnChunkCount = c;
  }

  public void addDrawnChunkCount(int c) {
    this.lastDrawnChunkCount += c;
  }

  public int getLastDrawnChunkCount() {
    return lastDrawnChunkCount;
  }

  public boolean areTexturesReady() {
    return texturesReady;
  }

  public MetalTextureManager getTextureManager() {
    return textureManager;
  }

  public boolean isWorldLoaded() {
    return worldLoaded;
  }

  public int getFrameCount() {
    return frameCount;
  }

  private void pruneFarMeshes(MinecraftClient client,
      org.joml.Vector3f camPos) {
    if (client.player == null)
      return;
    int renderDist = client.options.getViewDistance().getValue();
    float maxDist = (renderDist + 2) * 16.0f;
    float maxDistSq = maxDist * maxDist;



    java.util.List<CustomChunkMesher.ChunkMeshData> allMeshes = chunkMesher.getAllMeshes();
    int size = allMeshes.size();
    if (size == 0)
      return;
    int scanChunk = 100;
    int safeOffset = Math.min(pruneOffset, size);
    int limit = Math.min(scanChunk, size);
    pruneRemoveList.clear();
    var iter = allMeshes.listIterator(safeOffset);
    int scanned = 0;
    while (scanned < limit && iter.hasNext()) {
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      scanned++;
      float dx = mesh.chunkX * 16.0f + 8.0f - camPos.x;
      float dz = mesh.chunkZ * 16.0f + 8.0f - camPos.z;
      if (dx * dx + dz * dz > maxDistSq) {
        pruneRemoveList.add(mesh);
      }
    }

    pruneOffset = iter.hasNext() ? safeOffset + scanned : 0;
    for (int i = 0, n = pruneRemoveList.size(); i < n; i++) {
      CustomChunkMesher.ChunkMeshData mesh = pruneRemoveList.get(i);
      chunkMesher.removeMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
    }
    pruneRemoveList.clear();
  }

  public static boolean shouldBlitAt(String timingPoint) {
    return "flip_head".equals(timingPoint);
  }

  public static String getBlitTimingMode() {
    return "flip_head";
  }

  public void forceBlitNow() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blit(handle);
  }

  public void forceBlitDepthNow(int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blitDepth(handle, width, height);
  }

  public boolean uploadDepthDirect(int mcDepthTexId, int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.uploadDepthDirect(handle, mcDepthTexId, width,
        height);
  }

  public boolean blitDepthViaFBO(int mcDepthTexId, int mcFboId, int width,
      int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.blitDepthViaFBO(handle, mcDepthTexId, mcFboId,
        width, height);
  }

  public boolean isReady() {
    return worldLoaded && renderingActive;
  }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
  }

  public void onChunkLoaded(int chunkX, int chunkZ, net.minecraft.world.chunk.WorldChunk chunk) {
    if (!worldLoaded || !renderingActive)
      return;
    net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
    for (int sy = 0; sy < sections.length; sy++) {
      net.minecraft.world.chunk.ChunkSection section = sections[sy];
      if (section == null || section.isEmpty())
        continue;
      int worldY = chunk.sectionIndexToCoord(sy);
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        if (pendingBuildSet.add(packChunkKey(chunkX, worldY, chunkZ))) {
          sortedListDirty = true;
        }
      }







      if (!loadingMode) {
        chunkMesher.markDirty(chunkX - 1, worldY, chunkZ);
        chunkMesher.markDirty(chunkX + 1, worldY, chunkZ);
        chunkMesher.markDirty(chunkX, worldY - 1, chunkZ);
        chunkMesher.markDirty(chunkX, worldY + 1, chunkZ);
        chunkMesher.markDirty(chunkX, worldY, chunkZ - 1);
        chunkMesher.markDirty(chunkX, worldY, chunkZ + 1);
      } else {

        if (chunkMesher.hasMeshIgnoreDirty(chunkX - 1, worldY, chunkZ))
          chunkMesher.markDirty(chunkX - 1, worldY, chunkZ);
        if (chunkMesher.hasMeshIgnoreDirty(chunkX + 1, worldY, chunkZ))
          chunkMesher.markDirty(chunkX + 1, worldY, chunkZ);
        if (chunkMesher.hasMeshIgnoreDirty(chunkX, worldY - 1, chunkZ))
          chunkMesher.markDirty(chunkX, worldY - 1, chunkZ);
        if (chunkMesher.hasMeshIgnoreDirty(chunkX, worldY + 1, chunkZ))
          chunkMesher.markDirty(chunkX, worldY + 1, chunkZ);
        if (chunkMesher.hasMeshIgnoreDirty(chunkX, worldY, chunkZ - 1))
          chunkMesher.markDirty(chunkX, worldY, chunkZ - 1);
        if (chunkMesher.hasMeshIgnoreDirty(chunkX, worldY, chunkZ + 1))
          chunkMesher.markDirty(chunkX, worldY, chunkZ + 1);
      }
    }
  }

  public void scheduleSectionRebuild(int blockX, int blockY, int blockZ) {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    terrainDirty = true;
    int cx = blockX >> 4;
    int cy = blockY >> 4;
    int cz = blockZ >> 4;

    int pcx = 0, pcz = 0;
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc != null && mc.player != null) {
      pcx = mc.player.getChunkPos().x;
      pcz = mc.player.getChunkPos().z;
    }


    scheduleImmediateSectionBuild(cx, cy, cz, pcx, pcz);
    queueLightRefresh(cx, cy, cz);
    queueLightRefresh(cx - 1, cy, cz);
    queueLightRefresh(cx + 1, cy, cz);
    queueLightRefresh(cx, cy, cz - 1);
    queueLightRefresh(cx, cy, cz + 1);





    int localX = blockX & 15;
    int localY = blockY & 15;
    int localZ = blockZ & 15;
    if (localX == 0) {
      scheduleImmediateSectionBuild(cx - 1, cy, cz, pcx, pcz);
    }
    if (localX == 15) {
      scheduleImmediateSectionBuild(cx + 1, cy, cz, pcx, pcz);
    }
    if (localY == 0) {
      scheduleImmediateSectionBuild(cx, cy - 1, cz, pcx, pcz);
    }
    if (localY == 15) {
      scheduleImmediateSectionBuild(cx, cy + 1, cz, pcx, pcz);
    }
    if (localZ == 0) {
      scheduleImmediateSectionBuild(cx, cy, cz - 1, pcx, pcz);
    }
    if (localZ == 15) {
      scheduleImmediateSectionBuild(cx, cy, cz + 1, pcx, pcz);
    }
  }

  public void scheduleLightSectionRebuild(int sectionX, int sectionY,
      int sectionZ) {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    terrainDirty = true;
    int pcx = 0;
    int pcz = 0;
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc != null && mc.player != null) {
      pcx = mc.player.getChunkPos().x;
      pcz = mc.player.getChunkPos().z;
    }
    scheduleImmediateSectionBuild(sectionX, sectionY, sectionZ, pcx, pcz);
  }

  public boolean isGPUDrivenEnabled() {
    return gpuDrivenEnabled;
  }

  public MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }

  public int getLastGPUVisibleCount() {
    return lastGPUVisibleCount;
  }

  public int[] getGPUCullStats() {
    NativeBridge.nGetGPUCullStats(gpuCullStats);
    return gpuCullStats;
  }

  public int getThermalState() {
    return NativeBridge.nGetThermalState();
  }

  public int getThermalLODReduction() {
    return NativeBridge.nGetThermalLODReduction();
  }


  public boolean isLoadingMode() {
    return loadingMode;
  }


  public int getLoadingModePendingCount() {
    return loadingModePendingCount;
  }


  public int getLoadingModeMeshCount() {
    return loadingModeMeshCount;
  }
}
