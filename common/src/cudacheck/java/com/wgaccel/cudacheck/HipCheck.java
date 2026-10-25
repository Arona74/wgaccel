package com.wgaccel.cudacheck;

import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.HipSelfTest;
import com.wgaccel.gpu.SurfaceCodegen;
import java.util.Map;

/**
 * Runs the mod's GPU self-test on an AMD card outside the game (`gradlew.bat hipCheck`), then compiles the mod's fixed
 * kernel sources with hipRTC.
 */
public final class HipCheck {
   private HipCheck() {
   }

   public static void main(String[] args) {
      HipSelfTest.run().forEach(System.out::println);
      Map<String, String> sources = HipSelfTest.fixedSources();
      sources.put("structure terrain (DensityCodegen.BEARDIFIER)", DensityCodegen.BEARDIFIER);
      sources.put("surface prepass, warp of 32 (SurfaceCodegen.PREPASS_WARP)", SurfaceCodegen.PREPASS_WARP);
      System.out.println("The mod's fixed kernel sources through hipRTC:");
      HipSelfTest.compileModules(sources).forEach(System.out::println);
   }
}
