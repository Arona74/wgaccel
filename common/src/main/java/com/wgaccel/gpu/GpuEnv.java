package com.wgaccel.gpu;

import java.nio.ByteBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WindowsLibrary;

/**
 * I3: CUDA_DEVICE_MAX_CONNECTIONS for this process before the driver starts (cuInit). The card has that many hardware
 * work queues for our streams (8 when unset); with more streams than queues, streams share one and a launch waits
 * behind another stream's work in it whatever its priority (the surface's batches: ~2 ms of work, ~10 ms from launch to
 * done in 0.9.63's run). Set through Windows' own SetEnvironmentVariableW (Java cannot change its environment), which
 * the driver reads. Only the order the card runs work in changes; the results are the same.
 */
public final class GpuEnv {
   private static volatile boolean done;
   /** What was done, for the log. */
   public static volatile String state = "not set yet";

   private GpuEnv() {
   }

   /** Sets the variable once, before the first cuInit; 0 or a value already set by the user leaves it alone. */
   public static synchronized void deviceConnections(int connections) {
      if (done) {
         return;
      }

      done = true;
      if (!Lwjgl.PRESENT) {
         // A dedicated server without LWJGL (the GPU stays off): its Platform class below is not there.
         state = "left alone (no LWJGL)";
         return;
      }

      String own = System.getenv("CUDA_DEVICE_MAX_CONNECTIONS");
      if (connections <= 0 || Platform.get() != Platform.WINDOWS || own != null) {
         state = own != null ? "left as the user set it (" + own + ")" : "left to the driver (8)";
         return;
      }

      try {
         WindowsLibrary kernel32 = new WindowsLibrary("kernel32");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            long function = kernel32.getFunctionAddress(stack.ASCII("SetEnvironmentVariableW"));
            if (function == 0L) {
               state = "not set (no SetEnvironmentVariableW)";
               return;
            }

            ByteBuffer name = stack.UTF16("CUDA_DEVICE_MAX_CONNECTIONS");
            ByteBuffer value = stack.UTF16(Integer.toString(connections));
            int ok = JNI.callPPI(MemoryUtil.memAddress(name), MemoryUtil.memAddress(value), function);
            state = ok != 0 ? "set to " + connections + " before the driver started" : "not set (SetEnvironmentVariableW failed)";
         }
      } catch (Throwable failure) {
         // Left unset: the driver's default.
         state = "not set: " + failure;
      }
   }
}
