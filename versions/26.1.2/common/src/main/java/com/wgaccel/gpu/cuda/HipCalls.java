package com.wgaccel.gpu.cuda;

/**
 * The HIP call LWJGL's core has no shared helper for on every version (Hip calls the rest itself). Not on 26.1.2 yet:
 * LWJGL 3.4's core has no JNI helper with its arguments, so the mod uses OpenCL.
 */
public final class HipCalls {
   /** Whether HIP can be used on this version (GpuBackend chooses something else otherwise). */
   public static final boolean PRESENT = false;

   private HipCalls() {
   }

   /** hipModuleLaunchKernel at function, with the launch's arguments as CUDA's cuLaunchKernel takes them. */
   public static int launchKernel(long function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream,
         long params, long extra, long address) {
      throw new UnsupportedOperationException("HIP is not available on this Minecraft version");
   }
}
