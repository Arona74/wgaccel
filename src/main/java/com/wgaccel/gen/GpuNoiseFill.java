package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.FillGpu;
import com.wgaccel.gpu.GpuService;
import com.wgaccel.mixin.access.FluidStatusAccessor;
import com.wgaccel.mixin.access.LevelChunkSectionAccessor;
import com.wgaccel.mixin.access.NoiseBasedAquiferAccessor;
import com.wgaccel.mixin.access.NoiseChunkAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.fml.loading.FMLPaths;

/**
 * GPU step G2.5 (config gpuNoiseFill): the overworld's whole noise fill from the GPU. Per chunk the CPU works out
 * what the GPU cannot read itself, by the game's own code: FreeTerraForged's CellSampler values on the chunk's 17 x 17
 * column grid (its CacheChunk, during its tile stage) and the aquifer's cells over the filled height (each centre, and
 * its fluid status by vanilla's getAquiferStatus, cached in the aquifer as the fill would), then {@link FillGpu} gives
 * every block's state and the aquifer's fluid update flag. The blocks are then written in vanilla's doFill order
 * (setBlockState, so every section's palette comes out the same), the two worldgen heightmaps get each column's top
 * block, and flagged fluid blocks are marked for post-processing in the same order.
 *
 * <p>CHECK writes into copies of the sections, heightmaps and post-processing lists, lets the CPU fill the chunk as
 * usual, and compares: each section's block states byte for byte as the network writes them (palette, data) and its block counts,
 * both heightmaps' raw data and the post-processing lists in order. ON writes into the chunk and skips the CPU fill,
 * except every 64th chunk, which is checked as in CHECK. Chunks with structure terrain (beardifier) or blending, other
 * dimensions, and aquifers other than vanilla's (or C2ME's rewrite of it) stay on the CPU.
 *
 * <p>Known limit: C2ME's fluid update rule compares fluid statuses by object identity; FreeTerraForged's fluid picker
 * makes a new lava status per column change in its coast transition, so if two aquifer cells took such a status
 * straight from the picker, whether they count as equal could depend on the order cells are first used. That needs
 * ground 20 or more blocks below the lava level there; the check cannot see it (its CPU fill reuses the statuses the
 * GPU path cached).
 */
public final class GpuNoiseFill {
   /** Every how many GPU chunks one is checked (config gpuFillCheckEvery, default 64; 1 checks every chunk). */
   private static int checkEvery() {
      return Math.max(1, Config.GPU_FILL_CHECK_EVERY.get());
   }

   /** Config gpuFillDebugChunks: chunks always checked, diagnosed and saved with the game's blocks ("x,z" chunk coordinates). */
   static boolean debugChunk(ChunkAccess chunk) {
      java.util.List<? extends String> list = Config.GPU_FILL_DEBUG_CHUNKS.get();
      if (list.isEmpty()) {
         return false;
      }

      String key = chunk.getPos().x + "," + chunk.getPos().z;
      for (String each : list) {
         if (each.replace(" ", "").equals(key)) {
            return true;
         }
      }

      return false;
   }
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();

   private record Program(RandomState random, FillGpu gpu, com.wgaccel.gpu.FillBatcher batcher, List<Object> cellSamplers, long seedLo, long seedHi,
         boolean veins, int aquiferKind, BlockState[] table, boolean[] fluid, double[] noise, int[] permutations, int interpolators, int columns,
         ServerLevel level, NoiseBasedChunkGenerator generator, java.util.function.Supplier<Aquifer.FluidPicker> fluidPicker) {
   }

   private static final int SAMPLES = 8;
   private static final AtomicInteger SAMPLED = new AtomicInteger();

   /** A chunk worked out on the GPU, waiting to be written (or compared). */
   public static final class Pending {
      /** The dimension's program that made it (set by prepare). */
      private Program program;
      private final ChunkAccess chunk;
      private final int minCellY;
      private final int cellCountY;
      private final int cellWidth;
      private final int cellHeight;
      private final byte[] codes;
      private final boolean check;
      private final long prepNanos;
      private final long gpuNanos;
      private LevelChunkSection[] sections;
      /** Made by the look-ahead (from a stand-in chunk), not in the chunk's own noise step (the codes check's report). */
      private boolean fromAhead;
      /** From the look-ahead, made from the chunk's own noise chunk at its biome step (structure chunks), not a stand-in. */
      private boolean real;
      /** A chunk named in gpuFillDebugChunks: diagnosed and saved even when it is the same. */
      private boolean debug;
      private long[] oceanFloor;
      private long[] worldSurface;
      private ShortList[] marks;

      private Pending(ChunkAccess chunk, int minCellY, int cellCountY, int cellWidth, int cellHeight, byte[] codes, boolean check, long prepNanos,
            long gpuNanos) {
         this.chunk = chunk;
         this.minCellY = minCellY;
         this.cellCountY = cellCountY;
         this.cellWidth = cellWidth;
         this.cellHeight = cellHeight;
         this.codes = codes;
         this.check = check;
         this.prepNanos = prepNanos;
         this.gpuNanos = gpuNanos;
      }

      /** True when the CPU fills the chunk too and the result is compared ({@link #compare}); false: {@link #write}. */
      public boolean check() {
         return this.check;
      }
   }

   private static final class Scratch {
      final FillGpu.Job job = new FillGpu.Job();
      final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
      final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      byte[] codes = new byte[0];
   }

   private static volatile Program program;
   /** C2.5: the other dimensions' programs (the Nether's, the End's...), by their RandomState; no look-ahead there. */
   private static final Map<RandomState, Program> OTHERS = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
   private static volatile boolean failed;
   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
   private static final AtomicInteger ELIGIBLE = new AtomicInteger();
   private static final LongAdder WRITTEN = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder CHECKED_BLOCKS = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final LongAdder SKIP_STRUCTURES = new LongAdder();
   private static final LongAdder SKIP_BLENDING = new LongAdder();
   private static final LongAdder SKIP_OTHER = new LongAdder();
   private static final LongAdder SKIP_BUSY = new LongAdder();
   private static final LongAdder PREP_NANOS = new LongAdder();
   private static final LongAdder GPU_NANOS = new LongAdder();
   private static final LongAdder WRITE_NANOS = new LongAdder();
   private static final LongAdder CPU_NANOS = new LongAdder();
   private static final AtomicInteger LOGGED_DIFFERENCES = new AtomicInteger();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(64);

   private GpuNoiseFill() {
   }

