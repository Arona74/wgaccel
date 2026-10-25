package com.wgaccel.cudacheck;

import static org.lwjgl.cuda.CU.*;

import com.wgaccel.gpu.Gpu;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * I3: how long one chunk's per-column strata noise takes with different block shapes (256 threads in one block, as the
 * game launches strataSelect and strataFinish, or the same 256 spread over 4 or 8 blocks), on the game's saved strata
 * source (logs/wgaccel-strata.cu, kernel strataColumns). `gradlew.bat cudaStrataShapeBench`.
 */
public final class StrataShapeBench {
   private StrataShapeBench() {
   }

   private static void check(int result, String what) {
      if (result != CUDA_SUCCESS) {
         throw new IllegalStateException(what + " failed: " + result);
      }
   }

   public static void main(String[] args) throws Exception {
      Path saved = Path.of(System.getProperty("wgaccel.logs", "run/logs"), "wgaccel-strata.cu");
      String source = Files.readString(saved);
      long context = Gpu.createContext();
      long module = Gpu.loadModule(source, "strata_bench.cu");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         check(cuModuleGetFunction(function, module, stack.ASCII("strataColumns")), "cuModuleGetFunction");
         long kernel = function.get(0);
         int max = 64;
         PointerBuffer pointer = stack.mallocPointer(1);
         long[] buffers = new long[5];
         long[] sizes = {4L * 256, 4L * 256 * max, 4L * 256 * max, 4L * 256, 4L * 256};
         for (int i = 0; i < buffers.length; i++) {
            check(cuMemAlloc(pointer, sizes[i]), "cuMemAlloc");
            buffers[i] = pointer.get(0);
         }

         IntBuffer heights = MemoryUtil.memAllocInt(256);
         for (int i = 0; i < 256; i++) {
            heights.put(i, 60 + i % 40);
         }

         check(cuMemcpyHtoD(buffers[0], heights), "cuMemcpyHtoD");
         int[][] shapes = {{1, 256}, {2, 128}, {4, 64}, {8, 32}};
         for (int pass = 0; pass < 2; pass++) {
            for (int[] shape : shapes) {
               int rounds = 300;
               long total = 0;
               for (int round = 0; round < rounds; round++) {
                  int minX = 6320 + 16 * (round % 40);
                  int minZ = 7310 + 16 * (round / 40);
                  try (MemoryStack frame = MemoryStack.stackPush()) {
                     PointerBuffer params = frame.mallocPointer(7);
                     params.put(0, frame.pointers(buffers[0]).address());
                     params.put(1, MemoryUtil.memAddress(frame.ints(minX)));
                     params.put(2, MemoryUtil.memAddress(frame.ints(minZ)));
                     for (int i = 1; i < 5; i++) {
                        params.put(i + 2, frame.pointers(buffers[i]).address());
                     }

                     long start = System.nanoTime();
                     check(cuLaunchKernel(kernel, shape[0], 1, 1, shape[1], 1, 1, 0, 0L, params, null), "cuLaunchKernel");
                     check(cuCtxSynchronize(), "cuCtxSynchronize");
                     total += System.nanoTime() - start;
                  }
               }

               if (pass == 1) {
                  System.out.printf(Locale.ROOT, "strataColumns, %d block(s) of %d threads: %.3f ms per chunk (with the launch)%n", shape[0], shape[1],
                     total / 1.0e6 / rounds);
               }
            }
         }
      }

      cuCtxDestroy(context);
   }
}
