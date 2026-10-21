package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.SurfaceGpu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * GPU step G6 (config gpuSurfaceAhead, with gpuSurface ON): a chunk's surface worked out on the GPU before its surface
 * step. Each overworld chunk's final biomes are recorded at the end of its biome step ({@link #biomesDone}). When a
 * chunk's noise fill is written from the GPU ({@link #filled}), its surface inputs are made from what is known then -
 * the blocks and the world-surface heights from the fill's codes, the preliminary surface from its noise chunk, the
 * quart biomes of the chunk and its eight neighbours from the record - and, if every neighbour's biomes are known, the
 * job goes to the surface-ahead threads, which run it on the GPU. At the surface step {@link #take} gives the result
 * (waiting for it if it is still on the card); any chunk without one goes the usual way. Every 64th chunk's inputs are
 * also made the usual way and compared (GpuSurface).
 */
public final class SurfaceAhead {
   private static final int THREADS = 3;
   private static final int BIOME_BUDGET = 8192;
   /** How long a surface step waits for a result still on the card before going the usual way. */
   private static final long WAIT_NANOS = 20_000_000L;

   /** A chunk's surface made ahead: its job (the inputs as GpuSurface's would be) and the GPU's blocks, or done false (special: the CPU's). */
   static final class Result {
      final SurfaceGpu.Job job;
      final short[] out;
      volatile boolean ready;
      volatile boolean done;

      Result(SurfaceGpu.Job job, short[] out) {
         this.job = job;
         this.out = out;
      }
   }

   private record Biomes(int quartY, int quartHeight, int[] ids) {
   }

   /** A chunk's fill as the GPU wrote it (its codes and their states), kept for its surface step's inputs. */
   record Codes(byte[] codes, BlockState[] table, int fillMinY, int fillHeight, String origin) {
   }

   private static final int CODES_BUDGET = 1024;
   private static final ConcurrentHashMap<Long, Codes> CODES = new ConcurrentHashMap<>();
   private static final ConcurrentLinkedQueue<Long> CODES_ORDER = new ConcurrentLinkedQueue<>();
   static final LongAdder FROM_CODES = new LongAdder();

   /** The chunk's fill codes (removed), or null. */
   static Codes takeCodes(ChunkAccess chunk) {
      return CODES.remove(chunk.getPos().toLong());
   }

   /** The block kinds of the surface's job (air 0, fluid 1, the default block 2, other 3) from the fill's codes. */
   static void kinds(Codes codes, byte[] kinds, BlockState defaultBlock, int minY, int height) {
      kindsOnly(codes, kinds, defaultBlock, minY, height);
      FROM_CODES.increment();
   }

   /** As {@link #kinds}, not counted (the codes check). */
   static void kindsOnly(Codes codes, byte[] kinds, BlockState defaultBlock, int minY, int height) {
      byte[] kindOf = new byte[16];
      for (int c = 0; c < codes.table.length && c < 16; c++) {
         BlockState state = codes.table[c];
         kindOf[c] = (byte)(state == null || state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : state == defaultBlock ? 2 : 3);
      }

      for (int y = minY; y < minY + height; y++) {
         int ly = y - codes.fillMinY;
         int at = (y - minY) * 256;
         if (ly < 0 || ly >= codes.fillHeight) {
            java.util.Arrays.fill(kinds, at, at + 256, (byte)0);
            continue;
         }

         for (int t = 0; t < 256; t++) {
            kinds[at + t] = kindOf[codes.codes[(ly * 16 + (t % 16)) * 16 + t / 16] & 15];
         }
      }
   }

   private static final ConcurrentHashMap<Long, Biomes> BIOMES = new ConcurrentHashMap<>();
   private static final ConcurrentLinkedQueue<Long> BIOME_ORDER = new ConcurrentLinkedQueue<>();
   private static final ConcurrentHashMap<Long, Result> RESULTS = new ConcurrentHashMap<>();
   /** The results to make, themselves (a surface step may already have taken one from RESULTS and be waiting on it). */
   private static final LinkedBlockingQueue<Result> QUEUE = new LinkedBlockingQueue<>();
   private static final List<Thread> THREADS_RUNNING = new ArrayList<>();
   private static final AtomicInteger FAILURES = new AtomicInteger();
   private static volatile boolean enabled;
   static final LongAdder MADE = new LongAdder();
   static final LongAdder BATCHES = new LongAdder();
   static final LongAdder NO_NEIGHBOURS = new LongAdder();
   static final LongAdder USED = new LongAdder();
   static final LongAdder WAITED = new LongAdder();
   static final LongAdder WAIT_NANOS_SUM = new LongAdder();
   static final LongAdder MISSING = new LongAdder();
   static final LongAdder INPUT_NANOS = new LongAdder();
   static final LongAdder GPU_NANOS = new LongAdder();

   private SurfaceAhead() {
   }

   /** At the overworld's load. */
   public static synchronized void start() {
      // The last world's fill codes too: the next world has chunks at the same places (the benchmark's Chunky area), and
      // one filled on the CPU would otherwise find the old world's codes as its surface inputs.
      CODES.clear();
      CODES_ORDER.clear();
      BIOMES.clear();
      BIOME_ORDER.clear();
      RESULTS.clear();
      QUEUE.clear();
      enabled = Config.GPU_SURFACE_AHEAD.get() && Config.GPU_SURFACE.get() == Config.GpuFilterMode.ON;
      if (enabled && THREADS_RUNNING.isEmpty()) {
         for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(SurfaceAhead::run, "WGA GPU surface ahead " + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            THREADS_RUNNING.add(thread);
            thread.start();
         }
      }
   }

   static boolean enabled() {
      return enabled;
   }

   /** At the end of an overworld chunk's biome step: its final quart biomes (as the region gives them to its neighbours' surfaces). */
   public static void biomesDone(ChunkAccess chunk, net.minecraft.world.level.levelgen.RandomState random) {
      GpuSurface.Program p = GpuSurface.program();
      if (!enabled || p == null || random != p.random()) {
         return;
      }

      try {
         record(chunk, p);
      } catch (Throwable failure) {
         // Never break the biome step: the chunk's neighbours then just get their surfaces the usual way.
         if (FAILURES.incrementAndGet() <= 4) {
            WorldgenAccelerator.LOG.warn("WGA GPU surface ahead: recording chunk {}'s biomes failed: {}", chunk.getPos(), failure.toString());
         }
      }
   }

   private static void record(ChunkAccess chunk, GpuSurface.Program p) {

      int quartY = chunk.getMinBuildHeight() >> 2;
      int quartHeight = chunk.getHeight() >> 2;
      int quartX = chunk.getPos().getMinBlockX() >> 2;
      int quartZ = chunk.getPos().getMinBlockZ() >> 2;
      int[] ids = new int[16 * quartHeight];
      Holder<Biome> last = null;
      int lastId = 0;
      for (int qx = 0; qx < 4; qx++) {
         for (int qz = 0; qz < 4; qz++) {
            for (int qy = 0; qy < quartHeight; qy++) {
               // Straight from the sections, as ChunkAccess.getNoiseBiome reads them (the chunk is not marked as having
               // biomes until its biome step has returned, so getNoiseBiome itself would throw here).
               int y = quartY + qy;
               Holder<Biome> biome = chunk.getSection(chunk.getSectionIndex(y << 2)).getNoiseBiome(qx, y & 3, qz);
               if (biome != last) {
                  last = biome;
                  lastId = p.biomes().getId(biome.value());
               }

               ids[(qx * 4 + qz) * quartHeight + qy] = lastId;
            }
         }
      }

      long key = chunk.getPos().toLong();
      if (BIOMES.put(key, new Biomes(quartY, quartHeight, ids)) == null) {
         BIOME_ORDER.add(key);
         while (BIOMES.size() > BIOME_BUDGET) {
            Long old = BIOME_ORDER.poll();
            if (old == null) {
               break;
            }

            BIOMES.remove(old);
         }
      }
   }

   /**
    * When a chunk's noise fill was written from the GPU's codes (codes[(ly * 16 + lz) * 16 + lx] & 15 into table, ly from
    * minCellY * cellHeight): its surface job, sent to the GPU when its neighbours' biomes are known.
    */
   public static void filled(ChunkAccess chunk, NoiseChunk noise, byte[] codes, BlockState[] fillTable, int minCellY, int cellCountY, int cellHeight, String origin) {
      GpuSurface.Program p = GpuSurface.program();
      if (p == null) {
         return;
      }

      // Kept for the surface step's inputs (the usual way too): the codes say every block's kind without reading it back.
      long codesKey = chunk.getPos().toLong();
      if (CODES.put(codesKey, new Codes(codes, fillTable, minCellY * cellHeight, cellCountY * cellHeight, origin)) == null) {
         CODES_ORDER.add(codesKey);
         while (CODES.size() > CODES_BUDGET) {
            Long old = CODES_ORDER.poll();
            if (old == null) {
               break;
            }

            CODES.remove(old);
         }
      }

      if (!enabled || noise == null) {
         return;
      }

      long start = System.nanoTime();
      int minY = chunk.getMinBuildHeight();
      int fullHeight = chunk.getHeight();
      int minX = chunk.getPos().getMinBlockX();
      int minZ = chunk.getPos().getMinBlockZ();
      int fillMinY = minCellY * cellHeight;
      int fillHeight = cellCountY * cellHeight;
      byte[] kindOf = new byte[16];
      for (int c = 0; c < fillTable.length && c < 16; c++) {
         BlockState state = fillTable[c];
         kindOf[c] = (byte)(state == null || state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : state == p.defaultBlock() ? 2 : 3);
      }

      SurfaceGpu.Job job = new SurfaceGpu.Job();
      int maxTop = minY;
      for (int t = 0; t < 256; t++) {
         int lx = t / 16;
         int lz = t % 16;
         int height = minY - 1;
         for (int ly = fillHeight - 1; ly >= 0; ly--) {
            int y = fillMinY + ly;
            if (y < minY || y >= minY + fullHeight) {
               continue;
            }

            if (kindOf[codes[(ly * 16 + lz) * 16 + lx] & 15] != 0) {
               height = y;
               break;
            }
         }

         job.heights[t] = height;
         job.tops[t] = height + 1;
         maxTop = Math.max(maxTop, height + 1);
      }

      int height = Math.max(1, Math.min(fullHeight, maxTop - minY + 1));
      job.minY = minY;
      job.height = height;
      job.minX = minX;
      job.minZ = minZ;
      int n = 256 * height;
      job.kinds = new byte[n];
      for (int y = minY; y < minY + height; y++) {
         int ly = y - fillMinY;
         int at = (y - minY) * 256;
         if (ly < 0 || ly >= fillHeight) {
            continue;
         }

         for (int t = 0; t < 256; t++) {
            job.kinds[at + t] = kindOf[codes[(ly * 16 + (t % 16)) * 16 + t / 16] & 15];
         }
      }

      int cellX = minX >> 4;
      int cellZ = minZ >> 4;
      double c0 = noise.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
      double c1 = noise.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
      double c2 = noise.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
      double c3 = noise.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
      for (int t = 0; t < 256; t++) {
         int x = minX + t / 16;
         int z = minZ + t % 16;
         job.minBase[t] = Mth.floor(Mth.lerp2((double)((float)(x & 15) / 16.0F), (double)((float)(z & 15) / 16.0F), c0, c1, c2, c3));
      }

      int gx = (minX >> 2) - 1;
      int gz = (minZ >> 2) - 1;
      int gy = minY >> 2;
      int fullQuarts = ((chunk.getMaxBuildHeight() - 1) >> 2) - gy + 1;
      int sy = Math.max(1, Math.min(fullQuarts, (((minY + height - 1 - 2) >> 2) + 1) - gy + 1));
      job.grid[0] = gx;
      job.grid[1] = gy;
      job.grid[2] = gz;
      job.grid[3] = 6;
      job.grid[4] = sy;
      job.grid[5] = 6;
      int quarts = 36 * sy;
      job.quarts = new int[quarts];
      job.quartCount = quarts;
      for (int qx = 0; qx < 6; qx++) {
         for (int qz = 0; qz < 6; qz++) {
            int x = gx + qx;
            int z = gz + qz;
            Biomes biomes = BIOMES.get(ChunkPos.asLong(x >> 2, z >> 2));
            if (biomes == null) {
               NO_NEIGHBOURS.increment();
               return;
            }

            int column = ((x & 3) * 4 + (z & 3)) * biomes.quartHeight;
            for (int qy = 0; qy < sy; qy++) {
               // ChunkAccess.getNoiseBiome clamps y into the chunk.
               int y = Mth.clamp(gy + qy, biomes.quartY, biomes.quartY + biomes.quartHeight - 1) - biomes.quartY;
               job.quarts[(qx * 6 + qz) * sy + qy] = biomes.ids[column + y];
            }
         }
      }

      job.zoomSeed = p.zoomSeed();
      INPUT_NANOS.add(System.nanoTime() - start);
      long key = chunk.getPos().toLong();
      Result result = new Result(job, new short[n]);
      RESULTS.put(key, result);
      QUEUE.add(result);
   }

   /** At the surface step: the chunk's result, waited for if it is still being made, or null (the usual way). */
   static Result take(ChunkAccess chunk) {
      Result result = RESULTS.remove(chunk.getPos().toLong());
      if (result == null) {
         MISSING.increment();
         return null;
      }

      if (!result.ready) {
         long start = System.nanoTime();
         WAITED.increment();
         synchronized (result) {
            while (!result.ready && System.nanoTime() - start < WAIT_NANOS) {
               try {
                  result.wait(5);
               } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }
         }

         WAIT_NANOS_SUM.add(System.nanoTime() - start);
         if (!result.ready) {
            MISSING.increment();
            return null;
         }
      }

      USED.increment();
      return result;
   }

   /** At most this many chunks in one launch. */
   private static final int BATCH = 32;

   private static void run() {
      // The highest GPU priority (not the look-ahead's lowest): a chunk's surface step comes soon after its fill, and
      // a worker waits for a result still on the card.
      List<Result> keys = new ArrayList<>();
      List<Result> results = new ArrayList<>();
      List<SurfaceGpu.Job> jobs = new ArrayList<>();
      List<short[]> outs = new ArrayList<>();
      while (true) {
         keys.clear();
         try {
            keys.add(QUEUE.take());
            QUEUE.drainTo(keys, BATCH - 1);
            if (keys.size() < BATCH) {
               // A moment for more chunks to gather into the launch.
               Result more = QUEUE.poll(2, java.util.concurrent.TimeUnit.MILLISECONDS);
               if (more != null) {
                  keys.add(more);
                  QUEUE.drainTo(keys, BATCH - keys.size());
               }
            }
         } catch (InterruptedException interrupted) {
            return;
         }

         GpuSurface.Program p = GpuSurface.program();
         results.clear();
         jobs.clear();
         outs.clear();
         for (Result result : keys) {
            if (!result.ready) {
               results.add(result);
               jobs.add(result.job);
               outs.add(result.out);
            }
         }

         if (results.isEmpty()) {
            continue;
         }

         long start = System.nanoTime();
         boolean[] done = null;
         if (p != null) {
            try {
               done = p.gpu().runBatch(jobs, outs);
               MADE.add(results.size());
            } catch (Throwable failure) {
               if (FAILURES.incrementAndGet() <= 4) {
                  WorldgenAccelerator.LOG.warn("WGA GPU surface ahead: a batch of {} failed: {}", results.size(), failure.toString());
               }
            }
         }

         GPU_NANOS.add(System.nanoTime() - start);
         BATCHES.increment();
         for (int i = 0; i < results.size(); i++) {
            Result result = results.get(i);
            result.done = done != null && done[i];
            synchronized (result) {
               result.ready = true;
               result.notifyAll();
            }
         }
      }
   }

   /** A checked chunk: the inputs made ahead against the usual ones; null when the same, else the first difference. */
   static String compare(SurfaceGpu.Job ahead, SurfaceGpu.Job real) {
      if (ahead.minY != real.minY || ahead.height != real.height || ahead.minX != real.minX || ahead.minZ != real.minZ || ahead.zoomSeed != real.zoomSeed) {
         return String.format(Locale.ROOT, "range: ahead y %d + %d, real y %d + %d", ahead.minY, ahead.height, real.minY, real.height);
      }

      for (int t = 0; t < 256; t++) {
         if (ahead.heights[t] != real.heights[t] || ahead.tops[t] != real.tops[t] || ahead.minBase[t] != real.minBase[t]) {
            return String.format(Locale.ROOT, "column %d: ahead height %d base %d, real height %d base %d", t, ahead.heights[t], ahead.minBase[t], real.heights[t],
               real.minBase[t]);
         }
      }

      for (int i = 0; i < 256 * real.height; i++) {
         if (ahead.kinds[i] != real.kinds[i]) {
            return String.format(Locale.ROOT, "block %d (y %d, column %d): ahead kind %d, real %d", i, real.minY + i / 256, i % 256, ahead.kinds[i], real.kinds[i]);
         }
      }

      for (int k = 0; k < 6; k++) {
         if (ahead.grid[k] != real.grid[k]) {
            return "quart grid differs";
         }
      }

      for (int i = 0; i < real.quartCount; i++) {
         if (ahead.quarts[i] != real.quarts[i]) {
            return String.format(Locale.ROOT, "quart %d: ahead biome %d, real %d", i, ahead.quarts[i], real.quarts[i]);
         }
      }

      return null;
   }

   static void failed(ChunkAccess chunk, Throwable failure) {
      RESULTS.remove(chunk.getPos().toLong());
      if (FAILURES.incrementAndGet() <= 4) {
         WorldgenAccelerator.LOG.warn("WGA GPU surface ahead: chunk {}'s inputs failed: {}", chunk.getPos(), failure.toString());
      }
   }

   static String summary() {
      if (!enabled) {
         return "";
      }

      long made = Math.max(1, MADE.sum());
      return String.format(Locale.ROOT,
         "; ahead: %,d made in %,d launches (inputs %.2f ms on the noise step, GPU %.2f ms per chunk on its own threads), %,d without a neighbour's biomes, %,d used (%,d waited, %.2f ms on average), %,d without one",
         MADE.sum(), BATCHES.sum(), INPUT_NANOS.sum() / 1.0e6 / made, GPU_NANOS.sum() / 1.0e6 / made, NO_NEIGHBOURS.sum(), USED.sum(), WAITED.sum(),
         WAIT_NANOS_SUM.sum() / 1.0e6 / Math.max(1, WAITED.sum()), MISSING.sum());
   }
}
