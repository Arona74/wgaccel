package com.wgaccel.gen;

import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Voxy's LOD sections made from the GPU columns ({@link LodBuilder}) and put into the level's Voxy world ({@link
 * VoxyAccess}), a tile at a time: the {@code /wgaccel lod voxy} command ({@link VoxyLodGenerator}) and the automatic
 * generator ({@link VoxyAuto}) use it.
 *
 * <p>Two kinds of tile. With a column every 1-16 blocks (stride), a tile is 8 x 8 chunks, every chunk filled (one GPU
 * launch). With 32-256, one column every stride / 16 chunks: a tile is 7 x 7 cells of a grid of samples (8 x 8 of them,
 * one GPU launch, FreeTerraForged's fast cells), and between the samples each block column's ground is interpolated
 * from the four corners (rounded up or down at random by its fraction, so even slopes have no regular contour lines),
 * its blocks (layers, water, snow, ice, biome) one corner's, picked at random by the corners' weights (grass, sand and
 * rock blend instead of forming squares).
 *
 * <p>Each chunk is written from a little under the lowest ground around it (its neighbours' too: no holes in cliff
 * faces) up to one whole air section over the highest top around it. That air section matters for Voxy's coarse levels:
 * a coarse voxel is a block when any of its 8 children is one, and a face takes the light of the voxel in front of it;
 * without it that voxel is Voxy's empty air with light 0 and far terrain draws dark. A column: air with sky light above,
 * one snow block on cold dry ground, its fluid (ice on top when cold, sky light less one a block) between the ground and
 * its surface, the ground's layers (the surface's blocks), then the generator's default block.
 */
public final class VoxyWriter {
   /** Chunks per side of a near tile: one GPU launch (LodBuilder.BATCH chunks). */
   public static final int TILE = 8;
   /** Far tiles: grid cells per side (8 x 8 samples, one GPU launch; neighbouring tiles share their edge samples). */
   public static final int CELLS = 7;
   /** Blocks written under the lowest ground around a chunk. */
   private static final int UNDER = 8;
   private static final int SKY = 15;

   /** Which of a tile's chunks to write. */
   public interface ChunkTest {
      boolean write(int chunkX, int chunkZ);
   }

   final ServerLevel level;
   final LodBuilder builder;
   final VoxyAccess.Target target;
   private final int minY;
   private final int height;
   private final ThreadLocal<Work> works = new ThreadLocal<>();
   public final LongAdder chunks = new LongAdder();
   public final LongAdder sections = new LongAdder();
   public final LongAdder columnNanos = new LongAdder();
   public final LongAdder writeNanos = new LongAdder();
   public final LongAdder insertNanos = new LongAdder();

   public VoxyWriter(ServerLevel level, VoxyAccess.Target target, String name) {
      this.level = level;
      this.target = target;
      this.minY = Mc.minY(level);
      this.height = level.getHeight();
      this.builder = new LodBuilder(level, this.minY, this.height, name);
   }

   /** Chunks per side of a tile at this stride. */
   public static int unit(int stride) {
      return stride > 16 ? CELLS * (stride / 16) : TILE;
   }

   /** This thread's GPU worker (waiting for the GPU fill while it is made); null: the CPU's getBaseHeight. */
   public LodBuilder.Worker worker(BooleanSupplier stopping) {
      return this.builder.worker(stopping);
   }

   /** The builder's numbers, for reports. */
   public LodBuilder builder() {
      return this.builder;
   }

   /** The level's Voxy world given back. */
   public void release() {
      this.target.release();
   }

   private Work work() {
      Work work = this.works.get();
      if (work == null) {
         work = new Work();
         this.works.set(work);
      }

      return work;
   }

   /**
    * Tile (tx, tz) at the stride (its size {@link #unit}): the chunks the test takes made and written. Returns how many
    * were written.
    */
   public int tile(LodBuilder.Worker worker, int tx, int tz, int stride, ChunkTest test) {
      return stride > 16 ? this.farTile(worker, tx, tz, stride, test) : this.nearTile(worker, tx, tz, stride, test);
   }

