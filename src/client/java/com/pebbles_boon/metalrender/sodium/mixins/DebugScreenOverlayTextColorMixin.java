package com.pebbles_boon.metalrender.sodium.mixins;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayTextColorMixin {
    private static final int SKY = 0xFF87CEEB;
    private static final String PFX = "MetalRender rendering, v";

    @Redirect(method = "extractLines", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
    private void metalrender$tintDbg(GuiGraphicsExtractor g, Font font, String text,
            int x, int y, int color, boolean shadow) {
        if (text.startsWith(PFX)) {
            g.text(font, text, x, y, SKY, shadow);
            return;
        }
        g.text(font, text, x, y, color, shadow);
    }
}