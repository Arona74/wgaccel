package com.wgaccel.gpu;

/** G5.2a's kernels ({@link BiomeCheck}); no Minecraft classes, so the standalone check can compile them. */
public final class BiomeKernels {
   private BiomeKernels() {
   }

   /** The biome check kernels after a DensityCodegen source: biomeClimate (the six climate functions named) and biomeSearch. */
   public static String source(String[] climate) {
      return source(climate, 0);
   }

   /**
    * C2: with the climate functions' column-only subtrees remembered per thread (DensityCodegen's dc_cmN, {@code slots}
    * cache slots; 0: none), a thread works out up to 8 cells of one quart column (cells (y * 4 + x) * 4 + z: 16 apart),
    * so a column's climate noises and height splines are worked out once instead of per cell. The same values.
    */
   public static String source(String[] climate, int slots) {
      return multi(single(climate, slots));
   }

   /**
    * C1: one provider per cell (TerraBlender regions): biomeTreeSearchMulti and biomeDecorateMulti pick each cell's
    * provider by its quart column (provider[16 j + column], worked out on the CPU as FreeTerraForged's spatial resolver
    * does): its tree root and ocean projection, its Layout settings, flags and density. The decoration is biomeDecorate's
    * own text with those per provider.
    */
   private static String multi(String source) {
      int from = source.indexOf("extern \"C\" __global__ void biomeDecorate(");
      int to = source.indexOf("// C1: another provider's table");
      String decorate = source.substring(from, to)
         .replace("void biomeDecorate(", "void biomeDecorateMulti(")
         .replace("const long long* p, const int* q,", "const long long* pAll, const int* qAll, const int* provider, int flagStride, const float* densities,")
         .replace("float density, float presetCoverage, const int* flags,", "float presetCoverage, const int* flagsAll,")
         .replace("long long depth = t[4];", "int kp = provider[16 * j + column]; const long long* p = pAll + 6 * kp; const int* q = qAll + 7 * kp; "
            + "const int* flags = flagsAll + flagStride * kp; float density = densities[kp]; long long depth = t[4];");
      if (!decorate.contains("int kp = provider[16 * j + column];")) {
         throw new IllegalStateException("biomeDecorate's text changed: no per-provider version");
      }

      return source + decorate + """
         extern "C" __global__ void biomeTreeSearchMulti(const long long* in, int n, int perChunk, const int* at, const int* chunkXZ, const int* provider,
                                                         const int* roots, const int* targetMaxes, const int* bounds, const int* first, const int* count,
                                                         const int* biome, int sourceMin, int sourceMax, long long* out, int* chosen, int* tied) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= n) return;
             int j = i / perChunk;
             int column = (at[i * 3] - (chunkXZ[2 * j] >> 2)) * 4 + (at[i * 3 + 2] - (chunkXZ[2 * j + 1] >> 2));
             int kp = column < 0 || column >= 16 ? 0 : provider[16 * j + column];
             long long t[7];
             for (int k = 0; k < 6; k++) t[k] = in[i * 6 + k];
             t[6] = 0;
             long long cc = t[2];
             long long smin = sourceMin;
             long long smax = sourceMax;
             long long tmax = targetMaxes[kp];
             if (!(tmax == smax || cc <= smin || cc >= smax)) t[2] = smin + (cc - smin) * (tmax - smin) / (smax - smin);
             for (int k = 0; k < 6; k++) out[i * 6 + k] = t[k];
             int tie = 0;
             int r = bq_treeFind(t, roots[kp], bounds, first, count, biome, &tie);
             chosen[i] = r < 0 ? 0 : r;
             tied[i] = tie || r < 0 ? 1 : 0;
         }
         """;
   }

