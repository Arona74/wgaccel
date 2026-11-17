package com.wgaccel.gpu;

/**
 * CUDA C versions of FreeTerraForged's noise modules, written operation for operation like its Java (compiled with
 * {@link GpuSelfTest#EXACT_FLAGS}: no fused multiply-add, IEEE division and square root), so the results are
 * bit-identical. Integer hashing uses unsigned arithmetic (Java's int wraps; C++ signed overflow is undefined).
 */
public final class NoiseKernels {
   /** FTF's NoiseUtil: floor, lerp, the 2D gradient hash, interpolation curves, clamp and map. */
   public static final String NOISE_UTIL = """
      __device__ __forceinline__ int ftf_floor(float f) {
          return f >= 0.0f ? (int)f : (int)f - 1;
      }
      __device__ __forceinline__ float ftf_lerp(float a, float b, float alpha) {
          return a + alpha * (b - a);
      }
      __device__ __forceinline__ float ftf_clamp(float value, float lo, float hi) {
          return value < lo ? lo : (value > hi ? hi : value);
      }
      __device__ __forceinline__ float ftf_map(float value, float lo, float hi, float range) {
          float dif = ftf_clamp(value, lo, hi) - lo;
          return dif >= range ? 1.0f : dif / range;
      }
      __device__ __forceinline__ float ftf_interp(int kind, float t) {
          if (kind == 1) return t * t * (3.0f - 2.0f * t);
          if (kind == 2) return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
          return t;
      }
      __constant__ float FTF_GRAD_X[8] = {-1.0f, 1.0f, -1.0f, 1.0f, 0.0f, -1.0f, 0.0f, 1.0f};
      __constant__ float FTF_GRAD_Y[8] = {-1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 0.0f, 1.0f, 0.0f};
      __device__ __forceinline__ float ftf_grad_coord_2d(int seed, int x, int y, float xd, float yd) {
          unsigned int hash = (unsigned int)seed ^ (1619u * (unsigned int)x);
          hash ^= 31337u * (unsigned int)y;
          hash = hash * hash * hash * 60493u;
          int h = (int)hash;
          h ^= h >> 13;
          int i = h & 7;
          return xd * FTF_GRAD_X[i] + yd * FTF_GRAD_Y[i];
      }
      """;

   /** FTF's Perlin module: octaves of gradient noise (it uses its own stored seed, not the one passed in). */
   public static final String PERLIN = """
      __device__ float ftf_perlin_sample(float x, float y, int seed, int interp) {
          int x2 = ftf_floor(x);
          int y2 = ftf_floor(y);
          int x3 = x2 + 1;
          int y3 = y2 + 1;
          float xs = ftf_interp(interp, x - (float)x2);
          float ys = ftf_interp(interp, y - (float)y2);
          float xd0 = x - (float)x2;
          float yd0 = y - (float)y2;
          float xd2 = xd0 - 1.0f;
          float yd2 = yd0 - 1.0f;
          float xf0 = ftf_lerp(ftf_grad_coord_2d(seed, x2, y2, xd0, yd0), ftf_grad_coord_2d(seed, x3, y2, xd2, yd0), xs);
          float xf2 = ftf_lerp(ftf_grad_coord_2d(seed, x2, y3, xd0, yd2), ftf_grad_coord_2d(seed, x3, y3, xd2, yd2), xs);
          return ftf_lerp(xf0, xf2, ys);
      }
      __device__ float ftf_perlin(float x, float z, int seed, float frequency, int octaves, float lacunarity, float gain, int interp,
                                  float lo, float hi) {
          x *= frequency;
          z *= frequency;
          float sum = 0.0f;
          float amplitude = gain;
          for (int i = 0; i < octaves; i++) {
              sum += ftf_perlin_sample(x, z, seed + i, interp) * amplitude;
              x *= lacunarity;
              z *= lacunarity;
              amplitude *= gain;
          }
          return ftf_map(sum, lo, hi, hi - lo);
      }
      """;

