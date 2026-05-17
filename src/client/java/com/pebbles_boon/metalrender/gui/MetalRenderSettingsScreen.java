package com.pebbles_boon.metalrender.gui;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.gui.components.MetalOptionSlider;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;


public class MetalRenderSettingsScreen extends Screen {


  private static final int C_PANEL = 0xFF1E1E20;
  private static final int C_HEADER = 0xFF161618;
  private static final int C_TAB_BAR = 0xFF252527;
  private static final int C_TAB_ACTIVE = 0xFF007AFF;
  private static final int C_TAB_HOVER = 0xFF38383A;
  private static final int C_CARD = 0xFF2C2C2E;
  private static final int C_CARD_HOVER = 0xFF38383A;
  private static final int C_DIVIDER = 0xFF3A3A3C;
  private static final int C_TEXT_PRI = 0xFFFFFFFF;
  private static final int C_TEXT_SEC = 0xFF8E8E93;
  private static final int C_TEXT_ACCENT = 0xFF007AFF;
  private static final int C_VAL_ON = 0xFF30D158;
  private static final int C_VAL_OFF = 0xFFFF453A;
  private static final int C_PILL_ON = 0xFF34C759;
  private static final int C_PILL_OFF = 0xFF48484A;
  private static final int C_SCROLLTHUMB = 0xFF636366;


  private static final int PANEL_W = 700;
  private static final int PANEL_H = 460;
  private static final int HDR_H = 38;
  private static final int TAB_H = 32;
  private static final int FOOT_H = 36;
  private static final int CARD_H = 38;
  private static final int CARD_GAP = 1;
  private static final int SEC_H = 28;
  private static final int HPAD = 10;
  private static final int PILL_W = 40;
  private static final int PILL_H = 20;
  private static final int SLIDER_W = 120;
  private static final int SLIDER_H = 12;

  private static final String[] TABS = {
      "Video", "MetalRender", "Quality", "Performance", "Advanced", "LOD"
  };


  private final Screen parent;
  private MetalRenderConfig config;
  private int selectedTab = 0;
  private int scrollOffset = 0;
  private int maxScroll = 0;
  private boolean dragging = false;
  private int dragOriginY, dragOriginOff;


  private int px, py, pw, ph;
  private int cx, cy, cw, ch;


  private int pendingRenderDist;
  private int pendingSimDist;
  private int pendingMaxFps;
  private int pendingGuiScale;
  private double pendingBrightness;
  private int pendingFov;
  private double pendingDistortion;
  private double pendingFovEffects;
  private int pendingTargetFps;
  private int pendingMaxMemMb;
  private int pendingLod1, pendingLod2, pendingLod3, pendingLod4;
  private boolean pendingLodEnabled;
  private boolean pendingDeepDebugNextRun;





  private int initialRenderDist;
  private int initialLod1, initialLod2, initialLod3, initialLod4;
  private boolean initialLodEnabled;
  private int initialBiomeDetail;
  private int initialLeafCulling;
  private boolean initialSmoothLighting;


  private final List<Row> rows = new ArrayList<>();


  private enum RT {
    SECTION, TOGGLE, CYCLE, INFO, VANILLA, SLIDER
  }

  private static class Row {
    final RT type;
    final String label;
    String value;
    Runnable action;
    SimpleOption<?> vanillaOpt;
    MetalOptionSlider slider;
    int renderY = 0;

    Row(RT t, String l) {
      type = t;
      label = l;
    }

    int h() {
      return type == RT.SECTION ? SEC_H : CARD_H;
    }

    int gap() {
      return type == RT.SECTION ? 0 : CARD_GAP;
    }
  }





  public MetalRenderSettingsScreen(Screen parent) {
    super(Text.literal("MetalRender Settings"));
    this.parent = parent;
  }





