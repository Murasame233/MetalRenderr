package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

@SuppressWarnings("deprecation")
public final class MetalHudOverlay implements HudRenderCallback {
  private static final int COLOR = 0xFFFF00FF;
  private static final String LABEL = "MetalRender ACTIVE";
  private static final int LOADING_COLOR = 0xFFFFAA00;
  private static final int LOADING_BG_COLOR = 0x80000000;

  private String cachedLoadingText;
  private int cachedLoadingTextWidth;
  private int lastPending = -1, lastMeshes = -1;

  @Override
  public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
    if (!MetalRenderClient.isEnabled() ||
        MetalRenderClient.getWorldRenderer() == null ||
        !MetalRenderClient.getWorldRenderer().isReady()) {
      return;
    }
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc == null)
      return;
    TextRenderer textRenderer = mc.textRenderer;
    if (textRenderer == null)
      return;
    context.drawTextWithShadow(textRenderer, LABEL, 10, 10, COLOR);


    try {
      MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
      if (wr != null && wr.isLoadingMode()) {
        int pending = wr.getLoadingModePendingCount();
        int meshes = wr.getLoadingModeMeshCount();
        if (pending != lastPending || meshes != lastMeshes) {
          cachedLoadingText = "Loading: " + pending + " pending / " + meshes + " built";
          cachedLoadingTextWidth = textRenderer.getWidth(cachedLoadingText);
          lastPending = pending;
          lastMeshes = meshes;
        }
        int textWidth = cachedLoadingTextWidth;
        int screenWidth = mc.getWindow().getScaledWidth();
        int lx = screenWidth - textWidth - 6;
        int ly = 4;
        context.fill(lx - 3, ly - 1, lx + textWidth + 3, ly + 10, LOADING_BG_COLOR);
        context.drawTextWithShadow(textRenderer, cachedLoadingText, lx, ly, LOADING_COLOR);
      }
    } catch (Exception e) {

    }
  }

  public static void register() {
    HudRenderCallback.EVENT.register(new MetalHudOverlay());
  }
}
