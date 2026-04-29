package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {


    @Accessor("currentBreakingPos")
    @Nullable
    BlockPos metalrender$getCurrentBreakingPos();


    @Accessor("currentBreakingProgress")
    float metalrender$getCurrentBreakingProgress();


    @Accessor("breakingBlock")
    boolean metalrender$isBreakingBlock();
}