  @Override
  protected void init() {
    config = MetalRenderClient.getConfig();
    if (config == null)
      config = MetalRenderConfig.load();
    GameOptions o = MinecraftClient.getInstance().options;
    pendingRenderDist = o.getViewDistance().getValue();
    pendingSimDist = o.getSimulationDistance().getValue();
    pendingMaxFps = o.getMaxFps().getValue();
    pendingGuiScale = o.getGuiScale().getValue();
    pendingBrightness = o.getGamma().getValue();
    pendingFov = o.getFov().getValue();
    pendingDistortion = o.getDistortionEffectScale().getValue();
    pendingFovEffects = o.getFovEffectScale().getValue();
    pendingTargetFps = config.targetFrameRate;
    pendingMaxMemMb = config.maxMemoryMB;
    pendingLod1 = MetalRenderConfig.lod1Distance();
    pendingLod2 = MetalRenderConfig.lod2Distance();
    pendingLod3 = MetalRenderConfig.lod3Distance();
    pendingLod4 = MetalRenderConfig.lod4Distance();
    pendingLodEnabled = MetalRenderConfig.lodEnabled();
    pendingDeepDebugNextRun = MetalRenderConfig.isOneRunDeepDebugRequested();


    initialRenderDist = pendingRenderDist;
    initialLod1 = pendingLod1;
    initialLod2 = pendingLod2;
    initialLod3 = pendingLod3;
    initialLod4 = pendingLod4;
    initialLodEnabled = pendingLodEnabled;
    initialBiomeDetail = config.biomeTransitionDetail;
    initialLeafCulling = config.leafCullingMode;
    initialSmoothLighting = config.enableSimpleLighting;
    layout();
    rebuild();
  }

  private void layout() {
    pw = Math.min(PANEL_W, width - 16);
    ph = Math.min(PANEL_H, height - 16);
    px = (width - pw) / 2;
    py = (height - ph) / 2;
    cx = px + HPAD;
    cy = py + HDR_H + TAB_H;
    cw = pw - HPAD * 2;
    ch = ph - HDR_H - TAB_H - FOOT_H;
  }





  @Override
  public void render(DrawContext ctx, int mx, int my, float delta) {

    ctx.fill(0, 0, width, height, 0xAA000000);

    ctx.fill(px + 4, py + 4, px + pw + 4, py + ph + 4, 0x44000000);

    fr(ctx, px, py, pw, ph, C_PANEL);

    fr(ctx, px, py, pw, HDR_H, C_HEADER);
    ctx.drawTextWithShadow(textRenderer,
        Text.literal("MetalRender Settings"),
        px + 12, py + (HDR_H - 9) / 2, C_TEXT_PRI);
    int vx = px + 12 + textRenderer.getWidth("MetalRender Settings") + 6;
    ctx.drawText(textRenderer, Text.literal("v0.1.7"),
        vx, py + (HDR_H - 9) / 2, C_TEXT_SEC, false);

    renderTabs(ctx, mx, my);

    ctx.enableScissor(cx, cy, cx + cw, cy + ch);
    posSliders();
    renderRows(ctx, mx, my);
    ctx.disableScissor();

    renderScrollbar(ctx);

    int fy = py + ph - FOOT_H;
    fr(ctx, px, fy, pw, 1, C_DIVIDER);
    String gpu = MetalHardwareChecker.getDeviceName();
    if (gpu == null || gpu.isEmpty())
      gpu = "Unknown GPU";
    if (textRenderer.getWidth(gpu) > pw / 2 - 20)
      gpu = gpu.substring(0, Math.min(gpu.length(), 30)) + "\u2026";
    ctx.drawText(textRenderer, Text.literal(gpu),
        px + 12, fy + (FOOT_H - 9) / 2, C_TEXT_SEC, false);
    super.render(ctx, mx, my, delta);
  }

  private void renderTabs(DrawContext ctx, int mx, int my) {
    int ty = py + HDR_H;
    fr(ctx, px, ty, pw, TAB_H, C_TAB_BAR);
    int tw = pw / TABS.length;
    for (int i = 0; i < TABS.length; i++) {
      int tx = px + i * tw;
      boolean sel = i == selectedTab;
      boolean hov = mx >= tx && mx < tx + tw && my >= ty && my < ty + TAB_H && !sel;
      int bg = sel ? C_TAB_ACTIVE : (hov ? C_TAB_HOVER : C_TAB_BAR);
      fr(ctx, tx + 2, ty + 2, tw - 4, TAB_H - 4, bg);
      int tc = (sel || hov) ? C_TEXT_PRI : C_TEXT_SEC;
      ctx.drawCenteredTextWithShadow(textRenderer,
          Text.literal(TABS[i]), tx + tw / 2, ty + (TAB_H - 9) / 2, tc);
    }
    fr(ctx, px, ty + TAB_H - 1, pw, 1, C_DIVIDER);
  }

