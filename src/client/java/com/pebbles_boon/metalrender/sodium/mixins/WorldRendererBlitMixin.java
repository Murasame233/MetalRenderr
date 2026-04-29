package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.WorldRendererCrackAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.FramePass;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererBlitMixin {
  @Shadow
  private DefaultFramebufferSet framebufferSet;
  @Unique
  private int metalrender$blitFrameCount = 0;
  @Unique
  private int metalrender$tmpFbo = 0;
  @Unique
  private long metalrender$renderEndNanos = 0;
  @Unique
  private long metalrender$javaProfileAcc = 0;
  @Unique
  private int metalrender$javaProfileCount = 0;
  @Unique
  private long metalrender$frameTimingAcc = 0;
  @Unique
  private long metalrender$beginFrameAcc = 0;
  @Unique
  private long metalrender$endFrameAcc = 0;
  @Unique
  private int metalrender$frameTimingCount = 0;

  @Unique
  private final Matrix4f metalrender$reusableMv = new Matrix4f();

  @Unique
  private WorldRenderState metalrender$capturedState;

  @Inject(method = "renderMain", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$doMetalRender(FrameGraphBuilder frameGraphBuilder,
      Frustum frustum, Matrix4f posMatrix,
      GpuBufferSlice fogBuffer,
      boolean renderBlockOutline,
      WorldRenderState state,
      RenderTickCounter tickCounter,
      Profiler profiler, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled())
      return;
    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.shouldRenderWithMetal())
      return;
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      Camera camera = client.gameRenderer.getCamera();
      if (camera == null || camera.getCameraPos() == null)
        return;
      float tickDelta = tickCounter.getTickProgress(true);
      float fov = client.options.getFov().getValue().floatValue();
      Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);
      Matrix4f mv = metalrender$reusableMv;
      mv.identity();
      mv.rotateX((float) Math.toRadians(camera.getPitch()));
      mv.rotateY((float) Math.toRadians(camera.getYaw() + 180.0f));
      CapturedMatrices.capture(proj, mv, camera.getCameraPos().x,
          camera.getCameraPos().y,
          camera.getCameraPos().z);
      long t0 = System.nanoTime();

      worldRenderer.beginFrame(camera, tickDelta, proj, mv);
      long t1 = System.nanoTime();
      worldRenderer.endFrame();
      long t2 = System.nanoTime();
      metalrender$renderEndNanos = t2;
      metalrender$beginFrameAcc += (t1 - t0);
      metalrender$endFrameAcc += (t2 - t1);
      metalrender$frameTimingCount++;
      if (metalrender$frameTimingCount >= 60) {
        double bfMs = metalrender$beginFrameAcc / (metalrender$frameTimingCount * 1e6);
        double efMs = metalrender$endFrameAcc / (metalrender$frameTimingCount * 1e6);
        MetalLogger.info("RENDER_TIMING: beginFrame=%.2fms endFrame=%.2fms (avg/%d)",
            bfMs, efMs, metalrender$frameTimingCount);
        metalrender$beginFrameAcc = 0;
        metalrender$endFrameAcc = 0;
        metalrender$frameTimingCount = 0;
      }
    } catch (Exception e) {
      MetalLogger.error("[WorldRendererBlitMixin] Metal render failed: %s",
          e.getMessage());
    }
    metalrender$capturedState = state;
    metalrender$addBlitPassInline(frameGraphBuilder, worldRenderer);
    ci.cancel();
  }

  @Unique
  private int metalrender$cachedPrevDrawFbo = -1;
  @Unique
  private int metalrender$cachedColorTexId = -1;
  @Unique
  private boolean metalrender$tmpFboReady = false;

  @Unique
  private void metalrender$addBlitPassInline(FrameGraphBuilder frameGraphBuilder,
      MetalWorldRenderer worldRenderer) {
    FramePass pass = frameGraphBuilder.createPass("metalrender_blit");
    this.framebufferSet.mainFramebuffer = pass.transfer(this.framebufferSet.mainFramebuffer);
    pass.setRenderer(() -> {
      long blitStartNanos = System.nanoTime();
      long mcGapNanos = (metalrender$renderEndNanos > 0)
          ? (blitStartNanos - metalrender$renderEndNanos)
          : 0;
      net.minecraft.client.gl.Framebuffer mainFb = MinecraftClient.getInstance().getFramebuffer();
      int prevDrawFbo;
      if (metalrender$cachedPrevDrawFbo < 0) {
        prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        metalrender$cachedPrevDrawFbo = prevDrawFbo;
      } else {
        prevDrawFbo = metalrender$cachedPrevDrawFbo;
      }
      boolean boundColorFbo = false;
      MinecraftClient client = MinecraftClient.getInstance();
      int fbWidth = client.getWindow().getFramebufferWidth();
      int fbHeight = client.getWindow().getFramebufferHeight();
      if (mainFb != null && mainFb.getColorAttachment() instanceof GlTexture glTex) {
        int texId = glTex.getGlId();





        if (texId != metalrender$cachedColorTexId) {
          if (metalrender$tmpFbo == 0) {
            metalrender$tmpFbo = GL30.glGenFramebuffers();
          }
          GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, metalrender$tmpFbo);
          GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
              GL30.GL_COLOR_ATTACHMENT0,
              GL11.GL_TEXTURE_2D, texId, 0);
          int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
          metalrender$tmpFboReady = (status == GL30.GL_FRAMEBUFFER_COMPLETE);
          if (metalrender$tmpFboReady) {
            metalrender$cachedColorTexId = texId;
          } else {
            metalrender$cachedColorTexId = -1;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
          }
        }
        if (metalrender$tmpFboReady) {
          GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, metalrender$tmpFbo);
          boundColorFbo = true;
        }
      }
      {
        MetalRenderer renderer = MetalRenderClient.getRenderer();
        long handle = (renderer != null) ? renderer.getHandle() : 0;
        if (handle != 0) {
          int built = worldRenderer.buildMeshesDuringWait(handle);
          if (built > 0 && (metalrender$blitFrameCount % 120 == 0)) {
            MetalLogger.info("WAIT_BUILD: built %d meshes during GPU wait", built);
          }
        }
      }
      worldRenderer.forceBlitNow();
      if (boundColorFbo) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
      }







      try {
        WorldRenderState capturedState = metalrender$capturedState;
        if (capturedState != null
            && !capturedState.breakingBlockRenderStates.isEmpty()) {
          if (metalrender$blitFrameCount <= 5 || metalrender$blitFrameCount % 600 == 0) {
            MetalLogger.info("[BlockCrack] Attempting crack render: %d blocks breaking (frame %d)",
                capturedState.breakingBlockRenderStates.size(), metalrender$blitFrameCount);
          }
          MinecraftClient mc2 = MinecraftClient.getInstance();
          Camera camera2 = (mc2 != null) ? mc2.gameRenderer.getCamera() : null;
          if (camera2 != null) {


            if (capturedState.cameraRenderState == null) {
              capturedState.cameraRenderState = new net.minecraft.client.render.state.CameraRenderState();
            }
            capturedState.cameraRenderState.pos = camera2.getCameraPos();
            capturedState.cameraRenderState.initialized = true;

            WorldRendererCrackAccessor crackAcc = (WorldRendererCrackAccessor) (Object) this;




            MatrixStack ms = new MatrixStack();
            ms.peek().getPositionMatrix().set(CapturedMatrices.getModelView());
            VertexConsumerProvider.Immediate immediate = crackAcc.metalrender$getBufferBuilders()
                .getEntityVertexConsumers();
            crackAcc.metalrender$renderBlockDamage(ms, immediate, capturedState);
            immediate.draw();
          }
        }
      } catch (Exception ex) {

        if (metalrender$blitFrameCount <= 60 || metalrender$blitFrameCount % 600 == 0) {
          StringBuilder sb = new StringBuilder("[BlockCrack] GL render failed (frame ");
          sb.append(metalrender$blitFrameCount).append("): ");
          sb.append(ex);
          for (StackTraceElement el : ex.getStackTrace()) {
            sb.append("\n  at ").append(el);
          }
          MetalLogger.error(sb.toString());
        }
      }
      long blitEndNanos = System.nanoTime();
      double blitMs = (blitEndNanos - blitStartNanos) / 1_000_000.0;
      double gapMs = mcGapNanos / 1_000_000.0;
      metalrender$javaProfileAcc += (blitEndNanos - blitStartNanos) + mcGapNanos;
      metalrender$javaProfileCount++;
      if (metalrender$javaProfileCount >= 120) {
        MetalLogger.info("JAVA_PROFILE: mcGap=%.2fms blit=%.2fms (avg over %d frames)",
            gapMs, blitMs, metalrender$javaProfileCount);
        metalrender$javaProfileAcc = 0;
        metalrender$javaProfileCount = 0;
      }
      metalrender$blitFrameCount++;
      if (metalrender$blitFrameCount <= 3 ||
          metalrender$blitFrameCount % 600 == 0) {
        MetalLogger.info(
            "[WorldRendererBlitMixin] Blit pass (frame %d, color=%s, "
                + "drawFBO=%d, %dx%d)",
            metalrender$blitFrameCount, boundColorFbo, prevDrawFbo,
            fbWidth, fbHeight);
      }
    });
  }
}
