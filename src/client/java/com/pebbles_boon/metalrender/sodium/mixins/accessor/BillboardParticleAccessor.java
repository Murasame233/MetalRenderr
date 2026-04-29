package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BillboardParticle.class)
public interface BillboardParticleAccessor {
  @Accessor("scale")
  float metalrender$getScale();

  @Accessor("red")
  float metalrender$getRed();

  @Accessor("green")
  float metalrender$getGreen();

  @Accessor("blue")
  float metalrender$getBlue();

  @Accessor("alpha")
  float metalrender$getAlpha();

  @Accessor("zRotation")
  float metalrender$getZRotation();

  @Accessor("lastZRotation")
  float metalrender$getLastZRotation();

  @Accessor("sprite")
  Sprite metalrender$getSprite();







  @Invoker("getMinU")
  float metalrender$invokeGetMinU();

  @Invoker("getMaxU")
  float metalrender$invokeGetMaxU();

  @Invoker("getMinV")
  float metalrender$invokeGetMinV();

  @Invoker("getMaxV")
  float metalrender$invokeGetMaxV();
}
