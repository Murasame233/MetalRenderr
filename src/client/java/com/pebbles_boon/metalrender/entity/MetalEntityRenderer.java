package com.pebbles_boon.metalrender.entity;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalEntityRenderer {
  private static final int ENTITY_VERTEX_STRIDE = 32;
  private static final int MAX_BATCH_VERTICES = 262144;
  private static final int MAX_ENTITIES_PER_FRAME = 512;
  private long deviceHandle;
  private boolean active;
  private int frameCount;
  private long lastEntityLogTime = 0;
  private int captureCallsPerSec = 0;
  private int renderCallsPerSec = 0;
  private int entitiesCapturedPerSec = 0;
  private ByteBuffer vertexStagingBuffer;
  private long[] metalVertexBuffers = new long[3];
  private int currentVertexCount;

  private long cachedEntityPipeline = 0;
  private MetalVertexConsumer metalVertexConsumer;
  private final ArrayList<EntityDrawCommand> pendingDrawPool = new ArrayList<>();
  private int pendingDrawCount;
  private final ArrayList<CapturedEntity> capturedEntityPool = new ArrayList<>();
  private int capturedEntityCount;
  private static final int TEXTURE_CACHE_SIZE = 2048;
  private static final long TEXTURE_UNCACHED = Long.MIN_VALUE;
  private final long[] textureCache = new long[TEXTURE_CACHE_SIZE];
  {
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
  }
  private final MatrixStack matrixStack = new MatrixStack();
  private final net.minecraft.client.render.state.CameraRenderState reusableCameraRenderState = new net.minecraft.client.render.state.CameraRenderState();

  private MetalRenderCommandQueue reusableCmdQueue;

  public MetalEntityRenderer() {
    vertexStagingBuffer = ByteBuffer.allocateDirect(MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE)
        .order(ByteOrder.nativeOrder());
    metalVertexConsumer = new MetalVertexConsumer(vertexStagingBuffer, MAX_BATCH_VERTICES);
    MetalLogger.info("[BUILD_V8] MetalEntityRenderer constructed - perf/water/nonfull fixes");
  }

  public void setDeviceAndPipeline(long device, long pipeline) {
    this.deviceHandle = device;
    if (device != 0) {
      for (int i = 0; i < 3; i++) {
        metalVertexBuffers[i] = NativeBridge.nCreateBuffer(
            device, MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE, 0);
      }
      MetalLogger.info("[BUILD_V8] MetalEntityRenderer initialized: device=%d vb0=%d vb1=%d vb2=%d",
          device, metalVertexBuffers[0], metalVertexBuffers[1], metalVertexBuffers[2]);
    }
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public boolean hasVisibleSubmergedEntities() {
    for (int index = 0; index < capturedEntityCount; index++) {
      if (capturedEntityPool.get(index).isSubmerged) {
        return true;
      }
    }
    return false;
  }

  public void captureEntity(Entity entity, float tickDelta,
      Matrix4f modelMatrix) {
    if (!active || entity == null)
      return;
    if (capturedEntityCount >= MAX_ENTITIES_PER_FRAME)
      return;
    entitiesCapturedPerSec++;
    captureCallsPerSec++;
    CapturedEntity captured;
    if (capturedEntityCount < capturedEntityPool.size()) {
      captured = capturedEntityPool.get(capturedEntityCount);
    } else {
      captured = new CapturedEntity();
      capturedEntityPool.add(captured);
    }
    captured.entity = entity;
    captured.tickDelta = tickDelta;
    captured.modelMatrix.set(modelMatrix);
    captured.isHurt = entity instanceof LivingEntity le && le.hurtTime > 0;
    captured.hurtFactor = captured.isHurt ? ((LivingEntity) entity).hurtTime / 10.0f : 0.0f;
    captured.isSubmerged = entity.isSubmergedInWater() || entity.isTouchingWater();
    capturedEntityCount++;
  }

  @SuppressWarnings("unchecked")
  public void buildEntityMeshes(long frameContext) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;
    metalVertexConsumer.reset();
    currentVertexCount = 0;
    pendingDrawCount = 0;
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null || client.world == null)
      return;
    EntityRenderManager renderManager = client.getEntityRenderDispatcher();
    if (renderManager == null)
      return;
    Camera camera = client.gameRenderer.getCamera();
    double camX, camY, camZ;
    float currentTickDelta;
    if (camera != null && camera.getCameraPos() != null) {
      camX = camera.getCameraPos().x;
      camY = camera.getCameraPos().y;
      camZ = camera.getCameraPos().z;
    } else if (CapturedMatrices.isValid()) {
      camX = CapturedMatrices.getCamX();
      camY = CapturedMatrices.getCamY();
      camZ = CapturedMatrices.getCamZ();
    } else {
      camX = camY = camZ = 0;
    }
    currentTickDelta = client.getRenderTickCounter().getTickProgress(true);
    int entitiesRendered = 0;
    int modelCaptures = 0;
    int boxFallbacks = 0;
    for (int _ei = 0; _ei < capturedEntityCount; _ei++) {
      CapturedEntity captured = capturedEntityPool.get(_ei);
      Entity entity = captured.entity;
      if (entity == null || !entity.isAlive())
        continue;
      int startVertex = metalVertexConsumer.getVertexCount();
      boolean usedModel = false;
      try {
        usedModel = renderEntityModel(entity, captured, renderManager, camX,
            camY, camZ, currentTickDelta);
      } catch (Exception e) {
        if (frameCount < 5) {
          MetalLogger.warn("Failed to render entity model for %s: %s",
              entity.getType().toString(), e.getMessage());
        }
      }
      if (!usedModel || metalVertexConsumer.getVertexCount() == startVertex) {

        if (entity instanceof ItemEntity itemEntity) {
          boolean rendered = renderItemEntity(itemEntity, captured, camX, camY, camZ, currentTickDelta);
          if (rendered) {
            int vCount = metalVertexConsumer.getVertexCount() - startVertex;
            if (vCount > 0) {
              EntityDrawCommand dcmd;
              if (pendingDrawCount < pendingDrawPool.size()) {
                dcmd = pendingDrawPool.get(pendingDrawCount);
              } else {
                dcmd = new EntityDrawCommand();
                pendingDrawPool.add(dcmd);
              }
              dcmd.startVertex = startVertex;
              dcmd.vertexCount = vCount;
              dcmd.hurtFactor = captured.isHurt ? captured.hurtFactor : 0.0f;
              dcmd.whiteFlash = 0.0f;
              dcmd.renderFlags = 0;
              dcmd.glTextureId = captured.glTextureId;
              dcmd.isSubmerged = captured.isSubmerged;
              pendingDrawCount++;
              entitiesRendered++;
              modelCaptures++;
            }
          }
          continue;
        }
        buildEntityQuads(entity, captured, camX, camY, camZ);
        boxFallbacks++;
        if (captured.glTextureId == 0) {
          try {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            EntityRenderer fallbackRenderer = (EntityRenderer) renderManager.getRenderer(entity);
            if (fallbackRenderer != null) {
              Object fallbackStateObj = fallbackRenderer.getAndUpdateRenderState(entity, currentTickDelta);
              if (fallbackStateObj != null) {
                for (java.lang.reflect.Method m : fallbackRenderer.getClass().getMethods()) {
                  if (m.getName().equals("getTexture") && m.getParameterCount() == 1) {
                    Object rawTex = m.invoke(fallbackRenderer, fallbackStateObj);
                    if (rawTex instanceof Identifier texId) {
                      AbstractTexture mcTex = MinecraftClient.getInstance()
                          .getTextureManager().getTexture(texId);
                      if (mcTex != null) {
                        com.mojang.blaze3d.textures.GpuTexture gpuTex = mcTex.getGlTexture();
                        if (gpuTex instanceof GlTexture glTex) {
                          captured.glTextureId = glTex.getGlId();
                        }
                      }
                    }
                    break;
                  }
                }
              }
            }
          } catch (Exception ignored) {
          }
        }
      } else {
        modelCaptures++;
      }
      int verticesAdded = metalVertexConsumer.getVertexCount() - startVertex;
      if (verticesAdded > 0) {
        EntityDrawCommand cmd;
        if (pendingDrawCount < pendingDrawPool.size()) {
          cmd = pendingDrawPool.get(pendingDrawCount);
        } else {
          cmd = new EntityDrawCommand();
          pendingDrawPool.add(cmd);
        }
        cmd.startVertex = startVertex;
        cmd.vertexCount = verticesAdded;
        cmd.hurtFactor = captured.hurtFactor;
        cmd.whiteFlash = 0.0f;
        cmd.renderFlags = 0;
        cmd.glTextureId = captured.glTextureId;
        cmd.isSubmerged = captured.isSubmerged;
        pendingDrawCount++;
        entitiesRendered++;
      }
    }
    currentVertexCount = metalVertexConsumer.getVertexCount();
    if (frameCount < 3 && entitiesRendered > 0) {
      MetalLogger.info(
          "[BUILD_V8] buildEntityMeshes: %d entities (%d model, %d box) -> %d verts",
          entitiesRendered, modelCaptures, boxFallbacks, currentVertexCount);
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private boolean renderEntityModel(Entity entity, CapturedEntity captured,
      EntityRenderManager renderManager,
      double camX, double camY, double camZ,
      float tickDelta) {
    @SuppressWarnings("unchecked")
    EntityRenderer renderer = (EntityRenderer) renderManager.getRenderer(entity);
    if (renderer == null)
      return false;
    if (!(renderer instanceof LivingEntityRenderer livingRenderer))
      return false;
    EntityRenderState state;
    try {
      state = (EntityRenderState) livingRenderer.getAndUpdateRenderState(
          entity, tickDelta);
    } catch (Exception e) {
      return false;
    }
    if (state == null)
      return false;
    if (!(state instanceof LivingEntityRenderState livingState))
      return false;
    matrixStack.loadIdentity();
    Vec3d lerpedPos = entity.getLerpedPos(tickDelta);
    double ex = lerpedPos.x - camX;
    double ey = lerpedPos.y - camY;
    double ez = lerpedPos.z - camZ;
    Vec3d offset = renderer.getPositionOffset(state);
    ex += offset.x;
    ey += offset.y;
    ez += offset.z;
    matrixStack.translate((float) ex, (float) ey, (float) ez);
    if (frameCount % 3000 == 1) {
      MetalLogger.info("[ENTITY_DIAG_V8] entity=%s bodyYaw=%.1f baseScale=%.2f livingState=true",
          entity.getType().toString(), livingState.bodyYaw, livingState.baseScale);
    }
    int light = state.light;
    if (light == 0) {
      light = 0x00F000F0;
    }
    MetalRenderCommandQueue cmdQueue;
    if (reusableCmdQueue == null) {
      reusableCmdQueue = new MetalRenderCommandQueue(metalVertexConsumer, light);
    } else {
      reusableCmdQueue.reset(metalVertexConsumer, light);
    }
    cmdQueue = reusableCmdQueue;
    MinecraftClient client = MinecraftClient.getInstance();
    Camera camera = client != null ? client.gameRenderer.getCamera() : null;
    if (camera != null) {
      Vec3d cameraPos = camera.getCameraPos();
      reusableCameraRenderState.pos = cameraPos;
      reusableCameraRenderState.blockPos = net.minecraft.util.math.BlockPos.ofFloored(cameraPos);
      reusableCameraRenderState.entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
      reusableCameraRenderState.initialized = true;
    } else {
      reusableCameraRenderState.pos = Vec3d.ZERO;
      reusableCameraRenderState.blockPos = net.minecraft.util.math.BlockPos.ORIGIN;
      reusableCameraRenderState.entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
      reusableCameraRenderState.initialized = false;
    }
    try {
      livingRenderer.render(livingState, matrixStack, cmdQueue, reusableCameraRenderState);
    } catch (Exception e) {
      return false;
    }
    try {
      @SuppressWarnings("unchecked")
      LivingEntityRenderer<?, LivingEntityRenderState, ?> typedRenderer = (LivingEntityRenderer<?, LivingEntityRenderState, ?>) livingRenderer;
      Identifier texId = typedRenderer.getTexture(livingState);
      if (texId != null) {
        AbstractTexture mcTex = MinecraftClient.getInstance().getTextureManager().getTexture(
            texId);
        if (mcTex != null) {
          com.mojang.blaze3d.textures.GpuTexture gpuTex = mcTex.getGlTexture();
          if (gpuTex instanceof GlTexture glTex) {
            captured.glTextureId = glTex.getGlId();
          }
        }
      }
    } catch (Exception e) {
    }
    return true;
  }

  private static final Identifier ITEMS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/items.png");

  private boolean renderItemEntity(ItemEntity entity, CapturedEntity captured,
      double camX, double camY, double camZ, float tickDelta) {
    try {
      ItemStack stack = entity.getStack();
      if (stack == null || stack.isEmpty())
        return false;
      MinecraftClient client = MinecraftClient.getInstance();
      if (client == null)
        return false;

      Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
      Sprite sprite = null;
      AbstractTexture itemsTexture = client.getTextureManager().getTexture(ITEMS_ATLAS_ID);
      if (itemsTexture instanceof SpriteAtlasTexture itemsAtlas) {
        com.pebbles_boon.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor accessor = (com.pebbles_boon.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor) itemsAtlas;
        java.util.Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
        if (sprites != null) {
          sprite = sprites.get(Identifier.of(itemId.getNamespace(), "item/" + itemId.getPath()));
        }
      }

      if (itemsTexture != null) {
        com.mojang.blaze3d.textures.GpuTexture gpuTex = itemsTexture.getGlTexture();
        if (gpuTex instanceof GlTexture glTex) {
          captured.glTextureId = glTex.getGlId();
        }
      }
      float ex = (float) (MathHelper.lerp(tickDelta, entity.lastX, entity.getX()) - camX);
      float ey = (float) (MathHelper.lerp(tickDelta, entity.lastY, entity.getY()) - camY);
      float ez = (float) (MathHelper.lerp(tickDelta, entity.lastZ, entity.getZ()) - camZ);

      float age = entity.getItemAge() + tickDelta;
      float spinAngle = age / 20.0f * 57.2957795f;
      float bobY = (float) (Math.sin(age / 10.0f) * 0.1f + 0.1f);
      ey += bobY;
      float sinSpin = (float) Math.sin(Math.toRadians(spinAngle));
      float cosSpin = (float) Math.cos(Math.toRadians(spinAngle));
      float hw = 0.125f;
      int light = 0x00F000F0;
      int color = 0xFFFFFFFF;

      float u0, u1, v0, v1;
      if (sprite != null) {
        u0 = sprite.getMinU();
        u1 = sprite.getMaxU();
        v0 = sprite.getMinV();
        v1 = sprite.getMaxV();
      } else {

        u0 = 0.0f;
        u1 = 0.0625f;
        v0 = 0.0f;
        v1 = 0.0625f;
      }
      float nx = sinSpin, nz = cosSpin;

      float x0 = ex + cosSpin * (-hw), z0 = ez + sinSpin * (-hw);
      float x1 = ex + cosSpin * (hw), z1 = ez + sinSpin * (hw);
      float y0 = ey, y1 = ey + hw * 2;
      metalVertexConsumer.vertex(x0, y0, z0, color, u0, v1, 0, light, nx, 0, nz);
      metalVertexConsumer.vertex(x1, y0, z1, color, u1, v1, 0, light, nx, 0, nz);
      metalVertexConsumer.vertex(x1, y1, z1, color, u1, v0, 0, light, nx, 0, nz);
      metalVertexConsumer.vertex(x0, y1, z0, color, u0, v0, 0, light, nx, 0, nz);

      metalVertexConsumer.vertex(x1, y0, z1, color, u1, v1, 0, light, -nx, 0, -nz);
      metalVertexConsumer.vertex(x0, y0, z0, color, u0, v1, 0, light, -nx, 0, -nz);
      metalVertexConsumer.vertex(x0, y1, z0, color, u0, v0, 0, light, -nx, 0, -nz);
      metalVertexConsumer.vertex(x1, y1, z1, color, u1, v0, 0, light, -nx, 0, -nz);
      return true;
    } catch (Exception e) {
      if (frameCount < 5) {
        MetalLogger.warn("renderItemEntity failed: %s", e.getMessage());
      }
      return false;
    }
  }

  private void buildEntityQuads(Entity entity, CapturedEntity captured,
      double camX, double camY, double camZ) {
    float tickDelta = captured.tickDelta;
    float ex = (float) (MathHelper.lerp(tickDelta, entity.lastX, entity.getX()) - camX);
    float ey = (float) (MathHelper.lerp(tickDelta, entity.lastY, entity.getY()) - camY);
    float ez = (float) (MathHelper.lerp(tickDelta, entity.lastZ, entity.getZ()) - camZ);
    float halfW = entity.getWidth() * 0.5f;
    float height = entity.getHeight();
    float x0 = ex - halfW, y0 = ey, z0 = ez - halfW;
    float x1 = ex + halfW, y1 = ey + height, z1 = ez + halfW;
    int color = 0xFFFFFFFF;
    if (captured.isHurt) {
      int gb = (int) (255 * (1.0f - captured.hurtFactor * 0.6f));
      color = (255 << 24) | (255 << 16) | (gb << 8) | gb;
    }
    int light = 0x00F000F0;
    emitQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, color,
        light);
    emitQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, color,
        light);
    emitQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, color,
        light);
    emitQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0, color,
        light);
    emitQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, color,
        light);
    emitQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, color,
        light);
  }

  private void emitQuad(float x0, float y0, float z0, float x1, float y1,
      float z1, float x2, float y2, float z2, float x3,
      float y3, float z3, float nx, float ny, float nz,
      int color, int light) {
    metalVertexConsumer.vertex(x0, y0, z0, color, 0, 0, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x1, y1, z1, color, 1, 0, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x2, y2, z2, color, 1, 1, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x3, y3, z3, color, 0, 1, 0, light, nx, ny, nz);
  }

  public void renderCapturedEntities(long frameContext, boolean cameraInWater) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;
    renderCallsPerSec++;
    long now = System.currentTimeMillis();
    if (now - lastEntityLogTime >= 10000) {
      MetalLogger.info(
          "Entity stats: captures=%d/10s renders=%d/10s entities=%d/10s",
          captureCallsPerSec, renderCallsPerSec, entitiesCapturedPerSec);
      captureCallsPerSec = 0;
      renderCallsPerSec = 0;
      entitiesCapturedPerSec = 0;
      lastEntityLogTime = now;
    }
    buildEntityMeshes(frameContext);
    if (currentVertexCount == 0 || pendingDrawCount == 0)
      return;
    vertexStagingBuffer.flip();
    int uploadSize = currentVertexCount * ENTITY_VERTEX_STRIDE;
    long activeVB = metalVertexBuffers[frameCount % 3];
    if (activeVB != 0 && uploadSize > 0) {
      NativeBridge.nUploadBufferDataDirect(activeVB, vertexStagingBuffer, 0, uploadSize);
    }
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null) {
      if (frameCount < 5)
        MetalLogger.warn("MetalEntityRenderer: renderer null");
      return;
    }
    long entityPipeline = cachedEntityPipeline;
    if (entityPipeline == 0) {

      entityPipeline = NativeBridge.nGetEntityPipelineHandle(renderer.getHandle());
      if (entityPipeline != 0) {
        cachedEntityPipeline = entityPipeline;
      }
    }
    if (entityPipeline != 0) {
      NativeBridge.nSetPipelineState(frameContext, entityPipeline);
      if (frameCount < 3) {
        MetalLogger.info("Using entity pipeline: %d", entityPipeline);
      }
    } else {
      long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
      if (inhousePipeline == 0) {
        if (frameCount < 5)
          MetalLogger.warn("MetalEntityRenderer: no pipeline available");
      } else {
        NativeBridge.nSetPipelineState(frameContext, inhousePipeline);
        if (frameCount < 3) {
          MetalLogger.warn("FALLING BACK to terrain pipeline for entities! " +
              "entityPipeline=0");
        }
      }
    }
    NativeBridge.nSetChunkOffset(frameContext, 0.0f, 0.0f, 0.0f);
    int drawsDone = 0;
    float lastHurt = -1.0f, lastFlash = -1.0f;
    float lastWaterFog = -1.0f;
    long lastBoundTex = -1;
    for (int _di = 0; _di < pendingDrawCount; _di++) {
      EntityDrawCommand cmd = pendingDrawPool.get(_di);
      if (cmd.vertexCount <= 0)
        continue;
      if (cmd.hurtFactor != lastHurt || cmd.whiteFlash != lastFlash) {
        NativeBridge.nSetEntityOverlay(frameContext, cmd.hurtFactor,
            cmd.whiteFlash, 1.0f);
        lastHurt = cmd.hurtFactor;
        lastFlash = cmd.whiteFlash;
      }
      float wf = (cameraInWater || cmd.isSubmerged) ? 1.0f : 0.0f;
      if (wf != lastWaterFog) {
        NativeBridge.nSetWaterFog(frameContext, wf);
        lastWaterFog = wf;
      }
      if (cmd.glTextureId != 0) {
        long metalTex = getOrCreateMetalTexture(cmd.glTextureId);
        if (metalTex != 0 && metalTex != lastBoundTex) {
          NativeBridge.nBindEntityTexture(frameContext, metalTex);
          lastBoundTex = metalTex;
        }
      }
      NativeBridge.nDrawEntityBuffer(frameContext, activeVB,
          cmd.vertexCount, cmd.startVertex,
          cmd.renderFlags);
      drawsDone++;
    }
    frameCount++;
    if (frameCount <= 5 || frameCount % 3000 == 0) {
      MetalLogger.info(
          "MetalEntityRenderer: frame %d, %d entities, %d verts, %d draws",
          frameCount, pendingDrawCount, currentVertexCount, drawsDone);
    }
    pendingDrawCount = 0;

    for (int _ci = 0; _ci < capturedEntityCount; _ci++) {
      capturedEntityPool.get(_ci).entity = null;
    }
    capturedEntityCount = 0;
  }

  private long getOrCreateMetalTexture(int glTextureId) {
    if (glTextureId == 0 || deviceHandle == 0)
      return 0;
    if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE) {
      long cached = textureCache[glTextureId];
      if (cached != TEXTURE_UNCACHED)
        return cached;
    }
    try {
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE)
          textureCache[glTextureId] = 0L;
        return 0;
      }
      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      byte[] pixelData = new byte[width * height * 4];
      pixels.get(pixelData);
      long metalTex = NativeBridge.nCreateTexture2D(deviceHandle, width, height, pixelData);
      if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE)
        textureCache[glTextureId] = metalTex;
      if (metalTex != 0 && frameCount < 10) {
        int nonWhite = 0;
        int nonBlack = 0;
        int transparent = 0;
        int fullyOpaque = 0;
        int totalPixels = pixelData.length / 4;
        int sampleCount = Math.min(256, totalPixels);
        for (int i = 0; i < sampleCount; i++) {
          int r = pixelData[i * 4] & 0xFF;
          int g = pixelData[i * 4 + 1] & 0xFF;
          int b = pixelData[i * 4 + 2] & 0xFF;
          int a = pixelData[i * 4 + 3] & 0xFF;
          if (r < 250 || g < 250 || b < 250)
            nonWhite++;
          if (r > 5 || g > 5 || b > 5)
            nonBlack++;
          if (a < 128)
            transparent++;
          if (a == 255)
            fullyOpaque++;
        }
        StringBuilder firstPixels = new StringBuilder();
        for (int i = 0; i < Math.min(4, totalPixels); i++) {
          int r = pixelData[i * 4] & 0xFF;
          int g = pixelData[i * 4 + 1] & 0xFF;
          int b = pixelData[i * 4 + 2] & 0xFF;
          int a = pixelData[i * 4 + 3] & 0xFF;
          firstPixels.append(String.format("[%d,%d,%d,%d]", r, g, b, a));
        }
        MetalLogger.info(
            "Entity texture: glId=%d size=%dx%d metal=%d " +
                "of%d: nonWhite=%d nonBlack=%d transparent=%d opaque=%d px:%s",
            glTextureId, width, height, metalTex, sampleCount, nonWhite,
            nonBlack, transparent, fullyOpaque, firstPixels.toString());
      }
      return metalTex;
    } catch (Exception e) {
      MetalLogger.error("Failed to create Metal entity texture for glId=%d: %s",
          glTextureId, e.getMessage());
      if (glTextureId >= 0 && glTextureId < TEXTURE_CACHE_SIZE)
        textureCache[glTextureId] = 0L;
      return 0;
    }
  }

  public void invalidateTextureCache() {
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    MetalLogger.info("Entity texture cache invalidated");
  }

  public int getLastEntityCount() {
    return pendingDrawCount;
  }

  public int getLastVertexCount() {
    return currentVertexCount;
  }

  public void shutdown() {
    active = false;
    cachedEntityPipeline = 0;
    capturedEntityCount = 0;
    pendingDrawCount = 0;
    for (int _ti = 0; _ti < TEXTURE_CACHE_SIZE; _ti++) {
      long h = textureCache[_ti];
      if (h > 0)
        NativeBridge.nDestroyTexture2D(h);
    }
    java.util.Arrays.fill(textureCache, TEXTURE_UNCACHED);
    for (int i = 0; i < 3; i++) {
      if (metalVertexBuffers[i] != 0) {
        NativeBridge.nDestroyBuffer(metalVertexBuffers[i]);
        metalVertexBuffers[i] = 0;
      }
    }
    deviceHandle = 0;
    MetalLogger.info("MetalEntityRenderer shut down");
  }

  private static class CapturedEntity {
    Entity entity;
    float tickDelta;
    final Matrix4f modelMatrix = new Matrix4f();
    boolean isHurt;
    float hurtFactor;
    int glTextureId;
    boolean isSubmerged;
  }

  private static class EntityDrawCommand {
    int startVertex;
    int vertexCount;
    float hurtFactor;
    float whiteFlash;
    int renderFlags;
    int glTextureId;
    boolean isSubmerged;
  }
}
