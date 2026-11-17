package com.wgaccel.mixin.access;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An ImprovedNoise's permutation, to pack it for the GPU (step G2.2). */
@Mixin(ImprovedNoise.class)
public interface ImprovedNoiseAccessor {
   @Accessor("p")
   byte[] wgaccel$p();
}
