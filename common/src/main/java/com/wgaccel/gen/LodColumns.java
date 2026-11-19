package com.wgaccel.gen;

import com.wgaccel.LevelLogger;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.FillGpu;
import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * LOD prototype (0.70.0): what a distant-terrain mod needs of a column (its ground, its water or ground top, the top
 * block) for chunks nobody generates, from the GPU noise fill's stand-ins (the look-ahead's: no structures, no
 * blending). A LOD level of stride s (a power of two) needs one column in s x s: up to 16 the GPU fills only those
 * columns of each chunk (FillGpu.Job.stride; a chunk's inputs and corners are still made whole), past 16 also only one
 * chunk in (s / 16) x (s / 16).
 *
 * <p>{@code /wgaccel lod bench [radius] [threads] [stride]} measures it around the caller. Checks: columns against the
 * game's own answer, {@code getBaseHeight} with OCEAN_FLOOR_WG and WORLD_SURFACE_WG (the noise and aquifer per column,
 * without structure terrain, as a stand-in has it), whose time per call is also the CPU baseline; and with a stride
 * above 1, every 8th launch filled again whole, every block of the strided columns compared.
 */
public final class LodColumns {
   /** A column without any block of that kind. */
   public static final int NONE = Integer.MIN_VALUE;
   /** Chunks per GPU launch (the look-ahead's batch). */
   private static final int BATCH = 64;
   /** Columns per chunk checked against getBaseHeight (both heightmap types). */
   private static final int CHECKS_PER_CHUNK = 4;
   /** With a stride above 1: every how many launches the chunks are filled again whole and compared. */
   private static final int WHOLE_CHECK_EVERY = 8;
   private static final AtomicBoolean RUNNING = new AtomicBoolean();

   private LodColumns() {
   }

   /**
    * A chunk's LOD columns at a stride (n = 16 / stride per side; index i * n + j for the column at lx = i * stride,
    * lz = j * stride): the top block that blocks motion (OCEAN_FLOOR_WG's: the ground under any water), the top block
    * that is not air (WORLD_SURFACE_WG's: water or ground), that block's state and the ground block's state.
    */
   public record Columns(ChunkPos pos, int stride, int[] ground, int[] surface, BlockState[] top, BlockState[] groundTop) {
   }

