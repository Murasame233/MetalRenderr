package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.SectionRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderState.class)
public class WorldRendererTerrainMixin {
<<<<<<< HEAD
  @Inject(method = "renderSection", at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$skipVanillaTerrain(BlockRenderLayerGroup layerGroup,
      GpuSampler sampler, CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
      if (wr != null && wr.shouldRenderWithMetal()) {
        ci.cancel();
=======
  @Unique
  private int metalrender$skippedTerrainGroups = 0;
  @Unique
  private boolean metalrender$loggedWaitingForMetalDraw = false;

  @Unique
  private boolean metalrender$shouldSkipVanillaTerrain() {
    if (!MetalRenderClient.isEnabled()) {
      metalrender$loggedWaitingForMetalDraw = false;
      return false;
    }
    MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
    if (wr == null || !wr.metalActive()) {
      metalrender$loggedWaitingForMetalDraw = false;
      return false;
    }

    int drawnChunks = wr.getLastDrawnChunkCount();
    if (drawnChunks > 0) {
      metalrender$loggedWaitingForMetalDraw = false;
      return true;
    }

    if (!metalrender$loggedWaitingForMetalDraw) {
      MetalLogger.info(
          "[WorldRendererTerrainMixin] Metal terrain draw stalled; restoring vanilla terrain (meshes=%d, drawn=%d)",
          wr.getLoadingModeMeshCount(), drawnChunks);
      metalrender$loggedWaitingForMetalDraw = true;
    }
    return false;
  }

  @Redirect(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0), require = 0)
  private void metalrender$skipOpaqueTerrainGroup(ChunkSectionsToRender sections,
      ChunkSectionLayerGroup group, GpuSampler sampler) {
    if (metalrender$shouldSkipVanillaTerrain()) {
      metalrender$skippedTerrainGroups++;
      if (metalrender$skippedTerrainGroups <= 3 || metalrender$skippedTerrainGroups % 1000 == 0) {
        MetalLogger.info("[WorldRendererTerrainMixin] Skipped vanilla terrain group #%d (%s)",
            metalrender$skippedTerrainGroups, String.valueOf(group));
>>>>>>> 62d2482 (optimisation for high-rend scenes with tons of chunks. also fixed chunk loading speeds)
      }
    }
  }
}