   private int nearTile(LodBuilder.Worker worker, int tx, int tz, int stride, ChunkTest test) {
      Work work = this.work();
      work.stride = stride;
      List<ChunkPos> batch = worker != null ? worker.batch : new ArrayList<>(LodBuilder.BATCH);
      batch.clear();
      for (int dx = 0; dx < TILE; dx++) {
         for (int dz = 0; dz < TILE; dz++) {
            int x = tx * TILE + dx;
            int z = tz * TILE + dz;
            if (test.write(x, z)) {
               batch.add(Mc.chunk(Mc.key(x, z)));
            }
         }
      }

      int n = batch.size();
      if (n == 0) {
         return 0;
      }

      long start = System.nanoTime();
      work.count = 0;
      if (worker != null) {
         // Every chunk: exact (FreeTerraForged's region filters; its fast cells were no faster at 1-16 blocks apart).
         this.builder.launch(worker, stride, 0, true, work);
      } else {
         LodBuilder.Column column = new LodBuilder.Column();
         for (ChunkPos pos : batch) {
            work.chunk(pos, 16 / stride, stride);
            this.builder.cpuChunk(pos, 16 / stride, stride, column, work);
         }
      }

      this.columnNanos.add(System.nanoTime() - start);
      work.byPos.clear();
      for (int c = 0; c < work.count; c++) {
         work.byPos.put(Mc.key(work.columns[c].pos), work.columns[c]);
      }

      long insertNanos = 0;
      long writeStart = System.nanoTime();
      for (int c = 0; c < work.count; c++) {
         ChunkColumns chunk = work.columns[c];
         int cx = Mc.x(chunk.pos);
         int cz = Mc.z(chunk.pos);
         // Down to the lowest ground around the chunk, up to over the highest top around it (a neighbour outside the
         // tile: 16 blocks under its own lowest, over its own highest).
         int lowest = chunk.lowest;
         int highest = chunk.highest;
         for (int d = 0; d < 4; d++) {
            ChunkColumns next = work.byPos.get(Mc.key(cx + (d == 0 ? 1 : d == 1 ? -1 : 0), cz + (d == 2 ? 1 : d == 3 ? -1 : 0)));
            lowest = Math.min(lowest, next != null ? next.lowest : chunk.lowest - 16);
            highest = Math.max(highest, next != null ? next.highest : chunk.highest + 16);
         }

         insertNanos += this.writeChunk(work, chunk, lowest, highest);
      }

      this.writeNanos.add(System.nanoTime() - writeStart - insertNanos);
      this.insertNanos.add(insertNanos);
      this.chunks.add(n);
      return n;
   }

