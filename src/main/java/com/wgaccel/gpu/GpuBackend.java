package com.wgaccel.gpu;

import java.io.File;
import java.util.Locale;
import org.lwjgl.system.Platform;

/**
 * Which GPU API the mod's {@link Gpu}, {@link GpuStreams} and {@link GpuApi} use: NVIDIA's CUDA or AMD's HIP
 * ({@link Hip}). HIP's functions mirror CUDA's one for one, so the same kernels and calls run on either; only the
 * compiler and its options differ ({@link GpuSelfTest}, {@link HipSelfTest}).
 * <p>
 * -Dwgaccel.gpuBackend=cuda or hip chooses; without it, CUDA when NVIDIA's driver is installed and sees a card (so a PC
 * with an NVIDIA card and an AMD integrated one uses the NVIDIA card), else HIP when AMD's driver has a HIP runtime that sees a card,
 * else CUDA (whose self-test then says what is missing). Decided once, the first time the GPU is used.
 */
public final class GpuBackend {
   public static final boolean HIP = choose();
   /** How the backend was chosen, for the logs. */
   public static volatile String reason;
   /**
    * Whether the noise fill, biomes and surface time their GPU steps with events recorded between them (every 16th
    * run). Not under HIP: on a Radeon 680M (HIP 6.4, Windows) a run with events recorded between its kernels came back
    * wrong (the fill's codes of every timed batch differed from the game's, the surface's block indices were garbage, as
    * if a kernel no longer waited for the one before), and the same runs without them were identical (cudaFillBench, 2,840
    * chunks on 1 and 14 threads). Their reports then leave out the per-step times.
    */
   public static final boolean TIMING = !HIP;

   private GpuBackend() {
   }

   private static boolean choose() {
      String asked = System.getProperty("wgaccel.gpuBackend");
      if (asked != null) {
         reason = "-Dwgaccel.gpuBackend=" + asked;
         return asked.toLowerCase(Locale.ROOT).equals("hip");
      }

      boolean nvidia = nvidiaDriver();
      if (nvidia && cudaSeesCard()) {
         reason = "NVIDIA's driver is installed";
         return false;
      }

      if (Hip.runtimePresent()) {
         // 22 Oct 2026, RX 9070 XT PC: an NVIDIA driver left behind from a former card (nvcuda.dll, CUDA 12.9) with no
         // NVIDIA card in the machine; choosing CUDA by the file alone left the GPU parts off.
         reason = nvidia ? "NVIDIA's driver is installed but sees no card; AMD's HIP runtime sees one" : "no NVIDIA driver; AMD's HIP runtime sees a card";
         return true;
      }

      reason = "neither NVIDIA's driver nor a HIP runtime with a card was found";
      return false;
   }

   /**
    * Whether NVIDIA's driver library is installed, by looking for its file (loading it to find out would make LWJGL
    * print a warning in the log of every PC without it): nvcuda.dll in System32, or libcuda.so.1 in the usual Linux
    * folders.
    */
   private static boolean nvidiaDriver() {
      if (Platform.get() == Platform.WINDOWS) {
         return new File(System.getenv().getOrDefault("SystemRoot", "C:/Windows"), "System32/nvcuda.dll").isFile();
      }

      for (String folder : new String[] {"/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/lib/wsl/lib", "/usr/local/cuda/lib64"}) {
         if (new File(folder, "libcuda.so.1").isFile()) {
            return true;
         }
      }

      return false;
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
      return HIP ? "HIP" : "CUDA";
   }
}
