package com.wgaccel.gpu;

import java.util.Locale;

/**
 * Which GPU API the mod's {@link Gpu}, {@link GpuStreams} and {@link GpuApi} use: NVIDIA's CUDA, AMD's HIP
 * ({@link Hip}) or OpenCL ({@link OpenCl}, for Intel's GPUs). HIP's functions mirror CUDA's one for one, so the same
 * kernels and calls run on either; only the compiler and its options differ ({@link GpuSelfTest}, {@link HipSelfTest}).
 * OpenCL takes the same kernels translated ({@link OpenClSource}) and the same calls through {@link OpenCl}.
 * <p>
 * -Dwgaccel.gpuBackend=cuda, hip or opencl chooses; without it, CUDA when NVIDIA's driver is installed and sees a card (so
 * a PC with an NVIDIA card and an AMD or Intel integrated one uses the NVIDIA card), else HIP when AMD's driver has a HIP
 * runtime that sees a card, else OpenCL when it has a separate GPU the mod can use (an integrated one only when chosen),
 * else CUDA (whose self-test then says what is missing). Decided once, the first time the GPU is used.
 */
public final class GpuBackend {
   private static final String CHOSEN = choose();
   public static final boolean HIP = CHOSEN.equals("hip");
   public static final boolean OPENCL = CHOSEN.equals("opencl");
   /** How the backend was chosen, for the logs. */
   public static volatile String reason;
   /**
    * Whether the noise fill, biomes and surface time their GPU steps with events recorded between them (every 16th
    * run). Not under HIP: on a Radeon 680M (HIP 6.4, Windows) a run with events recorded between its kernels came back
    * wrong (the fill's codes of every timed batch differed from the game's, the surface's block indices were garbage, as
    * if a kernel no longer waited for the one before), and the same runs without them were identical (cudaFillBench, 2,840
    * chunks on 1 and 14 threads). Their reports then leave out the per-step times. Not under OpenCL either (its events
    * time nothing without a profiling queue).
    */
   public static final boolean TIMING = !HIP && !OPENCL;

   private GpuBackend() {
   }

   private static String choose() {
      String asked = System.getProperty("wgaccel.gpuBackend");
      if (asked != null) {
         reason = "-Dwgaccel.gpuBackend=" + asked;
         return asked.toLowerCase(Locale.ROOT);
      }

      String configured = GpuDevice.configuredBackend;
      if (configured != null && !configured.equalsIgnoreCase("AUTO")) {
         reason = "config gpuBackend = " + configured;
         return configured.toLowerCase(Locale.ROOT);
      }

      boolean nvidia = GpuInventory.nvidiaDriver();
      if (nvidia && cudaSeesCard()) {
         reason = "NVIDIA's driver is installed";
         return "cuda";
      }

      if (Hip.runtimePresent()) {
         // 22 Oct 2026, RX 9070 XT PC: an NVIDIA driver left behind from a former card (nvcuda.dll, CUDA 12.9) with no
         // NVIDIA card in the machine; choosing CUDA by the file alone left the GPU parts off.
         reason = nvidia ? "NVIDIA's driver is installed but sees no card; AMD's HIP runtime sees one" : "no NVIDIA driver; AMD's HIP runtime sees a card";
         return "hip";
      }

      if (OpenCl.automaticDevicePresent()) {
         reason = "neither CUDA nor HIP sees a card; OpenCL has a separate GPU the mod can use";
         return "opencl";
      }

      reason = "neither NVIDIA's driver, a HIP runtime nor a separate OpenCL GPU with what the mod needs was found (an integrated"
         + " GPU is used only when chosen: slower than the CPU on the one tested)";
      return "cuda";
   }

   /**
    * Whether NVIDIA's driver, once its file is known to be there, sees a card (cuInit fails with CUDA_ERROR_NO_DEVICE
    * when it doesn't). Runs after {@link GpuEnv#deviceConnections}, which has to come before the first cuInit.
    */
   private static boolean cudaSeesCard() {
      try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
         java.nio.IntBuffer count = stack.mallocInt(1);
         return org.lwjgl.cuda.CU.cuInit(0) == org.lwjgl.cuda.CU.CUDA_SUCCESS && org.lwjgl.cuda.CU.cuDeviceGetCount(count) == org.lwjgl.cuda.CU.CUDA_SUCCESS
            && count.get(0) > 0;
      } catch (Throwable missing) {
         return false;
      }
   }

   /** The backend's name, for the logs. */
   public static String name() {
      return HIP ? "HIP" : OPENCL ? "OpenCL" : "CUDA";
   }
}
