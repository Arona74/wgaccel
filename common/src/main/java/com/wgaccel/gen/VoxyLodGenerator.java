package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Voxy's LODs made from the GPU columns ({@link LodBuilder}) instead of generated chunks: {@code /wgaccel lod voxy
 * [radius] [threads] [stride]} fills the square of chunks around the caller into the level's Voxy world, nearest first,
 * through Voxy's section insert ({@link VoxyAccess}). Each chunk's sections are written from a little under the lowest
 * ground around it (its neighbours' too, so cliff faces have no holes) up to one section above its highest top (sky light
 * for the faces). A column: air with sky light above, one snow block on cold dry ground, its fluid (ice on top when cold)
 * between the ground and its surface, the ground's layers (the surface's blocks), then the generator's default block.
 *
 * <p>Terrain only: no trees, features or structures. Chunks the server has loaded are left out (Voxy takes them from the
 * real chunks); chunks generated before but not loaded are written over, until they are loaded again. Prototype: a
 * command, not yet an automatic generator.
 */
public final class VoxyLodGenerator {
   /** Chunks per side of a tile: one GPU launch (LodBuilder.BATCH chunks). */
   private static final int TILE = 8;
   /** Blocks written under the lowest ground around a chunk. */
   private static final int UNDER = 8;
   /**
    * Far mode (columns further apart than a chunk): cells of the sample grid per side of a tile, its 8 x 8 samples (one
    * GPU launch) the cells' corners; neighbouring tiles share their edge samples (made by both).
    */
   private static final int CELLS = 7;
   private static final AtomicReference<VoxyLodGenerator> RUNNING = new AtomicReference<>();

   private final ServerLevel level;
   private final LodBuilder builder;
   private final VoxyAccess.Target target;
   private final int minY;
   private final int height;
   private final int stride;
   /** Far mode: chunks per sample (stride / 16); 0 when every chunk is filled. */
   private final int step;
   /** Chunks per side of a tile. */
   private final int unit;
   private final int centerX;
   private final int centerZ;
   private final int radius;
   /** Chunks closer than this to the centre (in x or z) are left out: a ring. */
   private final int inner;
   private final List<long[]> tiles;
   private final AtomicInteger nextTile = new AtomicInteger();
   private final int totalChunks;
   private volatile boolean stopped;
   private final LongAdder chunks = new LongAdder();
   private final LongAdder skipped = new LongAdder();
   private final LongAdder sections = new LongAdder();
   private final LongAdder columnNanos = new LongAdder();
   private final LongAdder writeNanos = new LongAdder();
   private final LongAdder insertNanos = new LongAdder();
   private final int defaultLight = 15;

   private VoxyLodGenerator(ServerLevel level, VoxyAccess.Target target, int centerX, int centerZ, int radius, int inner, int stride) {
      this.level = level;
      this.target = target;
      this.minY = Mc.minY(level);
      this.height = level.getHeight();
      this.stride = stride;
      this.step = stride > 16 ? stride / 16 : 0;
      this.unit = this.step > 0 ? CELLS * this.step : TILE;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = radius;
      this.inner = inner;
      this.builder = new LodBuilder(level, this.minY, this.height, "Voxy LOD generator");
      // Tiles covering the square (without the inner square), nearest the centre first.
      int from = Math.floorDiv(centerX - radius, this.unit);
      int to = Math.floorDiv(centerX + radius, this.unit);
      int fromZ = Math.floorDiv(centerZ - radius, this.unit);
      int toZ = Math.floorDiv(centerZ + radius, this.unit);
      List<long[]> tiles = new ArrayList<>();
      long total = 0;
      for (int tx = from; tx <= to; tx++) {
         for (int tz = fromZ; tz <= toZ; tz++) {
            int wanted = 0;
            for (int x = tx * this.unit; x < (tx + 1) * this.unit; x++) {
               for (int z = tz * this.unit; z < (tz + 1) * this.unit; z++) {
                  wanted += this.wanted(x, z) ? 1 : 0;
               }
            }

            if (wanted > 0) {
               long dx = (long)tx * this.unit + this.unit / 2 - centerX;
               long dz = (long)tz * this.unit + this.unit / 2 - centerZ;
               tiles.add(new long[]{tx, tz, dx * dx + dz * dz});
               total += wanted;
            }
         }
      }

      tiles.sort((a, b) -> Long.compare(a[2], b[2]));
      this.tiles = tiles;
      this.totalChunks = (int)total;
   }

