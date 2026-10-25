package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.GpuBackend;
import java.io.DataOutputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryUtil;

/**
 * Runs every root of a saved noise-tree source (the game's logs/wgaccel-tile-noise.cu) at fixed points on the chosen
 * backend and writes the values (`gradlew.bat treeReplay -Pcu=... -PgpuBackend=...`): two backends' files compared tell
 * which roots differ (args: source, output file).
 */
public final class TreeReplay {
   private TreeReplay() {
   }

   public static void main(String[] args) throws Exception {
      String source = Files.readString(Path.of(args[0]));
      Matcher cases = Pattern.compile("case (\\d+): v = ").matcher(source);
      int roots = 0;
      while (cases.find()) {
         roots = Math.max(roots, Integer.parseInt(cases.group(1)) + 1);
      }

      int points = 4096;
      FloatBuffer xs = MemoryUtil.memAllocFloat(points);
      FloatBuffer zs = MemoryUtil.memAllocFloat(points);
      IntBuffer seeds = MemoryUtil.memAllocInt(points);
      Random random = new Random(7L);
      for (int i = 0; i < points; i++) {
         xs.put(i, (random.nextFloat() - 0.5F) * 40000.0F);
         zs.put(i, (random.nextFloat() - 0.5F) * 40000.0F);
         seeds.put(i, random.nextInt());
      }

      FloatBuffer values = MemoryUtil.memAllocFloat(points);
      IntBuffer flags = MemoryUtil.memAllocInt(points);
      try (Gpu gpu = new Gpu(source, "tile_noise.cu"); DataOutputStream out = new DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
         long dx = gpu.upload(xs);
         long dz = gpu.upload(zs);
         long dseeds = gpu.upload(seeds);
         // As TileNoiseGpu: up to 4 roots in one launch (blockIdx.y the row).
         long dout = gpu.allocate(16L * points);
         long dflags = gpu.allocate(16L * points);
         out.writeInt(roots);
         out.writeInt(points);
         FloatBuffer four = MemoryUtil.memAllocFloat(4 * points);
         IntBuffer fourFlags = MemoryUtil.memAllocInt(4 * points);
         for (int r = 0; r < roots; r += 4) {
            int rows = Math.min(4, roots - r);
            gpu.run("treeCheck", points / 256, rows, dx, dz, dseeds, points, r, 0, dout, dflags);
            gpu.download(dout, four);
            gpu.download(dflags, fourFlags);
            for (int k = 0; k < rows * points; k++) {
               out.writeFloat(four.get(k));
               out.writeInt(fourFlags.get(k));
            }
         }
      }

      System.out.printf(Locale.ROOT, "%s: %d roots x %d points written to %s%n", GpuBackend.name(), roots, points, args[1]);
      together(source, roots);
   }

   /**
    * As TileNoiseGpu.evaluate from several threads on one shared Gpu (the game's raw chunk and look-ahead threads): per
    * job its own buffers, a group of 4 roots launched with the output pointers offset by row, then freed. Every job's
    * values against the same points evaluated alone.
    */
   private static void together(String source, int roots) throws Exception {
      int groups = roots / 4;
      try (Gpu gpu = new Gpu(source, "tile_noise_together.cu")) {
         java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
         java.util.concurrent.atomic.AtomicInteger jobs = new java.util.concurrent.atomic.AtomicInteger();
         java.util.concurrent.atomic.AtomicInteger different = new java.util.concurrent.atomic.AtomicInteger();
         java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
         for (int t = 0; t < 6; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
               Random random = new Random(100 + thread);
               for (int j = 0; j < 40; j++) {
                  int points = 300 + random.nextInt(3000);
                  int first = 4 * random.nextInt(groups);
                  float[] x = new float[points];
                  float[] z = new float[points];
                  for (int i = 0; i < points; i++) {
                     x[i] = (random.nextFloat() - 0.5F) * 40000.0F;
                     z[i] = (random.nextFloat() - 0.5F) * 40000.0F;
                  }

                  float[] shared = evaluate(gpu, x, z, points, first);
                  float[] alone;
                  synchronized (TreeReplay.class) {
                     alone = evaluate(gpu, x, z, points, first);
                  }

                  jobs.incrementAndGet();
                  if (!java.util.Arrays.equals(shared, alone)) {
                     if (different.incrementAndGet() <= 5) {
                        int k = java.util.Arrays.mismatch(shared, alone);
                        System.out.printf(Locale.ROOT, "  job of %d points, roots %d..%d: value %d is %s at once, %s alone%n", points, first, first + 3, k, shared[k], alone[k]);
                     }
                  }
               }

               return null;
            }));
         }

         for (java.util.concurrent.Future<?> future : futures) {
            future.get();
         }

         pool.shutdown();
         System.out.printf(Locale.ROOT, "%s, 6 threads on one Gpu: %d jobs, %d differ from the same job alone%n", GpuBackend.name(), jobs.get(), different.get());
      }
   }

   private static float[] evaluate(Gpu gpu, float[] x, float[] z, int points, int first) {
      FloatBuffer xs = MemoryUtil.memAllocFloat(points).put(0, x, 0, points);
      FloatBuffer zs = MemoryUtil.memAllocFloat(points).put(0, z, 0, points);
      IntBuffer seeds = MemoryUtil.memCallocInt(points);
      FloatBuffer values = MemoryUtil.memAllocFloat(4 * points);
      long dx = gpu.upload(xs);
      long dz = gpu.upload(zs);
      long dseeds = gpu.upload(seeds);
      long dout = gpu.allocate(16L * points);
      long dflags = gpu.allocate(16L * points);
      try {
         for (int i = 0; i < 4; i += 2) {
            gpu.run("treeCheck", (points + 255) / 256, 2, dx, dz, dseeds, points, first + i, 0, dout + 4L * points * i, dflags + 4L * points * i);
         }

         gpu.download(dout, values);
         float[] out = new float[4 * points];
         values.get(0, out);
         return out;
      } finally {
         for (long device : new long[] {dx, dz, dseeds, dout, dflags}) {
            gpu.free(device);
         }

         MemoryUtil.memFree(xs);
         MemoryUtil.memFree(zs);
         MemoryUtil.memFree(seeds);
         MemoryUtil.memFree(values);
      }
   }
}
