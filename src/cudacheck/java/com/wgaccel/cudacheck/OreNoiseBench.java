package com.wgaccel.cudacheck;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Times vanilla's ore vein noises (ore_vein_a / ore_vein_b: first octave -7, amplitudes 1, 1, 1, 1; ore_gap: -5, 1) as
 * No Man's Land's ore veins sample them: a 5 x 5 lattice every 4 blocks over a 768-block world, vein noises at 4 times
 * the block coordinates.
 */
public final class OreNoiseBench {
   public static void main(String[] args) {
      RandomSource random = RandomSource.create(1);
      NormalNoise veinA = NormalNoise.create(random, new NormalNoise.NoiseParameters(-7, 1.0, 1.0, 1.0, 1.0));
      NormalNoise veinB = NormalNoise.create(random, new NormalNoise.NoiseParameters(-7, 1.0, 1.0, 1.0, 1.0));
      NormalNoise gap = NormalNoise.create(random, new NormalNoise.NoiseParameters(-5, 1.0));
      double sink = 0;
      for (int round = 0; round < 5; round++) {
         long start = System.nanoTime();
         int samples = 0;
         for (int chunk = 0; chunk < 200; chunk++) {
            int minX = chunk * 16;
            int minZ = 4000 - chunk * 16;
            for (int y = -256; y <= 512; y += 4) {
               for (int z = 0; z <= 16; z += 4) {
                  for (int x = 0; x <= 16; x += 4) {
                     sink += veinA.getValue((minX + x) * 4, y * 4, (minZ + z) * 4);
                     sink += veinB.getValue((minX + x) * 4, y * 4, (minZ + z) * 4);
                     sink += gap.getValue(minX + x, y, minZ + z);
                     samples += 3;
                  }
               }
            }
         }

         long nanos = System.nanoTime() - start;
         System.out.printf("round %d: %.3f ms per chunk (all 768 blocks), %.0f ns per sample%n", round, nanos / 200.0 / 1e6, nanos / (double)samples);
      }

      System.out.println(sink == 0 ? "" : "done");
   }
}