   /** Whether the chunk is in the square and outside the inner one. */
   private boolean wanted(int x, int z) {
      int d = Math.max(Math.abs(x - this.centerX), Math.abs(z - this.centerZ));
      return d <= this.radius && d >= this.inner;
   }

   /** {@code /wgaccel lod voxy}: starts filling the square around the chunk; false (reported) when it cannot. */
   public static boolean start(ServerLevel level, int chunkX, int chunkZ, int radius, int inner, int threads, int stride, Consumer<String> report) {
      VoxyAccess access = VoxyAccess.get();
      if (access == null) {
         report.accept("WGA Voxy LOD generator: Voxy's section insert is not there (" + VoxyAccess.failure() + ")");
         return false;
      }

      if (RUNNING.get() != null) {
         report.accept("WGA Voxy LOD generator: one is running already (/wgaccel lod voxy stop)");
         return false;
      }

      VoxyAccess.Target target = access.target(level);
      if (target == null) {
         report.accept("WGA Voxy LOD generator: Voxy has no world for this level (is Voxy running?)");
         return false;
      }

      VoxyLodGenerator generator = new VoxyLodGenerator(level, target, chunkX, chunkZ, radius, inner, stride);
      if (!RUNNING.compareAndSet(null, generator)) {
         target.release();
         report.accept("WGA Voxy LOD generator: one is running already");
         return false;
      }

      // The loaded chunks (left out below) from the real chunks, in case Voxy missed them.
      int[] ingested = ingestLoaded(level, chunkX, chunkZ, radius);
      report.accept(String.format(Locale.ROOT, "WGA Voxy LOD generator: %,d loaded chunks handed to Voxy's own ingest (%,d not taken)", ingested[0], ingested[1]));
      report.accept(String.format(Locale.ROOT, "WGA Voxy LOD generator: %,d chunks around chunk %d, %d (radius %d%s), %d threads, a column every %d blocks%s...",
         generator.totalChunks, chunkX, chunkZ, radius, inner > 0 ? ", from " + inner : "", threads, stride, stride > 16 ? " (heights between them interpolated)" : ""));
      Thread thread = new Thread(() -> generator.run(threads, report), "WGA Voxy LOD");
      thread.setDaemon(true);
      thread.start();
      return true;
   }

