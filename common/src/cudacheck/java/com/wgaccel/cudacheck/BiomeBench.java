package com.wgaccel.cudacheck;

import com.wgaccel.gpu.BiomeDump;
import com.wgaccel.gpu.BiomeGpu;
import com.wgaccel.gpu.BiomeKernels;
import com.wgaccel.gpu.Gpu;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The biome kernels on chunks saved from the game (logs/wgaccel-biomes.cu, wgaccel-biome-table.bin,
 * wgaccel-biome-jobs.bin): the climate functions from the saved source, the kernels from today's {@link BiomeKernels}.
 * Checks every cell's chosen biome against a brute-force search on the CPU (and the game GPU's saved answers), then
 * times the chunks on an idle card. Run: gradlew.bat cudaBiomeBench -Plogs=<the instance's logs folder>.
 */
public final class BiomeBench {
   public static void main(String[] args) throws Exception {
      Path logs = Path.of(args[0]);
      String saved = Files.readString(logs.resolve("wgaccel-biomes.cu"), StandardCharsets.UTF_8);
      int split = saved.indexOf("// Climate.quantizeCoord of the function's value");
      if (split < 0) {
         throw new IllegalStateException("no biome kernels in the saved source");
      }

      String climateBody = saved.substring(split);
      Matcher names = Pattern.compile("bq_quantize\\((\\w+)\\(c\\)\\)").matcher(climateBody);
      String[] climate = new String[6];
      for (int i = 0; i < 6; i++) {
         if (!names.find()) {
            throw new IllegalStateException("fewer than six climate functions in the saved source");
         }

         climate[i] = names.group(1);
      }

      // The cache slots the saved code's remembered subtrees use (dc_ccN / dc_cmN: the highest N + 1; 0 without).
      int slots = 0;
      Matcher memo = Pattern.compile("double dc_c[cm](\\d+)\\(").matcher(saved);
      while (memo.find()) {
         slots = Math.max(slots, Integer.parseInt(memo.group(1)) + 1);
      }

      System.out.printf(Locale.ROOT, "cache slots in the saved code: %d%n", slots);
      String source = saved.substring(0, split) + BiomeKernels.source(climate, slots).trim();
      BiomeDump dump = BiomeDump.read(logs.resolve("wgaccel-biome-table.bin"), logs.resolve("wgaccel-biome-jobs.bin"));
      System.out.printf(Locale.ROOT, "biome bench: %d chunks, %d points, %d tree nodes%n", dump.jobs.size(), dump.biomeIds.length, dump.nodeCount.length);
      long context = Gpu.createContext();
      long compile = System.nanoTime();
      BiomeGpu gpu = new BiomeGpu(context, source, dump.noise, dump.permutations, dump.nodeBounds, dump.nodeFirst, dump.nodeCount, dump.nodeBiome,
         dump.sourceMin, dump.sourceMax, dump.targetMax);
      System.out.printf(Locale.ROOT, "compiled in %.0f ms%n", (System.nanoTime() - compile) / 1.0e6);
      long cells = 0;
      long wrong = 0;
      long changed = 0;
      long ties = 0;
      List<long[]> keep = new ArrayList<>();
      for (int j = 0; j < dump.jobs.size(); j++) {
         BiomeGpu.Job job = dump.jobs.get(j);
         int n = job.count;
         long[] climateOut = new long[6 * n];
         long[] projected = new long[6 * n];
         int[] chosen = new int[n];
         int[] tied = new int[n];
         gpu.run(job, climateOut, projected, chosen, tied);
         keep.add(projected);
         for (int i = 0; i < n; i++) {
            cells++;
            int brute = brute(dump, projected, i);
            if (tied[i] != 0) {
               ties++;
               continue;
            }

            if (brute == -1 || brute != chosen[i]) {
               wrong++;
               if (wrong <= 5) {
                  System.out.printf(Locale.ROOT, "  chunk %d cell %d: tree %d, brute %d%n", j, i, chosen[i], brute);
               }
            }

            if (dump.tied.get(j)[i] == 0 && dump.chosen.get(j)[i] != chosen[i]) {
               changed++;
            }
         }
      }

      System.out.printf(Locale.ROOT, "check: %,d cells, %,d ties, %,d differ from brute force (%s), %,d differ from the game GPU's saved answers%n", cells, ties,
         wrong, wrong == 0 ? "EXACT" : "WRONG", changed);
      int rounds = 40;
      long start = System.nanoTime();
      for (int r = 0; r < rounds; r++) {
         for (BiomeGpu.Job job : dump.jobs) {
            int n = job.count;
            gpu.run(job, new long[6 * n], new long[6 * n], new int[n], new int[n]);
         }
      }

      double perChunk = (System.nanoTime() - start) / 1.0e6 / (rounds * dump.jobs.size());
      System.out.printf(Locale.ROOT, "time: %.2f ms per chunk one at a time on an idle card (%s)%n", perChunk, gpu.timings());

      // The look-ahead's way: all the saved chunks (repeated to 32) in one launch; the same answers as one at a time.
      List<BiomeGpu.Job> batch = new ArrayList<>();
      List<long[]> climates = new ArrayList<>();
      List<long[]> projections = new ArrayList<>();
      List<int[]> chosens = new ArrayList<>();
      List<int[]> tieds = new ArrayList<>();
      for (int j = 0; j < 32; j++) {
         BiomeGpu.Job job = dump.jobs.get(j % dump.jobs.size());
         batch.add(job);
         climates.add(new long[6 * job.count]);
         projections.add(new long[6 * job.count]);
         chosens.add(new int[job.count]);
         tieds.add(new int[job.count]);
      }

      gpu.runBatch(batch, climates, projections, chosens, tieds);
      long batchDiffer = 0;
      for (int j = 0; j < 32; j++) {
         BiomeGpu.Job job = batch.get(j);
         long[] single = keep.get(j % dump.jobs.size());
         int[] chosen = new int[job.count];
         int[] tied = new int[job.count];
         gpu.run(job, new long[6 * job.count], new long[6 * job.count], chosen, tied);
         for (int i = 0; i < job.count; i++) {
            if (chosens.get(j)[i] != chosen[i] || tieds.get(j)[i] != tied[i] || projections.get(j)[i * 6 + 4] != single[i * 6 + 4]) {
               batchDiffer++;
            }
         }
      }

      start = System.nanoTime();
      for (int r = 0; r < rounds; r++) {
         gpu.runBatch(batch, climates, projections, chosens, tieds);
      }

      double perBatchChunk = (System.nanoTime() - start) / 1.0e6 / (rounds * 32);
      System.out.printf(Locale.ROOT, "batch of 32: %,d cells differ from one at a time (%s); %.3f ms per chunk (%s)%n", batchDiffer, batchDiffer == 0 ? "SAME" : "DIFFERENT",
         perBatchChunk, gpu.batchTimings());
   }

   /** Minecraft's distance over every point; the biome number, or -1 when two biomes share the smallest distance. */
   private static int brute(BiomeDump dump, long[] projected, int i) {
      long[] t = new long[7];
      for (int k = 0; k < 6; k++) {
         t[k] = projected[i * 6 + k];
      }

      long best = Long.MAX_VALUE;
      int biome = -1;
      boolean tie = false;
      int[] q = dump.points;
      for (int p = 0; p < dump.biomeIds.length; p++) {
         long d = 0;
         for (int k = 0; k < 7; k++) {
            long a = t[k] - q[p * 14 + 2 * k + 1];
            long b = q[p * 14 + 2 * k] - t[k];
            long e = a > 0 ? a : Math.max(b, 0);
            d += e * e;
         }

         if (d < best) {
            best = d;
            biome = dump.biomeIds[p];
            tie = false;
         } else if (d == best && dump.biomeIds[p] != biome) {
            tie = true;
         }
      }

      return tie ? -1 : biome;
   }
}
