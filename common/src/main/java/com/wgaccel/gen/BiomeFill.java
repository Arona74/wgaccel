package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

/**
 * One chunk's biome fill (made thread-local between {@link #begin} and {@link #end}): the region identities it samples
 * (measuring), and exact column/cell memos for FreeTerraForged's biome selection & decoration (config {@code fasterBiomeChoice}):
 *
 * <ul>
 *   <li>Sky-column biome memo: in FreeTerraForged 1.0.0027, ~75% of all 4,160 cells in a 65-section chunk are strictly
 *       above surfaceY ({@code target.depth() < 0L}). Above surfaceY, horizontal climate channels (T, H, C, E, W),
 *       spatial domain, surface biome selection, underground layout ({@code appliesAt == false}), surface protection
 *       ({@code coverage == 0}), terrain role selection, and replacement plan are 100% invariant down the vertical
 *       quart column as depth becomes more negative. Caching the resolved {@code Holder<Biome>} per quart column
 *       for {@code target.depth() <= skyFirstDepth[slot] < 0L} (verified every 64th reuse) skips ~3,000 redundant
 *       R-tree searches and decoration calls per chunk.</li>
 *   <li>Column underground background biome memo: in underground cells ({@code target.depth() > 0L}), {@code Layout.background}
 *       contains only surface biomes ({@code maxDepth <= 0}), so {@code background.findValue(target)} depends only on
 *       the column's 5 horizontal parameters. Memoizing per column reduces 850 background R-tree searches/chunk to <= 16.</li>
 *   <li>Terrain per column: read once per quart column instead of once per vertical cell.</li>
 * </ul>
 */
public final class BiomeFill {
   public static final LongAdder CHUNKS = new LongAdder();
   public static final LongAdder IDENTITIES = new LongAdder();
   public static final LongAdder DISTINCT = new LongAdder();
   public static final LongAdder ROLE_HITS = new LongAdder();
   public static final LongAdder ROLE_MISSES = new LongAdder();
   public static final LongAdder COVERAGE_HITS = new LongAdder();
   public static final LongAdder STAGE_HITS = new LongAdder();
   public static final LongAdder SKY_HITS = new LongAdder();
   public static final LongAdder CHECKS = new LongAdder();
   public static final LongAdder MISMATCHES = new LongAdder();
   private static final AtomicInteger WARNINGS = new AtomicInteger();
   private static final int CHECK_EVERY = 64;
   private static final java.util.concurrent.atomic.AtomicLong CELL_SAMPLE_TURN = new java.util.concurrent.atomic.AtomicLong();
   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private BiomeFill() {
   }

   public static void begin() {
      State state = STATE.get();
      state.seen.clear();
      state.terrain.clear();
      state.lookup = null;
      state.roleLookup = null;
      java.util.Arrays.fill(state.roleHas, false);
      state.roleSubmergedLookup = null;
      state.roleSubmergedWaterLevel = Float.NaN;
      java.util.Arrays.fill(state.roleSubmergedHas, false);
      state.nonRiverBiome = null;
      state.riverBiome = null;
      state.baseBiomeCandidate = null;
      state.spatialPlan = null;
      java.util.Arrays.fill(state.roleLastIn, null);
      java.util.Arrays.fill(state.roleLastOut, null);
      java.util.Arrays.fill(state.skyHas, false);
      java.util.Arrays.fill(state.skySpatial, null);
      java.util.Arrays.fill(state.skyBiome, null);
      java.util.Arrays.fill(state.bgHas, false);
      java.util.Arrays.fill(state.bgBiome, null);
      java.util.Arrays.fill(state.bgLeaf, null);
      java.util.Arrays.fill(state.bgOtherLast, null);
      state.bgList = null;
      state.bgCheckedList = null;
      state.bgCheckedUniform = false;
      state.bgCheckedLastResult = null;
      state.coverageValid = false;
      state.stageChoices.clear();
      state.memo = Config.FASTER_BIOME_CHOICE.getAsBoolean();
      state.timeCells = (CELL_SAMPLE_TURN.getAndIncrement() & 15L) == 0L;
      state.active = true;
   }

   public static State state() {
      return STATE.get();
   }

   /** True on 1 in 16 biome chunk fills so per-cell System.nanoTime() telemetry adds minimal overhead. */
   public static boolean timingCells() {
      return STATE.get().timeCells;
   }

