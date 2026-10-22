package com.wgaccel.cudacheck;

import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Vanilla's BlendedNoise for the checks outside the game, where the real class cannot load (it is a DensityFunction,
 * whose static codecs need Minecraft's registries: "Not bootstrapped"). The same name and fields (all DensityCodegen
 * reads), built the same way from the real PerlinNoise and ImprovedNoise classes, and its compute copied line for line
 * from vanilla's (1.21.1), so it gives Minecraft's own values.
 */
final class BlendedNoise {
   private final PerlinNoise minLimitNoise;
   private final PerlinNoise maxLimitNoise;
   private final PerlinNoise mainNoise;
   private final double xzMultiplier;
   private final double yMultiplier;
   private final double xzFactor;
   private final double yFactor;
   private final double smearScaleMultiplier;

   BlendedNoise(RandomSource random, double xzScale, double yScale, double xzFactor, double yFactor, double smearScaleMultiplier) {
      this.minLimitNoise = PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-15, 0));
      this.maxLimitNoise = PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-15, 0));
      this.mainNoise = PerlinNoise.createLegacyForBlendedNoise(random, IntStream.rangeClosed(-7, 0));
      this.xzFactor = xzFactor;
      this.yFactor = yFactor;
      this.smearScaleMultiplier = smearScaleMultiplier;
      this.xzMultiplier = 684.412 * xzScale;
      this.yMultiplier = 684.412 * yScale;
   }

   double compute(int blockX, int blockY, int blockZ) {
      double d0 = (double)blockX * this.xzMultiplier;
      double d1 = (double)blockY * this.yMultiplier;
      double d2 = (double)blockZ * this.xzMultiplier;
      double d3 = d0 / this.xzFactor;
      double d4 = d1 / this.yFactor;
      double d5 = d2 / this.xzFactor;
      double d6 = this.yMultiplier * this.smearScaleMultiplier;
      double d7 = d6 / this.yFactor;
      double d8 = 0.0;
      double d9 = 0.0;
      double d10 = 0.0;
      double d11 = 1.0;

      for (int i = 0; i < 8; i++) {
         ImprovedNoise improvednoise = this.mainNoise.getOctaveNoise(i);
         if (improvednoise != null) {
            d10 += improvednoise.noise(PerlinNoise.wrap(d3 * d11), PerlinNoise.wrap(d4 * d11), PerlinNoise.wrap(d5 * d11), d7 * d11, d4 * d11) / d11;
         }

         d11 /= 2.0;
      }

      double d16 = (d10 / 10.0 + 1.0) / 2.0;
      boolean flag1 = d16 >= 1.0;
      boolean flag2 = d16 <= 0.0;
      d11 = 1.0;

      for (int j = 0; j < 16; j++) {
         double d12 = PerlinNoise.wrap(d0 * d11);
         double d13 = PerlinNoise.wrap(d1 * d11);
         double d14 = PerlinNoise.wrap(d2 * d11);
         double d15 = d6 * d11;
         if (!flag1) {
            ImprovedNoise improvednoise1 = this.minLimitNoise.getOctaveNoise(j);
            if (improvednoise1 != null) {
               d8 += improvednoise1.noise(d12, d13, d14, d15, d1 * d11) / d11;
            }
         }

         if (!flag2) {
            ImprovedNoise improvednoise2 = this.maxLimitNoise.getOctaveNoise(j);
            if (improvednoise2 != null) {
               d9 += improvednoise2.noise(d12, d13, d14, d15, d1 * d11) / d11;
            }
         }

         d11 /= 2.0;
      }

      return Mth.clampedLerp(d8 / 512.0, d9 / 512.0, d16) / 128.0;
   }
}
