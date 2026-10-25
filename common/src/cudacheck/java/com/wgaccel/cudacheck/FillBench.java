package com.wgaccel.cudacheck;

import com.wgaccel.gpu.FillGpu;
import com.wgaccel.gpu.Gpu;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The real noise fill outside the game: the instance's logs/wgaccel-noise-fill.cu (or -Dwgaccel.fillSource) and the
 * chunks saved beside it (wgaccel-noise-fill-sample-N.bin, by the game's first GPU chunks), compiled and run with
 * {@link FillGpu}: every sample's codes must equal the game's byte for byte, then the kernels are timed alone and with
 * 14 threads at once (as C2ME's workers ask). For changing the fill's code against real data without a game run.
 */
public final class FillBench {
   private record Sample(FillGpu.Job job, byte[] codes, double[] noise, int[] permutations, int interpolators, int columns, float[] beardKernel,
         byte[] game, String name) {
   }

   private FillBench() {
   }

   /**
    * -Dwgaccel.veinDebug=true: OreVeinifier's steps per block written by mc_ore_vein (vein noise, first random float,
    * ridged noise, d3, second random float, gap noise; -12345 for a step not reached), for comparing backends block by
    * block (an Intel Arc iGPU gave other vein blocks than the game, 23 Oct 2026). The buffer is fillStates' 26th
    * argument (FillGpu.debugBuffer), kept in DCtx: a pointer in a program-scope variable set by another kernel hung the
    * Radeon's driver twice.
    */
   private static String veinDebugSource(String source) {
      String helpers = """
         __device__ void wga_dbg(const DCtx& c, int k, double v) {
             int i = ((c.y - c.cellMinY * c.cellHeight) * 256 + (c.z - c.chunkZ) * 16 + (c.x - c.chunkX)) * 8 + k;
             if (c.dbg != 0 && i >= 0 && i < 8 * 256 * 1024) c.dbg[i] = v;
         }
         """;
      // DCtx gets the buffer, fillStates takes it last and stores it.
      source = replaceOnce(source, "const double* ccolumns = 0;", "const double* ccolumns = 0;\n    double* dbg = 0;");
      int kernel = source.indexOf("void fillStates(");
      int open = source.indexOf(") {", kernel);
      int z = source.indexOf("c.z = chunkZ + lz;", open);
      if (kernel < 0 || open < 0 || z < 0) {
         throw new IllegalStateException("vein debug: fillStates not found as expected in the source");
      }

      source = source.substring(0, open) + ", double* dbgOut" + source.substring(open, z) + "c.z = chunkZ + lz; c.dbg = dbgOut;"
         + source.substring(z + "c.z = chunkZ + lz;".length());
      int at = source.indexOf("__device__ int mc_ore_vein(");
      int end = at < 0 ? -1 : source.indexOf("\n}", at) >= 0 ? source.indexOf("return base + 2;", at) : -1;
      if (at < 0 || end < 0) {
         throw new IllegalStateException("vein debug: mc_ore_vein not found in the source");
      }

      String patched = source.substring(at, end);
      patched = patched.replaceFirst("(double d0 = \\w+\\(c\\);)", "$1 wga_dbg(c, 0, d0);");
      patched = patched.replaceFirst("if \\(mc_next_float\\(r\\) > 0\\.7f\\) return 0;", "float f1 = mc_next_float(r); wga_dbg(c, 1, f1); if (f1 > 0.7f) return 0;");
      patched = patched.replaceFirst("if \\((\\w+)\\(c\\) >= 0\\.0\\) return 0;", "double rv = $1(c); wga_dbg(c, 2, rv); if (rv >= 0.0) return 0;");
      patched = patched.replaceFirst("(double d3 = [^;]+;)", "$1 wga_dbg(c, 3, d3);");
      patched = patched.replaceFirst("if \\(\\(double\\)mc_next_float\\(r\\) < d3 && (\\w+)\\(c\\) > \\(double\\)-0\\.3f\\)",
         "float f2 = mc_next_float(r); double gv = $1(c); wga_dbg(c, 4, f2); wga_dbg(c, 5, gv); if ((double)f2 < d3 && gv > (double)-0.3f)");
      if (!patched.contains("wga_dbg(c, 0, d0)") || !patched.contains("wga_dbg(c, 2, rv)") || !patched.contains("wga_dbg(c, 5, gv)")) {
         throw new IllegalStateException("vein debug: mc_ore_vein not as expected:\n" + patched);
      }

      return source.substring(0, at) + helpers + patched + source.substring(end);
   }

   private static String replaceOnce(String text, String what, String with) {
      int at = text.indexOf(what);
      if (at < 0) {
         throw new IllegalStateException("vein debug: '" + what + "' not found in the source");
      }

      return text.substring(0, at) + with + text.substring(at + what.length());
   }

   private static void ok(int result, String what) {
      if (result != 0) {
         throw new IllegalStateException(what + " failed: " + result);
      }
   }

   private static final int DEBUG_DOUBLES = 8 * 256 * 1024;

   /** The debug buffer, made before the first fill (the patched fillStates always takes it). */
   private static long debugBuffer(long context) {
      try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
         ok(com.wgaccel.gpu.GpuApi.cuCtxSetCurrent(context), "cuCtxSetCurrent");
         org.lwjgl.PointerBuffer pointer = stack.mallocPointer(1);
         ok(com.wgaccel.gpu.GpuApi.cuMemAlloc(pointer, 8L * DEBUG_DOUBLES), "cuMemAlloc");
         return pointer.get(0);
      }
   }

   private static void veinDebug(FillGpu gpu, long context, List<Sample> samples, long debug) throws Exception {
      int doubles = DEBUG_DOUBLES;
      {
         java.nio.DoubleBuffer host = org.lwjgl.system.MemoryUtil.memAllocDouble(doubles);
         for (Sample sample : samples) {
            // The game's blocks when saved with them (chunks after a difference), else the game's own codes.
            byte[] expected = sample.game;
            if (expected == null) {
               expected = new byte[sample.codes.length];
               for (int i = 0; i < expected.length; i++) {
                  expected[i] = (byte)(sample.codes[i] & 15);
               }
            }

            for (int i = 0; i < doubles; i++) {
               host.put(i, -12345.0);
            }

            ok(com.wgaccel.gpu.GpuApi.cuMemcpyHtoD(debug, host), "clear");
            byte[] codes = new byte[sample.codes.length];
            gpu.fill(sample.job, codes);
            ok(com.wgaccel.gpu.GpuApi.ncuMemcpyDtoHAsync(org.lwjgl.system.MemoryUtil.memAddress(host), debug, 8L * doubles, 0L), "copy");
            ok(com.wgaccel.gpu.GpuApi.cuStreamSynchronize(0L), "sync");
            System.out.printf(Locale.ROOT, "%s: blocks where the game placed a vein block or the GPU's code differs (x y z: game gpu | vein noise, random 1, ridged, d3, random 2, gap)%n",
               sample.name);
            int shown = 0;
            for (int t = 0; t < expected.length && shown < 40; t++) {
               int game = expected[t];
               int mine = codes[t] & 15;
               if (game != mine || game >= 1 && game <= 6) {
                  shown++;
                  StringBuilder steps = new StringBuilder();
                  for (int k = 0; k < 6; k++) {
                     double v = host.get(t * 8 + k);
                     steps.append(v == -12345.0 ? "        -" : String.format(Locale.ROOT, " %s", Double.toString(v)));
                  }

                  System.out.printf(Locale.ROOT, "  %d %d %d: %d %d |%s%n", sample.job.chunkX + t % 16, sample.job.cellMinY * sample.job.cellHeight + t / 256,
                     sample.job.chunkZ + t % 256 / 16, game, mine, steps);
               }
            }
         }
      }
   }

   public static void main(String[] args) throws Exception {
      Path logs = Path.of(System.getProperty("wgaccel.logs", "run/logs"));
      String source = Files.readString(Path.of(System.getProperty("wgaccel.fillSource", logs.resolve("wgaccel-noise-fill.cu").toString())));
      if (!source.contains("const int* beard, const float* beardKernel")) {
         // A source saved before S7 (structure terrain): its fillStates takes the two new parameters unused (the samples
         // have no structures; such sources add 0.0 for the Beardifier).
         source = source.replace("int aquifer, unsigned char* codes, const double* columns) {",
            "int aquifer, unsigned char* codes, const double* columns, const int* beard, const float* beardKernel) {");
      }

      // A source saved before 0.20.0's OpenCL mc_next_float (Intel's optimizer converted the 24 bits as signed).
      String bits = "uint v = as_uint2(mc_next_long(r)).s1 >> 8; return (as_float(0x3F800000u | (v >> 1)) - 1.0f) + ((v & 1u) != 0u ? 5.9604645E-8f : 0.0f);";
      String word = "return (float)(int)(as_uint2(mc_next_long(r)).s1 >> 8) * 5.9604645E-8f;";
      String nextFloat = "\n#ifdef __OPENCL_VERSION__\n" + bits + "\n#else\nreturn (float)(long long)(mc_next_long(r) >> 40) * 5.9604645E-8f;\n#endif\n";
      String masked = "return (float)(long long)((mc_next_long(r) >> 40) & 16777215ull) * 5.9604645E-8f;";
      if (source.contains(word)) {
         source = source.replace(word, bits);
      } else if (source.contains(masked)) {
         source = source.replace(masked, nextFloat);
      } else if (!source.contains(bits)) {
         source = source.replace("return (float)(long long)(mc_next_long(r) >> 40) * 5.9604645E-8f;", nextFloat);
      }

      // -Dwgaccel.nextFloat=bits|split|shift|word: OpenCL's mc_next_float written another way, for finding one that
      // Intel's optimizer gets right inside the fill (bits is the mod's).
      String form = System.getProperty("wgaccel.nextFloat");
      if (form != null) {
         String line = switch (form) {
            case "bits" -> bits;
            case "split" -> "uint v = as_uint2(mc_next_long(r)).s1 >> 8; return (float)(v >> 12) * 0.000244140625f + (float)(v & 4095u) * 5.9604645E-8f;";
            case "shift" -> "return (float)(long)(mc_next_long(r) >> 40) * 5.9604645E-8f;";
            case "word" -> word;
            default -> throw new IllegalArgumentException("wgaccel.nextFloat: bits, split, shift or word");
         };
         source = source.replace(bits, line);
         System.out.println("OpenCL nextFloat: " + line);
      }
      List<Sample> samples = new ArrayList<>();
      for (int i = 0; Files.exists(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin")); i++) {
         samples.add(read(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin")));
      }

      // Chunks saved after a difference or named in gpuFillDebugChunks (100 to 107), with the game's own blocks.
      for (int i = 100; i < 108; i++) {
         if (Files.exists(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin"))) {
            samples.add(read(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin")));
         }
      }

      if (samples.isEmpty()) {
         System.out.println("no samples in " + logs);
         return;
      }

      if (System.getProperty("wgaccel.cache") != null) {
         com.wgaccel.gpu.GpuSelfTest.cacheDirectory = Path.of(System.getProperty("wgaccel.cache"));
         System.out.println("cache: " + com.wgaccel.gpu.GpuSelfTest.cacheDirectory);
      }

      boolean veinDebug = Boolean.getBoolean("wgaccel.veinDebug");
      if (veinDebug) {
         source = veinDebugSource(source);
      }

      Sample first = samples.get(0);
      long context = Gpu.createContext();
      long start = System.nanoTime();
      long debug = veinDebug ? debugBuffer(context) : 0L;
      FillGpu.debugBuffer = debug;
      FillGpu gpu = new FillGpu(context, source, first.noise, first.permutations, first.interpolators, first.columns);
      if (first.beardKernel() != null) {
         gpu.setBeardKernel(first.beardKernel());
      }
      System.out.printf(Locale.ROOT, "%d samples, source %,d KB, compiled in %.0f ms%n", samples.size(), source.length() / 1024, (System.nanoTime() - start) / 1.0e6);
      int same = 0;
      for (Sample sample : samples) {
         byte[] codes = new byte[sample.codes.length];
         gpu.fill(sample.job, codes);
         same += Arrays.equals(codes, sample.codes) ? 1 : 0;
      }

      System.out.printf(Locale.ROOT, "codes: %d of %d samples IDENTICAL to the game's%n", same, samples.size());
      for (Sample sample : samples) {
         if (sample.game == null) {
            continue;
         }

         byte[] codes = new byte[sample.codes.length];
         gpu.fill(sample.job, codes);
         int differ = 0;
         StringBuilder where = new StringBuilder();
         for (int t = 0; t < sample.game.length; t++) {
            if ((codes[t] & 15) != sample.game[t]) {
               if (differ++ < 8) {
                  where.append(String.format(Locale.ROOT, " (%d, %d, %d) gpu %d game %d;", sample.job.chunkX + t % 16, sample.job.cellMinY * sample.job.cellHeight
                     + t / 256, sample.job.chunkZ + t % 256 / 16, codes[t] & 15, sample.game[t]));
               }
            }
         }

         System.out.printf(Locale.ROOT, "%s (chunk at block %d %d): the GPU here against the game's blocks: %,d of %,d differ%s%n", sample.name,
            sample.job.chunkX, sample.job.chunkZ, differ, sample.game.length, where);
      }
      if (com.wgaccel.gpu.Gpu.linkProblem != null) {
         System.out.println("link to machine code: " + com.wgaccel.gpu.Gpu.linkProblem);
      }

      if (veinDebug) {
         veinDebug(gpu, context, samples, debug);
         return;
      }
      // G4a: all samples in one batch (each twice, so a batch holds repeats), compared the same way, then timed.
      List<FillGpu.Job> batchJobs = new ArrayList<>();
      List<byte[]> batchCodes = new ArrayList<>();
      for (int r = 0; r < 2; r++) {
         for (Sample sample : samples) {
            batchJobs.add(sample.job);
            batchCodes.add(new byte[sample.codes.length]);
         }
      }

      gpu.fillBatch(batchJobs, batchCodes);
      int sameBatch = 0;
      for (int i = 0; i < batchJobs.size(); i++) {
         sameBatch += Arrays.equals(batchCodes.get(i), samples.get(i % samples.size()).codes) ? 1 : 0;
      }

      System.out.printf(Locale.ROOT, "batched: %d of %d chunks IDENTICAL to the game's%n", sameBatch, batchJobs.size());
      for (int size : new int[] {1, 4, 8, 16, 32}) {
         List<FillGpu.Job> jobs = new ArrayList<>();
         List<byte[]> outs = new ArrayList<>();
         for (int i = 0; i < size; i++) {
            Sample sample = samples.get(i % samples.size());
            jobs.add(sample.job);
            outs.add(new byte[sample.codes.length]);
         }

         gpu.fillBatch(jobs, outs);
         int rounds = Math.max(4, 160 / size);
         long t0 = System.nanoTime();
         for (int r = 0; r < rounds; r++) {
            gpu.fillBatch(jobs, outs);
         }

         double ms = (System.nanoTime() - t0) / 1.0e6 / rounds;
         System.out.printf(Locale.ROOT, "batch of %d: %.2f ms per batch, %.2f ms per chunk, %.0f chunks/s on one thread%n", size, ms, ms / size, size * 1000.0 / ms);
      }
      int runs = 160;
      long alone = System.nanoTime();
      for (int r = 0; r < runs; r++) {
         Sample sample = samples.get(r % samples.size());
         gpu.fill(sample.job, new byte[sample.codes.length]);
      }

      double aloneMs = (System.nanoTime() - alone) / 1.0e6 / runs;
      double[] kernels = gpu.kernelMillis();
      if (kernels == null) {
         // Not timed (GpuBackend.TIMING off under HIP).
         System.out.printf(Locale.ROOT, "alone: %.2f ms per chunk%n", aloneMs);
      } else {
         System.out.printf(Locale.ROOT, "alone: %.2f ms per chunk (GPU: corners %.2f, columns %.2f, block states %.2f, copy out %.2f)%n", aloneMs, kernels[0],
            kernels[1], kernels[2], kernels[3]);
      }
      int threads = 14;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<?>> futures = new ArrayList<>();
      long together = System.nanoTime();
      for (int t = 0; t < threads; t++) {
         int offset = t;
         futures.add(pool.submit(() -> {
            for (int r = 0; r < runs / 4; r++) {
               Sample sample = samples.get((r + offset) % samples.size());
               gpu.fill(sample.job, new byte[sample.codes.length]);
            }

            return null;
         }));
      }

      for (Future<?> future : futures) {
         future.get();
      }

      double seconds = (System.nanoTime() - together) / 1.0e9;
      pool.shutdown();
      System.out.printf(Locale.ROOT, "%d threads: %.0f chunks/s on the GPU (%.2f ms per chunk of GPU time)%n", threads, threads * (runs / 4) / seconds,
         seconds * 1000.0 / (threads * (runs / 4)));
      together(gpu, samples, 1);
      together(gpu, samples, threads);
   }

   /**
    * As the game runs it: threads at once, half of them as the look-ahead's (its lowest stream priority, batches of 8),
    * half as chunks waited for (the highest, one chunk at a time), every result compared with the game's codes. On a
    * Radeon 680M (HIP) a look-ahead chunk came back different in game while the same job alone gave the game's blocks.
    */
   private static void together(FillGpu gpu, List<Sample> samples, int threads) throws Exception {
      java.util.concurrent.atomic.AtomicInteger chunks = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger different = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger batchDifferent = new java.util.concurrent.atomic.AtomicInteger();
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
         int offset = t;
         boolean ahead = t % 2 == 0;
         futures.add(pool.submit(() -> {
            if (ahead) {
               // Before the thread's first fill: its lane's stream gets the look-ahead's priority.
               com.wgaccel.gpu.GpuStreams.background();
            }

            for (int r = 0; r < 40; r++) {
               if (ahead) {
                  List<FillGpu.Job> jobs = new ArrayList<>();
                  List<byte[]> outs = new ArrayList<>();
                  List<Sample> expected = new ArrayList<>();
                  for (int i = 0; i < 8; i++) {
                     Sample sample = samples.get((r * 8 + i + offset) % samples.size());
                     jobs.add(sample.job);
                     outs.add(new byte[sample.codes.length]);
                     expected.add(sample);
                  }

                  gpu.fillBatch(jobs, outs);
                  for (int i = 0; i < jobs.size(); i++) {
                     chunks.incrementAndGet();
                     if (!Arrays.equals(outs.get(i), expected.get(i).codes)) {
                        different.incrementAndGet();
                        if (batchDifferent.incrementAndGet() <= 12 && threads == 1) {
                           int bytes = 0;
                           for (int k = 0; k < outs.get(i).length; k++) {
                              bytes += outs.get(i)[k] != expected.get(i).codes[k] ? 1 : 0;
                           }

                           StringBuilder batch = new StringBuilder();
                           for (Sample s : expected) {
                              batch.append(s.name.replace("wgaccel-noise-fill-sample-", "").replace(".bin", "")).append(' ');
                           }

                           System.out.printf(Locale.ROOT, "  batch %d, position %d (%s, %,d cells high): %,d codes differ; batch [%s]%n", r, i, expected.get(i).name,
                              expected.get(i).job.cellCountY, bytes, batch.toString().trim());
                        }
                     }
                  }
               } else {
                  Sample sample = samples.get((r + offset) % samples.size());
                  byte[] out = new byte[sample.codes.length];
                  gpu.fill(sample.job, out);
                  chunks.incrementAndGet();
                  if (!Arrays.equals(out, sample.codes)) {
                     different.incrementAndGet();
                  }
               }
            }

            return null;
         }));
      }

      for (Future<?> future : futures) {
         future.get();
      }

      pool.shutdown();
      System.out.printf(Locale.ROOT, "%d threads at once (look-ahead batches on the lowest priority, single chunks on the highest): %,d chunks, %s%n", threads,
         chunks.get(), different.get() == 0 ? "all IDENTICAL to the game's" : different.get() + " DIFFER (" + batchDifferent.get() + " of them from batches)");
   }

   private static Sample read(Path path) throws Exception {
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int version = in.readInt();
         if (version < 1 || version > 3) {
            throw new IllegalStateException("unknown sample version in " + path);
         }

         double[] noise = new double[in.readInt()];
         for (int i = 0; i < noise.length; i++) {
            noise[i] = in.readDouble();
         }

         int[] permutations = new int[in.readInt()];
         for (int i = 0; i < permutations.length; i++) {
            permutations[i] = in.readInt();
         }

         int interpolators = in.readInt();
         int columns = in.readInt();
         FillGpu.Job job = new FillGpu.Job();
         job.cells = new double[in.readInt()];
         for (int i = 0; i < job.cells.length; i++) {
            job.cells[i] = in.readDouble();
         }

         job.chunkX = in.readInt();
         job.chunkZ = in.readInt();
         job.cellMinY = in.readInt();
         job.cellCountY = in.readInt();
         job.cellWidth = in.readInt();
         job.cellHeight = in.readInt();
         job.veins = in.readInt() != 0;
         job.aquifer = in.readInt();
         job.sea = in.readInt();
         job.seaType = in.readInt();
         job.cellsUsed = in.readInt();
         job.seedLo = in.readLong();
         job.seedHi = in.readLong();
         for (int i = 0; i < 6; i++) {
            job.grid[i] = in.readInt();
         }

         for (int i = 0; i < 256; i++) {
            job.lava[i] = in.readInt();
         }

         job.loc = new int[3 * job.cellsUsed];
         job.status = new int[3 * job.cellsUsed];
         for (int i = 0; i < job.loc.length; i++) {
            job.loc[i] = in.readInt();
         }

         for (int i = 0; i < job.status.length; i++) {
            job.status[i] = in.readInt();
         }

         byte[] codes = new byte[in.readInt()];
         in.readFully(codes);
         float[] kernel = null;
         if (version >= 2) {
            job.beard = new int[in.readInt()];
            for (int i = 0; i < job.beard.length; i++) {
               job.beard[i] = in.readInt();
            }

            kernel = new float[in.readInt()];
            for (int i = 0; i < kernel.length; i++) {
               kernel[i] = in.readFloat();
            }
         }

         byte[] game = null;
         if (version >= 3) {
            game = new byte[in.readInt()];
            in.readFully(game);
         }

         return new Sample(job, codes, noise, permutations, interpolators, columns, kernel, game, path.getFileName().toString());
      }
   }
}
