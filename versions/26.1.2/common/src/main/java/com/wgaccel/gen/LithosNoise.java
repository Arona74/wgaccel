package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.GpuService;
import com.wgaccel.gpu.LithosNoiseGpu;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Lithos's rock noise from the GPU. Lithos 1.0.0 (26.1.2, obfuscated) samples one NormalNoise (its class worldgen.t,
 * from the seed) at block positions with fixed scales through five functions, about 4 samples per stone block from
 * each column's ocean floor down 96 blocks (strata) and more near the surface: 26% of Lithos's time (28 Oct 2026
 * profile). Those positions are known when a chunk's features step starts: its strata band hangs from OCEAN_FLOOR_WG,
 * which no longer changes then. So as each features region is made, its chunk's samples (ROWS heights per column from
 * 4 above the floor's top block, {@link LithosNoiseGpu}) are queued for the GPU thread; the first of Lithos's calls in
 * that region waits for them, the rest read the table (positions outside it, or a chunk whose table failed, go Lithos's
 * way). Every 64th answer from the table is compared with Lithos's own function; a difference turns this off.
 */
public final class LithosNoise {
   /** Heights per column: 4 above the floor's top block, the top block, then strata's 96 below it. */
   static final int ROWS = 101;
   private static final int ABOVE = 4;
   private static final String CLASS = "com.conquest.lithos.worldgen.t";
   private static final String[] FUNCTIONS = {"a", "b", "c", "d", "e"};

   private static volatile boolean disabled;
   private static volatile boolean started;
   /** Lithos's own functions (WorldGenLevel, int, int, int), for the compared answers; null until found. */
   private static volatile MethodHandle[] originals;
   private static volatile MethodHandle noiseForSeed;
   private static final LinkedBlockingQueue<Table> QUEUE = new LinkedBlockingQueue<>();
   private static final LongAdder TABLES = new LongAdder();
   private static final LongAdder WAIT_NANOS = new LongAdder();
   private static final LongAdder WAITED = new LongAdder();
   private static final LongAdder GPU_NANOS = new LongAdder();
   private static final LongAdder HITS = new LongAdder();
   private static final LongAdder MISSES = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private LithosNoise() {
   }

   /** One features region's chunk: its samples once the GPU has them. Used by the region's own thread only (besides the GPU thread's one write). */
   public static final class Table {
      final long seed;
      final int minX;
      final int minZ;
      final int[] tops = new int[256];
      final CountDownLatch done = new CountDownLatch(1);
      volatile double[] made;
      /** The region thread's copy once it has waited (null before, or when the GPU failed). */
      double[] values;
      boolean waited;
      /** While Lithos's own function runs for a compared answer. */
      boolean bypass;
      long hits;

      Table(long seed, int minX, int minZ) {
         this.seed = seed;
         this.minX = minX;
         this.minZ = minZ;
      }
   }

   /** A features region was made (Mc.featuresStart): its chunk's table is queued for the GPU when Lithos is there. */
   public static void featuresStart(WorldGenRegion region) {
      if (disabled || !Config.GPU_LITHOS_NOISE.get() || !lithosPresent()) {
         return;
      }

      ChunkPos pos = region.getCenter();
      Table table = new Table(region.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
      for (int i = 0; i < 256; i++) {
         // strata: top = OCEAN_FLOOR_WG height - 1, its blocks from top - 1 down to top - 96.
         table.tops[i] = region.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, table.minX + (i & 15), table.minZ + (i >> 4)) - 1 + ABOVE;
      }

      ((RegionExtras)region).wgaccel$extra(table);
      start();
      QUEUE.add(table);
   }

   private static boolean present = true;
   private static boolean looked;

