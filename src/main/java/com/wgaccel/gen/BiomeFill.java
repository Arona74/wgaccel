package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * One chunk's biome fill (made thread-local between {@link #begin} and {@link #end}): the region identities it samples
 * (measuring), and two exact memos for FreeTerraForged's biome decoration (config {@code fasterBiomeChoice}):
 *
 * <ul>
 *   <li>Terrain per column: FTF's terrain-role step asks, for every 4 x 4 x 4 cell at or above the surface, whether its
 *       column is a river, by copying the column's cell from the chunk's tile ({@code WorldLookup.applyCell}). During one
 *       chunk's fill FTF holds that tile, so a column's answer cannot change: it is kept per column, and only when it came
 *       from the tile (applyCell returned true).</li>
 *   <li>Cave-biome choice in a depth band: a pure function of the band, the climate target, the region key and the
 *       climate influence, asked about 270 times per chunk with only a few different inputs: kept per chunk.</li>
 *   <li>Coverage: the terrain-role step repeats the surface-coverage check the underground step just made for the same
 *       cell with the same sampler and climate target: the last answer is reused.</li>
 * </ul>
 *
 * Every 64th reuse is also worked out the normal way and compared; a difference is counted and logged.
 */
public final class BiomeFill {
   public static final LongAdder CHUNKS = new LongAdder();
   public static final LongAdder IDENTITIES = new LongAdder();
   public static final LongAdder DISTINCT = new LongAdder();
   public static final LongAdder ROLE_HITS = new LongAdder();
   public static final LongAdder ROLE_MISSES = new LongAdder();
   public static final LongAdder COVERAGE_HITS = new LongAdder();
   public static final LongAdder STAGE_HITS = new LongAdder();
   public static final LongAdder CHECKS = new LongAdder();
   public static final LongAdder MISMATCHES = new LongAdder();
   private static final AtomicInteger WARNINGS = new AtomicInteger();
   private static final int CHECK_EVERY = 64;
   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private BiomeFill() {
   }

   public static void begin() {
      State state = STATE.get();
      state.seen.clear();
      state.terrain.clear();
      state.lookup = null;
      state.coverageValid = false;
      state.stageChoices.clear();
      state.memo = Config.FASTER_BIOME_CHOICE.getAsBoolean();
      state.active = true;
   }

   public static void end() {
      State state = STATE.get();
      state.active = false;
      state.terrain.clear();
      state.lookup = null;
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
      boolean active;
      boolean memo;
      final LongOpenHashSet seen = new LongOpenHashSet();
      /** Column (block x, z) to FTF's terrain object, for one lookup (one world). */
      final Long2ObjectOpenHashMap<Object> terrain = new Long2ObjectOpenHashMap<>();
      Object lookup;
      boolean coverageValid;
      Object coverageSampler;
      Object coverageTarget;
      int coverageX;
      int coverageY;
      int coverageZ;
      float coverage;
      long reuses;
      /** Cave-biome choices of this fill, by (band, climate target, region key, climate influence). */
      final HashMap<StageKey, Object> stageChoices = new HashMap<>();

      public Object stageChoice(Object stage, Object target, long regionKey, float climateInfluence) {
         return this.stageChoices.get(new StageKey(stage, target, regionKey, Float.floatToRawIntBits(climateInfluence)));
      }

      public void rememberStageChoice(Object stage, Object target, long regionKey, float climateInfluence, Object value) {
         if (value != null) {
            this.stageChoices.put(new StageKey(stage, target, regionKey, Float.floatToRawIntBits(climateInfluence)), value);
         }
      }

      /** True on every 64th reuse on this thread: then the caller also works the value out the normal way and compares. */
      public boolean checkNow() {
         return ++this.reuses % CHECK_EVERY == 0L;
      }

      /** The column's remembered terrain, or null. */
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

      private static long columnKey(int blockX, int blockZ) {
         return (long)blockX << 32 | blockZ & 0xFFFFFFFFL;
      }
   }

   /** The band is compared by identity (FTF's Stage has no equals), the climate target by value (it is a record). */
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
