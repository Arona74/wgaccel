package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.GpuService;
import com.wgaccel.gpu.NoiseCodegen;
import com.wgaccel.gpu.StrataGpu;
import com.wgaccel.gpu.SurfaceCodegen;
import com.wgaccel.gpu.SurfaceGpu;
import com.wgaccel.gpu.SurfaceInputs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.neoforged.fml.loading.FMLPaths;

/**
 * GPU step G3.4 (config gpuSurface): the overworld's surface (the surface rules' column loop in
 * SurfaceSystem.buildSurface) from the GPU. Made when the overworld loads: the rule tree ({@link SurfaceCodegen}),
 * the rule inputs ({@link SurfaceInputs}) and FreeTerraForged's strata ({@link StrataGpu}, its StrataRule applied once
 * to a bare context: its layers are cached by name and its selector seeded from the world), run by {@link SurfaceGpu}.
 *
 * <p>Per chunk, once the rule tree is made (after FreeTerraForged's river water): each column's WORLD_SURFACE_WG height,
 * the block kinds (air, fluid, the default block, other stone) from the sections up to the highest column start, the
 * min surface base, the noise biomes around the chunk and the zoom seed; the GPU gives each block's new state or none.
 * A chunk with a column whose biome at its start is eroded badlands or a frozen ocean (their extensions stay on the
 * CPU), or strata the GPU is unsure of, stays on the CPU.
 *
 * <p>ON writes the blocks as vanilla's loop does (chunk.setBlockState in its order: x, then z, then y from the top down;
 * fluids marked for post-processing) and makes the loop run no rows (its start height is answered below the world),
 * while the eroded badlands test still reads each column's height from before the writes. Every 64th chunk is checked
 * instead: CHECK (every chunk) lets the CPU loop run and compares every block it could reach with the GPU's.
 */
public final class GpuSurface {
   private static final int CHECK_EVERY = 64;
   private static final int LOGGED_MAX = 12;

   record Program(RandomState random, SurfaceSystem system, SurfaceRules.RuleSource rules, Registry<Biome> biomes, long zoomSeed,
         BlockState defaultBlock, BlockState[] table, SurfaceGpu gpu, com.wgaccel.gpu.SurfaceBatcher batcher) {
   }

   /** The chunk in this thread's buildSurface, from its start to its end. */
   private static final class Current {
      final SurfaceGpu.Job job = new SurfaceGpu.Job();
      final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      short[] out = new short[0];
      BlockState[] before = new BlockState[0];
      Program program;
      ChunkAccess chunk;
      BiomeManager biomeManager;
      NoiseChunk noise;
      boolean active;
      boolean skipLoop;
      /** For the adaptive GPU queue: this chunk's surface went the GPU way (1), the CPU way as the card was busy (2), or neither (0). */
      int timedWay;
      boolean checkReady;
      long start;
      /** For a checked chunk: its heightmaps' raw data and post-processing marks from before the surface (the write check). */
      long[][] mapsBefore;
      it.unimi.dsi.fastutil.shorts.ShortList[] marksBefore;
      /** Marks other threads made on this checked chunk while its CPU surface ran (see FOREIGN). */
      java.util.concurrent.ConcurrentLinkedQueue<Long> foreignMarks;
   }

