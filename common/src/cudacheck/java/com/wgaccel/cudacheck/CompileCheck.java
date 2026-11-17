package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compiles a saved CUDA source (a game-made one from the instance's logs) with NVRTC and the mod's exact options and loads it: `gradlew.bat cudaCompileCheck -Pcu=...`. */
public final class CompileCheck {
   private CompileCheck() {
   }

   public static void main(String[] args) throws Exception {
      Path file = Path.of(args[0]);
      long start = System.nanoTime();
      try (Gpu gpu = new Gpu(Files.readString(file), file.getFileName().toString())) {
         System.out.printf("%s: compiled and loaded in %.0f ms%n", file, (System.nanoTime() - start) / 1.0e6);
      }
   }
}
