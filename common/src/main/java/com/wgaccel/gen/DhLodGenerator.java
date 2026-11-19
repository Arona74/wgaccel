package com.wgaccel.gen;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.wgaccel.Config;
import com.wgaccel.LevelLogger;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.FillGpu;
import com.wgaccel.gpu.SurfaceGpu;
import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Distant Horizons' world generator for the overworld (config distantHorizonsGenerator): DH's LOD sections (64 x 64
 * columns, one column every 2^detail blocks) made from the GPU noise fill's stand-ins ({@link LodColumns}) instead of
 * generated chunks. Each column is its ground (the noise fill's block), any water above it and air, with the biome at its
 * top. Not yet there: the surface (grass, sand: the ground's top is the noise fill's stone), structures and features.
 *
 * <p>DH asks with {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES}: heights are counted from the level's bottom,
 * and the columns are marked as its SURFACE step, so the chunks the player really generates replace them. Chunks whose
 * stand-in fails, and every column while the GPU fill is not ready, come from the game's getBaseHeight (slow, the same
 * heights).
 */
public final class DhLodGenerator implements IDhApiWorldGenerator {
   /** Chunks per GPU launch. */
   private static final int BATCH = 64;
   /**
    * The ground layers a surfaced column is written with (the surface's blocks, then the default block): what cliffs
    * show. 32, as deep as FreeTerraForged's scree looks for rock under a steep slope.
    */
   private static final int GROUND_DEPTH = 32;
   /** Columns 2^12 = 4,096 blocks apart at most (DH asks for the far sections at that detail directly). */
   private static final byte LARGEST_DETAIL = 12;
   /** How long the first requests wait for the GPU fill's program (it is made while the world loads). */
   private static final long WAIT_NANOS = 60_000_000_000L;

   private final ServerLevel level;
   private final IDhApiLevelWrapper wrapper;
   private final int minY;
   private final int height;
   private final long waitUntil = System.nanoTime() + WAIT_NANOS;
   private final ThreadLocal<Worker> workers = new ThreadLocal<>();
   private final ConcurrentHashMap<BlockState, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Holder<Biome>, IDhApiBiomeWrapper> biomes = new ConcurrentHashMap<>();
   private final IDhApiBlockStateWrapper air;
   private final LongAdder sections = new LongAdder();
   private final LongAdder columns = new LongAdder();
   private final LongAdder cpuColumns = new LongAdder();
   private final LongAdder sectionNanos = new LongAdder();
   private final LongAdder fillNanos = new LongAdder();
   private final LongAdder biomeNanos = new LongAdder();
   private final LongAdder surfacedChunks = new LongAdder();
   private final LongAdder bareChunks = new LongAdder();
   private final LongAdder surfaceInputNanos = new LongAdder();
   private final LongAdder surfaceGpuNanos = new LongAdder();
   private final java.util.concurrent.atomic.AtomicInteger surfaceFailures = new java.util.concurrent.atomic.AtomicInteger();
   private final long[] sectionsByDetail = new long[LARGEST_DETAIL + 1];
   private final AtomicLong nextReport = new AtomicLong(16);
   /** Set when DH closes the generator (its level unloading): new sections are cancelled, running ones stop at their next launch. */
   private volatile boolean closed;
   /** Sections being made; close waits for them, so none uses the GPU fill after the world releases it. */
   private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
   private final LongAdder fillInputNanos = new LongAdder();
   private volatile com.wgaccel.ftf.FtfLodDecor decor;
   private volatile boolean decorTried;

   /** The generator registered last (for /wgaccel lod dh). */
   private static volatile DhLodGenerator active;
   private static final java.util.concurrent.atomic.AtomicBoolean BENCH_RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

   private DhLodGenerator(ServerLevel level, IDhApiLevelWrapper wrapper) {
      this.level = level;
      this.wrapper = wrapper;
      this.minY = wrapper.getMinHeight();
      this.height = wrapper.getHeight();
      this.air = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
   }

