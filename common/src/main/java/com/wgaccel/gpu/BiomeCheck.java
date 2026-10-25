package com.wgaccel.gpu;

import com.mojang.datafixers.util.Pair;
import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gen.BiomeTap;
import com.wgaccel.platform.Platform;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G5.2a (config gpuBiomes CHECK): the biome step's climate and climate-table search on the GPU, checked
 * against the game's in the first {@link #CHUNKS} overworld chunks ({@link BiomeTap}). Climate: the router's six
 * climate functions (temperature, vegetation, continents, erosion, depth, ridges) through {@link DensityCodegen} at the
 * cell's block position (quart x 4), the CellSampler values from the chunk's quart columns, each quantized as
 * Climate.target does ((long)((float)v * 10000f)). Search: FreeTerraForged's OceanClimateProjection on the
 * continentalness, then every one of the table's points by Minecraft's distance (Climate.Parameter.distance squared and
 * summed with the offset as a seventh parameter; the R-tree finds the same smallest distance); a smallest distance
 * shared by points of different biomes is a tie (the CPU's answer then depends on the thread's last result: left to
 * the CPU and counted). Logged as "WGA GPU biome check"; the source as logs/wgaccel-biome-check.cu. Changes nothing.
 */
public final class BiomeCheck {
   private static final int CHUNKS = 16;
   private static final AtomicBoolean STARTED = new AtomicBoolean();

   private BiomeCheck() {
   }

   /** At the overworld's first biome step: starts the check on its own thread. */
   public static void maybeStart(RandomState random) {
      if (!SurfaceCheck.isOverworld(random) || STARTED.get() || Config.GPU_BIOMES.get() != Config.GpuFilterMode.CHECK || Config.GPU_MODE.get() == Config.GpuMode.OFF
         || !Lwjgl.PRESENT || STARTED.getAndSet(true)) {
         return;
      }

      Thread thread = new Thread(() -> {
         try {
            run(random);
         } catch (DensityCodegen.Unsupported unsupported) {
            com.wgaccel.WgaChat.warnOff("WGA GPU biome check off: {} has no GPU version", unsupported.kind);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU biome check failed: {}", failure.toString());
         }
      }, "WGA GPU biome check");
      thread.setDaemon(true);
      thread.start();
   }

   /** A new world may check again. */
   public static void reset() {
      STARTED.set(false);
   }

