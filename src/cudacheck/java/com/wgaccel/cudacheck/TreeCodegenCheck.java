package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.NoiseCodegen;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * Outside the game (`gradlew.bat cudaTreeCheck`): builds trees of stand-in records named like FreeTerraForged's noise
 * kinds (the same class and field names, which is all {@link NoiseCodegen} reads), generates their CUDA, compiles it
 * with NVRTC and runs every node as a root on the GPU. This catches codegen and CUDA mistakes before a world load;
 * whether the results equal FreeTerraForged's is checked in game (TreeCheck).
 */
public final class TreeCodegenCheck {
   private TreeCodegenCheck() {
   }

   interface N {
      default float minValue() {
         return 0.0F;
      }

      default float maxValue() {
         return 1.0F;
      }
   }

   enum Interpolation { LINEAR, CURVE3, CURVE4 }

   enum DistanceFunction { EUCLIDEAN, MANHATTAN, NATURAL }

   enum CellFunction { CELL_VALUE, NOISE_LOOKUP, DISTANCE }

   enum BlendMode { CONSTANT, INPUT_LINEAR, OUTPUT_LINEAR }

   enum EdgeFunction {
      DISTANCE_2, DISTANCE_2_ADD, DISTANCE_2_SUB, DISTANCE_2_MUL, DISTANCE_2_DIV;

      public float min() {
         return -1.0F;
      }

      public float max() {
         return 1.0F;
      }

      public float range() {
         return 2.0F;
      }
   }

   record Constant(float value) implements N {
      @Override
      public float minValue() {
         return this.value;
      }

      @Override
      public float maxValue() {
         return this.value;
      }
   }

   record Direct(Object value) {
      public boolean isBound() {
         return true;
      }
   }

   record HolderHolder(Object holder) implements N {
   }

   record Perlin(int seed, float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float min, float max) implements N {
   }

   record Perlin2(int seed, float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float min, float max) implements N {
   }

   record PerlinRidge(float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float[] spectralWeights, float min, float max)
         implements N {
   }

   record Billow(float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float[] spectralWeights, float min, float max)
         implements N {
   }

   record Simplex(float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float min, float max) implements N {
   }

   record Simplex2(float frequency, int octaves, float lacunarity, float gain, Interpolation interpolation, float min, float max) implements N {
   }

   record Worley(float frequency, float distance, CellFunction cellFunction, DistanceFunction distanceFunction, Object lookup, float min, float max)
         implements N {
   }

   record WorleyEdge(float frequency, float distance, EdgeFunction edgeFunction, DistanceFunction distanceFunction) implements N {
   }

   record Cubic(float frequency, int octaves, float lacunarity, float gain, float minValue, float maxValue) implements N {
   }

   record Add(Object input1, Object input2) implements N {
   }

   record Multiply(Object input1, Object input2) implements N {
   }

   record Min(Object input1, Object input2) implements N {
   }

   record Max(Object input1, Object input2) implements N {
   }

   record Clamp(Object input, Object min, Object max) implements N {
   }

   record Map(Object alpha, Object from, Object to) implements N {
   }

   record Blend(Object alpha, Object lower, Object upper, float mid, float range, Interpolation interpolation) implements N {
   }

   record Alpha(Object input, Object alpha) implements N {
   }

   record Threshold(Object input, Object lower, Object upper, Object threshold) implements N {
   }

   record Power(Object input, float power) implements N {
   }

   record ShiftSeed(Object input, int shift) implements N {
   }

   record Cache2d(Object noise, Object cache) implements N {
   }

   record Warp(Object input, Object domain) implements N {
   }

   record DomainWarp(Object x, Object z, Object mappedX, Object mappedZ, Object distance) {
   }

   record DirectionWarp(Object direction, Object strength) {
   }

   record AddWarp(Object input1, Object input2) {
   }

   record CompoundWarp(Object input1, Object input2) {
   }

   record DirectWarp() {
   }

   record Erosion(Object input, int seed, int octaves, float strength, float gridSize, float amplitude, float lacunarity, float distanceFallOff,
         BlendMode blendMode, Object cache) implements N {
   }

   record PowerCurve(Object input, float power, float mid, float min, float max) implements N {
   }

   record Invert(Object input) implements N {
   }

   record Boost(Object input, int iterations) implements N {
   }

   record SCurveFunction(float lower, float upper) {
   }

   record Steps(Object input, Object steps, Object slopeMin, Object slopeMax, Object slopeCurve) implements N {
   }

   record Step(float value, float lowerBound, float upperBound) {
   }

   record Terrace(Object input, Object ramp, Object cliff, Object rampHeight, float blendRange, Step[] steps) implements N {
   }

