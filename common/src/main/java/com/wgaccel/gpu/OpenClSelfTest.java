package com.wgaccel.gpu;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link GpuSelfTest} for OpenCL ({@link OpenCl}): the same CUDA kernel translated ({@link OpenClSource}), built for
 * the device and its floats compared with Java's bit for bit, through {@link Gpu} as the GPU parts use it. Uses no
 * Minecraft classes.
 */
public final class OpenClSelfTest {
   private OpenClSelfTest() {
   }

   /** Runs the test on the chosen OpenCL device; returns the lines to log. Throws when the route fails. */
   public static List<String> run() {
      List<String> lines = new ArrayList<>();
      lines.add("device " + GpuDevice.index() + " (" + GpuDevice.reason + ")");
      long started = System.nanoTime();
      try (Gpu gpu = new Gpu(GpuSelfTest.SOURCE, "terrain_like.cl")) {
         double compileMs = (System.nanoTime() - started) / 1.0e6;
         OpenCl.Device device = OpenCl.chosen();
         lines.add("GPU: " + device.name() + " (" + device.vendor() + ", " + device.units() + " compute units" + (device.integrated() ? ", integrated" : "")
            + "); " + device.version() + ", driver " + device.driver() + "; options " + OpenCl.options());
         int n = 1 << 22;
         FloatBuffer a = MemoryUtil.memAllocFloat(n);
         FloatBuffer b = MemoryUtil.memAllocFloat(n);
         FloatBuffer out = MemoryUtil.memAllocFloat(n);
         try {
            java.util.Random random = new java.util.Random(12345L);
            for (int i = 0; i < n; i++) {
               a.put(i, 0.001F + random.nextFloat() * 1000.0F);
               b.put(i, (random.nextFloat() - 0.5F) * 2000.0F);
            }

            long da = gpu.upload(a);
            long db = gpu.upload(b);
            long dout = gpu.allocate(4L * n);
            long launch = System.nanoTime();
            gpu.run("terrainLike", (n + 255) / 256, 1, da, db, dout, n);
            double gpuMs = (System.nanoTime() - launch) / 1.0e6;
            gpu.download(dout, out);
            int different = 0;
            int notANumber = 0;
            for (int i = 0; i < n; i++) {
               float expected = GpuSelfTest.terrainLike(a.get(i), b.get(i));
               float actual = out.get(i);
               if (Float.isNaN(expected) && Float.isNaN(actual)) {
                  notANumber++;
               } else if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual) && different++ < 4) {
                  lines.add(String.format(Locale.ROOT, "  differs: a=%s b=%s java=%s gpu=%s", a.get(i), b.get(i), expected, actual));
               }
            }

            lines.add(String.format(Locale.ROOT, "kernel built in %.0f ms; %,d values in %.2f ms on the GPU", compileMs, n, gpuMs));
            lines.add(different == 0
               ? String.format(Locale.ROOT, "BIT-IDENTICAL: all %,d numbers equal Java's bit for bit (%,d NaN on both sides)", n - notANumber, notANumber)
               : String.format(Locale.ROOT, "DIFFERENT: %,d of %,d numbers differ from Java's", different, n));
         } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
            MemoryUtil.memFree(out);
         }
      }

      return lines;
   }

   /**
    * Kernel sources through the translation and OpenCL's compiler, each built and released: what builds as it is, and
    * the compiler's errors for what does not (`gradlew.bat openclCheck`; the generated ones need a world).
    */
   public static List<String> compileModules(Map<String, String> sources) {
      List<String> lines = new ArrayList<>();
      OpenCl.init();
      for (Map.Entry<String, String> source : sources.entrySet()) {
         long started = System.nanoTime();
         try {
            long program = OpenCl.loadModule(source.getValue(), source.getKey().replaceAll("[^A-Za-z0-9]+", "_"));
            OpenCl.unloadModule(program);
            lines.add(String.format(Locale.ROOT, "OK      %s in %.0f ms", source.getKey(), (System.nanoTime() - started) / 1.0e6));
         } catch (RuntimeException failure) {
            lines.add("FAILED  " + source.getKey() + ": " + failure.getMessage());
         }
      }

      return lines;
   }
}
