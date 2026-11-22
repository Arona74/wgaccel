package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.FillGpu;
import com.wgaccel.gpu.SurfaceGpu;
import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The LOD columns of a level's chunks, for the LOD generators (Distant Horizons' {@link DhLodGenerator}, Voxy's
 * {@link VoxyLodGenerator}); nothing here depends on either mod. A thread's chunks are filled on the GPU by the noise
 * fill's stand-ins ({@link LodColumns}) in batches, their surface made by the GPU surface ({@link LodSurface}), with
 * FreeTerraForged's river water and feature-step ground (scree, snow) and vanilla's snow and ice on top; chunks whose
 * stand-in fails come from the game's getBaseHeight. Each column goes to a {@link Sink}.
 */
public final class LodBuilder {
   /** Chunks per GPU launch. */
   public static final int BATCH = 64;
   /**
    * The ground layers a surfaced column is made with (the surface's blocks, then the default block): what cliffs show.
    * 32, as deep as FreeTerraForged's scree looks for rock under a steep slope.
    */
   public static final int GROUND_DEPTH = 32;
   /** How long the first requests wait for the GPU fill's program (it is made while the world loads). */
   private static final long WAIT_NANOS = 60_000_000_000L;
   private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();

   final ServerLevel level;
   final int minY;
   final int height;
   private final String name;
   private final long waitUntil = System.nanoTime() + WAIT_NANOS;
   private final ThreadLocal<Worker> workers = new ThreadLocal<>();
   final LongAdder cpuColumns = new LongAdder();
   final LongAdder fillInputNanos = new LongAdder();
   final LongAdder fillNanos = new LongAdder();
   final LongAdder surfaceInputNanos = new LongAdder();
   final LongAdder surfaceGpuNanos = new LongAdder();
   final LongAdder surfacedChunks = new LongAdder();
   final LongAdder bareChunks = new LongAdder();
   private final AtomicInteger surfaceFailures = new AtomicInteger();
   private volatile com.wgaccel.ftf.FtfLodDecor decor;
   private volatile boolean decorTried;

   /** @param name the generator's name in log lines */
   public LodBuilder(ServerLevel level, int minY, int height, String name) {
      this.level = level;
      this.minY = minY;
      this.height = height;
      this.name = name;
   }

   /**
    * One column, handed to the sink (reused: copy what is kept). Heights are block y; {@link LodColumns#NONE} when the
    * column has none. A surfaced column has {@link #layers}; a bare one (no surface program, or a chunk from the CPU) has
    * {@link #groundTop} for its whole ground instead.
    */
   public static final class Column {
      public int x;
      public int z;
      /** The top of the ground (the highest block that is not air or fluid). */
      public int ground;
      /** The top of the column (ground, or the fluid above it). */
      public int surface;
      /** The block at the surface (the fluid's when there is water, else the ground's). */
      public BlockState top;
      /** A bare column's ground block. */
      public BlockState groundTop;
      /** A surfaced column's ground, layers[k] at ground - k (GROUND_DEPTH of them, no air or fluid); null when bare. */
      public BlockState[] layers;
      /** The block under the layers (the generator's default block). */
      public BlockState below;
      /** One block of snow on the ground (surface == ground then), one block of ice at the water's top. */
      public boolean snow;
      public boolean ice;
      public Holder<Biome> biome;
   }

   /** Where a launch's columns go. */
   public interface Sink {
      /** A chunk's columns follow: n x n of them, fillStride blocks apart (all its columns when 1). */
      default void chunk(ChunkPos pos, int n, int fillStride) {
      }

      /** Column (i, j) of the chunk last started: block x = min x + i * fillStride, z likewise. */
      void column(ChunkPos pos, int i, int j, Column column);
   }

   /** One thread's GPU stand-ins and lists. */
   public static final class Worker {
      final GpuNoiseFill.StandIns standIns;
      final boolean[] groundTest = new boolean[16];
      final boolean[] surfaceTest = new boolean[16];
      /** The chunks of the next launch; the caller adds them. */
      public final List<ChunkPos> batch = new ArrayList<>(BATCH);
      final List<ChunkPos> done = new ArrayList<>(BATCH);
      final List<FillGpu.Job> jobs = new ArrayList<>(BATCH);
      final List<byte[]> codes = new ArrayList<>(BATCH);
      final BlockState[] layers = new BlockState[GROUND_DEPTH];
      final SurfaceGpu.Job[] surfaceJobs = new SurfaceGpu.Job[BATCH];
      final Column column = new Column();