   record AdvancedTerrace(Object source, Object modulation, Object mask, Object slope, float blendMin, float blendMax, int steps, int octaves)
         implements N {
   }

   record Curve(Object input, Object curveFunction) implements N {
   }

   record Gradient(Object input, Object lower, Object upper, Object strength) implements N {
   }

   record Frequency(Object input, Object xFreq, Object zFreq) implements N {
   }

   record LegacyMoisture(Object source, int power) implements N {
   }

   record LegacyTemperature(float frequency, int power) implements N {
   }

   /** A node of every kind the preset uses, many nested, the way FreeTerraForged builds them. */
   private static List<Object> trees() {
      List<Object> all = new ArrayList<>();
      Constant zero = new Constant(0.0F);
      Constant one = new Constant(1.0F);
      Constant half = new Constant(0.5F);
      Perlin perlin = new Perlin(1234, 0.002F, 4, 2.0F, 0.5F, Interpolation.CURVE3, -0.9F, 0.9F);
      Perlin2 perlin2 = new Perlin2(99, 0.004F, 3, 2.1F, 0.45F, Interpolation.CURVE4, -0.8F, 0.8F);
      PerlinRidge ridge = new PerlinRidge(0.003F, 3, 2.0F, 0.6F, Interpolation.CURVE3, new float[] {1.0F, 0.6F, 0.36F}, 0.0F, 1.5F);
      Billow billow = new Billow(0.001F, 2, 2.0F, 0.5F, Interpolation.LINEAR, new float[] {1.0F, 0.5F}, 0.0F, 1.2F);
      Simplex simplex = new Simplex(0.0015F, 3, 2.0F, 0.5F, Interpolation.CURVE3, -1.0F, 1.0F);
      Simplex2 simplex2 = new Simplex2(0.0025F, 2, 2.0F, 0.5F, Interpolation.CURVE3, -1.0F, 1.0F);
      Worley worley = new Worley(0.005F, 0.8F, CellFunction.CELL_VALUE, DistanceFunction.EUCLIDEAN, zero, -1.0F, 1.0F);
      Worley lookup = new Worley(0.002F, 0.7F, CellFunction.NOISE_LOOKUP, DistanceFunction.NATURAL, perlin, 0.0F, 1.0F);
      WorleyEdge edge = new WorleyEdge(0.003F, 0.9F, EdgeFunction.DISTANCE_2_DIV, DistanceFunction.MANHATTAN);
      Cubic cubic = new Cubic(0.002F, 3, 2.0F, 0.5F, -1.3125F, 1.3125F);
      HolderHolder held = new HolderHolder(new Direct(perlin));
      Add add = new Add(perlin, held);
      Multiply multiply = new Multiply(ridge, simplex);
      Min min = new Min(billow, half);
      Max max = new Max(cubic, worley);
      Clamp clamp = new Clamp(add, zero, one);
      Map map = new Map(simplex2, new Constant(-0.5F), new Constant(0.5F));
      Blend blend = new Blend(perlin, multiply, map, 0.5F, 0.2F, Interpolation.CURVE3);
      Alpha alpha = new Alpha(min, max);
      Threshold threshold = new Threshold(edge, clamp, blend, half);
      Power power = new Power(clamp, 1.7F);
      ShiftSeed shift = new ShiftSeed(ridge, 7);
      Cache2d cache = new Cache2d(power, null);
      Warp warp = new Warp(alpha, new DomainWarp(perlin, perlin2, map, map, new Constant(40.0F)));
      Warp turn = new Warp(threshold, new DirectionWarp(perlin, new Constant(25.0F)));
      Warp compound = new Warp(lookup, new CompoundWarp(new AddWarp(new DirectWarp(), new DirectionWarp(simplex, half)), new DirectWarp()));
      Erosion erosion = new Erosion(clamp, 77, 2, 0.6F, 80.0F, 0.5F, 2.0F, 0.5F, BlendMode.INPUT_LINEAR, null);
      PowerCurve curve = new PowerCurve(clamp, 2.0F, 0.5F, 0.25F, 0.75F);
      Invert invert = new Invert(perlin);
      Boost boost = new Boost(clamp, 2);
      Steps steps = new Steps(clamp, new Constant(8.0F), new Constant(0.2F), new Constant(0.6F), new SCurveFunction(0.5F, 0.4F));
      Terrace terrace = new Terrace(clamp, half, new Constant(0.3F), new Constant(0.8F), 0.2F,
         new Step[] {new Step(0.0F, -0.1F, 0.1F), new Step(0.33F, 0.23F, 0.43F), new Step(0.66F, 0.56F, 0.76F), new Step(1.0F, 0.9F, 1.1F)});
      AdvancedTerrace advanced = new AdvancedTerrace(clamp, half, one, new Constant(0.4F), 0.1F, 0.9F, 3, 2);
      Curve curveFn = new Curve(clamp, Interpolation.CURVE4);
      Gradient gradient = new Gradient(clamp, new Constant(0.3F), new Constant(0.7F), half);
      Frequency frequency = new Frequency(perlin, new Constant(1.5F), new Constant(0.75F));
      LegacyMoisture moisture = new LegacyMoisture(clamp, 3);
      LegacyTemperature temperature = new LegacyTemperature(0.001F, 2);
      Add top = new Add(new Add(new Add(warp, turn), new Add(compound, erosion)), new Add(new Add(curve, invert), new Add(boost, steps)));
      Add top2 = new Add(new Add(terrace, advanced), new Add(new Add(curveFn, gradient), new Add(frequency, new Add(moisture, new Add(temperature,
         new Add(cache, shift))))));
      all.addAll(List.of(perlin, perlin2, ridge, billow, simplex, simplex2, worley, lookup, edge, cubic, add, multiply, min, max, clamp, map, blend, alpha,
         threshold, power, shift, cache, warp, turn, compound, erosion, curve, invert, boost, steps, terrace, advanced, curveFn, gradient, frequency,
         moisture, temperature, top, top2));
      return all;
   }