   private static volatile Program program;
   private static volatile boolean failed;
   private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);
   private static Field noiseBiomeSource;
   private static Field biomeZoomSeed;
   private static final AtomicInteger ELIGIBLE = new AtomicInteger();
   private static final LongAdder WRITTEN = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder CHECKED_BLOCKS = new LongAdder();
   private static final LongAdder SET_BLOCKS = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final LongAdder SKIP_SPECIAL = new LongAdder();
   private static final LongAdder SKIP_OTHER = new LongAdder();
   private static final LongAdder SKIP_BUSY = new LongAdder();
   private static final LongAdder PREP_NANOS = new LongAdder();
   private static final LongAdder GPU_NANOS = new LongAdder();
   private static final LongAdder WRITE_NANOS = new LongAdder();
   private static final LongAdder CPU_NANOS = new LongAdder();
   private static final AtomicInteger LOGGED_DIFFERENCES = new AtomicInteger();
   private static final AtomicInteger LOGGED_OTHER = new AtomicInteger();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(64);

   private GpuSurface() {
   }

   static Program program() {
      return failed ? null : program;
   }

   private static final LongAdder AHEAD_DIFFERENT = new LongAdder();
   private static final LongAdder SAME_SKIPPED = new LongAdder();

   /** At the overworld's load (gpuSurface not OFF, gpuMode not OFF): makes and compiles the surface code on its own thread. */
   public static void start(ServerLevel overworld) {
      Program old = program;
      if (old != null && old.batcher() != null) {
         old.batcher().stop();
      }

      program = null;
      failed = false;
      writeCheckOff = false;
      if (Config.GPU_SURFACE.get() == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      com.wgaccel.gpu.GpuPrepare.Task task = com.wgaccel.gpu.GpuPrepare.begin("surface");
      Thread thread = new Thread(() -> {
         long start = System.nanoTime();
         try {
            program = build(overworld);
            task.done();
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface: ready in %.0f ms (%s); chunks now get their surface from the GPU (%s)",
               (System.nanoTime() - start) / 1.0e6, Config.GPU_SURFACE.get(), Config.GPU_SURFACE.get() == Config.GpuFilterMode.ON ? "every 64th checked"
                  : "every chunk checked, nothing written"));
         } catch (DensityCodegen.Unsupported unsupported) {
            task.failed(unsupported.kind);
            com.wgaccel.WgaChat.warnOff("WGA GPU surface off: {} has no GPU version", unsupported.kind);
         } catch (NoiseCodegen.Unsupported unsupported) {
            task.failed(unsupported.getMessage());
            com.wgaccel.WgaChat.warnOff("WGA GPU surface off: {} has no GPU version", unsupported.getMessage());
         } catch (Throwable failure) {
            task.failed(failure.toString());
            com.wgaccel.WgaChat.warnOff("WGA GPU surface off: {}", com.wgaccel.gpu.GpuSelfTest.describe(failure));
         }
      }, "WGA GPU surface compile");
      thread.setDaemon(true);
      thread.start();
   }

   /**
    * C1 (as FreeTerraForged's developer suggested: take everything from its compat runtime layer): the surface rule root
    * FreeTerraForged's buildSurface hands SurfaceSystem (its worldgen plan's surface facet, with every mod's transforms
    * applied), or null outside FreeTerraForged.
    */
   private static SurfaceRules.RuleSource ftfSurfaceRoot(RandomState random) {
      try {
         Object plan = random.sampler().getClass().getMethod("getWorldgenPlan").invoke(random.sampler());
         java.lang.reflect.Method surface = plan.getClass().getMethod("surface");
         surface.setAccessible(true);
         Object facet = surface.invoke(plan);
         java.lang.reflect.Method root = facet.getClass().getMethod("root");
         root.setAccessible(true);
         return ((java.util.Optional<?>)root.invoke(facet)).map(value -> (SurfaceRules.RuleSource)value).orElse(null);
      } catch (ReflectiveOperationException | RuntimeException notFreeTerraForged) {
         return null;
      }
   }

   private static Program build(ServerLevel overworld) throws Exception {
      if (!(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
         throw new IllegalStateException("the overworld has no noise generator");
      }

      RandomState random = overworld.getChunkSource().randomState();
      SurfaceSystem system = random.surfaceSystem();
      SurfaceRules.RuleSource rules = ftfSurfaceRoot(random);
      if (rules == null) {
         rules = generator.generatorSettings().value().surfaceRule();
      }

      if (generator.generatorSettings().value().useLegacyRandomSource()) {
         throw new IllegalStateException("the overworld uses the legacy random source");
      }

      Registry<Biome> biomes = overworld.registryAccess().registryOrThrow(Registries.BIOME);
      WorldGenerationContext context = new WorldGenerationContext(generator, overworld);
      long zoomSeed = BiomeManager.obfuscateSeed(overworld.getSeed());
      SurfaceCodegen codegen = new SurfaceCodegen(random, context, biomes, system);
      codegen.generate(rules);
      SurfaceInputs inputs = new SurfaceInputs(system, biomes, zoomSeed);
      String strataSource;
      int strataMax;
      String strataSampled = "";
      Object strata = strataRule(rules);
      if (strata != null) {
         StrataGpu code = new StrataGpu(strataSourceOf(strata, system, random, biomes, context), codegen);
         strataSource = code.productionSource();
         strataMax = code.maxLayers();
         strataSampled = code.quarts() ? ", sampled per quart (FreeTerraForged 1.0.0013)" : ", sampled per column";
      } else {
         strataSource = """
            extern "C" __global__ void strataSelect(int minX, int minZ, int* indexOut, int* countOut, int* unsureOut) {
                int t = blockIdx.x * blockDim.x + threadIdx.x;
                if (t >= 256) return;
                indexOut[t] = 0;
                countOut[t] = 0;
                unsureOut[t] = 0;
            }
            extern "C" __global__ void strataLayers(int minX, int minZ, const int* indexIn, const int* countIn, float* depthOut, int* stateOut,
                                                    int* unsureLayer) {
                int i = blockIdx.x * blockDim.x + threadIdx.x;
                if (i < 256) unsureLayer[i] = 0;
            }
            extern "C" __global__ void strataFinish(const int* heights, float* depthOut, const int* countIn, int* unsureOut, const int* unsureLayer) {
            }
            """;
         strataMax = 1;
      }

      String source = codegen.productionSource(inputs, DimensionType.WAY_BELOW_MIN_Y, id(biomes, Biomes.ERODED_BADLANDS), id(biomes, Biomes.FROZEN_OCEAN),
         id(biomes, Biomes.DEEP_FROZEN_OCEAN));
      try {
         Files.writeString(FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-surface.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      int states = codegen.stateCount();
      if (states >= Short.MAX_VALUE) {
         throw new IllegalStateException(states + " block states, more than a short holds");
      }

      BlockState[] table = new BlockState[states];
      for (int i = 0; i < states; i++) {
         table[i] = codegen.stateAt(i);
      }

      BlockState defaultBlock = (BlockState)field(system, "defaultBlock");
      SurfaceGpu gpu = new SurfaceGpu(GpuService.context(), strataSource, strataMax, source, codegen.noiseData(), codegen.permutations(), inputs.noiseData(),
         inputs.permutations(), inputs.simplexData(), inputs.simplexPermutations(), inputs.temperature(), inputs.modifier());
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface: rule tree of %s, %d block states, strata %s, CUDA source %,d KB", codegen.kinds(),
         states, strata == null ? "none" : "up to " + strataMax + " layers" + strataSampled, source.length() / 1024));
      int batch = Config.GPU_SURFACE_BATCH_CHUNKS.get();
      com.wgaccel.gpu.SurfaceBatcher batcher = batch > 0 ? new com.wgaccel.gpu.SurfaceBatcher(gpu, batch, Config.GPU_SURFACE_BATCH_THREADS.get(), 200L) : null;
      return new Program(random, system, rules, biomes, zoomSeed, defaultBlock, table, gpu, batcher);
   }

   private static int id(Registry<Biome> biomes, net.minecraft.resources.ResourceKey<Biome> key) {
      Biome biome = biomes.get(key);
      return biome == null ? -100 : biomes.getId(biome);
   }

   /** FreeTerraForged's StrataRule in the rule source tree (one by name; two different ones: the GPU cannot tell them apart). */
   private static Object strataRule(Object rules) throws ReflectiveOperationException {
      Map<Object, Boolean> found = new IdentityHashMap<>();
      walk(rules, new IdentityHashMap<>(), found);
      Object first = null;
      for (Object each : found.keySet()) {
         if (first == null) {
            first = each;
         } else if (!each.equals(first)) {
            throw new IllegalStateException("more than one strata rule in the surface rules");
         }
      }

      return first;
   }

   private static void walk(Object node, Map<Object, Boolean> seen, Map<Object, Boolean> found) throws ReflectiveOperationException {
      if (node == null || seen.put(node, Boolean.TRUE) != null) {
         return;
      }

      if (node instanceof List<?> list) {
         for (Object each : list) {
            walk(each, seen, found);
         }

         return;
      }

      if (!(node instanceof SurfaceRules.RuleSource)) {
         return;
      }

      if (node.getClass().getName().endsWith(".StrataRule")) {
         found.put(node, Boolean.TRUE);
         return;
      }

      if (node.getClass().isRecord()) {
         for (RecordComponent component : node.getClass().getRecordComponents()) {
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            walk(accessor.invoke(node), seen, found);
         }
      }
   }

   /** The rule's StrataRule.Source, as a chunk's surface would make it, from a context holding only what the rule reads. */
   private static Object strataSourceOf(Object rule, SurfaceSystem system, RandomState random, Registry<Biome> biomes, WorldGenerationContext context)
         throws ReflectiveOperationException {
      // SurfaceRules.Context is protected: reached by name.
      Class<?> contextClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
      Constructor<?> make = contextClass.getDeclaredConstructor(SurfaceSystem.class, RandomState.class, ChunkAccess.class, NoiseChunk.class, Function.class,
         Registry.class, WorldGenerationContext.class);
      make.setAccessible(true);
      Object bare = make.newInstance(system, random, null, null, null, biomes, context);
      Method apply = rule.getClass().getMethod("apply", contextClass);
      apply.setAccessible(true);
      return apply.invoke(rule, bare);
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(target.getClass().getName() + "." + name);
   }

   /** At the start of buildSurface: whether this chunk may get its surface from the GPU (worked out once its rule tree is made). */
   public static void begin(RandomState random, BiomeManager biomeManager, Registry<Biome> biomes, boolean legacy, ChunkAccess chunk, NoiseChunk noise,
         SurfaceRules.RuleSource rules, Object system) {
      Current current = CURRENT.get();
      current.active = false;
      current.skipLoop = false;
      current.checkReady = false;
      current.timedWay = 0;
      Program p = program;
      if (p == null || failed || random != p.random || Config.GPU_SURFACE.get() == Config.GpuFilterMode.OFF) {
         return;
      }

      if (system != p.system || biomes != p.biomes || legacy || rules != p.rules) {
         other(chunk, system != p.system ? "another surface system" : biomes != p.biomes ? "another biome registry" : legacy ? "legacy random"
            : "another rule source");
         return;
      }

      LevelHeightAccessor accessor = chunk.getHeightAccessorForGeneration();
      if (accessor.getMinBuildHeight() != chunk.getMinBuildHeight() || accessor.getHeight() != chunk.getHeight()) {
         other(chunk, "an upgrading chunk's height");
         return;
      }

      current.program = p;
      current.chunk = chunk;
      current.biomeManager = biomeManager;
      current.noise = noise;
      current.active = true;
      current.start = System.nanoTime();
   }

   private static void other(ChunkAccess chunk, String why) {
      SKIP_OTHER.increment();
      if (LOGGED_OTHER.incrementAndGet() <= 4) {
         WorldgenAccelerator.LOG.info("WGA GPU surface: chunk {} stays on the CPU: {}", chunk.getPos(), why);
      }
   }

   /**
    * Once the chunk's rule tree is made: the GPU works the surface out; ON writes it (the loop then runs no rows),
    * CHECK keeps it for {@link #end}.
    */
   private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> MARK_CALLERS = new java.util.concurrent.ConcurrentHashMap<>();

   /**
    * Fluid-mark tracing (0.10.15, to find why checked chunks' CPU surface makes marks the GPU way does not): a mark made
    * on a checked chunk while its CPU surface runs is counted by its caller (the calling frames); each new caller is
    * logged once with the position and the block there at that moment.
    */
   /**
    * Fluid marks other threads made on a checked chunk while its CPU surface ran (0.10.16's trace: neighbouring
    * chunks' features, e.g. Still Life's oversized disks, call Feature.markAboveForPostProcessing, which reaches a chunk
    * still in its surface step without the far-chunk write check). The real chunk gets them whichever way its surface is
    * made, so the write check adds them to the GPU's copy before comparing.
    */
   private static final java.util.concurrent.ConcurrentHashMap<ChunkAccess, java.util.concurrent.ConcurrentLinkedQueue<Long>> FOREIGN = new java.util.concurrent.ConcurrentHashMap<>();
   private static final java.util.concurrent.atomic.LongAdder FOREIGN_MARKS = new java.util.concurrent.atomic.LongAdder();

   /** Checked chunks whose CPU surface is running (marks on them from any thread are traced). */
   private static final java.util.Set<ChunkAccess> TRACED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

   public static void traceMark(ChunkAccess chunk, BlockPos pos) {
      if (TRACED.isEmpty() || !TRACED.contains(chunk)) {
         return;
      }

      Current current = CURRENT.get();
      boolean own = current.checkReady && current.chunk == chunk;
      if (!own) {
         FOREIGN.computeIfAbsent(chunk, c -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(pos.asLong());
         FOREIGN_MARKS.increment();
      }

      String thread = (own ? "its surface thread " : "OTHER thread ") + Thread.currentThread().getName();

      StackTraceElement[] stack = new Throwable().getStackTrace();
      StringBuilder callers = new StringBuilder();
      int shown = 0;
      for (int i = 2; i < stack.length && shown < 9; i++) {
         String frame = stack[i].getClassName() + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber();
         if (frame.startsWith("java.") || frame.contains("mixinextras$bridge")) {
            continue;
         }

         callers.append(shown++ == 0 ? "" : " < ").append(frame);
      }

      String key = thread.replaceAll("[0-9]+", "#") + ": " + callers;
      java.util.concurrent.atomic.LongAdder count = MARK_CALLERS.get(key);
      if (count == null) {
         count = MARK_CALLERS.computeIfAbsent(key, k -> new java.util.concurrent.atomic.LongAdder());
         if (MARK_CALLERS.size() <= 12) {
            WorldgenAccelerator.LOG.info("WGA surface mark trace: new caller of markPosForPostprocessing at {} (block {}): {}", pos.toShortString(),
               chunk.getBlockState(pos), key);
         }
      }

      count.increment();
   }

   /** The traced mark callers with their counts, for the logs. */
   public static String markCallers() {
      StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
         "WGA surface mark trace (checked chunks' CPU surface; %,d marks from other threads added to the GPU's copy before comparing):", FOREIGN_MARKS.sum()));
      MARK_CALLERS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(8)
         .forEach(e -> text.append(String.format(Locale.ROOT, " [%,d x] %s;", e.getValue().sum(), e.getKey())));
      return text.toString();
   }

   public static void rulesMade() {
      Current current = CURRENT.get();
      if (!current.active) {
         return;
      }

      current.active = false;
      Program p = current.program;
      ChunkAccess chunk = current.chunk;
      boolean on = Config.GPU_SURFACE.get() == Config.GpuFilterMode.ON;
      SurfaceAhead.Result ahead = on && SurfaceAhead.enabled() ? SurfaceAhead.take(chunk) : null;
      if (ahead != null) {
         try {
            if (!ahead.done) {
               SKIP_SPECIAL.increment();
               return;
            }

            SurfaceGpu.Job job = current.job;
            int n = 256 * ahead.job.height;
            if (ELIGIBLE.getAndIncrement() % CHECK_EVERY == 0) {
               // Checked: the inputs the usual way, compared with those made ahead; then the CPU loop runs and its blocks are compared.
               if (!inputs(current, p, true)) {
                  return;
               }

               String difference = SurfaceAhead.compare(ahead.job, job);
               if (difference != null) {
                  AHEAD_DIFFERENT.increment();
                  if (LOGGED_DIFFERENCES.incrementAndGet() <= LOGGED_MAX) {
                     WorldgenAccelerator.LOG.warn("WGA GPU surface ahead check: chunk {}: the inputs made ahead differ: {}", chunk.getPos(), difference);
                  }

                  return;
               }

               if (current.out.length < n) {
                  current.out = new short[n + n / 4];
               }

               System.arraycopy(ahead.out, 0, current.out, 0, n);
               current.checkReady = true;
               TRACED.add(current.chunk);
               return;
            }

            long writeStart = System.nanoTime();
            job.kinds = ahead.job.kinds;
            job.minY = ahead.job.minY;
            job.height = ahead.job.height;
            job.minX = ahead.job.minX;
            job.minZ = ahead.job.minZ;
            System.arraycopy(ahead.job.tops, 0, job.tops, 0, 256);
            System.arraycopy(ahead.job.heights, 0, job.heights, 0, 256);
            System.arraycopy(ahead.job.minBase, 0, job.minBase, 0, 256);
            current.out = ahead.out;
            write(current, p, current.chunk.getSections(), heightmaps(current.chunk), null, true);
            WRITE_NANOS.add(System.nanoTime() - writeStart);
            WRITTEN.increment();
            current.skipLoop = true;
            current.timedWay = 1;
            report();
         } catch (Throwable failure) {
            failed = true;
            current.skipLoop = false;
            com.wgaccel.WgaChat.warnOff("WGA GPU surface off (chunks get their surface on the CPU from now on): {}", failure.toString());
         }

         return;
      }

      Object place = on ? com.wgaccel.gpu.GpuQueue.trySurface() : com.wgaccel.gpu.GpuQueue.NONE;
      if (place == null) {
         SKIP_BUSY.increment();
         current.timedWay = 2;
         return;
      }

      try {
         long start = System.nanoTime();
         SurfaceGpu.Job job = current.job;
         // Decided first: a checked chunk reads its blocks back (the check compares against them); others take their
         // kinds from the fill's codes when the GPU filled them.
         boolean check = Config.GPU_SURFACE.get() == Config.GpuFilterMode.CHECK || ELIGIBLE.getAndIncrement() % CHECK_EVERY == 0;
         if (!inputs(current, p, check)) {
            return;
         }

         long gpuStart = System.nanoTime();
         int n = 256 * job.height;
         if (current.out.length < n) {
            current.out = new short[n + n / 4];
         }

         boolean done = p.batcher() != null ? p.batcher().run(job, current.out) : p.gpu.run(job, current.out);
         com.wgaccel.gpu.GpuQueue.release(place);
         place = com.wgaccel.gpu.GpuQueue.NONE;
         long gpuEnd = System.nanoTime();
         PREP_NANOS.add(gpuStart - start);
         GPU_NANOS.add(gpuEnd - gpuStart);
         if (!done) {
            SKIP_SPECIAL.increment();
            return;
         }

         if (check) {
            current.checkReady = true;
               TRACED.add(current.chunk);
            return;
         }

         write(current, p, current.chunk.getSections(), heightmaps(current.chunk), null, true);
         WRITE_NANOS.add(System.nanoTime() - gpuEnd);
         WRITTEN.increment();
         current.skipLoop = true;
         current.timedWay = 1;
         report();
      } catch (Throwable failure) {
         failed = true;
         current.skipLoop = false;
         com.wgaccel.WgaChat.warnOff("WGA GPU surface off (chunks get their surface on the CPU from now on): {}", failure.toString());
      } finally {
         com.wgaccel.gpu.GpuQueue.release(place);
      }
   }

   /** The chunk's inputs into the job; false (the chunk stays on the CPU) when something cannot be read. */
   private static boolean inputs(Current current, Program p, boolean check) throws ReflectiveOperationException {
      SurfaceGpu.Job job = current.job;
      ChunkAccess chunk = current.chunk;
      int minY = chunk.getMinBuildHeight();
      int fullHeight = chunk.getHeight();
      int minX = chunk.getPos().getMinBlockX();
      int minZ = chunk.getPos().getMinBlockZ();
      int maxTop = minY;
      for (int t = 0; t < 256; t++) {
         int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, t / 16, t % 16);
         job.heights[t] = height;
         job.tops[t] = height + 1;
         maxTop = Math.max(maxTop, height + 1);
      }

      // Only blocks up to the highest column start: the loop reads and writes none above it.
      int height = Math.max(1, Math.min(fullHeight, maxTop - minY + 1));
      job.minY = minY;
      job.height = height;
      job.minX = minX;
      job.minZ = minZ;
      int n = 256 * height;
      if (job.kinds == null || job.kinds.length < n || current.before.length < n) {
         job.kinds = new byte[n + n / 4];
         current.before = new BlockState[n + n / 4];
      }

      byte[] kinds = job.kinds;
      BlockState[] before = current.before;
      BlockState defaultBlock = p.defaultBlock;
      current.mapsBefore = null;
      current.marksBefore = null;
      Heightmap[] mapsNow = check ? heightmaps(chunk) : null;
      if (mapsNow != null) {
         current.mapsBefore = new long[mapsNow.length][];
         for (int i = 0; i < mapsNow.length; i++) {
            current.mapsBefore[i] = mapsNow[i].getRawData().clone();
         }

         // Traced from here (before the marks are copied), so marks other threads make from now on are known.
         TRACED.add(chunk);
         it.unimi.dsi.fastutil.shorts.ShortList[] lists = chunk.getPostProcessing();
         current.marksBefore = new it.unimi.dsi.fastutil.shorts.ShortList[lists.length];
         for (int i = 0; i < lists.length; i++) {
            current.marksBefore[i] = lists[i] == null ? null : new it.unimi.dsi.fastutil.shorts.ShortArrayList(lists[i]);
         }
      }
      BlockState air = Blocks.AIR.defaultBlockState();
      SurfaceAhead.Codes codes = SurfaceAhead.takeCodes(chunk);
      if (!check && codes != null && Config.GPU_SURFACE_KINDS_FROM_CODES.get()) {
         SurfaceAhead.kinds(codes, kinds, defaultBlock, minY, height);
      } else for (int y0 = minY; y0 < minY + height; y0 = (y0 & ~15) + 16) {
         int y1 = Math.min(minY + height, (y0 & ~15) + 16);
         LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y0));
         // A section of one state (its palette holds no other: most sections before the surface, all stone or all air)
         // is filled at once instead of read block by block.
         BlockState first = section.hasOnlyAir() ? air : section.getBlockState(0, 0, 0);
         BlockState only = first == air && section.hasOnlyAir() || !section.getStates().maybeHas(state -> state != first) ? first : null;

         if (only != null) {
            byte kind = (byte)(only.isAir() ? 0 : !only.getFluidState().isEmpty() ? 1 : only == defaultBlock ? 2 : 3);
            java.util.Arrays.fill(before, (y0 - minY) * 256, (y1 - minY) * 256, only);
            java.util.Arrays.fill(kinds, (y0 - minY) * 256, (y1 - minY) * 256, kind);
            continue;
         }

         for (int y = y0; y < y1; y++) {
            int at = (y - minY) * 256;
            for (int t = 0; t < 256; t++) {
               BlockState state = section.getBlockState(t / 16, y & 15, t % 16);
               before[at + t] = state;
               kinds[at + t] = (byte)(state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : state == defaultBlock ? 2 : 3);
            }
         }
      }

      if (check && codes != null) {
         // The codes check: a checked chunk's kinds come from its blocks; those the fill's codes give (as an unchecked
         // chunk's do) must be the same (FreeTerraForged's river water, placed in the surface step before this, is
         // one way they could not be).
         byte[] fromCodes = new byte[256 * height];
         SurfaceAhead.kindsOnly(codes, fromCodes, defaultBlock, minY, height);
         int first = -1;
         for (int i = 0; i < 256 * height && first < 0; i++) {
            if (fromCodes[i] != kinds[i]) {
               first = i;
            }
         }

         if (first < 0) {
            CODES_CHECKED.increment();
         } else {
            CODES_DIFFERENT.increment();
            // Only air in the codes where the chunk holds water, stone or ice, with some water: FreeTerraForged's river
            // step (MixinSurfaceSystem.placeRiverWater, at the start of the surface step, before this; it sets those
            // three). 22 Oct 2026, RX 9070 XT, radius 1000: 11 of 214 checked chunks differed, all of them this (two with
            // 1-2 stone blocks among 40-odd of water at the same height). Counted apart so any other difference shows.
            boolean onlyWater = true;
            boolean water = false;
            for (int i = first; i < 256 * height && onlyWater; i++) {
               if (fromCodes[i] != kinds[i]) {
                  water |= kinds[i] == 1;
                  onlyWater = fromCodes[i] == 0 && (kinds[i] == 1 || kinds[i] == 2 || before[i] != null && before[i].is(Blocks.ICE));
               }
            }

            onlyWater &= water;

            if (onlyWater) {
               CODES_WATER.increment();
            }

            // The river step's only the first 3; every other one (22 Oct 2026: 2 of 213 were not logged behind 5 of water).
            if (onlyWater ? CODES_WATER.sum() <= 3 : CODES_DIFFERENT.sum() - CODES_WATER.sum() <= 20) {
               // The first block that is not river water (or the first at all), and each kind pair's count and y range.
               int at = first;
               for (int i = first; i < 256 * height && !onlyWater; i++) {
                  if (fromCodes[i] != kinds[i] && !(fromCodes[i] == 0 && kinds[i] == 1)) {
                     at = i;
                     break;
                  }
               }

               int y = minY + at / 256;
               int t = at % 256;
               int ly = y - codes.fillMinY();
               int raw = ly >= 0 && ly < codes.fillHeight() ? codes.codes()[(ly * 16 + t % 16) * 16 + t / 16] : -1;
               int count = 0;
               int[] pairs = new int[16];
               int[] pairLow = new int[16];
               int[] pairHigh = new int[16];
               for (int i = 0; i < 256 * height; i++) {
                  if (fromCodes[i] != kinds[i]) {
                     count++;
                     int pair = (fromCodes[i] & 3) * 4 + (kinds[i] & 3);
                     int py = minY + i / 256;
                     pairLow[pair] = pairs[pair] == 0 ? py : Math.min(pairLow[pair], py);
                     pairHigh[pair] = Math.max(pairHigh[pair], py);
                     pairs[pair]++;
                  }
               }

               StringBuilder kindPairs = new StringBuilder();
               for (int pair = 0; pair < 16; pair++) {
                  if (pairs[pair] > 0) {
                     kindPairs.append(kindPairs.isEmpty() ? "" : ", ").append(pair / 4).append("->").append(pair % 4).append(' ').append(pairs[pair])
                        .append(" (y ").append(pairLow[pair]).append("..").append(pairHigh[pair]).append(')');
                  }
               }

               WorldgenAccelerator.LOG.warn("WGA GPU surface codes check: chunk {}: {} blocks differ ({}; codes->blocks: {}); first at ({}, {}, {}): kind from the codes {} (code {}, state {}), from the blocks {} ({}); fill y {}..{}, status {}, filled by {}",
                  chunk.getPos(), count, onlyWater ? "only FreeTerraForged's river step" : "NOT only FreeTerraForged's river step", kindPairs, minX + t / 16, y, minZ + t % 16, fromCodes[at], raw,
                  raw < 0 ? "-" : codes.table()[raw & 15], kinds[at], before[at], codes.fillMinY(), codes.fillMinY() + codes.fillHeight() - 1, chunk.getPersistedStatus(),
                  codes.origin());
            }
         }
      }

      // SurfaceRules.Context.getMinSurfaceLevel without the surface depth: the preliminary surface at the 16-block cell's corners.
      int cellX = minX >> 4;
      int cellZ = minZ >> 4;
      double c0 = current.noise.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
      double c1 = current.noise.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
      double c2 = current.noise.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
      double c3 = current.noise.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
      for (int t = 0; t < 256; t++) {
         int x = minX + t / 16;
         int z = minZ + t % 16;
         job.minBase[t] = Mth.floor(Mth.lerp2((double)((float)(x & 15) / 16.0F), (double)((float)(z & 15) / 16.0F), c0, c1, c2, c3));
      }

      // The noise biomes BiomeManager.getBiome can reach from the blocks worked on (y clamped by the chunk, so only as
      // high as they reach), as the region gives them.
      if (noiseBiomeSource == null) {
         Field source = BiomeManager.class.getDeclaredField("noiseBiomeSource");
         source.setAccessible(true);
         Field seed = BiomeManager.class.getDeclaredField("biomeZoomSeed");
         seed.setAccessible(true);
         biomeZoomSeed = seed;
         noiseBiomeSource = source;
      }

      long zoomSeed = biomeZoomSeed.getLong(current.biomeManager);
      if (zoomSeed != p.zoomSeed) {
         other(chunk, "another biome zoom seed");
         return false;
      }

      BiomeManager.NoiseBiomeSource biomes = (BiomeManager.NoiseBiomeSource)noiseBiomeSource.get(current.biomeManager);
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
      if (job.quarts.length < quarts) {
         job.quarts = new int[quarts + quarts / 4];
      }

      job.quartCount = quarts;
      Registry<Biome> registry = p.biomes;
      Holder<Biome> last = null;
      int lastId = 0;
      for (int qx = 0; qx < 6; qx++) {
         for (int qz = 0; qz < 6; qz++) {
            int at = (qx * 6 + qz) * sy;
            for (int qy = 0; qy < sy; qy++) {
               Holder<Biome> biome = biomes.getNoiseBiome(gx + qx, gy + qy, gz + qz);
               if (biome != last) {
                  last = biome;
                  lastId = registry.getId(biome.value());
               }

               job.quarts[at + qy] = lastId;
            }
         }
      }

      job.zoomSeed = zoomSeed;
      return true;
   }

   /**
    * The GPU's blocks written as vanilla's column loop writes them, in its order. Before the light step (always, at
    * the surface step) straight into the sections with ProtoChunk.setBlockState's effects: the section's block (an air
    * block into an air-only section skipped), every heightmap of the status updated, fluids marked for post-processing;
    * otherwise (or if a heightmap is missing, which setBlockState would make) through setBlockState itself.
    */
   /**
    * The GPU's blocks into the chunk: into {@code sections} with these heightmaps (null: through setBlockState) and
    * fluid marks into {@code marks} (null: the chunk's own). The write check passes copies; {@code real} false then
    * leaves the counters alone.
    */
   private static void write(Current current, Program p, LevelChunkSection[] sections, Heightmap[] maps, it.unimi.dsi.fastutil.shorts.ShortList[] marks,
         boolean real) {
      write(current, p, sections, maps, marks, real, true);
   }

   private static void write(Current current, Program p, LevelChunkSection[] sections, Heightmap[] maps, it.unimi.dsi.fastutil.shorts.ShortList[] marks,
         boolean real, boolean atOnce) {
      SurfaceGpu.Job job = current.job;
      ChunkAccess chunk = current.chunk;
      BlockPos.MutableBlockPos pos = current.pos;
      short[] out = current.out;
      BlockState[] table = p.table;
      int height = job.height;
      int minY = job.minY;
      byte[] kinds = job.kinds;
      BlockState defaultBlock = p.defaultBlock;
      BlockState air = Blocks.AIR.defaultBlockState();
      int lowSection = chunk.getSectionIndex(minY);
      int set = 0;
      // S3: sections the surface changes for the most part (and never to air) are collected and made at once after the
      // loop; the loop still makes every heightmap update and fluid mark in vanilla's order (a heightmap's downward scan
      // reads blocks below, which vanilla's order has not written yet either).
      BlockState[][] bulk = atOnce && maps != null && com.wgaccel.Config.GPU_BULK_SECTIONS.get() ? bulkSections(job, out, table, kinds, defaultBlock, air, sections, lowSection) : null;
      // Heightmap.update does nothing for a block 2 or more below the map's height (its first test): each map's height
      // in this column is kept here and the call made only above that, then the height read again after a change. The
      // same updates as calling it for every block (the profile of 0.9.49: Heightmap.update 5.8% of all samples).
      int[] firsts = maps == null ? null : new int[maps.length];
      // gpuSurfaceMapsAfter: no per-block updates; each column's highest changed block is noted and the heights are
      // worked out after the writes (mapsAfter).
      boolean after = maps != null && !mapsAfterOff && com.wgaccel.Config.GPU_SURFACE_MAPS_AFTER.get();
      int[] changedTop = after ? new int[256] : null;
      if (after) {
         java.util.Arrays.fill(changedTop, Integer.MIN_VALUE);
      }
      // 0.9.63: and the lowest of them, so a block below every map's height skips the loop over the maps in one test.
      int floor = Integer.MIN_VALUE;
      for (int t = 0; t < 256; t++) {
         int lx = t / 16;
         int lz = t % 16;
         pos.setX(job.minX + lx).setZ(job.minZ + lz);
         if (maps != null) {
            for (int m = 0; m < maps.length; m++) {
               firsts[m] = maps[m].getFirstAvailable(lx, lz);
            }

            floor = lowest(firsts);
         }

         for (int y = Math.min(job.tops[t], minY + height - 1); y >= minY; y--) {
            int at = (y - minY) * 256 + t;
            int code = out[at];
            if (code >= 0) {
               BlockState state = table[code];
               if (maps != null && (kinds[at] == 2 && state == defaultBlock || kinds[at] == 0 && state == air)) {
                  // The block is already this state (the default block, or air): setBlockState would change nothing
                  // (same block, counts and heightmaps; neither is a fluid). Skipped.
                  set++;
                  if (real) {
                     SAME_SKIPPED.increment();
                  }

                  continue;
               }

               if (maps == null) {
                  chunk.setBlockState(pos.setY(y), state, false);
               } else {
                  int sectionAt = (y - minY) >> 4;
                  LevelChunkSection section = sections[lowSection + sectionAt];
                  if (bulk != null && bulk[sectionAt] != null) {
                     bulk[sectionAt][((y & 15) * 16 + lz) * 16 + lx] = state;
                     if (after) {
                        changedTop[t] = Math.max(changedTop[t], y);
                     } else if (y > floor - 2) {
                        floor = updateMaps(maps, firsts, floor, lx, y, lz, state);
                     }
                  } else if (!(state.is(Blocks.AIR) && section.hasOnlyAir())) {
                     section.setBlockState(lx, y & 15, lz, state, false);
                     if (after) {
                        changedTop[t] = Math.max(changedTop[t], y);
                     } else if (y > floor - 2) {
                        floor = updateMaps(maps, firsts, floor, lx, y, lz, state);
                     }
                  }

                  pos.setY(y);
               }

               if (!state.getFluidState().isEmpty()) {
                  if (marks == null) {
                     chunk.markPosForPostprocessing(pos);
                  } else {
                     // As ProtoChunk.markPosForPostprocessing does.
                     int index = chunk.getSectionIndex(pos.getY());
                     if (marks[index] == null) {
                        marks[index] = new it.unimi.dsi.fastutil.shorts.ShortArrayList();
                     }

                     marks[index].add(net.minecraft.world.level.chunk.ProtoChunk.packOffsetCoordinates(pos));
                  }
               }

               set++;
            }
         }
      }

      if (bulk != null) {
         for (int i = 0; i < bulk.length; i++) {
            if (bulk[i] != null) {
               net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = SectionBuilder.build(bulk[i]);
               LevelChunkSection old = sections[lowSection + i];
               if (states != null) {
                  sections[lowSection + i] = SectionBuilder.section(states, old.getBiomes(), bulk[i]);
                  if (real) {
                     BULK_SECTIONS.increment();
                  }
               } else {
                  // More than 256 states: written block by block after all (the same blocks; heightmaps already done).
                  for (int b = 0; b < 4096; b++) {
                     if (bulk[i][b] != old.getBlockState(b & 15, b >> 8, (b >> 4) & 15)) {
                        old.setBlockState(b & 15, b >> 8, (b >> 4) & 15, bulk[i][b], false);
                     }
                  }
               }
            }
         }
      }

      if (after) {
         mapsAfter(chunk, maps, changedTop);
      }

      if (real) {
         SET_BLOCKS.add(set);
      }
   }

   private static volatile boolean mapsAfterOff;
   private static final java.util.concurrent.atomic.LongAdder MAPS_CHECKED = new java.util.concurrent.atomic.LongAdder();

   /**
    * Each column's heights after the writes: a map's height only changes if a block at or above its height - 1 was
    * written; then it is the highest block from there down that the map counts, plus one (where Minecraft's per-block
    * updates end up: a counted block above the height raises it, the top block no longer counted scans down).
    */
   private static void mapsAfter(ChunkAccess chunk, Heightmap[] maps, int[] changedTop) {
      int min = chunk.getMinBuildHeight();
      for (Heightmap map : maps) {
         java.util.function.Predicate<BlockState> counted = ((com.wgaccel.mixin.access.HeightmapAccessor)map).wgaccel$opaque();
         for (int t = 0; t < 256; t++) {
            int lx = t / 16;
            int lz = t % 16;
            int first = map.getFirstAvailable(lx, lz);
            if (changedTop[t] < first - 1) {
               continue;
            }

            int y = Math.max(changedTop[t], first - 1);
            int found = min;
            for (; y >= min; y--) {
               if (counted.test(chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz)))) {
                  found = y + 1;
                  break;
               }
            }

            if (found != first) {
               ((com.wgaccel.mixin.access.HeightmapAccessor)map).wgaccel$setHeight(lx, lz, found);
            }
         }
      }
   }

   /**
    * A checked chunk (its surface written by Minecraft's own loop and updates): each map's height in every column must be
    * the highest block it counts plus one, the rule {@link #mapsAfter} relies on; a difference turns mapsAfter off.
    */
   private static void checkMaps(ChunkAccess chunk) {
      Heightmap[] maps = heightmaps(chunk);
      if (maps == null) {
         return;
      }

      int min = chunk.getMinBuildHeight();
      int top = chunk.getHighestSectionPosition() + 15;
      for (Heightmap map : maps) {
         java.util.function.Predicate<BlockState> counted = ((com.wgaccel.mixin.access.HeightmapAccessor)map).wgaccel$opaque();
         for (int t = 0; t < 256; t++) {
            int lx = t / 16;
            int lz = t % 16;
            int found = min;
            for (int y = top; y >= min; y--) {
               if (counted.test(chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz)))) {
                  found = y + 1;
                  break;
               }
            }

            if (found != map.getFirstAvailable(lx, lz)) {
               if (!mapsAfterOff) {
                  mapsAfterOff = true;
                  WorldgenAccelerator.LOG.warn("WGA GPU surface: heightmaps after the writes off: chunk {} column ({}, {}) has {} but its highest counted block gives {}",
                     chunk.getPos(), lx, lz, map.getFirstAvailable(lx, lz), found);
               }

               return;
            }
         }
      }

      MAPS_CHECKED.increment();
   }

   /** Returns the lowest of the maps' heights after the updates (floor: the lowest before them). */
   private static int updateMaps(Heightmap[] maps, int[] firsts, int floor, int lx, int y, int lz, BlockState state) {
      boolean changed = false;
      for (int m = 0; m < maps.length; m++) {
         if (y > firsts[m] - 2 && maps[m].update(lx, y, lz, state)) {
            firsts[m] = maps[m].getFirstAvailable(lx, lz);
            changed = true;
         }
      }

      return changed ? lowest(firsts) : floor;
   }

   private static int lowest(int[] firsts) {
      int low = Integer.MAX_VALUE;
      for (int first : firsts) {
         low = Math.min(low, first);
      }

      return low;
   }

   private static final java.util.concurrent.atomic.LongAdder BULK_SECTIONS = new java.util.concurrent.atomic.LongAdder();

   /**
    * The sections to make at once: at least half their blocks changed by the surface (writes that change nothing not
    * counted) and none to air; each starts as the section's blocks now, the loop then puts the new ones in.
    */
   private static BlockState[][] bulkSections(SurfaceGpu.Job job, short[] out, BlockState[] table, byte[] kinds, BlockState defaultBlock, BlockState air,
         LevelChunkSection[] sections, int lowSection) {
      int count = (job.height + 15) / 16;
      int[] changes = new int[count];
      boolean[] toAir = new boolean[count];
      for (int t = 0; t < 256; t++) {
         for (int y = Math.min(job.tops[t], job.minY + job.height - 1); y >= job.minY; y--) {
            int at = (y - job.minY) * 256 + t;
            int code = out[at];
            if (code >= 0) {
               BlockState state = table[code];
               if (kinds[at] == 2 && state == defaultBlock || kinds[at] == 0 && state == air) {
                  continue;
               }

               int section = (y - job.minY) >> 4;
               changes[section]++;
               toAir[section] |= state.is(Blocks.AIR);
            }
         }
      }

      BlockState[][] bulk = null;
      for (int i = 0; i < count; i++) {
         // Any job height (it ends one above the highest column, a multiple of 16 in about one chunk of 16, which alone
         // got sections at once until 0.9.48): a top section the job covers in part keeps its blocks above it, as each
         // starts as the section's blocks now.
         if (changes[i] >= 2048 && !toAir[i]) {
            if (bulk == null) {
               bulk = new BlockState[count][];
            }

            LevelChunkSection section = sections[lowSection + i];
            BlockState[] blocks = new BlockState[4096];
            // Block by block (0.9.51's one pass, PalettedContainer.getAll, threw "No value with id -1" with Lithium's
            // palettes and turned the GPU surface off).
            for (int b = 0; b < 4096; b++) {
               blocks[b] = section.getBlockState(b & 15, b >> 8, (b >> 4) & 15);
            }

            bulk[i] = blocks;
         }
      }

      return bulk;
   }

   /** The heightmaps ProtoChunk.setBlockState would update now, or null to write through setBlockState itself. */
   private static Heightmap[] heightmaps(ChunkAccess chunk) {
      if (!(chunk instanceof net.minecraft.world.level.chunk.ProtoChunk) || chunk.getMinBuildHeight() % 16 != 0
         || chunk.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.INITIALIZE_LIGHT)) {
         return null;
      }

      java.util.EnumSet<Heightmap.Types> types = chunk.getPersistedStatus().heightmapsAfter();
      Heightmap[] maps = new Heightmap[types.size()];
      int i = 0;
      for (Heightmap.Types type : types) {
         if (!chunk.hasPrimedHeightmap(type)) {
            return null;
         }

         maps[i++] = chunk.getOrCreateHeightmapUnprimed(type);
      }

      return maps;
   }

   /**
    * The heights vanilla's loop reads: in a chunk written from the GPU, the eroded badlands test (first) gets the
    * column's height from before the writes and the loop's start (second) lies below the world, so it runs no rows.
    */
   public static int height(ChunkAccess chunk, int lx, int lz, boolean loopStart, int original) {
      Current current = CURRENT.get();
      if (!current.skipLoop || current.chunk != chunk) {
         return original;
      }

      return loopStart ? chunk.getMinBuildHeight() - 2 : current.job.heights[(lx & 15) * 16 + (lz & 15)];
   }

   /** Whether this thread's chunk was written from the GPU (vanilla's loop is skipped: its heights are answered by {@link #height}). */
   public static boolean skipping(ChunkAccess chunk) {
      Current current = CURRENT.get();
      return current.skipLoop && current.chunk == chunk;
   }

   /** At the end of buildSurface: a checked chunk's blocks compared with the GPU's. */
   public static void end(ChunkAccess chunk) {
      Current current = CURRENT.get();
      if (current.timedWay != 0 && current.chunk == chunk) {
         com.wgaccel.gpu.GpuQueue.surfaceTimed(current.timedWay == 1, System.nanoTime() - current.start);
      }

      current.timedWay = 0;
      SurfaceAhead.takeCodes(chunk);
      boolean check = current.checkReady && current.chunk == chunk;
      TRACED.remove(chunk);
      java.util.concurrent.ConcurrentLinkedQueue<Long> foreign = FOREIGN.remove(chunk);
      current.foreignMarks = foreign;
      current.active = false;
      current.skipLoop = false;
      current.checkReady = false;
      current.chunk = null;
      current.biomeManager = null;
      current.noise = null;
      if (!check) {
         return;
      }

      try {
         CPU_NANOS.add(System.nanoTime() - current.start);
         Program p = current.program;
         SurfaceGpu.Job job = current.job;
         int height = job.height;
         int minY = job.minY;
         BlockState air = Blocks.AIR.defaultBlockState();
         int differ = 0;
         int set = 0;
         String first = null;
         for (int y0 = minY; y0 < minY + height; y0 = (y0 & ~15) + 16) {
            int y1 = Math.min(minY + height, (y0 & ~15) + 16);
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y0));
            boolean empty = section.hasOnlyAir();
            for (int y = y0; y < y1; y++) {
               for (int t = 0; t < 256; t++) {
                  int lx = t / 16;
                  int lz = t % 16;
                  int at = (y - minY) * 256 + t;
                  int code = current.out[at];
                  BlockState expected = code >= 0 ? p.table[code] : current.before[at];
                  BlockState game = empty ? air : section.getBlockState(lx, y & 15, lz);
                  set += code >= 0 ? 1 : 0;
                  if (expected != game) {
                     differ++;
                     if (first == null) {
                        first = String.format(Locale.ROOT, "first at (%d, %d, %d): game %s, gpu %s (before %s)", job.minX + lx, y, job.minZ + lz, game, expected,
                           current.before[at]);
                     }
                  }
               }
            }
         }

         if (differ == 0 && current.mapsBefore != null && !writeCheckOff) {
            String written;
            try {
               written = writeCheck(current, p, chunk);
            } catch (Throwable failure) {
               // A fault in the check itself: the check goes, the surface on the GPU stays.
               writeCheckOff = true;
               written = null;
               com.wgaccel.WgaChat.warnOff("WGA GPU surface write check off: {}", failure.toString());
            }

            if (written != null) {
               differ = 1;
               first = "the write: " + written;
               if (WRITE_DIFFERENT.sum() < 5) {
                  WorldgenAccelerator.LOG.warn("WGA GPU surface write check: chunk {}: {}", chunk.getPos(), written);
               }
               WRITE_DIFFERENT.increment();
            } else if (!writeCheckOff) {
               WRITE_CHECKED.increment();
            }
         }

         CHECKED.increment();
         CHECKED_BLOCKS.add(256L * height);
         if (com.wgaccel.Config.GPU_SURFACE_MAPS_AFTER.get() && !mapsAfterOff) {
            checkMaps(chunk);
         }
         SET_BLOCKS.add(set);
         if (differ > 0) {
            DIFFERENT.increment();
            if (LOGGED_DIFFERENCES.incrementAndGet() <= LOGGED_MAX) {
               WorldgenAccelerator.LOG.warn("WGA GPU surface check: chunk {} differs: {} blocks, {}", chunk.getPos(), differ, first);
            }
         }

         report();
      } catch (Throwable failure) {
         failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU surface check failed (chunks get their surface on the CPU from now on): {}", failure.toString());
      }
   }

   private static final LongAdder WRITE_CHECKED = new LongAdder();
   private static final LongAdder CODES_CHECKED = new LongAdder();
   private static final LongAdder CODES_DIFFERENT = new LongAdder();
   private static final LongAdder CODES_WATER = new LongAdder();
   private static volatile boolean writeCheckOff;
   private static final LongAdder WRITE_DIFFERENT = new LongAdder();

   /**
    * The write check of a checked chunk (the GPU's blocks matched the CPU's): the sections the surface works on rebuilt
    * as they were before it (the blocks read for the check; above the job's top the blocks now, which the surface does
    * not touch), the heightmaps and marks as they were, the GPU's blocks written into those copies the way a chunk from
    * the GPU is written (sections made at once included), then everything compared with what the CPU's surface made:
    * block states, block counts, heightmaps, fluid marks. Null when the same, else the first difference.
    */
   private static String writeCheck(Current current, Program p, ChunkAccess chunk) {
      String atOnce = writeCheck(current, p, chunk, true);
      if (atOnce == null) {
         return null;
      }

      // Different: the same write once more without sections made at once, to say which part differs.
      String plain = writeCheck(current, p, chunk, false);
      return atOnce + (plain == null ? "; block by block: the same as the CPU (so sections made at once differ)" : "; block by block too: " + plain);
   }

   private static String writeCheck(Current current, Program p, ChunkAccess chunk, boolean atOnceWrite) {
      SurfaceGpu.Job job = current.job;
      int minY = job.minY;
      int height = job.height;
      LevelChunkSection[] real = chunk.getSections();
      LevelChunkSection[] copies = real.clone();
      int low = chunk.getSectionIndex(minY);
      int high = chunk.getSectionIndex(minY + height - 1);
      BlockState air = Blocks.AIR.defaultBlockState();
      for (int i = low; i <= high; i++) {
         LevelChunkSection copy = new LevelChunkSection(new net.minecraft.world.level.chunk.PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
            air, net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES), real[i].getBiomes());
         int sectionMinY = chunk.getSectionYFromSectionIndex(i) << 4;
         for (int b = 0; b < 4096; b++) {
            int lx = b & 15;
            int ly = b >> 8;
            int lz = (b >> 4) & 15;
            int y = sectionMinY + ly;
            BlockState state = y < minY + height ? current.before[(y - minY) * 256 + lx * 16 + lz] : real[i].getBlockState(lx, ly, lz);
            if (state != air) {
               copy.setBlockState(lx, ly, lz, state, false);
            }
         }

         copies[i] = copy;
      }

      Heightmap[] realMaps = heightmaps(chunk);
      if (realMaps == null || realMaps.length != current.mapsBefore.length) {
         return null;
      }

      Heightmap[] maps = new Heightmap[realMaps.length];
      java.util.List<Heightmap.Types> types = new java.util.ArrayList<>(chunk.getPersistedStatus().heightmapsAfter());
      for (int i = 0; i < maps.length; i++) {
         maps[i] = new Heightmap(chunk, types.get(i));
         maps[i].setRawData(chunk, types.get(i), current.mapsBefore[i]);
      }

      it.unimi.dsi.fastutil.shorts.ShortList[] marks = current.marksBefore.clone();
      for (int i = 0; i < marks.length; i++) {
         marks[i] = marks[i] == null ? null : new it.unimi.dsi.fastutil.shorts.ShortArrayList(marks[i]);
      }

      // The step's end has let go of the chunk already; the write needs it (section indexes, the setBlockState path).
      current.chunk = chunk;
      try {
         write(current, p, copies, maps, marks, false, atOnceWrite);
      } finally {
         current.chunk = null;
      }
      for (int i = low; i <= high; i++) {
         // Block by block: packed palettes follow the order blocks were written in (Lithium's pack keeps the palette's
         // order), so the same blocks can pack differently (0.9.50: all "differences" were such, 0 blocks different).
         String blocks = firstWriteDifference(current, p, chunk, copies[i], real[i], i);
         if (blocks != null) {
            return "section " + i + " blocks, " + blocks;
         }

         com.wgaccel.mixin.access.LevelChunkSectionAccessor x = (com.wgaccel.mixin.access.LevelChunkSectionAccessor)copies[i];
         com.wgaccel.mixin.access.LevelChunkSectionAccessor y = (com.wgaccel.mixin.access.LevelChunkSectionAccessor)real[i];
         if (x.wgaccel$nonEmpty() != y.wgaccel$nonEmpty() || x.wgaccel$tickingBlocks() != y.wgaccel$tickingBlocks() || x.wgaccel$tickingFluids() != y.wgaccel$tickingFluids()) {
            return String.format(Locale.ROOT, "section %d block counts (game %d, %d, %d; gpu %d, %d, %d)", i, y.wgaccel$nonEmpty(), y.wgaccel$tickingBlocks(),
               y.wgaccel$tickingFluids(), x.wgaccel$nonEmpty(), x.wgaccel$tickingBlocks(), x.wgaccel$tickingFluids());
         }
      }

      for (int i = 0; i < maps.length; i++) {
         if (!java.util.Arrays.equals(maps[i].getRawData(), realMaps[i].getRawData())) {
            return "heightmap " + types.get(i);
         }
      }

      it.unimi.dsi.fastutil.shorts.ShortList[] realMarks = chunk.getPostProcessing();
      if (current.foreignMarks != null) {
         // Marks other threads made meanwhile (neighbours' features): in the real chunk either way.
         for (long packed : current.foreignMarks) {
            BlockPos at = BlockPos.of(packed);
            int index = chunk.getSectionIndex(at.getY());
            if (index >= 0 && index < marks.length) {
               if (marks[index] == null) {
                  marks[index] = new it.unimi.dsi.fastutil.shorts.ShortArrayList();
               }

               marks[index].add(net.minecraft.world.level.chunk.ProtoChunk.packOffsetCoordinates(at));
            }
         }
      }

      for (int i = 0; i < marks.length; i++) {
         if (!sortedMarks(marks[i]).equals(sortedMarks(realMarks[i]))) {
            return "fluid marks of section " + i + markDifference(current, chunk, i, marks[i], realMarks[i], current.marksBefore[i]);
         }
      }

      return null;
   }

   /** Where a written copy and the CPU's section first differ, with what the GPU's code and the column say there. */
   private static String firstWriteDifference(Current current, Program p, ChunkAccess chunk, LevelChunkSection copy, LevelChunkSection real, int index) {
      SurfaceGpu.Job job = current.job;
      int sectionMinY = chunk.getSectionYFromSectionIndex(index) << 4;
      int count = 0;
      String first = null;
      for (int b = 0; b < 4096; b++) {
         int lx = b & 15;
         int ly = b >> 8;
         int lz = (b >> 4) & 15;
         BlockState a = copy.getBlockState(lx, ly, lz);
         BlockState g = real.getBlockState(lx, ly, lz);
         if (a != g) {
            count++;
            if (first == null) {
               int y = sectionMinY + ly;
               int t = lx * 16 + lz;
               boolean inJob = y >= job.minY && y < job.minY + job.height;
               int at = (y - job.minY) * 256 + t;
               first = String.format(Locale.ROOT, "first at (%d, %d, %d): game %s, written %s; code %s, before %s, kind %s, column top %d, job y %d..%d",
                  job.minX + lx, y, job.minZ + lz, g, a, inJob ? Integer.toString(current.out[at]) : "-", inJob ? current.before[at] : "-",
                  inJob ? Integer.toString(job.kinds[at]) : "-", job.tops[t], job.minY, job.minY + job.height - 1);
            }
         }
      }

      return count == 0 ? null : count + " blocks, " + first;
   }

   /**
    * Which fluid marks differ (at most 4 of each kind): the GPU way's only and the CPU's only, each with its position,
    * the block before the surface, the CPU's block there now, whether the mark was there before the surface, and the
    * GPU's code there (-1: the GPU leaves the block alone).
    */
   private static String markDifference(Current current, ChunkAccess chunk, int index, it.unimi.dsi.fastutil.shorts.ShortList gpu,
         it.unimi.dsi.fastutil.shorts.ShortList cpu, it.unimi.dsi.fastutil.shorts.ShortList before) {
      it.unimi.dsi.fastutil.shorts.ShortList a = sortedMarks(gpu);
      it.unimi.dsi.fastutil.shorts.ShortList b = sortedMarks(cpu);
      it.unimi.dsi.fastutil.shorts.ShortList was = sortedMarks(before);
      StringBuilder text = new StringBuilder(String.format(Locale.ROOT, " (gpu %d marks, cpu %d, before the surface %d)", a.size(), b.size(), was.size()));
      int sectionY = chunk.getSectionYFromSectionIndex(index);
      for (int side = 0; side < 2; side++) {
         it.unimi.dsi.fastutil.shorts.ShortList mine = side == 0 ? a : b;
         it.unimi.dsi.fastutil.shorts.ShortList other = side == 0 ? b : a;
         int shown = 0;
         for (int k = 0; k < mine.size() && shown < 4; k++) {
            short packed = mine.getShort(k);
            int inMine = count(mine, packed);
            if (count(other, packed) >= inMine) {
               continue;
            }

            if (k > 0 && mine.getShort(k - 1) == packed) {
               continue;
            }

            shown++;
            BlockPos at = net.minecraft.world.level.chunk.ProtoChunk.unpackOffsetCoordinates(packed, sectionY, chunk.getPos());
            String blockBefore = "?";
            String gpuCode = "?";
            SurfaceGpu.Job job = current.job;
            int lx = at.getX() & 15;
            int lz = at.getZ() & 15;
            if (at.getY() >= job.minY && at.getY() < job.minY + job.height) {
               int column = lx * 16 + lz;
               blockBefore = String.valueOf(current.before[(at.getY() - job.minY) * 256 + column]);
               gpuCode = String.valueOf(current.out[(at.getY() - job.minY) * 256 + column]);
            }

            text.append(String.format(Locale.ROOT, "; %s only: %s x%d (cpu x%d) block before %s, cpu now %s, marked before %s, gpu code %s",
               side == 0 ? "gpu" : "cpu", at.toShortString(), inMine, count(other, packed), blockBefore, chunk.getBlockState(at), count(was, packed) > 0, gpuCode));
         }
      }

      return text.toString();
   }

   private static int count(it.unimi.dsi.fastutil.shorts.ShortList sorted, short value) {
      int n = 0;
      for (int i = 0; i < sorted.size(); i++) {
         n += sorted.getShort(i) == value ? 1 : 0;
      }

      return n;
   }

   private static it.unimi.dsi.fastutil.shorts.ShortList sortedMarks(it.unimi.dsi.fastutil.shorts.ShortList list) {
      it.unimi.dsi.fastutil.shorts.ShortArrayList sorted = list == null ? new it.unimi.dsi.fastutil.shorts.ShortArrayList() : new it.unimi.dsi.fastutil.shorts.ShortArrayList(list);
      sorted.sort(null);
      return sorted;
   }

   /** A summary every so often: chunks from the GPU, checked and identical, left to the CPU and why, times per chunk. */
   private static void report() {
      if (!MARK_CALLERS.isEmpty()) {
         WorldgenAccelerator.LOG.info(markCallers());
      }

      long done = WRITTEN.sum() + CHECKED.sum();
      int next = NEXT_REPORT.get();
      if (done < next || !NEXT_REPORT.compareAndSet(next, next < 1024 ? next * 2 : next + 2048)) {
         return;
      }

      long checked = CHECKED.sum();
      long gpuChunks = Math.max(1, done + SKIP_SPECIAL.sum());
      Program p = program;
      double[] kernels = p == null ? null : p.gpu.kernelMillis();
      String split = kernels == null ? "" : String.format(Locale.ROOT, " (on the GPU, sampled: strata %.2f, prepass %.2f, blocks %.2f, copy out %.2f)", kernels[0],
         kernels[1], kernels[2], kernels[3]);
      double[] batchKernels = p == null ? null : p.gpu.batchKernelMillis();
      if (batchKernels != null) {
         split += String.format(Locale.ROOT, " (batches on the GPU per chunk, sampled: copy in %.2f, strata %.2f, prepass %.2f, blocks %.2f, copy out %.2f)", batchKernels[0],
            batchKernels[1], batchKernels[2], batchKernels[3], batchKernels[4]);
      }

      double[] batchHost = p == null ? null : p.gpu.batchHostMillis();
      if (batchHost != null) {
         split += String.format(Locale.ROOT, " (a sampled batch from its thread: prep %.2f ms, enqueue %.2f, waiting for the card to start it %.2f, on the card %.2f,"
            + " waking up after %.2f)", batchHost[0], batchHost[1], batchHost[2], batchHost[3], batchHost[4]);
      }
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU surface: %,d chunks (%,d written from the GPU, %,d checked: %s, %,d blocks; %,d blocks set by the rules); on the CPU: %,d with eroded badlands, frozen ocean or unsure strata, %,d other, %,d while the GPU was busy; per chunk: CPU inputs %.2f ms, GPU %.2f ms%s, writing %.2f ms%s",
         done, WRITTEN.sum(), checked, DIFFERENT.sum() == 0 ? "IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", DIFFERENT.sum()), CHECKED_BLOCKS.sum(),
         SET_BLOCKS.sum(), SKIP_SPECIAL.sum(), SKIP_OTHER.sum(), SKIP_BUSY.sum(), PREP_NANOS.sum() / 1.0e6 / gpuChunks, GPU_NANOS.sum() / 1.0e6 / gpuChunks, split,
         WRITE_NANOS.sum() / 1.0e6 / Math.max(1, WRITTEN.sum()), checked == 0 ? "" : String.format(Locale.ROOT, ", the whole surface step of checked chunks %.2f ms",
            CPU_NANOS.sum() / 1.0e6 / checked)) + (p != null && p.batcher() != null ? p.batcher().summary() : "") + SurfaceAhead.summary()
            + com.wgaccel.gpu.GpuQueue.summary()
            + String.format(Locale.ROOT, "; heightmaps after the writes %s (rule checked in %,d chunks)", mapsAfterOff ? "OFF (a difference)" : "on", MAPS_CHECKED.sum())
            + String.format(Locale.ROOT, "; %,d writes skipped (already that block); %,d sections made at once (%,d with over 256 states not); writes checked %,d%s; codes checked %,d%s; %,d chunks' inputs from the fill's codes", SAME_SKIPPED.sum(),
               BULK_SECTIONS.sum(), SectionBuilder.TOO_MANY.sum(), WRITE_CHECKED.sum(), WRITE_DIFFERENT.sum() == 0 ? " IDENTICAL" : String.format(Locale.ROOT, ", %,d DIFFER", WRITE_DIFFERENT.sum()),
               CODES_CHECKED.sum(), CODES_DIFFERENT.sum() == 0 ? " IDENTICAL"
                  : String.format(Locale.ROOT, ", %,d DIFFER (%,d only FreeTerraForged's river step: air in the codes, water, stone or ice in the chunk; %,d otherwise)", CODES_DIFFERENT.sum(), CODES_WATER.sum(), CODES_DIFFERENT.sum() - CODES_WATER.sum()),
               SurfaceAhead.FROM_CODES.sum()) + (AHEAD_DIFFERENT.sum() == 0 ? "" : String.format(Locale.ROOT,
            ", %,d chunks' inputs made ahead DIFFER", AHEAD_DIFFERENT.sum())));
   }

   /** For the memory report: chunks the surface check still holds. */
   public static String memorySummary() {
      return String.format(java.util.Locale.ROOT, "surface traced chunks %,d, foreign marks %,d", TRACED.size(), FOREIGN.size());
   }
}
