package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
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
   private static final LongAdder DIFFERENT = new LongAdder();

   /** The calling thread's last region chunk (the ore's box is 1 to 4 chunks). */
   private static final class Last {
      long calls;
      long checks;
      WorldGenRegion region;
      int chunkX;
      int chunkZ;
      ChunkAccess chunk;
      LevelHeightAccessor heightLevel;
      int minY;
      int maxY;
      int minSection;
      int sections;
   }

   private static final ThreadLocal<Last> LAST = ThreadLocal.withInitial(Last::new);

   private FastOres() {
   }

   public static boolean on() {
      return !disabled && Config.FAST_ORES.get();
   }

   /** WorldGenRegion.getHeight(type, x, z) with the chunk kept between calls. */
   public static int height(WorldGenRegion region, Heightmap.Types type, int x, int z) {
      Last last = LAST.get();
      int chunkX = SectionPos.blockToSectionCoord(x);
      int chunkZ = SectionPos.blockToSectionCoord(z);
      if (last.region != region || last.chunk == null || last.chunkX != chunkX || last.chunkZ != chunkZ) {
         last.region = region;
         last.chunkX = chunkX;
         last.chunkZ = chunkZ;
         last.chunk = region.getChunk(chunkX, chunkZ);
      }

      return last.chunk.getHeight(type, x & 15, z & 15) + 1;
   }

   /** The level's build range and section range, read once per level and thread. */
   private static Last range(LevelHeightAccessor level) {
      Last last = LAST.get();
      if (last.heightLevel != level) {
         last.heightLevel = level;
         last.minY = level.getMinBuildHeight();
         last.maxY = level.getMaxBuildHeight();
         last.minSection = level.getMinSection();
         last.sections = level.getSectionsCount();
      }

      return last;
   }

   /** LevelHeightAccessor.isOutsideBuildHeight(y). */
   public static boolean outside(LevelHeightAccessor level, int y) {
      Last last = range(level);
      return y < last.minY || y >= last.maxY;
   }

   /** LevelHeightAccessor.getSectionIndex(y). */
   public static int sectionIndex(LevelHeightAccessor level, int y) {
      return SectionPos.blockToSectionCoord(y) - range(level).minSection;
   }

   /** LevelHeightAccessor.getSectionsCount(). */
   public static int sectionsCount(LevelHeightAccessor level) {
      return range(level).sections;
   }

   /**
    * Whether this call is one of the compared ones (every 256th on this thread). Counted per thread: summing the shared
    * counter on every call (1.2 billion per run) made ores 5-10x slower (run of 0.9.67).
    */
   public static boolean sample() {
      Last last = LAST.get();
      if ((++last.calls & 255) != 0) {
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

      if ((++LAST.get().checks & 8191) == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA fast ores: %,d answers, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
