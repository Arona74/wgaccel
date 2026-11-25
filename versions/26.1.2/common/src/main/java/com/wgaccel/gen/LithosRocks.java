package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.Block;

/**
 * Lithos 1.0.0's rock type of a block (obfuscated: worldgen.e.a(WorldGenLevel, Block), its rock record or null), kept
 * once worked out. Lithos looks the block's id up in the level's registry, turns it into a string, then walks its rock
 * types comparing names and building "prefix_" for each one, for every block it places or tests (25 Nov 2026 Conquest
 * profile: 1.8% of the CPU, 8% of all memory allocated). The answer depends on the block alone (block ids and Lithos's
 * rock list are fixed once the game runs), so it is kept per block in a table shared by all threads. Every 256th kept
 * answer is compared with Lithos's own; a difference turns this off.
 */
public final class LithosRocks {
   /** The kept answer "not a rock" (Lithos returns null; the map holds no nulls). */
   private static final Object NONE = new Object();
   private static final ConcurrentHashMap<Block, Object> KEPT = new ConcurrentHashMap<>();
   private static volatile boolean disabled;
   private static volatile boolean announced;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private LithosRocks() {
   }

   /** Whether a kept answer is there for the block (then {@link #answer} is it), false when this is off or nothing is kept. */
   public static Object kept(Block block) {
      if (disabled || !Config.FAST_LITHOS_ROCKS.get()) {
         return null;
      }

      return KEPT.get(block);
   }

   /** The answer a kept value stands for (NONE is Lithos's null). */
   public static Object answer(Object kept) {
      return kept == NONE ? null : kept;
   }

   /** Whether this kept answer is one of the compared ones (about 1 in 256): Lithos then works it out anyway. */
   public static boolean compared() {
      return ThreadLocalRandom.current().nextInt(256) == 0;
   }

   /** Lithos's own answer: kept, or compared with the kept one when there is one. */
   public static void made(Block block, Object rock) {
      if (disabled || block == null || !Config.FAST_LITHOS_ROCKS.get()) {
         return;
      }

      Object value = rock == null ? NONE : rock;
      Object before = KEPT.putIfAbsent(block, value);
      if (before == null) {
         if (!announced) {
            announced = true;
            WorldgenAccelerator.LOG.info("WGA Lithos rock types: on (each block's rock type kept once worked out, every 256th compared)");
         }

         return;
      }

      CHECKED.increment();
      if (!Objects.equals(before, value)) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA Lithos rock types: block {} was {} kept but {} from Lithos; off, Lithos works each block out again",
               block, answer(before), rock);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA Lithos rock types: %,d blocks kept, about %,d kept answers handed out, checked %,d: %s", KEPT.size(),
         CHECKED.sum() * 256, CHECKED.sum(), DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