   /** FTF's PerlinRidge and Billow (the same loop; Billow returns one minus the result). They use the seed passed in. */
   public static final String RIDGE = """
      __device__ float ftf_ridge(float x, float z, int seed, float frequency, int octaves, float lacunarity, float gain, int interp,
                                 const float* weights, float lo, float hi, int billow) {
          x *= frequency;
          z *= frequency;
          float amp = 2.0f;
          float value = 0.0f;
          float weight = 1.0f;
          for (int octave = 0; octave < octaves; octave++) {
              float signal = ftf_perlin_sample(x, z, seed + octave, interp);
              signal = 1.0f - fabsf(signal);
              signal *= signal;
              signal *= weight;
              weight = signal * amp;
              weight = ftf_clamp(weight, 0.0f, 1.0f);
              value += signal * weights[octave];
              x *= lacunarity;
              z *= lacunarity;
              amp *= gain;
          }
          float mapped = ftf_map(value, lo, hi, fabsf(hi - lo));
          return billow ? 1.0f - mapped : mapped;
      }
      extern "C" __global__ void ridgeCheck(const float* fp, const int* ip, const float* weights, int nodes, const float* xs, const float* zs,
                                            const int* seeds, int points, int billow, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 5;
          const int* n = ip + node * 3;
          out[i] = ftf_ridge(xs[p], zs[p], seeds[p], f[0], n[0], f[1], f[2], n[1], weights + n[2], f[3], f[4], billow);
      }
      """;

   /**
    * FTF's Simplex: octaves of 2D simplex noise with its 32-entry gradient table GRAD_2D_24 (passed in as 64 floats read
    * from FTF's own NoiseUtil, so the values are FTF's exactly). It uses the seed passed in.
    */
   public static final String SIMPLEX = """
      __device__ __forceinline__ float ftf_grad_coord_2d_24(const float* g24, int seed, int x, int y, float xd, float yd) {
          unsigned int hash = (unsigned int)seed ^ (1619u * (unsigned int)x);
          hash ^= 31337u * (unsigned int)y;
          hash = hash * hash * hash * 60493u;
          int h = (int)hash;
          h ^= h >> 13;
          int i = (int)((float)(h & 4194303) * 1.3333334f) & 31;
          return xd * g24[i * 2] + yd * g24[i * 2 + 1];
      }
      __device__ float ftf_simplex_single(const float* g24, float x, float y, int seed, float scaler) {
          float t = (x + y) * 0.36602542f;
          int i = ftf_floor(x + t);
          int j = ftf_floor(y + t);
          t = (float)(i + j) * 0.21132487f;
          float X0 = (float)i - t;
          float Y0 = (float)j - t;
          float x2 = x - X0;
          float y2 = y - Y0;
          int i2;
          int j2;
          if (x2 > y2) { i2 = 1; j2 = 0; } else { i2 = 0; j2 = 1; }
          float x3 = x2 - (float)i2 + 0.21132487f;
          float y3 = y2 - (float)j2 + 0.21132487f;
          float x4 = x2 - 1.0f + 0.42264974f;
          float y4 = y2 - 1.0f + 0.42264974f;
          t = 0.5f - x2 * x2 - y2 * y2;
          float n0;
          if (t < 0.0f) { n0 = 0.0f; } else { t *= t; n0 = t * t * ftf_grad_coord_2d_24(g24, seed, i, j, x2, y2); }
          t = 0.5f - x3 * x3 - y3 * y3;
          float n2;
          if (t < 0.0f) { n2 = 0.0f; } else { t *= t; n2 = t * t * ftf_grad_coord_2d_24(g24, seed, i + i2, j + j2, x3, y3); }
          t = 0.5f - x4 * x4 - y4 * y4;
          float n3;
          if (t < 0.0f) { n3 = 0.0f; } else { t *= t; n3 = t * t * ftf_grad_coord_2d_24(g24, seed, i + 1, j + 1, x4, y4); }
          return scaler * (n0 + n2 + n3);
      }
      __device__ float ftf_simplex(const float* g24, float x, float z, int seed, float frequency, int octaves, float lacunarity, float gain,
                                   float lo, float hi, float scaler) {
          x *= frequency;
          z *= frequency;
          float sum = 0.0f;
          float amp = 1.0f;
          for (int i = 0; i < octaves; i++) {
              sum += ftf_simplex_single(g24, x, z, seed + i, scaler) * amp;
              x *= lacunarity;
              z *= lacunarity;
              amp *= gain;
          }
          return ftf_map(sum, lo, hi, hi - lo);
      }
      extern "C" __global__ void simplexCheck(const float* g24, const float* fp, const int* ip, int nodes, const float* xs, const float* zs,
                                              const int* seeds, int points, float scaler, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 5;
          out[i] = ftf_simplex(g24, xs[p], zs[p], seeds[p], f[0], ip[node], f[1], f[2], f[3], f[4], scaler);
      }
      """;

