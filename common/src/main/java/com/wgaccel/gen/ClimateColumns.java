package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.ftf.FtfDepthFunction;
import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * A chunk's height-free climate channels worked out once per column. Its biome
 * fill asks Climate.Sampler.sample at
 * every quart of the chunk, 16 columns of 96, and each sample works out all six
 * climate values. When the world's router
 * proves any of temperature (0), humidity (1), continentalness (2), erosion
 * (3), or weirdness (4) depend on x and z
 * only ({@link DensityCodegen#heightFree}), the chunk's sampler (made by its
 * noise chunk on this thread) keeps each
 * column's values for the column's other heights. Every 64th kept value is
 * compared with the function's own; a
 * difference turns this off.
 */
public final class ClimateColumns {
   private static final int CHANNELS = 5;
   private static final String[] CHANNEL_NAMES = { "temperature", "humidity", "continentalness", "erosion", "weirdness",
         "depth" };
   private static volatile boolean disabled;
   private static volatile NoiseRouter lastRouter;
   private static volatile int lastFlags;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final WorldgenPlans.SamplerDecoratorStage[] EMPTY_STAGES = new WorldgenPlans.SamplerDecoratorStage[0];
   private static final MethodHandle STILL_LIFE_APPLY = findStillLifeApply();

   private ClimateColumns() {
   }

   private static MethodHandle findStillLifeApply() {
      try {
         if (!Platform.get().isModLoaded("stilllife_ftf_bridge")) {
            return null;
         }
         Class<?> bridge = Names.forName("stilllife.ftfbridge.StillLifeClimateBridge");
         MethodType type = MethodType.methodType(Climate.TargetPoint.class, Climate.Sampler.class, Climate.TargetPoint.class,
            int.class, int.class, int.class);
         return MethodHandles.publicLookup().findStatic(bridge, "apply", type);
      } catch (Throwable absent) {
         return null;
      }
   }

   private static Climate.TargetPoint applyStillLifeBridge(Climate.Sampler sampler, Climate.TargetPoint target,
         int quartX, int quartY, int quartZ) {
      try {
         return (Climate.TargetPoint) STILL_LIFE_APPLY.invokeExact(sampler, target, quartX, quartY, quartZ);
      } catch (Throwable failure) {
         disabled = true;
         WorldgenAccelerator.LOG.warn("WGA climate columns: Still Life bridge apply failed ({}); off", failure.toString());
         return target;
      }
   }

   /**
    * This thread's chunk sampler and its 16 columns' values (0 temperature, 1
    * humidity, 2 continentalness, 3 erosion, 4 weirdness).
    */
   static final class State {
      State() {
      }
      Object sampler;
      int flags;
      final long[][] keys = new long[CHANNELS][16];
      final boolean[][] has = new boolean[CHANNELS][16];
      final double[][] values = new double[CHANNELS][16];
      Object depthFunction;
      Object depthWrapper;
      Object pendingDepthWrapper;
      boolean pendingDepthActive;
      int pendingDepthX;
      int pendingDepthZ;
      final long[] depthKeys = new long[16];
      final boolean[] depthHas = new boolean[16];
      final int[] depthSurfaceY = new int[16];
      final long[] sampleKeys = new long[16];
      final boolean[] sampleHas = new boolean[16];
      final long[] sampleQt = new long[16];
      final long[] sampleQh = new long[16];
      final long[] sampleQc = new long[16];
      final long[] sampleQe = new long[16];
      final long[] sampleQw = new long[16];
      final long[] bridgeKeys = new long[16];
      final boolean[] bridgeHas = new boolean[16];
      final long[] bridgeQt = new long[16];
      final long[] bridgeQh = new long[16];
      final long[] bridgeQc = new long[16];
      final long[] bridgeQe = new long[16];
      final long[] bridgeQw = new long[16];
      int calls;
      int sampleCalls;
      boolean inSampleCheck;
      boolean checkingSample;
      Climate.TargetPoint checkExpected;
      WorldgenPlans.SamplerDecoration lastDecoration;
      WorldgenPlans.SamplerDecoratorStage[] lastStages;
   }

   /**
    * Which height-free channels (bits 0..4) depend on x and z only in this router
    * (worked out once per router).
    */
   private static int flags(NoiseRouter router) {
      if (router == lastRouter) {
         return lastFlags;
      }

      int flags = (DensityCodegen.heightFree(router.temperature()) ? 1 : 0)
            | (DensityCodegen.heightFree(router.vegetation()) ? 2 : 0)
            | (DensityCodegen.heightFree(router.continents()) ? 4 : 0)
            | (DensityCodegen.heightFree(router.erosion()) ? 8 : 0)
            | (DensityCodegen.heightFree(router.ridges()) ? 16 : 0);
      WorldgenAccelerator.LOG.info(
            "WGA climate columns: temperature {}, humidity {}, continentalness {}, erosion {}, weirdness {} (a router of this world)",
            label(flags, 0), label(flags, 1), label(flags, 2), label(flags, 3), label(flags, 4));
      lastFlags = flags;
      lastRouter = router;
      return flags;
   }

   private static String label(int flags, int channel) {
      return (flags & 1 << channel) != 0 ? "per column" : "at every height";
   }

   /**
    * A noise chunk made a climate sampler from this router on this thread
    * (NoiseChunk.cachedClimateSampler).
    */
   public static void made(Object sampler, NoiseRouter router) {
      if (disabled || !Config.FAST_CLIMATE_COLUMNS.get()) {
         return;
      }

      State s = BiomeFill.state().climate;
      s.sampler = sampler;
      s.flags = flags(router);
      for (int i = 0; i < CHANNELS; i++) {
         java.util.Arrays.fill(s.has[i], false);
      }
      s.depthFunction = null;
      s.depthWrapper = null;
      s.pendingDepthWrapper = null;
      s.pendingDepthActive = false;
      java.util.Arrays.fill(s.depthHas, false);
      java.util.Arrays.fill(s.sampleHas, false);
      java.util.Arrays.fill(s.bridgeHas, false);
      s.inSampleCheck = false;
      s.checkingSample = false;
      s.checkExpected = null;
   }

   /**
    * Fast whole-TargetPoint column hit for {@code SamplerDecoration.sample},
    * skipping DensityFunction dispatch, ThreadLocal lookups, and per-cell
    * quantization when all 5 height-free climate channels and surface depth are
    * cached for this quart column. Every 64th hit returns {@code null} with a
    * self-check armed so {@link #finishSampleCheck} verifies against vanilla.
    */
   public static Climate.TargetPoint tryDecoratedSample(WorldgenPlans.SamplerDecoration decoration, Object sampler,
         int quartX, int quartY, int quartZ) {
      if (disabled) {
         return null;
      }

      State s = BiomeFill.state().climate;
      if (s.inSampleCheck || s.sampler != sampler || (s.flags & 31) != 31
            || !(s.depthFunction instanceof FtfDepthFunction df)) {
         return null;
      }

      int slot = (quartX & 3) << 2 | quartZ & 3;
      long bKey = (long) (quartX << 2) << 32 | (quartZ << 2) & 0xFFFFFFFFL;
      if (!s.depthHas[slot] || s.depthKeys[slot] != bKey) {
         return null;
      }

      if (!s.sampleHas[slot] || s.sampleKeys[slot] != bKey) {
         if (!s.has[0][slot] || s.keys[0][slot] != bKey
               || !s.has[1][slot] || s.keys[1][slot] != bKey
               || !s.has[2][slot] || s.keys[2][slot] != bKey
               || !s.has[3][slot] || s.keys[3][slot] != bKey
               || !s.has[4][slot] || s.keys[4][slot] != bKey) {
            return null;
         }

         s.sampleKeys[slot] = bKey;
         s.sampleQt[slot] = Climate.quantizeCoord((float) s.values[0][slot]);
         s.sampleQh[slot] = Climate.quantizeCoord((float) s.values[1][slot]);
         s.sampleQc[slot] = Climate.quantizeCoord((float) s.values[2][slot]);
         s.sampleQe[slot] = Climate.quantizeCoord((float) s.values[3][slot]);
         s.sampleQw[slot] = Climate.quantizeCoord((float) s.values[4][slot]);
         s.sampleHas[slot] = true;
      }

      long qd = Climate.quantizeCoord((float) depthFromSurface(quartY << 2, s.depthSurfaceY[slot],
            df.wgaccel$worldDepth(), df.wgaccel$worldHeight()));
      WorldgenPlans.SamplerDecoratorStage[] stages;
      if (s.lastDecoration == decoration) {
         stages = s.lastStages;
      } else {
         List<WorldgenPlans.SamplerDecoratorStage> stageList = decoration.stages();
         stages = stageList.isEmpty() ? EMPTY_STAGES : stageList.toArray(WorldgenPlans.SamplerDecoratorStage[]::new);
         s.lastDecoration = decoration;
         s.lastStages = stages;
      }

      Climate.TargetPoint result;
      if (STILL_LIFE_APPLY != null && stages.length == 0 && sampler instanceof Climate.Sampler cs) {
         if (qd <= 0L && s.bridgeHas[slot] && s.bridgeKeys[slot] == bKey) {
            // StillLifeClimateBridge is 2D for (T, H, C, E, W) and leaves depth <= 0 unchanged.
            result = new Climate.TargetPoint(s.bridgeQt[slot], s.bridgeQh[slot], s.bridgeQc[slot],
               s.bridgeQe[slot], qd, s.bridgeQw[slot]);
         } else {
            Climate.TargetPoint base = new Climate.TargetPoint(s.sampleQt[slot], s.sampleQh[slot], s.sampleQc[slot],
               s.sampleQe[slot], qd, s.sampleQw[slot]);
            result = applyStillLifeBridge(cs, base, quartX, quartY, quartZ);
            if (!s.bridgeHas[slot] || s.bridgeKeys[slot] != bKey) {
               s.bridgeKeys[slot] = bKey;
               s.bridgeQt[slot] = result.temperature();
               s.bridgeQh[slot] = result.humidity();
               s.bridgeQc[slot] = result.continentalness();
               s.bridgeQe[slot] = result.erosion();
               s.bridgeQw[slot] = result.weirdness();
               s.bridgeHas[slot] = true;
            }
         }
      } else {
         result = new Climate.TargetPoint(s.sampleQt[slot], s.sampleQh[slot], s.sampleQc[slot],
            s.sampleQe[slot], qd, s.sampleQw[slot]);
         for (int i = 0; i < stages.length; i++) {
            WorldgenPlans.SamplerDecoratorStage stage = stages[i];
            Climate.TargetPoint decorated = stage.decorator().apply(result, quartX, quartY, quartZ);
            if (decorated == null) {
               throw new IllegalStateException("Sampler decorator stage " + stage.id() + " returned null");
            }
            result = decorated;
         }
         if (STILL_LIFE_APPLY != null && sampler instanceof Climate.Sampler cs) {
            result = applyStillLifeBridge(cs, result, quartX, quartY, quartZ);
         }
      }

      if ((++s.sampleCalls & 63) == 0) {
         s.checkingSample = true;
         s.inSampleCheck = true;
         s.checkExpected = result;
         return null;
      }

      return result;
   }

   /**
    * Completes a {@code SamplerDecoration.sample} miss or armed 1-in-64 self-check
    * after vanilla ran. Always clears the re-entrancy guard when armed.
    */
   public static void finishSampleCheck(Climate.TargetPoint real, int quartX, int quartY, int quartZ) {
      State s = BiomeFill.state().climate;
      if (!s.checkingSample) {
         return;
      }

      s.checkingSample = false;
      s.inSampleCheck = false;
      Climate.TargetPoint expected = s.checkExpected;
      s.checkExpected = null;
      if (real == null || disabled) {
         return;
      }

      CHECKED.increment();
      if (expected == null || expected.temperature() != real.temperature()
            || expected.humidity() != real.humidity()
            || expected.continentalness() != real.continentalness()
            || expected.erosion() != real.erosion()
            || expected.depth() != real.depth()
            || expected.weirdness() != real.weirdness()) {
         DIFFERENT.increment();
         disabled = true;
         WorldgenAccelerator.LOG.warn(
               "WGA climate columns: sampled TargetPoint at quart ({}, {}, {}) differed from cached column; off",
               quartX, quartY, quartZ);
      } else if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   /**
    * FreeTerraForged depth density kept by column surfaceY when the chunk's own
    * sampler is asking.
    */
   public static double depth(Object sampler, DensityFunction function, DensityFunction.FunctionContext context) {
      if (disabled) {
         return function.compute(context);
      }

      State s = BiomeFill.state().climate;
      if (s.inSampleCheck || s.sampler != sampler) {
         return function.compute(context);
      }

      int x = context.blockX();
      int z = context.blockZ();
      int slot = (x >> 2 & 3) << 2 | z >> 2 & 3;
      long key = (long) x << 32 | z & 0xFFFFFFFFL;
      if (s.depthWrapper == function && s.depthFunction instanceof FtfDepthFunction df
            && s.depthHas[slot] && s.depthKeys[slot] == key) {
         double kept = depthFromSurface(context.blockY(), s.depthSurfaceY[slot], df.wgaccel$worldDepth(),
               df.wgaccel$worldHeight());
         if ((++s.calls & 63) == 0) {
            check(kept, function.compute(context), 5, x, context.blockY(), z);
         }

         return kept;
      }

      s.pendingDepthWrapper = function;
      s.pendingDepthX = x;
      s.pendingDepthZ = z;
      s.pendingDepthActive = true;
      try {
         return function.compute(context);
      } finally {
         s.pendingDepthActive = false;
         s.pendingDepthWrapper = null;
      }
   }

   /**
    * Records the column surfaceY just computed by FTF's ClimateDepthFunction
    * during a column cache miss.
    */
   public static void recordPendingDepth(Object function, int surfaceY) {
      State s = BiomeFill.state().climate;
      if (!s.pendingDepthActive || !(function instanceof FtfDepthFunction)) {
         return;
      }

      Object wrapper = s.pendingDepthWrapper;
      s.pendingDepthActive = false;
      s.pendingDepthWrapper = null;
      int x = s.pendingDepthX;
      int z = s.pendingDepthZ;
      if (s.depthFunction != function || s.depthWrapper != wrapper) {
         java.util.Arrays.fill(s.depthHas, false);
         java.util.Arrays.fill(s.sampleHas, false);
         s.depthFunction = function;
         s.depthWrapper = wrapper;
      }

      int slot = (x >> 2 & 3) << 2 | z >> 2 & 3;
      s.depthKeys[slot] = (long) x << 32 | z & 0xFFFFFFFFL;
      s.depthSurfaceY[slot] = surfaceY;
      s.depthHas[slot] = true;
   }

   private static double depthFromSurface(int blockY, int surfaceY, int worldDepth, int worldHeight) {
      if (blockY <= surfaceY) {
         int span = surfaceY + worldDepth;
         return span <= 0 ? 1.5d : Math.clamp((double) (surfaceY - blockY) / span, 0.0d, 1.0d) * 1.5d;
      }

      int span = worldHeight - surfaceY;
      return span <= 0 ? -1.5d : -Math.clamp((double) (blockY - surfaceY) / span, 0.0d, 1.0d) * 1.5d;
   }

   /**
    * The sampler's temperature (0) or humidity (1) at this point: kept for its
    * column, or worked out.
    */
   public static double value(Object sampler, int which, DensityFunction function,
         DensityFunction.FunctionContext context) {
      if (disabled) {
         return function.compute(context);
      }

      State s = BiomeFill.state().climate;
      if (s.inSampleCheck || s.sampler != sampler || (s.flags & 1 << which) == 0) {
         return function.compute(context);
      }

      int x = context.blockX();
      int z = context.blockZ();
      int slot = (x >> 2 & 3) << 2 | z >> 2 & 3;
      long key = (long) x << 32 | z & 0xFFFFFFFFL;
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
            WorldgenAccelerator.LOG.warn(
                  "WGA climate columns: {} at {}, {}, {} was {} kept for its column but {} worked out; off, every height works it out",
                  CHANNEL_NAMES[which], x, y, z, kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA climate columns: about %,d kept values handed out, checked %,d: %s",
            CHECKED.sum() * 64, CHECKED.sum(),
            DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
