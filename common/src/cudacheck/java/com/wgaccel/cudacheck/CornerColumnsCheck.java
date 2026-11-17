package com.wgaccel.cudacheck;

import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.Gpu;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.lwjgl.system.MemoryUtil;

/**
 * C2 outside the game (`gradlew.bat cudaCornerColumnsCheck`): the corner kernel with its column subtrees cached per
 * corner column (hoisting on) against the same tree with every corner working everything out (hoisting off), bit for
 * bit, on a vanilla-like terrain tree: height splines over flat-cached shifted climate noises, BlendedNoise, a 3D noise
 * and y gradients, in chunks above and below y 0 and far from the origin.
 */
public final class CornerColumnsCheck {
   private CornerColumnsCheck() {
   }

   private static DensityFunctions.HolderHolder held(Object function) {
      return new DensityFunctions.HolderHolder(new DensityFunctions.Direct(function));
   }

   private static Object tree() {
      NormalNoise offset = NormalNoise.create(new XoroshiroRandomSource(31L), new NormalNoise.NoiseParameters(-3, 1.0, 1.0, 1.0, 0.0));
      NormalNoise continents = NormalNoise.create(new XoroshiroRandomSource(32L), new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0));
      NormalNoise erosion = NormalNoise.create(new XoroshiroRandomSource(33L), new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 0.0, 1.0, 1.0));
      NormalNoise cave = NormalNoise.create(new XoroshiroRandomSource(34L), new NormalNoise.NoiseParameters(-7, 1.0, 1.0));
      DensityFunctions.NoiseHolder offsetHolder = new DensityFunctions.NoiseHolder(null, offset);
      DensityFunctions.ShiftA shiftA = new DensityFunctions.ShiftA(offsetHolder);
      DensityFunctions.ShiftB shiftB = new DensityFunctions.ShiftB(offsetHolder);
      DensityFunctions.Constant zero = new DensityFunctions.Constant(0.0);
      Object continentalness = new DensityFunctions.Marker(DensityFunctions.MarkerType.FlatCache, held(new DensityFunctions.ShiftedNoise(held(shiftA), zero,
         held(shiftB), 0.25, 0.0, new DensityFunctions.NoiseHolder(null, continents))));
      Object erosionFn = new DensityFunctions.Marker(DensityFunctions.MarkerType.FlatCache, held(new DensityFunctions.ShiftedNoise(held(shiftA), zero,
         held(shiftB), 0.25, 0.0, new DensityFunctions.NoiseHolder(null, erosion))));
      CubicSpline.Multipoint inner = new CubicSpline.Multipoint(new DensityFunctions.Spline.Coordinate(new DensityFunctions.Direct(erosionFn)),
         new float[] {-0.5F, 0.0F, 0.5F}, List.of(new CubicSpline.Constant(-0.2F), new CubicSpline.Constant(0.1F), new CubicSpline.Constant(0.4F)),
         new float[] {0.0F, 0.5F, 0.0F}, -0.2F, 0.4F);
      CubicSpline.Multipoint outer = new CubicSpline.Multipoint(new DensityFunctions.Spline.Coordinate(new DensityFunctions.Direct(continentalness)),
         new float[] {-1.0F, -0.2F, 0.3F, 1.0F}, List.of(new CubicSpline.Constant(-1.0F), inner, new CubicSpline.Constant(0.2F), inner),
         new float[] {0.3F, 0.0F, -0.4F, 0.0F}, -1.0F, 0.4F);
      DensityFunctions.Spline depthSpline = new DensityFunctions.Spline(outer);
      DensityFunctions.YClampedGradient gradient = new DensityFunctions.YClampedGradient(-64, 320, 1.5, -1.5);
      DensityFunctions.Ap2 depth = new DensityFunctions.Ap2(DensityFunctions.TwoType.ADD, held(gradient), held(depthSpline), -3.0, 3.0);
      DensityFunctions.Ap2 factor = new DensityFunctions.Ap2(DensityFunctions.TwoType.MUL, held(depth), held(depthSpline), -3.0, 3.0);
      BlendedNoise base = new BlendedNoise(new XoroshiroRandomSource(35L), 0.25, 0.125, 80.0, 160.0, 8.0);
      DensityFunctions.Ap2 sloped = new DensityFunctions.Ap2(DensityFunctions.TwoType.ADD, held(factor), held(base), -4.0, 4.0);
      DensityFunctions.Noise cave3d = new DensityFunctions.Noise(new DensityFunctions.NoiseHolder(null, cave), 0.75, 0.5);
      DensityFunctions.Ap2 caves = new DensityFunctions.Ap2(DensityFunctions.TwoType.MIN, held(sloped), held(cave3d), -4.0, 2.0);
      Object first = new DensityFunctions.Marker(DensityFunctions.MarkerType.Interpolated, held(caves));
      Object second = new DensityFunctions.Marker(DensityFunctions.MarkerType.Interpolated, held(new DensityFunctions.Ap2(DensityFunctions.TwoType.MUL,
         held(depthSpline), held(continentalness), -1.0, 1.0)));
      return new DensityFunctions.Ap2(DensityFunctions.TwoType.ADD, held(first), held(second), -5.0, 5.0);
   }

   private static double[] corners(boolean hoist, int chunkX, int chunkZ, int cellMinY, int cellCountY) throws Exception {
      DensityCodegen codegen = new DensityCodegen();
      codegen.hoistColumns(hoist);
      String step = codegen.function(tree(), DensityCodegen.Mode.BLOCK_STEP);
      List<String> corners = new ArrayList<>();
      for (Object input : codegen.interpolatorInputs()) {
         corners.add(codegen.function(input, DensityCodegen.Mode.CORNER));
      }

      String source = codegen.chunkSource(step, corners);
      if (hoist && chunkX == 0 && chunkZ == 0) {
         System.out.printf(Locale.ROOT, "cached column subtrees in the corner functions: %d%n", source.split("__device__ __noinline__ double dc_cc", -1).length - 1);
      }
      int nx = 16 / 4 + 1;
      int per = nx * nx * (cellCountY + 1);
      int count = corners.size() * per;
      double[] noiseData = codegen.noiseData();
      int[] permutations = codegen.permutations();
      try (Gpu gpu = new Gpu(source, "corner_columns_check.cu")) {
         // C2: with prefilled corner columns, cornerColumns first (into the buffer after the corners), as FillGpu runs it.
         int slots = codegen.hasCornerColumns() ? codegen.cacheSlots() : 0;
         long out = gpu.allocate(8L * (count + (long)slots * 2 * nx * nx));
         if (slots > 0) {
            gpu.run("cornerColumns", (slots * 2 * nx * nx + 255) / 256, 1, MemoryUtil.memAllocDouble(1),
               MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData), MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations),
               chunkX, chunkZ, cellMinY, cellCountY, 4, 8, corners.size(), slots, out);
         }

         gpu.run("densityCorners", (count + 255) / 256, 1, MemoryUtil.memAllocDouble(1), MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData),
            MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations), chunkX, chunkZ, cellMinY, cellCountY, 4, 8, corners.size(), out);
         DoubleBuffer values = MemoryUtil.memAllocDouble(count);
         gpu.download(out, values);
         double[] result = new double[count];
         values.get(0, result);
         return result;
      }
   }

   public static void main(String[] args) throws Exception {
      int[][] chunks = {{0, 0}, {-16, 32}, {1_234_560, -987_648}, {-29_999_984, 29_999_968}};
      boolean all = true;
      for (int[] chunk : chunks) {
         // Vanilla's overworld: cells 4 x 8 from y -64, 48 cells (corner heights -64 to 320).
         double[] plain = corners(false, chunk[0], chunk[1], -8, 48);
         double[] cached = corners(true, chunk[0], chunk[1], -8, 48);
         int differ = 0;
         String first = null;
         for (int i = 0; i < plain.length; i++) {
            if (Double.doubleToRawLongBits(plain[i]) != Double.doubleToRawLongBits(cached[i])) {
               differ++;
               if (first == null) {
                  first = String.format(Locale.ROOT, "first at %d: plain %s, cached %s", i, plain[i], cached[i]);
               }
            }
         }

         all &= differ == 0;
         System.out.printf(Locale.ROOT, "chunk at (%d, %d): %,d corners: %s%n", chunk[0], chunk[1], plain.length, differ == 0 ? "BIT-IDENTICAL" : differ + " DIFFER, " + first);
      }

      System.out.println(all ? "corner columns: ALL BIT-IDENTICAL" : "corner columns: DIFFERENCES");
      if (!all) {
         System.exit(1);
      }
   }
}
