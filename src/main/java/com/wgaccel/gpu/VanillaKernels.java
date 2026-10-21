package com.wgaccel.gpu;

/**
 * CUDA C versions of vanilla Minecraft's 3D noise (doubles), for the 3D fill on the GPU (step G2), written operation
 * for operation like its Java and compiled with {@link GpuSelfTest#EXACT_OPTIONS}: Mth.floor and lfloor, smoothstep,
 * lerp, lerp2, lerp3, ImprovedNoise (the gradient table, the byte permutation), PerlinNoise (octaves with wrap) and
 * NormalNoise (two PerlinNoises, the second at 1.0181268882175227 times the coordinates).
 * <p>
 * A NormalNoise is packed as doubles D (from its offset): valueFactor, then its two PerlinNoises, each as
 * lowestFreqInputFactor, lowestFreqValueFactor, the octave count n, then n times (amplitude, xo, yo, zo, present);
 * and ints P (from its offset): 256 permutation values per octave, the first PerlinNoise's octaves then the second's.
 */
public final class VanillaKernels {
   public static final String NOISE = """
      __device__ __forceinline__ int mc_floor(double v) {
          int i = __double2int_rz(v);
          return v < (double)i ? i - 1 : i;
      }
      __device__ __forceinline__ long long mc_lfloor(double v) {
          long long i = __double2ll_rz(v);
          return v < (double)i ? i - 1 : i;
      }
      __device__ __forceinline__ double mc_smoothstep(double t) {
          return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
      }
      __device__ __forceinline__ double mc_lerp(double delta, double a, double b) {
          return a + delta * (b - a);
      }
      __device__ __forceinline__ double mc_lerp2(double dx, double dy, double a, double b, double c, double d) {
          return mc_lerp(dy, mc_lerp(dx, a, b), mc_lerp(dx, c, d));
      }
      __device__ __forceinline__ double mc_lerp3(double dx, double dy, double dz, double v0, double v1, double v2, double v3, double v4, double v5,
                                                  double v6, double v7) {
          return mc_lerp(dz, mc_lerp2(dx, dy, v0, v1, v2, v3), mc_lerp2(dx, dy, v4, v5, v6, v7));
      }
      __constant__ int MC_GRADIENT[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1, 0, 1, 1, 0, -1, 1,
                                          0, 1, -1, 0, -1, -1, 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};
      __device__ __forceinline__ double mc_grad_dot(int hash, double x, double y, double z) {
          const int* g = MC_GRADIENT + (hash & 15) * 3;
          return (double)g[0] * x + (double)g[1] * y + (double)g[2] * z;
      }
      // ImprovedNoise.noise(x, y, z) with yScale and yMax 0 (what PerlinNoise.getValue(x, y, z) asks for).
      __device__ double mc_improved(const int* p, double xo, double yo, double zo, double x, double y, double z) {
          double d0 = x + xo;
          double d1 = y + yo;
          double d2 = z + zo;
          int i = mc_floor(d0);
          int j = mc_floor(d1);
          int k = mc_floor(d2);
          double d3 = d0 - (double)i;
          double d4 = d1 - (double)j;
          double d5 = d2 - (double)k;
          double d6 = 0.0;
          double yd = d4 - d6;
          int a = p[i & 0xFF];
          int b = p[(i + 1) & 0xFF];
          int c = p[(a + j) & 0xFF];
          int l = p[(a + j + 1) & 0xFF];
          int e = p[(b + j) & 0xFF];
          int f = p[(b + j + 1) & 0xFF];
          double g0 = mc_grad_dot(p[(c + k) & 0xFF], d3, yd, d5);
          double g1 = mc_grad_dot(p[(e + k) & 0xFF], d3 - 1.0, yd, d5);
          double g2 = mc_grad_dot(p[(l + k) & 0xFF], d3, yd - 1.0, d5);
          double g3 = mc_grad_dot(p[(f + k) & 0xFF], d3 - 1.0, yd - 1.0, d5);
          double g4 = mc_grad_dot(p[(c + k + 1) & 0xFF], d3, yd, d5 - 1.0);
          double g5 = mc_grad_dot(p[(e + k + 1) & 0xFF], d3 - 1.0, yd, d5 - 1.0);
          double g6 = mc_grad_dot(p[(l + k + 1) & 0xFF], d3, yd - 1.0, d5 - 1.0);
          double g7 = mc_grad_dot(p[(f + k + 1) & 0xFF], d3 - 1.0, yd - 1.0, d5 - 1.0);
          return mc_lerp3(mc_smoothstep(d3), mc_smoothstep(d4), mc_smoothstep(d5), g0, g1, g2, g3, g4, g5, g6, g7);
      }
      __device__ __forceinline__ double mc_wrap(double v) {
          return v - (double)mc_lfloor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
      }
      // PerlinNoise.getValue(x, y, z); D at its lowestFreqInputFactor, P at its first octave's permutation. Returns the
      // number of doubles it took, so the caller can find the next one.
      __device__ double mc_perlin(const double* D, const int* P, double x, double y, double z, int* used) {
          double sum = 0.0;
          double input = D[0];
          double value = D[1];
          int n = (int)D[2];
          for (int i = 0; i < n; i++) {
              const double* o = D + 3 + i * 5;
              if (o[4] != 0.0) {
                  double v = mc_improved(P + i * 256, o[1], o[2], o[3], mc_wrap(x * input), mc_wrap(y * input), mc_wrap(z * input));
                  sum += o[0] * v * value;
              }
              input *= 2.0;
              value /= 2.0;
          }
          *used = 3 + n * 5;
          return sum;
      }
      __device__ double mc_normal(const double* D, const int* P, double x, double y, double z) {
          int used1;
          int used2;
          double first = mc_perlin(D + 1, P, x, y, z, &used1);
          int octaves1 = (int)D[3];
          double second = mc_perlin(D + 1 + used1, P + octaves1 * 256, x * 1.0181268882175227, y * 1.0181268882175227, z * 1.0181268882175227, &used2);
          return (first + second) * D[0];
      }
      extern "C" __global__ void mcNormalCheck(const double* D, const int* P, const int* offsets, int noises, const double* xs, const double* ys,
                                               const double* zs, int points, double* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= noises * points) return;
          int noise = i / points;
          int p = i - noise * points;
          out[i] = mc_normal(D + offsets[noise * 2], P + offsets[noise * 2 + 1], xs[p], ys[p], zs[p]);
      }
      """;

