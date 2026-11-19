package com.wgaccel.gen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.DensityCodegen;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * FreeTerraForged's Lithostitched biome injection (LithostitchedInjectionBridge$ParameterCriteria.matches): every rule
 * with density criteria works its density function out at each 4 x 4 x 4 cell's corner it is asked about, for every cell
 * of every chunk (9.4% of the CPU with Regions Unexplored and Oh The Biomes We've Gone, most of it Lithostitched's
 * shifted noises). Kept per thread:
 * - the value at a point, for the same function asked again there;
 * - for a function that depends on x and z only ({@link DensityCodegen#heightFree}), its value per column, handed out up
 *   the column; for one whose only use of y is range tests on a y gradient ({@link DensityCodegen#yTests}: Regions
 *   Unexplored's inferno_weight, its cave cells below y 0 and 0 above), per column and set of passed tests. Every 256th
 *   of those is worked out again and compared; a difference turns this off with a warning.
 * The same values as asking the function.
 */
public final class CriteriaMemo {
   private static final int BITS = 12;
   private static final int MASK = (1 << BITS) - 1;
   private static final LongAdder COMPUTED = new LongAdder();
   private static final LongAdder POINT_KEPT = new LongAdder();
   private static final LongAdder COLUMN_KEPT = new LongAdder();
   private static final AtomicLong CHECKS = new AtomicLong();
   private static final Map<Object, Info> SEEN = new IdentityHashMap<>();
   private static volatile boolean disabled;

   private static final class State {
      final Object[] pointFunction = new Object[1 << BITS];
      final int[] pointX = new int[1 << BITS];
      final int[] pointY = new int[1 << BITS];
      final int[] pointZ = new int[1 << BITS];
      final double[] pointValue = new double[1 << BITS];
      final Object[] columnFunction = new Object[1 << BITS];
      final int[] columnX = new int[1 << BITS];
      final int[] columnZ = new int[1 << BITS];
      final double[] columnValue = new double[1 << BITS];
      final int[] columnBand = new int[1 << BITS];
      final IdentityHashMap<Object, Info> infos = new IdentityHashMap<>();
      int sample;
   }

   /** A function's column use: whether kept per column, and its y tests (none for x and z only). */
   private record Info(boolean column, double[][] tests) {
      int band(int y) {
         int band = 0;
         for (int i = 0; i < this.tests.length; i++) {
            double[] t = this.tests[i];
            // YClampedGradient.compute, then RangeChoice's test.
            double v = net.minecraft.util.Mth.clampedMap((double)y, t[0], t[1], t[2], t[3]);
            if (v >= t[4] && v < t[5]) {
               band |= 1 << i;
            }
         }

         return band;
      }
   }

   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private CriteriaMemo() {
   }

   /** The function's value at the context's block, kept or worked out (the wrapped DensityFunction.compute). */
   public static double compute(DensityFunction function, DensityFunction.FunctionContext context, Operation<Double> original) {
      if (disabled || !Config.FAST_LITHOSTITCHED_CRITERIA.get()) {
         return original.call(function, context);
      }

      int x = context.blockX();
      int y = context.blockY();
      int z = context.blockZ();
      State s = STATE.get();
      int h = System.identityHashCode(function) * 0x9E3779B1 ^ x * 0x85EBCA6B ^ z * 0xC2B2AE35;
      h ^= h >>> 15;
      int point = (h ^ y * 0x27D4EB2F) & MASK;
      point ^= point >>> BITS;
      point &= MASK;
      if (s.pointFunction[point] == function && s.pointX[point] == x && s.pointY[point] == y && s.pointZ[point] == z) {
         POINT_KEPT.increment();
         return s.pointValue[point];
      }

      Info info = s.infos.get(function);
      if (info == null) {
         info = info(function);
         s.infos.put(function, info);
      }

      boolean free = info.column;
      int band = free ? info.band(y) : 0;
      int column = (h ^ band * 0x632BE5AB) & MASK;
      if (free && s.columnFunction[column] == function && s.columnX[column] == x && s.columnZ[column] == z && s.columnBand[column] == band) {
         double kept = s.columnValue[column];
         if ((++s.sample & 255) != 0) {
            COLUMN_KEPT.increment();
            keep(s, point, function, x, y, z, kept);
            return kept;
         }

         double real = original.call(function, context);
         if (Double.doubleToRawLongBits(real) != Double.doubleToRawLongBits(kept)) {
            disabled = true;
            WorldgenAccelerator.LOG.warn(String.format(Locale.ROOT,
               "WGA Lithostitched criteria: %s at (%d, %d, %d) is %s, its column's kept value %s; off", function.getClass().getSimpleName(), x, y, z,
               real, kept));
            return real;
         }

         if (CHECKS.incrementAndGet() % 1024 == 0) {
            WorldgenAccelerator.LOG.info(summary());
         }

         keep(s, point, function, x, y, z, real);
         return real;
      }

      double value = original.call(function, context);
      COMPUTED.increment();
      keep(s, point, function, x, y, z, value);
      if (free) {
         s.columnFunction[column] = function;
         s.columnX[column] = x;
         s.columnZ[column] = z;
         s.columnValue[column] = value;
         s.columnBand[column] = band;
      }

      return value;
   }

   private static void keep(State s, int point, Object function, int x, int y, int z, double value) {
      s.pointFunction[point] = function;
      s.pointX[point] = x;
      s.pointY[point] = y;
      s.pointZ[point] = z;
      s.pointValue[point] = value;
   }

   /** Worked out once per function (logged the first time a function is seen in this game). */
   private static Info info(DensityFunction function) {
      java.util.List<double[]> tests = DensityCodegen.yTests(function);
      Info info = tests == null || tests.size() > 31 ? new Info(false, new double[0][]) : new Info(true, tests.toArray(new double[0][]));
      synchronized (SEEN) {
         if (SEEN.put(function, info) == null && SEEN.size() <= 32) {
            String how = !info.column ? "depends on y (kept per point only): " + DensityCodegen.describe(function, 12)
               : info.tests.length == 0 ? "x and z only (kept per column)"
               : "x and z only apart from " + info.tests.length + " y range test(s) (kept per column and test result)";
            WorldgenAccelerator.LOG.info("WGA Lithostitched criteria: a {} function, {}", function.getClass().getSimpleName(), how);
         }
      }

      return info;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA Lithostitched criteria: %,d values worked out, %,d kept for the same point, %,d kept up the column; checked %,d: %s",
         COMPUTED.sum(), POINT_KEPT.sum(), COLUMN_KEPT.sum(), CHECKS.get(), disabled ? "DIFFERENT (off)" : "IDENTICAL");
   }
}
