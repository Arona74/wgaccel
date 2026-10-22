package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.BiomeGpu;
import com.wgaccel.gpu.BiomeKernels;
import com.wgaccel.gpu.BiomeTable;
import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.GpuService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.fml.loading.FMLPaths;

/**
 * GPU step G5.2b (config gpuBiomes ON): the overworld biome step's climate samples and climate-table searches from the
 * GPU. Made when the overworld loads (the router's six climate functions, FreeTerraForged's underground climate policy,
 * ocean projection and the plan's table: {@link BiomeKernels}, {@link BiomeTable}, {@link BiomeGpu}). Per chunk, at its
 * first climate sample (the sampler is known then) the chunk's inputs ({@link BiomeInputs}) and one run for all its
 * cells (the chunk's 4 x 4 quart columns, every quart y of the world); then each sample of a cell of the chunk returns
 * the GPU's target point, and the search right after it (same cell, the same target object) a ProviderResult made as
 * FreeTerraForged makes it (the domain, the biome as base and result, no fallback, the candidates, the projected
 * target). Anything else (other positions, a tie of the smallest distance between biomes, another domain) is the
 * CPU's. Every 64th chunk the CPU runs too and the results are compared ("WGA GPU biomes").
 */
public final class GpuBiomes {
   private static final int CHECK_EVERY = 64;

   /** vanilla: C2, the table is vanilla's MultiNoiseBiomeSource's (its getNoiseBiome is hooked, not FreeTerraForged's). */
   record Program(RandomState random, List<Object> cellSamplers, BiomeTable table, BiomeGpu gpu, boolean vanilla) {
   }

   private static final class Current {
      final BiomeInputs inputs = new BiomeInputs();
      final BiomeGpu.Job job = new BiomeGpu.Job();
      long[] climate = new long[0];
      long[] projected = new long[0];
      int[] chosen = new int[0];
      int[] tied = new int[0];
      int[] decorated = new int[0];
      /** C1: per other provider k, cell i at k * n + i: its projected continentalness and chosen number (-1: a tie). */
      long[] extraCC = new long[0];
      int[] extraChosen = new int[0];
      /** C1: per other provider k, cell i at k * n + i: its decorated number (-1: the CPU's), when hasExtraDecorated. */
      int[] extraDecorated = new int[0];
      boolean hasExtraDecorated;
      int extras;
      /** C1 (multi): per quart column the provider the GPU searched and decorated (0 the first; -1 unknown: the CPU's). */
      final int[] provider = new int[16];
      boolean hasDecorated;
      Climate.TargetPoint[] made = new Climate.TargetPoint[0];
      ChunkAccess chunk;
      Program program;
      boolean active;
      boolean inStep;
      boolean ran;
      boolean check;
      int quartX;
      int quartZ;
      int quartY;
      int quartHeight;
      int selecting = -1;
   }