   /** FTF's Perlin2: Perlin with the 32-entry gradient table (GRAD_2D_24); it uses its own stored seed. Needs SIMPLEX. */
   public static final String PERLIN2 = """
      __device__ float ftf_perlin2_sample(const float* g24, float x, float y, int seed, int interp) {
          int x2 = ftf_floor(x);
          int y2 = ftf_floor(y);
          int x3 = x2 + 1;
          int y3 = y2 + 1;
          float xs = ftf_interp(interp, x - (float)x2);
          float ys = ftf_interp(interp, y - (float)y2);
          float xd0 = x - (float)x2;
          float yd0 = y - (float)y2;
          float xd2 = xd0 - 1.0f;
          float yd2 = yd0 - 1.0f;
          float xf0 = ftf_lerp(ftf_grad_coord_2d_24(g24, seed, x2, y2, xd0, yd0), ftf_grad_coord_2d_24(g24, seed, x3, y2, xd2, yd0), xs);
          float xf2 = ftf_lerp(ftf_grad_coord_2d_24(g24, seed, x2, y3, xd0, yd2), ftf_grad_coord_2d_24(g24, seed, x3, y3, xd2, yd2), xs);
          return ftf_lerp(xf0, xf2, ys);
      }
      __device__ float ftf_perlin2(const float* g24, float x, float z, int seed, float frequency, int octaves, float lacunarity, float gain,
                                   int interp, float lo, float hi) {
          x *= frequency;
          z *= frequency;
          float sum = 0.0f;
          float amplitude = gain;
          for (int i = 0; i < octaves; i++) {
              sum += ftf_perlin2_sample(g24, x, z, seed + i, interp) * amplitude;
              x *= lacunarity;
              z *= lacunarity;
              amplitude *= gain;
          }
          return ftf_map(sum, lo, hi, hi - lo);
      }
      extern "C" __global__ void perlin2Check(const float* g24, const float* fp, const int* ip, int nodes, const float* xs, const float* zs,
                                              int points, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 5;
          const int* n = ip + node * 3;
          out[i] = ftf_perlin2(g24, xs[p], zs[p], n[0], f[0], n[1], f[1], f[2], n[2], f[3], f[4]);
      }
      """;