   /** At server start (gpuNoiseFill not OFF, gpuMode not OFF): makes and compiles the fill code on its own thread. */
   public static void start(ServerLevel overworld) {
      Program old = program;
      if (old != null && old.batcher() != null) {
         old.batcher().stop();
      }

      Ahead oldAhead = ahead;
      ahead = null;
      if (oldAhead != null) {
         oldAhead.stop();
      }

      program = null;
      synchronized (OTHERS) {
         for (Program other : OTHERS.values()) {
            if (other.batcher() != null) {
               other.batcher().stop();
            }
         }

         OTHERS.clear();
      }

      failed = false;
      if (Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      if (!overworld.getChunkSource().getGenerator().getClass().getName().contains("TerraForged") && !Config.GPU_NOISE_FILL_VANILLA.get() && !com.wgaccel.c2me.AreaStep.on()) {
         WorldgenAccelerator.LOG.info("WGA GPU noise fill: off in this world (not FreeTerraForged's; gpuNoiseFillVanilla is false)");
         return;
      }

      com.wgaccel.gpu.GpuPrepare.Task task = com.wgaccel.gpu.GpuPrepare.begin("noise fill");
      Thread thread = new Thread(() -> {
         long start = System.nanoTime();
         try {
            program = build(overworld);
            if (Config.GPU_LOOK_AHEAD.get() == Config.GpuFilterMode.ON && Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.ON) {
               ahead = new Ahead(program, lookAheadBudget());
            }

            task.done();
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU noise fill: ready in %.0f ms (%s, aquifer %s, ore veins %s); chunks now fill on the GPU (%s)",
               (System.nanoTime() - start) / 1.0e6, Config.GPU_NOISE_FILL.get(), program.aquiferKind == 2 ? "C2ME's" : "vanilla's", program.veins ? "on" : "off",
               Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.ON ? "every 64th checked" : "every chunk checked"));
         } catch (DensityCodegen.Unsupported unsupported) {
            task.failed(unsupported.kind);
            com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off: the density function kind {} has no GPU version", unsupported.kind);
         } catch (Throwable failure) {
            task.failed(failure.toString());
            com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off: {}", com.wgaccel.gpu.GpuSelfTest.describe(failure));
         }
      }, "WGA GPU noise fill compile");
      thread.setDaemon(true);
      thread.start();
   }

   /**
    * C2.5: a dimension other than the overworld loads (after the overworld's {@link #start}): its own fill program, made on
    * its own thread (config gpuNoiseFillOtherDimensions); its chunks fill on the GPU once it is ready. A density function
    * kind with no GPU version leaves only this dimension on the CPU.
    */
   public static void startOther(ServerLevel level) {
      if (!Config.GPU_NOISE_FILL_OTHER_DIMENSIONS.get() || Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF
         || !com.wgaccel.gpu.Lwjgl.PRESENT || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
         return;
      }

      String name = level.dimension().location().toString();
      if (Config.GPU_NOISE_FILL_CPU_DIMENSIONS.get().contains(name)) {
         WorldgenAccelerator.LOG.info("WGA GPU noise fill: off in {} (gpuNoiseFillCpuDimensions)", name);
         return;
      }

      // On the world-loading screen's list too (0.17.2): loaded after the overworld, so after its reset.
      com.wgaccel.gpu.GpuPrepare.Task task = com.wgaccel.gpu.GpuPrepare.begin("noise fill (" + level.dimension().location().getPath() + ")");
      Thread thread = new Thread(() -> {
         long start = System.nanoTime();
         try {
            Program built = build(level);
            OTHERS.put(built.random(), built);
            task.done();
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU noise fill (%s): ready in %.0f ms (aquifer %s, ore veins %s)", name,
               (System.nanoTime() - start) / 1.0e6, built.aquiferKind == 0 ? "disabled" : built.aquiferKind == 2 ? "C2ME's" : "vanilla's", built.veins ? "on" : "off"));
         } catch (DensityCodegen.Unsupported unsupported) {
            task.failed(unsupported.kind);
            com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off in " + name + ": the density function kind {} has no GPU version", unsupported.kind);
         } catch (Throwable failure) {
            task.failed(failure.toString());
            com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off in " + name + ": {}", failure.toString());
         }
      }, "WGA GPU noise fill compile " + name);
      thread.setDaemon(true);
      thread.start();
   }

   private static Program build(ServerLevel overworld) throws Exception {
      if (!(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
         throw new IllegalStateException("the level has no noise generator");
      }

      RandomState random = overworld.getChunkSource().randomState();
      NoiseRouter router = random.router();
      DensityCodegen codegen = new DensityCodegen();
      codegen.hoistColumns(true);
      // Every block-time function first: they register the interpolators whose corners the corner kernel works out.
      String step = codegen.function(DensityCodegen.unwrap(router.finalDensity()), DensityCodegen.Mode.BLOCK_STEP);
      String toggle = codegen.function(DensityCodegen.unwrap(router.veinToggle()), DensityCodegen.Mode.BLOCK_STEP);
      String ridged = codegen.function(DensityCodegen.unwrap(router.veinRidged()), DensityCodegen.Mode.BLOCK_STEP);
      String gap = codegen.function(DensityCodegen.unwrap(router.veinGap()), DensityCodegen.Mode.BLOCK_STEP);
      String barrier = codegen.function(DensityCodegen.unwrap(router.barrierNoise()), DensityCodegen.Mode.BLOCK_STEP);
      List<String> corners = new ArrayList<>();
      for (Object input : codegen.interpolatorInputs()) {
         corners.add(codegen.function(input, DensityCodegen.Mode.CORNER));
      }

      String source = codegen.fillSource(step, corners, toggle, ridged, gap, barrier);
      try {
         String file = overworld.dimension() == net.minecraft.world.level.Level.OVERWORLD ? "wgaccel-noise-fill.cu"
            : "wgaccel-noise-fill-" + overworld.dimension().location().getPath() + ".cu";
         Files.writeString(FMLPaths.GAMEDIR.get().resolve("logs").resolve(file), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      Object oreRandom = random.oreRandom();
      boolean veins = generator.generatorSettings().value().oreVeinsEnabled();
      // The vein seeds only when there are veins: the Nether and the End use the legacy random (no seedLo / seedHi).
      long seedLo = veins ? (Long)field(oreRandom, "seedLo") : 0L;
      long seedHi = veins ? (Long)field(oreRandom, "seedHi") : 0L;
      int aquiferKind = 1;
      for (java.lang.reflect.Field each : Aquifer.NoiseBasedAquifer.class.getDeclaredFields()) {
         if (each.getName().equals("c2me$packed1")) {
            aquiferKind = 2;
         }
      }

      if (!generator.generatorSettings().value().aquifersEnabled()) {
         // C2.5: the Nether's kind: the disabled aquifer (the global fluid picker only, no fluid updates).
         aquiferKind = 0;
      }

      BlockState[] table = {generator.generatorSettings().value().defaultBlock(), Blocks.COPPER_ORE.defaultBlockState(),
         Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
         Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState(), AIR, Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState()};
      boolean[] fluid = new boolean[16];
      for (int i = 0; i < table.length; i++) {
         fluid[i] = !table[i].getFluidState().isEmpty();
      }

      FillGpu gpu = new FillGpu(GpuService.context(), source, codegen.noiseData(), codegen.permutations(), corners.size(),
         codegen.columnFunctions().size());
      // S7: the Beardifier's kernel table, as the game made it.
      java.lang.reflect.Field kernel = net.minecraft.world.level.levelgen.Beardifier.class.getDeclaredField("BEARD_KERNEL");
      kernel.setAccessible(true);
      gpu.setBeardKernel((float[])kernel.get(null));
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU noise fill: final density with %d interpolators, %d CellSampler inputs and %d subtrees worked out once per column, CUDA source %,d KB",
         corners.size(), codegen.cellSamplers().size(), codegen.columnFunctions().size(), source.length() / 1024));
      SAMPLED.set(0);
      int batch = Config.GPU_BATCH_CHUNKS.get();

      com.wgaccel.gpu.FillBatcher batcher = batch > 0 ? new com.wgaccel.gpu.FillBatcher(gpu, batch, 2, 200L) : null;
      return new Program(random, gpu, batcher, List.copyOf(codegen.cellSamplers()), seedLo, seedHi, veins, aquiferKind, table, fluid, codegen.noiseData(),
         codegen.permutations(), corners.size(), codegen.columnFunctions().size(), overworld, generator, fluidPicker(generator));
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(name);
   }

   /** The state code of a fluid status type: 7 air, 8 water, 9 lava (default states), -1 anything else (CPU then). */
   private static int fluidCode(BlockState state) {
      if (state == AIR) {
         return 7;
      } else if (state == Blocks.WATER.defaultBlockState()) {
         return 8;
      } else if (state == Blocks.LAVA.defaultBlockState()) {
         return 9;
      }

      return -1;
   }

   /**
    * At the start of a chunk's fill (inside FreeTerraForged's tile stage): the chunk worked out on the GPU, or null
    * when it stays on the CPU (off, not ready, not the overworld, structures, blending, an unsupported aquifer).
    */
   public static Pending prepare(NoiseChunk noise, ChunkAccess chunk, RandomState random, int minCellY, int cellCountY) {
      Program p = program;
      boolean main = p != null && random == p.random;
      if (!main) {
         p = OTHERS.isEmpty() ? null : OTHERS.get(random);
      }

      if (p == null || failed || Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.OFF) {
         return null;
      }

      NoiseChunkAccessor access = (NoiseChunkAccessor)noise;
      if (access.wgaccel$blender() != Blender.empty()) {
         SKIP_BLENDING.increment();
         dropAhead(chunk);
         return null;
      }

      boolean structured = DensityTap.hasStructures(access.wgaccel$beardifier());
      if (structured && !Config.GPU_STRUCTURES.get()) {
         SKIP_STRUCTURES.increment();
         dropAhead(chunk);
         return null;
      }

      if (structured) {
         // S7: filled on the GPU with its Beardifier: from the look-ahead only if made from this chunk itself at its biome
         // step (afterBiomes); a stand-in's result has no structures.
         STRUCTURED.increment();
      }

      int cellWidth = access.wgaccel$cellWidth();
      int cellHeight = access.wgaccel$cellHeight();
      if (!(chunk instanceof ProtoChunk) || access.wgaccel$cellNoiseMinY() != minCellY || cellWidth <= 0 || 16 % cellWidth != 0 || cellCountY <= 0
         || (p.aquiferKind == 0) == noise.aquifer() instanceof Aquifer.NoiseBasedAquifer) {
         SKIP_OTHER.increment();
         return null;
      }

      boolean on = Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.ON;
      Ahead look = main ? ahead : null;
      if (look != null && on) {
         Pending ready = look.take(chunk, minCellY, cellCountY, cellWidth, cellHeight, structured);
         if (ready != null) {
            ready.program = p;
            return ready;
         }
      }

      if (structured && main) {
         dropAhead(chunk);
      }

      Object place = !on ? com.wgaccel.gpu.GpuQueue.NONE : p.batcher() != null ? (p.batcher().busy() ? null : com.wgaccel.gpu.GpuQueue.NONE)
         : com.wgaccel.gpu.GpuQueue.tryFill();
      if (place == null) {
         SKIP_BUSY.increment();
         return null;
      }

      try {
         long start = System.nanoTime();
         Scratch scratch = SCRATCH.get();
         FillGpu.Job job = scratch.job;
         if (!inputs(p, noise, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), minCellY, cellCountY, cellWidth, cellHeight, job, scratch)) {
            SKIP_OTHER.increment();
            return null;
         }

         if (Config.GPU_LOOK_AHEAD.get() == Config.GpuFilterMode.CHECK && LOOK_CHECK_TURN.getAndIncrement() % 8 == 0) {
            checkStandIn(p, chunk.getPos(), minCellY, job);
         }

         long gpuStart = System.nanoTime();
         if (scratch.codes.length < 256 * cellCountY * cellHeight) {
            scratch.codes = new byte[256 * cellCountY * cellHeight];
         }

         // The thread's own buffer: this chunk is written or compared before the thread fills another.
         byte[] codes = scratch.codes;
         if (p.batcher() != null) {
            p.batcher().fill(job, codes);
         } else {
            p.gpu.fill(job, codes);
         }
         long end = System.nanoTime();
         int sample = SAMPLED.getAndIncrement();
         if (sample < SAMPLES && main) {
            writeSample(p, job, codes, 256 * cellCountY * cellHeight, sample);
         }
         boolean debug = debugChunk(chunk);
         boolean check = Config.GPU_NOISE_FILL.get() == Config.GpuFilterMode.CHECK || ELIGIBLE.getAndIncrement() % checkEvery() == 0 || debug;
         Pending pending = new Pending(chunk, minCellY, cellCountY, cellWidth, cellHeight, codes, check, gpuStart - start, end - gpuStart);
         pending.program = p;
         pending.debug = debug;
         PREP_NANOS.add(pending.prepNanos);
         GPU_NANOS.add(pending.gpuNanos);
         if (check && !snapshot(pending)) {
            SKIP_OTHER.increment();
            return null;
         }

         return pending;
      } catch (Throwable failure) {
         failed = true;
         com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off (chunks fill on the CPU from now on): {}", failure.toString());
         return null;
      } finally {
         com.wgaccel.gpu.GpuQueue.release(place);
      }
   }

   /**
    * A chunk's fill inputs into the job, from its noise chunk (inside FreeTerraForged's tile stage): the CellSampler
    * values on the 17 x 17 column grid through the noise chunk's own wrapped samplers, the aquifer's cells and statuses
    * (cached in the noise chunk's aquifer), the chunk's cell range and the world's constants. False when the aquifer has
    * something the GPU does not know.
    */
   private static boolean inputs(Program p, NoiseChunk noise, int chunkX, int chunkZ, int minCellY, int cellCountY, int cellWidth, int cellHeight,
         FillGpu.Job job, Scratch scratch) {
      NoiseChunkAccessor access = (NoiseChunkAccessor)noise;
      job.beard = beard(access.wgaccel$beardifier());
      List<Object> inputs = p.cellSamplers;
      if (job.cells == null || job.cells.length != Math.max(1, inputs.size() * 289)) {
         job.cells = new double[Math.max(1, inputs.size() * 289)];
      }

      for (int k = 0; k < inputs.size(); k++) {
         DensityFunction wrapped = access.wgaccel$wrapped().get((DensityFunction)inputs.get(k));
         if (wrapped == null) {
            throw new IllegalStateException("the noise chunk has no wrapped form of CellSampler " + inputs.get(k));
         }

         for (int lx = 0; lx <= 16; lx++) {
            for (int lz = 0; lz <= 16; lz++) {
               job.cells[k * 289 + lx * 17 + lz] = wrapped.compute(new DensityFunction.SinglePointContext(chunkX + lx, 0, chunkZ + lz));
            }
         }
      }

      int minY = minCellY * cellHeight;
      if (p.aquiferKind == 0) {
         // The disabled aquifer: only the global fluid picker (the one the generator hands its noise chunks).
         if (!picks(p.fluidPicker.get(), job, chunkX, chunkZ)) {
            return false;
         }

         job.cellsUsed = 0;
         java.util.Arrays.fill(job.grid, 0);
      } else if (!aquifer(noise.aquifer(), job, scratch, chunkX, chunkZ, minY, minY + cellCountY * cellHeight - 1)) {
         return false;
      }

      job.chunkX = chunkX;
      job.chunkZ = chunkZ;
      job.cellMinY = minCellY;
      job.cellCountY = cellCountY;
      job.cellWidth = cellWidth;
      job.cellHeight = cellHeight;
      job.seedLo = p.seedLo;
      job.seedHi = p.seedHi;
      job.veins = p.veins;
      job.aquifer = p.aquiferKind;
      return true;
   }

   // G4b: fills worked out ahead (config gpuLookAhead ON).
   private static volatile Ahead ahead;

   /** The 4 x 4 area step: whether the look-ahead still has this chunk queued or being made (its result not ready yet). */
   public static boolean aheadPending(long key) {
      Ahead look = ahead;
      return look != null && look.states.get(key) == Ahead.QUEUED;
   }

   /** The 4 x 4 area step: the chunk queued (at the front) unless already queued, made, or done (structure terrain). */
   public static void aheadSoon(long key) {
      Ahead look = ahead;
      if (look != null && look.states.putIfAbsent(key, Ahead.QUEUED) == null) {
         look.noticed.increment();
         look.soon.add(key);
      }
   }

   /** At a new overworld chunk's structure references: its fill may be worked out ahead. */
   public static void noticed(ServerLevel level, net.minecraft.world.level.ChunkPos pos, boolean structured) {
      Ahead look = ahead;
      if (look != null && level == look.p.level()) {
         look.notice(pos, structured);
      }
   }

   /**
    * Whether the chunk's references include a structure that shapes the terrain (its Beardifier then has pieces: it
    * takes them from these references). Such chunks are filled at their noise step (run of 0.9.45: 1,610 made ahead
    * for nothing).
    */
   public static boolean structureTerrain(ChunkAccess chunk) {
      for (var entry : chunk.getAllReferences().entrySet()) {
         if (entry.getKey().terrainAdaptation() != net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE && !entry.getValue().isEmpty()) {
            return true;
         }
      }

      return false;
   }

   /**
    * How many made chunks may wait: gpuLookAheadChunks, but at least 4,096 (about 480 MB) with a heap over 10 GB: with
    * whole 4 x 4 areas noticed, 2,048 pushed out many results before their chunks came (run of 0.9.44: 8,956 dropped).
    */
   private static int lookAheadBudget() {
      int wanted = Config.GPU_LOOK_AHEAD_CHUNKS.get();
      return Runtime.getRuntime().maxMemory() > 10L * 1024 * 1024 * 1024 ? Math.max(wanted, 4096) : wanted;
   }

   /**
    * Right after a chunk's biome step: a chunk with structure terrain has its noise chunk now (vanilla makes it in the
    * biome step, its Beardifier included), so its fill job is made from it and handed to the look-ahead at the front,
    * ready for its noise step (0.9.88's vanilla profile: these chunks filled on the CPU, Beardifier 7.9% of the CPU).
    * Anything unusual: nothing (the noise step does it as before).
    */
   public static void afterBiomes(ChunkAccess chunk, RandomState random) {
      Program p = program;
      Ahead look = ahead;
      if (p == null || failed || look == null || random != p.random || Config.GPU_NOISE_FILL.get() != Config.GpuFilterMode.ON || !Config.GPU_STRUCTURES.get()
         || !(chunk instanceof ProtoChunk)) {
         return;
      }

      try {
         NoiseChunk noise = ((com.wgaccel.mixin.access.ChunkAccessAccessor)chunk).wgaccel$noiseChunk();
         if (noise == null) {
            return;
         }

         NoiseChunkAccessor access = (NoiseChunkAccessor)noise;
         if (access.wgaccel$blender() != Blender.empty() || !DensityTap.hasStructures(access.wgaccel$beardifier())
            || !(noise.aquifer() instanceof Aquifer.NoiseBasedAquifer)) {
            return;
         }

         int cellWidth = access.wgaccel$cellWidth();
         int cellHeight = access.wgaccel$cellHeight();
         net.minecraft.world.level.levelgen.NoiseSettings full = p.generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunk);
         int minCellY = Math.floorDiv(full.minY(), full.getCellHeight());
         int cellCountY = Math.min(Math.floorDiv(full.height(), full.getCellHeight()), access.wgaccel$cellCountY());
         if (access.wgaccel$cellNoiseMinY() != minCellY || cellWidth <= 0 || 16 % cellWidth != 0 || cellCountY <= 0) {
            return;
         }

         Scratch scratch = new Scratch();
         if (inputs(p, noise, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), minCellY, cellCountY, cellWidth, cellHeight, scratch.job, scratch)) {
            look.submitPrepared(chunk.getPos().toLong(), scratch.job);
         }
      } catch (Throwable ignored) {
         // The noise step fills it as before.
      }
   }

   private static void dropAhead(ChunkAccess chunk) {
      Ahead look = ahead;
      if (look != null) {
         look.drop(chunk.getPos());
      }
   }

   /**
    * The look-ahead: chunks noticed at their structure references are queued; two threads take up to 64 at a time, make
    * their inputs from stand-in chunks ({@link #standIn}, proven the same as the real chunks' by gpuLookAhead CHECK), run
    * them in one launch per kernel ({@link FillGpu#fillBatch}, on the thread's own lane) and keep the codes until the
    * chunk's noise step takes them ({@link #take}: only when its cell range is the one the stand-in had; chunks with
    * structure terrain or blending never get here). At most {@code budget} results wait; the oldest are dropped.
    */
   private static final class Ahead {
      private static final Object QUEUED = new Object();
      private static final Object TAKEN = new Object();
      private static final Object DONE = new Object();
      private static final int DONE_KEEP = 65_536;
      private static final int BATCH = 64;

      /** real: made from the chunk's own noise chunk at its biome step (structure pieces included), not a stand-in. */
      private record Result(int minCellY, int cellCountY, int cellWidth, int cellHeight, byte[] codes, boolean real, FillGpu.Job job) {
      }

      /** Results whose inputs were no longer the chunk's at its noise step (not used; the chunk filled the usual way). */
      final LongAdder inputsChanged = new LongAdder();
      private final AtomicInteger inputsLogged = new AtomicInteger();

      /** Jobs made from real chunks (structure chunks at their biome step), used instead of a stand-in for their keys. */
      private final java.util.concurrent.ConcurrentHashMap<Long, FillGpu.Job> prepared = new java.util.concurrent.ConcurrentHashMap<>();
      private final java.util.Set<Long> realKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
      final LongAdder preparedMade = new LongAdder();
      final LongAdder preparedUsed = new LongAdder();

      /** A structure chunk's job from its real noise chunk: queued at the front, made like the others (marked real). */
      void submitPrepared(long key, FillGpu.Job job) {
         this.prepared.put(key, job);
         this.realKeys.add(key);
         this.states.put(key, QUEUED);
         this.soon.add(key);
      }

      final Program p;
      private final int budget;
      private final java.util.concurrent.LinkedBlockingQueue<Long> wanted = new java.util.concurrent.LinkedBlockingQueue<>();
      /** The noticed chunks themselves (real chunks, their noise step next): made before the area mates queued earlier. */
      private final java.util.concurrent.LinkedBlockingQueue<Long> soon = new java.util.concurrent.LinkedBlockingQueue<>();
      private final java.util.concurrent.ConcurrentHashMap<Long, Object> states = new java.util.concurrent.ConcurrentHashMap<>();
      private final java.util.concurrent.ConcurrentLinkedQueue<Long> readyOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
      private final AtomicInteger readyCount = new AtomicInteger();
      private final java.util.concurrent.ConcurrentLinkedQueue<Long> doneOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
      private final AtomicInteger doneCount = new AtomicInteger();
      private final List<Thread> threads = new ArrayList<>();
      private volatile boolean stopped;
      final LongAdder noticed = new LongAdder();
      final LongAdder areaMates = new LongAdder();
      private static final long FILL_WAIT_NANOS = 0L;
      final LongAdder waited = new LongAdder();
      final LongAdder waitedInTime = new LongAdder();
      final LongAdder structuredSkipped = new LongAdder();
      final LongAdder made = new LongAdder();
      final LongAdder used = new LongAdder();
      final LongAdder late = new LongAdder();
      final LongAdder mismatched = new LongAdder();
      final LongAdder dropped = new LongAdder();
      final LongAdder skipped = new LongAdder();
      final LongAdder batches = new LongAdder();
      final LongAdder inputNanos = new LongAdder();
      final LongAdder gpuNanos = new LongAdder();
      private final AtomicInteger failures = new AtomicInteger();

      Ahead(Program p, int budget) {
         this.p = p;
         this.budget = budget;
         // Two threads: three (run AF) made smaller launches and more GPU time per chunk (3.2 -> 5.1 ms) for few more ready.
         // FreeTerraForged without tiles (1.0.0013): a stand-in's chunk cells are the heavy part and are made here (shared
         // with the chunk, FtfChunkCells), so more threads ({@link com.wgaccel.ftf.FtfSlowPaths#lookAheadThreads}).
         for (int i = 0; i < com.wgaccel.ftf.FtfSlowPaths.lookAheadThreads(2); i++) {
            Thread thread = new Thread(this::run, "WGA GPU look-ahead " + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            this.threads.add(thread);
            thread.start();
         }
      }

      void stop() {
         this.stopped = true;
         for (Thread thread : this.threads) {
            thread.interrupt();
         }
      }

      void notice(net.minecraft.world.level.ChunkPos pos, boolean structured) {
         long self = pos.toLong();
         if (com.wgaccel.c2me.AreaStep.on()) {
            // The 4 x 4 area step queues the chunks itself (only its areas' chunks wait in line); here only the chunks
            // with structure terrain are marked, so the area job leaves them to their noise step.
            if (structured) {
               this.structuredSkipped.increment();
               this.drop(pos);
            }

            return;
         }

         if (structured) {
            this.structuredSkipped.increment();
            this.drop(pos);
         } else if (this.states.putIfAbsent(self, QUEUED) == null) {
            this.noticed.increment();
            this.soon.add(self);
         } else if (this.states.get(self) == QUEUED) {
            // Queued already as an area mate: to the front now (the maker skips the copy it meets later).
            this.soon.add(self);
         }

         if (!Config.GPU_AREA_BATCHES.get()) {
            return;
         }

         // S5: the chunk's whole aligned 4 x 4 area at once, so the launches are bigger (a mate whose noise step comes
         // with another cell range, structures or blending simply does not use its result).
         int ax = pos.x & ~3;
         int az = pos.z & ~3;
         for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
               if (this.noticeOne(net.minecraft.world.level.ChunkPos.asLong(ax + dx, az + dz)) && (ax + dx != pos.x || az + dz != pos.z)) {
                  this.areaMates.increment();
               }
            }
         }
      }

      private boolean noticeOne(long key) {
         if (this.states.putIfAbsent(key, QUEUED) == null) {
            this.noticed.increment();
            this.wanted.add(key);
            return true;
         }

         return false;
      }

      void drop(net.minecraft.world.level.ChunkPos pos) {
         // A QUEUED chunk now DONE: its maker skips it when it sees this.
         if (this.done(pos.toLong()) instanceof Result) {
            this.readyCount.decrementAndGet();
         }
      }

      /**
       * The chunk's step has run (or it was dropped): marked DONE, so an area mate's notice never queues it again (run
       * of 0.9.44: a third of the look-ahead's GPU work was such chunks, made again and never used). The newest 65,536
       * are remembered. Returns what the chunk had before.
       */
      private Object done(long key) {
         Object before = this.states.put(key, DONE);
         if (before != DONE) {
            this.doneOrder.add(key);
            if (this.doneCount.incrementAndGet() > DONE_KEEP) {
               Long old = this.doneOrder.poll();
               if (old != null) {
                  this.doneCount.decrementAndGet();
                  this.states.remove(old, DONE);
               }
            }
         }

         return before;
      }

      /** The chunk's ready fill as a pending write (or check), or null: not made yet, or made for another cell range. */
      Pending take(ChunkAccess chunk, int minCellY, int cellCountY, int cellWidth, int cellHeight) {
         return this.take(chunk, minCellY, cellCountY, cellWidth, cellHeight, false);
      }

      /** As take; with realOnly (a structure chunk) only a result made from the real chunk is used. */
      Pending take(ChunkAccess chunk, int minCellY, int cellCountY, int cellWidth, int cellHeight, boolean realOnly) {
         long waitKey = chunk.getPos().toLong();
         if (this.states.get(waitKey) == QUEUED) {
            // Still queued or on the card: to the front and a short wait for it, as the biome step does (0.9.91, vanilla
            // and CPU-bound: 3,275 chunks not ready in time and 4,071 more while the GPU was busy filled on the CPU at
            // ~18 ms each; waiting for a result already being made is far cheaper).
            this.soon.add(waitKey);
            this.waited.increment();
            // 0.9.92 waited 25 ms: 3,009 waits, only 260 in time (the GPU 93% busy), 1:25 vs 1:22: no wait now (0 ms).
            long until = System.nanoTime() + FILL_WAIT_NANOS;
            while (this.states.get(waitKey) == QUEUED && System.nanoTime() < until) {
               java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            }

            if (this.states.get(waitKey) instanceof Result) {
               this.waitedInTime.increment();
            }
         }

         long key = chunk.getPos().toLong();
         Object state = this.done(key);
         if (!(state instanceof Result result)) {
            if (state == QUEUED) {
               // Still waiting or being made: the maker skips it, as it is DONE now.
               this.late.increment();
            }

            return null;
         }

         this.readyCount.decrementAndGet();
         if (realOnly && !result.real) {
            return null;
         }

         if (result.real) {
            this.preparedUsed.increment();
         }

         if (result.minCellY != minCellY || result.cellCountY != cellCountY || result.cellWidth != cellWidth || result.cellHeight != cellHeight) {
            this.mismatched.increment();
            return null;
         }

         // The identical-world rule (the user, 2026-10-22): a result made earlier (a stand-in's, or a structure chunk's at
         // its biome step) is used only if the chunk's own inputs now are the same as the ones it was made from (0.11.0's
         // only fill difference: a structure chunk's job made at its biome step, its inputs changed by the noise step;
         // the GPU from the noise step's inputs gave the game's blocks). The same inputs give the same blocks.
         String changed = this.changedInputs(chunk, result.job);
         if (changed != null) {
            this.inputsChanged.increment();
            if (this.inputsLogged.incrementAndGet() <= 8) {
               WorldgenAccelerator.LOG.info("WGA GPU look-ahead: chunk {}'s inputs changed since its {} was made ({}); filled the usual way", chunk.getPos(),
                  result.real ? "structure-chunk job (at its biome step)" : "stand-in", changed);
            }

            return null;
         }

         this.used.increment();
         boolean debug = debugChunk(chunk);
         boolean check = ELIGIBLE.getAndIncrement() % checkEvery() == 0 || debug;
         Pending pending = new Pending(chunk, minCellY, cellCountY, cellWidth, cellHeight, result.codes, check, 0L, 0L);
         pending.fromAhead = true;
         pending.debug = debug;
         pending.real = result.real;
         pending.program = this.p;
         if (check && !snapshot(pending)) {
            SKIP_OTHER.increment();
            return null;
         }

         return pending;
      }

      private final ThreadLocal<Scratch> verify = ThreadLocal.withInitial(Scratch::new);

      String memorySummary() {
         return String.format(Locale.ROOT, "fill look-ahead states %,d (results %,d), prepared %,d, real keys %,d, queues %,d + %,d, done %,d", this.states.size(),
            this.readyCount.get(), this.prepared.size(), this.realKeys.size(), this.wanted.size(), this.soon.size(), this.doneCount.get());
      }

      /** The first input in which the chunk's own inputs now differ from the job's, or null when they are the same. */
      private String changedInputs(ChunkAccess chunk, FillGpu.Job made) {
         try {
            NoiseChunk noise = ((com.wgaccel.mixin.access.ChunkAccessAccessor)chunk).wgaccel$noiseChunk();
            if (noise == null || made == null) {
               return "no noise chunk to compare with";
            }

            Scratch scratch = this.verify.get();
            if (!inputs(this.p, noise, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), made.cellMinY, made.cellCountY, made.cellWidth,
               made.cellHeight, scratch.job, scratch)) {
               return "its aquifer has something the GPU does not know";
            }

            return difference(scratch.job, made);
         } catch (Throwable failure) {
            return failure.toString();
         }
      }

      private void run() {
         com.wgaccel.gpu.GpuStreams.background();
         List<Long> keys = new ArrayList<>();
         List<FillGpu.Job> jobs = new ArrayList<>();
         List<byte[]> codes = new ArrayList<>();
         List<Long> made = new ArrayList<>();
         java.util.Set<Long> inBatch = new java.util.HashSet<>();
         while (!this.stopped) {
            keys.clear();
            try {
               Long first = this.soon.poll();
               if (first == null) {
                  first = this.wanted.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS);
               }

               if (first == null) {
                  continue;
               }

               keys.add(first);
               this.soon.drainTo(keys, BATCH - keys.size());
               this.wanted.drainTo(keys, BATCH - keys.size());
               if (keys.size() < BATCH) {
                  // Nobody waits on these yet: a moment for more to gather into the launch.
                  Long more = this.wanted.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                  if (more != null) {
                     keys.add(more);
                     this.wanted.drainTo(keys, BATCH - keys.size());
                  }
               }
            } catch (InterruptedException interrupted) {
               break;
            }

            jobs.clear();
            codes.clear();
            made.clear();
            long start = System.nanoTime();
            inBatch.clear();
            for (Long key : keys) {
               if (!inBatch.add(key)) {
                  continue;
               }

               if (this.states.get(key) != QUEUED) {
                  this.states.remove(key, TAKEN);
                  this.skipped.increment();
                  continue;
               }

               try {
                  FillGpu.Job prepared = this.prepared.remove(key);
                  FillGpu.Job job = prepared != null ? prepared : standIn(this.p, new net.minecraft.world.level.ChunkPos(key), Integer.MIN_VALUE, new Scratch());
                  if (job == null) {
                     this.states.remove(key, QUEUED);
                     this.skipped.increment();
                     continue;
                  }

                  jobs.add(job);
                  codes.add(new byte[256 * job.cellCountY * job.cellHeight]);
                  made.add(key);
               } catch (Throwable failure) {
                  this.states.remove(key, QUEUED);
                  this.skipped.increment();
                  if (this.failures.incrementAndGet() <= 8) {
                     WorldgenAccelerator.LOG.warn("WGA GPU look-ahead: chunk {}'s stand-in failed: {}", new net.minecraft.world.level.ChunkPos(key), failure.toString());
                  }
               }
            }

            long gpuStart = System.nanoTime();
            this.inputNanos.add(gpuStart - start);
            if (jobs.isEmpty()) {
               continue;
            }

            try {
               this.p.gpu.fillBatch(jobs, codes);
            } catch (Throwable failure) {
               com.wgaccel.WgaChat.warnOff("WGA GPU look-ahead off: {}", failure.toString());
               ahead = null;
               this.stop();
               return;
            }

            this.gpuNanos.add(System.nanoTime() - gpuStart);
            this.batches.increment();
            for (int i = 0; i < made.size(); i++) {
               FillGpu.Job job = jobs.get(i);
               Long key = made.get(i);
               boolean real = this.realKeys.remove(key);
               if (real) {
                  this.preparedMade.increment();
               }

               Result result = new Result(job.cellMinY, job.cellCountY, job.cellWidth, job.cellHeight, codes.get(i), real, job);
               if (this.states.replace(key, QUEUED, result)) {
                  this.made.increment();
                  this.readyCount.incrementAndGet();
                  this.readyOrder.add(key);
               } else {
                  this.states.remove(key, TAKEN);
               }
            }

            // Past the budget: the oldest results go (chunks noticed but never filled, at the edge of what was asked).
            while (this.readyCount.get() > this.budget) {
               Long old = this.readyOrder.poll();
               if (old == null) {
                  break;
               }

               Object state = this.states.get(old);
               if (state instanceof Result && this.states.remove(old, state)) {
                  this.readyCount.decrementAndGet();
                  this.dropped.increment();
               }
            }
         }
      }

      String summary() {
         long b = Math.max(1, this.batches.sum());
         long m = Math.max(1, this.made.sum());
         return String.format(Locale.ROOT, "; look-ahead: %,d noticed (%,d as area mates, %,d with structure terrain skipped), %,d made in %,d launches (%.1f chunks each; inputs %.2f ms and GPU %.2f ms per chunk on its own threads), %,d used, %,d not ready in time (%,d waited for, %,d of them in time), %,d other cell range, %,d dropped, %,d waiting of %,d, structure chunks made at their biome step %,d (%,d used), %,d not used: inputs changed since made",
            this.noticed.sum(), this.areaMates.sum(), this.structuredSkipped.sum(), this.made.sum(), this.batches.sum(), (double)this.made.sum() / b, this.inputNanos.sum() / 1.0e6 / m, this.gpuNanos.sum() / 1.0e6 / m,
            this.used.sum(), this.late.sum(), this.waited.sum(), this.waitedInTime.sum(), this.mismatched.sum(), this.dropped.sum(), this.readyCount.get(), this.budget, this.preparedMade.sum(), this.preparedUsed.sum(), this.inputsChanged.sum());
      }
   }

   // G4b.0: the look-ahead's way of making a chunk's inputs, checked against the real chunk's (config gpuLookAhead).
   private static final ThreadLocal<Scratch> STAND_IN = ThreadLocal.withInitial(Scratch::new);
   private static final AtomicInteger LOOK_CHECK_TURN = new AtomicInteger();
   private static final LongAdder LOOK_CHECKED = new LongAdder();
   private static final LongAdder LOOK_DIFFERENT = new LongAdder();
   private static final LongAdder LOOK_FAILED = new LongAdder();
   private static final LongAdder LOOK_NANOS = new LongAdder();
   private static final AtomicInteger LOOK_LOGGED = new AtomicInteger();
   private static volatile java.lang.reflect.Method beginTileStage;
   private static volatile java.lang.reflect.Method endTileStage;

   @SuppressWarnings("unchecked")
   static java.util.function.Supplier<Aquifer.FluidPicker> fluidPicker(NoiseBasedChunkGenerator generator) throws ReflectiveOperationException {
      return (java.util.function.Supplier<Aquifer.FluidPicker>)field(generator, "globalFluidPicker");
   }

   /**
    * A chunk's inputs made without the game's chunk: a stand-in ProtoChunk, its own noise chunk (an empty Beardifier as
    * the game makes for a chunk without structures, no blending, cut to the ground as the game's would be) and FreeTerraForged's tile stage around the reading, as
    * doFill has it. The chunk's cell range: minCellY as the game's, the count the stand-in's noise chunk gives. Null
    * when the aquifer has something the GPU does not know. The job is this thread's own (reused).
    */
   private static FillGpu.Job standIn(Program p, net.minecraft.world.level.ChunkPos pos, int minCellY, Scratch scratch) throws ReflectiveOperationException {
      net.minecraft.world.level.chunk.ProtoChunk proto = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
         p.level, p.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null);
      net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings = p.generator.generatorSettings().value();
      NoiseChunk noise = NoiseChunk.forChunk(proto, p.random, new net.minecraft.world.level.levelgen.Beardifier(
         new it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.level.levelgen.Beardifier.Rigid>().iterator(),
         new it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>().iterator()), settings,
         p.fluidPicker.get(), Blender.empty());
      tileStage(noise, true);
      try {
         NoiseChunkAccessor access = (NoiseChunkAccessor)noise;
         net.minecraft.world.level.levelgen.NoiseSettings full = settings.noiseSettings().clampToHeightAccessor(proto);
         int cellCountY = Math.min(Math.floorDiv(full.height(), full.getCellHeight()), access.wgaccel$cellCountY());
         if (minCellY == Integer.MIN_VALUE) {
            // As NoiseBasedChunkGenerator.fillFromNoise works it out.
            minCellY = Math.floorDiv(full.minY(), full.getCellHeight());
         }

         return inputs(p, noise, pos.getMinBlockX(), pos.getMinBlockZ(), minCellY, cellCountY, access.wgaccel$cellWidth(), access.wgaccel$cellHeight(),
            scratch.job, scratch) ? scratch.job : null;
      } finally {
         tileStage(noise, false);
      }
   }

   /** FreeTerraForged's NoiseChunkTileOwner.beginTileStage / endTileStage on the noise chunk (by name: FTF is optional). */
   static void tileStage(NoiseChunk noise, boolean begin) throws ReflectiveOperationException {
      if (tileOwner == null) {
         try {
            Class<?> owner = Class.forName("etcodehome.freeterraforged.world.worldgen.densityfunction.tile.NoiseChunkTileOwner");
            endTileStage = owner.getMethod("freeterraforged$endTileStage");
            beginTileStage = owner.getMethod("freeterraforged$beginTileStage");
            tileOwner = owner;
         } catch (ClassNotFoundException absent) {
            // No FreeTerraForged (C2, vanilla: 0.9.79's look-ahead failed on every stand-in here): no tile stage.
            tileOwner = Void.class;
         }
      }

      if (tileOwner.isInstance(noise)) {
         (begin ? beginTileStage : endTileStage).invoke(noise);
      }
   }

   private static volatile Class<?> tileOwner;

   private static final AtomicInteger DIFFER_SAMPLES = new AtomicInteger();

   /**
    * After a fill difference: the chunk's inputs made again from its own noise chunk, run on the GPU again and compared
    * with the blocks that were used (and, for a stand-in's result, a fresh stand-in's inputs with the real ones), so the
    * log says whether the inputs or the GPU code differ. The real job is saved as wgaccel-noise-fill-sample-10N.bin for
    * cudaFillBench. Diagnostics only: anything failing here is just reported.
    */
   private static String diagnose(Pending pending) {
      Program p = pending.program;
      if (p == null) {
         return "";
      }

      ChunkAccess chunk = pending.chunk;
      StringBuilder out = new StringBuilder();
      try {
         NoiseChunk noise = ((com.wgaccel.mixin.access.ChunkAccessAccessor)chunk).wgaccel$noiseChunk();
         if (noise == null) {
            return "; (no noise chunk to diagnose)";
         }

         Scratch scratch = new Scratch();
         if (!inputs(p, noise, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), pending.minCellY, pending.cellCountY, pending.cellWidth,
            pending.cellHeight, scratch.job, scratch)) {
            return "; (its aquifer has something the GPU does not know)";
         }

         int blocks = 256 * pending.cellCountY * pending.cellHeight;
         byte[] again = new byte[Math.max(pending.codes.length, blocks)];
         p.gpu.fill(scratch.job, again);
         int differ = 0;
         for (int i = 0; i < blocks; i++) {
            differ += again[i] != pending.codes[i] ? 1 : 0;
         }

         out.append(String.format(Locale.ROOT, "; the GPU again from the chunk's own inputs: %,d of %,d codes differ from the ones used", differ, blocks));
         if (pending.fromAhead && !pending.real) {
            FillGpu.Job made = standIn(p, chunk.getPos(), pending.minCellY, new Scratch());
            String difference = made == null ? "the stand-in's aquifer has an unknown fluid" : difference(scratch.job, made);
            out.append("; a new stand-in's inputs: ").append(difference == null ? "the same as the chunk's" : difference);
         }

         byte[] game = gameCodes(p, chunk, pending.minCellY * pending.cellHeight, blocks);
         int gameDiffer = 0;
         for (int i = 0; i < blocks; i++) {
            gameDiffer += (again[i] & 15) != game[i] ? 1 : 0;
         }

         out.append(String.format(Locale.ROOT, "; and %,d differ from the game's blocks", gameDiffer));
         int index = DIFFER_SAMPLES.getAndIncrement();
         if (index < 8) {
            writeSample(p, scratch.job, again, blocks, 100 + index, game);
            out.append("; saved as wgaccel-noise-fill-sample-").append(100 + index).append(".bin");
         }
      } catch (Throwable failure) {
         out.append("; (diagnosing failed: ").append(failure).append(')');
      }

      return out.toString();
   }

   /**
    * The game's blocks of a chunk just filled on the CPU, as the fill's state codes (the code's table index; 15: a state
    * the table does not have), in the kernel's order (y, then z, then x).
    */
   private static byte[] gameCodes(Program p, ChunkAccess chunk, int minY, int blocks) {
      byte[] out = new byte[blocks];
      LevelChunkSection[] sections = chunk.getSections();
      java.util.Map<BlockState, Integer> index = new java.util.IdentityHashMap<>();
      for (int i = p.table.length - 1; i >= 0; i--) {
         index.put(p.table[i], i);
      }

      net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = null;
      int section = Integer.MIN_VALUE;
      for (int t = 0; t < blocks; t++) {
         int y = minY + t / 256;
         int s = chunk.getSectionIndex(y);
         if (s != section) {
            section = s;
            states = s >= 0 && s < sections.length ? sections[s].getStates().copy() : null;
         }

         BlockState state = states == null ? AIR : states.get(t % 16, y & 15, t % 256 / 16);
         Integer code = index.get(state);
         out[t] = (byte)(code == null ? 15 : code);
      }

      return out;
   }

   /** CHECK: the stand-in's inputs for this chunk against the real ones just made (the job). */
   private static void checkStandIn(Program p, net.minecraft.world.level.ChunkPos pos, int minCellY, FillGpu.Job real) {
      long start = System.nanoTime();
      try {
         FillGpu.Job made = standIn(p, pos, minCellY, STAND_IN.get());
         LOOK_NANOS.add(System.nanoTime() - start);
         LOOK_CHECKED.increment();
         String difference = made == null ? "the stand-in's aquifer has an unknown fluid" : difference(real, made);
         if (difference != null) {
            LOOK_DIFFERENT.increment();
            if (LOOK_LOGGED.incrementAndGet() <= 12) {
               WorldgenAccelerator.LOG.warn("WGA GPU look-ahead check: chunk {}: the stand-in's inputs differ: {}", pos, difference);
            }
         }
      } catch (Throwable failure) {
         LOOK_FAILED.increment();
         if (LOOK_LOGGED.incrementAndGet() <= 12) {
            WorldgenAccelerator.LOG.warn("WGA GPU look-ahead check: chunk {}: the stand-in failed: {}", pos, failure.toString());
         }
      }
   }

   /** The first field in which two jobs differ, or null when they are the same (doubles compared by their bits). */
   private static String difference(FillGpu.Job a, FillGpu.Job b) {
      if (a.chunkX != b.chunkX || a.chunkZ != b.chunkZ || a.cellMinY != b.cellMinY || a.cellCountY != b.cellCountY || a.cellWidth != b.cellWidth
         || a.cellHeight != b.cellHeight) {
         return String.format(Locale.ROOT, "cells: real y %d + %d (width %d, height %d), stand-in y %d + %d (width %d, height %d)", a.cellMinY, a.cellCountY,
            a.cellWidth, a.cellHeight, b.cellMinY, b.cellCountY, b.cellWidth, b.cellHeight);
      }

      for (int i = 0; i < a.cells.length; i++) {
         if (Double.doubleToRawLongBits(a.cells[i]) != Double.doubleToRawLongBits(b.cells[i])) {
            return String.format(Locale.ROOT, "CellSampler %d at column (%d, %d): real %s, stand-in %s", i / 289, i % 289 / 17, i % 17, a.cells[i], b.cells[i]);
         }
      }

      if (!Arrays.equals(a.grid, b.grid) || a.cellsUsed != b.cellsUsed) {
         return "aquifer grid: real " + Arrays.toString(a.grid) + " (" + a.cellsUsed + " cells), stand-in " + Arrays.toString(b.grid) + " (" + b.cellsUsed + ")";
      }

      if (!Arrays.equals(a.lava, b.lava) || a.sea != b.sea || a.seaType != b.seaType) {
         return "lava levels or sea";
      }

      if (!Arrays.equals(a.beard, b.beard)) {
         return String.format(Locale.ROOT, "structure terrain (Beardifier): real %d ints, made %d", a.beard == null ? -1 : a.beard.length,
            b.beard == null ? -1 : b.beard.length);
      }

      if (a.veins != b.veins || a.aquifer != b.aquifer || a.seedLo != b.seedLo || a.seedHi != b.seedHi) {
         return "ore veins or aquifer kind";
      }

      for (int i = 0; i < 3 * a.cellsUsed; i++) {
         if (a.loc[i] != b.loc[i]) {
            return "aquifer cell centre " + i / 3;
         }

         if (a.status[i] != b.status[i]) {
            return String.format(Locale.ROOT, "aquifer cell %d status (%s): real %d, stand-in %d", i / 3, i % 3 == 0 ? "level" : i % 3 == 1 ? "fluid" : "identity",
               a.status[i], b.status[i]);
         }
      }

      return null;
   }

   /**
    * One chunk's GPU inputs and result as logs/wgaccel-noise-fill-sample-N.bin, beside logs/wgaccel-noise-fill.cu, so
    * the fill can be timed and changed outside the game against real data (gradlew.bat cudaFillBench).
    */
   private static void writeSample(Program p, FillGpu.Job job, byte[] codes, int blocks, int index) {
      writeSample(p, job, codes, blocks, index, null);
   }

   /** As above; with the game's own block codes (version 3), for cudaFillBench to compare the GPU with the game. */
   private static void writeSample(Program p, FillGpu.Job job, byte[] codes, int blocks, int index, byte[] game) {
      java.nio.file.Path path = FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-noise-fill-sample-" + index + ".bin");
      try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(path)))) {
         // Version 2: the chunk's structure pieces and the Beardifier's kernel table at the end (version 1 had none, so
         // offline its structure chunks were worked out without their structure terrain and looked different).
         out.writeInt(game == null ? 2 : 3);
         out.writeInt(p.noise.length);
         for (double value : p.noise) {
            out.writeDouble(value);
         }

         out.writeInt(p.permutations.length);
         for (int value : p.permutations) {
            out.writeInt(value);
         }

         out.writeInt(p.interpolators);
         out.writeInt(p.columns);
         out.writeInt(job.cells.length);
         for (double value : job.cells) {
            out.writeDouble(value);
         }

         int[] header = {job.chunkX, job.chunkZ, job.cellMinY, job.cellCountY, job.cellWidth, job.cellHeight, job.veins ? 1 : 0, job.aquifer, job.sea,
            job.seaType, job.cellsUsed};
         for (int value : header) {
            out.writeInt(value);
         }

         out.writeLong(job.seedLo);
         out.writeLong(job.seedHi);
         for (int value : job.grid) {
            out.writeInt(value);
         }

         for (int value : job.lava) {
            out.writeInt(value);
         }

         for (int i = 0; i < 3 * job.cellsUsed; i++) {
            out.writeInt(job.loc[i]);
         }

         for (int i = 0; i < 3 * job.cellsUsed; i++) {
            out.writeInt(job.status[i]);
         }

         out.writeInt(blocks);
         out.write(codes, 0, blocks);
         out.writeInt(job.beard.length);
         for (int value : job.beard) {
            out.writeInt(value);
         }

         java.lang.reflect.Field kernelField = net.minecraft.world.level.levelgen.Beardifier.class.getDeclaredField("BEARD_KERNEL");
         kernelField.setAccessible(true);
         float[] kernel = (float[])kernelField.get(null);
         out.writeInt(kernel.length);
         for (float value : kernel) {
            out.writeFloat(value);
         }

         if (game != null) {
            out.writeInt(game.length);
            out.write(game);
         }
      } catch (Exception failure) {
         WorldgenAccelerator.LOG.warn("WGA GPU noise fill: could not save a sample: {}", failure.toString());
      }
   }

   /** Each column's lava level and the sea from a fluid picker, into the job; false when a fluid is not air, water or lava. */
   private static boolean picks(Aquifer.FluidPicker picker, FillGpu.Job job, int chunkX, int chunkZ) {
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            FluidStatusAccessor low = (FluidStatusAccessor)(Object)picker.computeFluid(chunkX + lx, -1_000_000, chunkZ + lz);
            int lowType = fluidCode(low.wgaccel$type());
            if (lowType < 0) {
               return false;
            }

            job.lava[lx * 16 + lz] = lowType == 9 ? low.wgaccel$level() : Integer.MIN_VALUE;
            FluidStatusAccessor high = (FluidStatusAccessor)(Object)picker.computeFluid(chunkX + lx, 1_000_000, chunkZ + lz);
            job.sea = high.wgaccel$level();
            job.seaType = fluidCode(high.wgaccel$type());
            if (job.seaType < 0) {
               return false;
            }
         }
      }

      return true;
   }

   /**
    * The aquifer's cells the fill can reach (y from minY to maxY, the three nearest grid layers of each block), in the
    * job: centres from the aquifer's location cache (or vanilla's own draw), fluid statuses by vanilla's getAquiferStatus
    * with an identity number per status object, each column's lava level and the sea from the global fluid picker.
    * False when a fluid type is not plain air, water or lava.
    */
   private static boolean aquifer(Aquifer aquifer, FillGpu.Job job, Scratch scratch, int chunkX, int chunkZ, int minY, int maxY) {
      NoiseBasedAquiferAccessor a = (NoiseBasedAquiferAccessor)aquifer;
      if (!picks(a.wgaccel$picker(), job, chunkX, chunkZ)) {
         return false;
      }

      int minGridX = a.wgaccel$minGridX();
      int minGridY = a.wgaccel$minGridY();
      int minGridZ = a.wgaccel$minGridZ();
      int sizeX = a.wgaccel$gridSizeX();
      int sizeZ = a.wgaccel$gridSizeZ();
      long[] locations = a.wgaccel$locationCache();
      int sizeY = locations.length / (sizeX * sizeZ);
      int low = Math.floorDiv(minY + 1, 12) - 1;
      int high = Math.floorDiv(maxY + 1, 12) + 1;
      if (low < minGridY || high >= minGridY + sizeY) {
         return false;
      }

      int layers = high - low + 1;
      int cells = sizeX * layers * sizeZ;
      if (job.loc.length < 3 * cells) {
         job.loc = new int[3 * cells];
         job.status = new int[3 * cells];
      }

      Map<Object, Integer> identities = scratch.identities;
      identities.clear();
      for (int gy = 0; gy < layers; gy++) {
         for (int gz = 0; gz < sizeZ; gz++) {
            for (int gx = 0; gx < sizeX; gx++) {
               int index = (gy * sizeZ + gz) * sizeX + gx;
               int x = minGridX + gx;
               int y = low + gy;
               int z = minGridZ + gz;
               long packed = locations[((y - minGridY) * sizeZ + gz) * sizeX + gx];
               if (packed == Long.MAX_VALUE) {
                  RandomSource source = a.wgaccel$random().at(x, y, z);
                  packed = BlockPos.asLong(x * 16 + source.nextInt(10), y * 12 + source.nextInt(9), z * 16 + source.nextInt(10));
               }

               job.loc[index * 3] = BlockPos.getX(packed);
               job.loc[index * 3 + 1] = BlockPos.getY(packed);
               job.loc[index * 3 + 2] = BlockPos.getZ(packed);
               Aquifer.FluidStatus status = a.wgaccel$status(packed);
               FluidStatusAccessor fluid = (FluidStatusAccessor)(Object)status;
               int type = fluidCode(fluid.wgaccel$type());
               if (type < 0) {
                  return false;
               }

               Integer identity = identities.get(status);
               if (identity == null) {
                  identity = identities.size();
                  identities.put(status, identity);
               }

               job.status[index * 3] = fluid.wgaccel$level();
               job.status[index * 3 + 1] = type;
               job.status[index * 3 + 2] = identity;
            }
         }
      }

      job.grid[0] = minGridX;
      job.grid[1] = low;
      job.grid[2] = minGridZ;
      job.grid[3] = sizeX;
      job.grid[4] = layers;
      job.grid[5] = sizeZ;
      job.cellsUsed = cells;
      return true;
   }

   /** Writes a GPU chunk into the chunk itself (ON). */
   public static void write(Pending pending) {
      long start = System.nanoTime();
      ChunkAccess chunk = pending.chunk;
      Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
      Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
      write(pending, chunk.getSections(), ocean, surface, chunk.getPostProcessing());
      // The surface's kept codes are the overworld's only (keyed by chunk position).
      if (pending.program == program) try {
         NoiseChunk noise = ((com.wgaccel.mixin.access.ChunkAccessAccessor)chunk).wgaccel$noiseChunk();
         String origin = (pending.fromAhead ? "the look-ahead" : "the noise step") + (noise != null
            && DensityTap.hasStructures(((NoiseChunkAccessor)noise).wgaccel$beardifier()) ? ", with structure terrain" : ", no structure terrain");
         // A noise-step fill's codes are the thread's own buffer, which its next chunk overwrites: kept as a copy. Kept
         // as they were, a chunk's surface step found another chunk's codes (22 Oct 2026, RX 9070 XT, radius 1000: the
         // codes check's lava-for-stone chunk, 10,689 blocks, "filled by the noise step"). A look-ahead result's are its own.
         byte[] codes = pending.fromAhead ? pending.codes : Arrays.copyOf(pending.codes, 256 * pending.cellCountY * pending.cellHeight);
         SurfaceAhead.filled(chunk, noise, codes, pending.program.table, pending.minCellY, pending.cellCountY, pending.cellHeight, origin);
      } catch (Throwable failure) {
         // Never break the noise step: the chunk's surface then goes the usual way.
         SurfaceAhead.failed(chunk, failure);
      }
      WRITTEN.increment();
      WRITE_NANOS.add(System.nanoTime() - start);
      report();
   }

   /**
    * The blocks in vanilla doFill's order (cell x, cell z, cell layers and blocks from the top down, x, z), each column's
    * top block for the two heightmaps (vanilla's update leaves exactly that from an unprimed heightmap), and flagged fluid
    * blocks into the post-processing lists.
    */
   private static void write(Pending pending, LevelChunkSection[] sections, Heightmap ocean, Heightmap surface, ShortList[] marks) {
      Program p = pending.program;
      ChunkAccess chunk = pending.chunk;
      byte[] codes = pending.codes;
      BlockState[] table = p.table;
      boolean[] fluid = p.fluid;
      int cellWidth = pending.cellWidth;
      int cellHeight = pending.cellHeight;
      int cells = 16 / cellWidth;
      int[] topSurface = new int[256];
      int[] topOcean = new int[256];
      Arrays.fill(topSurface, Integer.MIN_VALUE);
      Arrays.fill(topOcean, Integer.MIN_VALUE);
      BlockState[] surfaceState = new BlockState[256];
      BlockState[] oceanState = new BlockState[256];
      boolean[] surfaceTest = new boolean[16];
      boolean[] oceanTest = new boolean[16];
      for (int i = 0; i < table.length; i++) {
         surfaceTest[i] = Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(table[i]);
         oceanTest[i] = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(table[i]);
      }

      BlockPos.MutableBlockPos pos = SCRATCH.get().pos;
      int chunkX = chunk.getPos().getMinBlockX();
      int chunkZ = chunk.getPos().getMinBlockZ();
      boolean[] uniform = uniformSections(pending, sections, table);
      for (int kx = 0; kx < cells; kx++) {
         for (int kz = 0; kz < cells; kz++) {
            int sectionIndex = Integer.MIN_VALUE;
            LevelChunkSection section = null;
            for (int cy = pending.cellCountY - 1; cy >= 0; cy--) {
               for (int iy = cellHeight - 1; iy >= 0; iy--) {
                  int y = (pending.minCellY + cy) * cellHeight + iy;
                  int ly = cy * cellHeight + iy;
                  int index = chunk.getSectionIndex(y);
                  if (index != sectionIndex) {
                     sectionIndex = index;
                     section = sections[index];
                  }

                  for (int ix = 0; ix < cellWidth; ix++) {
                     int lx = kx * cellWidth + ix;
                     for (int iz = 0; iz < cellWidth; iz++) {
                        int lz = kz * cellWidth + iz;
                        int code = codes[(ly * 16 + lz) * 16 + lx];
                        int c = code & 15;
                        BlockState state = table[c];
                        if (state == AIR) {
                           continue;
                        }

                        if (!uniform[index]) {
                           section.setBlockState(lx, y & 15, lz, state, false);
                        }

                        int column = lx * 16 + lz;
                        if (surfaceTest[c] && topSurface[column] == Integer.MIN_VALUE) {
                           topSurface[column] = y;
                           surfaceState[column] = state;
                        }

                        if (oceanTest[c] && topOcean[column] == Integer.MIN_VALUE) {
                           topOcean[column] = y;
                           oceanState[column] = state;
                        }

                        if ((code & 16) != 0 && fluid[c]) {
                           pos.set(chunkX + lx, y, chunkZ + lz);
                           if (!chunk.isOutsideBuildHeight(pos)) {
                              ChunkAccess.getOrCreateOffsetList(marks, index).add(ProtoChunk.packOffsetCoordinates(pos));
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int column = lx * 16 + lz;
            if (topSurface[column] != Integer.MIN_VALUE) {
               surface.update(lx, topSurface[column], lz, surfaceState[column]);
            }

            if (topOcean[column] != Integer.MIN_VALUE) {
               ocean.update(lx, topOcean[column], lz, oceanState[column]);
            }
         }
      }
   }

   private static final LongAdder UNIFORM_SECTIONS = new LongAdder();
   private static final LongAdder STRUCTURED = new LongAdder();
   private static final int[] NO_BEARD = {0, 0};

   /**
    * S7: the chunk's Beardifier as the GPU reads it (FillGpu.Job#beard): its pieces then junctions, in the iterators'
    * order (the order Minecraft and C2ME sum them in), the iterators left at their start.
    */
   private static int[] beard(net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker beardifier) {
      if (!(beardifier instanceof net.minecraft.world.level.levelgen.Beardifier)) {
         return NO_BEARD;
      }

      com.wgaccel.mixin.access.BeardifierAccessor access = (com.wgaccel.mixin.access.BeardifierAccessor)beardifier;
      it.unimi.dsi.fastutil.objects.ObjectListIterator<net.minecraft.world.level.levelgen.Beardifier.Rigid> pieces = access.wgaccel$pieceIterator();
      it.unimi.dsi.fastutil.objects.ObjectListIterator<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction> junctions = access.wgaccel$junctionIterator();
      pieces.back(Integer.MAX_VALUE);
      junctions.back(Integer.MAX_VALUE);
      it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
      out.add(0);
      out.add(0);
      int pieceCount = 0;
      while (pieces.hasNext()) {
         net.minecraft.world.level.levelgen.Beardifier.Rigid piece = pieces.next();
         net.minecraft.world.level.levelgen.structure.BoundingBox box = piece.box();
         out.add(box.minX());
         out.add(box.minY());
         out.add(box.minZ());
         out.add(box.maxX());
         out.add(box.maxY());
         out.add(box.maxZ());
         out.add(piece.groundLevelDelta());
         out.add(switch (piece.terrainAdjustment()) {
            case NONE -> 0;
            case BURY -> 1;
            case BEARD_THIN -> 2;
            case BEARD_BOX -> 3;
            case ENCAPSULATE -> 4;
         });
         pieceCount++;
      }

      pieces.back(Integer.MAX_VALUE);
      int junctionCount = 0;
      while (junctions.hasNext()) {
         net.minecraft.world.level.levelgen.structure.pools.JigsawJunction junction = junctions.next();
         out.add(junction.getSourceX());
         out.add(junction.getSourceGroundY());
         out.add(junction.getSourceZ());
         junctionCount++;
      }

      junctions.back(Integer.MAX_VALUE);
      if (pieceCount == 0 && junctionCount == 0) {
         return NO_BEARD;
      }

      out.set(0, pieceCount);
      out.set(1, junctionCount);
      return out.toIntArray();
   }
   private static final LongAdder MIXED_SECTIONS = new LongAdder();

   /**
    * S3: every still-empty section the fill covers completely with one block (not air) becomes a single-value section
    * at once (its biomes kept), instead of 4,096 writes; the writing loop then skips its blocks but still works out the
    * heightmaps and fluid marks from them. The same blocks and counts either way (saving packs the palette anew).
    */
   private static boolean[] uniformSections(Pending pending, LevelChunkSection[] sections, BlockState[] table) {
      boolean[] uniform = new boolean[sections.length];
      ChunkAccess chunk = pending.chunk;
      byte[] codes = pending.codes;
      int fillMinY = pending.minCellY * pending.cellHeight;
      int fillMaxY = fillMinY + pending.cellCountY * pending.cellHeight;
      for (int index = 0; index < sections.length; index++) {
         int minY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
         if (minY < fillMinY || minY + 16 > fillMaxY || !sections[index].hasOnlyAir()) {
            continue;
         }

         int from = (minY - fillMinY) * 256;
         int c = codes[from] & 15;
         boolean same = true;
         for (int i = from + 1; i < from + 4096 && same; i++) {
            same = (codes[i] & 15) == c;
         }

         if (same && table[c] == AIR) {
            continue;
         }

         if (!same && com.wgaccel.Config.GPU_BULK_SECTIONS.get()) {
            // S3 rest: a mixed section the fill covers completely, made at once from its codes (the writing loop still
            // works the heightmaps and fluid marks out from them).
            BlockState[] blocks = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
               blocks[i] = table[codes[from + i] & 15];
            }

            net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = SectionBuilder.build(blocks);
            if (states != null) {
               LevelChunkSection old = sections[index];
               sections[index] = SectionBuilder.section(states, old.getBiomes(), blocks);
               uniform[index] = true;
               MIXED_SECTIONS.increment();
            }

            continue;
         }

         if (same) {
            LevelChunkSection old = sections[index];
            sections[index] = SectionBuilder.uniform(table[c], old.getBiomes());
            uniform[index] = true;
            UNIFORM_SECTIONS.increment();
         }
      }

      return uniform;
   }

   private static int lowSection(Pending pending) {
      return pending.chunk.getSectionIndex(pending.minCellY * pending.cellHeight);
   }

   private static int highSection(Pending pending) {
      return pending.chunk.getSectionIndex((pending.minCellY + pending.cellCountY) * pending.cellHeight - 1);
   }

   /** Before the CPU fill of a checked chunk: copies of the sections it fills, both heightmaps and the post-processing lists. */
   private static boolean snapshot(Pending pending) {
      ChunkAccess chunk = pending.chunk;
      LevelChunkSection[] real = chunk.getSections();
      pending.sections = new LevelChunkSection[real.length];
      for (int i = lowSection(pending); i <= highSection(pending); i++) {
         // A fresh container, as a new chunk's sections start: PalettedContainer.copy() keeps the original's resize
         // handler, so writing into a copy resized the original instead (nothing landed, and every block cost a full
         // palette copy). Sections that are not plain air yet are not compared.
         if (!real[i].hasOnlyAir()) {
            return false;
         }

         pending.sections[i] = new LevelChunkSection(
            new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES), real[i].getBiomes());
      }

      pending.oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).getRawData().clone();
      pending.worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getRawData().clone();
      ShortList[] lists = chunk.getPostProcessing();
      pending.marks = new ShortList[lists.length];
      for (int i = 0; i < lists.length; i++) {
         pending.marks[i] = lists[i] == null ? null : new ShortArrayList(lists[i]);
      }

      return true;
   }

   /** After the CPU fill of a checked chunk: the GPU chunk written into the copies and compared with what the CPU made. */
   public static void compare(Pending pending, long cpuNanos) {
      try {
         CPU_NANOS.add(cpuNanos);
         ChunkAccess chunk = pending.chunk;
         Heightmap ocean = new Heightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG);
         ocean.setRawData(chunk, Heightmap.Types.OCEAN_FLOOR_WG, pending.oceanFloor);
         Heightmap surface = new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG);
         surface.setRawData(chunk, Heightmap.Types.WORLD_SURFACE_WG, pending.worldSurface);
         long start = System.nanoTime();
         write(pending, pending.sections, ocean, surface, pending.marks);
         WRITE_NANOS.add(System.nanoTime() - start);
         List<String> differences = new ArrayList<>();
         LevelChunkSection[] real = chunk.getSections();
         for (int i = lowSection(pending); i <= highSection(pending); i++) {
            // The fill still holds the real section's lock, which writing it out would take: compare a copy of its
            // block states (copying takes no lock; only the palette and data, as a rebuilt section recounts its blocks
            // and vanilla's recount counts fluid blocks twice), and the real block counts as the fill kept them.
            LevelChunkSection game = new LevelChunkSection(real[i].getStates().copy(), real[i].getBiomes());
            LevelChunkSectionAccessor a = (LevelChunkSectionAccessor)pending.sections[i];
            LevelChunkSectionAccessor b = (LevelChunkSectionAccessor)real[i];
            if (!samePacked(pending.sections[i], game)) {
               differences.add("section " + i + " (" + firstDifference(chunk, pending.sections[i], game, i) + ")");
            } else if (a.wgaccel$nonEmpty() != b.wgaccel$nonEmpty() || a.wgaccel$tickingBlocks() != b.wgaccel$tickingBlocks()
               || a.wgaccel$tickingFluids() != b.wgaccel$tickingFluids()) {
               differences.add(String.format(Locale.ROOT, "section %d block counts (game %d, %d, %d; gpu %d, %d, %d)", i, b.wgaccel$nonEmpty(),
                  b.wgaccel$tickingBlocks(), b.wgaccel$tickingFluids(), a.wgaccel$nonEmpty(), a.wgaccel$tickingBlocks(), a.wgaccel$tickingFluids()));
            }
         }

         if (!Arrays.equals(ocean.getRawData(), chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).getRawData())) {
            differences.add("heightmap OCEAN_FLOOR_WG");
         }

         if (!Arrays.equals(surface.getRawData(), chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getRawData())) {
            differences.add("heightmap WORLD_SURFACE_WG");
         }

         ShortList[] lists = chunk.getPostProcessing();
         for (int i = 0; i < lists.length; i++) {
            boolean empty = lists[i] == null || lists[i].isEmpty();
            boolean emptyGpu = pending.marks[i] == null || pending.marks[i].isEmpty();
            if (empty != emptyGpu || !empty && !lists[i].equals(pending.marks[i])) {
               differences.add(String.format(Locale.ROOT, "fluid marks in section %d (game %d, gpu %d)", i, empty ? 0 : lists[i].size(),
                  emptyGpu ? 0 : pending.marks[i].size()));
            }
         }

         CHECKED.increment();
         CHECKED_BLOCKS.add(256L * pending.cellCountY * pending.cellHeight);
         if (pending.debug) {
            WorldgenAccelerator.LOG.info("WGA GPU noise fill debug chunk {} (the GPU's blocks from {}): {}{}", chunk.getPos(),
               !pending.fromAhead ? "its own noise step" : pending.real ? "the structure-chunk job made at its biome step" : "the look-ahead's stand-in chunk",
               differences.isEmpty() ? "IDENTICAL" : String.join("; ", differences), diagnose(pending));
         }

         if (!differences.isEmpty()) {
            DIFFERENT.increment();
            String origin = !pending.fromAhead ? "its own noise step" : pending.real ? "the structure-chunk job made at its biome step"
               : "the look-ahead's stand-in chunk";
            if (LOGGED_DIFFERENCES.incrementAndGet() <= 12) {
               WorldgenAccelerator.LOG.warn("WGA GPU noise fill check: chunk {} differs (the GPU's blocks came from {}): {}{}", chunk.getPos(), origin,
                  String.join("; ", differences), diagnose(pending));
            }

            // A difference turns the GPU fill off for this game (the checked chunk itself keeps the CPU's blocks).
            failed = true;
            com.wgaccel.WgaChat.warnOff("WGA GPU noise fill off: chunk {} came out different from the game's (see the log); chunks fill on the CPU from now on",
               chunk.getPos());
         }

         report();
      } catch (Throwable failure) {
         failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU noise fill check failed (chunks fill on the CPU from now on): {}", failure.toString());
      }
   }

   /**
    * Whether two sections hold the same blocks, in the form saving writes (PalettedContainer.pack: the palette rebuilt from
    * the blocks in index order, then the packed data). A single-value section (S3) and one written block by block hold
    * different palettes in memory but pack the same when their blocks are the same.
    */
   private static boolean samePacked(LevelChunkSection a, LevelChunkSection b) {
      var pa = a.getStates().pack(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
      var pb = b.getStates().pack(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
      if (!pa.paletteEntries().equals(pb.paletteEntries())) {
         return false;
      }

      long[] da = pa.storage().map(java.util.stream.LongStream::toArray).orElse(new long[0]);
      long[] db = pb.storage().map(java.util.stream.LongStream::toArray).orElse(new long[0]);
      return Arrays.equals(da, db);
   }

   /** A section's block states as the network writes them: bits, palette, data. */
   private static ByteBuf bytes(LevelChunkSection section) {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      section.getStates().write(buffer);
      return buffer;
   }

   private static String firstDifference(ChunkAccess chunk, LevelChunkSection gpu, LevelChunkSection game, int index) {
      int count = 0;
      String first = null;
      for (int y = 0; y < 16; y++) {
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               BlockState a = game.getBlockState(x, y, z);
               BlockState b = gpu.getBlockState(x, y, z);
               if (a != b) {
                  count++;
                  if (first == null) {
                     first = String.format(Locale.ROOT, "first at (%d, %d, %d): game %s, gpu %s", chunk.getPos().getMinBlockX() + x,
                        chunk.getSectionYFromSectionIndex(index) * 16 + y, chunk.getPos().getMinBlockZ() + z, a, b);
                  }
               }
            }
         }
      }

      return count == 0 ? "same blocks, different palette" : count + " blocks differ, " + first;
   }

   /** A summary every so often: chunks from the GPU, checked and identical, left to the CPU and why, times per chunk. */
   private static void report() {
      long done = WRITTEN.sum() + CHECKED.sum();
      int next = NEXT_REPORT.get();
      if (done < next || !NEXT_REPORT.compareAndSet(next, next < 1024 ? next * 2 : next + 2048)) {
         return;
      }

      long checked = CHECKED.sum();
      long gpuChunks = Math.max(1, done);
      Program p = program;
      double[] kernels = p == null ? null : p.gpu.kernelMillis();
      String split = kernels == null ? "" : String.format(Locale.ROOT, " (on the GPU, sampled: corners %.2f, columns %.2f, block states %.2f, copy out %.2f)",
         kernels[0], kernels[1], kernels[2], kernels[3]);
      Ahead look = ahead;
      if (look != null) {
         split += look.summary();
      }

      if (LOOK_CHECKED.sum() + LOOK_FAILED.sum() > 0) {
         split += String.format(Locale.ROOT, "; look-ahead check: %,d chunks' stand-in inputs %s, %,d failed, %.2f ms each", LOOK_CHECKED.sum(),
            LOOK_DIFFERENT.sum() == 0 ? "IDENTICAL" : LOOK_DIFFERENT.sum() + " DIFFER", LOOK_FAILED.sum(), LOOK_NANOS.sum() / 1.0e6 / Math.max(1, LOOK_CHECKED.sum()));
      }

      if (p != null && p.batcher() != null && p.batcher().batches.sum() > 0) {
         split += String.format(Locale.ROOT, " in batches of %.1f chunks on average", (double)p.batcher().chunks.sum() / p.batcher().batches.sum());
      }
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU noise fill: %,d chunks (%,d written from the GPU, %,d checked: %s, %,d blocks); on the CPU: %,d with structures, %,d blending, %,d other, %,d while the GPU was busy; per chunk: CPU inputs %.2f ms, GPU %.2f ms%s, writing %.2f ms%s",
         done, WRITTEN.sum(), checked, DIFFERENT.sum() == 0 ? "IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", DIFFERENT.sum()), CHECKED_BLOCKS.sum(),
         SKIP_STRUCTURES.sum(), SKIP_BLENDING.sum(), SKIP_OTHER.sum(), SKIP_BUSY.sum(), PREP_NANOS.sum() / 1.0e6 / gpuChunks, GPU_NANOS.sum() / 1.0e6 / gpuChunks, split,
         WRITE_NANOS.sum() / 1.0e6 / gpuChunks, checked == 0 ? "" : String.format(Locale.ROOT, ", the CPU fill of checked chunks %.2f ms", CPU_NANOS.sum() / 1.0e6 / checked))
         + String.format(Locale.ROOT, "; %,d one-block and %,d mixed sections written at once; %,d chunks with structures on the GPU", UNIFORM_SECTIONS.sum(),
            MIXED_SECTIONS.sum(), STRUCTURED.sum()));
   }

   /** For the memory report: what the fill's look-ahead holds now. */
   public static String memorySummary() {
      Ahead look = ahead;
      return look == null ? "fill look-ahead off" : look.memorySummary();
   }
}