      Worker(GpuNoiseFill.StandIns standIns) {
         this.standIns = standIns;
         BlockState[] table = standIns.table();
         for (int i = 0; i < table.length; i++) {
            this.groundTest[i] = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(table[i]);
            this.surfaceTest[i] = Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(table[i]);
         }
      }
   }

   /** This thread's worker; null while the GPU fill is not ready (waiting for it until a minute after the start). */
   public Worker worker(BooleanSupplier closed) {
      Worker worker = this.workers.get();
      if (worker != null && worker.standIns.level() == this.level) {
         return worker;
      }

      while (true) {
         GpuNoiseFill.StandIns standIns = GpuNoiseFill.StandIns.create(BATCH);
         if (standIns != null && standIns.level() == this.level) {
            worker = new Worker(standIns);
            this.workers.set(worker);
            return worker;
         }

         if (closed.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("the world is closing");
         }

         if (System.nanoTime() > this.waitUntil) {
            return null;
         }

         try {
            Thread.sleep(250L);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
         }
      }
   }

   /**
    * The worker's batch filled on the GPU and its columns given to the sink, chunk by chunk in the batch's order; chunks
    * whose stand-in failed from the CPU. The batch is cleared.
    *
    * @param fillStride the columns' spacing in a chunk (1-16)
    * @param fastStride FreeTerraForged's fast stand-ins at this spacing (FtfLodCells), 0 for exact ones
    * @param features the feature step's ground (scree, snow, ice) too
    */
   public void launch(Worker worker, int fillStride, int fastStride, boolean features, Sink sink) {
      long start = System.nanoTime();
      worker.done.clear();
      worker.jobs.clear();
      worker.codes.clear();
      GpuSurface.Program surfaceProgram = LodSurface.program(worker.standIns.random());
      worker.standIns.prelims = surfaceProgram != null;
      worker.standIns.fastStride = fastStride;
      worker.standIns.prepare(worker.batch, fillStride, worker.done, worker.jobs, worker.codes);
      long prepared = System.nanoTime();
      this.fillInputNanos.add(prepared - start);
      // Each thread launches its own: the threads' launches overlap on the GPU. Merged into one launch (tried 19 Nov
      // 2026), they were slower: detail 0 1,723 chunks of area/s against 2,409-2,537 apart.
      worker.standIns.launch(worker.jobs, worker.codes);

      this.fillNanos.add(System.nanoTime() - prepared);
      int n = 16 / fillStride;
      BlockState[] table = worker.standIns.table();
      this.riverWater(worker, surfaceProgram);
      short[][] outs = this.surfaces(worker, surfaceProgram);
      com.wgaccel.ftf.FtfLodDecor decor = surfaceProgram == null || !features ? null : this.decor();
      int seaLevel = this.level.getChunkSource().getGenerator().getSeaLevel();
      Column c = worker.column;
      int at = 0;
      for (ChunkPos pos : worker.batch) {
         sink.chunk(pos, n, fillStride);
         if (!(at < worker.done.size() && worker.done.get(at).equals(pos))) {
            this.cpuChunk(pos, n, fillStride, c, sink);
            continue;
         }

         LodColumns.Columns columns = LodColumns.reduce(pos, worker.jobs.get(at), worker.codes.get(at), table, worker.groundTest, worker.surfaceTest);
         short[] out = outs == null ? null : outs[at];
         SurfaceGpu.Job surfaceJob = out == null ? null : worker.surfaceJobs[at];
         Object[] cells = out == null || decor == null ? null : decor.cells(worker.standIns.proto(at));
         at++;
         for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
               int column = i * n + j;
               c.x = pos.getMinBlockX() + i * fillStride;
               c.z = pos.getMinBlockZ() + j * fillStride;
               c.ground = columns.ground()[column];
               c.surface = columns.surface()[column];
               c.snow = false;
               c.ice = false;
               if (out == null) {
                  c.top = columns.top()[column];
                  c.groundTop = columns.groundTop()[column];
                  c.layers = null;
                  c.below = c.groundTop;
                  c.biome = this.biomeAt(c.x, c.surface == LodColumns.NONE ? this.minY : c.surface, c.z);
                  sink.column(pos, i, j, c);
                  continue;
               }

               // The surface's blocks down from the column's ground (its layers, which cliffs show), and the block at
               // its top (the same block without water).
               int t = (i * fillStride) * 16 + j * fillStride;
               BlockState[] layers = worker.layers;
               BlockState fallback = columns.groundTop()[column];
               for (int k = 0; k < GROUND_DEPTH; k++) {
                  BlockState state = c.ground == LodColumns.NONE ? surfaceProgram.defaultBlock()
                     : surfaced(surfaceProgram, surfaceJob, out, c.ground - k, t, k == 0 ? fallback : surfaceProgram.defaultBlock());
                  layers[k] = state.isAir() || !state.getFluidState().isEmpty() ? surfaceProgram.defaultBlock() : state;
               }

               c.top = surfaced(surfaceProgram, surfaceJob, out, c.surface, t, columns.top()[column]);
               c.biome = this.biomeAt(c.x, c.surface == LodColumns.NONE ? this.minY : c.surface, c.z);
               Object cell = cells == null ? null : cells[(j * fillStride) * 16 + i * fillStride];
               boolean dry = c.ground != LodColumns.NONE && c.surface == c.ground;
               if (dry && cell != null) {
                  decor.scree(cell, c.x, c.z, c.ground, c.biome, layers, surfaceProgram.defaultBlock());
               }

               // The top layer's snow and ice (vanilla's freeze_top_layer; FreeTerraForged's snow decorator takes the
               // snow off steep cold ground).
               if (dry && features) {
                  c.snow = c.ground + 1 < this.minY + this.height
                     && Mc.coldEnoughToSnow(c.biome.value(), new net.minecraft.core.BlockPos(c.x, c.ground + 1, c.z), seaLevel) && holdsSnow(layers[0])
                     && (cell == null || !decor.snowEroded(cell, c.x, c.z, c.ground, c.biome));
                  if (c.snow && cell != null) {
                     // (FreeTerraForged's columns only: its class is not touched without it.)
                     com.wgaccel.ftf.FtfLodDecor.SNOW_LAYERS.increment();
                  }
               } else if (features && c.surface != LodColumns.NONE && c.top.is(Blocks.WATER)) {
                  c.ice = Mc.coldEnoughToSnow(c.biome.value(), new net.minecraft.core.BlockPos(c.x, c.surface, c.z), seaLevel);
               }

               c.layers = layers;
               c.groundTop = layers[0];
               c.below = surfaceProgram.defaultBlock();
               sink.column(pos, i, j, c);
            }
         }
      }

      worker.batch.clear();
   }

   /** The chunks' columns from the game's getBaseHeight (the ground block the generator's default block, water its fluid). */
   public void cpuChunk(ChunkPos pos, int n, int fillStride, Column c, Sink sink) {
      ChunkGenerator generator = this.level.getChunkSource().getGenerator();
      RandomState random = this.level.getChunkSource().randomState();
      BlockState block = Blocks.STONE.defaultBlockState();
      BlockState fluid = Blocks.WATER.defaultBlockState();
      if (generator instanceof NoiseBasedChunkGenerator noise) {
         block = noise.generatorSettings().value().defaultBlock();
         fluid = noise.generatorSettings().value().defaultFluid();
      }

      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            c.x = pos.getMinBlockX() + i * fillStride;
            c.z = pos.getMinBlockZ() + j * fillStride;
            c.ground = generator.getBaseHeight(c.x, c.z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, random) - 1;
            c.surface = generator.getBaseHeight(c.x, c.z, Heightmap.Types.WORLD_SURFACE_WG, this.level, random) - 1;
            c.top = c.surface > c.ground ? fluid : block;
            c.groundTop = block;
            c.below = block;
            c.layers = null;
            c.snow = false;
            c.ice = false;
            c.biome = this.biomeAt(c.x, c.surface, c.z);
            sink.column(pos, i, j, c);
            this.cpuColumns.increment();
         }
      }
   }

   /**
    * The surface's blocks for the launch's filled chunks (index for index with worker.done; a null entry: that chunk's
    * surface stays the noise fill's, as when the surface program is not there), or null for none.
    */
   private short[][] surfaces(Worker worker, GpuSurface.Program p) {
      if (p == null || worker.done.isEmpty()) {
         this.bareChunks.add(worker.done.size());
         return null;
      }

      long start = System.nanoTime();
      int count = worker.done.size();
      short[][] outs = new short[count][];
      List<SurfaceGpu.Job> jobs = new ArrayList<>(count);
      List<short[]> made = new ArrayList<>(count);
      int[] index = new int[count];
      net.minecraft.world.level.biome.BiomeSource biomeSource = this.level.getChunkSource().getGenerator().getBiomeSource();
      for (int i = 0; i < count; i++) {
         try {
            SurfaceGpu.Job job = LodSurface.job(p, biomeSource, worker.standIns.random(), worker.done.get(i), worker.jobs.get(i), worker.codes.get(i),
               worker.standIns.table(), worker.standIns.prelim(i), this.minY, this.height);
            worker.surfaceJobs[i] = job;
            index[jobs.size()] = i;
            jobs.add(job);
            made.add(new short[256 * job.height]);
         } catch (RuntimeException failure) {
            if (this.surfaceFailures.incrementAndGet() <= 4) {
               WorldgenAccelerator.LOG.warn("WGA {}: chunk {}'s surface inputs failed", this.name, worker.done.get(i), failure);
            }
         }
      }

      long gpuStart = System.nanoTime();
      this.surfaceInputNanos.add(gpuStart - start);
      boolean[] done;
      try {
         done = jobs.isEmpty() ? new boolean[0] : p.gpu().runBatch(jobs, made);
      } catch (RuntimeException failure) {
         if (com.wgaccel.util.Interrupts.only(failure)) {
            throw new java.util.concurrent.CancellationException("interrupted");
         }

         if (this.surfaceFailures.incrementAndGet() <= 4) {
            WorldgenAccelerator.LOG.warn("WGA {}: a surface launch of {} chunks failed", this.name, jobs.size(), failure);
         }

         this.bareChunks.add(count);
         return null;
      } finally {
         this.surfaceGpuNanos.add(System.nanoTime() - gpuStart);
      }

      int surfaced = 0;
      for (int k = 0; k < jobs.size(); k++) {
         if (done[k]) {
            outs[index[k]] = made.get(k);
            surfaced++;
         }
      }

      this.surfacedChunks.add(surfaced);
      this.bareChunks.add(count - surfaced);
      return outs;
   }

   /**
    * FreeTerraForged's river water (FtfLodWater): what its surface step places before the rules (water of rivers, lakes,
    * wetlands; stone banks), into the launch's codes, before their surface jobs and columns are made.
    */
   private void riverWater(Worker worker, GpuSurface.Program p) {
      if (p == null || worker.done.isEmpty()) {
         return;
      }

      net.minecraft.world.level.biome.BiomeSource biomeSource = this.level.getChunkSource().getGenerator().getBiomeSource();
      net.minecraft.world.level.biome.Climate.Sampler sampler = worker.standIns.random().sampler();
      net.minecraft.world.level.biome.BiomeManager biomes = new net.minecraft.world.level.biome.BiomeManager(
         (qx, qy, qz) -> biomeSource.getNoiseBiome(qx, qy, qz, sampler), p.zoomSeed());
      for (int i = 0; i < worker.done.size(); i++) {
         if (!com.wgaccel.ftf.FtfLodWater.apply(worker.standIns.random(), p.system(), worker.standIns.proto(i), null, biomes, worker.jobs.get(i), worker.codes.get(i),
               worker.standIns.table(), p.defaultBlock())) {
            return;
         }
      }
   }

   /** The surface's block at y in column t, or the fill's when the surface left it (or y is outside the job). */
   private static BlockState surfaced(GpuSurface.Program p, SurfaceGpu.Job job, short[] out, int y, int t, BlockState fill) {
      if (y == LodColumns.NONE || y < job.minY || y >= job.minY + job.height) {
         return fill;
      }

      int code = out[(y - job.minY) * 256 + t];
      return code >= 0 ? p.table()[code] : fill;
   }

   /** Whether a snow layer stays on the block (SnowLayerBlock.canSurvive, without the world: not on ice or barriers). */
   private static boolean holdsSnow(BlockState state) {
      return !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BARRIER) && !state.isAir() && state.getFluidState().isEmpty()
         && (state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SOUL_SAND) || state.canOcclude());
   }

   /** The snow block the columns' snow is (one layer). */
   public static BlockState snow() {
      return SNOW;
   }

   /** The FreeTerraForged feature-step ground for the LOD columns (scree, snow erosion), or null; made once. */
   com.wgaccel.ftf.FtfLodDecor decor() {
      if (!this.decorTried) {
         synchronized (this) {
            if (!this.decorTried) {
               this.decor = com.wgaccel.ftf.FtfLodDecor.of(this.level, this.level.getChunkSource().randomState());
               this.decorTried = true;
            }
         }
      }

      return this.decor;
   }

   /** Whether the FreeTerraForged decor was made (for the report's scree counts). */
   boolean hasDecor() {
      return this.decor != null;
   }

   /** The noise biome at the block (the biome source's, as the biome step would choose it). */
   public Holder<Biome> biomeAt(int x, int y, int z) {
      return this.level.getChunkSource().getGenerator().getBiomeSource()
         .getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), this.level.getChunkSource().randomState().sampler());
   }

   /** FreeTerraForged's fast stand-ins' spacing for columns this far apart (config distantHorizonsFastFrom), 0 for exact. */
   public static int fastStride(int spacing, int fillStride, boolean exact) {
      int fastFrom = exact ? -1 : Config.DH_FAST_FROM.get();
      return fastFrom >= 0 && spacing >= 1 << fastFrom ? fillStride : 0;
   }
}
