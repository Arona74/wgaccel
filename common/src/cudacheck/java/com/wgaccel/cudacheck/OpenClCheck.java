package com.wgaccel.cudacheck;

import com.wgaccel.gpu.DensityCodegen;
import com.wgaccel.gpu.HipSelfTest;
import com.wgaccel.gpu.OpenCl;
import com.wgaccel.gpu.OpenClSelfTest;
import com.wgaccel.gpu.SurfaceCodegen;
import java.util.Map;

/**
 * Runs the mod's GPU self-test through OpenCL outside the game (`gradlew.bat openclSelfCheck`, which starts Java with
 * -Dwgaccel.gpuBackend=opencl), then builds the mod's fixed kernel sources through the translation.
 */
public final class OpenClCheck {
   private OpenClCheck() {
   }

   public static void main(String[] args) {
      for (OpenCl.Device device : OpenCl.devices()) {
         System.out.println("OpenCL GPU: " + device.name() + " (" + device.vendor() + ", " + device.version() + ", driver " + device.driver() + ")"
            + (device.usable() ? "" : ": no " + device.missing()));
      }

      OpenClSelfTest.run().forEach(System.out::println);
      Map<String, String> sources = HipSelfTest.fixedSources();
      sources.put("structure terrain (DensityCodegen.BEARDIFIER)", DensityCodegen.BEARDIFIER);
      sources.put("surface prepass, warp of 32 (SurfaceCodegen.PREPASS_WARP)", SurfaceCodegen.PREPASS_WARP);
      System.out.println("The mod's fixed kernel sources through OpenCL:");
      OpenClSelfTest.compileModules(sources).forEach(System.out::println);
   }
}
