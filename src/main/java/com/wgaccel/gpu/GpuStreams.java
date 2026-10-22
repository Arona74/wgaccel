package com.wgaccel.gpu;

import static org.lwjgl.cuda.CU.*;

import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * The lanes' CUDA streams, with priorities (config gpuStreamPriorities): work a generation thread waits for (the
 * surface, the fill and the biomes of the chunk in its step, and their batch threads) on the card's highest priority,
 * the fill look-ahead's work (nobody waits for it yet) on its lowest (the biome look-ahead's is needed at once, so it
 * stays on the highest; {@link #soon} gives the middle), so the card starts waiting work first as soon as it has room (run of 0.9.46: ~12 ms of GPU waiting per surface for ~3 ms of
 * kernels, behind the look-ahead's launches of 16 chunks). The results are the same either way: only the order the
 * card runs the launches in changes.
 */
public final class GpuStreams {
   /** The calling thread's tier: 0 waited for (highest), 1 needed soon (the middle), 2 background (lowest). */
   private static final ThreadLocal<Integer> TIER = ThreadLocal.withInitial(() -> 0);
   public static volatile boolean priorities = true;
   /** Config gpuErosionPriority: the GPU thread's erosion filter on its own stream of the highest priority (set before the GPU starts). */
   public static volatile boolean erosionStream = true;
   /** Config gpuServiceThreads: how many threads run the tile filters' GPU work (set before the GPU starts). */
   public static volatile int serviceThreads = 3;

   private GpuStreams() {
   }

   /** Called by a look-ahead thread at its start: the lanes it makes get the lowest priority. */
   public static void background() {
      TIER.set(2);
   }

   /**
    * Called by the biome look-ahead's threads: the middle priority (a chunk's biome step follows its structure references
    * at once, so its results are needed soon; the fill's look-ahead has the whole biome step more).
    */
   public static void soon() {
      TIER.set(1);
   }

   /** A new non-blocking stream in the current context, its priority by the calling thread. */
   public static long create() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         if (GpuBackend.HIP) {
            IntBuffer least = stack.mallocInt(1);
            IntBuffer greatest = stack.mallocInt(1);
            if (priorities && Hip.hipDeviceGetStreamPriorityRange(least, greatest) == Hip.hipSuccess) {
               int tier = TIER.get();
               int priority = tier == 0 ? greatest.get(0) : tier == 2 ? least.get(0) : (least.get(0) + greatest.get(0)) / 2;
               Hip.check(Hip.hipStreamCreateWithPriority(pointer, Hip.hipStreamNonBlocking, priority), "hipStreamCreateWithPriority");
            } else {
               Hip.check(Hip.hipStreamCreateWithFlags(pointer, Hip.hipStreamNonBlocking), "hipStreamCreateWithFlags");
            }

            return pointer.get(0);
         }

         if (priorities) {
            IntBuffer least = stack.mallocInt(1);
            IntBuffer greatest = stack.mallocInt(1);
            if (cuCtxGetStreamPriorityRange(least, greatest) == CUDA_SUCCESS) {
               // Greater priorities are lower numbers (typically 0 to -5).
               int tier = TIER.get();
               int priority = tier == 0 ? greatest.get(0) : tier == 2 ? least.get(0) : (least.get(0) + greatest.get(0)) / 2;
               GpuSelfTest.check(cuStreamCreateWithPriority(pointer, CU_STREAM_NON_BLOCKING, priority), "cuStreamCreateWithPriority");
               return pointer.get(0);
            }
         }

         GpuSelfTest.check(cuStreamCreate(pointer, CU_STREAM_NON_BLOCKING), "cuStreamCreate");
         return pointer.get(0);
      }
   }
}
