package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.GpuBackend;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link OpenClProbe}'s kernels through the mod's own GPU route ({@link Gpu}: HIP on an AMD card), to compare with OpenCL on
 * the same card. Only in `gradlew.bat openclCheck` (the probe's jar for other PCs leaves the mod's classes out).
 */
final class OpenClProbeHip {
   private OpenClProbeHip() {
   }

   static void run(java.io.PrintStream out, String floatSource, String noiseSource, int floats, int iterations, int trips, int tripFloats, double[] d,
         int[] p, int[] offsets, int noises, int points, double[] xs, double[] ys, double[] zs, double[] expected) {
      out.println();
      out.println("== " + GpuBackend.name() + " through the mod's own route (the same kernels, for comparison)");
      try (Gpu gpu = new Gpu(floatSource, "probe_float.cu")) {
         FloatBuffer a = MemoryUtil.memAllocFloat(floats);
         for (int i = 0; i < floats; i++) {
            a.put(i, i * 0.001f);
         }

         long buffer = gpu.upload(a);
         gpu.run("floatWork", (floats + 255) / 256, 1, buffer, floats, iterations);
         long start = System.nanoTime();
         for (int r = 0; r < 10; r++) {
            gpu.enqueue("floatWork", (floats + 255) / 256, 1, buffer, floats, iterations);
         }

         gpu.finish();
         out.printf(Locale.ROOT, "   time, heavy float kernel (%,d x %d steps): %.2f ms per launch%n", floats, iterations, (System.nanoTime() - start) / 1.0e7);
         FloatBuffer small = MemoryUtil.memAllocFloat(tripFloats);
         long tripBuffer = gpu.allocate(4L * tripFloats);
         start = 0L;
         for (int r = -100; r < trips; r++) {
            if (r == 0) {
               start = System.nanoTime();
            }

            gpu.copyIn(tripBuffer, small);
            gpu.run("touch", (tripFloats + 255) / 256, 1, tripBuffer, tripFloats);
            gpu.download(tripBuffer, small);
         }

         out.printf(Locale.ROOT, "   time, round trip (copy 16 KB in, tiny kernel, copy out, wait): %.1f us each%n", (System.nanoTime() - start) / 1.0e3 / trips);
         MemoryUtil.memFree(a);
         MemoryUtil.memFree(small);
      }

      try (Gpu gpu = new Gpu(noiseSource, "probe_noise.cu")) {
         int values = noises * points;
         long[] buffers = {gpu.upload(MemoryUtil.memAllocDouble(d.length).put(0, d)), gpu.upload(MemoryUtil.memAllocInt(p.length).put(0, p)),
            gpu.upload(MemoryUtil.memAllocInt(offsets.length).put(0, offsets)), gpu.upload(MemoryUtil.memAllocDouble(points).put(0, xs)),
            gpu.upload(MemoryUtil.memAllocDouble(points).put(0, ys)), gpu.upload(MemoryUtil.memAllocDouble(points).put(0, zs)), gpu.allocate(8L * values)};
         gpu.run("mcNormalCheck", (values + 255) / 256, 1, buffers[0], buffers[1], buffers[2], noises, buffers[3], buffers[4], buffers[5], points, buffers[6]);
         DoubleBuffer result = MemoryUtil.memAllocDouble(values);
         gpu.download(buffers[6], result);
         int different = 0;
         for (int i = 0; i < values; i++) {
            if (Double.doubleToRawLongBits(result.get(i)) != Double.doubleToRawLongBits(expected[i])) {
               different++;
            }
         }

         out.printf(Locale.ROOT, "   vanilla NormalNoise (doubles, the real game's values): %,d values %s%n", values, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
         long start = System.nanoTime();
         for (int r = 0; r < 20; r++) {
            gpu.enqueue("mcNormalCheck", (values + 255) / 256, 1, buffers[0], buffers[1], buffers[2], noises, buffers[3], buffers[4], buffers[5], points, buffers[6]);
         }

         gpu.finish();
         out.printf(Locale.ROOT, "   time, noise kernel (%,d values): %.3f ms per launch%n", values, (System.nanoTime() - start) / 2.0e7);
         MemoryUtil.memFree(result);
      }
   }
}