   /**
    * What vanilla's density functions need besides the noise (for {@link DensityCodegen}): the point being worked out
    * (block x, y, z; FreeTerraForged's CellSampler values for it, worked out on the CPU; the packed noises), Java's
    * Math.min and max for doubles (NaN and -0 as Java), Mth.clamp, the float lerp of splines, the spline interval
    * search (Mth.binarySearch) and linear extension, and the spaghetti cave rarity steps. Needs NOISE before it.
    */
   public static final String DENSITY = """
      // The point being worked out. grid 0: CellSampler inputs per point (cells[k * points + point]); grid 1: per
      // block column of a chunk and the next chunks' first columns (cells[k * 289 + lx * 17 + lz], lx and lz 0 to 16
      // from the chunk's corner), as FreeTerraForged's CacheChunk gives them inside a chunk's fill; corners: the
      // interpolators' cell-corner values for the chunk (cornerStride each).
      struct DCtx {
          int x;
          int y;
          int z;
          const double* cells;
          int point;
          int points;
          const double* nd;
          const int* np;
          int grid;
          int chunkX;
          int chunkZ;
          int cellMinY;
          int cellCountY;
          int cellWidth;
          int cellHeight;
          const double* corners;
          int cornerStride;
          const double* columns;
      };
      // A hoisted y-independent subtree's value for the block's column (DensityCodegen's column hoisting): two values
      // per column, for y >= 0 and y < 0, as a noise with y scale 0 still sees the sign of y (0.0 or -0.0).
      __device__ __forceinline__ double dc_column(const DCtx& c, int k) {
          return c.columns[(k * 2 + (c.y < 0 ? 1 : 0)) * 256 + (c.x - c.chunkX) * 16 + (c.z - c.chunkZ)];
      }
      __device__ __forceinline__ double dc_cell(const DCtx& c, int k) {
          if (c.grid == 0) return c.cells[k * c.points + c.point];
          return c.cells[k * 289 + (c.x - c.chunkX) * 17 + (c.z - c.chunkZ)];
      }
      // NoiseChunk's FlatCache: its input at the quarter-column corner and y 0 (as a single point).
      __device__ __forceinline__ DCtx dc_flat(const DCtx& c) {
          DCtx f = c;
          f.x = c.x & ~3;
          f.y = 0;
          f.z = c.z & ~3;
          return f;
      }
      // NoiseChunk's NoiseInterpolator while it fills a cell (CacheAllInCell): Mth.lerp3 over the cell's 8 corners.
      __device__ double dc_interpolated(const DCtx& c, int k) {
          int lx = c.x - c.chunkX;
          int lz = c.z - c.chunkZ;
          int ly = c.y - c.cellMinY * c.cellHeight;
          int cx = lx / c.cellWidth;
          int cz = lz / c.cellWidth;
          int cy = ly / c.cellHeight;
          int ix = lx - cx * c.cellWidth;
          int iz = lz - cz * c.cellWidth;
          int iy = ly - cy * c.cellHeight;
          int ny = c.cellCountY + 1;
          int nz = 16 / c.cellWidth + 1;
          const double* k0 = c.corners + k * c.cornerStride;
          double n000 = k0[(cx * nz + cz) * ny + cy];
          double n100 = k0[((cx + 1) * nz + cz) * ny + cy];
          double n010 = k0[(cx * nz + cz) * ny + cy + 1];
          double n110 = k0[((cx + 1) * nz + cz) * ny + cy + 1];
          double n001 = k0[(cx * nz + cz + 1) * ny + cy];
          double n101 = k0[((cx + 1) * nz + cz + 1) * ny + cy];
          double n011 = k0[(cx * nz + cz + 1) * ny + cy + 1];
          double n111 = k0[((cx + 1) * nz + cz + 1) * ny + cy + 1];
          return mc_lerp3((double)ix / (double)c.cellWidth, (double)iy / (double)c.cellHeight, (double)iz / (double)c.cellWidth, n000, n100, n010, n110,
                          n001, n101, n011, n111);
      }
      // NoiseInterpolator's step-by-step value (updateForY, then X, then Z, as doFill drives it): y first, then x, then
      // z, which can round differently from lerp3's x, y, z.
      __device__ double dc_interpolated_step(const DCtx& c, int k) {
          int lx = c.x - c.chunkX;
          int lz = c.z - c.chunkZ;
          int ly = c.y - c.cellMinY * c.cellHeight;
          int cx = lx / c.cellWidth;
          int cz = lz / c.cellWidth;
          int cy = ly / c.cellHeight;
          int ix = lx - cx * c.cellWidth;
          int iz = lz - cz * c.cellWidth;
          int iy = ly - cy * c.cellHeight;
          int ny = c.cellCountY + 1;
          int nz = 16 / c.cellWidth + 1;
          const double* k0 = c.corners + k * c.cornerStride;
          double n000 = k0[(cx * nz + cz) * ny + cy];
          double n100 = k0[((cx + 1) * nz + cz) * ny + cy];
          double n010 = k0[(cx * nz + cz) * ny + cy + 1];
          double n110 = k0[((cx + 1) * nz + cz) * ny + cy + 1];
          double n001 = k0[(cx * nz + cz + 1) * ny + cy];
          double n101 = k0[((cx + 1) * nz + cz + 1) * ny + cy];
          double n011 = k0[(cx * nz + cz + 1) * ny + cy + 1];
          double n111 = k0[((cx + 1) * nz + cz + 1) * ny + cy + 1];
          double dy = (double)iy / (double)c.cellHeight;
          double dx = (double)ix / (double)c.cellWidth;
          double dz = (double)iz / (double)c.cellWidth;
          double xz00 = mc_lerp(dy, n000, n010);
          double xz10 = mc_lerp(dy, n100, n110);
          double xz01 = mc_lerp(dy, n001, n011);
          double xz11 = mc_lerp(dy, n101, n111);
          double z0 = mc_lerp(dx, xz00, xz10);
          double z1 = mc_lerp(dx, xz01, xz11);
          return mc_lerp(dz, z0, z1);
      }
      __device__ __forceinline__ double java_mind(double a, double b) {
          if (a != a) return a;
          if (a == 0.0 && b == 0.0 && __double_as_longlong(b) == (long long)0x8000000000000000ull) return b;
          return a <= b ? a : b;
      }
      __device__ __forceinline__ double java_maxd(double a, double b) {
          if (a != a) return a;
          if (a == 0.0 && b == 0.0 && __double_as_longlong(a) == (long long)0x8000000000000000ull) return b;
          return a >= b ? a : b;
      }
      __device__ __forceinline__ double mth_clampd(double v, double lo, double hi) {
          return v < lo ? lo : java_mind(v, hi);
      }
      __device__ __forceinline__ float mth_lerpf(float t, float a, float b) {
          return a + t * (b - a);
      }
      __device__ __forceinline__ int mc_spline_interval(const float* locations, int n, float f) {
          int lo = 0;
          int length = n;
          while (length > 0) {
              int half = length / 2;
              int k = lo + half;
              if (f < locations[k]) {
                  length = half;
              } else {
                  lo = k + 1;
                  length -= half + 1;
              }
          }
          return lo - 1;
      }
      __device__ __forceinline__ float mc_linear_extend(float input, const float* locations, float value, const float* derivatives, int index) {
          float d = derivatives[index];
          return d == 0.0f ? value : value + d * (input - locations[index]);
      }
      __device__ __forceinline__ double mc_spaghetti_rarity_3d(double v) {
          if (v < -0.5) return 0.75;
          if (v < 0.0) return 1.0;
          return v < 0.5 ? 1.5 : 2.0;
      }
      __device__ __forceinline__ double mc_spaghetti_rarity_2d(double v) {
          if (v < -0.75) return 0.5;
          if (v < -0.5) return 0.75;
          if (v < 0.5) return 1.0;
          return v < 0.75 ? 2.0 : 3.0;
      }
      """;

