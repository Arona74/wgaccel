package com.wgaccel.cudacheck;

import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.Gpu;
import java.nio.DoubleBuffer;
import java.util.Locale;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * S7 outside the game: the GPU's Beardifier ({@link DensityCodegen#BEARDIFIER}) against Minecraft's own
 * Beardifier (its getBuryContribution, getBeardContribution and BEARD_KERNEL, copied line for line), summed as its compute sums
 * them, for random pieces, junctions and blocks, bit for bit: `gradlew.bat cudaBeardifierCheck`.
 */
public final class BeardifierCheck {
   private BeardifierCheck() {
   }

   // Minecraft 1.21.1's Beardifier, line for line (the class itself cannot load outside the game: its interfaces need
   // the registries): vanilla's getBuryContribution with Mth.clampedMap, getBeardContribution with Mth.fastInvSqrt.
   private static double computeBeardContribution(int x, double y, int z) {
      double d0 = net.minecraft.util.Mth.lengthSquared((double)x, y, (double)z);
      return Math.pow(Math.E, -d0 / 16.0);
   }

   private static double bury(double x, double y, double z) {
      double d0 = net.minecraft.util.Mth.length(x, y, z);
      return net.minecraft.util.Mth.clampedMap(d0, 0.0, 6.0, 1.0, 0.0);
   }

   private static double beard(float[] kernel, int x, int y, int z, int yy) {
      int i = x + 12;
      int j = y + 12;
      int k = z + 12;
      if (i >= 0 && i < 24 && j >= 0 && j < 24 && k >= 0 && k < 24) {
         double d0 = (double)yy + 0.5;
         double d1 = net.minecraft.util.Mth.lengthSquared((double)x, d0, (double)z);
         double d2 = -d0 * net.minecraft.util.Mth.fastInvSqrt(d1 / 2.0) / 2.0;
         return d2 * (double)kernel[k * 24 * 24 + i * 24 + j];
      }

      return 0.0;
   }

   public static void main(String[] args) throws Exception {
      float[] kernel = new float[13824];
      for (int i = 0; i < 24; i++) {
         for (int j = 0; j < 24; j++) {
            for (int k = 0; k < 24; k++) {
               kernel[i * 24 * 24 + j * 24 + k] = (float)computeBeardContribution(j - 12, (double)(k - 12) + 0.5, i - 12);
            }
         }
      }

      Random random = new Random(77L);
      int pieces = 12;
      int junctions = 16;
      int[] b = new int[2 + 8 * pieces + 3 * junctions];
      b[0] = pieces;
      b[1] = junctions;
      for (int i = 0; i < pieces; i++) {
         int x0 = random.nextInt(40) - 8;
         int y0 = random.nextInt(40) - 8;
         int z0 = random.nextInt(40) - 8;
         int at = 2 + 8 * i;
         b[at] = x0;
         b[at + 1] = y0;
         b[at + 2] = z0;
         b[at + 3] = x0 + random.nextInt(12);
         b[at + 4] = y0 + random.nextInt(12);
         b[at + 5] = z0 + random.nextInt(12);
         b[at + 6] = random.nextInt(7) - 3;
         b[at + 7] = random.nextInt(5);
      }

      for (int i = 0; i < junctions; i++) {
         int at = 2 + 8 * pieces + 3 * i;
         b[at] = random.nextInt(40) - 8;
         b[at + 1] = random.nextInt(40) - 8;
         b[at + 2] = random.nextInt(40) - 8;
      }

      int n = 48 * 48 * 48;
      int[] points = new int[3 * n];
      for (int i = 0; i < n; i++) {
         points[3 * i] = i % 48 - 12;
         points[3 * i + 1] = (i / 48) % 48 - 12;
         points[3 * i + 2] = i / 2304 - 12;
      }

      String source = DensityCodegen.BEARDIFIER + """
         extern "C" __global__ void beardCheck(const int* B, const float* K, const int* p, int n, double* out) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= n) return;
             out[t] = bf_value(B, K, p[t * 3], p[t * 3 + 1], p[t * 3 + 2]);
         }
         """;
      try (Gpu gpu = new Gpu(source, "beardifier_check.cu")) {
         long out = gpu.allocate(8L * n);
         gpu.run("beardCheck", (n + 255) / 256, 1, MemoryUtil.memAllocInt(b.length).put(0, b), MemoryUtil.memAllocFloat(kernel.length).put(0, kernel),
            MemoryUtil.memAllocInt(points.length).put(0, points), n, out);
         DoubleBuffer values = MemoryUtil.memAllocDouble(n);
         gpu.download(out, values);
         int differ = 0;
         int nonZero = 0;
         String first = null;
         for (int i = 0; i < n; i++) {
            int x = points[3 * i];
            int y = points[3 * i + 1];
            int z = points[3 * i + 2];
            double d = 0.0;
            for (int k = 0; k < pieces; k++) {
               int at = 2 + 8 * k;
               int m = Math.max(0, Math.max(b[at] - x, x - b[at + 3]));
               int w = Math.max(0, Math.max(b[at + 2] - z, z - b[at + 5]));
               int k1 = b[at + 1] + b[at + 6];
               int l1 = y - k1;
               int i2 = switch (b[at + 7]) {
                  case 0 -> 0;
                  case 1, 2 -> l1;
                  case 3 -> Math.max(0, Math.max(k1 - y, y - b[at + 4]));
                  default -> Math.max(0, Math.max(b[at + 1] - y, y - b[at + 4]));
               };
               d += switch (b[at + 7]) {
                  case 0 -> 0.0;
                  case 1 -> bury((double)m, (double)i2 / 2.0, (double)w);
                  case 2, 3 -> beard(kernel, m, i2, w, l1) * 0.8;
                  default -> bury((double)m / 2.0, (double)i2 / 2.0, (double)w / 2.0) * 0.8;
               };
            }

            for (int k = 0; k < junctions; k++) {
               int at = 2 + 8 * pieces + 3 * k;
               int j2 = x - b[at];
               int k2 = y - b[at + 1];
               int l2 = z - b[at + 2];
               d += beard(kernel, j2, k2, l2, k2) * 0.4;
            }

            nonZero += d != 0.0 ? 1 : 0;
            if (Double.doubleToRawLongBits(d) != Double.doubleToRawLongBits(values.get(i))) {
               differ++;
               if (first == null) {
                  first = String.format(Locale.ROOT, "(%d, %d, %d): java %s, gpu %s", x, y, z, d, values.get(i));
               }
            }
         }

         System.out.printf(Locale.ROOT, "beardifier: %,d blocks (%,d non-zero), %d pieces of every kind and %d junctions: %s%n", n, nonZero, pieces, junctions,
            differ == 0 ? "BIT-IDENTICAL" : differ + " DIFFER, first " + first);
      }
   }
}
