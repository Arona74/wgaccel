package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.SurfaceInputs;

/** The surface input kernel (G3.3a) compiled outside the game with stand-in constants: `gradlew.bat cudaSurfaceInputsCheck`. */
public final class SurfaceInputsCheck {
   private SurfaceInputsCheck() {
   }

   public static void main(String[] args) throws Exception {
      String source = SurfaceInputs.source(0.3660254037844386, 0.21132486540518713, 1 << 16, 2 << 16, 0, "mc_normal(nd + 0, np + 0", 123L, 456L,
         "mc_normal(nd + 0, np + 0");
      long start = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "surface_inputs.cu")) {
         System.out.printf("surface inputs: compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
      }

      String depth = """
         double sd0 = mc_normal(nd + 0, np + 0, (double)x, 0.0, (double)z);
         Xrs rnd = mc_random_at(1LL, 2LL, x, 0, z);
         double sdn = (double)(mc_next_long(rnd) >> 11) * (double)1.110223E-16f;
         double sdv = sd0 * 2.75 + 3.0 + sdn * 0.25;
         int depth = (int)sdv;
         double secondary = mc_normal(nd + 0, np + 0, (double)x, 0.0, (double)z);
         """;
      String loop = com.wgaccel.gpu.SurfaceCodegen.loopSource(SurfaceInputs.helpers(0.3660254037844386, 0.21132486540518713, 1 << 16, 2 << 16, 0), depth,
         "__device__ __noinline__ int r0(const SCtx& c) { return sf_strata(c); }", "r0", -2097152, 1, 2, 3);
      start = System.nanoTime();
      try (Gpu gpu = new Gpu(loop, "surface_loop.cu")) {
         System.out.printf("surface loop: compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
      }

      String production = com.wgaccel.gpu.SurfaceCodegen.productionSource(SurfaceInputs.helpers(0.3660254037844386, 0.21132486540518713, 1 << 16,
         2 << 16, 0), depth, "__device__ const unsigned int mA[1] = {1u};\n__device__ __noinline__ int r0(const SCtx& c) { return sf_strata(c); }", "r0",
         -2097152, 1, 2, 3, "(sf_in(fb, mA, 1) ? 2 : 0)");
      start = System.nanoTime();
      try (Gpu gpu = new Gpu(com.wgaccel.gpu.SurfaceGpu.productionBatchSource(production), "surface.cu")) {
         System.out.printf("surface production (with batch entries): compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
      }

      String strata = """
         extern "C" __global__ void strataSelect(int minX, int minZ, int* indexOut, int* countOut, int* unsureOut) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             indexOut[t] = 0;
             countOut[t] = 0;
             unsureOut[t] = 0;
         }
         extern "C" __global__ void strataLayers(int minX, int minZ, const int* indexIn, const int* countIn, float* depthOut, int* stateOut,
                                                 int* unsureLayer) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i < 256) unsureLayer[i] = 0;
         }
         extern "C" __global__ void strataFinish(const int* heights, float* depthOut, const int* countIn, int* unsureOut, const int* unsureLayer) {
         }
         """;
      start = System.nanoTime();
      try (Gpu gpu = new Gpu(com.wgaccel.gpu.SurfaceGpu.strataBatchSource(strata), "surface_strata.cu")) {
         System.out.printf("surface strata (with batch entries): compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
      }

      java.nio.file.Path saved = java.nio.file.Path.of(System.getProperty("wgaccel.logs", "run/logs"),
         "wgaccel-surface.cu");
      if (java.nio.file.Files.exists(saved) && java.nio.file.Files.readString(saved).contains("surfacePrepassWarp(")) {
         start = System.nanoTime();
         try (Gpu gpu = new Gpu(com.wgaccel.gpu.SurfaceGpu.productionBatchSource(java.nio.file.Files.readString(saved)), "surface.cu")) {
            System.out.printf("the game's saved surface source (with batch entries): compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
         }
      }

      String functions = """
         __device__ __noinline__ double f0(const DCtx& c) { return dc_cell(c, 0); }
         __device__ __noinline__ double f1(const DCtx& c) { return (double)c.y * 0.01 + dc_cell(c, 1); }
         """;
      String biome = com.wgaccel.gpu.VanillaKernels.NOISE + com.wgaccel.gpu.VanillaKernels.DENSITY + functions
         + com.wgaccel.gpu.BiomeKernels.source(new String[] {"f0", "f0", "f1", "f0", "f1", "f0"});
      start = System.nanoTime();
      try (Gpu gpu = new Gpu(biome, "biome_check.cu")) {
         System.out.printf("biome check: compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
      }
   }
}
