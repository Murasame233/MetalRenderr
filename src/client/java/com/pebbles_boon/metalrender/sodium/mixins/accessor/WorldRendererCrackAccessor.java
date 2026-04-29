package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;


@Mixin(WorldRenderer.class)
public interface WorldRendererCrackAccessor {


    @Invoker("fillBlockBreakingProgressRenderState")
    void metalrender$fillBlockBreaking(Camera camera, WorldRenderState state);


    @Invoker("renderBlockDamage")
    void metalrender$renderBlockDamage(MatrixStack matrices,
            VertexConsumerProvider.Immediate immediate,
            WorldRenderState state);


    @Accessor("bufferBuilders")
    BufferBuilderStorage metalrender$getBufferBuilders();
}