   public static void end() {
      State state = STATE.get();
      state.active = false;
      state.timeCells = false;
      state.terrain.clear();
      state.lookup = null;
      state.roleLookup = null;
      java.util.Arrays.fill(state.roleHas, false);
      state.roleSubmergedLookup = null;
      state.roleSubmergedWaterLevel = Float.NaN;
      java.util.Arrays.fill(state.roleSubmergedHas, false);
      state.nonRiverBiome = null;
      state.riverBiome = null;
      state.baseBiomeCandidate = null;
      state.spatialPlan = null;
      java.util.Arrays.fill(state.roleLastIn, null);
      java.util.Arrays.fill(state.roleLastOut, null);
      java.util.Arrays.fill(state.skyHas, false);
      java.util.Arrays.fill(state.skySpatial, null);
      java.util.Arrays.fill(state.skyBiome, null);
      java.util.Arrays.fill(state.bgHas, false);
      java.util.Arrays.fill(state.bgBiome, null);
      java.util.Arrays.fill(state.bgLeaf, null);
      java.util.Arrays.fill(state.bgOtherLast, null);
      state.bgList = null;
      state.bgCheckedList = null;
      state.bgCheckedUniform = false;
      state.bgCheckedLastResult = null;
      state.coverageValid = false;
      state.stageChoices.clear();
      CHUNKS.increment();
      DISTINCT.add(state.seen.size());
   }

   public static void identity(int quartX, int quartY, int quartZ) {
      IDENTITIES.increment();
      State state = STATE.get();
      if (state.active) {
         state.seen.add((quartX & 0x1FFFFFL) | (quartY & 0x1FFFFFL) << 21 | (quartZ & 0x1FFFFFL) << 42);
      }
   }

   /** The state of this thread's fill when the memos may be used, else null. */
   public static State memo() {
      State state = STATE.get();
      return state.active && state.memo ? state : null;
   }

   public static void checked(boolean same, String what) {
      CHECKS.increment();
      if (!same) {
         MISMATCHES.increment();
         if (WARNINGS.incrementAndGet() <= 20) {
            WorldgenAccelerator.LOG.warn("WGA faster biome choice: a reused {} differs from the normal one; please report", what);
         }
      }
   }

   public static final class State {
      public boolean active;
      public boolean memo;
      public boolean timeCells;
      final ClimateColumns.State climate = new ClimateColumns.State();
      final LongOpenHashSet seen = new LongOpenHashSet();
      final Long2ObjectOpenHashMap<Object> terrain = new Long2ObjectOpenHashMap<>();
      Object lookup;
      Object roleLookup;
      public final long[] roleKeys = new long[16];
      public final boolean[] roleHas = new boolean[16];
      public final Object[] roleTerrain = new Object[16];
      public final float[] roleWater = new float[16];
      public final float[] roleHeight = new float[16];
      public Object roleCell;
      Object roleSubmergedLookup;
      float roleSubmergedWaterLevel = Float.NaN;
      public final long[] roleSubmergedKeys = new long[16];
      public final boolean[] roleSubmergedHas = new boolean[16];
      public final boolean[] roleSubmerged = new boolean[16];
      Object nonRiverBiome;
      Object riverBiome;
      public Holder<Biome> baseBiomeCandidate;
      public boolean baseBiomeFallback;
      public int currentQuartX;
      public int currentQuartZ;
      public Object spatialPlan;
      public long spatialCellX = Long.MIN_VALUE;
      public long spatialCellZ = Long.MIN_VALUE;
      public Object spatialCached;
      public final Object[] roleLastIn = new Object[16];
      public final Object[] roleLastOut = new Object[16];
      public final Object[] roleLastCandidates = new Object[16];
      public final long[] roleLastDepth = new long[16];

      /** Sky-column biome cache (for cells above surfaceY where target.depth() < 0L). */
      public boolean skyDisabled;
      public final boolean[] skyHas = new boolean[16];
      public final long[] skyColKey = new long[16];
      public final long[] skyMaxDepth = new long[16];
      public final Object[] skySpatial = new Object[16];
      public final long[] skyT = new long[16];
      public final long[] skyH = new long[16];
      public final long[] skyC = new long[16];
      public final long[] skyE = new long[16];
      public final long[] skyW = new long[16];
      public final Object[] skyBiome = new Object[16];

      /** Column background surface biome cache for cave columns where isCaveCandidate(baseBiome) is true. */
      public boolean bgDisabled;
      public Object bgList;
      public Object bgCheckedList;
      public boolean bgCheckedUniform;
      public ThreadLocal<Object> bgCheckedLastResult;
      public final boolean[] bgHas = new boolean[16];
      public final long[] bgColKey = new long[16];
      public final long[] bgT = new long[16];
      public final long[] bgH = new long[16];
      public final long[] bgC = new long[16];
      public final long[] bgE = new long[16];
      public final long[] bgW = new long[16];
      public final Object[] bgBiome = new Object[16];
      public final Object[] bgLeaf = new Object[16];
      public final Object[] bgOtherLast = new Object[16];

