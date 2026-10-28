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
 * <p>
 * The same for Conquest's other column functions of the same form (api ConquestSurface: the surface height in 1/256
 * blocks; ConquestScree; ConquestRivers), which Arborea asks too (28 Oct 2026 profile: 12.5%, 6.8% and 4.6% of its
 * time), each its own {@link Kind} with its own table, counters and switch-off.
 */
public final class LandformMemo implements IntBinaryOperator {
   private static final int BITS = 17;

   /** Conquest's column functions remembered, each by its api class's name. */
   public enum Kind {
      LANDFORMS("ConquestLandforms", "landforms"),
      SURFACE("ConquestSurface", "surface heights"),
      SCREE("ConquestScree", "scree"),
      RIVERS("ConquestRivers", "rivers");

      final String api;
      final String label;
      volatile boolean disabled;
      final LongAdder calls = new LongAdder();
      /** Answers Conquest worked out (not kept). Landforms, 2026-10-28, r1000 without Lithos: about 305 a chunk, 80% of answers
       * kept; a shared, 16 times bigger table gave the same (each column is worked out about once already). */
      final LongAdder computed = new LongAdder();
      final LongAdder checked = new LongAdder();
      final AtomicLong checkCount = new AtomicLong();
      final LongAdder different = new LongAdder();

      Kind(String api, String label) {
         this.api = api;
         this.label = label;
      }

      /** The kind for an api class's simple name, null for another. */
      public static Kind of(String api) {
         for (Kind kind : values()) {
            if (kind.api.equals(api)) {
               return kind;
            }
         }

         return null;
      }
   }

   private final Kind kind;
   private final IntBinaryOperator real;
   /** Immutable entries (final fields): a slot written by one thread is read whole or not at all by another. */
   private final Entry[] slots = new Entry[1 << BITS];

   private record Entry(long key, int value) {
   }

   private LandformMemo(Kind kind, IntBinaryOperator real) {
      this.kind = kind;
      this.real = real;
   }

   /** The function to hand out in place of real (real itself when this is off). */
   public static IntBinaryOperator wrap(Kind kind, IntBinaryOperator real) {
      return real == null || kind == null || kind.disabled || !Config.FAST_CONQUEST_LANDFORMS.get() ? real : new LandformMemo(kind, real);
   }

   @Override
   public int applyAsInt(int x, int z) {
      if (this.kind.disabled) {
         return this.real.applyAsInt(x, z);
      }

      long key = (long)x << 32 | z & 0xFFFFFFFFL;
      int slot = (int)(key * 0x9E3779B97F4A7C15L >>> 64 - BITS);
      Entry entry = this.slots[slot];
      if (entry != null && entry.key == key) {
         if (ThreadLocalRandom.current().nextInt(256) == 0) {
            return verify(this.kind, entry.value, this.real.applyAsInt(x, z));
         }

         return entry.value;
      }

      int value = this.real.applyAsInt(x, z);
      this.kind.computed.increment();
      this.slots[slot] = new Entry(key, value);
      return value;
   }

   /** A compared answer: the function's own is used; a difference turns this kind off. */
   private static int verify(Kind kind, int kept, int real) {
      kind.calls.add(256);
      kind.checked.increment();
      if (kept != real) {
         kind.different.increment();
         if (!kind.disabled) {
            kind.disabled = true;
            WorldgenAccelerator.LOG.warn("WGA Conquest {}: a kept answer {} differs from Conquest's {}; off, Conquest's own calls are used", kind.label, kept, real);
         }
      }

      if (kind.checkCount.incrementAndGet() % 4096 == 0) {
         WorldgenAccelerator.LOG.info(summary(kind));
      }

      return real;
   }

   public static String summary(Kind kind) {
      long kept = kind.calls.sum();
      long computed = kind.computed.sum();
      return String.format(Locale.ROOT, "WGA Conquest %s: %,d kept answers, %,d worked out by Conquest (%.1f%% kept), checked %,d: %s", kind.label, kept, computed,
         100.0 * kept / Math.max(1, kept + computed), kind.checked.sum(), kind.different.sum() == 0 ? "IDENTICAL" : kind.different.sum() + " DIFFER (off)");
   }
}