   /** From the mod's start, with Distant Horizons there: each overworld DH loads gets this generator (when on). */
   public static void hook() {
      DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
         @Override
         public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
            register(event.value.levelWrapper);
         }
      });
      WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Distant Horizons {} found (API {}.{}): the GPU LOD generator is {}", DhApi.getModVersion(),
         DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), Config.DH_GENERATOR.get() ? "on" : "off (config distantHorizonsGenerator)");
      if (Config.DH_GENERATOR.get() && com.wgaccel.platform.Platform.get().isModLoaded("seedrenderer")) {
         // DH keeps one generator per priority: the one registered last replaces the other.
         WorldgenAccelerator.LOG.warn("WGA Distant Horizons SeedGen (seedrenderer) is installed too: Distant Horizons uses whichever generator registers last;"
            + " turn one of them off");
      }

      if (Config.DH_GENERATOR.get() && com.wgaccel.platform.Platform.get().isModLoaded("horizonsreshowing")) {
         // Its generator registers at priority 100 (its log says so), above this one's 10: DH uses it.
         WorldgenAccelerator.LOG.warn("WGA Horizons Reshowing is installed too: Distant Horizons uses its generator (a higher priority), not the GPU LOD generator");
      }
   }

   private static void register(IDhApiLevelWrapper wrapper) {
      try {
         if (!Config.DH_GENERATOR.get() || !(wrapper.getWrappedMcObject() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
         }

         if (Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
            WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Distant Horizons generator: not used, the GPU is off");
            return;
         }

         DhLodGenerator generator = new DhLodGenerator(level, wrapper);
         DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, generator);
         if (result.success) {
            active = generator;
         }

         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Distant Horizons generator: {} for {}{}", result.success ? "registered" : "NOT registered",
            wrapper.getDimensionName(), result.message == null || result.message.isEmpty() ? "" : " (" + result.message + ")");
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA Distant Horizons generator: not registered", failure);
      }
   }

   @Override
   public byte getSmallestDataDetailLevel() {
      return 0;
   }

   @Override
   public byte getLargestDataDetailLevel() {
      return LARGEST_DETAIL;
   }

   @Override
   public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
   }

   @Override
   public CompletableFuture<Void> generateLod(int chunkMinX, int chunkMinZ, int posX, int posZ, byte detail, IDhApiFullDataSource source,
         EDhApiDistantGeneratorMode mode, ExecutorService pool, Consumer<IDhApiFullDataSource> result) {
      return CompletableFuture.runAsync(() -> {
         this.inFlight.incrementAndGet();
         try {
            if (this.closed) {
               // DH asks after closing the generator (its threads run on as the world closes): DH drops a cancelled section quietly.
               throw new java.util.concurrent.CancellationException("the world is closing");
            }

            this.fill(chunkMinX, chunkMinZ, detail, source);
         } catch (RuntimeException | Error failure) {
            if (failure instanceof java.util.concurrent.CancellationException || com.wgaccel.util.Interrupts.only(failure)) {
               // DH stopping its threads (the world closing): the section is not needed any more.
               throw failure;
            }

            WorldgenAccelerator.LOG.warn("WGA Distant Horizons generator: section at chunk {}, {} (detail {}) failed", chunkMinX, chunkMinZ, detail, failure);
            throw failure;
         } finally {
            this.inFlight.decrementAndGet();
         }

         result.accept(source);
      }, pool);
   }

   @Override
   public void preGeneratorTaskStart() {
   }

   @Override
   public void close() {
      this.closed = true;
      // The running sections stop at their next GPU launch (a launch takes milliseconds); the GPU fill is released later, at the server's stop.
      long until = System.nanoTime() + 30_000_000_000L;
      while (this.inFlight.get() > 0 && System.nanoTime() < until) {
         try {
            Thread.sleep(10L);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      if (this.inFlight.get() > 0) {
         WorldgenAccelerator.LOG.warn("WGA Distant Horizons generator: {} sections still running 30 s after Distant Horizons closed it", this.inFlight.get());
      }

      this.report();
   }

   /** One DH thread's GPU stand-ins and lists. */
   private static final class Worker {
      final GpuNoiseFill.StandIns standIns;
      final boolean[] groundTest = new boolean[16];
      final boolean[] surfaceTest = new boolean[16];
      final List<ChunkPos> batch = new ArrayList<>(BATCH);
      final List<ChunkPos> done = new ArrayList<>(BATCH);
      final List<FillGpu.Job> jobs = new ArrayList<>(BATCH);
      final List<byte[]> codes = new ArrayList<>(BATCH);
      final List<DhApiTerrainDataPoint> points = new ArrayList<>(8);
      final BlockState[] layers = new BlockState[GROUND_DEPTH];
      final SurfaceGpu.Job[] surfaceJobs = new SurfaceGpu.Job[BATCH];

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
   private Worker worker() {
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

         if (this.closed) {
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

   private void fill(int chunkMinX, int chunkMinZ, int detail, IDhApiFullDataSource source) {
      long start = System.nanoTime();
      int stride = 1 << detail;
      int width = source.getWidthInDataColumns();
      // Up to 16 blocks apart: every chunk, its columns stride apart; further: one column (0, 0) in chunkStep x chunkStep chunks.
      int fillStride = Math.min(16, stride);
      int chunkStep = Math.max(1, stride / 16);
      int n = 16 / fillStride;
      int side = width / n;
      Worker worker = this.worker();
      if (worker != null) {
         // A section that failed part way left its chunks here.
         worker.batch.clear();
      }

      List<DhApiTerrainDataPoint> points = worker != null ? worker.points : new ArrayList<>(3);
      for (int cx = 0; cx < side; cx++) {
         for (int cz = 0; cz < side; cz++) {
            ChunkPos pos = Mc.chunk(Mc.key(chunkMinX + cx * chunkStep, chunkMinZ + cz * chunkStep));
            if (worker == null) {
               this.cpuChunk(source, pos, cx * n, cz * n, n, fillStride, points);
               continue;
            }

            worker.batch.add(pos);
            if (worker.batch.size() == BATCH) {
               this.launch(worker, source, chunkMinX, chunkMinZ, chunkStep, n, fillStride);
            }
         }
      }

      if (worker != null && !worker.batch.isEmpty()) {
         this.launch(worker, source, chunkMinX, chunkMinZ, chunkStep, n, fillStride);
      }

      this.sectionNanos.add(System.nanoTime() - start);
      this.sections.increment();
      synchronized (this.sectionsByDetail) {
         this.sectionsByDetail[Math.min(detail, LARGEST_DETAIL)]++;
      }

      long done = this.sections.sum();
      long next = this.nextReport.get();
      if (done >= next && this.nextReport.compareAndSet(next, next < 1024 ? next * 4 : next + 1024)) {
         this.report();
      }
   }

   /** The batch's chunks filled on the GPU, their columns written; chunks whose stand-in failed from the CPU. */
   private void launch(Worker worker, IDhApiFullDataSource source, int chunkMinX, int chunkMinZ, int chunkStep, int n, int fillStride) {
      if (this.closed) {
         throw new java.util.concurrent.CancellationException("the world is closing");
      }

      long start = System.nanoTime();
      worker.done.clear();
      worker.jobs.clear();
      worker.codes.clear();
      GpuSurface.Program surfaceProgram = LodSurface.program(worker.standIns.random());
      worker.standIns.prelims = surfaceProgram != null;
      int fastFrom = benchExact ? -1 : Config.DH_FAST_FROM.get();
      // fillStride * chunkStep is the section's column spacing, 2^detail.
      worker.standIns.fastStride = fastFrom >= 0 && fillStride * chunkStep >= 1 << fastFrom ? fillStride : 0;
      worker.standIns.prepare(worker.batch, fillStride, worker.done, worker.jobs, worker.codes);
      long prepared = System.nanoTime();
      this.fillInputNanos.add(prepared - start);
      // Each thread launches its own: Distant Horizons' threads' launches overlap on the GPU. Merged into one launch
      // (tried 19 Nov 2026), they were slower: detail 0 1,723 chunks of area/s against 2,409-2,537 apart.
      worker.standIns.launch(worker.jobs, worker.codes);

      this.fillNanos.add(System.nanoTime() - prepared);
      BlockState[] table = worker.standIns.table();
      this.riverWater(worker, surfaceProgram);
      short[][] outs = this.surfaces(worker, surfaceProgram);
      // The feature step's ground (scree, snow, ice) left out while the bench runs: it checks the surface step's.
      boolean features = !BENCH_RUNNING.get();
      com.wgaccel.ftf.FtfLodDecor decor = surfaceProgram == null || !features ? null : this.decor();
      int at = 0;
      for (ChunkPos pos : worker.batch) {
         int baseX = (Mc.x(pos) - chunkMinX) / chunkStep * n;
         int baseZ = (Mc.z(pos) - chunkMinZ) / chunkStep * n;
         if (at < worker.done.size() && worker.done.get(at).equals(pos)) {
            LodColumns.Columns columns = LodColumns.reduce(pos, worker.jobs.get(at), worker.codes.get(at), table, worker.groundTest, worker.surfaceTest);
            short[] out = outs == null ? null : outs[at];
            SurfaceGpu.Job surfaceJob = out == null ? null : worker.surfaceJobs[at];
            Object[] cells = out == null || decor == null ? null : decor.cells(worker.standIns.proto(at));
            at++;
            for (int i = 0; i < n; i++) {
               for (int j = 0; j < n; j++) {
                  int column = i * n + j;
                  int x = pos.getMinBlockX() + i * fillStride;
                  int z = pos.getMinBlockZ() + j * fillStride;
                  int ground = columns.ground()[column];
                  int surface = columns.surface()[column];
                  if (out == null) {
                     this.column(source, baseX + i, baseZ + j, x, z, ground, surface, columns.top()[column], columns.groundTop()[column], worker.points);
                     continue;
                  }

                  // The surface's blocks down from the column's ground (its layers, which cliffs show), and the block
                  // at its top (the same block without water).
                  int t = (i * fillStride) * 16 + j * fillStride;
                  BlockState[] layers = worker.layers;
                  BlockState fallback = columns.groundTop()[column];
                  for (int k = 0; k < GROUND_DEPTH; k++) {
                     BlockState state = ground == LodColumns.NONE ? surfaceProgram.defaultBlock()
                        : surfaced(surfaceProgram, surfaceJob, out, ground - k, t, k == 0 ? fallback : surfaceProgram.defaultBlock());
                     layers[k] = state.isAir() || !state.getFluidState().isEmpty() ? surfaceProgram.defaultBlock() : state;
                  }

                  BlockState top = surfaced(surfaceProgram, surfaceJob, out, surface, t, columns.top()[column]);
                  Holder<Biome> biome = this.biomeAt(x, surface == LodColumns.NONE ? this.minY : surface, z);
                  Object cell = cells == null ? null : cells[(j * fillStride) * 16 + i * fillStride];
                  boolean dry = ground != LodColumns.NONE && surface == ground;
                  if (dry && cell != null) {
                     decor.scree(cell, x, z, ground, biome, layers, surfaceProgram.defaultBlock());
                  }

                  // The top layer's snow and ice (vanilla's freeze_top_layer; FreeTerraForged's snow decorator takes the
                  // snow off steep cold ground).
                  int seaLevel = this.level.getChunkSource().getGenerator().getSeaLevel();
                  boolean snow = false;
                  boolean ice = false;
                  if (dry && features) {
                     snow = ground + 1 < this.minY + this.height && Mc.coldEnoughToSnow(biome.value(), new net.minecraft.core.BlockPos(x, ground + 1, z), seaLevel)
                        && holdsSnow(layers[0]) && (cell == null || !decor.snowEroded(cell, x, z, ground, biome));
                  } else if (features && surface != LodColumns.NONE && top.is(Blocks.WATER)) {
                     ice = Mc.coldEnoughToSnow(biome.value(), new net.minecraft.core.BlockPos(x, surface, z), seaLevel);
                  }

                  this.column(source, baseX + i, baseZ + j, ground, surface, top, layers, surfaceProgram.defaultBlock(), snow, ice, biome, worker.points);
               }
            }
         } else {
            this.cpuChunk(source, pos, baseX, baseZ, n, fillStride, worker.points);
         }
      }

      worker.batch.clear();
   }

   /** A chunk's columns from the game's getBaseHeight (the ground block the generator's default block, water its fluid). */
   private void cpuChunk(IDhApiFullDataSource source, ChunkPos pos, int baseX, int baseZ, int n, int fillStride, List<DhApiTerrainDataPoint> points) {
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
            int x = pos.getMinBlockX() + i * fillStride;
            int z = pos.getMinBlockZ() + j * fillStride;
            int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, random) - 1;
            int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.level, random) - 1;
            this.column(source, baseX + i, baseZ + j, x, z, ground, surface, surface > ground ? fluid : block, block, points);
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
               WorldgenAccelerator.LOG.warn("WGA Distant Horizons generator: chunk {}'s surface inputs failed", worker.done.get(i), failure);
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
            WorldgenAccelerator.LOG.warn("WGA Distant Horizons generator: a surface launch of {} chunks failed", jobs.size(), failure);
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

   /** One column: air above the surface, the fluid between the ground and the surface, the ground block down to the bottom. */
   private void column(IDhApiFullDataSource source, int relX, int relZ, int x, int z, int ground, int surface, BlockState top, BlockState groundTop,
         List<DhApiTerrainDataPoint> points) {
      int groundRel = ground == LodColumns.NONE ? 0 : Math.max(0, Math.min(this.height, ground + 1 - this.minY));
      int surfaceRel = surface == LodColumns.NONE ? groundRel : Math.max(groundRel, Math.min(this.height, surface + 1 - this.minY));
      long start = System.nanoTime();
      IDhApiBiomeWrapper biome = this.biome(x, surface == LodColumns.NONE ? this.minY : surface, z);
      this.biomeNanos.add(System.nanoTime() - start);
      points.clear();
      if (surfaceRel < this.height) {
         points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, surfaceRel, this.height, this.air, biome));
      }

      if (groundRel < surfaceRel) {
         points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, groundRel, surfaceRel, this.block(top), biome));
      }

      if (groundRel > 0) {
         // DH keeps, for each run of blocks, the light of the block above its top (LodDataBuilder.createFromChunk): the
         // sky's 15 in the open, one less per block of water above (water dims sky light by one). With 0 the ground
         // drew unlit, the whole LOD dark.
         int skyLight = Math.max(0, 15 - (surfaceRel - groundRel));
         points.add(DhApiTerrainDataPoint.create((byte)0, 0, skyLight, 0, groundRel, this.block(groundTop), biome));
      }

      source.setApiDataPointColumn(relX, relZ, points);
      this.columns.increment();
   }

   /**
    * A surfaced column: air above, snow (one block) on dry ground or ice (one block) over water when cold, the fluid
    * between the ground and the surface, the ground's layers (layers[k]: k below the ground) as runs, then the
    * generator's default block down to the bottom.
    */
   private void column(IDhApiFullDataSource source, int relX, int relZ, int ground, int surface, BlockState top, BlockState[] layers, BlockState below,
         boolean snow, boolean ice, Holder<Biome> holder, List<DhApiTerrainDataPoint> points) {
      int groundRel = ground == LodColumns.NONE ? 0 : Math.max(0, Math.min(this.height, ground + 1 - this.minY));
      int surfaceRel = surface == LodColumns.NONE ? groundRel : Math.max(groundRel, Math.min(this.height, surface + 1 - this.minY));
      long start = System.nanoTime();
      IDhApiBiomeWrapper biome = this.biomes.computeIfAbsent(holder, b -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{b}, this.wrapper));
      this.biomeNanos.add(System.nanoTime() - start);
      points.clear();
      int snowTop = snow && surfaceRel < this.height ? surfaceRel + 1 : surfaceRel;
      if (snowTop < this.height) {
         points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, snowTop, this.height, this.air, biome));
      }

      if (snowTop > surfaceRel) {
         points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, surfaceRel, snowTop, this.block(SNOW), biome));
      }

      if (groundRel < surfaceRel) {
         int iceFrom = ice ? surfaceRel - 1 : surfaceRel;
         if (iceFrom < surfaceRel) {
            points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, iceFrom, surfaceRel, this.block(Blocks.ICE.defaultBlockState()), biome));
         }

         if (groundRel < iceFrom) {
            points.add(DhApiTerrainDataPoint.create((byte)0, 0, 15, groundRel, iceFrom, this.block(top), biome));
         }
      }

      if (groundRel > 0) {
         // DH keeps, for each run of blocks, the light of the block above its top (LodDataBuilder.createFromChunk): the
         // sky's 15 in the open, one less per block of water above (water dims sky light by one). With 0 the ground
         // drew unlit, the whole LOD dark. The runs below get the same (their faces show where cliffs open them).
         int skyLight = Math.max(0, 15 - (surfaceRel - groundRel));
         // Runs of equal blocks: layers[k] at groundRel - 1 - k, the default block under the last layer.
         int to = groundRel;
         int k = 0;
         while (to > 0) {
            BlockState state = k < layers.length ? layers[k] : below;
            int from = to - 1;
            k++;
            while (from > 0) {
               if (k >= layers.length) {
                  if (state == below) {
                     from = 0;
                  }

                  break;
               }

               if (layers[k] != state) {
                  break;
               }

               from--;
               k++;
            }

            if (k > layers.length) {
               from = 0;
            }

            points.add(DhApiTerrainDataPoint.create((byte)0, 0, skyLight, from, to, this.block(state), biome));
            to = from;
         }
      }

      source.setApiDataPointColumn(relX, relZ, points);
      this.columns.increment();
   }

   private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();

   /** Whether a snow layer stays on the block (SnowLayerBlock.canSurvive, without the world: not on ice or barriers). */
   private static boolean holdsSnow(BlockState state) {
      return !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BARRIER) && !state.isAir() && state.getFluidState().isEmpty()
         && (state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.SOUL_SAND) || state.canOcclude());
   }

   /** The FreeTerraForged feature-step ground for the LOD columns (scree, snow erosion), or null; made once. */
   private com.wgaccel.ftf.FtfLodDecor decor() {
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

   private IDhApiBlockStateWrapper block(BlockState state) {
      return this.blocks.computeIfAbsent(state, s -> DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{s}, this.wrapper));
   }

   /** The noise biome at the column's top (the biome source's, as the biome step would choose it). */
   private IDhApiBiomeWrapper biome(int x, int y, int z) {
      Holder<Biome> biome = this.biomeAt(x, y, z);
      return this.biomes.computeIfAbsent(biome, b -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{b}, this.wrapper));
   }

   private Holder<Biome> biomeAt(int x, int y, int z) {
      return this.level.getChunkSource().getGenerator().getBiomeSource()
         .getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), this.level.getChunkSource().randomState().sampler());
   }

   private void report() {
      long count = Math.max(1, this.sections.sum());
      StringBuilder details = new StringBuilder();
      synchronized (this.sectionsByDetail) {
         for (int d = 0; d <= LARGEST_DETAIL; d++) {
            if (this.sectionsByDetail[d] > 0) {
               details.append(details.isEmpty() ? "" : ", ").append(d).append(": ").append(this.sectionsByDetail[d]);
            }
         }
      }

      WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, String.format(Locale.ROOT,
         "WGA Distant Horizons generator: %,d sections (by detail %s), %,d columns (%,d from the CPU); per section on its thread %.1f ms (fill inputs %.1f ms, fill GPU %.1f ms, surface inputs %.1f ms, surface GPU %.1f ms, biomes %.1f ms); chunks with their surface %,d, without %,d; FreeTerraForged scree on %,d dry columns, %,d changed",
         this.sections.sum(), details, this.columns.sum(), this.cpuColumns.sum(), this.sectionNanos.sum() / 1.0e6 / count, this.fillInputNanos.sum() / 1.0e6 / count,
         this.fillNanos.sum() / 1.0e6 / count,
         this.surfaceInputNanos.sum() / 1.0e6 / count, this.surfaceGpuNanos.sum() / 1.0e6 / count, this.biomeNanos.sum() / 1.0e6 / count, this.surfacedChunks.sum(),
         this.bareChunks.sum(), this.decor == null ? 0L : com.wgaccel.ftf.FtfLodDecor.COLUMNS.sum(), this.decor == null ? 0L : com.wgaccel.ftf.FtfLodDecor.CHANGED.sum()));
   }

   /**
    * {@code /wgaccel lod dh}: the given number of sections at a detail level around the chunk (a square), made on
    * threads as DH's own pool would; each column checked as DH checks the columns it is given (top down, no gap or
    * overlap, from the level's top to its bottom), and four columns a section against the game's getBaseHeight
    * (OCEAN_FLOOR_WG) at the block DH's section position puts them.
    */
   public static boolean bench(ServerLevel level, int chunkX, int chunkZ, int detail, int sections, int threads, Consumer<String> report) {
      DhLodGenerator generator = active;
      if (generator == null || generator.level != level) {
         report.accept("WGA Distant Horizons bench: no GPU LOD generator for this level (config distantHorizonsGenerator, overworld only)");
         return false;
      }

      if (!BENCH_RUNNING.compareAndSet(false, true)) {
         report.accept("WGA Distant Horizons bench: one is running already");
         return false;
      }

      Thread thread = new Thread(() -> {
         // Named as Distant Horizons' own, so /wgaccel profile samples them too.
         java.util.concurrent.atomic.AtomicInteger names = new java.util.concurrent.atomic.AtomicInteger();
         ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, task -> {
            Thread worker = new Thread(task, "DH-World Gen bench " + names.getAndIncrement());
            worker.setDaemon(true);
            return worker;
         });
         try {
            generator.runBench(chunkX, chunkZ, detail, sections, threads, pool, report);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA Distant Horizons bench failed", failure);
            report.accept("WGA Distant Horizons bench failed: " + failure);
         } finally {
            pool.shutdownNow();
            BENCH_RUNNING.set(false);
         }
      }, "WGA Distant Horizons bench");
      thread.setDaemon(true);
      thread.start();
      return true;
   }

   /** Set while the bench makes its exact pass after a fast one (distantHorizonsFastFrom then left out). */
   private static volatile boolean benchExact;

   /** The sections made at the detail (the bench's square), each in a checking section; seconds[0] their wall time, seconds[1] failures. */
   private List<CheckingSource> makeSections(int baseX, int baseZ, int side, int sectionChunks, int detail, int sections, ExecutorService pool, double[] seconds) {
      List<CheckingSource> sources = new ArrayList<>();
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      long start = System.nanoTime();
      for (int k = 0; k < sections; k++) {
         int sx = (baseX + k % side) * sectionChunks;
         int sz = (baseZ + k / side) * sectionChunks;
         CheckingSource source = new CheckingSource(sx, sz, 1 << detail);
         sources.add(source);
         futures.add(this.generateLod(sx, sz, 0, 0, (byte)detail, source, EDhApiDistantGeneratorMode.SURFACE, pool, s -> {
         }));
      }

      for (CompletableFuture<Void> future : futures) {
         try {
            future.join();
         } catch (RuntimeException failure) {
            seconds[1]++;
         }
      }

      seconds[0] = (System.nanoTime() - start) / 1.0e9;
      return sources;
   }

   private void runBench(int chunkX, int chunkZ, int detail, int sections, int threads, ExecutorService pool, Consumer<String> report) {
      try {
         this.runBenchPasses(chunkX, chunkZ, detail, sections, threads, pool, report);
      } finally {
         benchExact = false;
      }
   }

   private void runBenchPasses(int chunkX, int chunkZ, int detail, int sections, int threads, ExecutorService pool, Consumer<String> report) {
      int sectionChunks = 4 << detail;
      int side = (int)Math.ceil(Math.sqrt(sections));
      int baseX = Math.floorDiv(chunkX, sectionChunks) - side / 2;
      int baseZ = Math.floorDiv(chunkZ, sectionChunks) - side / 2;
      report.accept(String.format(Locale.ROOT, "WGA Distant Horizons bench: %d sections of detail %d (%d x %d chunks each, a column every %d blocks), %d threads...",
         sections, detail, sectionChunks, sectionChunks, 1 << detail, threads));
      // With the fast FreeTerraForged stand-ins at this detail: the fast pass first (new ground for both: the exact pass
      // builds the regions, whose filtered cells a later fast pass would read), then the same sections exact, compared.
      int fastFrom = Config.DH_FAST_FROM.get();
      if (fastFrom >= 0 && detail >= fastFrom && com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged")) {
         double[] fastSeconds = new double[2];
         long fastChunks = com.wgaccel.ftf.FtfLodCells.CHUNKS.sum();
         List<CheckingSource> fast = this.makeSections(baseX, baseZ, side, sectionChunks, detail, sections, pool, fastSeconds);
         fastChunks = com.wgaccel.ftf.FtfLodCells.CHUNKS.sum() - fastChunks;
         benchExact = true;
         double[] exactSeconds = new double[2];
         List<CheckingSource> exact = this.makeSections(baseX, baseZ, side, sectionChunks, detail, sections, pool, exactSeconds);
         benchExact = false;
         long columns = 0;
         long sameGround = 0;
         long sameTop = 0;
         long sameBlock = 0;
         long sumDiff = 0;
         int maxDiff = 0;
         int[] histogram = new int[5];
         for (int s = 0; s < fast.size(); s++) {
            CheckingSource f = fast.get(s);
            CheckingSource e = exact.get(s);
            for (int c = 0; c < 64 * 64; c++) {
               columns++;
               int diff = Math.abs(f.ground[c] - e.ground[c]);
               sumDiff += diff;
               maxDiff = Math.max(maxDiff, diff);
               histogram[diff == 0 ? 0 : diff <= 2 ? 1 : diff <= 5 ? 2 : diff <= 10 ? 3 : 4]++;
               sameGround += diff == 0 ? 1 : 0;
               sameTop += f.top[c] == e.top[c] ? 1 : 0;
               sameBlock += java.util.Objects.equals(f.topBlock[c], e.topBlock[c]) ? 1 : 0;
            }
         }

         double area = (double)sections * sectionChunks * sectionChunks;
         report.accept(String.format(Locale.ROOT,
            "WGA Distant Horizons bench: fast (FreeTerraForged cells for the read columns, %,d stand-ins) %.2f s = %,.0f chunks of area/s; exact %.2f s = %,.0f chunks of area/s (%.1fx)",
            fastChunks, fastSeconds[0], area / fastSeconds[0], exactSeconds[0], area / exactSeconds[0], exactSeconds[0] / fastSeconds[0]));
         report.accept(String.format(Locale.ROOT,
            "WGA Distant Horizons bench: fast against exact, %,d columns: ground the same %.1f%%, within 2 blocks %.1f%%, 3-5 %.1f%%, 6-10 %.1f%%, more %.1f%% (mean %.2f, max %d); top height the same %.1f%%, top block the same %.1f%%",
            columns, 100.0 * histogram[0] / columns, 100.0 * histogram[1] / columns, 100.0 * histogram[2] / columns, 100.0 * histogram[3] / columns,
            100.0 * histogram[4] / columns, (double)sumDiff / columns, maxDiff, 100.0 * sameTop / columns, 100.0 * sameBlock / columns));
         // The checks below on the exact pass's sections.
         benchExact = true;
      }

      List<CheckingSource> sources = new ArrayList<>();
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      long cpuBefore = this.cpuColumns.sum();
      long start = System.nanoTime();
      for (int k = 0; k < sections; k++) {
         int sx = (baseX + k % side) * sectionChunks;
         int sz = (baseZ + k / side) * sectionChunks;
         CheckingSource source = new CheckingSource(sx, sz, 1 << detail);
         sources.add(source);
         futures.add(this.generateLod(sx, sz, 0, 0, (byte)detail, source, EDhApiDistantGeneratorMode.SURFACE, pool, s -> {
         }));
      }

      int failed = 0;
      for (CompletableFuture<Void> future : futures) {
         try {
            future.join();
         } catch (RuntimeException failure) {
            failed++;
         }
      }

      double seconds = (System.nanoTime() - start) / 1.0e9;
      long bad = 0;
      long missing = 0;
      long checked = 0;
      long different = 0;
      String first = null;
      ChunkGenerator chunkGenerator = this.level.getChunkSource().getGenerator();
      RandomState random = this.level.getChunkSource().randomState();
      for (CheckingSource source : sources) {
         bad += source.bad;
         missing += 64 * 64 - source.set.cardinality();
         if (first == null) {
            first = source.firstBad;
         }

         for (int k = 0; k < 4; k++) {
            int relX = (k * 37 + 11) & 63;
            int relZ = (k * 23 + 41) & 63;
            int x = source.chunkX * 16 + relX * source.stride;
            int z = source.chunkZ * 16 + relZ * source.stride;
            int game = chunkGenerator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, random) - this.minY;
            checked++;
            if (game != source.ground[relX * 64 + relZ] && different++ < 8) {
               WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, String.format(Locale.ROOT,
                  "WGA Distant Horizons bench: column %d, %d: ground top %d above the bottom, the game's %d", x, z, source.ground[relX * 64 + relZ], game));
            }
         }
      }

      // The surface: a few columns against the real chunk generated up to its surface step (no structures in the
      // stand-ins, so a structure's terrain differs; the noise has no other difference).
      long blocksChecked = 0;
      long blocksDifferent = 0;
      for (int s = 0; s < Math.min(sources.size(), 8); s++) {
         CheckingSource source = sources.get(s);
         for (int k = 0; k < 2; k++) {
            int relX = (k * 37 + 11) & 63;
            int relZ = (k * 23 + 41) & 63;
            int x = source.chunkX * 16 + relX * source.stride;
            int z = source.chunkZ * 16 + relZ * source.stride;
            net.minecraft.world.level.chunk.ChunkAccess real = this.level.getServer()
               .submit(() -> this.level.getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.SURFACE, true)).join();
            int groundY = real.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            int topY = real.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            String realGround = this.block(real.getBlockState(new net.minecraft.core.BlockPos(x, groundY, z))).getSerialString();
            String realTop = this.block(real.getBlockState(new net.minecraft.core.BlockPos(x, topY, z))).getSerialString();
            int column = relX * 64 + relZ;
            int ourGround = source.ground[column] - 1 + this.minY;
            int ourTop = source.top[column] - 1 + this.minY;
            blocksChecked++;
            if (groundY != ourGround || topY != ourTop || !realGround.equals(source.groundBlock[column]) || !realTop.equals(source.topBlock[column])) {
               blocksDifferent++;
               WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, String.format(Locale.ROOT,
                  "WGA Distant Horizons bench: column %d, %d: LOD ground %s at %d, top %s at %d; the real chunk's ground %s at %d, top %s at %d", x, z,
                  source.groundBlock[column], ourGround, source.topBlock[column], ourTop, realGround, groundY, realTop, topY));
            }
         }
      }

      report.accept(String.format(Locale.ROOT, "WGA Distant Horizons bench: ground and top blocks against the real chunks' surface step: %d columns, %s", blocksChecked,
         blocksDifferent == 0 ? "IDENTICAL" : blocksDifferent + " differ (in the log)"));
      double area = (double)sections * (4 << detail) * (4 << detail);
      report.accept(String.format(Locale.ROOT,
         "WGA Distant Horizons bench: %d sections in %.2f s (%d failed): %.1f sections/s, %,.0f columns/s, covering %,.0f chunks of area/s; %,d columns from the CPU",
         sections, seconds, failed, sections / seconds, sections * 4096 / seconds, area / seconds, this.cpuColumns.sum() - cpuBefore));
      report.accept(String.format(Locale.ROOT, "WGA Distant Horizons bench: columns as DH checks them: %s; ground against getBaseHeight: %,d checked, %s",
         bad == 0 && missing == 0 ? "ALL VALID" : String.format(Locale.ROOT, "%,d invalid, %,d never set (first: %s)", bad, missing, first), checked,
         different == 0 ? "IDENTICAL" : different + " differ (in the log)"));
      this.report();
   }

   /** A section that checks each column as DH's setter does and keeps its ground top (above the bottom). */
   private final class CheckingSource implements IDhApiFullDataSource {
      final int chunkX;
      final int chunkZ;
      final int stride;
      final java.util.BitSet set = new java.util.BitSet(64 * 64);
      final int[] ground = new int[64 * 64];
      final String[] groundBlock = new String[64 * 64];
      final String[] topBlock = new String[64 * 64];
      final int[] top = new int[64 * 64];
      long bad;
      String firstBad;

      CheckingSource(int chunkX, int chunkZ, int stride) {
         this.chunkX = chunkX;
         this.chunkZ = chunkZ;
         this.stride = stride;
      }

      @Override
      public int getWidthInDataColumns() {
         return 64;
      }

      @Override
      public synchronized List<DhApiTerrainDataPoint> setApiDataPointColumn(int relX, int relZ,
            com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep step, List<DhApiTerrainDataPoint> points) {
         String why = null;
         int top = DhLodGenerator.this.height;
         int ground = 0;
         for (DhApiTerrainDataPoint point : points) {
            if (point == null || point.blockStateWrapper == null || point.biomeWrapper == null) {
               why = "a null point or wrapper";
            } else if (point.detailLevel != 0) {
               why = "detail " + point.detailLevel;
            } else if (point.topYBlockPos != top) {
               why = "a gap or overlap at " + top + " (" + point + ")";
            } else if (point.topYBlockPos - point.bottomYBlockPos < 1 || point.topYBlockPos - point.bottomYBlockPos > 6094) {
               why = "height " + (point.topYBlockPos - point.bottomYBlockPos);
            }

            if (why != null) {
               break;
            }

            top = point.bottomYBlockPos;
            if (!point.blockStateWrapper.isAir() && !point.blockStateWrapper.isLiquid() && ground == 0) {
               ground = point.topYBlockPos;
               this.groundBlock[relX * 64 + relZ] = point.blockStateWrapper.getSerialString();
            }

            if (!point.blockStateWrapper.isAir() && this.topBlock[relX * 64 + relZ] == null) {
               this.topBlock[relX * 64 + relZ] = point.blockStateWrapper.getSerialString();
               this.top[relX * 64 + relZ] = point.topYBlockPos;
            }
         }

         if (why == null && top != 0) {
            why = "does not reach the bottom (" + top + ")";
         }

         if (why == null && this.set.get(relX * 64 + relZ)) {
            why = "set twice";
         }

         if (why != null) {
            this.bad++;
            if (this.firstBad == null) {
               this.firstBad = "column " + relX + ", " + relZ + " of the section at chunk " + this.chunkX + ", " + this.chunkZ + ": " + why;
            }
         }

         this.set.set(relX * 64 + relZ);
         this.ground[relX * 64 + relZ] = ground;
         return points;
      }

      @Override
      public List<DhApiTerrainDataPoint> getApiDataPointColumn(int relX, int relZ) {
         return new ArrayList<>();
      }
   }
}
