package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.DensityCodegen;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * A chunk's climate temperature and humidity worked out once per column. Its biome fill asks Climate.Sampler.sample at
 * every quart of the chunk, 16 columns of 96, and each sample works out all six climate values (31 Oct 2026 profile,
 * vanilla 26.1.2: its temperature and humidity noises, 2D in vanilla's router, were 4% of the CPU). When the world's
 * router proves both depend on x and z only ({@link DensityCodegen#heightFree}), the chunk's sampler (made by its noise
 * chunk on this thread) keeps each column's two values for the column's other heights. Every 64th kept value is compared
 * with the function's own; a difference turns this off.
 */
public final class ClimateColumns {
   private static volatile boolean disabled;
   private static volatile NoiseRouter lastRouter;
   private static volatile int lastFlags;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private ClimateColumns() {
   }

   /** This thread's chunk sampler and its 16 columns' values (0 temperature, 1 humidity). */
   private static final class State {
      Object sampler;
      int flags;
      final long[][] keys = new long[2][16];
      final boolean[][] has = new boolean[2][16];
      final double[][] values = new double[2][16];
      int calls;
   }

   /** Which of temperature (1) and humidity (2) depend on x and z only in this router (worked out once per router). */
   private static int flags(NoiseRouter router) {
      if (router == lastRouter) {
         return lastFlags;
      }

      int flags = (DensityCodegen.heightFree(router.temperature()) ? 1 : 0) | (DensityCodegen.heightFree(router.vegetation()) ? 2 : 0);
      WorldgenAccelerator.LOG.info("WGA climate columns: temperature {}, humidity {} (a router of this world)", (flags & 1) != 0 ? "per column" : "at every height",
         (flags & 2) != 0 ? "per column" : "at every height");
      lastFlags = flags;
      lastRouter = router;
      return flags;
   }

   /** A noise chunk made a climate sampler from this router on this thread (NoiseChunk.cachedClimateSampler). */
   public static void made(Object sampler, NoiseRouter router) {
      if (disabled || !Config.FAST_CLIMATE_COLUMNS.get()) {
         return;
      }

      State s = STATE.get();
      s.sampler = sampler;
      s.flags = flags(router);
      java.util.Arrays.fill(s.has[0], false);
      java.util.Arrays.fill(s.has[1], false);
   }

   /** The sampler's temperature (0) or humidity (1) at this point: kept for its column, or worked out. */
   public static double value(Object sampler, int which, DensityFunction function, DensityFunction.FunctionContext context) {
      if (disabled) {
         return function.compute(context);
      }

      State s = STATE.get();
      if (s.sampler != sampler || (s.flags & 1 << which) == 0) {
         return function.compute(context);
      }

      int x = context.blockX();
      int z = context.blockZ();
      int slot = (x >> 2 & 3) << 2 | z >> 2 & 3;
      long key = (long)x << 32 | z & 0xFFFFFFFFL;
      if (s.has[which][slot] && s.keys[which][slot] == key) {
         double kept = s.values[which][slot];
         if ((++s.calls & 63) == 0) {
            check(kept, function.compute(context), which, x, context.blockY(), z);
         }

         return kept;
      }

      double value = function.compute(context);
      s.keys[which][slot] = key;
      s.values[which][slot] = value;
      s.has[which][slot] = true;
      return value;
   }

   private static void check(double kept, double real, int which, int x, int y, int z) {
      CHECKED.increment();
      if (Double.doubleToRawLongBits(kept) != Double.doubleToRawLongBits(real)) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA climate columns: {} at {}, {}, {} was {} kept for its column but {} worked out; off, every height works it out",
               which == 0 ? "temperature" : "humidity", x, y, z, kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA climate columns: about %,d kept values handed out, checked %,d: %s", CHECKED.sum() * 64, CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
