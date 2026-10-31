package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Minecraft 26.1's preliminary surface levels, shared by all noise chunks of a world. Each noise chunk keeps the levels it
 * has worked out (the router's preliminarySurfaceLevel at a column, every 4 blocks) in its own map, but its aquifer asks
 * for the columns around its cells, past the chunk's edges, so each column is worked out again by every chunk around it
 * (28 Oct 2026 profile of vanilla: 8% of the CPU, most of the noise fill step's CPU time). The level depends on the world
 * and the column alone when the chunk has no blending (the router's caches return the function's own value), so it is
 * kept in a table per world (one long per slot: column and level, read whole) for the noise chunks after it. Every 256th
 * kept level a noise chunk is handed is compared with the one it works out; a difference turns this off.
 */
public final class SurfaceLevels {
   /** No kept level for that column. */
   public static final int MISSING = Integer.MIN_VALUE;
   private static final int BITS = 17;
   private static final Map<RandomState, SurfaceLevels> WORLDS = new WeakHashMap<>();
   private static volatile SurfaceLevels last;
   private static volatile boolean disabled;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();
   private final WeakReference<RandomState> owner;
   private final AtomicLongArray table = new AtomicLongArray(1 << BITS);

   private SurfaceLevels(RandomState owner) {
      this.owner = new WeakReference<>(owner);
   }

   /** The table of this world's noise chunks, null when this is off. */
   public static SurfaceLevels of(RandomState random) {
      if (disabled || !Config.FAST_SURFACE_LEVELS.get()) {
         return null;
      }

      SurfaceLevels levels = last;
      if (levels != null && levels.owner.get() == random) {
         return levels;
      }

      synchronized (WORLDS) {
         levels = WORLDS.computeIfAbsent(random, SurfaceLevels::new);
      }

      last = levels;
      return levels;
   }

   /** Whether this is still on (a difference turns it off for every world). */
   public static boolean on() {
      return !disabled;
   }

   // The column's quantized x and z in 24 bits each (blocks / 4: up to 33 million blocks out), the level in the low 16 bits
   // (offset so that an empty slot, 0, is never a kept level).
   private static long packed(long column, int level) {
      return columnBits(column) | (level + 32768 & 0xFFFF);
   }

   private static long columnBits(long column) {
      int x = (int)column >> 2;
      int z = (int)(column >> 32) >> 2;
      return (long)(x & 0xFFFFFF) << 40 | (long)(z & 0xFFFFFF) << 16;
   }

   private static int slot(long column) {
      long h = ((int)column * 0x9E3779B97F4A7C15L ^ (int)(column >> 32) * 0xC2B2AE3D27D4EB4FL) * 0x165667B19E3779F9L;
      return (int)(h >>> 64 - BITS);
   }

   private static boolean fits(long column) {
      int x = (int)column;
      int z = (int)(column >> 32);
      return x >= -(1 << 25) && x < 1 << 25 && z >= -(1 << 25) && z < 1 << 25;
   }

   /** The kept level of this column ({@code ColumnPos.asLong} of its quantized x and z), or {@link #MISSING}. */
   public int kept(long column) {
      long entry = this.table.getOpaque(slot(column));
      return entry != 0 && (entry & ~0xFFFFL) == columnBits(column) && fits(column) ? (int)(entry & 0xFFFF) - 32768 : MISSING;
   }

   /** A level a noise chunk worked out: kept, or compared with the kept one when there is one. */
   public void made(long column, int level) {
      if (disabled || !fits(column) || level <= -32768 || level > 32767) {
         return;
      }

      int slot = slot(column);
      long entry = this.table.getOpaque(slot);
      if (entry != 0 && (entry & ~0xFFFFL) == columnBits(column)) {
         check((int)(entry & 0xFFFF) - 32768, level, column);
         return;
      }

      this.table.setOpaque(slot, packed(column, level));
   }

   private static void check(int kept, int real, long column) {
      CHECKED.increment();
      if (kept != real) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA surface levels: column {}, {} had level {} kept but {} worked out; off, each noise chunk works its levels out again",
               (int)column, (int)(column >> 32), kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA surface levels: about %,d kept levels handed out, checked %,d: %s", CHECKED.sum() * 256, CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
