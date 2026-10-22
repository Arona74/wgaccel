package com.wgaccel.cudacheck;

import com.wgaccel.gpu.GpuSelfTest;
import java.nio.IntBuffer;
import org.lwjgl.cuda.NVRTC;
import org.lwjgl.system.MemoryStack;

/** Where the mod finds NVRTC without CUDA_PATH (as a game started before the toolkit's install): `gradlew.bat cudaNvrtcLookupCheck`. */
public final class NvrtcLookupCheck {
   private NvrtcLookupCheck() {
   }

   public static void main(String[] args) {
      System.out.println("CUDA_PATH seen by this process: " + System.getenv("CUDA_PATH"));
      GpuSelfTest.configureNvrtc();
      System.out.println("NVRTC: " + GpuSelfTest.nvrtcState);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer major = stack.mallocInt(1);
         IntBuffer minor = stack.mallocInt(1);
         NVRTC.nvrtcVersion(major, minor);
         System.out.println("NVRTC loaded: version " + major.get(0) + "." + minor.get(0));
      } catch (Throwable failure) {
         System.out.println("NVRTC failed: " + GpuSelfTest.describe(failure));
      }
   }
}
