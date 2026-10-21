package com.wgaccel.gpu;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GPU step G3.3b: FreeTerraForged's StrataRule.Source.initBuffer per block column on the GPU: the selector noise picks
 * the layer set ((int)(selector * sets), at most the last), each layer's depth noise (its own seed) is summed in floats,
 * and from the column's WORLD_SURFACE_WG height down each layer's depth is height minus Math.round(depth / sum * height)
 * in turn. The noises are FreeTerraForged's own trees through {@link NoiseCodegen} (bit-identical); a column where a
 * pow result lay too near a float rounding boundary is marked unsure (left to the CPU). Kernel strataColumns.
 */
public final class StrataGpu {
   private final String source;
   private final String productionSource;
   private final int maxLayers;

   public StrataGpu(Object strataSource, SurfaceCodegen states) throws ReflectiveOperationException {
      NoiseCodegen codegen = new NoiseCodegen(pairs(NoiseUtil.GRAD_2D_24), pairs(NoiseUtil.CELL_2D), (float[])noiseUtil("SIN"), (Float)noiseUtil("radToIndex"));
      String selector = codegen.function(SurfaceCodegen.field(strataSource, "selector"));
      List<?> sets = (List<?>)SurfaceCodegen.field(strataSource, "strata");
      StringBuilder kernel = new StringBuilder();
      int max = 1;
      StringBuilder cases = new StringBuilder();
      StringBuilder counts = new StringBuilder();
      StringBuilder layerCases = new StringBuilder();
      for (int s = 0; s < sets.size(); s++) {
         List<?> layers = (List<?>)sets.get(s);
         max = Math.max(max, layers.size());
         cases.append("        case ").append(s).append(": n = ").append(layers.size()).append(";\n");
         counts.append("        case ").append(s).append(": n = ").append(layers.size()).append("; break;\n");
         layerCases.append("        case ").append(s).append(":\n            switch (l) {\n");
         for (int l = 0; l < layers.size(); l++) {
            Object layer = layers.get(l);
            String depth = codegen.function(record(layer, "depth"));
            int seed = (Integer)record(layer, "seed");
            int state = states.state((BlockState)record(layer, "material"));
            cases.append("            d[").append(l).append("] = ").append(depth).append("(fx, fz, ").append(seed).append(", u); m[").append(l).append("] = ")
               .append(state).append(";\n");
            layerCases.append("                case ").append(l).append(": d = ").append(depth).append("(fx, fz, ").append(seed).append(", u); m = ").append(state)
               .append("; break;\n");
         }

         cases.append("            break;\n");
         layerCases.append("            }\n            break;\n");
      }

      this.maxLayers = max;
      kernel.append("""
         // Java's Math.round(float) (round half up; exact as v - floor(v) has no rounding error) and (int) of a float.
         __device__ __forceinline__ int st_round(float v) {
             if (isnan(v)) return 0;
             float f = floorf(v);
             float r = v - f >= 0.5f ? f + 1.0f : f;
             if (r >= 2147483647.0f) return 2147483647;
             if (r <= -2147483648.0f) return (-2147483647 - 1);
             return (int)r;
         }
         __device__ __forceinline__ int st_f2i(float v) {
             if (isnan(v)) return 0;
             if (v >= 2147483647.0f) return 2147483647;
             if (v <= -2147483648.0f) return (-2147483647 - 1);
             return (int)v;
         }
         extern "C" __global__ void strataColumns(const int* heights, int minX, int minZ, float* depthOut, int* stateOut, int* countOut, int* unsureOut) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             int lx = t / 16;
             int lz = t - lx * 16;
             float fx = (float)(minX + lx);
             float fz = (float)(minZ + lz);
             int u = 0;
             float sel = %s(fx, fz, 0, u);
             int index = st_f2i(sel * (float)%d);
             index = min(%d, index);
             float d[%d];
             int m[%d];
             int n = 0;
             switch (index) {
         """.formatted(selector, sets.size(), sets.size() - 1, max, max));
      kernel.append(cases);
      kernel.append("""
                 default: n = -1;
             }
             countOut[t] = n;
             unsureOut[t] = u;
             if (n <= 0) return;
             int height = heights[t];
             float sum = 0.0f;
             for (int i = 0; i < n; i++) sum += d[i];
             int y = height;
             for (int i = 0; i < n; i++) {
                 y -= st_round(d[i] / sum * (float)height);
                 depthOut[t * %1$d + i] = (float)y;
                 stateOut[t * %1$d + i] = m[i];
             }
         }
         """.formatted(max));
      this.source = codegen.source(List.of()) + kernel;
      // Production (G3.4): the same in three steps so the layers' depth noises (the bulk) run one thread per column and
      // layer instead of one per column: strataSelect (per column: the selector, the set, its layer count and the
      // selector's unsure flag), strataLayers (per column and layer: its depth and block), strataFinish (per column:
      // the unsure flags joined, then the sum and the depths from the top as initBuffer makes them, in place).
      String helpers = kernel.substring(0, kernel.indexOf("extern \"C\""));
      String production = helpers + """
         extern "C" __global__ void strataSelect(int minX, int minZ, int* indexOut, int* countOut, int* unsureOut) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             float fx = (float)(minX + t / 16);
             float fz = (float)(minZ + t %% 16);
             int u = 0;
             float sel = %1$s(fx, fz, 0, u);
             int index = st_f2i(sel * (float)%2$d);
             index = min(%3$d, index);
             int n = -1;
             switch (index) {
         %4$s
             }
             indexOut[t] = index;
             countOut[t] = n;
             unsureOut[t] = u;
         }
         extern "C" __global__ void strataLayers(int minX, int minZ, const int* indexIn, const int* countIn, float* depthOut, int* stateOut, int* unsureLayer) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= 256 * %5$d) return;
             int t = i / %5$d;
             int l = i - t * %5$d;
             if (l >= countIn[t]) {
                 unsureLayer[i] = 0;
                 return;
             }
             float fx = (float)(minX + t / 16);
             float fz = (float)(minZ + t %% 16);
             int u = 0;
             float d = 0.0f;
             int m = 0;
             switch (indexIn[t]) {
         %6$s
             }
             depthOut[i] = d;
             stateOut[i] = m;
             unsureLayer[i] = u;
         }
         extern "C" __global__ void strataFinish(const int* heights, float* depthOut, const int* countIn, int* unsureOut, const int* unsureLayer) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             int n = countIn[t];
             int u = unsureOut[t];
             for (int l = 0; l < %5$d; l++) u |= unsureLayer[t * %5$d + l];
             unsureOut[t] = u;
             if (n <= 0) return;
             int height = heights[t];
             float* d = depthOut + t * %5$d;
             float sum = 0.0f;
             for (int i = 0; i < n; i++) sum += d[i];
             int y = height;
             for (int i = 0; i < n; i++) {
                 y -= st_round(d[i] / sum * (float)height);
                 d[i] = (float)y;
             }
         }
         """.formatted(selector, sets.size(), sets.size() - 1, counts, max, layerCases);
      this.productionSource = codegen.source(List.of()) + production;
   }

   /** The production kernels strataSelect, strataLayers and strataFinish (the same results as strataColumns). */
   public String productionSource() {
      return this.productionSource;
   }

   public String source() {
      return this.source;
   }

   public int maxLayers() {
      return this.maxLayers;
   }

   private static Object record(Object target, String name) throws ReflectiveOperationException {
      Method accessor = target.getClass().getDeclaredMethod(name);
      accessor.setAccessible(true);
      return accessor.invoke(target);
   }

   private static float[] pairs(NoiseUtil.Vec2f[] table) {
      float[] values = new float[table.length * 2];
      for (int i = 0; i < table.length; i++) {
         values[i * 2] = table[i].x();
         values[i * 2 + 1] = table[i].y();
      }

      return values;
   }

   private static Object noiseUtil(String name) throws ReflectiveOperationException {
      Field field = NoiseUtil.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
   }
}
