package com.wgaccel.cudacheck;

import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.Gpu;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.lwjgl.system.MemoryUtil;

/**
 * Outside the game (`gradlew.bat cudaEndIslandsCheck`): vanilla's own End islands function (the real Minecraft class) for
 * a few seeds, against its CUDA from {@link DensityCodegen} (C2.5), compared bit for bit at points near the centre, in the
 * outer islands and far out (where the int square wraps).
 */
public final class EndIslandsCheck {
   private EndIslandsCheck() {
   }

   public static void main(String[] args) throws Exception {
      // The function's interface makes registry codecs when it loads; outside the game they only ask that the game says it
      // has started (nothing from them is used here).
      net.minecraft.SharedConstants.tryDetectVersion();
      java.lang.reflect.Field started = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
      started.setAccessible(true);
      started.setBoolean(null, true);
      int points = 16384;
      IntBuffer xs = MemoryUtil.memAllocInt(points);
      IntBuffer ys = MemoryUtil.memAllocInt(points);
      IntBuffer zs = MemoryUtil.memAllocInt(points);
      Random random = new Random(7L);
      for (int p = 0; p < points; p++) {
         int range = switch (p % 4) {
            case 0 -> 2_000;
            case 1 -> 12_000;
            case 2 -> 200_000;
            default -> 30_000_000;
         };
         xs.put(p, random.nextInt(2 * range) - range);
         ys.put(p, random.nextInt(256));
         zs.put(p, random.nextInt(2 * range) - range);
      }

      long different = 0;
      for (long seed : new long[] {0L, 1L, -4172144997902289642L, 123456789L}) {
         // The class itself (DensityFunctions.endIslands would load DensityFunctions, which needs the game's registries).
         java.lang.reflect.Constructor<?> make = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$EndIslandDensityFunction")
            .getDeclaredConstructor(long.class);
         make.setAccessible(true);
         DensityFunction islands = (DensityFunction)make.newInstance(seed);
         DensityCodegen codegen = new DensityCodegen();
         String name = codegen.function(islands);
         String source = codegen.source(List.of(name));
         int[] permutations = codegen.permutations();
         double[] noiseData = codegen.noiseData();
         try (Gpu gpu = new Gpu(source, "end_islands_check.cu")) {
            long out = gpu.allocate(8L * points);
            gpu.run("densityCheck", (points + 255) / 256, 1, xs, ys, zs, MemoryUtil.memAllocDouble(1), points,
               MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData), MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations),
               0, 0, out);
            DoubleBuffer values = MemoryUtil.memAllocDouble(points);
            gpu.download(out, values);
            int differ = 0;
            int islandsSeen = 0;
            for (int p = 0; p < points; p++) {
               double java = islands.compute(new DensityFunction.SinglePointContext(xs.get(p), ys.get(p), zs.get(p)));
               double cuda = values.get(p);
               if (java > -0.84375) {
                  islandsSeen++;
               }

               // A NaN on both sides (far out, where Java's int square wraps) is the same block either way (never above 0).
               if (Double.doubleToRawLongBits(java) != Double.doubleToRawLongBits(cuda) && !(Double.isNaN(java) && Double.isNaN(cuda))) {
                  if (differ++ < 5) {
                     System.out.printf(Locale.ROOT, "  seed %d at (%d, %d): Java %s, GPU %s%n", seed, xs.get(p), zs.get(p), java, cuda);
                  }
               }
            }

            different += differ;
            System.out.printf(Locale.ROOT, "seed %d: %,d points (%,d above the floor): %s%n", seed, points, islandsSeen,
               differ == 0 ? "IDENTICAL" : differ + " DIFFER");
         }
      }

      System.out.println(different == 0 ? "End islands: IDENTICAL" : "End islands: " + different + " DIFFER");
      if (different != 0) {
         System.exit(1);
      }
   }
}