      boolean coverageValid;
      Object coverageSampler;
      Object coverageTarget;
      int coverageX;
      int coverageY;
      int coverageZ;
      float coverage;
      long reuses;
      final HashMap<StageKey, Object> stageChoices = new HashMap<>();

      public boolean skyCached(int slot, int quartX, int quartZ, long depth) {
         return !this.skyDisabled && this.skyHas[slot] && this.skyColKey[slot] == columnKey(quartX, quartZ) && depth <= this.skyMaxDepth[slot];
      }

      public boolean skyCached(int slot, int quartX, int quartZ, Object spatial, net.minecraft.world.level.biome.Climate.TargetPoint target) {
         return !this.skyDisabled && this.skyHas[slot] && this.skyColKey[slot] == columnKey(quartX, quartZ)
            && target.depth() <= this.skyMaxDepth[slot]
            && this.skySpatial[slot] == spatial
            && this.skyT[slot] == target.temperature()
            && this.skyH[slot] == target.humidity()
            && this.skyC[slot] == target.continentalness()
            && this.skyE[slot] == target.erosion()
            && this.skyW[slot] == target.weirdness();
      }

      public void rememberSky(int slot, int quartX, int quartZ, long depth, Holder<Biome> biome) {
         if (!this.skyDisabled && biome != null) {
            this.skyHas[slot] = true;
            this.skyColKey[slot] = columnKey(quartX, quartZ);
            this.skyMaxDepth[slot] = depth;
            this.skyBiome[slot] = biome;
         }
      }

      public void rememberSky(int slot, int quartX, int quartZ, Object spatial, net.minecraft.world.level.biome.Climate.TargetPoint target, Holder<Biome> biome) {
         if (!this.skyDisabled && biome != null) {
            this.skyHas[slot] = true;
            this.skyColKey[slot] = columnKey(quartX, quartZ);
            this.skyMaxDepth[slot] = target.depth();
            this.skySpatial[slot] = spatial;
            this.skyT[slot] = target.temperature();
            this.skyH[slot] = target.humidity();
            this.skyC[slot] = target.continentalness();
            this.skyE[slot] = target.erosion();
            this.skyW[slot] = target.weirdness();
            this.skyBiome[slot] = biome;
         }
      }

      public boolean bgCached(Object list, int slot, int quartX, int quartZ, net.minecraft.world.level.biome.Climate.TargetPoint target, Object last) {
         return list == this.bgList && this.bgHas[slot] && this.bgColKey[slot] == columnKey(quartX, quartZ)
            && this.bgT[slot] == target.temperature()
            && this.bgH[slot] == target.humidity()
            && this.bgC[slot] == target.continentalness()
            && this.bgE[slot] == target.erosion()
            && this.bgW[slot] == target.weirdness()
            && (last == this.bgLeaf[slot] || last == this.bgOtherLast[slot]);
      }

      public void rememberBg(Object list, int slot, int quartX, int quartZ, net.minecraft.world.level.biome.Climate.TargetPoint target,
            Object biome, Object beforeLast, Object afterLeaf) {
         if (list != this.bgList) {
            java.util.Arrays.fill(this.bgHas, false);
            java.util.Arrays.fill(this.bgLeaf, null);
            java.util.Arrays.fill(this.bgOtherLast, null);
            this.bgList = list;
         }
         if (biome != null && afterLeaf != null) {
            long key = columnKey(quartX, quartZ);
            long t = target.temperature(), h = target.humidity(), c = target.continentalness(), e = target.erosion(), w = target.weirdness();
            if (this.bgHas[slot] && this.bgColKey[slot] == key
                  && this.bgT[slot] == t && this.bgH[slot] == h && this.bgC[slot] == c && this.bgE[slot] == e && this.bgW[slot] == w
                  && this.bgBiome[slot] == biome && this.bgLeaf[slot] == afterLeaf) {
               if (beforeLast != afterLeaf) {
                  this.bgOtherLast[slot] = beforeLast;
               }
               return;
            }
            this.bgHas[slot] = true;
            this.bgColKey[slot] = key;
            this.bgT[slot] = t;
            this.bgH[slot] = h;
            this.bgC[slot] = c;
            this.bgE[slot] = e;
            this.bgW[slot] = w;
            this.bgBiome[slot] = biome;
            this.bgLeaf[slot] = afterLeaf;
            this.bgOtherLast[slot] = beforeLast;
         }
      }

      public Object stageChoice(Object stage, Object target, long regionKey, float climateInfluence) {
         return this.stageChoices.get(new StageKey(stage, target, regionKey, Float.floatToRawIntBits(climateInfluence)));
      }

