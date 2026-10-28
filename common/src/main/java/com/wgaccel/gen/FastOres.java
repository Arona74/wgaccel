package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Mc;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * S8 ores (the user's pick: faster on the CPU, exactly). 0.9.65's profile: ores 7.5% of the CPU, of it about a third in
 * OreFeature.place asking WorldGenRegion.getHeight for every column of the ore's box (a chunk lookup each time; ores
 * above the ground try every column), and ~13% in BulkSectionAccess.getSection and doPlace's isOutsideBuildHeight asking
 * the level for its section range for every block. Here the same answers come from a per-thread cached chunk and the
 * level's height range read once: WorldGenRegion.getHeight is the chunk's height + 1, and a section index is
 * (y >> 4) minus the lowest section. Every 256th answer is compared with the original call; a difference turns this off.
 */
public final class FastOres {
   private static volatile boolean disabled;
   private static final LongAdder CALLS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final java.util.concurrent.atomic.AtomicLong CHECK_COUNT = new java.util.concurrent.atomic.AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();


   private FastOres() {
   }

   public static boolean on() {
      return !disabled && Config.FAST_ORES.get();
   }

   /**
    * The chunks of one OreFeature.place call (its box is at most 2 x 2 chunks), kept for that call only (the mixin's
    * MixinExtras @Share local): no ThreadLocal per column (the Still Life profile, 2026-10-22: the ThreadLocal and the
    * one-chunk cache's misses at chunk borders were ~6% of feature time).
    */
   public static final class Chunks {
      private final WorldGenRegion region;
      private final long[] keys = new long[4];
      private final ChunkAccess[] chunks = new ChunkAccess[4];
      private int size;

      public Chunks(WorldGenRegion region) {
         this.region = region;
      }

      public WorldGenRegion region() {
         return this.region;
      }

      ChunkAccess get(int chunkX, int chunkZ) {
         long key = Mc.key(chunkX, chunkZ);
         for (int i = 0; i < this.size; i++) {
            if (this.keys[i] == key) {
               return this.chunks[i];
            }
         }

         ChunkAccess chunk = this.region.getChunk(chunkX, chunkZ);
         int slot = this.size < 4 ? this.size++ : 3;
         this.keys[slot] = key;
         this.chunks[slot] = chunk;
         return chunk;
      }
   }

   /** WorldGenRegion.getHeight(type, x, z) from the call's chunks. */
   public static int height(Chunks chunks, Heightmap.Types type, int x, int z) {
      return chunks.get(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
   }

   /** A level's build range and section range (the same for every thread). */
   private record Range(LevelHeightAccessor level, int minY, int maxY, int minSection, int sections) {
   }

   /**
    * The last level's range, shared by all threads (no ThreadLocal: 0.9.90's vanilla profile had ThreadLocal look-ups
    * at ~10% of the CPU, nearly all FastOres asking for its per-thread record per ore block). Immutable, so a race only
    * makes it twice; the compared answers (every ~256th) still check it against the game.
    */
   private static volatile Range lastRange;

   private static Range range(LevelHeightAccessor level) {
      // Keyed by the region's level (one per dimension, the same heights), not the region (one per chunk step).
      LevelHeightAccessor key = level instanceof WorldGenRegion region ? region.getLevel() : level;
      Range range = lastRange;
      if (range == null || range.level() != key) {
         range = new Range(key, Mc.minY(level), Mc.maxY(level), Mc.minSection(level), level.getSectionsCount());
         lastRange = range;
      }

      return range;
   }

   /** LevelHeightAccessor.isOutsideBuildHeight(y). */
   public static boolean outside(LevelHeightAccessor level, int y) {
      Range range = range(level);
      return y < range.minY() || y >= range.maxY();
   }

   /** LevelHeightAccessor.getSectionIndex(y). */
   public static int sectionIndex(LevelHeightAccessor level, int y) {
      return SectionPos.blockToSectionCoord(y) - range(level).minSection();
   }

   /** LevelHeightAccessor.getSectionsCount(). */
   public static int sectionsCount(LevelHeightAccessor level) {
      return range(level).sections();
   }

   /**
    * Whether this call is one of the compared ones (every 256th on this thread). Counted per thread: summing the shared
    * counter on every call (1.2 billion per run) made ores 5-10x slower (run of 0.9.67).
    */
   public static boolean sample() {
      // About 1 in 256 at random (ThreadLocalRandom lives on the thread itself: no ThreadLocal look-up).
      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(256) != 0) {
         return false;
      }

      CALLS.add(256);
      return true;
   }

   /** A compared call: the game's answer is used; a difference turns this off. */
   public static int verify(int answer, int real) {
      CHECKED.increment();
      if (real != answer) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA fast ores: an answer {} differs from the game's {}; off, the game's own calls are used", answer, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA fast ores: %,d answers, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
