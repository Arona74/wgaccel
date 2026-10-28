package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntBinaryOperator;

/**
 * Conquest ReTerraForged's landform function (its api ConquestLandforms: a column's landform class and height, from its
 * terrain cell), remembered per column. Conquest of Arborea's trees ask it for column after column: in the 2026-10-27
 * probe, 10 million calls of 5 to 10 us each, 90% of them a column asked before, and 75% of tree_forest's time. The
 * answer depends only on the world and the column, so it is kept in a direct-mapped table (a newer column replaces an
 * older one in its slot). Every 256th kept answer is compared with the function's own; a difference turns this off.
 */
public final class LandformMemo implements IntBinaryOperator {
   private static final int BITS = 17;
   private static volatile boolean disabled;
   private static final LongAdder CALLS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private final IntBinaryOperator real;
   /** Immutable entries (final fields): a slot written by one thread is read whole or not at all by another. */
   private final Entry[] slots = new Entry[1 << BITS];

   private record Entry(long key, int value) {
   }

   private LandformMemo(IntBinaryOperator real) {
      this.real = real;
   }

   /** The function to hand out in place of real (real itself when this is off). */
   public static IntBinaryOperator wrap(IntBinaryOperator real) {
      return real == null || disabled || !Config.FAST_CONQUEST_LANDFORMS.get() ? real : new LandformMemo(real);
   }

   @Override
   public int applyAsInt(int x, int z) {
      if (disabled) {
         return this.real.applyAsInt(x, z);
      }

      long key = (long)x << 32 | z & 0xFFFFFFFFL;
      int slot = (int)(key * 0x9E3779B97F4A7C15L >>> 64 - BITS);
      Entry entry = this.slots[slot];
      if (entry != null && entry.key == key) {
         if (ThreadLocalRandom.current().nextInt(256) == 0) {
            return verify(entry.value, this.real.applyAsInt(x, z));
         }

         return entry.value;
      }

      int value = this.real.applyAsInt(x, z);
      this.slots[slot] = new Entry(key, value);
      return value;
   }

   /** A compared answer: the function's own is used; a difference turns this off. */
   private static int verify(int kept, int real) {
      CALLS.add(256);
      CHECKED.increment();
      if (kept != real) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA Conquest landforms: a kept answer {} differs from Conquest's {}; off, Conquest's own calls are used", kept, real);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 4096 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA Conquest landforms: %,d kept answers, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
