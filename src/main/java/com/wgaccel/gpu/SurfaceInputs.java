package com.wgaccel.gpu;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * GPU step G3.3a: the surface rules' inputs worked out on the GPU instead of taken from the game: each block's biome
 * (BiomeManager.getBiome's fuzzy zoom over the quart biome grid: zoom seed, LinearCongruentialGenerator, getFiddle),
 * its temperature (Biome.getTemperature: the base temperature and modifier, NeoForge's modified climate settings,
 * TEMPERATURE_NOISE above y 80, the FROZEN modifier's noises; vanilla's 2D SimplexNoise in PerlinSimplexNoise),
 * steepness (Worldgen Patches' version: the WORLD_SURFACE_WG height difference of the column's neighbours either way),
 * the surface depth (the surface noise and the SurfaceSystem's positional random nextDouble) and the surface
 * secondary. Kernel surfaceInputs, one thread per recorded rule call; the check compares each with the game's value.
 */
public final class SurfaceInputs {
   private final DensityCodegen noises = new DensityCodegen();
   private final DoubleArrayList simplexData = new DoubleArrayList();
   private final IntArrayList simplexPerm = new IntArrayList();
   private final float[] temperature;
   private final int[] modifier;
   private final String source;
   private final String helpers;
   private final String surfaceNoise;
   private final String secondaryNoise;
   private final long seedLo;
   private final long seedHi;

   public SurfaceInputs(Object surfaceSystem, Registry<Biome> biomes, long zoomSeed) throws ReflectiveOperationException {
      int count = biomes.size();
      this.temperature = new float[Math.max(1, count)];
      this.modifier = new int[Math.max(1, count)];
      for (Biome biome : biomes) {
         int id = biomes.getId(biome);
         Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
         this.temperature[id] = climate.temperature();
         this.modifier[id] = climate.temperatureModifier() == Biome.TemperatureModifier.FROZEN ? 1 : 0;
      }

      String surfaceNoise = this.noises.normalNoise(SurfaceCodegen.field(surfaceSystem, "surfaceNoise"));
      String secondaryNoise = this.noises.normalNoise(SurfaceCodegen.field(surfaceSystem, "surfaceSecondaryNoise"));
      Object random = SurfaceCodegen.field(surfaceSystem, "noiseRandom");
      if (!random.getClass().getName().endsWith("XoroshiroPositionalRandomFactory")) {
         throw new DensityCodegen.Unsupported("surface positional random " + random.getClass().getName());
      }

      long seedLo = (Long)SurfaceCodegen.field(random, "seedLo");
      long seedHi = (Long)SurfaceCodegen.field(random, "seedHi");
      int temperatureNoise = this.packSimplex(staticField(Biome.class, "TEMPERATURE_NOISE"));
      int frozenNoise = this.packSimplex(staticField(Biome.class, "FROZEN_TEMPERATURE_NOISE"));
      int infoNoise = this.packSimplex(staticField(Biome.class, "BIOME_INFO_NOISE"));
      Class<?> simplex = Class.forName("net.minecraft.world.level.levelgen.synth.SimplexNoise");
      double f2 = (Double)staticField(simplex, "F2");
      double g2 = (Double)staticField(simplex, "G2");
      this.helpers = helpers(f2, g2, frozenNoise, infoNoise, temperatureNoise);
      this.surfaceNoise = surfaceNoise.replace("c.nd", "nd").replace("c.np", "np");
      this.secondaryNoise = secondaryNoise.replace("c.nd", "nd").replace("c.np", "np");
      this.seedLo = seedLo;
      this.seedHi = seedHi;
      this.source = source(f2, g2, frozenNoise, infoNoise, temperatureNoise, surfaceNoise.replace("c.nd", "nd").replace("c.np", "np"), seedLo, seedHi,
         secondaryNoise.replace("c.nd", "nd").replace("c.np", "np"));
   }

   /** The CUDA source with its constants (also built by the standalone check with stand-in values). */
   public static String source(double f2, double g2, int frozenNoise, int infoNoise, int temperatureNoise, String surfaceNoise, long seedLo, long seedHi,
         String secondaryNoise) {
      return VanillaKernels.NOISE + VanillaKernels.RANDOM + helpers(f2, g2, frozenNoise, infoNoise, temperatureNoise) + """
         extern "C" __global__ void surfaceInputs(const int* calls, int count, const int* grid, const int* g, long long zoomSeed, const int* heights,
                                                  const float* temperature, const int* modifier, const double* sd, const int* sp, const double* nd,
                                                  const int* np, int* out, double* secondary) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= count) return;
             const int* r = calls + t * 12;
             int x = r[0];
             int y = r[1];
             int z = r[2];
             int biome = bm_biome(grid, g, zoomSeed, x, y, z);
             SxData sc;
             sc.nd = sd;
             sc.np = sp;
             int cold = biome >= 0 ? bm_cold(sc, temperature, modifier, biome, x, y, z) : -1;
             int lx = x & 15;
             int lz = z & 15;
             int hn = heights[lx * 16 + max(lz - 1, 0)];
             int hs = heights[lx * 16 + min(lz + 1, 15)];
             int steep;
             if (abs(hs - hn) >= 4) {
                 steep = 1;
             } else {
                 int hw = heights[max(lx - 1, 0) * 16 + lz];
                 int he = heights[min(lx + 1, 15) * 16 + lz];
                 steep = abs(hw - he) >= 4 ? 1 : 0;
             }
             double d0 = %1$s, (double)x, 0.0, (double)z);
             Xrs rnd = mc_random_at(%2$dLL, %3$dLL, x, 0, z);
             double next = (double)(mc_next_long(rnd) >> 11) * (double)1.110223E-16f;
             double v = d0 * 2.75 + 3.0 + next * 0.25;
             int depth = isnan(v) ? 0 : (v >= 2147483647.0 ? 2147483647 : (v <= -2147483648.0 ? (-2147483647 - 1) : (int)v));
             out[t * 4] = biome;
             out[t * 4 + 1] = cold;
             out[t * 4 + 2] = steep;
             out[t * 4 + 3] = depth;
             secondary[t] = %4$s, (double)x, 0.0, (double)z);
         }
         """.formatted(surfaceNoise, seedLo, seedHi, secondaryNoise);
   }

   /**
    * The input helpers (vanilla SimplexNoise and PerlinSimplexNoise, BiomeManager's zoom, Biome.getTemperature) without a
    * kernel, for the column loop's module too ({@link SurfaceCodegen#loopSource}).
    */
   public static String helpers(double f2, double g2, int frozenNoise, int infoNoise, int temperatureNoise) {
      return """
         struct SxData {
             const double* nd;
             const int* np;
         };
         __device__ const int SX_GRADIENT[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1, 0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1,
                                                 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};
         __device__ __forceinline__ double sx_corner(int g, double x, double y, double z, double r) {
             double d1 = r - x * x - y * y - z * z;
             if (d1 < 0.0) return 0.0;
             d1 *= d1;
             return d1 * d1 * ((double)SX_GRADIENT[g * 3] * x + (double)SX_GRADIENT[g * 3 + 1] * y + (double)SX_GRADIENT[g * 3 + 2] * z);
         }
         // Vanilla SimplexNoise.getValue(x, y), operation for operation.
         __device__ double sx_value(const int* p, double x, double y) {
             double d0 = (x + y) * %1$s;
             int i = mc_floor(x + d0);
             int j = mc_floor(y + d0);
             double d1 = (double)(i + j) * %2$s;
             double d2 = (double)i - d1;
             double d3 = (double)j - d1;
             double d4 = x - d2;
             double d5 = y - d3;
             int k;
             int l;
             if (d4 > d5) {
                 k = 1;
                 l = 0;
             } else {
                 k = 0;
                 l = 1;
             }
             double d6 = d4 - (double)k + %2$s;
             double d7 = d5 - (double)l + %2$s;
             double d8 = d4 - 1.0 + 2.0 * %2$s;
             double d9 = d5 - 1.0 + 2.0 * %2$s;
             int i1 = i & 0xFF;
             int j1 = j & 0xFF;
             int k1 = p[(i1 + p[j1 & 0xFF]) & 0xFF] %% 12;
             int l1 = p[(i1 + k + p[(j1 + l) & 0xFF]) & 0xFF] %% 12;
             int i2 = p[(i1 + 1 + p[(j1 + 1) & 0xFF]) & 0xFF] %% 12;
             double d10 = sx_corner(k1, d4, d5, 0.0, 0.5);
             double d11 = sx_corner(l1, d6, d7, 0.0, 0.5);
             double d12 = sx_corner(i2, d8, d9, 0.0, 0.5);
             return 70.0 * (d10 + d11 + d12);
         }
         // PerlinSimplexNoise.getValue(x, y, false): D = [levels, input factor, value factor, then present per level], P = 512 per level.
         __device__ double ps_value(const double* D, const int* P, double x, double y) {
             int n = (int)D[0];
             double d0 = 0.0;
             double d1 = D[1];
             double d2 = D[2];
             for (int i = 0; i < n; i++) {
                 if (D[3 + i] != 0.0) d0 += sx_value(P + i * 512, x * d1 + 0.0, y * d1 + 0.0) * d2;
                 d1 /= 2.0;
                 d2 *= 2.0;
             }
             return d0;
         }
         __device__ __forceinline__ long long lcg_next(long long a, long long b) {
             unsigned long long l = (unsigned long long)a;
             l *= l * 6364136223846793005ULL + 1442695040888963407ULL;
             return (long long)(l + (unsigned long long)b);
         }
         __device__ __forceinline__ double bm_fiddle(long long seed) {
             long long v = seed >> 24;
             long long m = ((v %% 1024) + 1024) %% 1024;
             double d0 = (double)m / 1024.0;
             return (d0 - 0.5) * 0.9;
         }
         __device__ double bm_distance(long long seed, int x, int y, int z, double xn, double yn, double zn) {
             long long i = lcg_next(seed, (long long)x);
             i = lcg_next(i, (long long)y);
             i = lcg_next(i, (long long)z);
             i = lcg_next(i, (long long)x);
             i = lcg_next(i, (long long)y);
             i = lcg_next(i, (long long)z);
             double d0 = bm_fiddle(i);
             i = lcg_next(i, seed);
             double d1 = bm_fiddle(i);
             i = lcg_next(i, seed);
             double d2 = bm_fiddle(i);
             double a = zn + d2;
             double b = yn + d1;
             double c = xn + d0;
             return a * a + b * b + c * c;
         }
         // BiomeManager.getBiome: the quart grid holds the region's noise biome ids from (gx, gy, gz), size sx x sy x sz (y clamped).
         __device__ int bm_biome(const int* grid, const int* g, long long seed, int x, int y, int z) {
             int i = x - 2;
             int j = y - 2;
             int k = z - 2;
             int l = i >> 2;
             int i1 = j >> 2;
             int j1 = k >> 2;
             double d0 = (double)(i & 3) / 4.0;
             double d1 = (double)(j & 3) / 4.0;
             double d2 = (double)(k & 3) / 4.0;
             int k1 = 0;
             double d3 = __longlong_as_double(0x7ff0000000000000LL);
             for (int l1 = 0; l1 < 8; l1++) {
                 bool f = (l1 & 4) == 0;
                 bool f1 = (l1 & 2) == 0;
                 bool f2 = (l1 & 1) == 0;
                 double d7 = bm_distance(seed, f ? l : l + 1, f1 ? i1 : i1 + 1, f2 ? j1 : j1 + 1, f ? d0 : d0 - 1.0, f1 ? d1 : d1 - 1.0, f2 ? d2 : d2 - 1.0);
                 if (d3 > d7) {
                     k1 = l1;
                     d3 = d7;
                 }
             }
             int qx = ((k1 & 4) == 0 ? l : l + 1) - g[0];
             int qy = min(max(((k1 & 2) == 0 ? i1 : i1 + 1) - g[1], 0), g[4] - 1);
             int qz = ((k1 & 1) == 0 ? j1 : j1 + 1) - g[2];
             if (qx < 0 || qx >= g[3] || qz < 0 || qz >= g[5]) return -2;
             return grid[(qx * g[5] + qz) * g[4] + qy];
         }
         // Biome.getTemperature (FROZEN modifier, then above y 80 the height adjustment) < 0.15f: cold enough to snow.
         __device__ int bm_cold(const SxData& c, const float* temperature, const int* modifier, int biome, int x, int y, int z) {
             float f = temperature[biome];
             if (modifier[biome] == 1) {
                 double d0 = ps_value(c.nd + %3$d, c.np + %4$d, (double)x * 0.05, (double)z * 0.05) * 7.0;
                 double d1 = ps_value(c.nd + %5$d, c.np + %6$d, (double)x * 0.2, (double)z * 0.2);
                 double d2 = d0 + d1;
                 if (d2 < 0.3) {
                     double d3 = ps_value(c.nd + %5$d, c.np + %6$d, (double)x * 0.09, (double)z * 0.09);
                     if (d3 < 0.8) f = 0.2f;
                 }
             }
             if (y > 80) {
                 float f1 = (float)(ps_value(c.nd + %7$d, c.np + %8$d, (double)((float)x / 8.0f), (double)((float)z / 8.0f)) * 8.0);
                 f = f - (f1 + (float)y - 80.0f) * 0.05f / 40.0f;
             }
             return f >= 0.15f ? 0 : 1;
         }
         """.formatted(DensityCodegen.lit(f2), DensityCodegen.lit(g2), frozenNoise >> 16, frozenNoise & 0xFFFF, infoNoise >> 16, infoNoise & 0xFFFF,
            temperatureNoise >> 16, temperatureNoise & 0xFFFF);
   }

   private static Object staticField(Class<?> type, String name) throws ReflectiveOperationException {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
   }

   /** Packs a PerlinSimplexNoise (useOffsets false); returns its data offset << 16 | permutation offset (in 512s... as ints). */
   private int packSimplex(Object noise) throws ReflectiveOperationException {
      int data = this.simplexData.size();
      int perm = this.simplexPerm.size();
      if (data > 0xFFFF || perm > 0xFFFF) {
         throw new IllegalStateException("too many simplex noises");
      }

      Object[] levels = (Object[])SurfaceCodegen.field(noise, "noiseLevels");
      this.simplexData.add(levels.length);
      this.simplexData.add((Double)SurfaceCodegen.field(noise, "highestFreqInputFactor"));
      this.simplexData.add((Double)SurfaceCodegen.field(noise, "highestFreqValueFactor"));
      for (Object level : levels) {
         this.simplexData.add(level == null ? 0.0 : 1.0);
      }

      for (Object level : levels) {
         int[] p = level == null ? new int[512] : (int[])SurfaceCodegen.field(level, "p");
         for (int i = 0; i < 512; i++) {
            this.simplexPerm.add(p[i]);
         }
      }

      return data << 16 | perm;
   }

   public String source() {
      return this.source;
   }

   /** The helpers with this world's constants (for the column loop's module). */
   public String helpers() {
      return this.helpers;
   }

   /** The surface depth and secondary as CUDA expressions over x, z and the arrays nd, np (this object's noise data). */
   public String surfaceDepthCode() {
      return """
         double sd0 = %s, (double)x, 0.0, (double)z);
         Xrs rnd = mc_random_at(%dLL, %dLL, x, 0, z);
         double sdn = (double)(mc_next_long(rnd) >> 11) * (double)1.110223E-16f;
         double sdv = sd0 * 2.75 + 3.0 + sdn * 0.25;
         int depth = isnan(sdv) ? 0 : (sdv >= 2147483647.0 ? 2147483647 : (sdv <= -2147483648.0 ? (-2147483647 - 1) : (int)sdv));
         double secondary = %s, (double)x, 0.0, (double)z);
         """.formatted(this.surfaceNoise, this.seedLo, this.seedHi, this.secondaryNoise);
   }

   public double[] noiseData() {
      return this.noises.noiseData();
   }

   public int[] permutations() {
      return this.noises.permutations();
   }

   public double[] simplexData() {
      return this.simplexData.toDoubleArray();
   }

   public int[] simplexPermutations() {
      return this.simplexPerm.toIntArray();
   }

   public float[] temperature() {
      return this.temperature;
   }

   public int[] modifier() {
      return this.modifier;
   }
}
