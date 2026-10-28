package com.wgaccel.gpu;

import com.wgaccel.gpu.cuda.CU;
import com.wgaccel.gpu.cuda.HipCalls;
import java.util.Locale;

/**
 * Which GPU API the mod's {@link Gpu}, {@link GpuStreams} and {@link GpuApi} use: NVIDIA's CUDA, AMD's HIP
 * ({@link Hip}) or OpenCL ({@link OpenCl}, any vendor's). HIP's functions mirror CUDA's one for one, so the same
 * kernels and calls run on either; only the compiler and its options differ ({@link GpuSelfTest}, {@link HipSelfTest}).
 * OpenCL takes the same kernels translated ({@link OpenClSource}) and the same calls through {@link OpenCl}.
 * <p>
 * -Dwgaccel.gpuBackend=cuda, hip or opencl, or config gpuBackend, chooses; automatic is HIP on Linux with ROCm
 * ({@link Hip#linuxAutomatic}), otherwise OpenCL, on a separate card first,
 * then the most compute units ({@link GpuDevice}; Intel integrated graphics only when chosen; none: the GPU stays off).
 * Decided once, the first time the GPU is used.
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
      String chosen = asked();
      // CUDA and HIP are not on every Minecraft version yet (gpu/cuda in versions/<mc>): OpenCL there instead.
      if (chosen.equals("cuda") && !CU.PRESENT || chosen.equals("hip") && !HipCalls.PRESENT) {
         reason += "; " + chosen.toUpperCase(Locale.ROOT) + " is not available on this Minecraft version yet, OpenCL instead";
         return "opencl";
      }

      return chosen;
   }

   private static String asked() {
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

      if (HipCalls.PRESENT && Hip.linuxAutomatic()) {
         reason = "automatic: HIP, ROCm's runtime with hipRTC " + Hip.hiprtcState + " (Linux: faster than ROCm's OpenCL)";
         return "hip";
      }

      // OpenCL by default: every GPU driver has its compiler (nothing to install), and it ran as fast as CUDA and HIP on the cards
      // measured (RTX 5060 laptop 0:34 against CUDA 0:32, RX 9070 XT 0:46 as HIP). CUDA and HIP when chosen. Without a GPU
      // OpenCL can use, the GPU stays off (OpenCl.init refuses Intel integrated graphics chosen automatically).
      reason = OpenCl.automaticDevicePresent() ? "automatic: OpenCL, through the GPU driver's own compiler (" + OpenCl.survey() + ")"
         : OpenCl.devices().isEmpty() ? "automatic: OpenCL lists no GPU: " + OpenCl.survey()
         : "automatic: no GPU OpenCL can use (" + OpenCl.survey() + ")";
      return "opencl";
   }

   /** The backend's name, for the logs. */
   public static String name() {
      return HIP ? "HIP" : OPENCL ? "OpenCL" : "CUDA";
   }
}