   /**
    * The chunks the server has loaded in the square, handed to Voxy's own ingest again (on the server thread). Voxy
    * ingests a chunk when the client shows it, and gives up when its light is not there yet: on a world's first load the
    * spawn area's chunks come before their light, so Voxy misses them until they leave the view (or /voxy import).
    *
    * @return {taken, not taken}
    */
   public static int[] ingestLoaded(ServerLevel level, int centerX, int centerZ, int radius) {
      VoxyAccess access = VoxyAccess.get();
      int[] counts = new int[2];
      if (access == null) {
         return counts;
      }

      for (int x = centerX - radius; x <= centerX + radius; x++) {
         for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk != null) {
               counts[access.ingest(chunk) ? 0 : 1]++;
            }
         }
      }

      return counts;
   }

   /**
    * When a player joins with Voxy installed: the loaded chunks around them handed to Voxy again 10 s and 30 s later (the
    * spawn area Voxy misses on a world's first load, see {@link #ingestLoaded}).
    */
   public static void onPlayerLoggedIn(net.minecraft.server.level.ServerPlayer player) {
      if (!com.wgaccel.platform.Platform.get().isModLoaded("voxy") || VoxyAccess.get() == null) {
         return;
      }

      Thread thread = new Thread(() -> {
         for (long delay : new long[]{10_000L, 20_000L}) {
            try {
               Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
               return;
            }

            net.minecraft.server.MinecraftServer server = ((ServerLevel)player.level()).getServer();
            if (!server.isRunning() || player.hasDisconnected()) {
               return;
            }

            server.execute(() -> {
               ServerLevel level = (ServerLevel)player.level();
               int radius = server.getPlayerList().getViewDistance() + 1;
               int[] counts = ingestLoaded(level, Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4, radius);
               WorldgenAccelerator.LOG.info(com.wgaccel.LevelLogger.ALWAYS, "WGA Voxy: {} loaded chunks around {} handed to Voxy's ingest again ({} not taken: no light yet, or Voxy's ingest off)",
                  counts[0], player.getName().getString(), counts[1]);
            });
         }
      }, "WGA Voxy ingest");
      thread.setDaemon(true);
      thread.start();
   }

   /** {@code /wgaccel lod voxy stop}. */
   public static boolean stop(Consumer<String> report) {
      VoxyLodGenerator generator = RUNNING.get();
      if (generator == null) {
         report.accept("WGA Voxy LOD generator: none is running");
         return false;
      }

      generator.stopped = true;
      report.accept("WGA Voxy LOD generator: stopping");
      return true;
   }

   private boolean stopping() {
      return this.stopped || !this.level.getServer().isRunning();
   }

   private void run(int threads, Consumer<String> report) {
      long start = System.nanoTime();
      List<Thread> workers = new ArrayList<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      try {
         for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
               try {
                  this.work();
               } catch (Throwable failure) {
                  if (!(failure instanceof java.util.concurrent.CancellationException)) {
                     failed.compareAndSet(null, failure);
                     this.stopped = true;
                  }
               }
            }, "WGA Voxy LOD " + t);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
         }

         long nextReport = System.nanoTime() + 10_000_000_000L;
         for (Thread worker : workers) {
            while (worker.isAlive()) {
               worker.join(500L);
               if (System.nanoTime() > nextReport) {
                  nextReport += 10_000_000_000L;
                  report.accept(this.line("so far", start));
               }
            }
         }
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         this.stopped = true;
      } finally {
         this.target.release();
         RUNNING.compareAndSet(this, null);
      }

      if (failed.get() != null) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator failed", failed.get());
         report.accept("WGA Voxy LOD generator failed: " + failed.get());
      }

      report.accept(this.line(this.stopped ? "stopped" : "done", start));
   }

   private String line(String what, long start) {
      double seconds = (System.nanoTime() - start) / 1.0e9;
      long done = Math.max(1, this.chunks.sum());
      return String.format(Locale.ROOT,
         "WGA Voxy LOD generator %s: %,d of %,d chunks (%,d loaded left out) in %.1f s = %,.0f chunks/s; %,d sections; per chunk on its thread: columns %.2f ms (fill inputs %.2f, fill GPU %.2f, surface inputs %.2f, surface GPU %.2f), ids %.2f ms, Voxy insert %.2f ms; %,d columns from the CPU",
         what, this.chunks.sum(), this.totalChunks, this.skipped.sum(), seconds, this.chunks.sum() / Math.max(seconds, 1.0e-3), this.sections.sum(),
         this.columnNanos.sum() / 1.0e6 / done, this.builder.fillInputNanos.sum() / 1.0e6 / done, this.builder.fillNanos.sum() / 1.0e6 / done,
         this.builder.surfaceInputNanos.sum() / 1.0e6 / done, this.builder.surfaceGpuNanos.sum() / 1.0e6 / done, this.writeNanos.sum() / 1.0e6 / done,
         this.insertNanos.sum() / 1.0e6 / done, this.builder.cpuColumns.sum());
   }

   /** One chunk's columns as Voxy block and biome ids, index z << 4 | x. */
   private static final class ChunkColumns {
      ChunkPos pos;
      final int[] ground = new int[256];
      final int[] surface = new int[256];
      final int[] fluid = new int[256];
      final int[] biome = new int[256];
      final boolean[] snow = new boolean[256];
      final boolean[] ice = new boolean[256];
      /** layers[col * GROUND_DEPTH + k]: the block k under the ground. */
      final int[] layers = new int[256 * LodBuilder.GROUND_DEPTH];
      final int[] below = new int[256];
      int lowest;
      int highest;
   }

   /** One thread's lists and buffers. */
   private final class Work implements LodBuilder.Sink {
      final ChunkColumns[] columns = new ChunkColumns[LodBuilder.BATCH];
      final Map<Long, ChunkColumns> byPos = new HashMap<>();
      final int snowId;
      final int iceId;
      Object section;
      /** Far mode: the interpolated chunk being written. */
      final ChunkColumns out = new ChunkColumns();
      int count;
      ChunkColumns current;

      Work() {
         for (int i = 0; i < this.columns.length; i++) {
            this.columns[i] = new ChunkColumns();
         }

         this.snowId = VoxyLodGenerator.this.target.block(LodBuilder.snow());
         this.iceId = VoxyLodGenerator.this.target.block(Blocks.ICE.defaultBlockState());
      }

      @Override
      public void chunk(ChunkPos pos, int n, int fillStride) {
         ChunkColumns chunk = this.columns[this.count++];
         chunk.pos = pos;
         chunk.lowest = Integer.MAX_VALUE;
         chunk.highest = Integer.MIN_VALUE;
         this.current = chunk;
      }

      @Override
      public void column(ChunkPos pos, int i, int j, LodBuilder.Column c) {
         VoxyAccess.Target target = VoxyLodGenerator.this.target;
         ChunkColumns chunk = this.current;
         int bottom = VoxyLodGenerator.this.minY - 1;
         int ground = c.ground == LodColumns.NONE ? bottom : c.ground;
         int surface = c.surface == LodColumns.NONE ? ground : Math.max(ground, c.surface);
         int fluid = surface > ground ? target.block(c.top) : 0;
         int biome = target.biome(c.biome);
         int below = target.block(c.below);
         int s = VoxyLodGenerator.this.stride;
         int at = -1;
         for (int x = i * s; x < Math.min(16, i * s + s); x++) {
            for (int z = j * s; z < Math.min(16, j * s + s); z++) {
               int col = z << 4 | x;
               chunk.ground[col] = ground;
               chunk.surface[col] = surface;
               chunk.fluid[col] = fluid;
               chunk.biome[col] = biome;
               chunk.snow[col] = c.snow;
               chunk.ice[col] = c.ice;
               chunk.below[col] = below;
               int base = col * LodBuilder.GROUND_DEPTH;
               if (at >= 0) {
                  System.arraycopy(chunk.layers, at, chunk.layers, base, LodBuilder.GROUND_DEPTH);
               } else {
                  for (int k = 0; k < LodBuilder.GROUND_DEPTH; k++) {
                     chunk.layers[base + k] = target.block(c.layers == null ? c.groundTop : c.layers[k]);
                  }

                  at = base;
               }
            }
         }

         chunk.lowest = Math.min(chunk.lowest, ground);
         chunk.highest = Math.max(chunk.highest, c.snow ? surface + 1 : surface);
      }
   }

   private void work() {
      LodBuilder.Worker worker = this.builder.worker(this::stopping);
      Work work = new Work();
      while (!this.stopping()) {
         int index = this.nextTile.getAndIncrement();
         if (index >= this.tiles.size()) {
            return;
         }

         long[] tile = this.tiles.get(index);
         if (this.step > 0) {
            this.farTile(worker, work, (int)tile[0], (int)tile[1]);
            continue;
         }

         List<ChunkPos> batch = worker != null ? worker.batch : new ArrayList<>(LodBuilder.BATCH);
         batch.clear();
         for (int dx = 0; dx < TILE; dx++) {
            for (int dz = 0; dz < TILE; dz++) {
               int x = (int)tile[0] * TILE + dx;
               int z = (int)tile[1] * TILE + dz;
               if (!this.wanted(x, z)) {
                  continue;
               }

               if (this.level.getChunkSource().hasChunk(x, z)) {
                  this.skipped.increment();
               } else {
                  batch.add(Mc.chunk(Mc.key(x, z)));
               }
            }
         }

         if (batch.isEmpty()) {
            continue;
         }

         long start = System.nanoTime();
         work.count = 0;
         int n = batch.size();
         if (worker != null) {
            this.builder.launch(worker, this.stride, LodBuilder.fastStride(this.stride, this.stride, false), true, work);
         } else {
            LodBuilder.Column column = new LodBuilder.Column();
            for (ChunkPos pos : batch) {
               work.chunk(pos, 16 / this.stride, this.stride);
               this.builder.cpuChunk(pos, 16 / this.stride, this.stride, column, work);
            }
         }

         long columns = System.nanoTime();
         this.columnNanos.add(columns - start);
         this.write(work);
         this.chunks.add(n);
      }
   }

   /** The tile's chunks' sections into Voxy. */
   private void write(Work work) {
      work.byPos.clear();
      for (int c = 0; c < work.count; c++) {
         work.byPos.put(Mc.key(work.columns[c].pos), work.columns[c]);
      }

      long insertNanos = 0;
      long start = System.nanoTime();
      for (int c = 0; c < work.count; c++) {
         ChunkColumns chunk = work.columns[c];
         int cx = Mc.x(chunk.pos);
         int cz = Mc.z(chunk.pos);
         // Down to the lowest ground around the chunk (a neighbour outside the tile: 16 blocks under its own lowest).
         // Up to above the highest top around it (a neighbour outside the tile: 16 blocks over its own highest).
         int lowest = chunk.lowest;
         int highest = chunk.highest;
         int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
         for (int[] d : around) {
            ChunkColumns next = work.byPos.get(Mc.key(cx + d[0], cz + d[1]));
            lowest = Math.min(lowest, next != null ? next.lowest : chunk.lowest - 16);
            highest = Math.max(highest, next != null ? next.highest : chunk.highest + 16);
         }

         insertNanos += this.writeChunk(work, chunk, lowest, highest);
      }

      this.writeNanos.add(System.nanoTime() - start - insertNanos);
      this.insertNanos.add(insertNanos);
   }

   /**
    * The chunk's sections from under lowest (its ground and its neighbours') up to one whole section of air over the
    * section holding highest (its top and its neighbours'); returns the insert time.
    *
    * <p>The air section matters for Voxy's coarse levels: a coarse voxel is a block when any of its eight children is
    * one, air (with their average light) only when all are, so far away the section holding the top is one block voxel,
    * and its faces take the light of the voxel above it: without a written section there, Voxy's empty air with light 0,
    * the far terrain drawn dark (patches changing with the level Voxy shows). Real chunks have every section, sky lit.
    */
   private long writeChunk(Work work, ChunkColumns chunk, int lowest, int highest) {
      int cx = Mc.x(chunk.pos);
      int cz = Mc.z(chunk.pos);
      int topSection = (this.minY + this.height - 1) >> 4;
      int from = Math.max(this.minY, lowest - UNDER) >> 4;
      int to = Math.min(topSection, (Math.max(highest, chunk.highest) >> 4) + 1);
      if (work.section == null) {
         work.section = this.target.section(cx, from, cz);
      }

      long insertNanos = 0;
      long[] data = this.target.data(work.section);
      for (int sy = from; sy <= to; sy++) {
         int nonAir = this.fill(chunk, work, data, sy << 4);
         long insert = System.nanoTime();
         this.target.insert(work.section, cx, sy, cz, nonAir);
         insertNanos += System.nanoTime() - insert;
         this.sections.increment();
      }

      return insertNanos;
   }

   /**
    * Far mode: one tile, its chunks made from a grid of samples (one column per step x step chunks, the first column of
    * the sample's chunk) one GPU launch fills. Between the samples, each block column's ground is the four corners'
    * interpolated, and its blocks (layers, water, snow, biome) are one corner's, chosen at random by the corners' weights
    * (so grass, sand and rock blend rather than in squares the samples' size).
    */
   private void farTile(LodBuilder.Worker worker, Work work, int tx, int tz) {
      int sample0X = tx * CELLS;
      int sample0Z = tz * CELLS;
      List<ChunkPos> batch = worker != null ? worker.batch : new ArrayList<>(LodBuilder.BATCH);
      batch.clear();
      for (int a = 0; a <= CELLS; a++) {
         for (int b = 0; b <= CELLS; b++) {
            batch.add(Mc.chunk(Mc.key((sample0X + a) * this.step, (sample0Z + b) * this.step)));
         }
      }

      long start = System.nanoTime();
      work.count = 0;
      if (worker != null) {
         this.builder.launch(worker, 16, LodBuilder.fastStride(this.stride, 16, false), true, work);
      } else {
         LodBuilder.Column column = new LodBuilder.Column();
         for (ChunkPos pos : batch) {
            work.chunk(pos, 1, 16);
            this.builder.cpuChunk(pos, 1, 16, column, work);
         }
      }

      this.columnNanos.add(System.nanoTime() - start);
      // work.columns[a * 8 + b]: sample (a, b), its value in column 0 (the sample's chunk was filled with it).
      long writeStart = System.nanoTime();
      long insertNanos = 0;
      int span = this.step * 16;
      int originX = sample0X * span;
      int originZ = sample0Z * span;
      ChunkColumns out = work.out;
      int written = 0;
      for (int cx = tx * this.unit; cx < (tx + 1) * this.unit; cx++) {
         for (int cz = tz * this.unit; cz < (tz + 1) * this.unit; cz++) {
            if (!this.wanted(cx, cz)) {
               continue;
            }

            if (this.level.getChunkSource().hasChunk(cx, cz)) {
               this.skipped.increment();
               continue;
            }

            int a = (cx * 16 - originX) / span;
            int b = (cz * 16 - originZ) / span;
            ChunkColumns c00 = work.columns[a * 8 + b];
            ChunkColumns c10 = work.columns[(a + 1) * 8 + b];
            ChunkColumns c01 = work.columns[a * 8 + b + 1];
            ChunkColumns c11 = work.columns[(a + 1) * 8 + b + 1];
            out.pos = Mc.chunk(Mc.key(cx, cz));
            out.highest = Integer.MIN_VALUE;
            int lowest = Math.min(Math.min(c00.ground[0], c10.ground[0]), Math.min(c01.ground[0], c11.ground[0]));
            for (int x = 0; x < 16; x++) {
               double fx = (cx * 16 + x - originX - a * span) / (double)span;
               for (int z = 0; z < 16; z++) {
                  double fz = (cz * 16 + z - originZ - b * span) / (double)span;
                  double w00 = (1 - fx) * (1 - fz);
                  double w10 = fx * (1 - fz);
                  double w01 = (1 - fx) * fz;
                  double w11 = fx * fz;
                  // Rounded up or down at random by its fraction: even slopes' 1-block steps then have ragged edges,
                  // not regular contour lines (stripes and moire far away).
                  double exact = w00 * c00.ground[0] + w10 * c10.ground[0] + w01 * c01.ground[0] + w11 * c11.ground[0];
                  int ground = (int)Math.floor(exact);
                  ground += random(cz * 16 + z + 0x5F3759DF, cx * 16 + x) < exact - ground ? 1 : 0;
                  double r = random(cx * 16 + x, cz * 16 + z);
                  ChunkColumns from = r < w00 ? c00 : r < w00 + w10 ? c10 : r < w00 + w10 + w01 ? c01 : c11;
                  int col = z << 4 | x;
                  boolean wet = from.surface[0] > from.ground[0] && from.surface[0] > ground;
                  out.ground[col] = ground;
                  out.surface[col] = wet ? from.surface[0] : ground;
                  out.fluid[col] = wet ? from.fluid[0] : 0;
                  out.ice[col] = wet && from.ice[0];
                  out.snow[col] = !wet && from.snow[0];
                  out.biome[col] = from.biome[0];
                  out.below[col] = from.below[0];
                  System.arraycopy(from.layers, 0, out.layers, col * LodBuilder.GROUND_DEPTH, LodBuilder.GROUND_DEPTH);
                  out.highest = Math.max(out.highest, out.snow[col] ? out.surface[col] + 1 : out.surface[col]);
               }
            }

            int highest = Math.max(Math.max(c00.highest, c10.highest), Math.max(c01.highest, c11.highest));
            insertNanos += this.writeChunk(work, out, lowest, highest);
            written++;
         }
      }

      this.writeNanos.add(System.nanoTime() - writeStart - insertNanos);
      this.insertNanos.add(insertNanos);
      this.chunks.add(written);
   }

   /** A number in [0, 1) from the block column (the same each run). */
   private static double random(int x, int z) {
      long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
      h ^= h >>> 31;
      h *= 0xBF58476D1CE4E5B9L;
      h ^= h >>> 29;
      return (h >>> 11) * 0x1.0p-53;
   }

   /** The section at block y baseY: its 4,096 ids; returns its non-air count. */
   private int fill(ChunkColumns chunk, Work work, long[] data, int baseY) {
      int nonAir = 0;
      long sky = VoxyAccess.air(this.defaultLight);
      for (int col = 0; col < 256; col++) {
         int ground = chunk.ground[col];
         int surface = chunk.surface[col];
         int biome = chunk.biome[col];
         boolean snow = chunk.snow[col];
         boolean ice = chunk.ice[col];
         int base = col * LodBuilder.GROUND_DEPTH;
         int groundLight = Math.max(0, this.defaultLight - (surface - ground));
         for (int ly = 0; ly < 16; ly++) {
            int y = baseY + ly;
            long id;
            if (y > surface) {
               id = snow && y == surface + 1 ? VoxyAccess.id(this.defaultLight, work.snowId, biome) : sky;
            } else if (y > ground) {
               // Water dims sky light by one a block.
               int light = Math.max(0, this.defaultLight - (surface - y));
               id = VoxyAccess.id(light, ice && y == surface ? work.iceId : chunk.fluid[col], biome);
            } else {
               int k = ground - y;
               // The ground with its column's sky light (15 in the open, less under water), as VWG's own no-chunk path
               // does: in real chunks an opaque block has 0, but their tops are mostly lit plants, snow layers and
               // leaves, which Voxy's coarse levels keep; Voxy lights some faces from the voxel itself (dark
               // rectangles far away with 0).
               id = VoxyAccess.id(groundLight, k < LodBuilder.GROUND_DEPTH ? chunk.layers[base + k] : chunk.below[col], biome);
            }

            data[ly << 8 | col] = id;
            nonAir += (id & 0xFFFFFL << 27) != 0 ? 1 : 0;
         }
      }

      return nonAir;
   }
}
