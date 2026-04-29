package com.pebbles_boon.metalrender.entity;

import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

public class MetalRenderCommandQueue implements OrderedRenderCommandQueue {
    private VertexConsumer vertexConsumer;
    private int defaultLight;

    public MetalRenderCommandQueue(VertexConsumer vertexConsumer, int light) {
        this.vertexConsumer = vertexConsumer;
        this.defaultLight = light;
    }


    public void reset(VertexConsumer vertexConsumer, int light) {
        this.vertexConsumer = vertexConsumer;
        this.defaultLight = light;
    }

    @Override
    public RenderCommandQueue getBatchingQueue(int index) {
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices,
            RenderLayer layer, int light, int overlay, int color,
            Sprite sprite, int flags, ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        if (model != null) {
            if (model instanceof net.minecraft.client.render.entity.model.EntityModel entityModel) {
                entityModel.setAngles(state);
            }
            model.render(matrices, vertexConsumer, light, overlay, color);
        }
    }

    @Override
    public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer layer,
            int light, int overlay, Sprite sprite, boolean visible, boolean noCull,
            int color, ModelCommandRenderer.CrumblingOverlayCommand crumbling, int extra) {

        if (part != null && (visible || noCull)) {
            part.render(matrices, vertexConsumer, light, overlay, color);
        }
    }

    @Override
    public void submitShadowPieces(MatrixStack matrices, float radius,
            List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitLabel(MatrixStack matrices, Vec3d pos, int bgColor, Text text,
            boolean seeThrough, int textColor, double distance, CameraRenderState camera) {
    }

    @Override
    public void submitText(MatrixStack matrices, float x, float y, OrderedText text,
            boolean shadow, TextRenderer.TextLayerType layerType, int bgColor, int textColor,
            int light, int sortOrder) {
    }

    @Override
    public void submitFire(MatrixStack matrices, EntityRenderState state, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leashData) {
    }

    @Override
    public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int color) {
    }

    @Override
    public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state) {
    }

    @Override
    public void submitBlockStateModel(MatrixStack matrices, RenderLayer layer,
            BlockStateModel model, float x, float y, float z, int light, int overlay, int color) {
    }

    @Override
    public void submitItem(MatrixStack matrices, ItemDisplayContext context,
            int light, int overlay, int color, int[] tintColors, List<BakedQuad> quads,
            RenderLayer layer, ItemRenderState.Glint glint) {
    }

    @Override
    public void submitCustom(MatrixStack matrices, RenderLayer layer,
            OrderedRenderCommandQueue.Custom custom) {
    }

    @Override
    public void submitCustom(OrderedRenderCommandQueue.LayeredCustom custom) {
    }
}
