package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block reads of block predicates kept for one evaluation. Still Life's stone_cliff (2026-10-22 profile, 49% of feature
 * time) tests, per placement, an any_of of 21 all_of groups whose first test reads the same block (offset 0) 20 times,
 * and its disk's state provider runs 119 rules per block, each reading neighbours: block reads (chunk look-up and
 * palette) were most of it. While one BlockPredicateFilter.shouldPlace or one RuleBasedBlockStateProvider.getState runs,
 * nothing writes blocks (predicates and state providers only read), so a block read twice in that time is the same
 * block: the second comes from here. Each region (one chunk step, one thread) holds its own {@link Scope}; outside a
 * scope reads go to the region as before. About 1 in 256 cached answers is compared with a real read; a difference
 * turns this off.
 */
public final class PredicateReads {
   private static volatile boolean disabled;
   private static final LongAdder HITS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final java.util.concurrent.atomic.AtomicLong LOGGED = new java.util.concurrent.atomic.AtomicLong();

   private PredicateReads() {
   }

   /**
    * A region's reads in its current predicate evaluation: an open-addressing table (128 slots, at most 96 kept), so a
    * read costs a hash and a probe or two whatever the evaluation reads (0.10.11's linear search over 32 kept reads cost
    * about what it saved: a disk's 119 rules read more blocks than that).
    */
   public static final class Scope {
      private static final int SLOTS = 128;
      private static final int MAX = 96;
      private final long[] keys = new long[SLOTS];
      private final BlockState[] states = new BlockState[SLOTS];
      private final int[] used = new int[MAX];
      private int size;
      private int depth;
      private long hits;

      /** Opens a scope (nested ones share the outer one's reads); false when this is off. */
      public boolean begin() {
         if (disabled || !Config.FAST_BLOCK_PREDICATES.get()) {
            return false;
         }

         this.depth++;
         return true;
      }

      public void end() {
         if (--this.depth == 0) {
            for (int i = 0; i < this.size; i++) {
               this.states[this.used[i]] = null;
            }

            this.size = 0;
            if (this.hits != 0) {
               HITS.add(this.hits);
               this.hits = 0;
            }
         }
      }

      /** Whether a scope is open. */
      public boolean open() {
         return this.depth > 0 && !disabled;
      }

      /** Closes the scope for one read of the world's own block (its depth returned for {@link #resume}). */
      public int pause() {
         int depth = this.depth;
         this.depth = 0;
         return depth;
      }

      public void resume(int depth) {
         this.depth = depth;
      }

      private static int slot(long key) {
         long h = key * 0x9E3779B97F4A7C15L;
         return (int)(h >>> 57);
      }

      /** The kept block at pos, or null when not read yet in this scope. */
      public BlockState kept(BlockPos pos) {
         long key = pos.asLong();
         for (int i = slot(key); ; i = (i + 1) & (SLOTS - 1)) {
            BlockState state = this.states[i];
            if (state == null) {
               return null;
            }

            if (this.keys[i] == key) {
               this.hits++;
               return state;
            }
         }
      }

      /** Keeps a block just read at pos (not found by {@link #kept} just before). */
      public void keep(BlockPos pos, BlockState state) {
         if (this.size >= MAX || state == null) {
            return;
         }

         long key = pos.asLong();
         int i = slot(key);
         while (this.states[i] != null) {
            i = (i + 1) & (SLOTS - 1);
         }

         this.keys[i] = key;
         this.states[i] = state;
         this.used[this.size++] = i;
      }
   }

   /** The region side: WorldGenRegion holds one scope (a mixin). */
   public interface Holder {
      Scope wgaccel$predicateReads();
   }

   /** Whether this kept answer is one of the compared ones (about 1 in 256). */
   public static boolean sample() {
      return java.util.concurrent.ThreadLocalRandom.current().nextInt(256) == 0;
   }

   /** A compared answer: the world's is used; a difference turns this off. */
   public static BlockState verify(BlockState kept, BlockState real, BlockPos pos) {
      CHECKED.increment();
      if (kept != real) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA predicate reads: {} was {} in the scope but {} in the world; off, every read goes to the world", pos, kept, real);
         }
      }

      if (LOGGED.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA predicate reads: %,d reads answered from the scope, checked %,d: %s", HITS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