   /**
    * FTF's Worley and WorleyEdge: the nearest (and second nearest) of the 3 x 3 cells' points, the points offset by its
    * 256-entry CELL_2D table (passed in from FTF's own class). Distance kinds by ordinal: EUCLIDEAN, MANHATTAN, NATURAL;
    * cell kinds: CELL_VALUE, NOISE_LOOKUP (not here: it evaluates a nested noise), DISTANCE; edge kinds: DISTANCE_2,
    * _ADD, _SUB, _MUL, _DIV.
    */
   public static final String WORLEY = """
      __device__ __forceinline__ int ftf_hash_2d(int seed, int x, int y) {
          unsigned int hash = (unsigned int)seed ^ (1619u * (unsigned int)x);
          hash ^= 31337u * (unsigned int)y;
          hash = hash * hash * hash * 60493u;
          int h = (int)hash;
          return h ^ (h >> 13);
      }
      __device__ __forceinline__ float ftf_val_coord_2d(int seed, int x, int y) {
          unsigned int n = (unsigned int)seed ^ (1619u * (unsigned int)x);
          n ^= 31337u * (unsigned int)y;
          return (float)(int)(n * n * n * 60493u) / 2.1474836E9f;
      }
      __device__ __forceinline__ float ftf_distance(int kind, float x, float y) {
          if (kind == 1) return fabsf(x) + fabsf(y);
          if (kind == 2) return fabsf(x) + fabsf(y) + x * x + y * y;
          return x * x + y * y;
      }
      __device__ float ftf_worley(const float* cell, float x, float z, int seed, float frequency, float distance, int distanceKind,
                                  int cellKind, float lo, float hi) {
          x *= frequency;
          z *= frequency;
          int xi = ftf_floor(x);
          int yi = ftf_floor(z);
          int cellX = xi;
          int cellY = yi;
          float nearest = 3.4028235E38f;
          for (int dy = -1; dy <= 1; dy++) {
              for (int dx = -1; dx <= 1; dx++) {
                  int cx = xi + dx;
                  int cy = yi + dy;
                  int c = ftf_hash_2d(seed, cx, cy) & 0xFF;
                  float deltaX = (float)cx + cell[c * 2] * distance - x;
                  float deltaY = (float)cy + cell[c * 2 + 1] * distance - z;
                  float dist = ftf_distance(distanceKind, deltaX, deltaY);
                  if (dist < nearest) {
                      nearest = dist;
                      cellX = cx;
                      cellY = cy;
                  }
              }
          }
          if (cellKind == 2) return 0.0f;
          float value = ftf_val_coord_2d(seed, cellX, cellY);
          return ftf_map(value, lo, hi, hi - lo);
      }
      __device__ float ftf_worley_edge(const float* cell, float x, float z, int seed, float frequency, float distance, int distanceKind,
                                       int edgeKind, float emin, float emax, float erange) {
          x *= frequency;
          z *= frequency;
          int xi = ftf_floor(x);
          int yi = ftf_floor(z);
          float nearest1 = 3.4028235E38f;
          float nearest2 = 3.4028235E38f;
          for (int dy = -1; dy <= 1; dy++) {
              for (int dx = -1; dx <= 1; dx++) {
                  int cx = xi + dx;
                  int cy = yi + dy;
                  int c = ftf_hash_2d(seed, cx, cy) & 0xFF;
                  float deltaX = (float)cx + cell[c * 2] * distance - x;
                  float deltaY = (float)cy + cell[c * 2 + 1] * distance - z;
                  float dist = ftf_distance(distanceKind, deltaX, deltaY);
                  if (dist < nearest1) {
                      nearest2 = nearest1;
                      nearest1 = dist;
                  } else if (dist < nearest2) {
                      nearest2 = dist;
                  }
              }
          }
          nearest1 = fmaxf(1.0E-6f, nearest1);
          float result;
          if (edgeKind == 1) result = nearest2 + nearest1 - 1.0f;
          else if (edgeKind == 2) result = nearest2 - nearest1 - 1.0f;
          else if (edgeKind == 3) result = nearest2 * nearest1 - 1.0f;
          else if (edgeKind == 4) result = nearest1 / nearest2 - 1.0f;
          else result = nearest2 - 1.0f;
          float value = (!isnan(result) && !isinf(result)) ? result : emin;
          return ftf_map(value, emin, emax, erange);
      }
      extern "C" __global__ void worleyCheck(const float* cell, const float* fp, const int* ip, int nodes, const float* xs, const float* zs,
                                             const int* seeds, int points, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 4;
          const int* n = ip + node * 2;
          out[i] = ftf_worley(cell, xs[p], zs[p], seeds[p], f[0], f[1], n[0], n[1], f[2], f[3]);
      }
      extern "C" __global__ void worleyEdgeCheck(const float* cell, const float* fp, const int* ip, int nodes, const float* xs, const float* zs,
                                                 const int* seeds, int points, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 5;
          const int* n = ip + node * 2;
          out[i] = ftf_worley_edge(cell, xs[p], zs[p], seeds[p], f[0], f[1], n[0], n[1], f[2], f[3], f[4]);
      }
      """;