   private static void run(RandomState random) throws Exception {
      NoiseRouter router = random.router();
      DensityCodegen codegen = new DensityCodegen();
      String[] climate = {codegen.function(DensityCodegen.unwrap(router.temperature())), codegen.function(DensityCodegen.unwrap(router.vegetation())),
         codegen.function(DensityCodegen.unwrap(router.continents())), codegen.function(DensityCodegen.unwrap(router.erosion())),
         codegen.function(DensityCodegen.unwrap(router.depth())), codegen.function(DensityCodegen.unwrap(router.ridges()))};

      BiomeTable plan = BiomeTable.of(random);
      List<? extends Pair<Climate.ParameterPoint, ?>> values = plan.values;
      long sourceMin = plan.sourceMin;
      long sourceMax = plan.sourceMax;
      long targetMax = plan.targetMax;
      String source = codegen.source(List.of()) + BiomeKernels.source(climate);
      try {
         Files.writeString(Platform.logs().resolve("wgaccel-biome-check.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      long compileStart = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "biome_check.cu")) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU biome check: climate functions of %d CellSampler inputs, a table of %,d points (%d biomes), ocean projection to %d; compiled in %.0f ms; waiting for %d chunks",
            codegen.cellSamplers().size(), values.size(), plan.biomeCount, targetMax, (System.nanoTime() - compileStart) / 1.0e6, CHUNKS));
         double[] noiseData = codegen.noiseData();
         int[] permutations = codegen.permutations();
         long nd = gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData));
         long np = gpu.upload(MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations));
         long boundsDevice = gpu.upload(MemoryUtil.memAllocInt(plan.nodeBounds.length).put(0, plan.nodeBounds));
         long firstDevice = gpu.upload(MemoryUtil.memAllocInt(plan.nodeFirst.length).put(0, plan.nodeFirst));
         long countDevice = gpu.upload(MemoryUtil.memAllocInt(plan.nodeCount.length).put(0, plan.nodeCount));
         long nodeBiomeDevice = gpu.upload(MemoryUtil.memAllocInt(plan.nodeBiome.length).put(0, plan.nodeBiome));
         LinkedBlockingQueue<BiomeTap.Tap> taps = new LinkedBlockingQueue<>();
         BiomeTap.enable(random, codegen.cellSamplers(), CHUNKS, taps::add);
         long samples = 0;
         long climateDiffer = 0;
         long searches = 0;
         long targetDiffer = 0;
         long biomeDiffer = 0;
         long ties = 0;
         long fallbacks = 0;
         String firstClimate = null;
         String firstSearch = null;
         int chunks = 0;
         while (chunks < CHUNKS) {
            BiomeTap.Tap tap = taps.poll(30, TimeUnit.MINUTES);
            if (tap == null) {
               break;
            }

            long start = System.nanoTime();
            // Climate per sample.
            int n = tap.samples;
            long climateOut = gpu.allocate(48L * Math.max(1, n));
            if (n > 0) gpu.run("biomeClimate", (n + 255) / 256, 1, MemoryUtil.memAllocInt(Math.max(1, 3 * n)).put(0, tap.sampleAt, 0, 3 * n), n,
               MemoryUtil.memAllocDouble(tap.cells.length).put(0, tap.cells), tap.chunkX, tap.chunkZ, nd, np, tap.banding ? 1 : 0,
               Double.longBitsToDouble(tap.seed), tap.coverage, tap.size, tap.verticalSize, tap.surfaceDepth, tap.endDepth, tap.add21875, tap.add03125,
               MemoryUtil.memAllocInt(16).put(0, tap.minimumSurface), climateOut);
            DoubleBuffer climateBits = MemoryUtil.memAllocDouble(Math.max(1, 6 * n));
            gpu.download(climateOut, climateBits);
            int chunkClimate = 0;
            for (int i = 0; i < 6 * n; i++) {
               long gpuValue = Double.doubleToRawLongBits(climateBits.get(i));
               if (gpuValue != tap.sampled[i]) {
                  chunkClimate++;
                  if (firstClimate == null) {
                     int s = i / 6;
                     firstClimate = String.format(Locale.ROOT, "cell (%d, %d, %d) %s: game %d, gpu %d", tap.sampleAt[s * 3], tap.sampleAt[s * 3 + 1], tap.sampleAt[s * 3 + 2],
                        new String[] {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"}[i % 6], tap.sampled[i], gpuValue);
                  }
               }
            }

            MemoryUtil.memFree(climateBits);
            // Search per searched target.
            int m = tap.searches;
            DoubleBuffer in = MemoryUtil.memAllocDouble(Math.max(1, 6 * m));
            for (int i = 0; i < 6 * m; i++) {
               in.put(i, Double.longBitsToDouble(tap.searchIn[i]));
            }

            long targetOut = gpu.allocate(48L * Math.max(1, m));
            long chosen = gpu.allocate(4L * Math.max(1, m));
            long tied = gpu.allocate(4L * Math.max(1, m));
            if (m > 0) gpu.run("biomeTreeSearch", (m + 255) / 256, 1, in, m, boundsDevice, firstDevice, countDevice, nodeBiomeDevice, (int)sourceMin, (int)sourceMax, (int)targetMax, targetOut,
               chosen, tied);
            DoubleBuffer targetBits = MemoryUtil.memAllocDouble(Math.max(1, 6 * m));
            IntBuffer chosenIndex = MemoryUtil.memAllocInt(Math.max(1, m));
            IntBuffer tiedFlag = MemoryUtil.memAllocInt(Math.max(1, m));
            gpu.download(targetOut, targetBits);
            gpu.download(chosen, chosenIndex);
            gpu.download(tied, tiedFlag);
            int chunkTargets = 0;
            int chunkBiomes = 0;
            int chunkTies = 0;
            for (int i = 0; i < m; i++) {
               if (tap.fallback[i]) {
                  fallbacks++;
                  continue;
               }

               boolean targetSame = true;
               for (int k = 0; k < 6; k++) {
                  targetSame &= Double.doubleToRawLongBits(targetBits.get(i * 6 + k)) == tap.searchOut[i * 6 + k];
               }

               if (!targetSame) {
                  chunkTargets++;
               }

               if (tiedFlag.get(i) != 0) {
                  chunkTies++;
                  continue;
               }

               Object gpuBiome = plan.biomes.get(chosenIndex.get(i));
               if (gpuBiome != tap.searchBiome[i] && !gpuBiome.equals(tap.searchBiome[i])) {
                  chunkBiomes++;
                  if (firstSearch == null) {
                     firstSearch = String.format(Locale.ROOT, "target %s: game %s, gpu %s", java.util.Arrays.toString(java.util.Arrays.copyOfRange(tap.searchIn, i * 6, i * 6 + 6)),
                        name(tap.searchBiome[i]), name(gpuBiome));
                  }
               }
            }

            MemoryUtil.memFree(in);
            MemoryUtil.memFree(targetBits);
            MemoryUtil.memFree(chosenIndex);
            MemoryUtil.memFree(tiedFlag);
            gpu.free(climateOut);
            gpu.free(targetOut);
            gpu.free(chosen);
            gpu.free(tied);
            samples += n;
            climateDiffer += chunkClimate;
            searches += m;
            targetDiffer += chunkTargets;
            biomeDiffer += chunkBiomes;
            ties += chunkTies;
            if (chunks < 4 || chunkClimate + chunkTargets + chunkBiomes > 0) {
               WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
                  "WGA GPU biome check: chunk at (%d, %d): %,d climate samples %s; %,d searches: projected target %s, biome %s, %d ties left to the CPU; GPU %.2f ms",
                  tap.chunkX, tap.chunkZ, n, chunkClimate == 0 ? "IDENTICAL" : chunkClimate + " values DIFFER", m, chunkTargets == 0 ? "IDENTICAL" : chunkTargets + " DIFFER",
                  chunkBiomes == 0 ? "IDENTICAL" : chunkBiomes + " DIFFER", chunkTies, (System.nanoTime() - start) / 1.0e6));
            }

            chunks++;
         }

         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU biome check: %d chunks; %,d climate samples: %s; %,d searches: projected target %s, biome %s; %,d ties left to the CPU, %,d fallback searches skipped",
            chunks, samples, climateDiffer == 0 ? "IDENTICAL" : climateDiffer + " values DIFFER, first " + firstClimate, searches,
            targetDiffer == 0 ? "IDENTICAL" : targetDiffer + " DIFFER", biomeDiffer == 0 ? "IDENTICAL" : biomeDiffer + " DIFFER, first " + firstSearch, ties, fallbacks));
      }
   }

   private static String name(Object biome) {
      return biome instanceof net.minecraft.core.Holder<?> holder ? holder.unwrapKey().map(key -> key.location().toString()).orElse("direct") : String.valueOf(biome);
   }
}
