package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A features region's worldgen heights (WORLD_SURFACE_WG, OCEAN_FLOOR_WG), kept once read. From the carvers step on,
 * block writes update only the final heightmaps (ChunkStatus CARVERS and FEATURES: FINAL_HEIGHTMAPS, 1.21.1 and 26.1.2),
 * so every chunk a features region can see has worldgen heightmaps that no longer change while it runs. Lithos reads
 * them in 15 x 15 windows around each column (cliff tests, 450 reads a column), each through the region's chunk lookup
 * and the heightmap (28 Oct 2026 profile: 3 to 13% of Lithos's time). Every 256th kept height is compared with the
 * region's own answer; a difference turns this off.
 */
public final class RegionHeights {
   private static final int UNKNOWN = Integer.MIN_VALUE;
   private static volatile boolean disabled;
   private static final LongAdder CALLS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private RegionHeights() {
   }

   /** A region's kept heights: its chunks within radius of the centre chunk, one array per worldgen heightmap. */
   public static final class Table {
      final int minX;
      final int minZ;
      final int width;
      final int[] surface;
      final int[] oceanFloor;

      Table(int minX, int minZ, int width) {
         this.minX = minX;
         this.minZ = minZ;
         this.width = width;
         this.surface = new int[width * width];
         this.oceanFloor = new int[width * width];
         Arrays.fill(this.surface, UNKNOWN);
         Arrays.fill(this.oceanFloor, UNKNOWN);
      }
   }

   public static boolean on() {
      return !disabled && Config.FAST_REGION_HEIGHTS.get();
   }

   /** The table for a region centred on chunk (chunkX, chunkZ) that hands out chunks up to radius chunks away. */
   public static Table table(int chunkX, int chunkZ, int radius) {
      return new Table((chunkX - radius) << 4, (chunkZ - radius) << 4, (2 * radius + 1) << 4);
   }

   /** The kept heights of a worldgen heightmap; null for the others (they change as features place blocks). */
   public static int[] heights(Table table, Heightmap.Types type) {
      return type == Heightmap.Types.WORLD_SURFACE_WG ? table.surface : type == Heightmap.Types.OCEAN_FLOOR_WG ? table.oceanFloor : null;
   }

   /** The column's slot, -1 outside the table (then the region answers as usual). */
   public static int index(Table table, int x, int z) {
      int dx = x - table.minX;
      int dz = z - table.minZ;
      return dx >= 0 && dz >= 0 && dx < table.width && dz < table.width ? dz * table.width + dx : -1;
   }

   /** Whether the slot holds a height. */
   public static boolean known(int height) {
      return height != UNKNOWN;
   }

   /** Whether this kept height is one of the compared ones (about 1 in 256). */
   public static boolean sample() {
      return ThreadLocalRandom.current().nextInt(256) == 0;
   }

   /** A compared height: the region's is used; a different one turns this off. */
   public static int verify(int kept, int real, Heightmap.Types type, int x, int z) {
      CALLS.add(256);
      CHECKED.increment();
      if (kept != real) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA region heights: {} at {}, {} kept as {} but the region now gives {}; off, the region reads each height again", type, x, z, kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA region heights: %,d kept heights handed out, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