   /**
    * Java's maths where CUDA's differs or Java's result must be matched exactly. java_minf and java_maxf follow
    * Math.min and max (NaN wins, -0 is below +0; CUDA's fminf and fmaxf differ). java_cast_checked is (float) of a
    * double from a function CUDA and Java may round differently in the last bits (pow, exp, sin, cos: CUDA's are within
    * 2 units in the last place, Java's within 1): when the double lies within 8 units of a float rounding boundary,
    * the two might round to different floats, so u is set and that value is worked out on the CPU instead. ftf_pow is
    * FTF's (float)Math.pow((double)value, (double)power). Needs nothing else.
    */
   public static final String JAVA_MATH = """
      __device__ __forceinline__ int ftf_add(int a, int b) {
          return (int)((unsigned int)a + (unsigned int)b);
      }
      __device__ __forceinline__ float java_minf(float a, float b) {
          if (a != a) return a;
          if (a == 0.0f && b == 0.0f && __float_as_int(b) == (int)0x80000000u) return b;
          return a <= b ? a : b;
      }
      __device__ __forceinline__ float java_maxf(float a, float b) {
          if (a != a) return a;
          if (a == 0.0f && b == 0.0f && __float_as_int(a) == (int)0x80000000u) return b;
          return a >= b ? a : b;
      }
      __device__ __forceinline__ int ftf_round(float f) {
          return f >= 0.0f ? __float2int_rz(f + 0.5f) : __float2int_rz(f - 0.5f);
      }
      __device__ __forceinline__ float ftf_powi(float value, int power) {
          if (power <= 0) return 1.0f;
          float result = value;
          for (int i = 1; i < power; i++) result *= value;
          return result;
      }
      __device__ __forceinline__ float ftf_copysign(float value, float sign) {
          if (sign < 0.0f && value > 0.0f) return -value;
          return sign > 0.0f && value < 0.0f ? -value : value;
      }
      __device__ float java_cast_checked(double r, int& u) {
          float f = (float)r;
          if (isfinite(r) && r != 0.0) {
              if (isinf(f) || fabsf(f) == 3.4028235E38f) {
                  u = 1;
              } else {
                  double lo = ((double)f + (double)nextafterf(f, __int_as_float((int)0xff800000u))) * 0.5;
                  double hi = ((double)f + (double)nextafterf(f, __int_as_float(0x7f800000))) * 0.5;
                  double tolerance = fabs(r) * 1.7763568394002505E-15;
                  if (fabs(r - lo) <= tolerance || fabs(r - hi) <= tolerance) u = 1;
              }
          }
          return f;
      }
      __device__ float ftf_pow(float value, float power, int& u) {
          if (isnan(power)) return __int_as_float(0x7fc00000);
          if (fabsf(value) == 1.0f && isinf(power)) return __int_as_float(0x7fc00000);
          return java_cast_checked(pow((double)value, (double)power), u);
      }
      __device__ __forceinline__ float java_signum(float f) {
          return f > 0.0f ? 1.0f : (f < 0.0f ? -1.0f : f);
      }
      """;

