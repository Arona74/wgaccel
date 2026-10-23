package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * FreeTerraForged's BiomeSurfaceEnvelope.minimumSurfaceY (the lowest surface over 10 x 10 blocks around a quart, 100
 * heightmap samples) keeps its answers in a cache per thread, so our biome look-ahead and the chunk's own biome step
 * (other threads) each work every answer out: ~23% of the CPU in the 2026-10-22 08:45 run on FTF 1.0.0013. Here the
 * answers are shared by all threads in a fixed-size table (an entry per lookup, accuracy and quart; a newer one replaces
 * an older one in its slot). FreeTerraForged's own cache treats the answer as a function of exactly these. 1 in 256
 * shared answers is worked out again and compared; a difference turns this off. Only while FreeTerraForged's cache is
 * still per thread ({@link FtfSlowPaths#surfaceEnvelope}).
 */
public final class FtfSurfaceEnvelope {
   private static final int SLOTS = 1 << 17;
   private static final AtomicReferenceArray<Entry> TABLE = new AtomicReferenceArray<>(SLOTS);
   private static final LongAdder MADE = new LongAdder();
   private static final LongAdder SHARED = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(1 << 16);
   private static volatile boolean off;

   private record Entry(Object lookup, boolean accurate, int quartX, int quartZ, int height) {
   }

   private FtfSurfaceEnvelope() {
   }

   public static int minimumSurfaceY(Object lookup, boolean accurate, int quartX, int quartZ, IntSupplier make) {
      if (off || !Config.FTF_SHARED_CHUNK_CELLS.get() || !FtfSlowPaths.surfaceEnvelope()) {
         return make.getAsInt();
      }

      int hash = (quartX * 0x9E3779B1 + quartZ * 0x85EBCA6B) ^ (accurate ? 0x5bd1e995 : 0) ^ System.identityHashCode(lookup);
      int slot = (hash ^ (hash >>> 15)) & (SLOTS - 1);
      Entry entry = TABLE.get(slot);
      if (entry != null && entry.lookup == lookup && entry.accurate == accurate && entry.quartX == quartX && entry.quartZ == quartZ) {
         SHARED.increment();
         if (ThreadLocalRandom.current().nextInt(256) == 0) {
            CHECKED.increment();
            int real = make.getAsInt();
            if (real != entry.height) {
               DIFFERENT.increment();
               off = true;
               com.wgaccel.WgaChat.warnOff("WGA shared FTF surface envelope off: quart {} {} gave {} instead of {}", quartX, quartZ, entry.height, real);
               return real;
            }
         }

         if (Due.due(NEXT_REPORT, SHARED.sum(), 2)) {
            WorldgenAccelerator.LOG.info(summary());
         }

         return entry.height;
      }

      int height = make.getAsInt();
      MADE.increment();
      TABLE.set(slot, new Entry(lookup, accurate, quartX, quartZ, height));
      return height;
   }

   // The block heights inside an answer: FreeTerraForged 1.0.0013 works each out from its heightmap noise (Heightmap.apply,
   // the 1.0.0010 version read a cached tile), 100 per answer, and neighbouring answers' 10 x 10 windows overlap (about 6
   // times over). Shared the same way, by context and block; 1 in 256 worked out again and compared.
   private static final int BLOCK_SLOTS = 1 << 20;
   private static final AtomicReferenceArray<Block> BLOCKS = new AtomicReferenceArray<>(BLOCK_SLOTS);
   private static final LongAdder BLOCKS_MADE = new LongAdder();
   private static final LongAdder BLOCKS_SHARED = new LongAdder();

   private record Block(Object context, int x, int z, int height) {
   }

   public static int blockHeight(Object context, int x, int z, IntSupplier make) {
      if (off || !Config.FTF_SHARED_CHUNK_CELLS.get() || !FtfSlowPaths.surfaceEnvelope()) {
         return make.getAsInt();
      }

      int hash = (x * 0x9E3779B1) ^ (z * 0x85EBCA6B) ^ System.identityHashCode(context);
      int slot = (hash ^ (hash >>> 16)) & (BLOCK_SLOTS - 1);
      Block block = BLOCKS.get(slot);
      if (block != null && block.context == context && block.x == x && block.z == z) {
         BLOCKS_SHARED.increment();
         if (ThreadLocalRandom.current().nextInt(256) == 0) {
            CHECKED.increment();
            int real = make.getAsInt();
            if (real != block.height) {
               DIFFERENT.increment();
               off = true;
               com.wgaccel.WgaChat.warnOff("WGA shared FTF surface envelope off: block {} {} height {} instead of {}", x, z, block.height, real);
               return real;
            }
         }

         return block.height;
      }

      int height = make.getAsInt();
      BLOCKS_MADE.increment();
      BLOCKS.set(slot, new Block(context, x, z, height));
      return height;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA shared FTF surface envelope: %,d worked out, %,d shared; block heights %,d worked out, %,d shared; checked %,d: %s", MADE.sum(), SHARED.sum(), BLOCKS_MADE.sum(), BLOCKS_SHARED.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