   public static void main(String[] args) throws Exception {
      Field sin = NoiseUtil.class.getDeclaredField("SIN");
      sin.setAccessible(true);
      Field radToIndex = NoiseUtil.class.getDeclaredField("radToIndex");
      radToIndex.setAccessible(true);
      NoiseCodegen codegen = new NoiseCodegen(pairs(NoiseUtil.GRAD_2D_24), pairs(NoiseUtil.CELL_2D), (float[])sin.get(null), radToIndex.getFloat(null));
      List<Object> roots = new ArrayList<>(trees());
      for (Object root : roots) {
         codegen.function(root);
      }

      roots.sort(Comparator.comparingInt(codegen::id));
      List<String> names = new ArrayList<>();
      for (Object root : roots) {
         names.add(codegen.function(root));
      }

      String source = codegen.source(names);
      Path saved = Path.of("build", "tree-check.cu");
      Files.createDirectories(saved.getParent());
      Files.writeString(saved, source);
      System.out.printf(Locale.ROOT, "%d roots, CUDA source %,d bytes (saved as %s)%n", roots.size(), source.length(), saved);

      int points = 4096;
      FloatBuffer xs = MemoryUtil.memAllocFloat(points);
      FloatBuffer zs = MemoryUtil.memAllocFloat(points);
      IntBuffer seeds = MemoryUtil.memAllocInt(points);
      Random random = new Random(1L);
      for (int p = 0; p < points; p++) {
         xs.put(p, (random.nextFloat() - 0.5F) * 400_000.0F);
         zs.put(p, (random.nextFloat() - 0.5F) * 400_000.0F);
         seeds.put(p, random.nextInt());
      }

      long start = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "tree_check.cu")) {
         System.out.printf(Locale.ROOT, "compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
         int rows = roots.size();
         long out = gpu.allocate(4L * points * rows);
         long unsure = gpu.allocate(4L * points * rows);
         long run = System.nanoTime();
         gpu.run("treeCheck", (points + 255) / 256, rows, xs, zs, seeds, points, 0, 0, out, unsure);
         System.out.printf(Locale.ROOT, "%,d values in %.2f ms%n", (long)rows * points, (System.nanoTime() - run) / 1.0e6);
         FloatBuffer values = MemoryUtil.memAllocFloat(points * rows);
         IntBuffer flags = MemoryUtil.memAllocInt(points * rows);
         gpu.download(out, values);
         gpu.download(unsure, flags);
         for (int r = 0; r < rows; r++) {
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            int nan = 0;
            int notSure = 0;
            for (int p = 0; p < points; p++) {
               float v = values.get(r * points + p);
               if (Float.isNaN(v)) {
                  nan++;
               } else {
                  lo = Math.min(lo, v);
                  hi = Math.max(hi, v);
               }

               notSure += flags.get(r * points + p);
            }

            System.out.printf(Locale.ROOT, "%-6s %-18s min %-12s max %-12s NaN %d, unsure %d%n", names.get(r), NoiseCodegen.kind(roots.get(r)), lo, hi, nan, notSure);
         }
      }
   }

   private static float[] pairs(NoiseUtil.Vec2f[] table) {
      float[] values = new float[table.length * 2];
      for (int i = 0; i < table.length; i++) {
         values[i * 2] = table[i].x();
         values[i * 2 + 1] = table[i].y();
      }

      return values;
   }
}