   /** One fill reduced to its columns. */
   static Columns reduce(ChunkPos pos, FillGpu.Job job, byte[] codes, BlockState[] table, boolean[] groundTest, boolean[] surfaceTest) {
      int stride = job.stride;
      int n = 16 / stride;
      int[] ground = new int[n * n];
      int[] surface = new int[n * n];
      BlockState[] top = new BlockState[n * n];
      BlockState[] groundTop = new BlockState[n * n];
      int minY = job.cellMinY * job.cellHeight;
      int height = job.cellCountY * job.cellHeight;
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            int column = i * n + j;
            ground[column] = NONE;
            surface[column] = NONE;
            for (int ly = height - 1; ly >= 0; ly--) {
               int c = codes[(ly * n + j) * n + i] & 15;
               if (surface[column] == NONE && surfaceTest[c]) {
                  surface[column] = minY + ly;
                  top[column] = table[c];
               }

               if (groundTest[c]) {
                  ground[column] = minY + ly;
                  groundTop[column] = table[c];
                  break;
               }
            }
         }
      }

      return new Columns(pos, stride, ground, surface, top, groundTop);
   }

   /** Starts a bench on its own threads; false when one is running or the GPU fill is not ready. Lines go to report. */
   public static boolean bench(ServerLevel level, int centerX, int centerZ, int radius, int threads, int stride, Consumer<String> report) {
      if (stride < 1 || Integer.bitCount(stride) != 1) {
         report.accept("WGA LOD bench: the stride must be a power of two");
         return false;
      }

      GpuNoiseFill.StandIns probe = GpuNoiseFill.StandIns.create(BATCH);
      if (probe == null || probe.level() != level) {
         report.accept("WGA LOD bench: the GPU noise fill is not ready in this dimension (overworld only; gpuNoiseFill on, and its compile done)");
         return false;
      }

      if (!RUNNING.compareAndSet(false, true)) {
         report.accept("WGA LOD bench: one is running already");
         return false;
      }

      Thread thread = new Thread(() -> {
         try {
            run(level, centerX, centerZ, radius, threads, stride, report);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA LOD bench failed", failure);
            report.accept("WGA LOD bench failed: " + failure);
         } finally {
            RUNNING.set(false);
         }
      }, "WGA LOD bench");
      thread.setDaemon(true);
      thread.start();
      return true;
   }

   private static void run(ServerLevel level, int centerX, int centerZ, int radius, int threads, int stride, Consumer<String> report)
         throws InterruptedException {
      int chunkStep = Math.max(1, stride / 16);
      int fillStride = Math.min(16, stride);
      ConcurrentLinkedQueue<List<ChunkPos>> batches = new ConcurrentLinkedQueue<>();
      List<ChunkPos> batch = new ArrayList<>(BATCH);
      int total = 0;
      // Aligned to the step, so a level's chunks are the same ones whoever asks.
      int fromX = Math.floorDiv(centerX - radius, chunkStep) * chunkStep;
      int fromZ = Math.floorDiv(centerZ - radius, chunkStep) * chunkStep;
      for (int x = fromX; x <= centerX + radius; x += chunkStep) {
         for (int z = fromZ; z <= centerZ + radius; z += chunkStep) {
            batch.add(Mc.chunk(Mc.key(x, z)));
            total++;
            if (batch.size() == BATCH) {
               batches.add(batch);
               batch = new ArrayList<>(BATCH);
            }
         }
      }

      if (!batch.isEmpty()) {
         batches.add(batch);
      }

      int side = 2 * radius + 1;
      report.accept(String.format(Locale.ROOT, "WGA LOD bench: stride %d, %d x %d chunks around chunk %d, %d (%,d filled, %d columns each), %d threads...",
         stride, side, side, centerX, centerZ, total, (16 / fillStride) * (16 / fillStride), threads));
      Stats stats = new Stats();
      RandomState random = level.getChunkSource().randomState();
      long start = System.nanoTime();
      List<Thread> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
         Thread worker = new Thread(() -> work(level, random, batches, fillStride, stats), "WGA LOD bench " + t);
         worker.setDaemon(true);
         workers.add(worker);
         worker.start();
      }

      for (Thread worker : workers) {
         worker.join();
      }

      long wall = System.nanoTime() - start;
      long chunks = Math.max(1, stats.chunks.sum());
      long calls = Math.max(1, stats.checks.sum());
      double wallSeconds = wall / 1.0e9;
      // The LOD path's time: wall time minus the checks' share (they ran on the same threads).
      double checkSeconds = (stats.checkNanos.sum() + stats.wholeNanos.sum()) / 1.0e9 / threads;
      double lodSeconds = Math.max(1e-9, wallSeconds - checkSeconds);
      double area = (double)stats.chunks.sum() * chunkStep * chunkStep;
      report.accept(String.format(Locale.ROOT,
         "WGA LOD bench: %,d chunks filled (%,d stand-ins failed) in %.1f s wall, %.1f s without the checks: %,.0f LOD columns/s, covering %,.0f chunks of area/s",
         stats.chunks.sum(), stats.failed.sum(), wallSeconds, lodSeconds, stats.columns.sum() / lodSeconds, area / lodSeconds));
      report.accept(String.format(Locale.ROOT, "WGA LOD bench: per filled chunk, on its thread: inputs (CPU) %.2f ms, GPU %.2f ms, columns %.3f ms",
         stats.inputNanos.sum() / 1.0e6 / chunks, stats.gpuNanos.sum() / 1.0e6 / chunks, stats.reduceNanos.sum() / 1.0e6 / chunks));
      double cpuPerColumn = stats.checkNanos.sum() / 1.0e6 / calls * 2;
      report.accept(String.format(Locale.ROOT, "WGA LOD bench: the game's getBaseHeight, both types: %.2f ms a column on one thread (%,.0f columns/s a thread)",
         cpuPerColumn, 1000.0 / Math.max(1e-9, cpuPerColumn)));
      long different = stats.different.sum();
      String whole = fillStride == 1 ? "" : String.format(Locale.ROOT, "; %,d chunks filled again whole, their strided columns' %,d blocks: %s",
         stats.wholeChunks.sum(), stats.wholeBlocks.sum(), stats.wholeDifferent.sum() == 0 ? "IDENTICAL"
            : String.format(Locale.ROOT, "%,d differ (first ones in the log)", stats.wholeDifferent.sum()));
      report.accept(String.format(Locale.ROOT, "WGA LOD bench: %,d columns checked against getBaseHeight (ground and surface): %s%s", stats.checks.sum() / 2,
         different == 0 ? "IDENTICAL" : String.format(Locale.ROOT, "%,d differ (first ones in the log)", different), whole));
   }

   private static final class Stats {
      final LongAdder chunks = new LongAdder();
      final LongAdder columns = new LongAdder();
      final LongAdder failed = new LongAdder();
      final LongAdder inputNanos = new LongAdder();
      final LongAdder gpuNanos = new LongAdder();
      final LongAdder reduceNanos = new LongAdder();
      final LongAdder checks = new LongAdder();
      final LongAdder checkNanos = new LongAdder();
      final LongAdder different = new LongAdder();
      final LongAdder wholeChunks = new LongAdder();
      final LongAdder wholeBlocks = new LongAdder();
      final LongAdder wholeDifferent = new LongAdder();
      final LongAdder wholeNanos = new LongAdder();
      final AtomicInteger launches = new AtomicInteger();
      final AtomicInteger logged = new AtomicInteger();
   }

   private static void work(ServerLevel level, RandomState random, ConcurrentLinkedQueue<List<ChunkPos>> batches, int stride, Stats stats) {
      GpuNoiseFill.StandIns standIns = GpuNoiseFill.StandIns.create(BATCH);
      GpuNoiseFill.StandIns wholeIns = stride == 1 ? null : GpuNoiseFill.StandIns.create(BATCH);
      if (standIns == null) {
         return;
      }

      BlockState[] table = standIns.table();
      boolean[] groundTest = new boolean[16];
      boolean[] surfaceTest = new boolean[16];
      for (int i = 0; i < table.length; i++) {
         groundTest[i] = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(table[i]);
         surfaceTest[i] = Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(table[i]);
      }

      List<ChunkPos> done = new ArrayList<>(BATCH);
      List<FillGpu.Job> jobs = new ArrayList<>(BATCH);
      List<byte[]> codes = new ArrayList<>(BATCH);
      for (List<ChunkPos> batch; (batch = batches.poll()) != null; ) {
         done.clear();
         jobs.clear();
         codes.clear();
         long input = standIns.inputNanos;
         long gpu = standIns.gpuNanos;
         int failed = standIns.standInsFailed;
         standIns.fill(batch, stride, done, jobs, codes);
         stats.inputNanos.add(standIns.inputNanos - input);
         stats.gpuNanos.add(standIns.gpuNanos - gpu);
         stats.failed.add(standIns.standInsFailed - failed);
         for (int i = 0; i < done.size(); i++) {
            long start = System.nanoTime();
            Columns columns = reduce(done.get(i), jobs.get(i), codes.get(i), table, groundTest, surfaceTest);
            stats.reduceNanos.add(System.nanoTime() - start);
            stats.chunks.increment();
            stats.columns.add(columns.ground().length);
            check(level, random, columns, jobs.get(i).cellMinY * jobs.get(i).cellHeight, stats);
         }

         if (wholeIns != null && stats.launches.getAndIncrement() % WHOLE_CHECK_EVERY == 0) {
            long start = System.nanoTime();
            checkWhole(wholeIns, done, jobs, codes, stats);
            stats.wholeNanos.add(System.nanoTime() - start);
         }
      }
   }

   /** The launch's chunks filled again whole: every block of their strided columns must be the same code. */
   private static void checkWhole(GpuNoiseFill.StandIns wholeIns, List<ChunkPos> strided, List<FillGpu.Job> stridedJobs, List<byte[]> stridedCodes,
         Stats stats) {
      List<ChunkPos> done = new ArrayList<>(BATCH);
      List<FillGpu.Job> jobs = new ArrayList<>(BATCH);
      List<byte[]> codes = new ArrayList<>(BATCH);
      wholeIns.fill(strided, 1, done, jobs, codes);
      int at = 0;
      for (int i = 0; i < done.size(); i++) {
         while (at < strided.size() && !strided.get(at).equals(done.get(i))) {
            at++;
         }

         if (at == strided.size()) {
            break;
         }

         FillGpu.Job s = stridedJobs.get(at);
         FillGpu.Job w = jobs.get(i);
         byte[] sc = stridedCodes.get(at);
         byte[] wc = codes.get(i);
         stats.wholeChunks.increment();
         if (s.cellMinY != w.cellMinY || s.cellCountY != w.cellCountY || s.cellHeight != w.cellHeight) {
            stats.wholeDifferent.increment();
            log(stats, "WGA LOD bench: chunk " + done.get(i) + " filled whole has another cell range (" + w.cellMinY + " + " + w.cellCountY + " vs " + s.cellMinY
               + " + " + s.cellCountY + ")");
            continue;
         }

         int n = 16 / s.stride;
         int height = s.cellCountY * s.cellHeight;
         for (int ci = 0; ci < n; ci++) {
            for (int cj = 0; cj < n; cj++) {
               int lx = ci * s.stride;
               int lz = cj * s.stride;
               for (int ly = 0; ly < height; ly++) {
                  stats.wholeBlocks.increment();
                  byte a = sc[(ly * n + cj) * n + ci];
                  byte b = wc[(ly * 16 + lz) * 16 + lx];
                  if (a != b) {
                     stats.wholeDifferent.increment();
                     log(stats, String.format(Locale.ROOT, "WGA LOD bench: block %d, %d, %d differs from the whole fill: code %d (whole %d)",
                        done.get(i).getMinBlockX() + lx, s.cellMinY * s.cellHeight + ly, done.get(i).getMinBlockZ() + lz, a, b));
                  }
               }
            }
         }
      }
   }

   private static void log(Stats stats, String line) {
      if (stats.logged.incrementAndGet() <= 16) {
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, line);
      }
   }

   /** A few columns of the chunk against the game's getBaseHeight (the top block's y + 1; the bottom when none). */
   private static void check(ServerLevel level, RandomState random, Columns columns, int bottom, Stats stats) {
      var generator = level.getChunkSource().getGenerator();
      int n = 16 / columns.stride();
      int chunkX = columns.pos().getMinBlockX();
      int chunkZ = columns.pos().getMinBlockZ();
      long seed = Mc.key(columns.pos()) * 0x9E3779B97F4A7C15L;
      for (int k = 0; k < Math.min(CHECKS_PER_CHUNK, n * n); k++) {
         seed = seed * 6364136223846793005L + 1442695040888963407L;
         int i = (int)((seed >>> 40) % n);
         int j = (int)((seed >>> 52) % n);
         int column = i * n + j;
         int x = chunkX + i * columns.stride();
         int z = chunkZ + j * columns.stride();
         long start = System.nanoTime();
         int gameGround = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
         int gameSurface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
         stats.checkNanos.add(System.nanoTime() - start);
         stats.checks.add(2);
         int ground = columns.ground()[column] == NONE ? bottom : columns.ground()[column] + 1;
         int surface = columns.surface()[column] == NONE ? bottom : columns.surface()[column] + 1;
         if (ground != gameGround || surface != gameSurface) {
            stats.different.increment();
            log(stats, String.format(Locale.ROOT, "WGA LOD bench: column %d, %d differs: ground %d (game %d), surface %d (game %d), top block %s", x, z, ground,
               gameGround, surface, gameSurface, columns.top()[column]));
         }
      }
   }
}
