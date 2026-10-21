package com.wgaccel.cudacheck;

import static org.lwjgl.cuda.CU.*;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.GpuEnv;
import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * I3: how long a small launch on a high-priority stream takes from launch to done while many low-priority streams keep
 * the card busy with ~1 ms launches that leave most of it free, as in the game (the surface's batches next to the
 * fill's, biomes' and tiles' lanes). Run with -Pconnections=8 (the driver's default) and 32: `gradlew.bat
 * cudaQueueBench -Pconnections=32`.
 */
public final class QueueBench {
   private static final String SOURCE = """
      extern "C" __global__ void spin(long long cycles, int* sink) {
          long long start = clock64();
          while (clock64() - start < cycles) { }
          if (threadIdx.x == 0 && cycles < 0) sink[0] = 1;
      }
      """;

   private QueueBench() {
   }

   private static void check(int result, String what) {
      if (result != CUDA_SUCCESS) {
         throw new IllegalStateException(what + " failed: " + result);
      }
   }

   public static void main(String[] args) throws Exception {
      int connections = Integer.getInteger("connections", 8);
      if (Boolean.getBoolean("preload")) {
         // As the game: the driver library loaded (and asked its version) before the variable is set.
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer version = stack.mallocInt(1);
            check(cuDriverGetVersion(version), "cuDriverGetVersion");
            System.out.println("preloaded the driver library: version " + version.get(0));
         }
      }

      GpuEnv.deviceConnections(connections);
      long context = Gpu.createContext();
      long module = Gpu.loadModule(SOURCE, "queue_bench.cu");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         check(cuModuleGetFunction(function, module, stack.ASCII("spin")), "cuModuleGetFunction");
         long spin = function.get(0);
         PointerBuffer pointer = stack.mallocPointer(1);
         check(cuMemAlloc(pointer, 4), "cuMemAlloc");
         long sink = pointer.get(0);
         java.nio.IntBuffer least = stack.mallocInt(1);
         java.nio.IntBuffer greatest = stack.mallocInt(1);
         check(cuCtxGetStreamPriorityRange(least, greatest), "cuCtxGetStreamPriorityRange");
         int busyStreams = 24;
         long[] busy = new long[busyStreams];
         for (int i = 0; i < busyStreams; i++) {
            check(cuStreamCreateWithPriority(pointer, CU_STREAM_NON_BLOCKING, least.get(0)), "cuStreamCreateWithPriority");
            busy[i] = pointer.get(0);
         }

         check(cuStreamCreateWithPriority(pointer, CU_STREAM_NON_BLOCKING, greatest.get(0)), "cuStreamCreateWithPriority");
         long urgent = pointer.get(0);
         // ~1.9 GHz: 2,000,000 cycles ~1 ms on 2 blocks per launch; the urgent one ~0.05 ms on 8 blocks.
         long busyCycles = 2_000_000L;
         long urgentCycles = 100_000L;
         int rounds = 200;
         double[] latency = new double[rounds];
         for (int round = 0; round < rounds; round++) {
            try (MemoryStack frame = MemoryStack.stackPush()) {
               PointerBuffer busyArgs = frame.mallocPointer(2);
               busyArgs.put(0, MemoryUtil.memAddress(frame.longs(busyCycles)));
               busyArgs.put(1, frame.pointers(sink).address());
               // Each busy stream gets 3 launches queued, as lanes with work waiting.
               for (int k = 0; k < 3; k++) {
                  for (long stream : busy) {
                     check(cuLaunchKernel(spin, 2, 1, 1, 64, 1, 1, 0, stream, busyArgs, null), "cuLaunchKernel busy");
                  }
               }

               PointerBuffer urgentArgs = frame.mallocPointer(2);
               urgentArgs.put(0, MemoryUtil.memAddress(frame.longs(urgentCycles)));
               urgentArgs.put(1, frame.pointers(sink).address());
               long start = System.nanoTime();
               check(cuLaunchKernel(spin, 8, 1, 1, 64, 1, 1, 0, urgent, urgentArgs, null), "cuLaunchKernel urgent");
               check(cuStreamSynchronize(urgent), "cuStreamSynchronize");
               latency[round] = (System.nanoTime() - start) / 1.0e6;
               check(cuCtxSynchronize(), "cuCtxSynchronize");
            }
         }

         Arrays.sort(latency);
         double sum = 0;
         for (int i = 10; i < rounds; i++) {
            sum += latency[i];
         }

         System.out.printf(Locale.ROOT, "CUDA_DEVICE_MAX_CONNECTIONS %d (as set: %s): urgent launch to done, average %.3f ms, median %.3f, 90th %.3f "
            + "(next to %d busy streams of ~1 ms launches)%n", connections, System.getenv("CUDA_DEVICE_MAX_CONNECTIONS"), sum / (rounds - 10),
            latency[rounds / 2], latency[rounds * 9 / 10], busyStreams);
      }

      cuCtxDestroy(context);
   }
}