   private static String single(String[] climate, int slots) {
      return """
         // Climate.quantizeCoord of the function's value as Climate.target takes it: (long)((float)v * 10000f).
         __device__ __forceinline__ long long bq_quantize(double v) {
             float f = (float)v;
             return (long long)(f * 10000.0f);
         }
         // Java's Math.min / Math.clamp for floats as FreeTerraForged uses them (a NaN stays NaN).
         __device__ __forceinline__ float bq_minf(float a, float b) {
             if (a != a) return a;
             if (b != b) return b;
             return a <= b ? a : b;
         }
         __device__ __forceinline__ float bq_clampf(float v, float mn, float mx) {
             if (v != v) return v;
             return v < mn ? mn : (v > mx ? mx : v);
         }
         // FreeTerraForged's UndergroundBiomeBanding hashing: mix64, unit, cellHash.
         __device__ __forceinline__ unsigned long long bq_mix64(unsigned long long z) {
             z = (z ^ (z >> 30)) * (unsigned long long)(-4658895280553007687LL);
             z = (z ^ (z >> 27)) * (unsigned long long)(-7723592293110705685LL);
             return z ^ (z >> 31);
         }
         __device__ __forceinline__ double bq_unit(unsigned long long l) {
             return (double)(long long)(bq_mix64(l) >> 11) * 1.1102230246251565E-16;
         }
         __device__ __forceinline__ unsigned long long bq_cellHash(unsigned long long seed, int x, int y, int z, unsigned long long salt) {
             unsigned long long h = bq_mix64(seed ^ salt ^ (unsigned long long)(-7046029254386353131LL));
             h = bq_mix64(h ^ ((unsigned long long)(unsigned int)x * (unsigned long long)(-2960836687051489901LL)));
             h = bq_mix64(h ^ ((unsigned long long)(unsigned int)y * (unsigned long long)(-6511265742569568659LL)));
             return bq_mix64(h ^ ((unsigned long long)(unsigned int)z * (unsigned long long)(-7031135171492799847LL)));
         }
         // UndergroundBiomeBanding.sample3d(...).occupancy(): the nearest of the 27 jittered cells (ties: the smaller hash).
         __device__ double bq_occupancy(unsigned long long seed, int bx, int by, int bz, int size, int vsize) {
             double fx = (double)bx / (double)size;
             double fy = (double)by / (double)vsize;
             double fz = (double)bz / (double)size;
             int ix = (int)floor(fx);
             int iy = (int)floor(fy);
             int iz = (int)floor(fz);
             double best = __longlong_as_double(0x7ff0000000000000LL);
             long long bestHash = 0;
             for (int dy = -1; dy <= 1; dy++) {
                 for (int dz = -1; dz <= 1; dz++) {
                     for (int dx = -1; dx <= 1; dx++) {
                         int cx = ix + dx;
                         int cy = iy + dy;
                         int cz = iz + dz;
                         unsigned long long h = bq_cellHash(seed, cx, cy, cz, (unsigned long long)4373823286838901223LL);
                         double jx = ((double)cx + 0.5) + (bq_unit(h ^ (unsigned long long)7146057691288625177LL) - 0.5) * 0.4000000059604645;
                         double jy = ((double)cy + 0.5) + (bq_unit(h ^ (unsigned long long)(-4126379630918253789LL)) - 0.5) * 0.4000000059604645;
                         double jz = ((double)cz + 0.5) + (bq_unit(h ^ (unsigned long long)(-3335678366873096957LL)) - 0.5) * 0.4000000059604645;
                         double ex = fx - jx;
                         double ey = fy - jy;
                         double ez = fz - jz;
                         double d = ex * ex + ey * ey + ez * ez;
                         if (d < best || (d == best && (long long)h < bestHash)) {
                             best = d;
                             bestHash = (long long)h;
                         }
                     }
                 }
             }
             return bq_unit((unsigned long long)bestHash ^ (unsigned long long)8476491713452849087LL);
         }
         // UndergroundBiomeBanding.surfaceFactor.
         __device__ __forceinline__ float bq_surfaceFactor(long long depth, long long surface, long long limit, long long add03125) {
             long long s = surface + add03125;
             if (depth <= s || limit <= s) return 0.0f;
             if (depth >= limit) return 1.0f;
             return bq_clampf((float)(depth - s) / (float)(limit - s), 0.0f, 1.0f);
         }
         // FreeTerraForged's UndergroundBiomeClimatePolicy.apply on the depth: kept where a cave biome may be, else 0 (its SURFACE_DEPTH, quantizeCoord(0)).
         __device__ long long bq_policyDepth(long long depth, int qx, int qy, int qz, unsigned long long seed, float coverageScale, int size, int vsize,
                                            long long surface, long long end, long long add21875, long long add03125, int minimumSurface) {
             // UndergroundBiomeSurfaceProtection.coverageFactor.
             float blocks = ((float)depth / 10000.0f - -0.0037500262f) / 0.0078125f;
             float coverage;
             if (blocks <= 24.0f) {
                 coverage = 0.0f;
             } else {
                 if (minimumSurface != 2147483647) blocks = bq_minf(blocks, (float)(minimumSurface - qy * 4 - 5));
                 coverage = bq_clampf((blocks - 16.0f - 8.0f) / 24.0f, 0.0f, 1.0f);
             }
             // UndergroundBiomeBanding.allowsCaveBiome.
             bool allows;
             if (depth < surface || depth > end) {
                 allows = true;
             } else {
                 long long limit = end < surface + add21875 ? end : surface + add21875;
                 float f = coverageScale * bq_minf(bq_surfaceFactor(depth, surface, limit, add03125), bq_clampf(coverage, 0.0f, 1.0f));
                 if (!(f > 0.0f)) {
                     allows = f != f;
                     if (f != f) allows = bq_occupancy(seed, qx * 4, qy * 4, qz * 4, size, vsize) < (double)f;
                 } else if (f >= 1.0f) {
                     allows = true;
                 } else {
                     allows = bq_occupancy(seed, qx * 4, qy * 4, qz * 4, size, vsize) < (double)f;
                 }
             }
             return allows ? depth : 0;
         }
         __device__ __forceinline__ void bq_climateCell(int i, const int* at, const double* cells, int chunkX, int chunkZ, const double* nd, const int* np,
                                                        int banding, double seedBits, float coverageScale, int size, int vsize, int surface, int end,
                                                        int add21875, int add03125, const int* minimumSurface, long long* out, double* ccache,
                                                        unsigned char* ccflag) {
             DCtx c;
             c.x = at[i * 3] * 4;
             c.y = at[i * 3 + 1] * 4;
             c.z = at[i * 3 + 2] * 4;
             c.cells = cells;
             c.point = 0;
             c.points = 0;
             c.nd = nd;
             c.np = np;
             c.grid = 1;
             c.chunkX = chunkX;
             c.chunkZ = chunkZ;
             c.cellMinY = 0;
             c.cellCountY = 0;
             c.cellWidth = 4;
             c.cellHeight = 8;
             c.corners = 0;
             c.cornerStride = 0;
             c.columns = 0;
             c.ccache = ccache;
             c.ccflag = ccflag;
             out[i * 6] = bq_quantize(%s(c));
             out[i * 6 + 1] = bq_quantize(%s(c));
             out[i * 6 + 2] = bq_quantize(%s(c));
             out[i * 6 + 3] = bq_quantize(%s(c));
             out[i * 6 + 4] = bq_quantize(%s(c));
             out[i * 6 + 5] = bq_quantize(%s(c));
             if (banding) {
                 int qx = at[i * 3];
                 int qz = at[i * 3 + 2];
                 int column = (qx - (chunkX >> 2)) * 4 + (qz - (chunkZ >> 2));
                 int minimum = column >= 0 && column < 16 ? minimumSurface[column] : 2147483647;
                 out[i * 6 + 4] = bq_policyDepth(out[i * 6 + 4], qx, at[i * 3 + 1], qz, (unsigned long long)__double_as_longlong(seedBits), coverageScale, size,
                                                 vsize, surface, end, add21875, add03125, minimum);
             }
         }
         extern "C" __global__ void biomeClimate(const int* at, int n, const double* cells, int chunkX, int chunkZ, const double* nd, const int* np, int banding,
                                                 double seedBits, float coverageScale, int size, int vsize, int surface, int end, int add21875, int add03125,
                                                 const int* minimumSurface, long long* out) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             int slices = (n / 16 + BQ_CELLS - 1) / BQ_CELLS;
             if (t >= 16 * slices) return;
             double ccache[BQ_SLOTS];
             unsigned char ccflag[BQ_SLOTS];
             for (int s = 0; s < BQ_SLOTS; s++) ccflag[s] = 0;
             int column = t %% 16;
             int slice = t / 16;
             for (int k = 0; k < BQ_CELLS; k++) {
                 int i = (slice * BQ_CELLS + k) * 16 + column;
                 if (i >= n) break;
                 bq_climateCell(i, at, cells, chunkX, chunkZ, nd, np, banding, seedBits, coverageScale, size, vsize, surface, end, add21875, add03125,
                                minimumSurface, out, ccache, ccflag);
             }
         }
         // Several chunks in one launch (the biome look-ahead): perChunk cells each, chunk j's CellSampler grid at
         // cells + j * cellStride, its corner at chunkXZ[2 j], its 16 minimum surfaces at minimumSurface + 16 j.
         extern "C" __global__ void biomeClimateBatch(const int* at, int n, int perChunk, const double* cells, int cellStride, const int* chunkXZ,
                                                      const double* nd, const int* np, int banding, double seedBits, float coverageScale, int size, int vsize,
                                                      int surface, int end, int add21875, int add03125, const int* minimumSurface, long long* out) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             int slices = (perChunk / 16 + BQ_CELLS - 1) / BQ_CELLS;
             int perChunkThreads = 16 * slices;
             int j = t / perChunkThreads;
             if (j >= n / perChunk) return;
             int r = t - j * perChunkThreads;
             double ccache[BQ_SLOTS];
             unsigned char ccflag[BQ_SLOTS];
             for (int s = 0; s < BQ_SLOTS; s++) ccflag[s] = 0;
             int column = r %% 16;
             int slice = r / 16;
             for (int k = 0; k < BQ_CELLS; k++) {
                 int local = (slice * BQ_CELLS + k) * 16 + column;
                 if (local >= perChunk) break;
                 int i = j * perChunk + local;
                 bq_climateCell(i, at, cells + (long long)j * cellStride, chunkXZ[2 * j], chunkXZ[2 * j + 1], nd, np, banding, seedBits, coverageScale,
                                size, vsize, surface, end, add21875, add03125, minimumSurface + 16 * j, out, ccache, ccflag);
             }
         }
         // Climate.Parameter.distance.
         __device__ __forceinline__ long long bq_distance(long long v, long long mn, long long mx) {
             long long i = v - mx;
             long long j = mn - v;
             return i > 0 ? i : (j > 0 ? j : 0);
         }
         __device__ __forceinline__ long long bq_boxDistance(const long long* t, const int* q) {
             long long d = 0;
             for (int k = 0; k < 7; k++) {
                 long long e = bq_distance(t[k], q[2 * k], q[2 * k + 1]);
                 d += e * e;
             }
             return d;
         }
         // The search through Minecraft's R-tree (flattened): depth first, a child entered while its box distance is at most
         // the best so far, so every point at the smallest distance is seen; chosen = its biome's number, tied when points of
         // two biomes share the smallest distance (the CPU's answer then depends on its last result: left to the CPU).
         extern "C" __global__ void biomeTreeSearch(const long long* in, int n, const int* bounds, const int* first, const int* count, const int* biome,
                                                    int sourceMin, int sourceMax, int targetMax, long long* out, int* chosen, int* tied) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= n) return;
             long long t[7];
             for (int k = 0; k < 6; k++) t[k] = in[i * 6 + k];
             t[6] = 0;
             long long cc = t[2];
             long long smin = sourceMin;
             long long smax = sourceMax;
             long long tmax = targetMax;
             if (!(tmax == smax || cc <= smin || cc >= smax)) t[2] = smin + (cc - smin) * (tmax - smin) / (smax - smin);
             for (int k = 0; k < 6; k++) out[i * 6 + k] = t[k];
             long long best = 0x7fffffffffffffffLL;
             int bestBiome = -1;
             int tie = 0;
             // Nearest children first (each node's children by box distance, the nearest on top), so the first leaf
             // reached is already close and far branches are cut; a node is still entered while its distance is at
             // most the best, so every point at the smallest distance is seen as before.
             int stack[96];
             long long stackD[96];
             int top = 0;
             stack[0] = 0;
             stackD[0] = bq_boxDistance(t, bounds);
             top = 1;
             while (top > 0) {
                 top--;
                 int node = stack[top];
                 long long d = stackD[top];
                 if (d > best) continue;
                 int c = count[node];
                 if (c == 0) {
                     if (d < best) {
                         best = d;
                         bestBiome = biome[node];
                         tie = 0;
                     } else if (biome[node] != bestBiome) {
                         tie = 1;
                     }
                     continue;
                 }
                 if (top + c > 96) {
                     tie = 1;
                     break;
                 }
                 int f = first[node];
                 int base = top;
                 for (int k = 0; k < c; k++) {
                     int child = f + k;
                     long long cd = bq_boxDistance(t, bounds + child * 14);
                     if (cd > best) continue;
                     // Insert keeping [base, top) in falling distance (the nearest last, popped first).
                     int j = top;
                     while (j > base && stackD[j - 1] < cd) {
                         stack[j] = stack[j - 1];
                         stackD[j] = stackD[j - 1];
                         j--;
                     }
                     stack[j] = child;
                     stackD[j] = cd;
                     top++;
                 }
             }
             chosen[i] = bestBiome;
             tied[i] = tie;
         }
         extern "C" __global__ void biomeSearch(const long long* in, int n, const int* points, const int* biomes, int count, int sourceMin, int sourceMax,
                                                int targetMax, long long* out, int* chosen, int* tied) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= n) return;
             long long t[7];
             for (int k = 0; k < 6; k++) t[k] = in[i * 6 + k];
             t[6] = 0;
             // FreeTerraForged's OceanClimateProjection.apply.
             long long cc = t[2];
             long long smin = sourceMin;
             long long smax = sourceMax;
             long long tmax = targetMax;
             if (!(tmax == smax || cc <= smin || cc >= smax)) t[2] = smin + (cc - smin) * (tmax - smin) / (smax - smin);
             for (int k = 0; k < 6; k++) out[i * 6 + k] = t[k];
             long long best = 0x7fffffffffffffffLL;
             int at = 0;
             int tie = 0;
             for (int p = 0; p < count; p++) {
                 const int* q = points + p * 14;
                 long long d = 0;
                 for (int k = 0; k < 7; k++) {
                     long long e = bq_distance(t[k], q[2 * k], q[2 * k + 1]);
                     d += e * e;
                 }
                 if (d < best) {
                     best = d;
                     at = p;
                     tie = 0;
                 } else if (d == best && biomes[p] != biomes[at]) {
                     tie = 1;
                 }
             }
             chosen[i] = at;
             tied[i] = tie;
         }
         // G5.4: FreeTerraForged's biome decoration. UndergroundBiomeSurfaceProtection.coverageFactor of a target's depth.
         __device__ float bq_coverage(long long depth, int qy, int minimumSurface) {
             float blocks = ((float)depth / 10000.0f - -0.0037500262f) / 0.0078125f;
             if (blocks <= 24.0f) return 0.0f;
             if (minimumSurface != 2147483647) blocks = bq_minf(blocks, (float)(minimumSurface - qy * 4 - 5));
             return bq_clampf((blocks - 16.0f - 8.0f) / 24.0f, 0.0f, 1.0f);
         }
         // UndergroundBiomeBanding.sample3d: the occupancy and whether it has a region (the key and identity are the CPU's).
         __device__ double bq_region(unsigned long long seed, int bx, int by, int bz, int size, int vsize) {
             return bq_occupancy(seed, bx, by, bz, size, vsize);
         }
         // Climate.ParameterList.findValue through its R-tree from a root (nearest children first; every node at most the
         // best distance entered, so ties are found): the biome's number, *tie set when two biomes share the smallest distance.
         __device__ int bq_treeFind(const long long* t, int root, const int* bounds, const int* first, const int* count, const int* biome, int* tie) {
             long long best = 0x7fffffffffffffffLL;
             int bestBiome = -1;
             int tied = 0;
             int stack[96];
             long long stackD[96];
             stack[0] = root;
             stackD[0] = bq_boxDistance(t, bounds + root * 14);
             int top = 1;
             while (top > 0) {
                 top--;
                 int node = stack[top];
                 long long d = stackD[top];
                 if (d > best) continue;
                 int c = count[node];
                 if (c == 0) {
                     if (d < best) {
                         best = d;
                         bestBiome = biome[node];
                         tied = 0;
                     } else if (biome[node] != bestBiome) {
                         tied = 1;
                     }
                     continue;
                 }
                 if (top + c > 96) {
                     tied = 1;
                     break;
                 }
                 int f = first[node];
                 int base = top;
                 for (int k = 0; k < c; k++) {
                     int child = f + k;
                     long long cd = bq_boxDistance(t, bounds + child * 14);
                     if (cd > best) continue;
                     int j = top;
                     while (j > base && stackD[j - 1] < cd) {
                         stack[j] = stack[j - 1];
                         stackD[j] = stackD[j - 1];
                         j--;
                     }
                     stack[j] = child;
                     stackD[j] = cd;
                     top++;
                 }
             }
             if (tied) *tie = 1;
             return bestBiome;
         }
         // The decoration stage (MinecraftWorldgenPlanCompiler's ftf_selection_policy) per cell after the search: the
         // underground policy (Layout.findValue / the cave-candidate background) then the terrain roles (rivers).
         // out[i]: the decorated biome's number, or -1 for the CPU (a tie in any search, or a region's stage choice).
         // Chunk j = i / perChunk: its corner chunkXZ[2 j], its 16 minimum surfaces and 16 river flags at 16 j.
         // p (longs): selectionStart, endDepth, deepStart, fullDensityStart, add03125, seed; q (ints): originalRoot,
         // backgroundRoot, riverRoot (-1: none), hasStage, banding, horizontalSize, verticalSize; flags per biome: 1 cave, 2 river.
         extern "C" __global__ void biomeDecorate(const long long* climate, const long long* projected, const int* chosen, const int* tiedIn, const int* at, int n,
                                                  int perChunk, const int* chunkXZ, const int* minimumSurface, const int* rivers, const long long* p, const int* q,
                                                  float density, float presetCoverage, const int* flags, const int* bounds, const int* first, const int* count,
                                                  const int* biome, int* out) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= n) return;
             if (tiedIn[i]) {
                 out[i] = -1;
                 return;
             }
             int j = i / perChunk;
             long long t[7];
             long long pt[7];
             // FreeTerraForged decorates with the search's (projected) target: ProviderResult.target().
             for (int k = 0; k < 6; k++) {
                 t[k] = projected[i * 6 + k];
                 pt[k] = projected[i * 6 + k];
             }
             t[6] = 0;
             pt[6] = 0;
             int qx = at[i * 3];
             int qy = at[i * 3 + 1];
             int qz = at[i * 3 + 2];
             int column = (qx - (chunkXZ[2 * j] >> 2)) * 4 + (qz - (chunkXZ[2 * j + 1] >> 2));
             if (column < 0 || column >= 16) {
                 out[i] = -1;
                 return;
             }
             long long depth = t[4];
             float cov = bq_coverage(depth, qy, minimumSurface[16 * j + column]);
             int tie = 0;
             int r = chosen[i];
             if (depth >= p[0] && depth <= p[1]) {
                 if (!q[3]) {
                     r = bq_treeFind(t, q[0], bounds, first, count, biome, &tie);
                 } else {
                     int bg = bq_treeFind(t, q[1], bounds, first, count, biome, &tie);
                     float f = bq_minf(bq_surfaceFactor(depth, p[0], p[3], p[4]), bq_clampf(cov, 0.0f, 1.0f));
                     float d = density * f;
                     if (d <= 0.0f) {
                         r = bg;
                     } else {
                         double occupancy = bq_region((unsigned long long)p[5], qx * 4, qy * 4, qz * 4, q[5], q[6]);
                         if (d < 1.0f && occupancy >= (double)d) {
                             r = bg;
                         } else if (!q[4]) {
                             r = bq_treeFind(t, q[0], bounds, first, count, biome, &tie);
                         } else {
                             out[i] = -1;
                             return;
                         }
                     }
                 }
             } else if ((flags[r] & 1) && (cov <= 0.0f || presetCoverage <= 0.0f || cov < 1.0f)) {
                 r = bq_treeFind(t, q[1], bounds, first, count, biome, &tie);
             }
             if (!tie && r >= 0 && q[2] >= 0 && !(flags[r] & 2) && !(cov > 0.0f) && rivers[16 * j + column]) {
                 r = bq_treeFind(pt, q[2], bounds, first, count, biome, &tie);
             }
             out[i] = tie || r < 0 ? -1 : r;
         }
         // C1: another provider's table (TerraBlender regions, as Biomes O' Plenty's): its tree from its root and its own
         // ocean projection; outProjected its projected target (for the decoration), outCC its projected continentalness (the
         // only coordinate the projection changes; copied out), chosen its biome's number or -1 and tied 1 (a tie: the CPU's).
         extern "C" __global__ void biomeTreeSearchRoot(const long long* in, int n, int root, const int* bounds, const int* first, const int* count,
                                                        const int* biome, int sourceMin, int sourceMax, int targetMax, long long* outProjected,
                                                        long long* outCC, int* chosen, int* tied) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= n) return;
             long long t[7];
             for (int k = 0; k < 6; k++) t[k] = in[i * 6 + k];
             t[6] = 0;
             long long cc = t[2];
             long long smin = sourceMin;
             long long smax = sourceMax;
             long long tmax = targetMax;
             if (!(tmax == smax || cc <= smin || cc >= smax)) t[2] = smin + (cc - smin) * (tmax - smin) / (smax - smin);
             for (int k = 0; k < 6; k++) outProjected[i * 6 + k] = t[k];
             outCC[i] = t[2];
             int tie = 0;
             int r = bq_treeFind(t, root, bounds, first, count, biome, &tie);
             chosen[i] = tie || r < 0 ? -1 : r;
             tied[i] = tie || r < 0 ? 1 : 0;
         }
         """.formatted((Object[])climate).replace("BQ_SLOTS", Integer.toString(Math.max(1, slots * 2)))
         // Cells per thread: 8 of a column with remembered subtrees, else 1 (thread i, cell i, as before).
         .replace("BQ_CELLS", slots > 0 ? "8" : "1")
         // No cache without slots: the remembered functions then work everything out (as elsewhere).
         .replace("c.ccache = ccache;", slots > 0 ? "c.ccache = ccache;" : "c.ccache = 0;");
   }
}
