package com.wgaccel.gpu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * GPU step G3.2: the overworld's surface rule source (vanilla's SurfaceRules as FreeTerraForged builds them) as CUDA:
 * every rule node a {@code __device__ int rK(const SCtx& c)} giving a block state index or -1 (null, the next rule),
 * every condition a {@code bool qK(const SCtx& c)}, written as each kind's Java does it. The rule context's values
 * (stone depths, water height, surface depth and secondary, min surface level, the block's biome, steepness and
 * temperature, FreeTerraForged's strata layers for the column) are inputs; the conditions' lazy caches change no result,
 * as every condition is a pure function of them. Vanilla noises at (x, 0, z) and positional random draws are worked
 * out on the GPU. Reads nodes by reflection (kinds by class name).
 */
public final class SurfaceCodegen {
   private final StringBuilder code = new StringBuilder();
   private final Map<Object, String> rules = new IdentityHashMap<>();
   private final Map<Object, String> conditions = new IdentityHashMap<>();
   private final Map<BlockState, Integer> states = new IdentityHashMap<>();
   private final List<BlockState> stateList = new ArrayList<>();
   private final Map<String, Integer> kinds = new java.util.TreeMap<>();
   private final DensityCodegen noises = new DensityCodegen();
   private final RandomState random;
   private final WorldGenerationContext context;
   private final Registry<Biome> biomes;
   private final Object system;
   private int next;
   private String root;
   /**
    * FreeTerraForged's FastStrataSequenceRule (its 740a69e3 build, 23 Oct 2026): its surface wraps the rule tree, deep
    * stone short-cut to deepslate or straight to the strata, and its StrataRule gives no block (the stone stays) where its
    * layer is plain stone. Both followed here when FreeTerraForged has the class.
    */
   private final boolean fastStrata;
   /** The prepass's flags of the column's first stone's biome (2 badlands, 4 desert): its expression of fb, or none. */
   private String firstFlags = null;

   public SurfaceCodegen(RandomState random, WorldGenerationContext context, Registry<Biome> biomes, Object surfaceSystem) {
      this.random = random;
      this.context = context;
      this.biomes = biomes;
      this.system = surfaceSystem;
      boolean fast;
      try {
         Class.forName("etcodehome.freeterraforged.world.worldgen.surface.rule.FastStrataSequenceRule");
         fast = true;
      } catch (ClassNotFoundException | LinkageError absent) {
         fast = false;
      }

      this.fastStrata = fast;
   }

   /** Whether this FreeTerraForged wraps its surface rules in FastStrataSequenceRule (the tree to generate is then its optimize()). */
   public boolean fastStrata() {
      return this.fastStrata;
   }

   /** Generates the whole tree under the rule source; returns the root rule's function name. */
   public String generate(Object ruleSource) throws ReflectiveOperationException {
      if (this.fastStrata) {
         // FastStrataSequenceRule.tryApply's per-column flags, from the biome at the column's first rule call (its first
         // stone, top down): badlands (BiomeTags.IS_BADLANDS) and desert (Biomes.DESERT or a key path holding "desert").
         List<Object> badlands = new java.util.ArrayList<>();
         List<Object> deserts = new java.util.ArrayList<>();
         for (Map.Entry<ResourceKey<Biome>, Biome> entry : this.biomes.entrySet()) {
            net.minecraft.core.Holder<Biome> holder = this.biomes.getHolderOrThrow(entry.getKey());
            if (holder.is(net.minecraft.tags.BiomeTags.IS_BADLANDS)) {
               badlands.add(entry.getKey());
            }

            if (holder.is(net.minecraft.world.level.biome.Biomes.DESERT) || entry.getKey().location().getPath().contains("desert")) {
               deserts.add(entry.getKey());
            }
         }

         int words = Math.max(1, (this.biomes.size() + 31) / 32);
         this.firstFlags = String.format(Locale.ROOT, "(sf_in(fb, %s, %d) ? 2 : 0) | (sf_in(fb, %s, %d) ? 4 : 0)", this.maskTable(badlands), words,
            this.maskTable(deserts), words);
      }

      this.root = this.rule(ruleSource);
      return this.root;
   }

   /** A block state's index (the GPU's output codes); states met only at run time (strata materials) are added too. */
   public synchronized int state(BlockState state) {
      Integer known = this.states.get(state);
      if (known == null) {
         known = this.stateList.size();
         this.states.put(state, known);
         this.stateList.add(state);
      }

      return known;
   }

   public synchronized BlockState stateAt(int index) {
      return index < 0 || index >= this.stateList.size() ? null : this.stateList.get(index);
   }

   public Map<String, Integer> kinds() {
      return this.kinds;
   }

   public double[] noiseData() {
      return this.noises.noiseData();
   }

   public int[] permutations() {
      return this.noises.permutations();
   }

   /** The whole CUDA source: helpers, the tree and the check kernel surfaceCheck (one thread per recorded rule call). */
   public String source() {
      return VanillaKernels.NOISE + VanillaKernels.RANDOM + HELPERS + this.code + """
         extern "C" __global__ void surfaceCheck(const int* calls, int count, const double* secondary, const float* strataDepth, const int* strataState,
                                                 const int* strataCount, int strataMax, const double* nd, const int* np, int* out) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= count) return;
             const int* r = calls + t * 13;
             SCtx c;
             c.fastFlags = 0;
             c.x = r[0];
             c.y = r[1];
             c.z = r[2];
             c.stoneAbove = r[3];
             c.stoneBelow = r[4];
             c.water = r[5];
             c.depth = r[6];
             c.biome = r[7];
             c.cold = r[8];
             c.steep = r[9];
             c.slope = r[12];
             c.minSurface = r[10];
             int column = r[11];
             c.secondary = secondary[column];
             c.strataDepth = strataDepth + column * strataMax;
             c.strataState = strataState + column * strataMax;
             c.strataCount = strataCount[column];
             c.nd = nd;
             c.np = np;
             out[t] = %s(c);
         }
         """.formatted(this.root);
   }

   /**
    * GPU step G3.3c: the whole column loop of SurfaceSystem.buildSurface, one thread per block column, with every input
    * from the GPU ({@link SurfaceInputs}' helpers and code; the strata layers from {@link StrataGpu}): from the column's
    * top (WORLD_SURFACE_WG + 1) down to the world's floor, air resets the stone depth above and the water height, fluid
    * sets the water height, stone counts the depth above and (scanning down to the next non-stone) below, and every
    * default-block stone tries the rule tree. Block kinds per block: 0 air, 1 fluid, 2 the default block, 3 other stone
    * ((column * height) + y - minY); out the same, the rule's state index or -1. special marks columns whose biome at the
    * top is eroded badlands or a frozen ocean (vanilla's extensions, left to the CPU).
    */
   public String loopSource(SurfaceInputs inputs, int wayBelowMinY, int erodedBadlands, int frozenOcean, int deepFrozenOcean) {
      return loopSource(inputs.helpers(), inputs.surfaceDepthCode(), this.code.toString(), this.root, wayBelowMinY, erodedBadlands, frozenOcean, deepFrozenOcean);
   }

   /** As {@link #loopSource(SurfaceInputs, int, int, int, int)} from its parts (the standalone check passes stand-ins). */
   public static String loopSource(String inputHelpers, String surfaceDepthCode, String ruleCode, String root, int wayBelowMinY, int erodedBadlands,
         int frozenOcean, int deepFrozenOcean) {
      String depthCode = surfaceDepthCode.replace("(nd + ", "(ind + ").replace(", np + ", ", inp + ");
      return SurfaceInputs.steep(VanillaKernels.NOISE + VanillaKernels.RANDOM + inputHelpers + HELPERS + ruleCode + """
         extern "C" __global__ void surfaceLoop(const unsigned char* kinds, int minY, int height, const int* tops, int minX, int minZ, const int* heights,
                                                const int* minBase, const float* strataDepth, const int* strataState, const int* strataCount, int strataMax,
                                                const int* grid, const int* g, long long zoomSeed, const float* temperature, const int* modifier,
                                                const double* sd, const int* sp, const double* ind, const int* inp, const double* nd, const int* np, int* out,
                                                int* special) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             int lx = t / 16;
             int lz = t - lx * 16;
             int x = minX + lx;
             int z = minZ + lz;
             const unsigned char* kind = kinds + t * height;
             int* result = out + t * height;
             for (int i = 0; i < height; i++) result[i] = -1;
         %s
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
             int top = tops[t];
             int columnBiome = bm_biome(grid, g, zoomSeed, x, top, z);
             special[t] = columnBiome == %d || columnBiome == %d || columnBiome == %d || columnBiome < 0 ? 1 : 0;
             SxData sx;
             sx.nd = sd;
             sx.np = sp;
             SCtx c;
             c.fastFlags = 0;
             c.x = x;
             c.z = z;
             c.depth = depth;
             c.secondary = secondary;
             c.steep = steep;
             {
                 // C1: Lithostitched's SlopeCondition: the highest minus the lowest of the four neighbours' heights.
                 int sn = heights[lx * 16 + max(lz - 1, 0)];
                 int ss = heights[lx * 16 + min(lz + 1, 15)];
                 int se = heights[min(lx + 1, 15) * 16 + lz];
                 int sw = heights[max(lx - 1, 0) * 16 + lz];
                 c.slope = max(max(sn, ss), max(se, sw)) - min(min(sn, ss), min(se, sw));
             }
             c.minSurface = minBase[t] + depth - 8;
             c.strataDepth = strataDepth + t * strataMax;
             c.strataState = strataState + t * strataMax;
             c.strataCount = strataCount[t];
             c.nd = nd;
             c.np = np;
             int i2 = 0;
             int j2 = (-2147483647 - 1);
             int k2 = 2147483647;
             for (int i3 = top; i3 >= minY; i3--) {
                 int k = i3 >= minY && i3 < minY + height ? kind[i3 - minY] : 0;
                 if (k == 0) {
                     i2 = 0;
                     j2 = (-2147483647 - 1);
                 } else if (k == 1) {
                     if (j2 == (-2147483647 - 1)) j2 = i3 + 1;
                 } else {
                     if (k2 >= i3) {
                         k2 = %d;
                         for (int j3 = i3 - 1; j3 >= minY - 1; j3--) {
                             int kk = j3 >= minY && j3 < minY + height ? kind[j3 - minY] : 0;
                             if (kk < 2) {
                                 k2 = j3 + 1;
                                 break;
                             }
                         }
                     }
                     i2++;
                     int k3 = i3 - k2 + 1;
                     if (k == 2) {
                         c.y = i3;
                         c.stoneAbove = i2;
                         c.stoneBelow = k3;
                         c.water = j2;
                         c.biome = bm_biome(grid, g, zoomSeed, x, i3, z);
                         c.cold = c.biome >= 0 ? bm_cold(sx, temperature, modifier, c.biome, x, i3, z) : 0;
                         result[i3 - minY] = %s(c);
                     }
                 }
             }
         }
         """.formatted(depthCode, erodedBadlands, frozenOcean, deepFrozenOcean, wayBelowMinY, root));
   }

   /**
    * GPU step G3.4: the surface for use, in two kernels on the noise fill's shared context: surfacePrepass (one thread
    * per column, vanilla's walk without the rules: for every default-block stone it reaches, the stone depth above (at
    * least 1), below and the water height; the column's top biome for vanilla's extensions) and surfaceBlocks (one
    * thread per block: the rule tree for each stone the walk reached). The strata layers come from {@link StrataGpu}'s
    * kernels in their own module. Layout per block (y - minY) * 256 + column, so neighbouring threads read neighbouring
    * memory.
    */
   public String productionSource(SurfaceInputs inputs, int wayBelowMinY, int erodedBadlands, int frozenOcean, int deepFrozenOcean) {
      return productionSource(inputs.helpers(), inputs.surfaceDepthCode(), this.code.toString(), this.root, wayBelowMinY, erodedBadlands, frozenOcean,
         deepFrozenOcean, this.firstFlags);
   }

   /**
    * I3: the prepass's column walk done by a warp of 32 threads per column (the old kernel walks each column on one
    * thread, ~450 dependent steps). The same above, below and water: below is the length of the run of solid blocks
    * (kind 2 or more) ending at y, counted upwards from minY; above and water come from a downward scan in steps of 32
    * blocks with ballots (above: solid blocks since the last air, y included; water: the highest fluid since the last air,
    * plus one). above is written everywhere (0 but at stone), below and water at stone only, as the old walk does.
    */
   public static final String PREPASS_WARP = """
      __device__ void prepassWarpScan(const unsigned char* kinds, int minY, int height, int top, int t, int lane, int* above, int* below, int* water) {
          const unsigned int all = 0xffffffffu;
          const int none = (-2147483647 - 1);
          int last = minY + height - 1;
          for (int y = max(top + 1, minY) + lane; y <= last; y += 32) above[(y - minY) * 256 + t] = 0;
          int high = min(top, last);
          int run = 0;
          for (int base = minY; base <= high; base += 32) {
              int y = base + lane;
              int k = y <= high ? kinds[(y - minY) * 256 + t] : 0;
              unsigned int solid = __ballot_sync(all, k >= 2);
              unsigned int breaks = ~solid & ((2u << lane) - 1u);
              int r = breaks != 0u ? lane - (31 - __clz(breaks)) : run + lane + 1;
              if (k == 2) below[(y - minY) * 256 + t] = r;
              run = __shfl_sync(all, r, 31);
          }
          int i2 = 0;
          int j2 = none;
          for (int base = high; base >= minY; base -= 32) {
              int y = base - lane;
              int k = y >= minY ? kinds[(y - minY) * 256 + t] : 0;
              unsigned int air = __ballot_sync(all, k == 0);
              unsigned int solid = __ballot_sync(all, k >= 2);
              unsigned int fluid = __ballot_sync(all, k == 1);
              unsigned int upTo = (2u << lane) - 1u;
              unsigned int airs = air & upTo;
              int count;
              int w;
              if (airs != 0u) {
                  int a = 31 - __clz(airs);
                  unsigned int after = upTo & ~((2u << a) - 1u);
                  count = __popc(solid & after);
                  unsigned int f = fluid & after;
                  w = f != 0u ? base - (__ffs(f) - 1) + 1 : none;
              } else {
                  count = i2 + __popc(solid & upTo);
                  unsigned int f = fluid & upTo;
                  w = j2 != none ? j2 : (f != 0u ? base - (__ffs(f) - 1) + 1 : none);
              }
              if (y >= minY) {
                  int at = (y - minY) * 256 + t;
                  if (k == 2) {
                      above[at] = count;
                      water[at] = w;
                  } else {
                      above[at] = 0;
                  }
              }
              i2 = __shfl_sync(all, count, 31);
              j2 = __shfl_sync(all, w, 31);
          }
      }
      """;

   public static String productionSource(String inputHelpers, String surfaceDepthCode, String ruleCode, String root, int wayBelowMinY, int erodedBadlands,
         int frozenOcean, int deepFrozenOcean, String firstFlags) {
      String depthCode = surfaceDepthCode.replace("(nd + ", "(ind + ").replace(", np + ", ", inp + ");
      // FastStrataSequenceRule's flags (special's bits 2 and 4) from the biome at the column's first stone, top down.
      String firstSingle = firstFlags == null ? "" : """
             if (firstStone != (-2147483647 - 1)) {
                 int fb = bm_biome(grid, g, zoomSeed, x, firstStone, z);
                 special[t] |= FLAGS;
             }
         """.replace("FLAGS", firstFlags);
      String firstWarp = firstFlags == null ? "" : """
                 int firstStone = (-2147483647 - 1);
                 for (int y = min(top, minY + height - 1); y >= minY; y--) {
                     if (kinds[(y - minY) * 256 + t] == 2) {
                         firstStone = y;
                         break;
                     }
                 }
                 if (firstStone != (-2147483647 - 1)) {
                     int fb = bm_biome(grid, g, zoomSeed, x, firstStone, z);
                     special[t] |= FLAGS;
                 }
         """.replace("FLAGS", firstFlags);
      return SurfaceInputs.steep(VanillaKernels.NOISE + VanillaKernels.RANDOM + inputHelpers + HELPERS + ruleCode + """
         extern "C" __global__ void surfacePrepass(const unsigned char* kinds, int minY, int height, const int* tops, int minX, int minZ, const int* grid,
                                                   const int* g, long long zoomSeed, const double* ind, const int* inp, int* above, int* below,
                                                   int* water, int* columnDepth, double* columnSecondary, int* special) {
             int t = blockIdx.x * blockDim.x + threadIdx.x;
             if (t >= 256) return;
             int x = minX + t / 16;
             int z = minZ + t %% 16;
         %s
             columnDepth[t] = depth;
             columnSecondary[t] = secondary;
             int top = tops[t];
             int columnBiome = bm_biome(grid, g, zoomSeed, x, top, z);
             special[t] = columnBiome == %d || columnBiome == %d || columnBiome == %d || columnBiome < 0 ? 1 : 0;
             for (int i = 0; i < height; i++) above[i * 256 + t] = 0;
             int i2 = 0;
             int j2 = (-2147483647 - 1);
             int k2 = 2147483647;
             int firstStone = (-2147483647 - 1);
             for (int i3 = top; i3 >= minY; i3--) {
                 int k = i3 >= minY && i3 < minY + height ? kinds[(i3 - minY) * 256 + t] : 0;
                 if (k == 0) {
                     i2 = 0;
                     j2 = (-2147483647 - 1);
                 } else if (k == 1) {
                     if (j2 == (-2147483647 - 1)) j2 = i3 + 1;
                 } else {
                     if (k2 >= i3) {
                         k2 = %d;
                         for (int j3 = i3 - 1; j3 >= minY - 1; j3--) {
                             int kk = j3 >= minY && j3 < minY + height ? kinds[(j3 - minY) * 256 + t] : 0;
                             if (kk < 2) {
                                 k2 = j3 + 1;
                                 break;
                             }
                         }
                     }
                     i2++;
                     if (k == 2) {
                         if (firstStone == (-2147483647 - 1)) firstStone = i3;
                         int at = (i3 - minY) * 256 + t;
                         above[at] = i2;
                         below[at] = i3 - k2 + 1;
                         water[at] = j2;
                     }
                 }
             }
         WGA_FIRST_SINGLE
         }
         extern "C" __global__ void surfaceBlocks(int minY, int height, int minX, int minZ, const int* heights, const int* minBase, const int* above,
                                                  const int* below, const int* water, const int* columnDepth, const double* columnSecondary,
                                                  const float* strataDepth, const int* strataState, const int* strataCount, int strataMax, const int* grid,
                                                  const int* g, long long zoomSeed, const float* temperature, const int* modifier, const double* sd,
                                                  const int* sp, const double* nd, const int* np, const int* special, short* out) {
             int i = blockIdx.x * blockDim.x + threadIdx.x;
             if (i >= 256 * height) return;
             int ly = i >> 8;
             int t = i & 255;
             int stone = above[i];
             if (stone == 0) {
                 out[i] = -1;
                 return;
             }
             int lx = t / 16;
             int lz = t - lx * 16;
             int x = minX + lx;
             int z = minZ + lz;
             int y = minY + ly;
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
             SxData sx;
             sx.nd = sd;
             sx.np = sp;
             SCtx c;
             c.fastFlags = 0;
             c.x = x;
             c.y = y;
             c.z = z;
             c.stoneAbove = stone;
             c.stoneBelow = below[i];
             c.water = water[i];
             c.depth = columnDepth[t];
             c.secondary = columnSecondary[t];
             c.steep = steep;
             {
                 // C1: Lithostitched's SlopeCondition: the highest minus the lowest of the four neighbours' heights.
                 int sn = heights[lx * 16 + max(lz - 1, 0)];
                 int ss = heights[lx * 16 + min(lz + 1, 15)];
                 int se = heights[min(lx + 1, 15) * 16 + lz];
                 int sw = heights[max(lx - 1, 0) * 16 + lz];
                 c.slope = max(max(sn, ss), max(se, sw)) - min(min(sn, ss), min(se, sw));
             }
             c.minSurface = minBase[t] + c.depth - 8;
             c.fastFlags = special[t] >> 1;
             c.strataDepth = strataDepth + t * strataMax;
             c.strataState = strataState + t * strataMax;
             c.strataCount = strataCount[t];
             c.nd = nd;
             c.np = np;
             c.biome = bm_biome(grid, g, zoomSeed, x, y, z);
             c.cold = c.biome >= 0 ? bm_cold(sx, temperature, modifier, c.biome, x, y, z) : 0;
             out[i] = (short)%s(c);
         }
         """.formatted(depthCode, erodedBadlands, frozenOcean, deepFrozenOcean, wayBelowMinY, root) + PREPASS_WARP + """
         extern "C" __global__ void surfacePrepassWarp(const unsigned char* kinds, int minY, int height, const int* tops, int minX, int minZ, const int* grid,
                                                       const int* g, long long zoomSeed, const double* ind, const int* inp, int* above, int* below,
                                                       int* water, int* columnDepth, double* columnSecondary, int* special) {
             int t = blockIdx.x * 8 + (threadIdx.x >> 5);
             int lane = threadIdx.x & 31;
             if (t >= 256) return;
             int top = tops[t];
             if (lane == 0) {
                 int x = minX + t / 16;
                 int z = minZ + t %% 16;
         %s
                 columnDepth[t] = depth;
                 columnSecondary[t] = secondary;
                 int columnBiome = bm_biome(grid, g, zoomSeed, x, top, z);
                 special[t] = columnBiome == %d || columnBiome == %d || columnBiome == %d || columnBiome < 0 ? 1 : 0;
         WGA_FIRST_WARP
             }
             prepassWarpScan(kinds, minY, height, top, t, lane, above, below, water);
         }
         """.formatted(depthCode, erodedBadlands, frozenOcean, deepFrozenOcean)).replace("WGA_FIRST_SINGLE", firstSingle).replace("WGA_FIRST_WARP", firstWarp);
   }

   /** The number of block states the rules can give (indexes fit a short while under 32,767). */
   public synchronized int stateCount() {
      return this.stateList.size();
   }

   private static final String HELPERS = """
      struct SCtx {
          int x;
          int y;
          int z;
          int stoneAbove;
          int stoneBelow;
          int water;
          int depth;
          int biome;
          int cold;
          int steep;
          int slope;
          int minSurface;
          int fastFlags;
          double secondary;
          const float* strataDepth;
          const int* strataState;
          int strataCount;
          const double* nd;
          const int* np;
      };
      // Mth.map: lerp(inverseLerp(v, a, b), c, d).
      __device__ __forceinline__ double sf_map(double v, double a, double b, double c, double d) {
          double t = (v - a) / (b - a);
          return c + t * (d - c);
      }
      // Java's (int) of a double: NaN 0, clamped.
      __device__ __forceinline__ int sf_d2i(double v) {
          if (isnan(v)) return 0;
          if (v >= 2147483647.0) return 2147483647;
          if (v <= -2147483648.0) return (-2147483647 - 1);
          return (int)v;
      }
      // Java's Math.round(double) for the values met here (exact: x - floor(x) has no rounding error).
      __device__ __forceinline__ long long sf_round(double v) {
          if (isnan(v)) return 0;
          double f = floor(v);
          long long r = (long long)f;
          return v - f >= 0.5 ? r + 1 : r;
      }
      __device__ __forceinline__ bool sf_in(int id, const unsigned int* mask, int words) {
          return id >= 0 && (id >> 5) < words && (mask[id >> 5] >> (id & 31) & 1u) != 0u;
      }
      // FreeTerraForged's StrataRule.Source.tryApply: the first layer whose depth lies below y, else the last one.
      __device__ int sf_strata(const SCtx& c) {
          int last = -1;
          for (int i = 0; i < c.strataCount; i++) {
              last = c.strataState[i];
              if ((float)c.y > c.strataDepth[i]) return last;
          }
          return last;
      }
      """;

   private static String kind(Object node) {
      String name = node.getClass().getName();
      return name.substring(name.lastIndexOf('.') + 1);
   }

   private String rule(Object node) throws ReflectiveOperationException {
      String known = this.rules.get(node);
      if (known != null) {
         return known;
      }

      String kind = kind(node);
      this.kinds.merge(kind, 1, Integer::sum);
      String body;
      switch (kind) {
         // C1: Lithostitched's TransientMergedRule (Terralith and other mods' rules merged in): its one rule, or its rules as a
         // vanilla sequence (TransientMergedRule.apply), the same as a SequenceRuleSource of them.
         case "SurfaceRules$SequenceRuleSource", "TransientMergedRule" -> {
            StringBuilder text = new StringBuilder();
            for (Object child : (List<?>)field(node, kind.equals("TransientMergedRule") ? "rules" : "sequence")) {
               text.append(line("{ int v = %s(c); if (v >= 0) return v; }", this.rule(child)));
            }

            body = text + line("return -1;");
         }
         // C1: Lithostitched's ReferenceRule (rules from its registry, the first that answers) and Regions Unexplored's
         // ConfigRuleSource (its config, read once: the branch the world uses).
         case "ReferenceRule" -> {
            StringBuilder text = new StringBuilder();
            for (Object holder : (Iterable<?>)field(node, "rules")) {
               text.append(line("{ int v = %s(c); if (v >= 0) return v; }", this.rule(((net.minecraft.core.Holder<?>)holder).value())));
            }

            body = text + line("return -1;");
         }
         case "ConfigRuleSource" -> body = line("return %s(c);", this.rule(configBranch(node)));
         case "SurfaceRules$TestRuleSource" -> body = line("if (!%s(c)) return -1;", this.condition(field(node, "ifTrue"))) + line("return %s(c);", this.rule(field(node, "thenRun")));
         case "SurfaceRules$BlockRuleSource" -> body = line("return %d;", this.state((BlockState)field(node, "resultState")));
         case "SurfaceRules$Bandlands" -> body = this.bandlands();
         case "StrataRule" -> body = !this.fastStrata ? line("return sf_strata(c);")
            : line("int v = sf_strata(c);") + line("return v == %d ? -1 : v;", this.state(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
         // FreeTerraForged's FastStrataSequenceRule.tryApply: outside badlands, deep stone below y 0 is deepslate and deep
         // stone above y 8 goes straight to the strata; else its base rules, then the strata. Flags from the prepass.
         case "FastStrataSequenceRule$Source" -> {
            Object strataSource = field(node, "strata");
            String strata = strataSource == null ? "-1" : this.rule(strataSource) + "(c)";
            body = line("if ((c.fastFlags & 1) == 0) {")
               + line("    if (c.y <= 0 && c.y > -59 && c.stoneAbove > 2 && (c.stoneBelow <= 0 || c.stoneBelow > 1)) return %d;",
                  this.state(net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState()))
               + line("    int minAbove = (c.fastFlags & 2) != 0 ? 12 : 5;")
               + line("    if (c.y > 8 && c.stoneAbove > minAbove && (c.stoneBelow <= 0 || c.stoneBelow > 3)) return %s;", strata)
               + line("}")
               + line("{ int v = %s(c); if (v >= 0) return v; }", this.rule(field(node, "base")))
               + line("return %s;", strata);
         }
         case "AcquiredSurfaceRule" -> body = line("return %s(c);", this.rule(field(node, "root")));
         default -> throw new DensityCodegen.Unsupported("surface rule " + kind);
      }

      String name = "r" + this.next++;
      this.code.append("__device__ __noinline__ int ").append(name).append("(const SCtx& c) {\n").append(body).append("}\n");
      this.rules.put(node, name);
      return name;
   }

   /** SurfaceSystem.getBand with FreeTerraForged's safe y (Math.floorMod(y, 192) + 3840) and the clay band table. */
   private String bandlands() throws ReflectiveOperationException {
      BlockState[] bands = (BlockState[])field(this.system, "clayBands");
      String noise = this.noises.normalNoise(field(this.system, "clayBandsOffsetNoise"));
      StringBuilder table = new StringBuilder("__device__ const int bands").append(this.next).append('[').append(bands.length).append("] = {");
      for (int i = 0; i < bands.length; i++) {
         table.append(i == 0 ? "" : ", ").append(this.state(bands[i]));
      }

      this.code.append(table).append("};\n");
      return line("int y = ((c.y % 192) + 192) % 192 + 3840;") + line("int i = (int)sf_round(%s, (double)c.x, 0.0, (double)c.z) * 4.0);", noise)
         + line("return bands%d[(y + i + %d) %% %d];", this.next, bands.length, bands.length);
   }

   private String condition(Object node) throws ReflectiveOperationException {
      String known = this.conditions.get(node);
      if (known != null) {
         return known;
      }

      String kind = kind(node);
      this.kinds.merge(kind, 1, Integer::sum);
      String body = switch (kind) {
         case "SurfaceRules$BiomeConditionSource" -> this.biomeCondition(node);
         // C1: Lithostitched's conditions: all of / any of (in order, stopping as Java's loops do), a biome HolderSet.
         case "AllOfCondition", "AnyOfCondition" -> {
            boolean all = kind.equals("AllOfCondition");
            StringBuilder text = new StringBuilder();
            for (Object child : (List<?>)field(node, "conditions")) {
               text.append(line(all ? "if (!%s(c)) return false;" : "if (%s(c)) return true;", this.condition(child)));
            }

            yield text + line(all ? "return true;" : "return false;");
         }
         case "BiomeCondition" -> this.biomeMask(holderKeys(field(node, "biomes")));
         case "SurfaceRules$NoiseThresholdConditionSource" -> {
            @SuppressWarnings("unchecked")
            NormalNoise noise = this.random.getOrCreateNoise((ResourceKey<NormalNoise.NoiseParameters>)field(node, "noise"));
            yield line("double d = %s, (double)c.x, 0.0, (double)c.z);", this.noises.normalNoise(noise))
               + line("return d >= %s && d <= %s;", DensityCodegen.lit((Double)field(node, "minThreshold")), DensityCodegen.lit((Double)field(node, "maxThreshold")));
         }
         case "SurfaceRules$VerticalGradientConditionSource" -> {
            int i = ((VerticalAnchor)field(node, "trueAtAndBelow")).resolveY(this.context);
            int j = ((VerticalAnchor)field(node, "falseAtAndAbove")).resolveY(this.context);
            Object factory = this.random.getOrCreateRandomFactory((ResourceLocation)field(node, "randomName"));
            if (!kind(factory).equals("XoroshiroRandomSource$XoroshiroPositionalRandomFactory")) {
               throw new DensityCodegen.Unsupported("positional random " + kind(factory));
            }

            yield line("int k = c.y;") + line("if (k <= %d) return true;", i) + line("if (k >= %d) return false;", j)
               + line("double d0 = sf_map((double)k, (double)%d, (double)%d, 1.0, 0.0);", i, j)
               + line("Xrs r = mc_random_at(%dLL, %dLL, c.x, k, c.z);", (Long)field(factory, "seedLo"), (Long)field(factory, "seedHi"))
               + line("return (double)mc_next_float(r) < d0;");
         }
         case "SurfaceRules$YConditionSource" -> line("return c.y + (%s ? c.stoneAbove : 0) >= %d + c.depth * %d;", (Boolean)field(node, "addStoneDepth"),
            ((VerticalAnchor)field(node, "anchor")).resolveY(this.context), (Integer)field(node, "surfaceDepthMultiplier"));
         case "SurfaceRules$WaterConditionSource" -> line("return c.water == (-2147483647 - 1) || c.y + (%s ? c.stoneAbove : 0) >= c.water + %d + c.depth * %d;",
            (Boolean)field(node, "addStoneDepth"), (Integer)field(node, "offset"), (Integer)field(node, "surfaceDepthMultiplier"));
         case "SurfaceRules$StoneDepthCheck" -> {
            boolean ceiling = ((Enum<?>)field(node, "surfaceType")).name().equals("CEILING");
            int range = (Integer)field(node, "secondaryDepthRange");
            yield line("int i = %s;", ceiling ? "c.stoneBelow" : "c.stoneAbove") + line("int j = %s;", (Boolean)field(node, "addSurfaceDepth") ? "c.depth" : "0")
               + line("int k = %s;", range == 0 ? "0" : "sf_d2i(sf_map(c.secondary, -1.0, 1.0, 0.0, (double)" + range + "))")
               + line("return i <= 1 + %d + j + k;", (Integer)field(node, "offset"));
         }
         // C1: Lithostitched's SlopeCondition (InclusiveRange.isValueInRange of the neighbours' height difference).
         case "SlopeCondition" -> {
            Object range = field(node, "threshold");
            yield line("return c.slope >= %d && c.slope <= %d;", (Integer)field(range, "minInclusive"), (Integer)field(range, "maxInclusive"));
         }
         case "SurfaceRules$NotConditionSource" -> line("return !%s(c);", this.condition(field(node, "target")));
         case "SurfaceRules$Hole" -> line("return c.depth <= 0;");
         case "SurfaceRules$Steep" -> line("return c.steep != 0;");
         case "SurfaceRules$Temperature" -> line("return c.cold != 0;");
         case "SurfaceRules$AbovePreliminarySurface" -> line("return c.y >= c.minSurface;");
         default -> throw new DensityCodegen.Unsupported("surface condition " + kind);
      };
      String name = "q" + this.next++;
      this.code.append("__device__ __noinline__ bool ").append(name).append("(const SCtx& c) {\n").append(body).append("}\n");
      this.conditions.put(node, name);
      return name;
   }

   private String biomeCondition(Object node) throws ReflectiveOperationException {
      return this.biomeMask((List<?>)field(node, "biomes"));
   }

   /** C1: a HolderSet's biome keys (Lithostitched's BiomeCondition tests HolderSet.contains of the block's biome). */
   private static List<Object> holderKeys(Object set) {
      List<Object> keys = new java.util.ArrayList<>();
      for (Object holder : (Iterable<?>)set) {
         ((net.minecraft.core.Holder<?>)holder).unwrapKey().ifPresent(keys::add);
      }

      return keys;
   }

   /** C1: Regions Unexplored's ConfigRuleSource: its on_enabled or on_disabled rule, as RUConfigHandler.COMMON.test(key) says. */
   private static Object configBranch(Object node) throws ReflectiveOperationException {
      Class<?> handler = Class.forName("net.regions_unexplored.config.RUConfigHandler", true, node.getClass().getClassLoader());
      Object common = handler.getField("COMMON").get(null);
      java.lang.reflect.Method test = common.getClass().getMethod("test", String.class);
      boolean enabled = (Boolean)test.invoke(common, (String)field(node, "key"));
      return field(node, enabled ? "onEnabled" : "onDisabled");
   }

   private String biomeMask(List<?> keys) {
      int words = Math.max(1, (this.biomes.size() + 31) / 32);
      return line("return sf_in(c.biome, %s, %d);", this.maskTable(keys), words);
   }

   /** A biome id bit table of these keys; returns its name. */
   private String maskTable(List<?> keys) {
      int words = Math.max(1, (this.biomes.size() + 31) / 32);
      int[] mask = new int[words];
      for (Object key : keys) {
         @SuppressWarnings("unchecked")
         Biome biome = this.biomes.get((ResourceKey<Biome>)key);
         if (biome != null) {
            int id = this.biomes.getId(biome);
            mask[id >> 5] |= 1 << (id & 31);
         }
      }

      String name = "m" + this.next++;
      StringBuilder table = new StringBuilder("__device__ const unsigned int ").append(name).append('[').append(words).append("] = {");
      for (int i = 0; i < words; i++) {
         table.append(i == 0 ? "" : ", ").append(Integer.toUnsignedString(mask[i])).append('u');
      }

      this.code.append(table).append("};\n");
      return name;
   }

   private static String line(String format, Object... arguments) {
      return "    " + (arguments.length == 0 ? format : String.format(Locale.ROOT, format, arguments)) + "\n";
   }

   static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      if (target.getClass().isRecord()) {
         Method accessor = target.getClass().getDeclaredMethod(name);
         accessor.setAccessible(true);
         return accessor.invoke(target);
      }

      throw new NoSuchFieldException(target.getClass().getName() + "." + name);
   }
}