   /**
    * What FreeTerraForged's combinators need besides the leaf noises, for {@link NoiseCodegen}'s generated trees. Needs
    * NOISE_UTIL, WORLEY, JAVA_MATH and the generated tables before it (FTF_CELL, FTF_SIN, FTF_SIN_MASK, FTF_RAD_TO_INDEX).
    */
   public static final String TREES = """
      __device__ __forceinline__ float ftf_sin(float r) {
          return FTF_SIN[__float2int_rz(r * FTF_RAD_TO_INDEX) & FTF_SIN_MASK];
      }
      __device__ __forceinline__ float ftf_cubic_lerp(float a, float b, float c, float d, float t) {
          float p = d - c - (a - b);
          return t * t * t * p + t * t * (a - b - p) + t * (c - a) + b;
      }
      __device__ float ftf_cubic_sample(float x, float y, int seed) {
          int x2 = ftf_floor(x);
          int y2 = ftf_floor(y);
          int x3 = x2 - 1;
          int y3 = y2 - 1;
          int x4 = x2 + 1;
          int y4 = y2 + 1;
          int x5 = x2 + 2;
          int y5 = y2 + 2;
          float xs = x - (float)x2;
          float ys = y - (float)y2;
          return ftf_cubic_lerp(
              ftf_cubic_lerp(ftf_val_coord_2d(seed, x3, y3), ftf_val_coord_2d(seed, x2, y3), ftf_val_coord_2d(seed, x4, y3), ftf_val_coord_2d(seed, x5, y3), xs),
              ftf_cubic_lerp(ftf_val_coord_2d(seed, x3, y2), ftf_val_coord_2d(seed, x2, y2), ftf_val_coord_2d(seed, x4, y2), ftf_val_coord_2d(seed, x5, y2), xs),
              ftf_cubic_lerp(ftf_val_coord_2d(seed, x3, y4), ftf_val_coord_2d(seed, x2, y4), ftf_val_coord_2d(seed, x4, y4), ftf_val_coord_2d(seed, x5, y4), xs),
              ftf_cubic_lerp(ftf_val_coord_2d(seed, x3, y5), ftf_val_coord_2d(seed, x2, y5), ftf_val_coord_2d(seed, x4, y5), ftf_val_coord_2d(seed, x5, y5), xs),
              ys) * 0.44444445f;
      }
      __device__ float ftf_cubic(float x, float z, int seed, float frequency, int octaves, float lacunarity, float gain, float lo, float hi) {
          x *= frequency;
          z *= frequency;
          float sum = ftf_cubic_sample(x, z, seed);
          float amplifier = 1.0f;
          for (int i = 1; i < octaves; i++) {
              x *= lacunarity;
              z *= lacunarity;
              amplifier *= gain;
              sum += ftf_cubic_sample(x, z, seed + i) * amplifier;
          }
          return ftf_map(sum, lo, hi, hi - lo);
      }
      template <typename F>
      __device__ float ftf_worley_lookup(F lookup, float x, float z, int seed, float frequency, float distance, int distanceKind) {
          x *= frequency;
          z *= frequency;
          int xi = ftf_floor(x);
          int yi = ftf_floor(z);
          int cellX = xi;
          int cellY = yi;
          int best = -1;
          float nearest = 3.4028235E38f;
          for (int dy = -1; dy <= 1; dy++) {
              for (int dx = -1; dx <= 1; dx++) {
                  int cx = xi + dx;
                  int cy = yi + dy;
                  int c = ftf_hash_2d(seed, cx, cy) & 0xFF;
                  float deltaX = (float)cx + FTF_CELL[c * 2] * distance - x;
                  float deltaY = (float)cy + FTF_CELL[c * 2 + 1] * distance - z;
                  float dist = ftf_distance(distanceKind, deltaX, deltaY);
                  if (dist < nearest) {
                      nearest = dist;
                      best = c;
                      cellX = cx;
                      cellY = cy;
                  }
              }
          }
          if (best < 0) return __int_as_float(0x7fc00000);
          return lookup((float)cellX + FTF_CELL[best * 2], (float)cellY + FTF_CELL[best * 2 + 1], seed);
      }
      __device__ __forceinline__ float ftf_sd(float px, float py, float ax, float ay, float bx, float by) {
          float padx = px - ax;
          float pady = py - ay;
          float badx = bx - ax;
          float bady = by - ay;
          float paba = padx * badx + pady * bady;
          float baba = badx * badx + bady * bady;
          float h = paba == 0.0f && baba == 0.0f ? 0.0f : ftf_clamp(paba / baba, 0.0f, 1.0f);
          float dx = badx * h - padx;
          float dy = bady * h - pady;
          return dx * dx + dy * dy;
      }
      template <typename F>
      __device__ float ftf_erosion_single(F input, int eseed, float x, float y, float gridSize) {
          float cache[25];
          for (int i = 0; i < 25; i++) cache[i] = -1.0f;
          int pix = ftf_floor(x / gridSize);
          int piy = ftf_floor(y / gridSize);
          float minHeight2 = 3.4028235E38f;
          for (int dy1 = -1; dy1 <= 1; dy1++) {
              for (int dx1 = -1; dx1 <= 1; dx1++) {
                  int pax = pix + dx1;
                  int pay = piy + dy1;
                  int c1 = ftf_hash_2d(eseed, pax, pay) & 0xFF;
                  float ax = ((float)pax + FTF_CELL[c1 * 2]) * gridSize;
                  float ay = ((float)pay + FTF_CELL[c1 * 2 + 1]) * gridSize;
                  float bx = ax;
                  float by = ay;
                  float lowest = 3.4028235E38f;
                  for (int dy2 = -1; dy2 <= 1; dy2++) {
                      for (int dx2 = -1; dx2 <= 1; dx2++) {
                          int pbx = pax + dx2;
                          int pby = pay + dy2;
                          int c2 = ftf_hash_2d(eseed, pbx, pby) & 0xFF;
                          float candidateX = ((float)pbx + FTF_CELL[c2 * 2]) * gridSize;
                          float candidateY = ((float)pby + FTF_CELL[c2 * 2 + 1]) * gridSize;
                          int index = (dy1 + dy2 + 2) * 5 + dx1 + dx2 + 2;
                          float height = cache[index];
                          if (height == -1.0f) {
                              height = input(candidateX, candidateY);
                              cache[index] = height;
                          }
                          if (height < lowest) {
                              lowest = height;
                              bx = candidateX;
                              by = candidateY;
                          }
                      }
                  }
                  float height2 = ftf_sd(x, y, ax, ay, bx, by);
                  if (height2 < minHeight2) minHeight2 = height2;
              }
          }
          return ftf_clamp((float)sqrt((double)minHeight2) / gridSize, 0.0f, 1.0f);
      }
      template <typename F>
      __device__ float ftf_erosion_value(F input, int eseed, int octaves, float gridSize, float amplitude, float lacunarity, float fallOff,
                                         float x, float y) {
          float sum = 0.0f;
          float total = 0.0f;
          float gain = 1.0f;
          float distance = gridSize;
          for (int i = 0; i < octaves; i++) {
              float value = ftf_erosion_single(input, eseed, x, y, distance);
              value *= gain;
              sum += value;
              total += gain;
              gain *= amplitude;
              distance *= fallOff;
              x *= lacunarity;
              y *= lacunarity;
          }
          return sum / total;
      }
      """;

   /** The check kernel: every Perlin node (parameters in two arrays) at every sample point. */
   public static final String PERLIN_CHECK = """
      extern "C" __global__ void perlinCheck(const float* fp, const int* ip, int nodes, const float* xs, const float* zs, int points, float* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= nodes * points) return;
          int node = i / points;
          int p = i - node * points;
          const float* f = fp + node * 5;
          const int* n = ip + node * 3;
          out[i] = ftf_perlin(xs[p], zs[p], n[0], f[0], n[1], f[1], f[2], n[2], f[3], f[4]);
      }
      """;

   private NoiseKernels() {
   }
}
