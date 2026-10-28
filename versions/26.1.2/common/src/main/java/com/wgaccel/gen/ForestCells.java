package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Conquest of Arborea 1.0.0's forest cells, kept once made. Its trees find their forest cell by asking a cell function
 * (obfuscated: trees.d.h.a(long seed, int cellX, int cellZ)) for every cell of a square around the column's own, column
 * after column; each answer is a new java.util.Random seeded from (seed, cellX, cellZ) and ten nextDouble calls into an
 * immutable record (28 Oct 2026 profile: 22.5% of Arborea's time). The answer depends on those three numbers alone, so
 * the record is kept in a table shared by all threads (immutable entries: a slot read whole or not at all) and handed out
 * again. Every 256th kept answer is compared with Arborea's own (record equality: each number's bits); a difference
 * turns this off.
 */
public final class ForestCells {
   private static final int SLOTS = 1 << 14;
   private static final Entry[] TABLE = new Entry[SLOTS];
   private static volatile boolean disabled;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private ForestCells() {
   }

   private record Entry(long seed, int x, int z, Object cell) {
   }

   private static int slot(long seed, int x, int z) {
      long h = (x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ seed) * 0x165667B19E3779F9L;
      return (int)(h >>> 50);
   }

   /** The kept cell for (seed, x, z), null when there is none (or this is off): then Arborea works it out and {@link #made} keeps it. */
   public static Object kept(long seed, int x, int z) {
      if (disabled || !Config.FAST_ARBOREA_CELLS.get()) {
         return null;
      }

      Entry entry = TABLE[slot(seed, x, z)];
      return entry != null && entry.x == x && entry.z == z && entry.seed == seed ? entry.cell : null;
   }

   /** Whether this kept answer is one of the compared ones (about 1 in 256): Arborea then works it out anyway. */
   public static boolean compared() {
      return ThreadLocalRandom.current().nextInt(256) == 0;
   }

   /** Arborea's own answer: kept, or compared with the kept one when there is one. */
   public static void made(long seed, int x, int z, Object cell) {
      if (disabled || cell == null || !Config.FAST_ARBOREA_CELLS.get()) {
         return;
      }

      int slot = slot(seed, x, z);
      Entry entry = TABLE[slot];
      if (entry != null && entry.x == x && entry.z == z && entry.seed == seed) {
         check(entry.cell, cell, x, z);
         return;
      }

      TABLE[slot] = new Entry(seed, x, z, cell);
   }

   private static void check(Object kept, Object real, int x, int z) {
      CHECKED.increment();
      if (!Objects.equals(kept, real)) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA Arborea forest cells: cell {}, {} was {} kept but {} from Arborea; off, Arborea works each cell out again", x, z, kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA Arborea forest cells: about %,d kept cells handed out, checked %,d: %s", CHECKED.sum() * 256, CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
