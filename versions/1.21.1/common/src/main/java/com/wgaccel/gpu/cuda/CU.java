package com.wgaccel.gpu.cuda;

/**
 * The CUDA driver calls the mod makes, named as LWJGL's (the shared code calls them through this class, as each
 * Minecraft version has its own). On 1.21.1 they are LWJGL's own (its CUDA module, shaded into the jar), inherited.
 */
public class CU extends org.lwjgl.cuda.CU {
   /** Whether CUDA can be used on this version (GpuBackend chooses something else otherwise). */
   public static final boolean PRESENT = true;

   protected CU() {
   }
}