  private void renderRows(DrawContext ctx, int mx, int my) {
    int totalH = totalH();
    maxScroll = Math.max(0, totalH - ch);
    scrollOffset = cl(scrollOffset, 0, maxScroll);
    int y = cy - scrollOffset;
    for (Row r : rows) {
      r.renderY = y;
      if (y + r.h() >= cy && y < cy + ch)
        drawRow(ctx, r, y, mx, my);
      y += r.h() + r.gap();
    }
  }

  private void drawRow(DrawContext ctx, Row r, int y, int mx, int my) {
    if (r.type == RT.SECTION) {
      ctx.drawText(textRenderer, Text.literal(r.label.toUpperCase()),
          cx + 4, y + (SEC_H - 9) / 2 + 4, C_TEXT_SEC, false);
      return;
    }
    boolean hov = mx >= cx && mx < cx + cw
        && my >= y && my < y + r.h()
        && my >= cy && my < cy + ch;
    fr(ctx, cx, y, cw, CARD_H, hov ? C_CARD_HOVER : C_CARD);

    if (r.type == RT.TOGGLE && "Enabled".equals(r.value))
      fr(ctx, cx, y, 3, CARD_H, C_TAB_ACTIVE);
    ctx.drawText(textRenderer, Text.literal(r.label),
        cx + 10, y + (CARD_H - 9) / 2, C_TEXT_PRI, false);
    int rx = cx + cw - 8;
    switch (r.type) {
      case TOGGLE -> {
        boolean on = "Enabled".equals(r.value);
        drawPill(ctx, rx - PILL_W, y + (CARD_H - PILL_H) / 2, on);
      }
      case CYCLE -> {
        int vw = textRenderer.getWidth(r.value) + 12;
        fr(ctx, rx - vw, y + 8, vw, CARD_H - 16, C_TAB_ACTIVE);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal(r.value), rx - vw / 2, y + (CARD_H - 9) / 2, C_TEXT_PRI);
      }
      case INFO -> {
        String v = r.value == null ? "" : r.value;
        int col = C_TEXT_SEC;
        if ("Enabled".equals(v) || "Yes".equals(v) || "Supported".equals(v) || "Installed".equals(v))
          col = C_VAL_ON;
        else if ("Disabled".equals(v) || "No".equals(v) || "Not Available".equals(v) || "Not Installed".equals(v))
          col = C_VAL_OFF;
        else if (!v.isEmpty())
          col = C_TEXT_ACCENT;
        ctx.drawText(textRenderer, Text.literal(v),
            rx - textRenderer.getWidth(v), y + (CARD_H - 9) / 2, col, false);
      }
      case VANILLA -> {
        String v = r.value == null ? "" : r.value;
        int col = "ON".equals(v) ? C_VAL_ON : ("OFF".equals(v) ? C_VAL_OFF : C_TEXT_ACCENT);
        ctx.drawText(textRenderer, Text.literal(v),
            rx - textRenderer.getWidth(v), y + (CARD_H - 9) / 2, col, false);
      }
      case SLIDER -> {
        if (r.slider != null) {
          String sv = r.slider.getMessage().getString();
          ctx.drawText(textRenderer, Text.literal(sv),
              rx - SLIDER_W - 6 - textRenderer.getWidth(sv),
              y + (CARD_H - 9) / 2, C_TEXT_ACCENT, false);
        }
      }
      default -> {
      }
    }
    fr(ctx, cx + 8, y + CARD_H - 1, cw - 16, 1, C_DIVIDER);
  }


  private void drawPill(DrawContext ctx, int x, int y, boolean on) {
    int bg = on ? C_PILL_ON : C_PILL_OFF;
    ctx.fill(x + 2, y, x + PILL_W - 2, y + PILL_H, bg);
    ctx.fill(x, y + 2, x + PILL_W, y + PILL_H - 2, bg);
    int kx = on ? x + PILL_W - PILL_H + 1 : x + 1;
    ctx.fill(kx + 1, y + 2, kx + PILL_H - 2, y + PILL_H - 2, 0xFFFFFFFF);
  }

  private void renderScrollbar(DrawContext ctx) {
    if (maxScroll <= 0)
      return;
    int sbX = px + pw - 4;
    int tot = totalH();
    int thumbH = Math.max(16, (int) ((float) ch / tot * ch));
    int thumbY = cy + (int) ((float) scrollOffset / maxScroll * (ch - thumbH));
    ctx.fill(sbX, cy, sbX + 3, cy + ch, 0xFF3A3A3C);
    ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, C_SCROLLTHUMB);
  }

  private void posSliders() {
    for (Row r : rows) {
      if (r.type != RT.SLIDER || r.slider == null)
        continue;
      int ry = r.renderY;
      boolean vis = ry >= cy && ry + CARD_H <= cy + ch;
      r.slider.setPosition(cx + cw - 8 - SLIDER_W, ry + (CARD_H - SLIDER_H) / 2);
      r.slider.setWidth(SLIDER_W);
      r.slider.visible = vis;
      r.slider.active = vis;
    }
  }





  @Override
  public boolean mouseClicked(Click click, boolean bl) {
    double mx = click.x(), my = click.y();

    int ty = py + HDR_H;
    int tw = pw / TABS.length;
    if (my >= ty && my < ty + TAB_H) {
      for (int i = 0; i < TABS.length; i++) {
        int tx = px + i * tw;
        if (mx >= tx && mx < tx + tw) {
          if (selectedTab != i) {
            selectedTab = i;
            scrollOffset = 0;
            rebuild();
          }
          return true;
        }
      }
    }

    int sbX = px + pw - 6;
    if (mx >= sbX && mx <= sbX + 6 && my >= cy && my <= cy + ch) {
      dragging = true;
      dragOriginY = (int) my;
      dragOriginOff = scrollOffset;
      return true;
    }

    if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
      int y = cy - scrollOffset;
      for (Row r : rows) {
        double lo = Math.max(y, cy), hi = Math.min(y + r.h(), cy + ch);
        if (my >= lo && my < hi) {
          if ((r.type == RT.TOGGLE || r.type == RT.CYCLE) && r.action != null) {
            r.action.run();
            rebuild();
            return true;
          }
          if (r.type == RT.VANILLA && r.vanillaOpt != null) {
            cycleVanilla(r.vanillaOpt);
            rebuild();
            return true;
          }
        }
        y += r.h() + r.gap();
      }
    }
    return super.mouseClicked(click, bl);
  }

  @Override
  public boolean mouseDragged(net.minecraft.client.gui.Click click, double dx, double dy) {
    if (dragging && maxScroll > 0) {
      double my = click.y();
      int tot = totalH();
      int thumbH = Math.max(16, (int) ((float) ch / tot * ch));
      float ratio = (float) (my - dragOriginY) / (ch - thumbH);
      scrollOffset = cl(dragOriginOff + (int) (ratio * maxScroll), 0, maxScroll);
      return true;
    }
    return super.mouseDragged(click, dx, dy);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.gui.Click click) {
    dragging = false;
    return super.mouseReleased(click);
  }

  @Override
  public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
    if (mx >= px && mx < px + pw && my >= cy && my < cy + ch) {
      scrollOffset = cl(scrollOffset - (int) (vAmt * CARD_H * 2), 0, maxScroll);
      return true;
    }
    return super.mouseScrolled(mx, my, hAmt, vAmt);
  }

  @Override
  public void close() {
    applyPending();
    config.save();




    boolean needsRebuild = (pendingRenderDist != initialRenderDist)
        || (pendingLod1 != initialLod1)
        || (pendingLod2 != initialLod2)
        || (pendingLod3 != initialLod3)
        || (pendingLod4 != initialLod4)
        || (pendingLodEnabled != initialLodEnabled)
        || (config.biomeTransitionDetail != initialBiomeDetail)
        || (config.leafCullingMode != initialLeafCulling)
        || (config.enableSimpleLighting != initialSmoothLighting);


    boolean biomeChanged = config.biomeTransitionDetail != initialBiomeDetail;
    com.pebbles_boon.metalrender.util.MetalLogger.info(
        "Settings closed: needsRebuild=%b (renderDist=%b lod=%b biome=%b leaf=%b lighting=%b)",
        needsRebuild,
        pendingRenderDist != initialRenderDist,
        pendingLod1 != initialLod1 || pendingLod2 != initialLod2 || pendingLod3 != initialLod3
            || pendingLod4 != initialLod4 || pendingLodEnabled != initialLodEnabled,
        biomeChanged,
        config.leafCullingMode != initialLeafCulling,
        config.enableSimpleLighting != initialSmoothLighting);
    if (needsRebuild || biomeChanged) {

      if (NativeBridge.isLibLoaded()) {
        NativeBridge.nFlushFrames();
      }


      MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
      if (wr != null) {
        wr.onConfigScreenClosed();
      }
    }
    if (client != null)
      client.setScreen(parent);
  }

  private void applyPending() {
<<<<<<< HEAD
    GameOptions o = MinecraftClient.getInstance().options;
    o.getViewDistance().setValue(pendingRenderDist);
    o.getSimulationDistance().setValue(pendingSimDist);
    o.getMaxFps().setValue(pendingMaxFps);
    o.getGuiScale().setValue(pendingGuiScale);
    o.getGamma().setValue(pendingBrightness);
    o.getFov().setValue(pendingFov);
    o.getDistortionEffectScale().setValue(pendingDistortion);
    o.getFovEffectScale().setValue(pendingFovEffects);
=======
    Options o = Minecraft.getInstance().options;
    o.renderDistance().set(pendingRenderDist);
    if (config.prioritizeFpsOverTps) {
      pendingSimDist = Math.min(pendingSimDist, 5);
    }
    o.simulationDistance().set(pendingSimDist);
    o.framerateLimit().set(toVanillaFpsLimit(pendingMaxFps));
    o.guiScale().set(pendingGuiScale);
    o.gamma().set(pendingBrightness);
    o.fov().set(pendingFov);
    o.screenEffectScale().set(pendingDistortion);
    o.fovEffectScale().set(pendingFovEffects);
    o.save();
>>>>>>> e028af4 (checkpoint, WIP)
    config.targetFrameRate = pendingTargetFps;
    config.maxMemoryMB = pendingMaxMemMb;
    MetalRenderConfig.setOneRunDeepDebugRequested(pendingDeepDebugNextRun);
    MetalRenderConfig.setLodEnabled(pendingLodEnabled);
    MetalRenderConfig.setLod1Distance(pendingLod1);
    MetalRenderConfig.setLod2Distance(pendingLod2);
    MetalRenderConfig.setLod3Distance(pendingLod3);
    MetalRenderConfig.setLod4Distance(pendingLod4);

    if (NativeBridge.isLibLoaded()) {
      NativeBridge.nSetFeatureFlags(
          config.enableIndirectCommandBuffers,
          config.enableMeshShaders,
          config.enableArgumentBuffers,
          config.enableProgrammableBlending,
          config.enableMemorylessTargets);
    }

    MetalWorldRenderer wr = MetalWorldRenderer.getInstance();
    if (wr != null) {
      wr.applyFeatureConfig(config);
    }
  }





  private void rebuild() {
    clearChildren();
    rows.clear();
    int bw = 64, bh = 20;
    addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
        .dimensions(px + pw - bw - 10, py + (HDR_H - bh) / 2, bw, bh).build());
    switch (selectedTab) {
      case 0 -> buildVideo();
      case 1 -> buildMetal();
      case 2 -> buildQuality();
      case 3 -> buildPerformance();
      case 4 -> buildAdvanced();
      case 5 -> buildLod();
    }
    for (Row r : rows)
      if (r.type == RT.SLIDER && r.slider != null)
        addDrawableChild(r.slider);
  }



  private void buildVideo() {
    GameOptions o = MinecraftClient.getInstance().options;
    sec("Display");
    vanilla("Fullscreen", o.getFullscreen());
    vanilla("VSync", o.getEnableVsync());
    sld("Max FPS", 10, 260, 10, pendingMaxFps, v -> pendingMaxFps = (int) (float) v);
    sld("GUI Scale", 0, 6, 1, pendingGuiScale, v -> pendingGuiScale = (int) (float) v);
    sec("World");
    sld("Render Distance", 2, 32, 1, pendingRenderDist, v -> pendingRenderDist = (int) (float) v);
    sld("Simulation Distance", 5, 32, 1, pendingSimDist, v -> pendingSimDist = (int) (float) v);
    sec("Environment");
    sld("Brightness", 0f, 1f, 0.05f, (float) pendingBrightness, v -> pendingBrightness = v);
    sec("Camera");
    sld("Field of View", 30f, 110f, 1f, pendingFov, v -> pendingFov = (int) (float) v);
    sld("Distortion Effects", 0f, 1f, 0.05f, (float) pendingDistortion, v -> pendingDistortion = v);
    sld("FOV Effects", 0f, 1f, 0.05f, (float) pendingFovEffects, v -> pendingFovEffects = v);
    vanilla("View Bobbing", o.getBobView());
    vanilla("Entity Shadows", o.getEntityShadows());
    vanilla("Graphics", o.getPreset());
  }

  private void buildMetal() {
    sec("Renderer");
    tog("Metal Rendering", config.enableMetalRendering, v -> config.enableMetalRendering = v);
    tog("Smooth Lighting", config.enableSimpleLighting, v -> config.enableSimpleLighting = v);
    if (MetalRenderConfig.isDeepDebugActive()) {
      nfo("Deep Debug Status", "Active this run");
    } else {
      tog("Deep Debug Next Run", pendingDeepDebugNextRun, v -> pendingDeepDebugNextRun = v);
      nfo("Deep Debug Status", pendingDeepDebugNextRun ? "Armed for next launch" : "Off");
    }
    sec("Hardware");
    nfo("GPU", MetalHardwareChecker.getDeviceName());
    nfo("Metal", MetalRenderClient.isMetalAvailable() ? "Supported" : "Not Available");
    nfo("Apple Silicon", MetalHardwareChecker.isAppleSilicon() ? "Yes" : "No");
    nfo("Sodium", MetalRenderClient.isSodiumLoaded() ? "Installed" : "Not Installed");
    nfo("Mesh Shaders", MetalHardwareChecker.supportsMeshShaders() ? "Supported" : "Not Available");
  }

  private void buildQuality() {
    sec("Rendering Style");
    cyc("Leaves Mode", config.leafCullingMode == 0 ? "Fast" : "Fancy",
        () -> config.leafCullingMode = config.leafCullingMode == 0 ? 1 : 0);
    tog("Zone 2 LOD", config.enableZone2Lod, v -> config.enableZone2Lod = v);
    sec("Biome Blending");



    sld("Biome Blend", 0, 10, 1, config.biomeTransitionDetail,
        v -> config.biomeTransitionDetail = (int) (float) v);
  }

  private void buildPerformance() {
    sec("Frame Pacing");
    sld("Target FPS", 30, 240, 30, pendingTargetFps, v -> pendingTargetFps = (int) (float) v);
    tog("Triple Buffering", config.enableTripleBuffering, v -> config.enableTripleBuffering = v);
<<<<<<< HEAD
=======
    tog("Burst Thread Mode", config.enableBurstThreadMode, v -> config.enableBurstThreadMode = v);
    tog("Sacrifice TPS for FPS", config.prioritizeFpsOverTps, v -> config.prioritizeFpsOverTps = v);
    nfo("FPS Priority Mode", config.prioritizeFpsOverTps ? "Simulation Distance <= 5" : "Off");
>>>>>>> e028af4 (checkpoint, WIP)
    sec("Memory");
    sld("Max GPU Memory (MB)", 512, 4096, 512, pendingMaxMemMb, v -> pendingMaxMemMb = (int) (float) v);
    tog("Memory Fallback", config.enableMemoryPressureFallback, v -> config.enableMemoryPressureFallback = v);
    sec("Runtime");
    Runtime rt = Runtime.getRuntime();
    long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    long max = rt.maxMemory() / (1024 * 1024);
    nfo("Heap Usage", used + " / " + max + " MB");
  }

  private void buildAdvanced() {
    sec("Metal Features");
    tog("Argument Buffers", config.enableArgumentBuffers, v -> config.enableArgumentBuffers = v);
    tog("Indirect CMD Buffers", config.enableIndirectCommandBuffers, v -> config.enableIndirectCommandBuffers = v);
    tog("Mesh Shaders", config.enableMeshShaders, v -> config.enableMeshShaders = v);
    tog("Memoryless Targets", config.enableMemorylessTargets, v -> config.enableMemorylessTargets = v);
  }

  private void buildLod() {
    sec("Level of Detail");
    tog("LOD System", pendingLodEnabled, v -> {
      pendingLodEnabled = v;
      MetalRenderConfig.setLodEnabled(pendingLodEnabled);
    });
    nfo("LOD 0", "Full detail near player");
    sec("Distances (chunks)");




    sld("LOD 1", 1, 32, 1, pendingLod1, v -> {
      pendingLod1 = (int) (float) v;
      MetalRenderConfig.setLod1Distance(pendingLod1);
    });
    sld("LOD 2", 2, 32, 1, pendingLod2, v -> {
      pendingLod2 = (int) (float) v;
      MetalRenderConfig.setLod2Distance(pendingLod2);
    });
    sld("LOD 3", 3, 32, 1, pendingLod3, v -> {
      pendingLod3 = (int) (float) v;
      MetalRenderConfig.setLod3Distance(pendingLod3);
    });
    sld("LOD 4", 4, 32, 1, pendingLod4, v -> {
      pendingLod4 = (int) (float) v;
      MetalRenderConfig.setLod4Distance(pendingLod4);
    });
    sec("Descriptions");
    nfo("LOD 1", "Skip non-full blocks");
    nfo("LOD 2", "Skip decorations and fences");
    nfo("LOD 3", "Skip slabs, stairs, trapdoors");
    nfo("LOD 4", "Full cubes only");
  }



  private void sec(String label) {
    rows.add(new Row(RT.SECTION, label));
  }

  private void tog(String label, boolean on, java.util.function.Consumer<Boolean> setter) {
    boolean[] s = { on };
    Row r = new Row(RT.TOGGLE, label);
    r.value = s[0] ? "Enabled" : "Disabled";
    r.action = () -> {
      s[0] = !s[0];
      r.value = s[0] ? "Enabled" : "Disabled";
      setter.accept(s[0]);
    };
    rows.add(r);
  }

  private void cyc(String label, String initial, Runnable action) {
    Row r = new Row(RT.CYCLE, label);
    r.value = initial;
    r.action = action;
    rows.add(r);
  }

  private void nfo(String label, String value) {
    Row r = new Row(RT.INFO, label);
    r.value = value;
    rows.add(r);
  }

  private void vanilla(String label, SimpleOption<?> opt) {
    Row r = new Row(RT.VANILLA, label);
    r.value = fmtV(opt);
    r.vanillaOpt = opt;
    rows.add(r);
  }

  private void sld(String label, float min, float max, float step,
      float cur, java.util.function.Consumer<Float> cb) {
    Row r = new Row(RT.SLIDER, label);
    r.slider = new MetalOptionSlider(0, 0, SLIDER_W, SLIDER_H,
        Text.literal(""), min, max, step, cur, cb);
    rows.add(r);
  }



  private int totalH() {
    int h = 0;
    for (Row r : rows)
      h += r.h() + r.gap();
    return h;
  }

  private String fmtV(SimpleOption<?> opt) {
    Object v = opt.getValue();
    if (v instanceof Boolean b)
      return b ? "ON" : "OFF";
    if (v instanceof Integer i)
      return String.valueOf(i);
    if (v instanceof Double d) {
      if (d == (int) (double) d)
        return String.valueOf((int) (double) d);
      return String.format("%.1f", d);
    }
    return v.toString();
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void cycleVanilla(SimpleOption opt) {
    Object v = opt.getValue();
    if (v instanceof Boolean b)
      opt.setValue(!b);
  }


  private static void fr(DrawContext ctx, int x, int y, int w, int h, int col) {
    ctx.fill(x, y, x + w, y + h, col);
  }


  private static int cl(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
