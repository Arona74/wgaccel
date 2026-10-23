package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.GpuBackend;
import com.wgaccel.gpu.GpuMemory;
import com.wgaccel.gpu.GpuStreams;
import java.nio.FloatBuffer;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;

/**
 * What the GPU runtime itself costs in RAM, outside the game (`gradlew.bat gpuMemoryCheck`): the process's committed
 * memory after starting the runtime, after each batch of new streams, after a kernel has run on every stream, and after
 * loading the same small program several times. The 23 Oct 2026 r1000 on the RX 9070 XT had 3.4 GB more native memory
 * with the mod than without, only 0.15 GB of it the mod's own page-locked buffers.
 */
public final class GpuMemoryCheck {
   private static final String SOURCE = "extern \"C\" __global__ void touch(float* out) { out[threadIdx.x] = (float)threadIdx.x; }\n";

   private GpuMemoryCheck() {
   }

   public static void main(String[] args) {
      GpuStreams.pool = 0;
      long start = GpuMemory.committed();
      print("JVM started", start, start);
      long context = Gpu.createContext();
      long runtime = GpuMemory.committed();
      print("GPU runtime started (" + GpuBackend.name() + ")", runtime, start);

      Gpu gpu = new Gpu(context, SOURCE, "touch.cu");
      long loaded = GpuMemory.committed();
      print("first program loaded", loaded, runtime);

      int total = 0;
      long[] streams = new long[128];
      long before = loaded;
      for (int batch : new int[] {1, 7, 8, 16, 32, 64}) {
         for (int i = 0; i < batch; i++) {
            streams[total++] = GpuStreams.create();
         }

         long now = GpuMemory.committed();
         print(total + " streams made", now, before);
         before = now;
      }

      long device = gpu.allocate(4L * 64);
      long launched = before;
      int used = 0;
      for (int count : new int[] {1, 7, 8, 16, 32, 64}) {
         for (int i = 0; i < count; i++) {
            gpu.useStream(streams[used++]);
            gpu.run("touch", 1, 1, device);
         }

         long now = GpuMemory.committed();
         print("a kernel run on " + used + " streams", now, launched);
         launched = now;
      }

      long programs = launched;
      for (int i = 1; i <= 8; i++) {
         new Gpu(context, SOURCE.replace("touch", "touch" + i), "touch" + i + ".cu");
      }

      print("8 more programs loaded", GpuMemory.committed(), programs);

      long cardBefore = GpuMemory.committed();
      for (int i = 0; i < 30; i++) {
         gpu.allocate(10L << 20);
      }

      print("300 MB of buffers on the card", GpuMemory.committed(), cardBefore);

      // Scratch: a per-thread array the compiler cannot keep in registers (indexed by a runtime value), as big kernels spill.
      String scratch = "extern \"C\" __global__ void spill(float* out, int n) { float a[SIZE]; for (int i = 0; i < SIZE; i++) a[i] = i * out[0];"
         + " out[blockIdx.x * blockDim.x + threadIdx.x] = a[(threadIdx.x * 7 + n) % SIZE]; }\n";
      long outBuffer = gpu.allocate(4L * 1024 * 1024);
      for (int size : new int[] {256, 1024, 4096}) {
         long spillStart = GpuMemory.committed();
         Gpu spill = new Gpu(context, scratch.replace("SIZE", Integer.toString(size)).replace("spill", "spill" + size), "spill" + size + ".cu");
         long loadedSpill = GpuMemory.committed();
         spill.run("spill" + size, 4096, 1, outBuffer, 3);
         print("scratch kernel, " + (size * 4 / 1024) + " KB per thread: load", loadedSpill, spillStart);
         print("scratch kernel, " + (size * 4 / 1024) + " KB per thread: first run", GpuMemory.committed(), loadedSpill);
         if (size == 256) {
            long one = GpuMemory.committed();
            for (int i = 1; i < 16; i++) {
               spill.useStream(streams[i]);
               spill.run("spill" + size, 4096, 1, outBuffer, 3);
            }

            print("  the same on 15 more streams", GpuMemory.committed(), one);
         }
      }

      FloatBuffer unused = MemoryUtil.memAllocFloat(1);
      MemoryUtil.memFree(unused);
      System.out.println(GpuMemory.summary());
   }

   private static void print(String what, long now, long before) {
      System.out.println(String.format(Locale.ROOT, "%-40s committed %,7d MB (%+,d MB)", what, now >> 20, (now - before) >> 20));
   }
}
