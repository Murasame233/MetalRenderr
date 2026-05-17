package com.pebbles_boon.metalrender.sodium.mixins;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
<<<<<<< HEAD
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {}
=======
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.GameRenderer", remap = false)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V", remap = false), require = 0)
    private void metalrender$blitBeforeHandRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive())
            return;
        if (!MetalWorldRenderer.shouldBlitAt("before_hand"))
            return;
        worldRenderer.forceBlitNow();
    }
}
>>>>>>> e028af4 (checkpoint, WIP)
