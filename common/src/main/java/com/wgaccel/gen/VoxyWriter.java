package com.wgaccel.gen;

import com.wgaccel.Config;

import com.wgaccel.platform.Mc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
   /** voxyTrees: the stand-in trees ({@link LodTrees}). */
   private final LodTrees trees;
   /** voxyAronaLayers: AronaLayers' own layer placement on a stand-in chunk ({@link LodLayers}). */
   private final LodLayers layers;
   private final int minY;
   private final int height;
   private final ThreadLocal<Work> works = new ThreadLocal<>();
   public final LongAdder chunks = new LongAdder();
   public final LongAdder sections = new LongAdder();
   public final LongAdder columnNanos = new LongAdder();
   public final LongAdder writeNanos = new LongAdder();
   public final LongAdder insertNanos = new LongAdder();
   /** voxyTrees: the time working out and drawing the trees (part of writeNanos). */
   public final LongAdder treeNanos = new LongAdder();
   /**
    * At most this many threads write a chunk's sections into Voxy at once (the rest wait their turn): many threads writing
    * while Voxy meshes and unloads the same sections hit a race in Voxy 0.2.16 (WorldSection.trySetFreed: "Section freed
    * while marked as dirty or in the save queue"), after which its threads wait for that section for ever
    * (ActiveSectionTracker.acquire spins until the section is loaded).
    */
   private volatile int maxWriters = Integer.MAX_VALUE;
   private volatile boolean closed;
   private final Object gate = new Object();
   private int writing;
   /** The threads writing into Voxy now, and since when (System.nanoTime): a write that never returns shows here. */
   private final java.util.concurrent.ConcurrentHashMap<Thread, Long> writingSince = new java.util.concurrent.ConcurrentHashMap<>();

   public VoxyWriter(ServerLevel level, VoxyAccess.Target target, String name) {
      this.level = level;
      this.target = target;
      this.minY = Mc.minY(level);
      this.height = level.getHeight();
      this.builder = new LodBuilder(level, this.minY, this.height, name);
      this.trees = new LodTrees(target::block, level);
      this.layers = new LodLayers(level, target::block);
   }

   public void maxWriters(int writers) {
      this.maxWriters = Math.max(1, writers);
      synchronized (this.gate) {
         this.gate.notifyAll();
      }
   }

   /** How long the oldest write into Voxy still running has taken (ns), 0 when none runs. */
   public long longestWriteNanos() {
      long now = System.nanoTime();
      long longest = 0;
      for (long since : this.writingSince.values()) {
         longest = Math.max(longest, now - since);
      }

      return longest;
   }

   /** The world is closing: threads waiting for their turn to write leave (CancellationException), none enters. */
   public void close() {
      this.closed = true;
      synchronized (this.gate) {
         this.gate.notifyAll();
      }
   }

   private void enter() {
      synchronized (this.gate) {
         while (this.writing >= this.maxWriters || this.closed) {
            if (this.closed) {
               throw new java.util.concurrent.CancellationException("the world is closing");
            }

            try {
               this.gate.wait(100L);
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               throw new java.util.concurrent.CancellationException("stopping");
            }
         }

         this.writing++;
      }

      this.writingSince.put(Thread.currentThread(), System.nanoTime());
   }

   private void leave() {
      this.writingSince.remove(Thread.currentThread());
      synchronized (this.gate) {
         this.writing--;
         this.gate.notify();
      }
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
      try {
         return stride > 16 ? this.farTile(worker, tx, tz, stride, test) : this.nearTile(worker, tx, tz, stride, test);
      } finally {
         this.unpinAll(this.work());
      }
   }

   /**
    * Voxy's world sections the chunk's sections go into (levels 0-4, as WorldUpdater.insertUpdate takes them) held by us
    * while this thread writes there: insertUpdate acquires and releases each of them for every section, and each release
    * to no reference unloads it (saved by Voxy's saving service, then freed). That churn, hundreds of times a second over
    * the same sections, is where Voxy 0.2.16's race shows ("Section freed while marked as dirty or in the save queue",
    * after which its threads and ours wait for ever). Held, they unload once, when we let go: the 64 used last per
    * thread (a level-0 world section is 32^3 ids, 256 KB), all at the end of each tile.
    */
   private void pin(Work work, int cx, int sy, int cz) {
      if (!this.target.pins()) {
         return;
      }

      for (int lvl = 0; lvl <= 4; lvl++) {
         int x = cx >> (lvl + 1);
         int y = sy >> (lvl + 1);
         int z = cz >> (lvl + 1);
         long key = (long)lvl << 60 | ((long)x & 0xFFFFFL) << 40 | ((long)y & 0xFFFL) << 28 | (long)z & 0xFFFFFL;
         if (work.pinned.get(key) == null) {
            Object section = this.target.pin(lvl, x, y, z);
            if (section != null) {
               work.pinned.put(key, section);
            }
         }
      }
   }

   private void unpinAll(Work work) {
      if (work.pinned.isEmpty()) {
         return;
      }

      this.writingSince.put(Thread.currentThread(), System.nanoTime());
      try {
         for (Object section : work.pinned.values()) {
            this.target.unpin(section);
         }

         work.pinned.clear();
      } finally {
         this.writingSince.remove(Thread.currentThread());
      }
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

      work.byPos.clear();
      for (int c = 0; c < work.count; c++) {
         work.byPos.put(Mc.key(work.columns[c].pos), work.columns[c]);
      }

      if (this.trees.anyRuns()) {
         // The features run as they are read the ground around: the ring of chunks around the tile, and the tile's chunks
         // not written, worked out too (one more launch; never written).
         batch.clear();
         for (int dx = -1; dx <= TILE && batch.size() < LodBuilder.BATCH; dx++) {
            for (int dz = -1; dz <= TILE && batch.size() < LodBuilder.BATCH; dz++) {
               long key = Mc.key(tx * TILE + dx, tz * TILE + dz);
               if (!work.byPos.containsKey(key)) {
                  batch.add(Mc.chunk(key));
               }
            }
         }

         if (!batch.isEmpty()) {
            work.intoMargin = true;
            work.marginCount = 0;
            try {
               if (worker != null) {
                  this.builder.launch(worker, stride, 0, true, work);
               } else {
                  LodBuilder.Column column = new LodBuilder.Column();
                  for (ChunkPos pos : batch) {
                     work.chunk(pos, 16 / stride, stride);
                     this.builder.cpuChunk(pos, 16 / stride, stride, column, work);
                  }
               }
            } finally {
               work.intoMargin = false;
            }

            for (int c = 0; c < work.marginCount; c++) {
               work.byPos.put(Mc.key(work.margin[c].pos), work.margin[c]);
            }
         }
      }

      this.columnNanos.add(System.nanoTime() - start);

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

         if (work.survey) {
            // /wgaccel lod trees: the trees worked out (LodTreeStats' record), nothing written into Voxy.
            this.plant(work, chunk);
            continue;
         }

         insertNanos += this.writeChunk(work, chunk, lowest, highest);
      }

      this.writeNanos.add(System.nanoTime() - writeStart - insertNanos);
      this.insertNanos.add(insertNanos);
      this.chunks.add(work.survey ? 0 : n);
      return n;
   }

   /**
    * {@code /wgaccel lod trees}: tile (tx, tz) at full detail worked out as the generator would, its trees counted
    * ({@link LodTreeStats}) for the chunks the test takes; nothing written into Voxy.
    */
   public int survey(LodBuilder.Worker worker, int tx, int tz, ChunkTest test) {
      Work work = this.work();
      work.survey = true;
      try {
         return this.nearTile(worker, tx, tz, 1, test);
      } finally {
         work.survey = false;
      }
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
      work.farStep = step;
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
                  out.biomes[col] = from.biomes[0];
                  out.soil[col] = from.soil[0];
                  out.below[col] = from.below[0];
                  System.arraycopy(from.layerStates, 0, out.layerStates, col * LodBuilder.GROUND_DEPTH, LodBuilder.GROUND_DEPTH);
                  out.belowState[col] = from.belowState[0];
                  out.fluidState[col] = wet ? from.fluidState[0] : null;
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
      @SuppressWarnings("unchecked")
      final Holder<Biome>[] biomes = new Holder[256];
      /** The ground's top block is soil a tree grows on (dirt, grass, podzol...). */
      final boolean[] soil = new boolean[256];
      final boolean[] snow = new boolean[256];
      final boolean[] ice = new boolean[256];
      /** layers[col * GROUND_DEPTH + k]: the block k under the ground. */
      final int[] layers = new int[256 * LodBuilder.GROUND_DEPTH];
      final int[] below = new int[256];
      /** Near tiles: the ground's top block and the fluid over it, for the features run as they are ({@link FeatureSandbox}). */
      final BlockState[] layerStates = new BlockState[256 * LodBuilder.GROUND_DEPTH];
      final BlockState[] belowState = new BlockState[256];
      final BlockState[] fluidState = new BlockState[256];
      int lowest;
      int highest;
   }

   /** One thread's buffers; the sink the builder hands a launch's columns to. */
   private final class Work implements LodBuilder.Sink {
      final ChunkColumns[] columns = new ChunkColumns[LodBuilder.BATCH];
      /**
       * The chunks around a near tile (and those in it not written), worked out but never written: the ground the
       * features run as they are read past the tile's edge (repeating its edge column instead made a false step there,
       * which Lithos took for a cliff: lines of rock along the tiles' edges).
       */
      final ChunkColumns[] margin = new ChunkColumns[LodBuilder.BATCH];
      boolean intoMargin;
      int marginCount;
      final Map<Long, ChunkColumns> byPos = new HashMap<>();
      /** Far tiles: the interpolated chunk being written. */
      final ChunkColumns out = new ChunkColumns();
      /** voxyStepSides: each column's lowest neighbour ground, and whether it is on for the chunk being written. */
      final int[] lowNeighbour = new int[256];
      boolean stepSides;
      /** Each tree's checks (its base, Integer.MIN_VALUE: it does not grow) and its blocks (x, y, z from its base, id, solid). */
      final Map<LodTrees.Tree, Integer> bases = new java.util.LinkedHashMap<>(4096, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<LodTrees.Tree, Integer> eldest) {
            return this.size() > 16384;
         }
      };
      final Map<LodTrees.Tree, int[]> grown = new java.util.LinkedHashMap<>(1024, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<LodTrees.Tree, int[]> eldest) {
            return this.size() > 2048;
         }
      };
      /** Voxy's world sections this thread holds while writing ({@link #pin}): the 64 used last, the eldest let go. */
      final Map<Long, Object> pinned = new java.util.LinkedHashMap<>(128, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
            if (this.size() > 64) {
               VoxyWriter.this.target.unpin(eldest.getValue());
               return true;
            }

            return false;
         }
      };
      /** {@link #survey}: the trees counted, nothing written. */
      boolean survey;
      /** voxyTrees: the chunk being written's trees, per section (y >> 4): Voxy block ids, 0 none; spare arrays. */
      final Map<Integer, int[]> trees = new HashMap<>();
      final ArrayDeque<int[]> spare = new ArrayDeque<>();
      @SuppressWarnings("unchecked")
      final Holder<Biome>[] seen = new Holder[16];
      int treeTop;
      /** The features run as they are over the chunk being written (from its biomes and its neighbours'). */
      final List<LodTrees.Run> runs = new ArrayList<>();
      /** The level a feature run as it is reads: the chunk being written's columns and the tile's around it. */
      final Around around = new Around();
      /**
       * The plants placed before the trees over the 3 x 3 chunks around the one written (48 x 48, x then z): 0 none, 1 one a
       * tree replaces (grass), 2 one it cannot (pink petals...).
       */
      final byte[] covered = new byte[48 * 48];
      /** Each column's highest leaves (Integer.MIN_VALUE: none): snow goes on them where the ground is snowy. */
      final int[] leafTop = new int[256];
      /** Each column's highest AronaLayers layer (Integer.MIN_VALUE: none): a plant there stands on it, a block higher. */
      final int[] layerTop = new int[256];
      /**
       * The trees standing so far around the chunk being written (its 3 x 3 chunks, taken in order of x then z, the same
       * order whichever of them is written): trunk x, z and crown radius.
       */
      final int[] standing = new int[3 * 2048];
      int standingCount;
      final int snowId;
      final int iceId;
      Object section;
      int count;
      /** The launch's column spacing (a column fills stride x stride block columns). */
      int stride;
      /** Far tiles: the chunks between two sample columns (the ring's stride / 16). */
      int farStep = 1;
      ChunkColumns current;

      Work() {
         for (int i = 0; i < this.columns.length; i++) {
            this.columns[i] = new ChunkColumns();
            this.margin[i] = new ChunkColumns();
         }

         this.snowId = VoxyWriter.this.target.block(LodBuilder.snow());
         this.iceId = VoxyWriter.this.target.block(Blocks.ICE.defaultBlockState());
      }

      /** The columns around the chunk being written (as {@link #groundAt}: outside the tile, its nearest edge column). */
      final class Around implements FeatureSandbox.Columns {
         ChunkColumns chunk;
         int x0;
         int z0;
         private ChunkColumns found;

         private int find(int x, int z) {
            if (x >= this.x0 && x < this.x0 + 16 && z >= this.z0 && z < this.z0 + 16) {
               this.found = this.chunk;
               return (z - this.z0) << 4 | x - this.x0;
            }

            ChunkColumns other = this.chunk == Work.this.out ? null : Work.this.byPos.get(Mc.key(x >> 4, z >> 4));
            if (other != null) {
               this.found = other;
               return (z & 15) << 4 | x & 15;
            }

            this.found = this.chunk;
            return Math.clamp(z - this.z0, 0, 15) << 4 | Math.clamp(x - this.x0, 0, 15);
         }

         @Override
         public int ground(int x, int z) {
            int col = this.find(x, z);
            return this.found.ground[col];
         }

         @Override
         public int surface(int x, int z) {
            int col = this.find(x, z);
            return this.found.surface[col];
         }

         @Override
         public BlockState layer(int x, int z, int k) {
            int col = this.find(x, z);
            return k < LodBuilder.GROUND_DEPTH ? this.found.layerStates[col * LodBuilder.GROUND_DEPTH + k] : this.found.belowState[col];
         }

         @Override
         public BlockState fluid(int x, int z) {
            int col = this.find(x, z);
            return this.found.fluidState[col];
         }

         @Override
         public Holder<Biome> biome(int x, int z) {
            int col = this.find(x, z);
            return this.found.biomes[col];
         }
      }

      @Override
      public void chunk(ChunkPos pos, int n, int fillStride) {
         ChunkColumns chunk = this.intoMargin ? this.margin[this.marginCount++] : this.columns[this.count++];
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
         BlockState top = c.layers == null ? c.groundTop : c.layers[0];
         boolean soil = top != null && top.is(BlockTags.DIRT);
         int s = this.stride;
         int at = -1;
         for (int x = i * s; x < Math.min(16, i * s + s); x++) {
            for (int z = j * s; z < Math.min(16, j * s + s); z++) {
               int col = z << 4 | x;
               chunk.ground[col] = ground;
               chunk.surface[col] = surface;
               chunk.fluid[col] = fluid;
               chunk.biome[col] = biome;
               chunk.biomes[col] = c.biome;
               chunk.soil[col] = soil;
               chunk.snow[col] = c.snow;
               chunk.ice[col] = c.ice;
               chunk.below[col] = below;
               for (int k = 0; k < LodBuilder.GROUND_DEPTH; k++) {
                  chunk.layerStates[col * LodBuilder.GROUND_DEPTH + k] = c.layers == null ? c.groundTop : c.layers[k];
               }

               chunk.belowState[col] = c.below;
               chunk.fluidState[col] = surface > ground ? c.top : null;
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
      shape(chunk, work);
      long trees = System.nanoTime();
      this.plant(work, chunk);
      if (work.treeTop != Integer.MIN_VALUE) {
         this.snowOnTrees(work, chunk);
      }

      this.treeNanos.add(System.nanoTime() - trees);
      int topSection = (this.minY + this.height - 1) >> 4;
      int from = Math.max(this.minY, lowest - UNDER) >> 4;
      int to = Math.min(topSection, (Math.max(Math.max(highest, chunk.highest), work.treeTop) >> 4) + 1);
      if (work.section == null) {
         work.section = this.target.section(cx, from, cz);
      }

      long insertNanos = 0;
      long[] data = this.target.data(work.section);
      this.enter();
      try {
         for (int sy = from; sy <= to; sy++) {
            this.pin(work, cx, sy, cz);
            int nonAir = fill(chunk, work, data, sy << 4, work.trees.get(sy));
            long insert = System.nanoTime();
            this.target.insert(work.section, cx, sy, cz, nonAir);
            insertNanos += System.nanoTime() - insert;
            this.sections.increment();
         }
      } finally {
         this.leave();
      }

      return insertNanos;
   }

   /**
    * The moire settings, before a chunk is written: voxyHeightDither moves each dry column's ground by a random -1, 0 or
    * +1 (the same for a column every time); voxyStepSides works out each column's lowest neighbour ground (inside the
    * chunk; at its edge the column's own) for {@link #fill}.
    */
   private static void shape(ChunkColumns chunk, Work work) {
      Config.VoxyDither dither = Config.VOXY_HEIGHT_DITHER.get();
      if (dither == Config.VoxyDither.ALL || dither == Config.VoxyDither.FAR && work.stride >= 16 && chunk == work.out) {
         int x0 = Mc.x(chunk.pos) * 16;
         int z0 = Mc.z(chunk.pos) * 16;
         for (int col = 0; col < 256; col++) {
            if (chunk.surface[col] != chunk.ground[col]) {
               continue;
            }

            int step = (int)(random(x0 + (col & 15) + 0x2545F491, z0 + (col >> 4)) * 3.0) - 1;
            chunk.ground[col] += step;
            chunk.surface[col] += step;
         }
      }

      work.stepSides = Config.VOXY_STEP_SIDES.get();
      if (work.stepSides) {
         for (int col = 0; col < 256; col++) {
            int x = col & 15;
            int z = col >> 4;
            int own = chunk.ground[col];
            int low = own;
            low = Math.min(low, x > 0 ? chunk.ground[col - 1] : own);
            low = Math.min(low, x < 15 ? chunk.ground[col + 1] : own);
            low = Math.min(low, z > 0 ? chunk.ground[col - 16] : own);
            low = Math.min(low, z < 15 ? chunk.ground[col + 16] : own);
            work.lowNeighbour[col] = low;
         }
      }
   }

   /**
    * voxyTrees: the trees over the chunk (its own and its neighbours' within {@link LodTrees#REACH}) into work.trees. A
    * tree stands where its biome is the feature's and the ground is dry soil; a neighbour's column comes from the tile
    * when it is in it, else the chunk's nearest edge column.
    */
   private void plant(Work work, ChunkColumns chunk) {
      for (Map.Entry<Integer, int[]> entry : work.trees.entrySet()) {
         Arrays.fill(entry.getValue(), 0);
         work.spare.add(entry.getValue());
      }

      work.trees.clear();
      work.treeTop = Integer.MIN_VALUE;
      Arrays.fill(work.leafTop, Integer.MIN_VALUE);
      Arrays.fill(work.layerTop, Integer.MIN_VALUE);
      Config.VoxyFeature mode = Config.VOXY_TREES.get();
      if (mode == Config.VoxyFeature.OFF || mode == Config.VoxyFeature.NEAR && chunk == work.out) {
         // Lithos may still be on.
         this.ran(work, chunk);
         return;
      }

      int cx = Mc.x(chunk.pos);
      int cz = Mc.z(chunk.pos);
      int x0 = cx * 16;
      int z0 = cz * 16;
      work.standingCount = 0;
      Arrays.fill(work.covered, (byte)0);
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            ChunkColumns other = dx == 0 && dz == 0 ? chunk : chunk == work.out ? null : work.byPos.get(Mc.key(cx + dx, cz + dz));
            int n = biomes(chunk, work.seen, 0);
            n = other != null && other != chunk ? biomes(other, work.seen, n) : n;
            for (int b = 0; b < n; b++) {
               this.trees.covers(work.seen[b], cx + dx, cz + dz, (x, z, dy, px, pz, blocking) -> {
                  int i = x - (x0 - 16);
                  int k = z - (z0 - 16);
                  // Takes only on free ground just under the try's height (the patch centre's ground + 1 + dy).
                  if (i >= 0 && i < 48 && k >= 0 && k < 48 && work.covered[i * 48 + k] == 0
                     && groundAt(work, chunk, x0, z0, px, pz) + dy == groundAt(work, chunk, x0, z0, x, z)) {
                     work.covered[i * 48 + k] = (byte)(blocking ? 2 : 1);
                  }
               });
            }
         }
      }

      int[] record = LodTreeStats.chunk(Mc.key(cx, cz));
      if (record != null) {
         for (int col = 0; col < 256; col++) {
            if (chunk.surface[col] == chunk.ground[col]) {
               record[1]++;
               record[2] += chunk.soil[col] ? 1 : 0;
            }
         }
      }

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            ChunkColumns other = dx == 0 && dz == 0 ? chunk : chunk == work.out ? null : work.byPos.get(Mc.key(cx + dx, cz + dz));
            int n = biomes(chunk, work.seen, 0);
            n = other != null && other != chunk ? biomes(other, work.seen, n) : n;
            // The same order whichever chunk is written: which tree stands under which depends on it.
            Arrays.sort(work.seen, 0, n, java.util.Comparator.comparingInt(System::identityHashCode));
            for (int b = 0; b < n; b++) {
               Holder<Biome> biome = work.seen[b];
               trees:
               for (LodTrees.Tree tree : this.trees.trees(biome, cx + dx, cz + dz)) {
                  int x = tree.x();
                  int z = tree.z();
                  LodTrees.Kind kind = tree.kind();
                  int height = tree.height();
                  int radius = tree.radius();
                  int crown = tree.crown();
                  // Every tree of the 3 x 3 chunks is looked at (a far one can still stand over a near one's spot);
                  // only those within reach are drawn.
                  boolean near = x >= x0 - LodTrees.REACH && x < x0 + 16 + LodTrees.REACH && z >= z0 - LodTrees.REACH
                     && z < z0 + 16 + LodTrees.REACH;
                  boolean inside = x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
                  // Its spot's checks, once per tree on this thread (each of the 3 x 3 chunks around asks): its base, or
                  // none when it does not grow.
                  Integer known = work.bases.get(tree);
                  int base;
                  if (known != null) {
                     base = known;
                  } else {
                     base = Integer.MIN_VALUE;
                     ChunkColumns at = inside ? chunk : chunk == work.out ? null : work.byPos.get(Mc.key(x >> 4, z >> 4));
                     int col;
                     if (at == null) {
                        at = chunk;
                        col = Math.clamp(z - z0, 0, 15) << 4 | Math.clamp(x - x0, 0, 15);
                     } else {
                        col = (z & 15) << 4 | x & 15;
                     }

                     // Ground cover a tree cannot replace on its spot (pink petals): the room check fails at its base.
                     int ci = x - (x0 - 16);
                     int ck = z - (z0 - 16);
                     boolean covered = kind.config != null && ci >= 0 && ci < 48 && ck >= 0 && ck < 48 && work.covered[ci * 48 + ck] == 2;
                     // Room to grow (TreeFeature's free height: the ground around the trunk, uphill on a slope, takes it).
                     if (at.biomes[col] == biome && at.soil[col] && at.surface[col] == at.ground[col] && !covered
                        && this.trees.fits(kind, height, x, z, at.ground[col] + 1, (gx, gz) -> groundAt(work, chunk, x0, z0, gx, gz))) {
                        base = at.ground[col] + 1;
                     }

                     work.bases.put(tree, base);
                  }

                  if (base == Integer.MIN_VALUE) {
                     continue trees;
                  }

                  // A spot under an earlier tree's crown: the game's heightmap finds its leaves there, a sapling does
                  // not grow on them (dense forests lose a good share; cherry groves most: crowns 9 blocks across).
                  // Earlier: in this chunk, or in the chunks before it in x, z order.
                  for (int t = 0; t < work.standingCount; t++) {
                     int ex = x - work.standing[t * 3];
                     int ez = z - work.standing[t * 3 + 1];
                     int er = work.standing[t * 3 + 2];
                     if (ex * ex + ez * ez <= er * er) {
                        continue trees;
                     }
                  }

                  if (work.standingCount < 2048) {
                     work.standing[work.standingCount * 3] = x;
                     work.standing[work.standingCount * 3 + 1] = z;
                     work.standing[work.standingCount * 3 + 2] = kind.mushroom != 0 ? radius : kind.footprint;
                     work.standingCount++;
                  }

                  if (!near) {
                     continue trees;
                  }

                  if (inside && record != null) {
                     record[0]++;
                  }

                  // Its blocks, grown once per tree on this thread (a tree over a chunk edge is drawn by each chunk it reaches).
                  int[] blocks = work.grown.get(tree);
                  if (blocks == null) {
                     int[][] list = {new int[64 * 5]};
                     int[] count = {0};
                     int bx0 = x;
                     int by0 = base;
                     int bz0 = z;
                     this.trees.grow(kind, x, base, z, height, radius, crown, new net.minecraft.world.level.levelgen.LegacyRandomSource(tree.seed()),
                        (bx, by, bz, id, solid) -> {
                           if (count[0] + 5 > list[0].length) {
                              list[0] = Arrays.copyOf(list[0], list[0].length * 2);
                           }

                           int[] a = list[0];
                           a[count[0]++] = bx - bx0;
                           a[count[0]++] = by - by0;
                           a[count[0]++] = bz - bz0;
                           a[count[0]++] = id;
                           a[count[0]++] = solid ? 1 : 0;
                        });
                     blocks = Arrays.copyOf(list[0], count[0]);
                     work.grown.put(tree, blocks);
                  }

                  for (int i = 0; i < blocks.length; i += 5) {
                     put(work, chunk, x0, z0, x + blocks[i], base + blocks[i + 1], z + blocks[i + 2], blocks[i + 3], blocks[i + 4] != 0);
                  }
               }
            }
         }
      }

      this.ran(work, chunk);
   }

   /**
    * The features run as they are ({@link FeatureSandbox}): Conquest of Arborea's forests (voxyTrees) and Lithos (voxyLithos).
    * Near tiles: all of them together in the 3 x 3 chunks around the chunk being written (Lithos's rock under Arborea's
    * trees, as in the game), their blocks over this chunk. Far tiles (ALL): the trees of the chunk's own run only (a crown
    * over a chunk edge is cut, less than a far voxel); Lithos once per stride x stride blocks (a run costs what the game's
    * does), its rock and ground cover laid on each chunk around from its own ground up.
    */
   private void ran(Work work, ChunkColumns chunk) {
      boolean far = chunk == work.out;
      Config.VoxyFeature treeMode = Config.VOXY_TREES.get();
      Config.VoxyFeature lithosMode = Config.VOXY_LITHOS.get();
      boolean trees = treeMode == Config.VoxyFeature.ALL || treeMode == Config.VoxyFeature.NEAR && !far;
      boolean lithos = lithosMode == Config.VoxyFeature.ALL || lithosMode == Config.VoxyFeature.NEAR && !far;
      Config.VoxyFeature plantMode = Config.VOXY_PLANTS.get();
      boolean plants = plantMode == Config.VoxyFeature.ALL || plantMode == Config.VoxyFeature.NEAR && !far;
      Config.VoxyFeature layerMode = Config.VOXY_ARONA_LAYERS.get();
      boolean layers = (layerMode == Config.VoxyFeature.ALL || layerMode == Config.VoxyFeature.NEAR && !far) && LodLayers.available();
      if (!trees && !lithos && !plants && !layers) {
         return;
      }

      int cx = Mc.x(chunk.pos);
      int cz = Mc.z(chunk.pos);
      int x0 = cx * 16;
      int z0 = cz * 16;
      work.around.chunk = chunk;
      work.around.x0 = x0;
      work.around.z0 = z0;
      if (layers) {
         // AronaLayers' layers on this chunk's own columns (from the ground the LODs have, before Lithos's rock goes into
         // its layers); into empty cells only, under any tree.
         this.layered(work, chunk, x0, z0, this.layers.run(cx, cz, work.around));
      }

      if (!trees && !lithos && !plants) {
         return;
      }
      if (!far) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               ChunkColumns other = dx == 0 && dz == 0 ? chunk : work.byPos.get(Mc.key(cx + dx, cz + dz));
               // The chunk's own biomes (a neighbour outside the tile: this chunk's, as for the trees above).
               this.runsOf(work, other != null ? other : chunk, trees, lithos, plants);
               if (!work.runs.isEmpty()) {
                  int[] blocks = this.trees.ran(List.copyOf(work.runs), cx + dx, cz + dz, Mc.key(cx + dx, cz + dz),
                     known(work, cx + dx, cz + dz) ? LodTrees.COMPLETE : LodTrees.PARTIAL, work.around);
                  this.lay(work, chunk, (cx + dx) * 16, (cz + dz) * 16, blocks);
               }
            }
         }

         return;
      }

      if (trees) {
         this.runsOf(work, chunk, true, false, false);
         if (!work.runs.isEmpty()) {
            this.lay(work, chunk, x0, z0, this.trees.ran(List.copyOf(work.runs), cx, cz, Mc.key(cx, cz), LodTrees.PARTIAL, work.around));
         }
      }

      if (lithos || plants) {
         // Lithos's rock and the plants on it, once per sample.
         this.runsOf(work, chunk, false, lithos, plants);
         if (!work.runs.isEmpty()) {
            // One run per stride x stride blocks (its first chunk written on this thread stands for the others).
            int every = Math.max(1, work.farStep);
            long key = Mc.key(Math.floorDiv(cx, every), Math.floorDiv(cz, every));
            this.lay(work, chunk, x0, z0, this.trees.ran(List.copyOf(work.runs), cx, cz, key, LodTrees.SAMPLED, work.around));
         }
      }
   }

   /** AronaLayers' blocks ({@link LodLayers#run}) on the chunk: over the ground into empty cells, at or under it into its layers. */
   private void layered(Work work, ChunkColumns chunk, int x0, int z0, int[] blocks) {
      for (int i = 0; i < blocks.length; i += 5) {
         int col = blocks[i + 2] << 4 | blocks[i];
         int dy = blocks[i + 1];
         int id = blocks[i + 3];
         if (dy <= 0) {
            if (-dy < LodBuilder.GROUND_DEPTH) {
               chunk.layers[col * LodBuilder.GROUND_DEPTH - dy] = id;
            }

            continue;
         }

         empty(work, chunk, col, chunk.ground[col] + dy, id);
         work.layerTop[col] = Math.max(work.layerTop[col], chunk.ground[col] + dy);
      }
   }

   /** A block over the column's surface into an empty cell only (AronaLayers' layers, plants: under trees and rock; no snow on them). */
   private static void empty(Work work, ChunkColumns chunk, int col, int y, int id) {
      if (y <= chunk.surface[col]) {
         return;
      }

      int[] section = work.trees.get(y >> 4);
      if (section == null) {
         section = work.spare.isEmpty() ? new int[4096] : work.spare.poll();
         work.trees.put(y >> 4, section);
      }

      int at = (y & 15) << 8 | col;
      if (section[at] == 0) {
         section[at] = id;
         work.treeTop = Math.max(work.treeTop, y);
      }
   }

   /** Chunk (cx, cz) and the 8 around it all worked out on this tile (its run then reads no guessed ground). */
   private static boolean known(Work work, int cx, int cz) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (!work.byPos.containsKey(Mc.key(cx + dx, cz + dz))) {
               return false;
            }
         }
      }

      return true;
   }

   /** The chunk's biomes' runs into work.runs (those of the kinds asked), in the game's order. */
   private void runsOf(Work work, ChunkColumns chunk, boolean trees, boolean lithos, boolean plants) {
      int n = biomes(chunk, work.seen, 0);
      work.runs.clear();
      for (int b = 0; b < n; b++) {
         for (LodTrees.Run run : this.trees.runs(work.seen[b])) {
            boolean on = switch (run.kind()) {
               case LodTrees.LITHOS -> lithos;
               case LodTrees.PLANTS -> plants;
               default -> trees;
            };
            if (on && LodTrees.enabled(run) && !work.runs.contains(run)) {
               work.runs.add(run);
            }
         }
      }

      work.runs.sort(LodTrees.ORDER);
   }

   /**
    * A run's blocks ({@link LodTrees#ran}: from the corner (ox, oz), y from each column's ground) on this chunk: over the
    * ground as trees are; at or under it into the ground's layers (Lithos's rock).
    */
   private void lay(Work work, ChunkColumns chunk, int ox, int oz, int[] blocks) {
      int x0 = Mc.x(chunk.pos) * 16;
      int z0 = Mc.z(chunk.pos) * 16;
      for (int i = 0; i < blocks.length; i += 5) {
         int lx = ox + blocks[i] - x0;
         int lz = oz + blocks[i + 2] - z0;
         if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
            continue;
         }

         int col = lz << 4 | lx;
         int dy = blocks[i + 1];
         if (dy > 0 && blocks[i + 4] == 2) {
            // On an AronaLayers layer: a block higher, as AronaLayers keeps the plant (LSL lowers it onto the layer).
            int y = chunk.ground[col] + dy;
            empty(work, chunk, col, y <= work.layerTop[col] ? y + 1 : y, blocks[i + 3]);
         } else if (dy > 0) {
            put(work, chunk, x0, z0, x0 + lx, chunk.ground[col] + dy, z0 + lz, blocks[i + 3], blocks[i + 4] != 0);
         } else if (-dy < LodBuilder.GROUND_DEPTH) {
            chunk.layers[col * LodBuilder.GROUND_DEPTH - dy] = blocks[i + 3];
         }
      }
   }

   /** Snow on the highest leaves of each column whose ground is snowy (the game's freeze step puts it on the top block). */
   private void snowOnTrees(Work work, ChunkColumns chunk) {
      int x0 = Mc.x(chunk.pos) * 16;
      int z0 = Mc.z(chunk.pos) * 16;
      for (int col = 0; col < 256; col++) {
         int top = work.leafTop[col];
         if (top != Integer.MIN_VALUE && chunk.snow[col]) {
            put(work, chunk, x0, z0, x0 + (col & 15), top + 1, z0 + (col >> 4), work.snowId, false);
         }
      }
   }

   /** The ground at block column (x, z): the chunk's, a tile neighbour's, else the chunk's nearest edge column. */
   private static int groundAt(Work work, ChunkColumns chunk, int x0, int z0, int x, int z) {
      if (x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16) {
         return chunk.ground[(z - z0) << 4 | x - x0];
      }

      ChunkColumns other = chunk == work.out ? null : work.byPos.get(Mc.key(x >> 4, z >> 4));
      return other != null ? other.ground[(z & 15) << 4 | x & 15]
         : chunk.ground[Math.clamp(z - z0, 0, 15) << 4 | Math.clamp(x - x0, 0, 15)];
   }

   /** The distinct biomes of the chunk's columns added to seen (at most its length); returns the count. */
   private static int biomes(ChunkColumns chunk, Holder<Biome>[] seen, int n) {
      outer:
      for (int col = 0; col < 256 && n < seen.length; col++) {
         Holder<Biome> biome = chunk.biomes[col];
         if (biome == null) {
            continue;
         }

         for (int i = 0; i < n; i++) {
            if (seen[i] == biome) {
               continue outer;
            }
         }

         seen[n++] = biome;
      }

      return n;
   }

   private static void put(Work work, ChunkColumns chunk, int x0, int z0, int x, int y, int z, int block, boolean over) {
      int lx = x - x0;
      int lz = z - z0;
      if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
         return;
      }

      int col = lz << 4 | lx;
      if (y <= chunk.surface[col]) {
         return;
      }

      int[] section = work.trees.get(y >> 4);
      if (section == null) {
         section = work.spare.isEmpty() ? new int[4096] : work.spare.poll();
         work.trees.put(y >> 4, section);
      }

      int i = (y & 15) << 8 | col;
      if (over || section[i] == 0) {
         section[i] = block;
         work.treeTop = Math.max(work.treeTop, y);
         if (!over) {
            work.leafTop[col] = Math.max(work.leafTop[col], y);
         }
      }
   }

   /** The section at block y baseY: its 4,096 ids (trees: voxyTrees' blocks, null none); returns its non-air count. */
   private static int fill(ChunkColumns chunk, Work work, long[] data, int baseY, int[] trees) {
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
            int tree = y > surface && trees != null ? trees[ly << 8 | col] : 0;
            if (tree != 0) {
               id = VoxyAccess.id(SKY, tree, biome);
            } else if (y > surface) {
               id = snow && y == surface + 1 ? VoxyAccess.id(SKY, work.snowId, biome) : sky;
            } else if (y > ground) {
               // Water dims sky light by one a block.
               id = VoxyAccess.id(Math.max(0, SKY - (surface - y)), ice && y == surface ? work.iceId : chunk.fluid[col], biome);
            } else {
               int k = ground - y;
               int block = k < LodBuilder.GROUND_DEPTH ? chunk.layers[base + k] : chunk.below[col];
               if (k > 0 && work.stepSides && surface == ground && y > work.lowNeighbour[col]) {
                  // A block a step shows on its side: the surface's (snow when snowed on).
                  block = snow ? work.snowId : chunk.layers[base];
               }

               id = VoxyAccess.id(groundLight, block, biome);
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