   /**
    * Vanilla's positional random (XoroshiroPositionalRandomFactory.at: Mth.getSeed xor seedLo, seedHi; Xoroshiro128++,
    * nextFloat = top 24 bits times 2^-24, nextInt(bound) as XoroshiroRandomSource does it) and the ore vein rule
    * (OreVeinifier). Codes: 0 none (the default block), 1 copper ore, 2 raw copper block, 3 granite, 4 deepslate iron
    * ore, 5 raw iron block, 6 tuff. Needs DENSITY before it.
    */
   public static final String RANDOM = """
      struct Xrs {
          unsigned long long lo;
          unsigned long long hi;
      };
      __device__ __forceinline__ long long mc_get_seed(int x, int y, int z) {
          long long i = (long long)(int)((unsigned int)x * 3129871u) ^ (long long)((unsigned long long)(long long)z * 116129781ull) ^ (long long)y;
          unsigned long long u = (unsigned long long)i;
          u = u * u * 42317861ull + u * 11ull;
          return ((long long)u) >> 16;
      }
      __device__ __forceinline__ Xrs mc_random_at(long long seedLo, long long seedHi, int x, int y, int z) {
          Xrs r;
          r.lo = (unsigned long long)(mc_get_seed(x, y, z) ^ seedLo);
          r.hi = (unsigned long long)seedHi;
          if ((r.lo | r.hi) == 0ull) {
              r.lo = (unsigned long long)-7046029254386353131LL;
              r.hi = 7640891576956012809ull;
          }
          return r;
      }
      __device__ __forceinline__ unsigned long long mc_rotl(unsigned long long v, int n) {
          return (v << n) | (v >> (64 - n));
      }
      __device__ __forceinline__ unsigned long long mc_next_long(Xrs& r) {
          unsigned long long i = r.lo;
          unsigned long long j = r.hi;
          unsigned long long k = mc_rotl(i + j, 17) + i;
          j ^= i;
          r.lo = mc_rotl(i, 49) ^ j ^ (j << 21);
          r.hi = mc_rotl(j, 28);
          return k;
      }
      __device__ __forceinline__ float mc_next_float(Xrs& r) {
          return (float)(long long)(mc_next_long(r) >> 40) * 5.9604645E-8f;
      }
      __device__ __forceinline__ int mc_next_int(Xrs& r, int bound) {
          unsigned long long i = (unsigned long long)(unsigned int)(int)mc_next_long(r);
          unsigned long long j = i * (unsigned long long)(long long)bound;
          unsigned long long k = j & 4294967295ull;
          if (k < (unsigned long long)(long long)bound) {
              unsigned int l = ((unsigned int)(~bound + 1)) % (unsigned int)bound;
              while (k < (unsigned long long)l) {
                  i = (unsigned long long)(unsigned int)(int)mc_next_long(r);
                  j = i * (unsigned long long)(long long)bound;
                  k = j & 4294967295ull;
              }
          }
          return (int)((long long)j >> 32);
      }
      __device__ __forceinline__ double mth_clamped_map(double v, double fromMin, double fromMax, double toMin, double toMax) {
          double t = (v - fromMin) / (fromMax - fromMin);
          return t < 0.0 ? toMin : (t > 1.0 ? toMax : toMin + t * (toMax - toMin));
      }
      """;

   private VanillaKernels() {
   }
}
