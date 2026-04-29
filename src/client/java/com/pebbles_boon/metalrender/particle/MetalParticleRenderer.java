package com.pebbles_boon.metalrender.particle;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.BillboardParticleAccessor;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Queue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalParticleRenderer {
  private static final int VERTEX_STRIDE = 32;
  private static final int MAX_PARTICLE_VERTICES = 65536 * 6;
  private static final int MAX_PARTICLES_PER_FRAME = 65536;
  private long deviceHandle;
  private boolean active;
  private int frameCount;
  private ByteBuffer vertexStagingBuffer;
  private long[] metalVertexBuffers = new long[3];
  private int currentVertexCount;

  private long cachedParticlePipeline = 0;
  private long cachedParticleFallbackPipeline = 0;
  private final ArrayList<ParticleDrawCommand> pendingDrawPool = new ArrayList<>();
  private int pendingDrawCount;



  private final ArrayList<CapturedParticle> capturedParticlePool = new ArrayList<>();
  private int capturedParticleCount = 0;
  private static final int TEXTURE_CACHE_SIZE = 2048;
  private static final long TEXTURE_UNCACHED = Long.MIN_VALUE;
  private final long[] textureCache = new long[TEXTURE_CACHE_SIZE];
  private final int[] textureUploadFrame = new int[TEXTURE_CACHE_SIZE];
  {
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
  }

  private static final int ATLAS_REFRESH_FRAMES = 120;

  private ByteBuffer textureReadbackBuf = null;
  private byte[] texturePixelBuf = null;



  private static final float[][] CORNER_OFFSETS = {
      { -1.0f, -1.0f, 0.0f },
      { 1.0f, -1.0f, 0.0f },
      { 1.0f, 1.0f, 0.0f },
      { -1.0f, 1.0f, 0.0f }
  };
  private final Quaternionf bbRot = new Quaternionf();
  private final Vector3f[] bbCorners = { new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f() };
  private final Vector3f bbNormal = new Vector3f();

  private final BlockPos.Mutable reusableBlockPos = new BlockPos.Mutable();

  public MetalParticleRenderer() {
    vertexStagingBuffer = ByteBuffer.allocateDirect(MAX_PARTICLE_VERTICES * VERTEX_STRIDE)
        .order(ByteOrder.nativeOrder());
  }

  public void setDeviceAndPipeline(long device) {
    this.deviceHandle = device;
    if (device != 0) {
      for (int i = 0; i < 3; i++) {
        metalVertexBuffers[i] = NativeBridge.nCreateBuffer(
            device, MAX_PARTICLE_VERTICES * VERTEX_STRIDE, 0);
      }
      MetalLogger.info("MetalParticleRenderer initialized: device=%d vb0=%d vb1=%d vb2=%d",
          device, metalVertexBuffers[0], metalVertexBuffers[1], metalVertexBuffers[2]);
    }
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public void captureParticles(ParticleManager particleManager, Camera camera,
      float tickDelta) {
    if (!active)
      return;
    capturedParticleCount = 0;
    try {
      captureFromManager(particleManager, camera, tickDelta);
    } catch (Exception e) {
      if (frameCount < 5) {
        MetalLogger.error("Failed to capture particles: %s", e.getMessage());
        e.printStackTrace();
      }
    }
  }

  public void captureParticleList(Queue<? extends Particle> particles,
      Camera camera, float tickDelta) {
    if (!active || particles == null)
      return;
    double camX = camera.getCameraPos().x;
    double camY = camera.getCameraPos().y;
    double camZ = camera.getCameraPos().z;

    boolean cameraInWater = false;
    ClientWorld world = null;
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc != null && mc.world != null) {
      world = mc.world;
      reusableBlockPos.set(
          (int) Math.floor(camera.getCameraPos().x),
          (int) Math.floor(camera.getCameraPos().y),
          (int) Math.floor(camera.getCameraPos().z));
      cameraInWater = !mc.world.getBlockState(reusableBlockPos).getFluidState().isEmpty();
    }
    int captured = 0;
    for (Particle p : particles) {
      if (capturedParticleCount >= MAX_PARTICLES_PER_FRAME)
        break;
      if (p == null || !p.isAlive())
        continue;
      if (!(p instanceof BillboardParticle bp))
        continue;

      if (!cameraInWater && world != null) {
        ParticleAccessor pa2 = (ParticleAccessor) p;
        double px = pa2.metalrender$getX();
        double py = pa2.metalrender$getY();
        double pz = pa2.metalrender$getZ();

        boolean nearWater = false;
        for (int dy = 0; dy >= -4; dy--) {
          reusableBlockPos.set(
              (int) Math.floor(px),
              (int) Math.floor(py + dy),
              (int) Math.floor(pz));
          if (!world.getBlockState(reusableBlockPos).getFluidState().isEmpty()) {
            nearWater = true;
            break;
          }
        }
        if (nearWater) {

          continue;
        }
      }

      if (cameraInWater) {
        int hash = System.identityHashCode(p);
        if ((hash & 7) > 1) {
          continue;
        }
      }
      captured++;
      CapturedParticle cp;
      if (capturedParticleCount < capturedParticlePool.size()) {
        cp = capturedParticlePool.get(capturedParticleCount);
      } else {
        cp = new CapturedParticle();
        capturedParticlePool.add(cp);
      }
      capturedParticleCount++;
      ParticleAccessor pa = (ParticleAccessor) p;
      BillboardParticleAccessor bpa = (BillboardParticleAccessor) bp;
      cp.x = (float) (MathHelper.lerp(tickDelta, pa.metalrender$getLastX(),
          pa.metalrender$getX()) -
          camX);
      cp.y = (float) (MathHelper.lerp(tickDelta, pa.metalrender$getLastY(),
          pa.metalrender$getY()) -
          camY);
      cp.z = (float) (MathHelper.lerp(tickDelta, pa.metalrender$getLastZ(),
          pa.metalrender$getZ()) -
          camZ);
      cp.scale = bp.getSize(tickDelta);

      if (cameraInWater) {
        cp.scale *= 0.5f;
      }
      cp.red = bpa.metalrender$getRed();
      cp.green = bpa.metalrender$getGreen();
      cp.blue = bpa.metalrender$getBlue();
      cp.alpha = bpa.metalrender$getAlpha();
      cp.zRotation = MathHelper.lerp(tickDelta, bpa.metalrender$getLastZRotation(),
          bpa.metalrender$getZRotation());
      Sprite sprite = bpa.metalrender$getSprite();
      if (sprite != null) {




        cp.minU = bpa.metalrender$invokeGetMinU();
        cp.maxU = bpa.metalrender$invokeGetMaxU();
        cp.minV = bpa.metalrender$invokeGetMinV();
        cp.maxV = bpa.metalrender$invokeGetMaxV();
        if (sprite.getAtlasId() != null) {
          cp.atlasId = sprite.getAtlasId();
        }
      } else {
        cp.minU = 0;
        cp.maxU = 1;
        cp.minV = 0;
        cp.maxV = 1;
      }


      if (world != null) {
        int wpX = (int) Math.floor(pa.metalrender$getX());
        int wpY = (int) Math.floor(pa.metalrender$getY());
        int wpZ = (int) Math.floor(pa.metalrender$getZ());
        reusableBlockPos.set(wpX, wpY, wpZ);
        int blockLev = world.getLightLevel(net.minecraft.world.LightType.BLOCK, reusableBlockPos);
        int skyLev = world.getLightLevel(net.minecraft.world.LightType.SKY, reusableBlockPos);
        cp.light = ((skyLev * 16) << 16) | (blockLev * 16);
      } else {
        cp.light = 0x00F000F0;
      }
    }
    if (frameCount < 5 && captured > 0) {
      MetalLogger.info("Captured %d billboard particles from queue", captured);

      int logged = 0;
      for (int _di = 0; _di < capturedParticleCount; _di++) {
        CapturedParticle dbg = capturedParticlePool.get(_di);
        if (logged >= 5)
          break;
        MetalLogger.info("  Particle scale=%.4f class=%s pos=(%.1f,%.1f,%.1f)",
            dbg.scale, "captured", dbg.x, dbg.y, dbg.z);
        logged++;
      }
    }
  }

  private void captureFromManager(ParticleManager manager, Camera camera,
      float tickDelta) {
  }

  public void renderCapturedParticles(long frameContext) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;
    if (capturedParticleCount == 0) {
      frameCount++;
      return;
    }
    buildParticleGeometry();
    if (currentVertexCount == 0 || pendingDrawCount == 0) {
      capturedParticleCount = 0;
      frameCount++;
      return;
    }
    vertexStagingBuffer.flip();
    int uploadSize = currentVertexCount * VERTEX_STRIDE;
    long activeVB = metalVertexBuffers[frameCount % 3];
    if (activeVB != 0 && uploadSize > 0) {
      NativeBridge.nUploadBufferDataDirect(activeVB, vertexStagingBuffer, 0, uploadSize);
    }
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null) {
      frameCount++;
      return;
    }
    long particlePipeline = cachedParticlePipeline;
    if (particlePipeline == 0) {
      particlePipeline = NativeBridge.nGetParticlePipelineHandle(renderer.getHandle());
      if (particlePipeline == 0) {

        long fb = cachedParticleFallbackPipeline;
        if (fb == 0) {
          fb = NativeBridge.nGetEntityTranslucentPipelineHandle(renderer.getHandle());
          if (fb != 0)
            cachedParticleFallbackPipeline = fb;
        }
        particlePipeline = fb;
      } else {
        cachedParticlePipeline = particlePipeline;
      }
    }
    if (particlePipeline == 0) {
      if (frameCount < 5)
        MetalLogger.warn("No particle pipeline available");
      frameCount++;
      return;
    }
    NativeBridge.nSetPipelineState(frameContext, particlePipeline);
    NativeBridge.nSetChunkOffset(frameContext, 0.0f, 0.0f, 0.0f);
    NativeBridge.nSetEntityOverlay(frameContext, 0.0f, 0.0f, 1.0f);


    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    long fallbackBlockAtlas = (wr != null) ? wr.getTextureManager().getBlockAtlasTexture() : 0;
    long lastBoundTex = 0;
    if (fallbackBlockAtlas != 0) {
      NativeBridge.nBindEntityTexture(frameContext, fallbackBlockAtlas);
      lastBoundTex = fallbackBlockAtlas;
    }
    int drawsDone = 0;
    for (int _di = 0; _di < pendingDrawCount; _di++) {
      ParticleDrawCommand cmd = pendingDrawPool.get(_di);
      if (cmd.vertexCount <= 0)
        continue;
      if (cmd.glTextureId != 0) {
        long metalTex = getOrCreateMetalTexture(cmd.glTextureId);
        if (metalTex != 0 && metalTex != lastBoundTex) {
          NativeBridge.nBindEntityTexture(frameContext, metalTex);
          lastBoundTex = metalTex;
        }
      } else if (fallbackBlockAtlas != 0 && lastBoundTex != fallbackBlockAtlas) {
        NativeBridge.nBindEntityTexture(frameContext, fallbackBlockAtlas);
        lastBoundTex = fallbackBlockAtlas;
      }
      NativeBridge.nDrawEntityBuffer(frameContext, activeVB,
          cmd.vertexCount, cmd.startVertex,
          0x8);
      drawsDone++;
    }
    frameCount++;
    if (frameCount <= 5 || frameCount % 3000 == 0) {
      MetalLogger.info(
          "MetalParticleRenderer: frame %d, %d particles, %d verts, %d draws",
          frameCount, capturedParticleCount, currentVertexCount, drawsDone);
    }
    pendingDrawCount = 0;


    for (int i = 0; i < capturedParticleCount; i++)
      capturedParticlePool.get(i).atlasId = null;
    capturedParticleCount = 0;
  }

  private void buildParticleGeometry() {
    vertexStagingBuffer.clear();
    currentVertexCount = 0;
    pendingDrawCount = 0;
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null)
      return;
    Camera camera = client.gameRenderer.getCamera();
    if (camera == null)
      return;
    Quaternionf camRot = camera.getRotation();
    int currentGlTexId = -1;
    int batchStartVertex = 0;
    int batchVertexCount = 0;
    for (int _pi = 0; _pi < capturedParticleCount; _pi++) {
      CapturedParticle cp = capturedParticlePool.get(_pi);
      if (currentVertexCount + 6 > MAX_PARTICLE_VERTICES)
        break;
      int glTexId = 0;
      if (cp.atlasId != null) {
        glTexId = getGlTextureIdForAtlas(cp.atlasId);
      }
      if (glTexId != currentGlTexId && batchVertexCount > 0) {
        ParticleDrawCommand cmd;
        if (pendingDrawCount < pendingDrawPool.size()) {
          cmd = pendingDrawPool.get(pendingDrawCount);
        } else {
          cmd = new ParticleDrawCommand();
          pendingDrawPool.add(cmd);
        }
        cmd.startVertex = batchStartVertex;
        cmd.vertexCount = batchVertexCount;
        cmd.glTextureId = currentGlTexId;
        pendingDrawCount++;
        batchStartVertex = currentVertexCount;
        batchVertexCount = 0;
      }
      currentGlTexId = glTexId;
      buildBillboardQuad(cp, camRot);
      batchVertexCount += 6;
    }
    if (batchVertexCount > 0) {
      ParticleDrawCommand cmd;
      if (pendingDrawCount < pendingDrawPool.size()) {
        cmd = pendingDrawPool.get(pendingDrawCount);
      } else {
        cmd = new ParticleDrawCommand();
        pendingDrawPool.add(cmd);
      }
      cmd.startVertex = batchStartVertex;
      cmd.vertexCount = batchVertexCount;
      cmd.glTextureId = currentGlTexId;
      pendingDrawCount++;
    }
    if (frameCount < 5 && currentVertexCount > 0) {
      MetalLogger.info("Built %d particle verts in %d draw batches",
          currentVertexCount, pendingDrawCount);
    }
  }

  private void buildBillboardQuad(CapturedParticle cp, Quaternionf camRot) {

    float size = cp.scale * 0.5f;
    float x = cp.x, y = cp.y, z = cp.z;
    bbRot.set(camRot);
    if (cp.zRotation != 0.0f) {
      bbRot.rotateZ(cp.zRotation);
    }




    for (int i = 0; i < 4; i++) {
      Vector3f c = bbCorners[i];
      c.set(CORNER_OFFSETS[i][0], CORNER_OFFSETS[i][1], CORNER_OFFSETS[i][2]);
      bbRot.transform(c);
      c.mul(size);
      c.add(x, y, z);
    }
    int r = (int) (cp.red * 255.0f) & 0xFF;
    int g = (int) (cp.green * 255.0f) & 0xFF;
    int b = (int) (cp.blue * 255.0f) & 0xFF;
    int a = (int) (cp.alpha * 255.0f) & 0xFF;
    int color = (a << 24) | (r << 16) | (g << 8) | b;
    bbNormal.set(0, 0, 1);
    camRot.transform(bbNormal);
    float nx = bbNormal.x, ny = bbNormal.y, nz = bbNormal.z;
    int light = cp.light;

    float u0 = cp.maxU, u1 = cp.minU, u2 = cp.minU, u3 = cp.maxU;
    float v0 = cp.maxV, v1 = cp.maxV, v2 = cp.minV, v3 = cp.minV;

    writeParticleVertex(bbCorners[0].x, bbCorners[0].y, bbCorners[0].z, u0, v0, color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[1].x, bbCorners[1].y, bbCorners[1].z, u1, v1, color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[2].x, bbCorners[2].y, bbCorners[2].z, u2, v2, color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[0].x, bbCorners[0].y, bbCorners[0].z, u0, v0, color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[2].x, bbCorners[2].y, bbCorners[2].z, u2, v2, color, nx, ny, nz, light);
    writeParticleVertex(bbCorners[3].x, bbCorners[3].y, bbCorners[3].z, u3, v3, color, nx, ny, nz, light);
  }

  private void writeParticleVertex(float px, float py, float pz, float u,
      float v, int color, float nx, float ny,
      float nz, int light) {
    if (currentVertexCount >= MAX_PARTICLE_VERTICES ||
        vertexStagingBuffer.remaining() < VERTEX_STRIDE)
      return;
    vertexStagingBuffer.putFloat(px);
    vertexStagingBuffer.putFloat(py);
    vertexStagingBuffer.putFloat(pz);
    int iU = (int) (Math.min(Math.max(u, 0.0f), 1.0f) * 32767.0f);
    int iV = (int) (Math.min(Math.max(v, 0.0f), 1.0f) * 32767.0f);
    vertexStagingBuffer.putShort((short) (iU & 0x7FFF));
    vertexStagingBuffer.putShort((short) (iV & 0x7FFF));
    int cr = (color >> 16) & 0xFF;
    int cg = (color >> 8) & 0xFF;
    int cb = color & 0xFF;
    int ca = (color >> 24) & 0xFF;
    vertexStagingBuffer.put((byte) cr);
    vertexStagingBuffer.put((byte) cg);
    vertexStagingBuffer.put((byte) cb);
    vertexStagingBuffer.put((byte) ca);
    vertexStagingBuffer.put((byte) (int) ((nx * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte) (int) ((ny * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte) (int) ((nz * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte) 255);
    vertexStagingBuffer.putShort((short) 0);
    vertexStagingBuffer.putShort((short) 0);




    int blockL = light & 0xFFFF;
    int skyL = (light >> 16) & 0xFFFF;
    vertexStagingBuffer.putShort((short) blockL);
    vertexStagingBuffer.putShort((short) skyL);
    currentVertexCount++;
  }

  private long getOrCreateMetalTexture(int glTextureId) {
    if (glTextureId == 0 || deviceHandle == 0)
      return 0;
    boolean inBounds = glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE;
    int lastUpload = inBounds ? textureUploadFrame[glTextureId] : -1;
    boolean needsRefresh = (lastUpload < 0) ||
        (frameCount - lastUpload >= ATLAS_REFRESH_FRAMES);
    long cached = inBounds ? textureCache[glTextureId] : TEXTURE_UNCACHED;
    if (cached != TEXTURE_UNCACHED && !needsRefresh)
      return cached;
    try {
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (inBounds)
          textureCache[glTextureId] = 0L;
        return 0;
      }
      int reqSz = width * height * 4;
      if (textureReadbackBuf == null || textureReadbackBuf.capacity() < reqSz) {
        textureReadbackBuf = ByteBuffer.allocateDirect(reqSz).order(ByteOrder.nativeOrder());
        texturePixelBuf = new byte[reqSz];
      }
      ByteBuffer pixels = textureReadbackBuf;
      pixels.clear();
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      pixels.rewind();
      if (texturePixelBuf.length < reqSz)
        texturePixelBuf = new byte[reqSz];
      byte[] pixelData = texturePixelBuf;
      pixels.get(pixelData, 0, reqSz);
      if (cached != TEXTURE_UNCACHED && cached != 0) {
        NativeBridge.nUpdateTexture2D(cached, width, height, pixelData);
        if (inBounds)
          textureUploadFrame[glTextureId] = frameCount;
        return cached;
      }
      long metalTex = NativeBridge.nCreateTexture2D(deviceHandle, width, height, pixelData);
      if (inBounds) {
        textureCache[glTextureId] = metalTex;
        textureUploadFrame[glTextureId] = frameCount;
      }
      if (metalTex != 0 && frameCount < 3) {
        MetalLogger.info(
            "Created/refreshed Metal particle texture: glId=%d %dx%d handle=%d",
            glTextureId, width, height, metalTex);
      }
      return metalTex;
    } catch (Exception e) {
      MetalLogger.error("Failed to create Metal particle texture glId=%d: %s",
          glTextureId, e.getMessage());
      if (inBounds)
        textureCache[glTextureId] = 0L;
      return 0;
    }
  }

  private int getGlTextureIdForAtlas(Identifier atlasId) {
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client == null)
        return 0;
      AbstractTexture tex = client.getTextureManager().getTexture(atlasId);
      if (tex == null)
        return 0;
      com.mojang.blaze3d.textures.GpuTexture gpuTex = tex.getGlTexture();
      if (gpuTex instanceof GlTexture glTex) {
        return glTex.getGlId();
      }
    } catch (Exception e) {
    }
    return 0;
  }

  public void invalidateTextureCache() {
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
    MetalLogger.info("Particle texture cache invalidated");
  }

  public int getLastParticleCount() {
    return capturedParticleCount;
  }

  public int getLastVertexCount() {
    return currentVertexCount;
  }

  public void shutdown() {
    active = false;
    cachedParticlePipeline = 0;
    cachedParticleFallbackPipeline = 0;
    textureReadbackBuf = null;
    texturePixelBuf = null;
    for (int i = 0; i < capturedParticleCount; i++)
      capturedParticlePool.get(i).atlasId = null;
    capturedParticleCount = 0;
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    java.util.Arrays.fill(textureUploadFrame, -1);
    for (int i = 0; i < 3; i++) {
      if (metalVertexBuffers[i] != 0) {
        NativeBridge.nDestroyBuffer(metalVertexBuffers[i]);
        metalVertexBuffers[i] = 0;
      }
    }
    deviceHandle = 0;
    MetalLogger.info("MetalParticleRenderer shut down");
  }

  private static class CapturedParticle {
    float x, y, z;
    float scale;
    float red, green, blue, alpha;
    float zRotation;
    float minU, maxU, minV, maxV;
    int light;
    Identifier atlasId;
  }

  private static class ParticleDrawCommand {
    int startVertex;
    int vertexCount;
    int glTextureId;
  }
}
