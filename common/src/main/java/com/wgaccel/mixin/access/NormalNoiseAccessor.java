package com.wgaccel.mixin.access;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A NormalNoise's parts, to pack it for the GPU (step G2.2). */
@Mixin(NormalNoise.class)
public interface NormalNoiseAccessor {
   @Accessor("first")
   PerlinNoise wgaccel$first();

   @Accessor("second")
   PerlinNoise wgaccel$second();

   @Accessor("valueFactor")
   double wgaccel$valueFactor();
}
