package com.wgaccel.mixin.access;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A PerlinNoise's octaves and factors, to pack it for the GPU (step G2.2). */
@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {
   @Accessor("noiseLevels")
   ImprovedNoise[] wgaccel$noiseLevels();

   @Accessor("amplitudes")
   DoubleList wgaccel$amplitudes();

   @Accessor("lowestFreqInputFactor")
   double wgaccel$lowestFreqInputFactor();

   @Accessor("lowestFreqValueFactor")
   double wgaccel$lowestFreqValueFactor();
}