      public void rememberStageChoice(Object stage, Object target, long regionKey, float climateInfluence, Object value) {
         if (value != null) {
            this.stageChoices.put(new StageKey(stage, target, regionKey, Float.floatToRawIntBits(climateInfluence)), value);
         }
      }

      public boolean checkNow() {
         return ++this.reuses % CHECK_EVERY == 0L;
      }

      public Object terrain(Object lookup, int blockX, int blockZ) {
         return lookup == this.lookup ? this.terrain.get(columnKey(blockX, blockZ)) : null;
      }

      public void rememberTerrain(Object lookup, int blockX, int blockZ, Object terrain) {
         if (lookup != this.lookup) {
            this.terrain.clear();
            this.lookup = lookup;
         }

         this.terrain.put(columnKey(blockX, blockZ), terrain);
      }

      public boolean roleCached(Object lookup, int slot, int blockX, int blockZ) {
         return lookup == this.roleLookup && this.roleHas[slot] && this.roleKeys[slot] == columnKey(blockX, blockZ);
      }

      public void rememberRoleCell(Object lookup, int slot, int blockX, int blockZ, Object terrain, float water, float height) {
         if (lookup != this.roleLookup) {
            java.util.Arrays.fill(this.roleHas, false);
            this.roleLookup = lookup;
         }

         this.roleKeys[slot] = columnKey(blockX, blockZ);
         this.roleTerrain[slot] = terrain;
         this.roleWater[slot] = water;
         this.roleHeight[slot] = height;
         this.roleHas[slot] = true;
      }

      public boolean roleSubmergedCached(Object lookup, float waterLevel, int slot, int blockX, int blockZ) {
         return lookup == this.roleSubmergedLookup
            && Float.compare(this.roleSubmergedWaterLevel, waterLevel) == 0
            && this.roleSubmergedHas[slot]
            && this.roleSubmergedKeys[slot] == columnKey(blockX, blockZ);
      }

      public void rememberRoleSubmerged(Object lookup, float waterLevel, int slot, int blockX, int blockZ, boolean submerged) {
         if (lookup != this.roleSubmergedLookup || Float.compare(this.roleSubmergedWaterLevel, waterLevel) != 0) {
            java.util.Arrays.fill(this.roleSubmergedHas, false);
            java.util.Arrays.fill(this.roleLastIn, null);
            java.util.Arrays.fill(this.roleLastOut, null);
            java.util.Arrays.fill(this.roleLastCandidates, null);
            this.roleSubmergedLookup = lookup;
            this.roleSubmergedWaterLevel = waterLevel;
         }

         this.roleLastIn[slot] = null;
         this.roleLastOut[slot] = null;
         this.roleLastCandidates[slot] = null;
         this.roleSubmergedKeys[slot] = columnKey(blockX, blockZ);
         this.roleSubmerged[slot] = submerged;
         this.roleSubmergedHas[slot] = true;
      }

      public boolean isNonRiver(Holder<Biome> resolved) {
         if (resolved == this.nonRiverBiome) {
            return true;
         }
         if (resolved == this.riverBiome || resolved == null) {
            return false;
         }
         if (!resolved.is(BiomeTags.IS_RIVER)) {
            this.nonRiverBiome = resolved;
            return true;
         }
         this.riverBiome = resolved;
         return false;
      }

      public boolean coverageMatches(Object sampler, Object target, int quartX, int quartY, int quartZ) {
         return this.coverageValid && sampler == this.coverageSampler && target == this.coverageTarget && quartX == this.coverageX
            && quartY == this.coverageY && quartZ == this.coverageZ;
      }

      public float coverage() {
         return this.coverage;
      }

      public void rememberCoverage(Object sampler, Object target, int quartX, int quartY, int quartZ, float coverage) {
         this.coverageValid = true;
         this.coverageSampler = sampler;
         this.coverageTarget = target;
         this.coverageX = quartX;
         this.coverageY = quartY;
         this.coverageZ = quartZ;
         this.coverage = coverage;
      }

      private static long columnKey(int x, int z) {
         return (long)x << 32 | z & 0xFFFFFFFFL;
      }
   }

   private record StageKey(Object stage, Object target, long regionKey, int climateInfluenceBits) {
      @Override
      public boolean equals(Object other) {
         return other instanceof StageKey key && key.stage == this.stage && key.regionKey == this.regionKey
            && key.climateInfluenceBits == this.climateInfluenceBits && key.target.equals(this.target);
      }

      @Override
      public int hashCode() {
         return (System.identityHashCode(this.stage) * 31 + this.target.hashCode()) * 31 + Long.hashCode(this.regionKey) * 31 + this.climateInfluenceBits;
      }
   }
}
