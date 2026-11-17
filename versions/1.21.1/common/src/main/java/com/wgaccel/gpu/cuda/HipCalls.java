package com.wgaccel.gpu.cuda;

import org.lwjgl.system.JNI;

/** The HIP call LWJGL's core has no shared helper for on every version (Hip calls the rest itself). */
public final class HipCalls {
   /** Whether HIP can be used on this version (GpuBackend chooses something else otherwise). */
   public static final boolean PRESENT = true;

   private HipCalls() {
   }

   /** hipModuleLaunchKernel at function, with the launch's arguments as CUDA's cuLaunchKernel takes them. */
   public static int launchKernel(long function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream,
         long params, long extra, long address) {
      return JNI.callPPPPI(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params, extra, address);
   }
}