   private int farTile(LodBuilder.Worker worker, int tx, int tz, int stride, ChunkTest test) {
      int step = stride / 16;
      int unit = CELLS * step;
      boolean any = false;
      for (int cx = tx * unit; cx < (tx + 1) * unit && !any; cx++) {
         for (int cz = tz * unit; cz < (tz + 1) * unit && !any; cz++) {
            any = test.write(cx, cz);
         }
      }

      if (!any) {
         return 0;
      }

      Work work = this.work();
      work.stride = 16;
      int sample0X = tx * CELLS;
      int sample0Z = tz * CELLS;
      List<ChunkPos> batch = worker != null ? worker.batch : new ArrayList<>(LodBuilder.BATCH);
      batch.clear();
      for (int a = 0; a <= CELLS; a++) {
         for (int b = 0; b <= CELLS; b++) {
            batch.add(Mc.chunk(Mc.key((sample0X + a) * step, (sample0Z + b) * step)));
         }
      }

      long start = System.nanoTime();
      work.count = 0;
      if (worker != null) {
         // FreeTerraForged's fast cells (FtfLodCells: its terrain for the sample columns only, without whole regions'
         // filters), whatever Distant Horizons' distantHorizonsFastFrom says: with the CLIENT preset that is 6 (64 blocks),
         // so stride 32 built whole regions per sample and ran at full detail's rate (~1,000 chunks/s, 19 Nov 2026).
         this.builder.launch(worker, 16, 16, true, work);
      } else {
         LodBuilder.Column column = new LodBuilder.Column();
         for (ChunkPos pos : batch) {
            work.chunk(pos, 1, 16);
            this.builder.cpuChunk(pos, 1, 16, column, work);
         }
      }

      this.columnNanos.add(System.nanoTime() - start);
      // work.columns[a * 8 + b]: sample (a, b), its value in column 0 (the sample's whole chunk was filled with it).
      long writeStart = System.nanoTime();
      long insertNanos = 0;
      int span = step * 16;
      int originX = sample0X * span;
      int originZ = sample0Z * span;
      ChunkColumns out = work.out;
      int written = 0;
      for (int cx = tx * unit; cx < (tx + 1) * unit; cx++) {
         for (int cz = tz * unit; cz < (tz + 1) * unit; cz++) {
            if (!test.write(cx, cz)) {
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
      return written;
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

   /** One thread's buffers; the sink the builder hands a launch's columns to. */
   private final class Work implements LodBuilder.Sink {
      final ChunkColumns[] columns = new ChunkColumns[LodBuilder.BATCH];
      final Map<Long, ChunkColumns> byPos = new HashMap<>();
      /** Far tiles: the interpolated chunk being written. */
      final ChunkColumns out = new ChunkColumns();
      final int snowId;
      final int iceId;
      Object section;
      int count;
      /** The launch's column spacing (a column fills stride x stride block columns). */
      int stride;
      ChunkColumns current;

      Work() {
         for (int i = 0; i < this.columns.length; i++) {
            this.columns[i] = new ChunkColumns();
         }

         this.snowId = VoxyWriter.this.target.block(LodBuilder.snow());
         this.iceId = VoxyWriter.this.target.block(Blocks.ICE.defaultBlockState());
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
         VoxyAccess.Target target = VoxyWriter.this.target;
         ChunkColumns chunk = this.current;
         int bottom = VoxyWriter.this.minY - 1;
         int ground = c.ground == LodColumns.NONE ? bottom : c.ground;
         int surface = c.surface == LodColumns.NONE ? ground : Math.max(ground, c.surface);
         int fluid = surface > ground ? target.block(c.top) : 0;
         int biome = target.biome(c.biome);
         int below = target.block(c.below);
         int s = this.stride;
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

   /** The chunk's sections from under lowest up to one whole air section over the one holding highest; returns the insert time. */
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
         int nonAir = fill(chunk, work, data, sy << 4);
         long insert = System.nanoTime();
         this.target.insert(work.section, cx, sy, cz, nonAir);
         insertNanos += System.nanoTime() - insert;
         this.sections.increment();
      }

      return insertNanos;
   }

   /** The section at block y baseY: its 4,096 ids; returns its non-air count. */
   private static int fill(ChunkColumns chunk, Work work, long[] data, int baseY) {
      int nonAir = 0;
      long sky = VoxyAccess.air(SKY);
      for (int col = 0; col < 256; col++) {
         int ground = chunk.ground[col];
         int surface = chunk.surface[col];
         int biome = chunk.biome[col];
         boolean snow = chunk.snow[col];
         boolean ice = chunk.ice[col];
         int base = col * LodBuilder.GROUND_DEPTH;
         // The ground with its column's sky light, as Voxy World Gen V2's own no-chunk path writes it (Voxy lights an
         // opaque block's faces from the voxel in front, so it does not show).
         int groundLight = Math.max(0, SKY - (surface - ground));
         for (int ly = 0; ly < 16; ly++) {
            int y = baseY + ly;
            long id;
            if (y > surface) {
               id = snow && y == surface + 1 ? VoxyAccess.id(SKY, work.snowId, biome) : sky;
            } else if (y > ground) {
               // Water dims sky light by one a block.
               id = VoxyAccess.id(Math.max(0, SKY - (surface - y)), ice && y == surface ? work.iceId : chunk.fluid[col], biome);
            } else {
               int k = ground - y;
               id = VoxyAccess.id(groundLight, k < LodBuilder.GROUND_DEPTH ? chunk.layers[base + k] : chunk.below[col], biome);
            }

            data[ly << 8 | col] = id;
            nonAir += (id & 0xFFFFFL << 27) != 0 ? 1 : 0;
         }
      }

      return nonAir;
   }

   /** A number in [0, 1) from the block column (the same each run). */
   private static double random(int x, int z) {
      long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
      h ^= h >>> 31;
      h *= 0xBF58476D1CE4E5B9L;
      h ^= h >>> 29;
      return (h >>> 11) * 0x1.0p-53;
   }
}