   private static volatile Program program;
   private static volatile boolean failed;
   private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);
   private static final AtomicInteger ELIGIBLE = new AtomicInteger();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(64);
   private static final AtomicInteger LOGGED = new AtomicInteger();
   private static final LongAdder CHUNKS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder SAMPLES = new LongAdder();
   private static final LongAdder SEARCHES = new LongAdder();
   private static final LongAdder TIES = new LongAdder();
   /** C1: searches of other providers' tables from the GPU (part of SEARCHES). */
   private static final LongAdder EXTRA_SEARCHES = new LongAdder();
   /** C1 (multi): searches whose domain was not the column's predicted provider (the CPU's). */
   private static final LongAdder MISPREDICTED = new LongAdder();
   private static final LongAdder CPU_SAMPLES = new LongAdder();
   private static final LongAdder CPU_SEARCHES = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final LongAdder INPUT_NANOS = new LongAdder();
   private static final LongAdder GPU_NANOS = new LongAdder();

   private GpuBiomes() {
   }

   static Program program() {
      return failed ? null : program;
   }

   static boolean ready() {
      return program != null && !failed;
   }

   /** At the overworld's load (gpuBiomes ON, gpuMode not OFF): makes and compiles the code on its own thread. */
   public static void start(ServerLevel overworld) {
      program = null;
      failed = false;
      // The last world's FreeTerraForged context must not be used: its tile cache is closed (0.9.46's second world: every
      // look-ahead chunk failed with "Cache is closed", so all its biomes went to the CPU, 0:59 instead of 0:51).
      decorContext = null;
      if (Config.GPU_BIOMES.get() != Config.GpuFilterMode.ON || Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      if (!overworld.getChunkSource().getGenerator().getClass().getName().contains("TerraForged") && !Config.GPU_BIOMES_VANILLA.get() && !com.wgaccel.c2me.AreaStep.on()) {
         WorldgenAccelerator.LOG.info("WGA GPU biomes: off in this world (not FreeTerraForged's; gpuBiomesVanilla is false)");
         return;
      }

      com.wgaccel.gpu.GpuPrepare.Task task = com.wgaccel.gpu.GpuPrepare.begin("biomes");
      Thread thread = new Thread(() -> {
         long start = System.nanoTime();
         try {
            program = build(overworld.getChunkSource().randomState(), overworld.getChunkSource().getGenerator().getBiomeSource());
            task.done();
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU biomes: ready in %.0f ms; the climate and table search of chunks now come from the GPU (every 64th checked)",
               (System.nanoTime() - start) / 1.0e6));
         } catch (DensityCodegen.Unsupported unsupported) {
            task.failed(unsupported.kind);
            com.wgaccel.WgaChat.warnOff("WGA GPU biomes off: {} has no GPU version", unsupported.kind);
         } catch (Throwable failure) {
            task.failed(failure.toString());
            com.wgaccel.WgaChat.warnOff("WGA GPU biomes off: {}", com.wgaccel.gpu.GpuSelfTest.describe(failure));
         }
      }, "WGA GPU biomes compile");
      thread.setDaemon(true);
      thread.start();
      BiomesAhead.start(overworld);
   }

   private static Program build(RandomState random, net.minecraft.world.level.biome.BiomeSource biomeSource) throws Exception {
      NoiseRouter router = random.router();
      // C2: without FreeTerraForged's plan (no getWorldgenPlan), vanilla's MultiNoiseBiomeSource's own parameter list.
      boolean vanilla = false;
      try {
         random.sampler().getClass().getMethod("getWorldgenPlan");
      } catch (NoSuchMethodException noPlan) {
         if (!(biomeSource instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource)) {
            throw new IllegalStateException("the overworld's biome source is " + biomeSource.getClass().getSimpleName() + ", not a MultiNoiseBiomeSource");
         }

         vanilla = true;
      }

      DensityCodegen codegen = new DensityCodegen();
      // C2: column-only climate noises and splines remembered per thread (BiomeKernels: a thread per 8 cells of a column).
      codegen.hoistColumns(true);
      String[] climate = {codegen.function(DensityCodegen.unwrap(router.temperature())), codegen.function(DensityCodegen.unwrap(router.vegetation())),
         codegen.function(DensityCodegen.unwrap(router.continents())), codegen.function(DensityCodegen.unwrap(router.erosion())),
         codegen.function(DensityCodegen.unwrap(router.depth())), codegen.function(DensityCodegen.unwrap(router.ridges()))};
      BiomeTable table;
      if (vanilla) {
         java.lang.reflect.Method parameters = net.minecraft.world.level.biome.MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
         parameters.setAccessible(true);
         table = BiomeTable.ofParameters("vanilla", (Climate.ParameterList<?>)parameters.invoke(biomeSource));
      } else {
         table = BiomeTable.of(random);
      }

      String source = codegen.source(List.of()) + BiomeKernels.source(climate, codegen.cacheSlots());
      try {
         Files.writeString(FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-biomes.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      try {
         java.nio.file.Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
         com.wgaccel.gpu.BiomeDump.writeTable(logs.resolve("wgaccel-biome-table.bin"), table, codegen.noiseData(), codegen.permutations());
         Files.deleteIfExists(logs.resolve("wgaccel-biome-jobs.bin"));
      } catch (Exception ignored) {
      }

      com.wgaccel.gpu.BiomeDecoration decoration = null;
      if (!vanilla && Config.GPU_BIOME_DECORATION.get() != Config.GpuFilterMode.OFF) {
         try {
            decoration = com.wgaccel.gpu.BiomeDecoration.of(random, table);
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
               "WGA GPU biome decoration: depth range %d..%d (deep from %d, full density from %d), density %s, banding %s, stages %s, roots original %d, background %d, river %d, %d tree nodes, %d biomes",
               decoration.longs[0], decoration.longs[1], decoration.longs[2], decoration.longs[3], decoration.density, decoration.ints[4] != 0, decoration.ints[3] != 0,
               decoration.ints[0], decoration.ints[1], decoration.ints[2], decoration.count.length, table.biomes.size()));
         } catch (Throwable failure) {
            com.wgaccel.WgaChat.warnOff("WGA GPU biome decoration off: {}", failure.toString());
         }
      }

      BiomeGpu gpu = decoration == null
         ? new BiomeGpu(GpuService.context(), source, codegen.noiseData(), codegen.permutations(), table.nodeBounds, table.nodeFirst, table.nodeCount, table.nodeBiome,
            table.sourceMin, table.sourceMax, table.targetMax)
         : new BiomeGpu(GpuService.context(), source, codegen.noiseData(), codegen.permutations(), decoration.bounds, decoration.first, decoration.count, decoration.biome,
            table.sourceMin, table.sourceMax, table.targetMax, decoration.longs, decoration.ints, decoration.density, decoration.presetCoverage, decoration.flags);
      if (table.extraRoots.length > 0 && table.spatialResolver != null) {
         // C1: one provider per cell: each quart column's provider worked out on the CPU (regions), the GPU searching and
         // decorating only that one (its tree root, projection and Layout).
         int providers = 1 + table.extraRoots.length;
         int[] roots = new int[providers];
         long[] maxima = new long[providers];
         maxima[0] = table.targetMax;
         for (int k = 1; k < providers; k++) {
            roots[k] = table.extraRoots[k - 1];
            maxima[k] = table.extraTargetMax[k - 1];
         }

         if (decoration != null) {
            long[][] longs = new long[providers][];
            int[][] ints = new int[providers][];
            float[] density = new float[providers];
            int[][] flags = new int[providers][];
            longs[0] = decoration.longs;
            ints[0] = decoration.ints;
            density[0] = decoration.density;
            flags[0] = decoration.flags;
            for (int k = 1; k < providers; k++) {
               longs[k] = decoration.extraLongs[k - 1];
               ints[k] = decoration.extraInts[k - 1];
               density[k] = decoration.extraDensity[k - 1];
               flags[k] = decoration.extraFlags[k - 1];
            }

            gpu.setProviders(roots, maxima, longs, ints, density, flags);
         } else {
            gpu.setProviders(roots, maxima, null, null, null, null);
         }

         WorldgenAccelerator.LOG.info("WGA GPU biomes: {} other providers, each cell searched in its own column's ({}), deferred placeholder number {}", table.extraIds.size(),
            table.extraIds, table.deferredNumber);
      }

      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU biomes: %d CellSampler inputs, a table of %,d points (%d biomes), ocean projection to %d",
         codegen.cellSamplers().size(), table.values.size(), table.biomeCount, table.targetMax));
      return new Program(random, List.copyOf(codegen.cellSamplers()), table, gpu, vanilla);
   }

   /** At doCreateBiomes' start: this chunk's cells may come from the GPU. */
   public static void begin(ChunkAccess chunk, RandomState random) {
      Current current = CURRENT.get();
      current.active = false;
      current.inStep = false;
      current.ran = false;
      current.hasDecorated = false;
      current.selecting = -1;
      Program p = program;
      if (p == null || failed || random != p.random || Config.GPU_BIOMES.get() != Config.GpuFilterMode.ON) {
         return;
      }

      current.program = p;
      current.chunk = chunk;
      current.quartX = chunk.getPos().getMinBlockX() >> 2;
      current.quartZ = chunk.getPos().getMinBlockZ() >> 2;
      current.quartY = chunk.getMinBuildHeight() >> 2;
      current.quartHeight = (chunk.getMaxBuildHeight() >> 2) - current.quartY;
      current.active = true;
      current.inStep = true;
      BiomesAhead look = BiomesAhead.active();
      if (look != null) {
         // G5.3: the result made ahead, or the CPU (a GPU run now would make the chunk wait).
         BiomesAhead.Result result = look.take(chunk.getPos());
         if (result == null || result.quartY() != current.quartY || result.quartHeight() != current.quartHeight) {
            if (result != null) {
               look.mismatched.increment();
            }

            current.active = false;
            return;
         }

         // The identical-world rule, as the fill look-ahead's: the result is used only if the surface envelope's
         // minimums it was made from are still the ones FreeTerraForged gives now (22 Oct 2026, FreeTerraForged
         // 1.0.0013, RX 9070 XT: chunk [35, 65]'s quart (142, 40, 262) had depth 3790 in the game, 0 from a result made
         // ahead: lush caves against desert).
         if (result.minimumSurface() != null && !sameEnvelope(look, chunk, result.minimumSurface())) {
            current.active = false;
            return;
         }

         unpack(current, result);
      }
   }

   private static final ThreadLocal<int[]> ENVELOPE_NOW = ThreadLocal.withInitial(() -> new int[16]);

   private static boolean sameEnvelope(BiomesAhead look, ChunkAccess chunk, int[] made) {
      boolean banded = false;
      for (int value : made) {
         banded |= value != Integer.MAX_VALUE;
      }

      if (!banded) {
         return true;
      }

      int[] now = ENVELOPE_NOW.get();
      try {
         if (!BiomeInputs.minimumSurfaces(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), now)) {
            return false;
         }
      } catch (ReflectiveOperationException | RuntimeException failure) {
         return false;
      }

      for (int i = 0; i < 16; i++) {
         if (now[i] != made[i]) {
            look.inputsChanged.increment();
            if (look.inputsLogged.incrementAndGet() <= 8) {
               WorldgenAccelerator.LOG.info("WGA GPU biomes ahead: chunk {}'s surface envelope changed since its result was made (quart column {}: {} now, {} made); its biomes the usual way",
                  chunk.getPos(), i, now[i], made[i]);
            }

            return false;
         }
      }

      return true;
   }

   private static void unpack(Current current, BiomesAhead.Result result) {
      int n = 16 * current.quartHeight;
      if (current.climate.length < 6 * n) {
         current.climate = new long[6 * n];
         current.projected = new long[6 * n];
         current.chosen = new int[n];
         current.tied = new int[n];
         current.decorated = new int[n];
         current.made = new Climate.TargetPoint[n];
      }

      java.util.Arrays.fill(current.made, 0, n, null);
      if (result.provider() != null) {
         System.arraycopy(result.provider(), 0, current.provider, 0, 16);
      }

      int[] extra = result.extra();
      current.extras = extra == null ? 0 : extra.length / (16 + n);
      if (current.extraCC.length < current.extras * n) {
         current.extraCC = new long[current.extras * n];
         current.extraChosen = new int[current.extras * n];
         current.extraDecorated = new int[current.extras * n];
      }

      current.hasExtraDecorated = result.extraDecorated() != null;
      if (current.hasExtraDecorated) {
         System.arraycopy(result.extraDecorated(), 0, current.extraDecorated, 0, current.extras * n);
      }

      for (int k = 0; k < current.extras; k++) {
         int at = k * (16 + n);
         for (int i = 0; i < n; i++) {
            current.extraCC[k * n + i] = extra[at + (i & 15)];
            current.extraChosen[k * n + i] = extra[at + 16 + i];
         }
      }

      current.hasDecorated = result.decorated() != null;
      if (current.hasDecorated) {
         System.arraycopy(result.decorated(), 0, current.decorated, 0, n);
      }

      int[] columns = result.columns();
      int[] cells = result.cells();
      for (int i = 0; i < n; i++) {
         int at = (i & 15) * 6;
         long[] c = current.climate;
         long[] t = current.projected;
         c[i * 6] = t[i * 6] = columns[at];
         c[i * 6 + 1] = t[i * 6 + 1] = columns[at + 1];
         c[i * 6 + 2] = columns[at + 2];
         t[i * 6 + 2] = columns[at + 4];
         c[i * 6 + 3] = t[i * 6 + 3] = columns[at + 3];
         c[i * 6 + 4] = t[i * 6 + 4] = cells[i * 2];
         c[i * 6 + 5] = t[i * 6 + 5] = columns[at + 5];
         int chosen = cells[i * 2 + 1];
         current.tied[i] = chosen < 0 ? 1 : 0;
         current.chosen[i] = Math.max(0, chosen);
      }

      current.ran = true;
      current.check = ELIGIBLE.getAndIncrement() % CHECK_EVERY == 0;
      CHUNKS.increment();
      if (current.check) {
         CHECKED.increment();
      }

      report();
   }

   public static void end() {
      Current current = CURRENT.get();
      current.active = false;
      current.inStep = false;
      current.chunk = null;
      current.selecting = -1;
   }

   private static int cell(Current current, int quartX, int quartY, int quartZ) {
      int x = quartX - current.quartX;
      int y = quartY - current.quartY;
      int z = quartZ - current.quartZ;
      if (x < 0 || x >= 4 || z < 0 || z >= 4 || y < 0 || y >= current.quartHeight) {
         return -1;
      }

      return (y * 4 + x) * 4 + z;
   }

   /** The GPU's target point for a cell of this chunk, or null (the CPU samples). Runs the chunk at its first sample. */
   public static Climate.TargetPoint sample(Object sampler, int quartX, int quartY, int quartZ) {
      Current current = CURRENT.get();
      if (current.inStep) {
         BiomesAhead.sawSampler(sampler);
      }

      if (!current.active) {
         return null;
      }

      int cell = cell(current, quartX, quartY, quartZ);
      if (cell < 0) {
         CPU_SAMPLES.increment();
         return null;
      }

      if (!current.ran && !run(current, sampler)) {
         return null;
      }

      if (current.check) {
         // The CPU samples; the wrap compares (compareSample).
         return null;
      }

      SAMPLES.increment();
      long[] c = current.climate;
      int at = cell * 6;
      Climate.TargetPoint target = new Climate.TargetPoint(c[at], c[at + 1], c[at + 2], c[at + 3], c[at + 4], c[at + 5]);
      current.made[cell] = target;
      return target;
   }

   /** CHECK chunks: the CPU's sample of a cell against the GPU's (the CPU's is used). */
   public static boolean checking() {
      Current current = CURRENT.get();
      return current.active && current.ran && current.check;
   }

   public static void compareSample(int quartX, int quartY, int quartZ, Climate.TargetPoint game) {
      Current current = CURRENT.get();
      int cell = cell(current, quartX, quartY, quartZ);
      if (cell < 0) {
         return;
      }

      long[] c = current.climate;
      int at = cell * 6;
      if (c[at] != game.temperature() || c[at + 1] != game.humidity() || c[at + 2] != game.continentalness() || c[at + 3] != game.erosion()
         || c[at + 4] != game.depth() || c[at + 5] != game.weirdness()) {
         difference(current, String.format(Locale.ROOT, "climate at quart (%d, %d, %d): game %s, gpu [%d, %d, %d, %d, %d, %d]", quartX, quartY, quartZ, game, c[at],
            c[at + 1], c[at + 2], c[at + 3], c[at + 4], c[at + 5]));
      }

      current.made[cell] = game;
   }

   /** Around select(): the cell being chosen, when its target is the one made for it. */
   public static void selecting(int quartX, int quartY, int quartZ, Climate.TargetPoint target) {
      Current current = CURRENT.get();
      current.selecting = -1;
      if (!current.active || !current.ran) {
         return;
      }

      int cell = cell(current, quartX, quartY, quartZ);
      if (cell >= 0 && current.made[cell] == target) {
         current.selecting = cell;
      }
   }

   public static void selected() {
      CURRENT.get().selecting = -1;
   }

   /** The GPU's search result for the cell being chosen, or null (the CPU searches). */
   public static Object search(ResourceLocation domain, Climate.TargetPoint target) {
      Current current = CURRENT.get();
      int cell = current.selecting;
      if (cell < 0 || current.made[cell] != target || current.check) {
         if (current.active && current.ran && !current.check) {
            CPU_SEARCHES.increment();
         }

         return null;
      }

      Program p = current.program;
      if (current.tied[cell] != 0) {
         TIES.increment();
         return null;
      }

      if (p.gpu.multi()) {
         // C1: the GPU searched this cell's column's predicted provider; its answer only for that domain.
         int k = p.table.providerId.equals(domain) ? 0 : p.table.extraIds.indexOf(domain) + 1;
         if (k < 0 || (k == 0) != p.table.providerId.equals(domain) || k != current.provider[cell & 15]) {
            MISPREDICTED.increment();
            return null;
         }

         int chosen = current.chosen[cell];
         if (chosen == p.table.deferredNumber) {
            CPU_SEARCHES.increment();
            return null;
         }

         SEARCHES.increment();
         if (k > 0) {
            EXTRA_SEARCHES.increment();
         }

         @SuppressWarnings("unchecked")
         Holder<Biome> found = (Holder<Biome>)p.table.biomes.get(chosen);
         long[] t = current.projected;
         int at = cell * 6;
         Climate.TargetPoint projected = new Climate.TargetPoint(t[at], t[at + 1], t[at + 2], t[at + 3], t[at + 4], t[at + 5]);
         return newResult(domain, found, k == 0 ? p.table.candidates : p.table.extraCandidates.get(k - 1), projected);
      }

      if (!p.table.providerId.equals(domain)) {
         // C1: another provider's cell: its own search from the GPU (a tie or TerraBlender's deferred placeholder: the CPU's).
         int k = p.table.extraIds.indexOf(domain);
         int n = 16 * current.quartHeight;
         int chosen = k < 0 || k >= current.extras ? -1 : current.extraChosen[k * n + cell];
         if (chosen < 0 || chosen == p.table.deferredNumber) {
            if (k >= 0 && k < current.extras && chosen < 0) {
               TIES.increment();
            } else {
               CPU_SEARCHES.increment();
            }

            return null;
         }

         SEARCHES.increment();
         EXTRA_SEARCHES.increment();
         @SuppressWarnings("unchecked")
         Holder<Biome> other = (Holder<Biome>)p.table.biomes.get(chosen);
         long[] c = current.climate;
         int at = cell * 6;
         Climate.TargetPoint projected = new Climate.TargetPoint(c[at], c[at + 1], current.extraCC[k * n + cell], c[at + 3], c[at + 4], c[at + 5]);
         return newResult(domain, other, p.table.extraCandidates.get(k), projected);
      }

      SEARCHES.increment();
      @SuppressWarnings("unchecked")
      Holder<Biome> biome = (Holder<Biome>)p.table.biomes.get(current.chosen[cell]);
      long[] t = current.projected;
      int at = cell * 6;
      Climate.TargetPoint projected = new Climate.TargetPoint(t[at], t[at + 1], t[at + 2], t[at + 3], t[at + 4], t[at + 5]);
      return newResult(domain, biome, p.table.candidates, projected);
   }

   /** C2: in a vanilla world, whether the GPU biomes are in use (the hook on MultiNoiseBiomeSource.getNoiseBiome asks). */
   public static boolean vanillaReady() {
      Program p = program;
      return p != null && !failed && p.vanilla();
   }

   /** C2: vanilla's search (ParameterList.findValue) from the GPU for the cell being chosen, or null (the CPU searches). */
   @SuppressWarnings("unchecked")
   public static Holder<Biome> searchVanilla(Climate.TargetPoint target) {
      Current current = CURRENT.get();
      int cell = current.selecting;
      if (cell < 0 || current.made[cell] != target || current.check) {
         if (current.active && current.ran && !current.check) {
            CPU_SEARCHES.increment();
         }

         return null;
      }

      if (current.tied[cell] != 0) {
         TIES.increment();
         return null;
      }

      SEARCHES.increment();
      return (Holder<Biome>)current.program.table.biomes.get(current.chosen[cell]);
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   private static Object newResult(ResourceLocation domain, Holder<Biome> biome, Climate.ParameterList<?> candidates, Climate.TargetPoint projected) {
      return new etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult(domain, biome, biome, false, (Climate.ParameterList)candidates,
         projected);
   }

   /** CHECK chunks: the CPU's search against the GPU's. */
   public static void compareSearch(ResourceLocation domain, Climate.TargetPoint target, Climate.TargetPoint projected, Holder<Biome> biome, boolean usedFallback) {
      Current current = CURRENT.get();
      int cell = current.selecting;
      // C1: only the GPU table's own domain (another provider's cell is searched on the CPU, the GPU's answer never used);
      // vanilla (one table) passes null.
      if (cell < 0 || current.made[cell] != target || usedFallback) {
         return;
      }

      BiomeTable table = current.program.table;
      if (domain != null && current.program.gpu.multi()) {
         int k = table.providerId.equals(domain) ? 0 : table.extraIds.indexOf(domain) + 1;
         if (k < 0 || (k == 0) != table.providerId.equals(domain) || k != current.provider[cell & 15]) {
            return;
         }
      } else if (domain != null && !table.providerId.equals(domain)) {
         // C1: another provider's cell against the GPU's search of its table.
         int k = table.extraIds.indexOf(domain);
         int n = 16 * current.quartHeight;
         if (k < 0 || k >= current.extras) {
            return;
         }

         int chosen = current.extraChosen[k * n + cell];
         long[] c = current.climate;
         int at = cell * 6;
         if (chosen < 0) {
            TIES.increment();
         } else if (c[at] != projected.temperature() || c[at + 1] != projected.humidity() || current.extraCC[k * n + cell] != projected.continentalness()
            || c[at + 3] != projected.erosion() || c[at + 4] != projected.depth() || c[at + 5] != projected.weirdness() || !table.biomes.get(chosen).equals(biome)) {
            difference(current, String.format(Locale.ROOT, "search in %s of %s: game %s %s, gpu %s (cc %d)", domain, target,
               biome.unwrapKey().map(Object::toString).orElse("?"), projected, table.biomes.get(chosen), current.extraCC[k * n + cell]));
         }

         return;
      }

      long[] t = current.projected;
      int at = cell * 6;
      boolean same = t[at] == projected.temperature() && t[at + 1] == projected.humidity() && t[at + 2] == projected.continentalness()
         && t[at + 3] == projected.erosion() && t[at + 4] == projected.depth() && t[at + 5] == projected.weirdness();
      Object gpuBiome = current.program.table.biomes.get(current.chosen[cell]);
      if (current.tied[cell] != 0) {
         TIES.increment();
      } else if (!same || !gpuBiome.equals(biome)) {
         difference(current, String.format(Locale.ROOT, "search of %s: game %s %s, gpu %s", target, biome.unwrapKey().map(Object::toString).orElse("?"), projected,
            gpuBiome));
      }
   }

   private static void difference(Current current, String what) {
      DIFFERENT.increment();
      if (LOGGED.incrementAndGet() <= 12) {
         WorldgenAccelerator.LOG.warn("WGA GPU biomes check: chunk {}: {}", current.chunk == null ? "?" : current.chunk.getPos(), what);
      }
   }

   private static boolean run(Current current, Object sampler) {
      current.ran = true;
      Program p = current.program;
      try {
         long start = System.nanoTime();
         BiomeInputs inputs = current.inputs;
         inputs.read(current.chunk, sampler, p.cellSamplers);
         BiomeGpu.Job job = current.job;
         int n = 16 * current.quartHeight;
         job(job, inputs, current.quartX, current.quartY, current.quartZ, current.quartHeight);
         if (current.climate.length < 6 * n) {
            current.climate = new long[6 * n];
            current.projected = new long[6 * n];
            current.chosen = new int[n];
            current.tied = new int[n];
            current.decorated = new int[n];
            current.made = new Climate.TargetPoint[n];
         }

         java.util.Arrays.fill(current.made, 0, n, null);
         if (p.gpu.multi() && !regions(job, current.provider, p.table, current.quartX, current.quartZ)) {
            // C1: no provider per column yet (no FreeTerraForged context seen): this chunk is the CPU's.
            current.active = false;
            return false;
         }

         current.hasDecorated = p.gpu.decorates() && riverFlags(job, current.quartX, current.quartZ);
         current.extras = p.gpu.extras();
         if (current.extraCC.length < current.extras * n) {
            current.extraCC = new long[current.extras * n];
            current.extraChosen = new int[current.extras * n];
            current.extraDecorated = new int[current.extras * n];
         }

         current.hasExtraDecorated = current.hasDecorated && p.gpu.decoratesExtras();

         long gpuStart = System.nanoTime();
         p.gpu.runBatch(List.of(job), List.of(current.climate), List.of(current.projected), List.of(current.chosen), List.of(current.tied),
            current.hasDecorated ? List.of(current.decorated) : null, current.extras > 0 ? List.of(current.extraCC) : null,
            current.extras > 0 ? List.of(current.extraChosen) : null, current.hasExtraDecorated ? List.of(current.extraDecorated) : null);
         long end = System.nanoTime();
         INPUT_NANOS.add(gpuStart - start);
         GPU_NANOS.add(end - gpuStart);
         current.check = ELIGIBLE.getAndIncrement() % CHECK_EVERY == 0;
         CHUNKS.increment();
         if (current.check) {
            CHECKED.increment();
         }

         report();
         return true;
      } catch (Throwable failure) {
         failed = true;
         current.active = false;
         com.wgaccel.WgaChat.warnOff("WGA GPU biomes off (the CPU from now on): {}", failure.toString());
         return false;
      }
   }

   // G5.4: the decoration from the GPU.
   private static volatile Object decorContext;
   private static final LongAdder DECOR_GPU = new LongAdder();
   private static final LongAdder DECOR_CPU = new LongAdder();
   private static final LongAdder DECOR_SAME = new LongAdder();
   private static final LongAdder DECOR_DIFFERENT = new LongAdder();
   private static final AtomicInteger DECOR_LOGGED = new AtomicInteger();

   /** From the decoration call: FreeTerraForged's GeneratorContext (its tile lookup gives the river flags). */
   public static void sawContext(Object context) {
      if (decorContext == null && context != null) {
         decorContext = context;
      }
   }

   /** The chunk's 16 quart columns' river flags from FreeTerraForged's tile (as its terrain roles read them); false without a context yet. */
   static boolean riverFlags(BiomeGpu.Job job, int quartX, int quartZ) {
      Object context = decorContext;
      if (context == null) {
         return false;
      }

      etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup lookup = ((etcodehome.freeterraforged.world.worldgen.GeneratorContext)context).lookup;
      etcodehome.freeterraforged.concurrent.Resource<etcodehome.freeterraforged.world.worldgen.cell.Cell> resource = etcodehome.freeterraforged.world.worldgen.cell.Cell.getResource();
      try {
         riverCells(lookup, resource, job, quartX, quartZ);
      } catch (IllegalStateException closed) {
         // A context whose cache was closed (one made for a moment, as for a spawn search): the next decoration call
         // gives a live one; this chunk is the CPU's.
         if (decorContext == context) {
            decorContext = null;
         }

         throw closed;
      } finally {
         resource.close();
      }

      return true;
   }

   /**
    * C1: the chunk's 16 quart columns' providers as FreeTerraForged chooses them (the region cell of the column by
    * WorldLookup.applyBiomeRegion, then the plan's spatial resolver), into the job (0 for an unknown domain) and known
    * (-1 there: the CPU's); false without a context yet.
    */
   static boolean regions(BiomeGpu.Job job, int[] known, BiomeTable table, int quartX, int quartZ) {
      Object context = decorContext;
      if (context == null || table.spatialResolver == null) {
         return false;
      }

      etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup lookup = ((etcodehome.freeterraforged.world.worldgen.GeneratorContext)context).lookup;
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SpatialResolver resolver =
         (etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SpatialResolver)table.spatialResolver;
      etcodehome.freeterraforged.concurrent.Resource<etcodehome.freeterraforged.world.worldgen.cell.Cell> resource = etcodehome.freeterraforged.world.worldgen.cell.Cell.getResource();
      try {
         for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
               etcodehome.freeterraforged.world.worldgen.cell.Cell cell = resource.get().reset();
               lookup.applyBiomeRegion(cell, (quartX + qx) << 2, (quartZ + qz) << 2);
               Object domain = resolver.resolve(cell.biomeRegionX, cell.biomeRegionZ).domain();
               int k = table.providerId.equals(domain) ? 0 : table.extraIds.indexOf(domain) + 1;
               known[qx * 4 + qz] = k > 0 || table.providerId.equals(domain) ? k : -1;
               job.provider[qx * 4 + qz] = Math.max(0, known[qx * 4 + qz]);
            }
         }
      } catch (IllegalStateException closed) {
         if (decorContext == context) {
            decorContext = null;
         }

         throw closed;
      } finally {
         resource.close();
      }

      return true;
   }

   private static void riverCells(etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup lookup,
      etcodehome.freeterraforged.concurrent.Resource<etcodehome.freeterraforged.world.worldgen.cell.Cell> resource, BiomeGpu.Job job, int quartX, int quartZ) {
      for (int qx = 0; qx < 4; qx++) {
         for (int qz = 0; qz < 4; qz++) {
            etcodehome.freeterraforged.world.worldgen.cell.Cell cell = resource.get().reset();
            lookup.applyCell(cell, (quartX + qx) << 2, (quartZ + qz) << 2, false);
            job.rivers[qx * 4 + qz] = cell.terrain.isRiver() ? 1 : 0;
         }
      }
   }

   private static int decorationCell(Climate.TargetPoint target) {
      Current current = CURRENT.get();
      int cell = current.selecting;
      // The decoration is called inside select() (the cell marked by selecting()) with the search's projected target.
      if (cell < 0 || !current.active || !current.ran || !current.hasDecorated) {
         return -1;
      }

      return cell;
   }

   /**
    * The GPU's decoration by FreeTerraForged's stage for the cell being chosen, as a biome number; -1 when the CPU
    * decorates (a tie, a region's stage choice, a selection the GPU did not make: another biome, a fallback, an unknown
    * domain); -2 when there is no GPU decoration for this cell at all.
    */
   private static int decoratedNumber(Current current, Climate.TargetPoint target, etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult selection) {
      int cell = decorationCell(target);
      if (cell < 0) {
         return -2;
      }

      BiomeTable table = current.program.table;
      int n = 16 * current.quartHeight;
      int value;
      int searched;
      if (current.program.gpu.multi()) {
         Object domain = selection.domain();
         int k = table.providerId.equals(domain) ? 0 : table.extraIds.indexOf(domain) + 1;
         if (k < 0 || (k == 0) != table.providerId.equals(domain) || k != current.provider[cell & 15]) {
            return -1;
         }

         value = current.decorated[cell];
         searched = current.tied[cell] != 0 ? -1 : current.chosen[cell];
      } else if (table.providerId.equals(selection.domain())) {
         value = current.decorated[cell];
         searched = current.tied[cell] != 0 ? -1 : current.chosen[cell];
      } else {
         int k = table.extraIds.indexOf(selection.domain());
         if (k < 0 || k >= current.extras || !current.hasExtraDecorated) {
            return -2;
         }

         value = current.extraDecorated[k * n + cell];
         searched = current.extraChosen[k * n + cell];
      }

      // The GPU decorated its own search's result: only for a selection that is exactly that (C1: a deferred placeholder's
      // fallback search, or a CPU search, may differ).
      if (selection.usedFallback() || searched < 0 || !table.biomes.get(searched).equals(selection.biome()) || selection.biome() != selection.baseBiome()) {
         return -1;
      }

      return value;
   }

   /**
    * The cell's final biome with the GPU's decoration (gpuBiomeDecoration ON, not a checked chunk): FreeTerraForged's
    * stage from the GPU, then the plan's later stages (C1: Lithostitched's injectors) on the CPU as SelectionDecoration.apply
    * runs them; null: the CPU decorates.
    */
   @SuppressWarnings("unchecked")
   public static Holder<Biome> decorated(etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SelectionDecoration decoration,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult selection,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SpatialResult spatial, Climate.TargetPoint target, int quartX, int quartY, int quartZ,
      Climate.Sampler sampler, etcodehome.freeterraforged.world.worldgen.GeneratorContext context) {
      if (Config.GPU_BIOME_DECORATION.get() != Config.GpuFilterMode.ON) {
         return null;
      }

      Current current = CURRENT.get();
      if (current.check) {
         return null;
      }

      int value = decoratedNumber(current, target, selection);
      if (value < 0) {
         if (value == -1) {
            DECOR_CPU.increment();
         }

         return null;
      }

      DECOR_GPU.increment();
      return later(decoration, selection, (Holder<Biome>)current.program.table.biomes.get(value), spatial, target, quartX, quartY, quartZ, sampler, context);
   }

   /** The plan's stages after the first (FreeTerraForged's), from the first's result, as SelectionDecoration.apply runs them. */
   private static Holder<Biome> later(etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SelectionDecoration decoration,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult selection, Holder<Biome> first,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SpatialResult spatial, Climate.TargetPoint target, int quartX, int quartY, int quartZ,
      Climate.Sampler sampler, etcodehome.freeterraforged.world.worldgen.GeneratorContext context) {
      List<etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SelectionDecoratorStage> stages = decoration.stages();
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult now = selection.withBiome(first);
      for (int i = 1; i < stages.size(); i++) {
         Holder<Biome> next = stages.get(i).decorator().apply(now, spatial, target, quartX, quartY, quartZ, sampler, context);
         if (next == null) {
            throw new IllegalStateException("Selection decorator " + stages.get(i).id() + " returned null");
         }

         now = now.withBiome(next);
      }

      return now.biome();
   }

   /** Whether the CPU's decoration of this cell is to be compared with the GPU's (CHECK, or a checked chunk). */
   public static boolean decorationChecking() {
      Config.GpuFilterMode mode = Config.GPU_BIOME_DECORATION.get();
      return mode == Config.GpuFilterMode.CHECK || mode == Config.GpuFilterMode.ON && CURRENT.get().check;
   }

   public static void compareDecoration(etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SelectionDecoration decoration,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.ProviderResult selection,
      etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans.SpatialResult spatial, Climate.TargetPoint target, int quartX, int quartY, int quartZ,
      Climate.Sampler sampler, etcodehome.freeterraforged.world.worldgen.GeneratorContext context, Holder<Biome> game) {
      Current current = CURRENT.get();
      int value = decoratedNumber(current, target, selection);
      if (value == -2) {
         return;
      }

      if (value < 0) {
         DECOR_CPU.increment();
         return;
      }

      @SuppressWarnings("unchecked")
      Holder<Biome> gpu = later(decoration, selection, (Holder<Biome>)current.program.table.biomes.get(value), spatial, target, quartX, quartY, quartZ, sampler,
         context);
      if (gpu == game || gpu.equals(game)) {
         DECOR_SAME.increment();
      } else {
         DECOR_DIFFERENT.increment();
         if (DECOR_LOGGED.incrementAndGet() <= 12) {
            WorldgenAccelerator.LOG.warn("WGA GPU biome decoration check: chunk {} target {} in {}: game {}, gpu {} (searched {})", current.chunk == null ? "?"
               : current.chunk.getPos(), target, selection.domain(), game.unwrapKey().map(Object::toString).orElse("?"), gpu, selection.biome());
         }
      }
   }

   /** The GPU job of a chunk's cells (every quart y of the world over its 4 x 4 quart columns) from its inputs. */
   static void job(BiomeGpu.Job job, BiomeInputs inputs, int quartX, int quartY, int quartZ, int quartHeight) {
      int n = 16 * quartHeight;
      if (job.at.length < 3 * n) {
         job.at = new int[3 * n];
      }

      for (int y = 0; y < quartHeight; y++) {
         for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
               int cell = (y * 4 + x) * 4 + z;
               job.at[cell * 3] = quartX + x;
               job.at[cell * 3 + 1] = quartY + y;
               job.at[cell * 3 + 2] = quartZ + z;
            }
         }
      }

      job.count = n;
      job.cells = inputs.cells;
      job.chunkX = inputs.chunkX;
      job.chunkZ = inputs.chunkZ;
      job.banding = inputs.banding;
      job.seed = inputs.seed;
      job.coverage = inputs.coverage;
      job.size = inputs.size;
      job.verticalSize = inputs.verticalSize;
      job.surfaceDepth = inputs.surfaceDepth;
      job.endDepth = inputs.endDepth;
      job.add21875 = inputs.add21875;
      job.add03125 = inputs.add03125;
      System.arraycopy(inputs.minimumSurface, 0, job.minimumSurface, 0, 16);
   }

   private static void report() {
      long done = CHUNKS.sum();
      int next = NEXT_REPORT.get();
      if (done < next || !NEXT_REPORT.compareAndSet(next, next < 1024 ? next * 2 : next + 2048)) {
         return;
      }

      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU biomes: %,d chunks (%,d checked: %s); from the GPU %,d samples and %,d searches (%,d in other providers' tables, %,d other than the column's predicted provider); on the CPU %,d samples, %,d searches, %,d ties; per chunk: CPU inputs %.2f ms, GPU %.2f ms",
         done, CHECKED.sum(), DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER", SAMPLES.sum(), SEARCHES.sum(), EXTRA_SEARCHES.sum(), MISPREDICTED.sum(), CPU_SAMPLES.sum(), CPU_SEARCHES.sum(),
         TIES.sum(), INPUT_NANOS.sum() / 1.0e6 / Math.max(1, done), GPU_NANOS.sum() / 1.0e6 / Math.max(1, done))
         + (Config.GPU_BIOME_DECORATION.get() == Config.GpuFilterMode.OFF ? "" : String.format(Locale.ROOT,
            "; decoration: %,d from the GPU, %,d left to the CPU, compared %,d %s", DECOR_GPU.sum(), DECOR_CPU.sum(), DECOR_SAME.sum() + DECOR_DIFFERENT.sum(),
            DECOR_DIFFERENT.sum() == 0 ? "IDENTICAL" : DECOR_DIFFERENT.sum() + " DIFFER"))
         + (BiomesAhead.active() == null ? "" : BiomesAhead.active().summary()));
   }
}
