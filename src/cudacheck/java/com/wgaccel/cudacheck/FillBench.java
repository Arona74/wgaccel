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
   private record Sample(FillGpu.Job job, byte[] codes, double[] noise, int[] permutations, int interpolators, int columns, float[] beardKernel) {
   }

   private FillBench() {
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

      List<Sample> samples = new ArrayList<>();
      for (int i = 0; Files.exists(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin")); i++) {
         samples.add(read(logs.resolve("wgaccel-noise-fill-sample-" + i + ".bin")));
      }

      if (samples.isEmpty()) {
         System.out.println("no samples in " + logs);
         return;
      }

      if (System.getProperty("wgaccel.cache") != null) {
         com.wgaccel.gpu.GpuSelfTest.cacheDirectory = Path.of(System.getProperty("wgaccel.cache"));
         System.out.println("cache: " + com.wgaccel.gpu.GpuSelfTest.cacheDirectory);
      }

      Sample first = samples.get(0);
      long context = Gpu.createContext();
      long start = System.nanoTime();
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
      if (com.wgaccel.gpu.Gpu.linkProblem != null) {
         System.out.println("link to machine code: " + com.wgaccel.gpu.Gpu.linkProblem);
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
      System.out.printf(Locale.ROOT, "alone: %.2f ms per chunk (GPU: corners %.2f, columns %.2f, block states %.2f, copy out %.2f)%n", aloneMs, kernels[0],
         kernels[1], kernels[2], kernels[3]);
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
   }

   private static Sample read(Path path) throws Exception {
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int version = in.readInt();
         if (version != 1 && version != 2) {
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

         return new Sample(job, codes, noise, permutations, interpolators, columns, kernel);
      }
   }
}
