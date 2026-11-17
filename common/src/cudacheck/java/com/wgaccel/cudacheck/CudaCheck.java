package com.wgaccel.cudacheck;

import com.wgaccel.gpu.GpuSelfTest;

/** Runs the mod's GPU self-test outside the game: `gradlew.bat cudaCheck`. */
public final class CudaCheck {
   private CudaCheck() {
   }

   public static void main(String[] args) {
      GpuSelfTest.run().forEach(System.out::println);
   }
}
