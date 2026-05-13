package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderMixin {
    @Unique
    private static final double metalrender$hardCullDistSq = 48.0 * 48.0;

    @Unique
    private static final double metalrender$rateLimitDistSq = 24.0 * 24.0;

    @Unique
    private static final int metalrender$rateFar = 3;

    @Unique
    private static final int metalrender$softBudget = 48;

    @Unique
    private static final int metalrender$hardBudget = 96;

    @Unique
    private Vec3 metalrender$cameraPos = Vec3.ZERO;

    @Unique
    private long metalrender$frameCounter = 0;

    @Unique
    private int metalrender$renderedThisFrame = 0;

    @Inject(method = "prepare", at = @At("TAIL"), require = 0)
    private void metalrender$prepareCamera(Vec3 cameraPos, CallbackInfo ci) {
        if (cameraPos != null) {
            metalrender$cameraPos = cameraPos;
        }
        metalrender$frameCounter++;
        metalrender$renderedThisFrame = 0;
    }

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void metalrender$cullBlockEntity(
            E blockEntity, float tickDelta,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfoReturnable<S> cir) {
        if (blockEntity == null || !MetalRenderClient.isEnabled()) {
            return;
        }
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer == null || !worldRenderer.metalActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }

        double distSq = blockEntity.getBlockPos().distToCenterSqr(
                metalrender$cameraPos.x,
                metalrender$cameraPos.y,
                metalrender$cameraPos.z);
        if (distSq > metalrender$hardCullDistSq) {
            cir.setReturnValue(null);
            return;
        }

        int hash = Long.hashCode(blockEntity.getBlockPos().asLong());
        if (distSq > metalrender$rateLimitDistSq &&
                (metalrender$frameCounter + hash) % metalrender$rateFar != 0) {
            cir.setReturnValue(null);
            return;
        }

        if (metalrender$renderedThisFrame >= metalrender$hardBudget) {
            if ((metalrender$frameCounter + hash) % 4 != 0) {
                cir.setReturnValue(null);
                return;
            }
        } else if (metalrender$renderedThisFrame >= metalrender$softBudget) {
            if ((metalrender$frameCounter + hash) % 2 != 0) {
                cir.setReturnValue(null);
                return;
            }
        }

        metalrender$renderedThisFrame++;
    }
}