   /** Whether Lithos's noise class is there (looked up once); finds its functions. */
   private static synchronized boolean lithosPresent() {
      if (!looked) {
         looked = true;
         try {
            Class<?> type = Class.forName(CLASS, false, LithosNoise.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle[] found = new MethodHandle[FUNCTIONS.length];
            for (int f = 0; f < FUNCTIONS.length; f++) {
               Class<?> result = f == 2 || f == 4 ? boolean.class : double.class;
               Method method = type.getDeclaredMethod(FUNCTIONS[f], WorldGenLevel.class, int.class, int.class, int.class);
               if (method.getReturnType() != result || !Modifier.isStatic(method.getModifiers())) {
                  throw new NoSuchMethodException(FUNCTIONS[f] + " has another form");
               }

               method.setAccessible(true);
               found[f] = lookup.unreflect(method).asType(MethodType.methodType(result, WorldGenLevel.class, int.class, int.class, int.class));
            }

            Method seeded = type.getDeclaredMethod("a", long.class);
            if (seeded.getReturnType() != NormalNoise.class) {
               throw new NoSuchMethodException("a(long) is not its noise");
            }

            seeded.setAccessible(true);
            noiseForSeed = lookup.unreflect(seeded);
            originals = found;
         } catch (ClassNotFoundException missing) {
            present = false;
         } catch (Throwable other) {
            present = false;
            WorldgenAccelerator.LOG.warn("WGA Lithos noise: Lithos's noise class is not as expected ({}); Lithos's noise stays on the CPU", other.toString());
         }
      }

      return present;
   }

   private static synchronized void start() {
      if (started) {
         return;
      }

      started = true;
      Thread thread = new Thread(LithosNoise::serve, "WGA GPU Lithos noise");
      thread.setDaemon(true);
      thread.start();
   }

   /** The GPU thread: one table after another (a new seed, a new noise). */
   private static void serve() {
      LithosNoiseGpu gpu = null;
      long seed = 0;
      while (true) {
         Table table;
         try {
            table = QUEUE.take();
         } catch (InterruptedException interrupted) {
            return;
         }

         try {
            if (disabled) {
               throw new IllegalStateException("off");
            }

            if (gpu == null || seed != table.seed) {
               if (gpu != null) {
                  gpu.close();
                  gpu = null;
               }

               NormalNoise noise = (NormalNoise)noiseForSeed.invoke(table.seed);
               long compile = System.nanoTime();
               gpu = new LithosNoiseGpu(GpuService.context(), noise, ROWS);
               seed = table.seed;
               WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA Lithos noise: on the GPU (%d heights per column, %d samples each; ready in %.0f ms)", ROWS,
                  LithosNoiseGpu.SAMPLES, (System.nanoTime() - compile) / 1.0e6));
            }

            long begin = System.nanoTime();
            double[] values = new double[256 * ROWS * LithosNoiseGpu.SAMPLES];
            gpu.run(table.minX, table.minZ, table.tops, values);
            GPU_NANOS.add(System.nanoTime() - begin);
            TABLES.increment();
            table.made = values;
         } catch (Throwable failure) {
            if (!disabled) {
               disabled = true;
               WorldgenAccelerator.LOG.warn("WGA Lithos noise: the GPU failed ({}); Lithos's noise stays on the CPU", failure.toString());
            }
         } finally {
            table.done.countDown();
         }
      }
   }

   /** The region's table when Lithos asks from a features region (waiting the first time), null otherwise. */
   public static Table table(WorldGenLevel level) {
      if (!(level instanceof RegionExtras extras) || !(extras.wgaccel$extra() instanceof Table table) || table.bypass) {
         return null;
      }

      if (!table.waited) {
         table.waited = true;
         long begin = System.nanoTime();
         try {
            table.done.await();
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
         }

         WAIT_NANOS.add(System.nanoTime() - begin);
         WAITED.increment();
         table.values = table.made;
      }

      return disabled ? null : table.values != null ? table : null;
   }

   /** The first sample's slot for (x, y, z) in the table, -1 outside it. */
   public static int slot(Table table, int x, int y, int z) {
      int dx = x - table.minX;
      int dz = z - table.minZ;
      int column = dz << 4 | dx;
      int k = (dx | dz) >>> 4 == 0 ? table.tops[column] - y : -1;
      if (k < 0 || k >= ROWS) {
         // Rare (0 in 1,024 per chunk in the 28 Oct 2026 test run): counted one by one.
         MISSES.increment();
         return -1;
      }

      table.hits++;
      if ((table.hits & 1023) == 0) {
         HITS.add(1024);
      }

      return (column * ROWS + k) * LithosNoiseGpu.SAMPLES;
   }

   public static double sample(Table table, int slot, int sample) {
      return table.values[slot + sample];
   }

   /** Whether this answer is one of the compared ones (about 1 in 64). */
   public static boolean compared() {
      return ThreadLocalRandom.current().nextInt(64) == 0;
   }

   /** Lithos's own answer of function f (0 to 4: a to e) at (x, y, z), the table bypassed while it runs. */
   private static Object own(Table table, int f, WorldGenLevel level, int x, int y, int z) throws Throwable {
      table.bypass = true;
      try {
         MethodHandle handle = originals[f];
         return f == 2 || f == 4 ? (Object)(boolean)handle.invokeExact(level, x, y, z) : (Object)(double)handle.invokeExact(level, x, y, z);
      } finally {
         table.bypass = false;
      }
   }

   /** A compared double answer: Lithos's is used; a different one turns this off. */
   public static double verify(Table table, int f, double kept, WorldGenLevel level, int x, int y, int z) {
      try {
         double real = (Double)own(table, f, level, x, y, z);
         check(Double.doubleToRawLongBits(real) == Double.doubleToRawLongBits(kept), f, kept, real, x, y, z);
         return real;
      } catch (Throwable failure) {
         off(failure);
         return kept;
      }
   }

   /** A compared boolean answer, as {@link #verify(Table, int, double, WorldGenLevel, int, int, int)}. */
   public static boolean verify(Table table, int f, boolean kept, WorldGenLevel level, int x, int y, int z) {
      try {
         boolean real = (Boolean)own(table, f, level, x, y, z);
         check(real == kept, f, kept, real, x, y, z);
         return real;
      } catch (Throwable failure) {
         off(failure);
         return kept;
      }
   }

   private static void check(boolean same, int f, Object kept, Object real, int x, int y, int z) {
      CHECKED.increment();
      if (!same) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA Lithos noise: {}({}, {}, {}) was {} from the GPU but {} from Lithos; off, Lithos's noise stays on the CPU", FUNCTIONS[f], x, y, z,
               kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   private static void off(Throwable failure) {
      if (!disabled) {
         disabled = true;
         WorldgenAccelerator.LOG.warn("WGA Lithos noise: Lithos's own function failed for a check ({}); off", failure.toString());
      }
   }

   public static String summary() {
      long tables = Math.max(1, TABLES.sum());
      long waited = Math.max(1, WAITED.sum());
      long hits = HITS.sum();
      long misses = MISSES.sum();
      return String.format(Locale.ROOT,
         "WGA Lithos noise: %,d chunk tables (GPU %.2f ms each, Lithos waited %.2f ms per chunk), about %,d answers from the tables, %,d outside them (%.1f%%), checked %,d: %s",
         TABLES.sum(), GPU_NANOS.sum() / 1.0e6 / tables, WAIT_NANOS.sum() / 1.0e6 / waited, hits, misses, 100.0 * misses / Math.max(1, hits + misses), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
