package com.wgaccel.gpu;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * GPU step G2.2b: turns vanilla's density functions (the overworld's noise router, as FreeTerraForged builds it) into
 * CUDA C in doubles, like {@link NoiseCodegen} does FreeTerraForged's 2D noise: every distinct node one
 * {@code __device__} function {@code fK(const DCtx& c)}, each kind written operation for operation like its Java
 * compute; splines (floats, as vanilla works them) one function per spline; markers (interpolation and caches) pass
 * their input through, as they do for a single point; FreeTerraForged's CellSampler (its 2D tile cells, cheap on the
 * CPU) is an input worked out on the CPU and handed in per point. NormalNoises are packed once into {@link #noiseData}
 * and {@link #permutations} as {@link VanillaKernels#NOISE} reads them. Reads nodes by reflection (kind by class
 * name, fields by name), so the standalone check can use it with the real Minecraft classes.
 */
public final class DensityCodegen {
   /** A kind with no CUDA version yet; its node and those above it stay on the CPU. */
   public static final class Unsupported extends RuntimeException {
      public final String kind;

      Unsupported(String kind) {
         super(kind, null, false, false);
         this.kind = kind;
      }
   }

   /**
    * How a node is worked out. POINT: one position on its own (markers pass their input through). CORNER: a cell corner
    * of NoiseChunk's slice fill (FlatCache reads its input at the quarter column, y 0; a nested Interpolated is not
    * supported). BLOCK: a block while NoiseChunk fills a cell (Interpolated blends its cell's corners with lerp3,
    * FlatCache as in CORNER).
    */
   public enum Mode {
      POINT("f"),
      CORNER("c"),
      BLOCK("b"),
      /** As BLOCK, but Interpolated takes NoiseInterpolator's step-by-step value (y, then x, then z). */
      BLOCK_STEP("i"),
      /** As BLOCK_STEP for a subtree that depends only on x and z, worked out once per column (no hoisting inside). */
      COLUMN("k");

      final String prefix;

      Mode(String prefix) {
         this.prefix = prefix;
      }
   }

   private final Map<Mode, Map<Object, Integer>> idsByMode = new java.util.EnumMap<>(Mode.class);
   private final Map<Object, Integer> ids;
   private final Map<Object, Integer> interpolators = new IdentityHashMap<>();
   private final List<Object> interpolatorInputs = new ArrayList<>();
   private final Map<Object, String> failed = new IdentityHashMap<>();
   private final Map<Object, Boolean> columnOnly = new IdentityHashMap<>();
   private final Map<Object, Integer> columnIndex = new IdentityHashMap<>();
   private final List<String> columnFunctions = new ArrayList<>();
   private boolean hoistColumns;
   private final Map<Mode, Map<Object, String>> splinesByMode = new java.util.EnumMap<>(Mode.class);
   private final Map<Object, int[]> noises = new IdentityHashMap<>();
   private final Set<Object> children = Collections.newSetFromMap(new IdentityHashMap<>());
   private final List<Object> cellSamplers = new ArrayList<>();
   private final StringBuilder code = new StringBuilder();
   private final DoubleArrayList noiseData = new DoubleArrayList();
   private final IntArrayList permutations = new IntArrayList();
   private int next;
   private int arrays;
   private Mode mode = Mode.POINT;

   public DensityCodegen() {
      for (Mode each : Mode.values()) {
         this.idsByMode.put(each, new IdentityHashMap<>());
      }

      this.ids = this.idsByMode.get(Mode.POINT);
   }

   /** The whole CUDA source: the noise and density helpers, every node emitted so far and the check kernel. */
   public String source(List<String> rootFunctions) {
      StringBuilder kernel = new StringBuilder("""
         extern "C" __global__ void densityCheck(const int* xs, const int* ys, const int* zs, const double* cells, int points, const double* nd,
                                                 const int* np, int first, int offset, double* out) {
             int p = offset + blockIdx.x * blockDim.x + threadIdx.x;
             if (p >= points) return;
             DCtx c;
             c.x = xs[p];
             c.y = ys[p];
             c.z = zs[p];
             c.cells = cells;
             c.point = p;
             c.points = points;
             c.nd = nd;
             c.np = np;
             c.grid = 0;
             c.chunkX = 0;
             c.chunkZ = 0;
             c.cellMinY = 0;
             c.cellCountY = 0;
             c.cellWidth = 4;
             c.cellHeight = 8;
             c.corners = 0;
             c.cornerStride = 0;
             double v;
             switch (first + (int)blockIdx.y) {
         """);
      for (int i = 0; i < rootFunctions.size(); i++) {
         kernel.append("    case ").append(i).append(": v = ").append(rootFunctions.get(i)).append("(c); break;\n");
      }

      kernel.append("""
                 default: v = 0.0;
             }
             out[blockIdx.y * points + p] = v;
         }
         """);
      return VanillaKernels.NOISE + VanillaKernels.DENSITY + this.code + kernel;
   }

   /**
    * The CUDA source for one chunk's final density as NoiseChunk fills it: densityCorners works out every
    * interpolator's input at the cell corners (CORNER mode), densityBlocks every block's final density (BLOCK mode)
    * plus the beardifier's 0 (chunks without structures). CellSampler inputs per block column (grid 1).
    */
   public String chunkSource(String blockFunction, List<String> cornerFunctions) {
      return this.chunkSource(blockFunction, null, cornerFunctions);
   }

   /** As {@link #chunkSource(String, List)}, plus a kernel densityBlocksStep for the BLOCK_STEP root when given. */
   public String chunkSource(String blockFunction, String stepFunction, List<String> cornerFunctions) {
      StringBuilder kernels = new StringBuilder(cornersKernel(cornerFunctions));
      kernels.append(blocksKernel("densityBlocks", blockFunction));
      if (stepFunction != null) {
         kernels.append(blocksKernel("densityBlocksStep", stepFunction));
      }

      return VanillaKernels.NOISE + VanillaKernels.DENSITY + this.code + kernels;
   }

   /**
    * The CUDA source for the noise fill itself (GPU step G2.5): densityCorners as in {@link #chunkSource}, then
    * fillStates, every block's final state code and its fluid update flag in one byte ({@link #fillKernel}). The step
    * function is the final density in BLOCK_STEP mode; the vein and barrier functions as for {@link #statesKernel}.
    */
   public String fillSource(String stepFunction, List<String> cornerFunctions, String toggle, String ridged, String gap, String barrier) {
      return VanillaKernels.NOISE + VanillaKernels.DENSITY + this.code + cornersKernel(cornerFunctions) + columnsKernel(this.columnFunctions)
         + statesHelpers(toggle, ridged, gap, barrier) + fillKernel(stepFunction);
   }

   /** fillColumns: every hoisted subtree per block column, for y 0 and y -1 (the two signs of y), as dc_column reads them. */
   private static String columnsKernel(List<String> columnFunctions) {
      StringBuilder kernel = new StringBuilder("""
         extern "C" __global__ void fillColumns(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                                int cellWidth, int cellHeight, int count, double* columns) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= count * 512) return;
             int k = t / 512;
             int r = t - k * 512;
             int sign = r / 256;
             int column = r - sign * 256;
             DCtx c;
             dc_chunk(c, cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, 0, 0);
             c.x = chunkX + column / 16;
             c.y = sign ? -1 : 0;
             c.z = chunkZ + column % 16;
             double v;
             switch (k) {
         """);
      for (int i = 0; i < columnFunctions.size(); i++) {
         kernel.append("    case ").append(i).append(": v = ").append(columnFunctions.get(i)).append("(c); break;\n");
      }

      kernel.append("""
                 default: v = 0.0;
             }
             columns[t] = v;
         }
         """);
      return kernel.toString();
   }

   private static String cornersKernel(List<String> cornerFunctions) {
      StringBuilder kernels = new StringBuilder("""
         __device__ __forceinline__ void dc_chunk(DCtx& c, const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY,
                                                  int cellCountY, int cellWidth, int cellHeight, const double* corners, int cornerStride) {
             c.cells = cells;
             c.point = 0;
             c.points = 0;
             c.nd = nd;
             c.np = np;
             c.grid = 1;
             c.chunkX = chunkX;
             c.chunkZ = chunkZ;
             c.cellMinY = cellMinY;
             c.cellCountY = cellCountY;
             c.cellWidth = cellWidth;
             c.cellHeight = cellHeight;
             c.corners = corners;
             c.cornerStride = cornerStride;
             c.columns = 0;
         }
         extern "C" __global__ void densityCorners(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                                   int cellWidth, int cellHeight, int interpolators, double* corners) {
             int nx = 16 / cellWidth + 1;
             int ny = cellCountY + 1;
             int per = nx * nx * ny;
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= interpolators * per) return;
             int k = t / per;
             int r = t - k * per;
             int i = r / (nx * ny);
             int rest = r - i * nx * ny;
             int kz = rest / ny;
             int j = rest - kz * ny;
             DCtx c;
             dc_chunk(c, cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, 0, 0);
             c.x = chunkX + i * cellWidth;
             c.y = (j + cellMinY) * cellHeight;
             c.z = chunkZ + kz * cellWidth;
             double v;
             switch (k) {
         """);
      for (int i = 0; i < cornerFunctions.size(); i++) {
         kernels.append("    case ").append(i).append(": v = ").append(cornerFunctions.get(i)).append("(c); break;\n");
      }

      kernels.append("""
                 default: v = 0.0;
             }
             corners[t] = v;
         }
         """);
      return kernels.toString();
   }

   /**
    * The block state kernel densityStates: each block's state as NoiseChunk's MaterialRuleList works it out, the
    * aquifer first (Aquifer$NoiseBasedAquifer.computeSubstance, or the disabled aquifer's picker), then the ore veins
    * (OreVeinifier), else the default block. Codes: 0 none (the default block), 1 to 6 vein blocks as
    * {@link VanillaKernels#RANDOM}, 7 air, 8 the default fluid, 9 lava. The aquifer's grid (its cells' random centres and
    * fluid statuses) and each column's lava level come from the CPU (vanilla's and FreeTerraForged's own code); the
    * vein and barrier functions are in BLOCK_STEP mode (block time). Appended to a {@link #chunkSource} source.
    */
   public static String statesKernel(String toggle, String ridged, String gap, String barrier) {
      return statesHelpers(toggle, ridged, gap, barrier) + """
         extern "C" __global__ void densityStates(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                                  int cellWidth, int cellHeight, const double* corners, int cornerStride, const double* density, long long seedLo,
                                                  long long seedHi, int veins, const int* grid, const int* loc, const int* status, const int* lava, int sea,
                                                  int seaType, int aquifer, int* codes) {
             int height = cellCountY * cellHeight;
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256 * height) return;
             int ly = t / 256;
             int rest = t - ly * 256;
             int lz = rest / 16;
             int lx = rest - lz * 16;
             DCtx c;
             dc_chunk(c, cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, corners, cornerStride);
             c.x = chunkX + lx;
             c.y = cellMinY * cellHeight + ly;
             c.z = chunkZ + lz;
             int code = aq_substance(c, density[t], lx, lz, grid, loc, status, lava, sea, seaType, aquifer);
             if (code == 0 && veins) code = mc_ore_vein(c, seedLo, seedHi);
             codes[t] = code;
         }
         """;
   }

   /** The aquifer and ore vein helpers of {@link #statesKernel} (aq_*, mc_ore_vein), shared with {@link #fillKernel}. */
   private static String statesHelpers(String toggle, String ridged, String gap, String barrier) {
      return VanillaKernels.RANDOM + """
         struct AqStatus {
             int level;
             int type;
         };
         __device__ __forceinline__ int aq_at(AqStatus s, int y) {
             return y < s.level ? s.type : 7;
         }
         __device__ __forceinline__ double aq_similarity(int a, int b) {
             return 1.0 - (double)abs(b - a) / 25.0;
         }
         __device__ __forceinline__ int floor_div(int a, int b) {
             int q = a / b;
             return (a %% b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
         }
         __device__ double aq_pressure(const DCtx& c, int y, AqStatus a, AqStatus b, double* barrier, int* has) {
             int sa = aq_at(a, y);
             int sb = aq_at(b, y);
             if ((sa == 9 && sb == 8) || (sa == 8 && sb == 9)) return 2.0;
             int j = abs(a.level - b.level);
             if (j == 0) return 0.0;
             double d0 = 0.5 * (double)(int)((unsigned int)a.level + (unsigned int)b.level);
             double d1 = (double)y + 0.5 - d0;
             double d2 = (double)j / 2.0;
             double d9 = d2 - fabs(d1);
             double d10;
             if (d1 > 0.0) {
                 double d11 = 0.0 + d9;
                 d10 = d11 > 0.0 ? d11 / 1.5 : d11 / 2.5;
             } else {
                 double d15 = 3.0 + d9;
                 d10 = d15 > 0.0 ? d15 / 3.0 : d15 / 10.0;
             }
             double d12;
             if (!(d10 < -2.0) && !(d10 > 2.0)) {
                 if (!*has) {
                     *barrier = %4$s(c);
                     *has = 1;
                 }
                 d12 = *barrier;
             } else {
                 d12 = 0.0;
             }
             return 2.0 * (d12 + d10);
         }
         // The global fluid picker at a block of the chunk: lava up to the column's lava level (below the sea), else the
         // default fluid up to the sea level.
         __device__ __forceinline__ AqStatus aq_pick(const int* lava, int sea, int seaType, int lx, int lz, int y) {
             int l = lava[lx * 16 + lz];
             AqStatus s;
             if (y < min(l, sea)) {
                 s.level = l;
                 s.type = 9;
             } else {
                 s.level = sea;
                 s.type = seaType;
             }
             return s;
         }
         __device__ int aq_substance(const DCtx& c, double density, int lx, int lz, const int* grid, const int* loc, const int* status, const int* lava,
                                     int sea, int seaType, int enabled) {
             int i = c.x;
             int j = c.y;
             int k = c.z;
             if (density > 0.0) return 0;
             AqStatus pick = aq_pick(lava, sea, seaType, lx, lz, j);
             if (!enabled) return aq_at(pick, j);
             if (aq_at(pick, j) == 9) return 9;
             int l = floor_div(i - 5, 16);
             int i1 = floor_div(j + 1, 12);
             int j1 = floor_div(k - 5, 16);
             int k1 = 2147483647;
             int l1 = 2147483647;
             int i2 = 2147483647;
             int a1 = 0;
             int a2 = 0;
             int a3 = 0;
             for (int i3 = 0; i3 <= 1; i3++) {
                 for (int j3 = -1; j3 <= 1; j3++) {
                     for (int k3 = 0; k3 <= 1; k3++) {
                         int index = ((i1 + j3 - grid[1]) * grid[5] + (j1 + k3 - grid[2])) * grid[3] + (l + i3 - grid[0]);
                         int dx = loc[index * 3] - i;
                         int dy = loc[index * 3 + 1] - j;
                         int dz = loc[index * 3 + 2] - k;
                         int l5 = dx * dx + dy * dy + dz * dz;
                         if (k1 >= l5) {
                             a3 = a2;
                             a2 = a1;
                             a1 = index;
                             i2 = l1;
                             l1 = k1;
                             k1 = l5;
                         } else if (l1 >= l5) {
                             a3 = a2;
                             a2 = index;
                             i2 = l1;
                             l1 = l5;
                         } else if (i2 >= l5) {
                             a3 = index;
                             i2 = l5;
                         }
                     }
                 }
             }
             AqStatus s1 = {status[a1 * 2], status[a1 * 2 + 1]};
             double d1 = aq_similarity(k1, l1);
             int state = aq_at(s1, j);
             if (d1 <= 0.0) return state;
             if (state == 8 && aq_at(aq_pick(lava, sea, seaType, lx, lz, j - 1), j - 1) == 9) return state;
             double barrier = 0.0;
             int has = 0;
             AqStatus s2 = {status[a2 * 2], status[a2 * 2 + 1]};
             double d2 = d1 * aq_pressure(c, j, s1, s2, &barrier, &has);
             if (density + d2 > 0.0) return 0;
             AqStatus s3 = {status[a3 * 2], status[a3 * 2 + 1]};
             double d0 = aq_similarity(k1, i2);
             if (d0 > 0.0) {
                 double d3 = d1 * d0 * aq_pressure(c, j, s1, s3, &barrier, &has);
                 if (density + d3 > 0.0) return 0;
             }
             double d4 = aq_similarity(l1, i2);
             if (d4 > 0.0) {
                 double d5 = d1 * d4 * aq_pressure(c, j, s2, s3, &barrier, &has);
                 if (density + d5 > 0.0) return 0;
             }
             return state;
         }
         __device__ int mc_ore_vein(const DCtx& c, long long seedLo, long long seedHi) {
             double d0 = %1$s(c);
             int y = c.y;
             int copper = d0 > 0.0;
             int maxY = copper ? 50 : -8;
             int minY = copper ? 0 : -60;
             double d1 = fabs(d0);
             int j = maxY - y;
             int k = y - minY;
             if (k < 0 || j < 0) return 0;
             int l = min(j, k);
             double d2 = mth_clamped_map((double)l, 0.0, 20.0, -0.2, 0.0);
             if (d1 + d2 < (double)0.4f) return 0;
             Xrs r = mc_random_at(seedLo, seedHi, c.x, y, c.z);
             if (mc_next_float(r) > 0.7f) return 0;
             if (%2$s(c) >= 0.0) return 0;
             double d3 = mth_clamped_map(d1, (double)0.4f, (double)0.6f, (double)0.1f, (double)0.3f);
             int base = copper ? 1 : 4;
             if ((double)mc_next_float(r) < d3 && %3$s(c) > (double)-0.3f) return base + (mc_next_float(r) < 0.02f ? 1 : 0);
             return base + 2;
         }
         """.formatted(toggle, ridged, gap, barrier);
   }

   /**
    * The noise fill's block kernel fillStates (needs {@link #statesHelpers}): per block the final density (the step
    * function plus the beardifier's 0), then NoiseChunk's MaterialRuleList as in densityStates, written as one byte:
    * the state code (0 to 9, as {@link #statesKernel}) and in bit 4 the aquifer's shouldScheduleFluidUpdate after this
    * block (doFill marks a fluid block for post-processing when it is set). The aquifer argument: 0 the disabled aquifer
    * (its flag is always false), 1 vanilla 1.21.1's NoiseBasedAquifer, 2 C2ME's rewrite of it (a later Minecraft
    * version's logic: the nearest four centres by C2ME's packed order, which breaks equal distances differently, and its
    * own flag rule). Statuses come three ints each: level, type code and an identity number (C2ME compares fluid
    * statuses with equals, which in 1.21.1 is object identity; the CPU numbers the objects).
    */
   private static String fillKernel(String step) {
      return """
         __device__ __forceinline__ AqStatus fa_status(const int* status, int index) {
             AqStatus s = {status[index * 3], status[index * 3 + 1]};
             return s;
         }
         // Vanilla 1.21.1's NoiseBasedAquifer.computeSubstance (as aq_substance), returning the flag in bit 4.
         __device__ int fa_vanilla(const DCtx& c, double density, int lx, int lz, const int* grid, const int* loc, const int* status, const int* lava,
                                   int sea, int seaType) {
             int i = c.x;
             int j = c.y;
             int k = c.z;
             if (density > 0.0) return 0;
             AqStatus pick = aq_pick(lava, sea, seaType, lx, lz, j);
             if (aq_at(pick, j) == 9) return 9;
             int l = floor_div(i - 5, 16);
             int i1 = floor_div(j + 1, 12);
             int j1 = floor_div(k - 5, 16);
             int k1 = 2147483647;
             int l1 = 2147483647;
             int i2 = 2147483647;
             int a1 = 0;
             int a2 = 0;
             int a3 = 0;
             for (int i3 = 0; i3 <= 1; i3++) {
                 for (int j3 = -1; j3 <= 1; j3++) {
                     for (int k3 = 0; k3 <= 1; k3++) {
                         int index = ((i1 + j3 - grid[1]) * grid[5] + (j1 + k3 - grid[2])) * grid[3] + (l + i3 - grid[0]);
                         int dx = loc[index * 3] - i;
                         int dy = loc[index * 3 + 1] - j;
                         int dz = loc[index * 3 + 2] - k;
                         int l5 = dx * dx + dy * dy + dz * dz;
                         if (k1 >= l5) {
                             a3 = a2;
                             a2 = a1;
                             a1 = index;
                             i2 = l1;
                             l1 = k1;
                             k1 = l5;
                         } else if (l1 >= l5) {
                             a3 = a2;
                             a2 = index;
                             i2 = l1;
                             l1 = l5;
                         } else if (i2 >= l5) {
                             a3 = index;
                             i2 = l5;
                         }
                     }
                 }
             }
             AqStatus s1 = fa_status(status, a1);
             double d1 = aq_similarity(k1, l1);
             int state = aq_at(s1, j);
             if (d1 <= 0.0) return state | (d1 >= aq_similarity(100, 144) ? 16 : 0);
             if (state == 8 && aq_at(aq_pick(lava, sea, seaType, lx, lz, j - 1), j - 1) == 9) return state | 16;
             double barrier = 0.0;
             int has = 0;
             AqStatus s2 = fa_status(status, a2);
             double d2 = d1 * aq_pressure(c, j, s1, s2, &barrier, &has);
             if (density + d2 > 0.0) return 0;
             AqStatus s3 = fa_status(status, a3);
             double d0 = aq_similarity(k1, i2);
             if (d0 > 0.0) {
                 double d3 = d1 * d0 * aq_pressure(c, j, s1, s3, &barrier, &has);
                 if (density + d3 > 0.0) return 0;
             }
             double d4 = aq_similarity(l1, i2);
             if (d4 > 0.0) {
                 double d5 = d1 * d4 * aq_pressure(c, j, s2, s3, &barrier, &has);
                 if (density + d5 > 0.0) return 0;
             }
             return state | 16;
         }
         // C2ME's insertion of a packed candidate (distance << 20 | order << 16 | grid index) into the nearest four.
         __device__ __forceinline__ void fa_insert(int p, int* A, int* B, int* C, int* D) {
             if (p <= *C) {
                 int n1 = max(*A, p);
                 *A = min(*A, p);
                 int n2 = max(*B, n1);
                 *B = min(*B, n1);
                 int n3 = max(*C, n2);
                 *C = min(*C, n2);
                 *D = min(*D, n3);
             }
         }
         // C2ME's computeSubstance (opts-worldgen-vanilla MixinAquiferSamplerImpl: refreshDistPosIdx, applyPost,
         // getFinalBlockState), returning the flag in bit 4.
         __device__ int fa_c2me(const DCtx& c, double density, int lx, int lz, const int* grid, const int* loc, const int* status, const int* lava,
                                int sea, int seaType) {
             int i = c.x;
             int j = c.y;
             int k = c.z;
             if (density > 0.0) return 0;
             AqStatus pick = aq_pick(lava, sea, seaType, lx, lz, j);
             if (aq_at(pick, j) == 9) return 9;
             int gx = (i - 5) >> 4;
             int gy = floor_div(j + 1, 12);
             int gz = (k - 5) >> 4;
             int A = 2147483647;
             int B = 2147483647;
             int C = 2147483647;
             int D = 2147483647;
             int order = 12;
             for (int offY = -1; offY <= 1; offY++) {
                 for (int offZ = 0; offZ <= 1; offZ++) {
                     int index0 = ((gy + offY - grid[1]) * grid[5] + (gz + offZ - grid[2])) * grid[3] + (gx - grid[0]);
                     int dx0 = loc[index0 * 3] - i;
                     int dy0 = loc[index0 * 3 + 1] - j;
                     int dz0 = loc[index0 * 3 + 2] - k;
                     fa_insert((dx0 * dx0 + dy0 * dy0 + dz0 * dz0) << 20 | (order - 1) << 16 | index0, &A, &B, &C, &D);
                     int index1 = index0 + 1;
                     int dx1 = loc[index1 * 3] - i;
                     int dy1 = loc[index1 * 3 + 1] - j;
                     int dz1 = loc[index1 * 3 + 2] - k;
                     fa_insert((dx1 * dx1 + dy1 * dy1 + dz1 * dz1) << 20 | (order - 2) << 16 | index1, &A, &B, &C, &D);
                     order -= 2;
                 }
             }
             double flow = aq_similarity(100, 144);
             int dist1 = A >> 20;
             int dist2 = B >> 20;
             int dist3 = C >> 20;
             int dist4 = D >> 20;
             int c1 = A & 65535;
             int c2 = B & 65535;
             int c3 = C & 65535;
             AqStatus s1 = fa_status(status, c1);
             double d = aq_similarity(dist1, dist2);
             int state = aq_at(s1, j);
             if (d <= 0.0) {
                 int flag = d >= flow ? status[c1 * 3 + 2] != status[c2 * 3 + 2] : 0;
                 return state | (flag ? 16 : 0);
             }
             if (state == 8 && aq_at(aq_pick(lava, sea, seaType, lx, lz, j - 1), j - 1) == 9) return state | 16;
             double barrier = 0.0;
             int has = 0;
             AqStatus s2 = fa_status(status, c2);
             double e = d * aq_pressure(c, j, s1, s2, &barrier, &has);
             if (density + e > 0.0) return 0;
             AqStatus s3 = fa_status(status, c3);
             double f = aq_similarity(dist1, dist3);
             if (f > 0.0) {
                 double g = d * f * aq_pressure(c, j, s1, s3, &barrier, &has);
                 if (density + g > 0.0) return 0;
             }
             double h = aq_similarity(dist2, dist3);
             if (h > 0.0) {
                 double g = d * h * aq_pressure(c, j, s2, s3, &barrier, &has);
                 if (density + g > 0.0) return 0;
             }
             int id1 = status[c1 * 3 + 2];
             int id2 = status[c2 * 3 + 2];
             int id3 = status[c3 * 3 + 2];
             int bl = id1 != id2;
             int bl2 = h >= flow && id2 != id3;
             int bl3 = f >= flow && id1 != id3;
             int flag;
             if (!bl && !bl2 && !bl3) {
                 flag = f >= flow && aq_similarity(dist1, dist4) >= flow && D != 2147483647 && id1 != status[(D & 65535) * 3 + 2];
             } else {
                 flag = 1;
             }
             return state | (flag ? 16 : 0);
         }
         %2$s
         extern "C" __global__ void fillStates(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                               int cellWidth, int cellHeight, const double* corners, int cornerStride, long long seedLo, long long seedHi,
                                               int veins, const int* grid, const int* loc, const int* status, const int* lava, int sea, int seaType,
                                               int aquifer, unsigned char* codes, const double* columns, const int* beard, const float* beardKernel) {
             int height = cellCountY * cellHeight;
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256 * height) return;
             int ly = t / 256;
             int rest = t - ly * 256;
             int lz = rest / 16;
             int lx = rest - lz * 16;
             DCtx c;
             dc_chunk(c, cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, corners, cornerStride);
             c.columns = columns;
             c.x = chunkX + lx;
             c.y = cellMinY * cellHeight + ly;
             c.z = chunkZ + lz;
             double density = %s(c) + bf_value(beard, beardKernel, c.x, c.y, c.z);
             int code;
             if (aquifer == 0) {
                 code = density > 0.0 ? 0 : aq_at(aq_pick(lava, sea, seaType, lx, lz, c.y), c.y);
             } else if (aquifer == 1) {
                 code = fa_vanilla(c, density, lx, lz, grid, loc, status, lava, sea, seaType);
             } else {
                 code = fa_c2me(c, density, lx, lz, grid, loc, status, lava, sea, seaType);
             }
             if (code == 0 && veins) code = mc_ore_vein(c, seedLo, seedHi);
             codes[t] = (unsigned char)code;
         }
         """.formatted(step, BEARDIFIER);
   }

   /** S7: the Beardifier on the GPU (bf_value), shared by the fill kernel and the standalone check. */
   public static final String BEARDIFIER = """
         // S7: Minecraft's Beardifier (structure terrain) as C2ME's version sums it (vanilla's gives the same numbers): the
         // pieces in order, then the junctions. B: piece count, junction count, then per piece minX, minY, minZ, maxX,
         // maxY, maxZ, ground level delta, adjustment (0 none, 1 bury, 2 beard thin, 3 beard box, 4 encapsulate), then per
         // junction source x, ground y, z. K: Beardifier.BEARD_KERNEL (24 x 24 x 24 floats).
         __device__ __forceinline__ double bf_fast_inv_sqrt(double v) {
             double d0 = 0.5 * v;
             long long i = __double_as_longlong(v);
             i = 6910469410427058090LL - (i >> 1);
             v = __longlong_as_double(i);
             return v * (1.5 - d0 * v * v);
         }
         __device__ double bf_beard(const float* K, int x, int y, int z, int yy) {
             int i = x + 12;
             int j = y + 12;
             int k = z + 12;
             if (i >= 0 && i < 24 && j >= 0 && j < 24 && k >= 0 && k < 24) {
                 double d0 = (double)yy + 0.5;
                 double d1 = (double)x * (double)x + d0 * d0 + (double)z * (double)z;
                 double d2 = -d0 * bf_fast_inv_sqrt(d1 / 2.0) / 2.0;
                 return d2 * (double)K[k * 576 + i * 24 + j];
             }
             return 0.0;
         }
         __device__ __forceinline__ double bf_bury(double x, double y, double z) {
             double d = sqrt(x * x + y * y + z * z);
             return d > 6.0 ? 0.0 : 1.0 - d / 6.0;
         }
         __device__ double bf_value(const int* B, const float* K, int x, int y, int z) {
             int pieces = B[0];
             int junctions = B[1];
             double d = 0.0;
             const int* p = B + 2;
             for (int n = 0; n < pieces; n++, p += 8) {
                 int m = max(0, max(p[0] - x, x - p[3]));
                 int w = max(0, max(p[2] - z, z - p[5]));
                 int o = p[1] + p[6];
                 int q = y - o;
                 double v;
                 switch (p[7]) {
                     case 1: v = bf_bury((double)m, (double)q / 2.0, (double)w); break;
                     case 2: v = bf_beard(K, m, q, w, q) * 0.8; break;
                     case 3: v = bf_beard(K, m, max(0, max(o - y, y - p[4])), w, q) * 0.8; break;
                     case 4: v = bf_bury((double)m / 2.0, (double)max(0, max(p[1] - y, y - p[4])) / 2.0, (double)w / 2.0) * 0.8; break;
                     default: v = 0.0;
                 }
                 d += v;
             }
             for (int n = 0; n < junctions; n++, p += 3) {
                 int r = x - p[0];
                 int l = y - p[1];
                 int u = z - p[2];
                 d += bf_beard(K, r, l, u, l) * 0.4;
             }
             return d;
         }
         """;

   private static String blocksKernel(String name, String function) {
      return """
         extern "C" __global__ void %s(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                       int cellWidth, int cellHeight, const double* corners, int cornerStride, double* out) {
             int height = cellCountY * cellHeight;
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256 * height) return;
             int ly = t / 256;
             int rest = t - ly * 256;
             int lz = rest / 16;
             int lx = rest - lz * 16;
             DCtx c;
             dc_chunk(c, cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, corners, cornerStride);
             c.x = chunkX + lx;
             c.y = cellMinY * cellHeight + ly;
             c.z = chunkZ + lz;
             out[t] = %s(c) + 0.0;
         }
         """.formatted(name, function);
   }

   /** The packed NormalNoises (VanillaKernels' layout). */
   public double[] noiseData() {
      return this.noiseData.toDoubleArray();
   }

   public int[] permutations() {
      return this.permutations.toIntArray();
   }

   /** The CellSampler nodes in input order: the caller works out each one's value per point (input k at k * points + point). */
   public List<Object> cellSamplers() {
      return this.cellSamplers;
   }

   /** The node's function name for one point on its own, emitting it and everything below it first; throws {@link Unsupported}. */
   public String function(Object node) throws ReflectiveOperationException {
      return this.function(node, Mode.POINT);
   }

   /** The node's function name in this mode, emitting it and everything below it first; throws {@link Unsupported}. */
   public String function(Object node, Mode mode) throws ReflectiveOperationException {
      node = unwrap(node);
      Map<Object, Integer> known = this.idsByMode.get(mode);
      Integer id = known.get(node);
      if (id != null) {
         return mode.prefix + id;
      }

      String missing = mode == Mode.POINT ? this.failed.get(node) : null;
      if (missing != null) {
         throw new Unsupported(missing);
      }

      Mode outer = this.mode;
      this.mode = mode;
      try {
         String body = this.body(node);
         id = this.next++;
         this.code.append("__device__ __noinline__ double ").append(mode.prefix).append(id).append("(const DCtx& c) {\n").append(body).append("}\n");
         known.put(node, id);
         return mode.prefix + id;
      } catch (Unsupported unsupported) {
         if (mode == Mode.POINT) {
            this.failed.put(node, unsupported.kind);
         }

         throw unsupported;
      } finally {
         this.mode = outer;
      }
   }

   /** The Interpolated nodes met in BLOCK mode, in index order: each one's input, worked out at the cell corners (CORNER mode). */
   public List<Object> interpolatorInputs() {
      return this.interpolatorInputs;
   }

   public int id(Object node) {
      return this.ids.getOrDefault(unwrap(node), -1);
   }

   public boolean isChild(Object node) {
      return this.children.contains(unwrap(node));
   }

   /** A node with its holder and C2ME wrappers taken off (what is actually worked out). */
   public static Object unwrap(Object node) {
      try {
         while (true) {
            String kind = kind(node);
            if (kind.equals("HolderHolder")) {
               Object holder = fields(node).get("function");
               node = invoke(holder, "value");
            } else if (kind.equals("CompiledDensityFunction")) {
               Object original = null;
               for (Object value : fields(node).values()) {
                  if (value instanceof Supplier<?> supplier) {
                     original = supplier.get();
                  }
               }

               if (original == null) {
                  return node;
               }

               node = original;
            } else {
               return node;
            }
         }
      } catch (ReflectiveOperationException failure) {
         return node;
      }
   }

   /** A kind's name: the class name after its package (DensityFunctions' records without the outer name). */
   public static String kind(Object node) {
      if (node instanceof Enum<?> constant) {
         return constant.getDeclaringClass().getSimpleName();
      }

      String name = node.getClass().getName();
      String kind = name.substring(name.lastIndexOf('.') + 1);
      return kind.startsWith("DensityFunctions$") ? kind.substring("DensityFunctions$".length()) : kind;
   }

   private String call(Object node) throws ReflectiveOperationException {
      Object actual = unwrap(node);
      if (kind(actual).equals("Constant")) {
         return lit((Double)fields(actual).get("value"));
      }

      this.children.add(actual);
      if (this.hoistColumns && this.mode == Mode.BLOCK_STEP && !kind(actual).equals("CellSampler") && this.columnOnly(actual)) {
         Integer index = this.columnIndex.get(actual);
         if (index == null) {
            String name = this.function(actual, Mode.COLUMN);
            index = this.columnFunctions.size();
            this.columnFunctions.add(name);
            this.columnIndex.put(actual, index);
         }

         return "dc_column(c, " + index + ")";
      }

      return this.function(actual, this.mode) + "(c)";
   }

   /**
    * Column hoisting for the noise fill (off by default; the checks keep the plain per-block code): in BLOCK_STEP mode,
    * every largest subtree that depends only on x and z (CellSampler, a noise with y scale 0, flat caches, constants,
    * and maths and splines of those) is worked out once per block column by the kernel fillColumns and read per block.
    * The same functions on the same inputs, so the same values; it only stops each column repeating them per block.
    */
   public void hoistColumns(boolean hoist) {
      this.hoistColumns = hoist;
   }

   /** The hoisted subtrees' COLUMN functions, in column index order. */
   public List<String> columnFunctions() {
      return this.columnFunctions;
   }

   /** Whether a node's value depends only on the block's x and z (and the sign of y, for a noise with y scale 0). */
   private boolean columnOnly(Object node) throws ReflectiveOperationException {
      Object actual = unwrap(node);
      Boolean known = this.columnOnly.get(actual);
      if (known != null) {
         return known;
      }

      Map<String, Object> f = fields(actual);
      boolean result = switch (kind(actual)) {
         case "Constant", "CellSampler", "BlendAlpha", "BlendOffset" -> true;
         case "Noise" -> (Double)f.get("yScale") == 0.0;
         case "Ap2" -> this.columnOnly(f.get("argument1")) && this.columnOnly(f.get("argument2"));
         case "MulOrAdd", "Mapped", "Clamp", "BlendDensity" -> this.columnOnly(f.get("input"));
         case "RangeChoice" -> this.columnOnly(f.get("input")) && this.columnOnly(f.get("whenInRange")) && this.columnOnly(f.get("whenOutOfRange"));
         case "ClampToNearestUnit" -> this.columnOnly(f.get("function"));
         case "Spline" -> this.splineColumnOnly(f.get("spline"));
         case "Marker" -> {
            String type = ((Enum<?>)f.get("type")).name();
            yield type.equals("FlatCache") || !type.equals("Interpolated") && this.columnOnly(f.get("wrapped"));
         }
         default -> false;
      };
      this.columnOnly.put(actual, result);
      return result;
   }

   private boolean splineColumnOnly(Object spline) throws ReflectiveOperationException {
      String kind = kind(spline);
      if (kind.equals("CubicSpline$Constant")) {
         return true;
      } else if (!kind.equals("CubicSpline$Multipoint")) {
         return false;
      }

      Map<String, Object> f = fields(spline);
      Object coordinate = f.get("coordinate");
      if (!kind(coordinate).equals("Spline$Coordinate") || !this.columnOnly(invoke(fields(coordinate).get("function"), "value"))) {
         return false;
      }

      for (Object value : (List<?>)f.get("values")) {
         if (!this.splineColumnOnly(value)) {
            return false;
         }
      }

      return true;
   }

   /** A marker in the current mode: as NoiseChunk wraps it while filling a chunk, or passing through for a single point. */
   private String marker(Map<String, Object> f) throws ReflectiveOperationException {
      String type = ((Enum<?>)f.get("type")).name();
      Object wrapped = f.get("wrapped");
      if (this.mode == Mode.POINT) {
         return line("return %s;", this.call(wrapped));
      }

      if (type.equals("FlatCache")) {
         Object actual = unwrap(wrapped);
         String flat = kind(actual).equals("Constant") ? lit((Double)fields(actual).get("value")) : this.function(actual, Mode.POINT) + "(dc_flat(c))";
         return line("return %s;", flat);
      }

      if (type.equals("Interpolated")) {
         if (this.mode == Mode.CORNER || this.mode == Mode.COLUMN) {
            throw new Unsupported(this.mode == Mode.CORNER ? "an Interpolated inside an Interpolated" : "an Interpolated in a column subtree");
         }

         Object actual = unwrap(wrapped);
         Integer index = this.interpolators.get(actual);
         if (index == null) {
            this.function(actual, Mode.CORNER);
            index = this.interpolatorInputs.size();
            this.interpolators.put(actual, index);
            this.interpolatorInputs.add(actual);
         }

         return line(this.mode == Mode.BLOCK_STEP ? "return dc_interpolated_step(c, %d);" : "return dc_interpolated(c, %d);", index);
      }

      return line("return %s;", this.call(wrapped));
   }

   private String body(Object node) throws ReflectiveOperationException {
      String kind = kind(node);
      Map<String, Object> f = fields(node);
      return switch (kind) {
         case "Constant" -> line("return %s;", lit((Double)f.get("value")));
         case "Ap2" -> {
            String type = ((Enum<?>)f.get("type")).name();
            Object second = f.get("argument2");
            String a = line("double a = %s;", this.call(f.get("argument1")));
            yield a + switch (type) {
               case "ADD" -> line("return a + %s;", this.call(second));
               case "MUL" -> line("return a == 0.0 ? 0.0 : a * %s;", this.call(second));
               case "MIN" -> line("return a < %s ? a : java_mind(a, %s);", lit(minValue(second)), this.call(second));
               case "MAX" -> line("return a > %s ? a : java_maxd(a, %s);", lit(maxValue(second)), this.call(second));
               default -> throw new Unsupported("Ap2." + type);
            };
         }
         case "MulOrAdd" -> {
            String type = ((Enum<?>)f.get("specificType")).name();
            String input = this.call(f.get("input"));
            String argument = lit((Double)f.get("argument"));
            yield type.equals("MUL") ? line("return %s * %s;", input, argument) : line("return %s + %s;", input, argument);
         }
         case "Mapped" -> {
            String type = ((Enum<?>)f.get("type")).name();
            String v = line("double v = %s;", this.call(f.get("input")));
            yield v + switch (type) {
               case "ABS" -> line("return fabs(v);");
               case "SQUARE" -> line("return v * v;");
               case "CUBE" -> line("return v * v * v;");
               case "HALF_NEGATIVE" -> line("return v > 0.0 ? v : v * 0.5;");
               case "QUARTER_NEGATIVE" -> line("return v > 0.0 ? v : v * 0.25;");
               case "SQUEEZE" -> line("double d = mth_clampd(v, -1.0, 1.0);") + line("return d / 2.0 - d * d * d / 24.0;");
               default -> throw new Unsupported("Mapped." + type);
            };
         }
         case "Clamp" -> line("return mth_clampd(%s, %s, %s);", this.call(f.get("input")), lit((Double)f.get("minValue")), lit((Double)f.get("maxValue")));
         case "RangeChoice" -> line("double v = %s;", this.call(f.get("input")))
            + line("return v >= %s && v < %s ? %s : %s;", lit((Double)f.get("minInclusive")), lit((Double)f.get("maxExclusive")),
               this.call(f.get("whenInRange")), this.call(f.get("whenOutOfRange")));
         case "YClampedGradient" -> {
            double fromY = (Integer)f.get("fromY");
            double toY = (Integer)f.get("toY");
            yield line("double t = ((double)c.y - %s) / (%s - %s);", lit(fromY), lit(toY), lit(fromY))
               + line("return t < 0.0 ? %s : (t > 1.0 ? %s : %s + t * (%s - %s));", lit((Double)f.get("fromValue")), lit((Double)f.get("toValue")),
                  lit((Double)f.get("fromValue")), lit((Double)f.get("toValue")), lit((Double)f.get("fromValue")));
         }
         case "Noise" -> {
            String noise = this.noise(f.get("noise"));
            yield noise == null ? line("return 0.0;")
               : line("return %s, (double)c.x * %s, (double)c.y * %s, (double)c.z * %s);", noise, lit((Double)f.get("xzScale")), lit((Double)f.get("yScale")),
                  lit((Double)f.get("xzScale")));
         }
         case "WeirdScaledSampler" -> {
            String mapper = ((Enum<?>)f.get("rarityValueMapper")).name();
            String rarity = switch (mapper) {
               case "TYPE1" -> "mc_spaghetti_rarity_3d";
               case "TYPE2" -> "mc_spaghetti_rarity_2d";
               default -> throw new Unsupported("WeirdScaledSampler." + mapper);
            };
            String noise = this.noise(f.get("noise"));
            yield line("double e = %s(%s);", rarity, this.call(f.get("input")))
               + (noise == null ? line("return e * fabs(0.0);")
                  : line("return e * fabs(%s, (double)c.x / e, (double)c.y / e, (double)c.z / e));", noise));
         }
         case "Spline" -> line("return (double)%s(c);", this.spline(f.get("spline")));
         case "Marker" -> this.marker(f);
         case "BlendAlpha" -> line("return 1.0;");
         case "BlendOffset" -> line("return 0.0;");
         case "BlendDensity" -> line("return %s;", this.call(f.get("input")));
         case "CellSampler" -> {
            int index = this.cellSamplers.indexOf(node);
            if (index < 0) {
               index = this.cellSamplers.size();
               this.cellSamplers.add(node);
            }

            yield line("return dc_cell(c, %d);", index);
         }
         case "ClampToNearestUnit" -> {
            int resolution = (Integer)f.get("resolution");
            yield line("double v = %s;", this.call(f.get("function"))) + line("double scaled = v * (double)%d;", resolution)
               + line("if (!isfinite(scaled)) return v;") + line("double truncated = scaled < 0.0 ? ceil(scaled) : floor(scaled);")
               + line("return (truncated + 1.0) / (double)%d;", resolution);
         }
         default -> throw new Unsupported(kind);
      };
   }

   /** A CubicSpline as a float function {@code sK(const DCtx& c)}; returns its name. */
   private String spline(Object spline) throws ReflectiveOperationException {
      Map<Object, String> splines = this.splinesByMode.computeIfAbsent(this.mode, m -> new IdentityHashMap<>());
      String known = splines.get(spline);
      if (known != null) {
         return known;
      }

      String kind = kind(spline);
      Map<String, Object> f = fields(spline);
      String body;
      if (kind.equals("CubicSpline$Constant")) {
         body = line("return %s;", litf((Float)f.get("value")));
      } else if (kind.equals("CubicSpline$Multipoint")) {
         float[] locations = (float[])f.get("locations");
         float[] derivatives = (float[])f.get("derivatives");
         List<?> values = (List<?>)f.get("values");
         Object coordinate = f.get("coordinate");
         if (!kind(coordinate).equals("Spline$Coordinate")) {
            throw new Unsupported("spline coordinate " + kind(coordinate));
         }

         Object function = invoke(fields(coordinate).get("function"), "value");
         String coordinateCall = this.call(function);
         List<String> children = new ArrayList<>();
         for (Object value : values) {
            children.add(this.spline(value));
         }

         String loc = this.floatArray(locations);
         String der = this.floatArray(derivatives);
         int n = locations.length;
         StringBuilder text = new StringBuilder();
         text.append(line("float f = (float)%s;", coordinateCall));
         text.append(line("int i = mc_spline_interval(%s, %d, f);", loc, n));
         text.append(line("if (i < 0) return mc_linear_extend(f, %s, %s(c), %s, 0);", loc, children.get(0), der));
         text.append(line("if (i == %d) return mc_linear_extend(f, %s, %s(c), %s, %d);", n - 1, loc, children.get(n - 1), der, n - 1));
         text.append(line("float v0;"));
         text.append(line("float v1;"));
         text.append(line("switch (i) {"));
         for (int i = 0; i < n - 1; i++) {
            text.append(line("case %d: v0 = %s(c); v1 = %s(c); break;", i, children.get(i), children.get(i + 1)));
         }

         text.append(line("default: v0 = 0.0f; v1 = 0.0f;"));
         text.append(line("}"));
         text.append(line("float f1 = %s[i];", loc));
         text.append(line("float f2 = %s[i + 1];", loc));
         text.append(line("float f3 = (f - f1) / (f2 - f1);"));
         text.append(line("float f4 = %s[i];", der));
         text.append(line("float f5 = %s[i + 1];", der));
         text.append(line("float f8 = f4 * (f2 - f1) - (v1 - v0);"));
         text.append(line("float f9 = -f5 * (f2 - f1) + (v1 - v0);"));
         text.append(line("return mth_lerpf(f3, v0, v1) + f3 * (1.0f - f3) * mth_lerpf(f3, f8, f9);"));
         body = text.toString();
      } else {
         throw new Unsupported("spline " + kind);
      }

      String name = "s" + this.next++;
      this.code.append("__device__ __noinline__ float ").append(name).append("(const DCtx& c) {\n").append(body).append("}\n");
      splines.put(spline, name);
      return name;
   }

   /** The call prefix "mc_normal(c.nd + D, c.np + P" for a NoiseHolder's NormalNoise (packed once), or null if it has none. */
   private String noise(Object holder) throws ReflectiveOperationException {
      Object noise = fields(holder).get("noise");
      return noise == null ? null : this.normalNoise(noise);
   }

   /** As {@link #noise} for a NormalNoise itself (the surface rules' noises), packed once into the same arrays. */
   public String normalNoise(Object noise) throws ReflectiveOperationException {
      int[] offsets = this.noises.get(noise);
      if (offsets == null) {
         offsets = new int[] {this.noiseData.size(), this.permutations.size()};
         this.noiseData.add((Double)field(noise, "valueFactor"));
         this.pack(field(noise, "first"));
         this.pack(field(noise, "second"));
         this.noises.put(noise, offsets);
      }

      return "mc_normal(c.nd + " + offsets[0] + ", c.np + " + offsets[1];
   }

   private void pack(Object perlin) throws ReflectiveOperationException {
      Object[] levels = (Object[])field(perlin, "noiseLevels");
      DoubleList amplitudes = (DoubleList)field(perlin, "amplitudes");
      this.noiseData.add((Double)field(perlin, "lowestFreqInputFactor"));
      this.noiseData.add((Double)field(perlin, "lowestFreqValueFactor"));
      this.noiseData.add(levels.length);
      for (int i = 0; i < levels.length; i++) {
         Object level = levels[i];
         this.noiseData.add(amplitudes.getDouble(i));
         this.noiseData.add(level == null ? 0.0 : (Double)field(level, "xo"));
         this.noiseData.add(level == null ? 0.0 : (Double)field(level, "yo"));
         this.noiseData.add(level == null ? 0.0 : (Double)field(level, "zo"));
         this.noiseData.add(level == null ? 0.0 : 1.0);
         byte[] permutation = level == null ? null : (byte[])field(level, "p");
         for (int k = 0; k < 256; k++) {
            this.permutations.add(permutation == null ? 0 : permutation[k] & 0xFF);
         }
      }
   }

   private String floatArray(float[] values) {
      String name = "a" + this.arrays++;
      StringBuilder text = new StringBuilder("__device__ const float ").append(name).append('[').append(Math.max(1, values.length)).append("] = {");
      for (int i = 0; i < values.length; i++) {
         text.append(i == 0 ? "" : ", ").append(litf(values[i]));
      }

      this.code.append(text.append(values.length == 0 ? "0.0f" : "").append("};\n"));
      return name;
   }

   private static String line(String format, Object... arguments) {
      return "    " + (arguments.length == 0 ? format : String.format(Locale.ROOT, format, arguments)) + "\n";
   }

   /** An exact C double literal (hex), in parentheses. */
   static String lit(double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
         return String.format(Locale.ROOT, "__longlong_as_double(0x%016xLL)", Double.doubleToRawLongBits(value));
      }

      return "(" + Double.toHexString(value) + ")";
   }

   static String litf(float value) {
      return NoiseCodegen.lit(value);
   }

   private static double minValue(Object node) throws ReflectiveOperationException {
      return (Double)invoke(unwrap(node), "minValue");
   }

   private static double maxValue(Object node) throws ReflectiveOperationException {
      return (Double)invoke(unwrap(node), "maxValue");
   }

   static Object invoke(Object target, String method) throws ReflectiveOperationException {
      Method found = target.getClass().getMethod(method);
      found.setAccessible(true);
      return found.invoke(target);
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(target.getClass().getName() + "." + name);
   }

   /** A record's components, or an object's non-static fields, by name. */
   static Map<String, Object> fields(Object object) throws ReflectiveOperationException {
      Map<String, Object> values = new TreeMap<>();
      Class<?> type = object.getClass();
      if (type.isRecord()) {
         for (RecordComponent component : type.getRecordComponents()) {
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            values.put(component.getName(), accessor.invoke(object));
         }

         return values;
      }

      // Java's own classes (Enum's name and ordinal) are closed to reflection and never hold a density function.
      for (Class<?> c = type; c != null && !c.getName().startsWith("java."); c = c.getSuperclass()) {
         for (Field field : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
               try {
                  field.setAccessible(true);
                  values.putIfAbsent(field.getName(), field.get(object));
               } catch (RuntimeException ignored) {
               }
            }
         }
      }

      return values;
   }
}
