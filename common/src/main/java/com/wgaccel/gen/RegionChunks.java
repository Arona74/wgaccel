package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A world-generation region's chunks, kept once found. Every block a feature reads or writes through the region goes
 * through WorldGenRegion.getChunk(x, z, EMPTY): the distance to the centre, the step's allowed status, the region's
 * holder table and the holder's future, each time (Lithos, 2026-10-27 profile: ~14% of its time in those lookups). The
 * chunks the region may hand out were finished before its step started and stay the same while it runs, so the answer
 * for a column of chunks never changes. Only EMPTY requests (the block reads and writes) use the kept chunk; others, and
 * any request vanilla would fail, go the usual way. Every 256th kept chunk is compared with the game's; a difference
 * turns this off. One chunk does change: a neighbour's ProtoChunk, once it has reached SPAWN, may be finished at any
 * time (the full step waits for no neighbour), and its holder then hands out an ImposterProtoChunk around the new
 * LevelChunk instead (with C2ME 0.4.0-alpha.0.65, 2026-10-27: twice in 2 to 4 billion kept answers, once in a features
 * step region). So a kept ProtoChunk at SPAWN is asked for again each time; earlier ones cannot be swapped before they
 * reach SPAWN, and the imposter is final. Only in the features step's regions, where the reads and writes are.
 */
public final class RegionChunks {
   private static volatile boolean disabled;
   private static final LongAdder CALLS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private RegionChunks() {
   }

   /** A kept chunk (immutable: a slot written by one thread is read whole or not at all by another). */
   public record Entry(int x, int z, ChunkAccess chunk) {
   }

   /** A region's table: 8 x 8 slots by the chunk's low coordinate bits (regions are at most 7 chunks wide where it matters). */
   public static Entry[] table() {
      return new Entry[64];
   }

   /**
    * The kept chunk for WorldGenRegion.getChunk(x, z, status, load), null when there is none (or this is off, or the
    * request is not EMPTY): then the caller asks the region and hands its answer to {@link #keep}. No lambda per call:
    * this is every block read and write of every feature.
    */
   public static ChunkAccess kept(Entry[] table, int x, int z, ChunkStatus status) {
      if (status != ChunkStatus.EMPTY || disabled || !Config.FAST_REGION_CHUNKS.get()) {
         return null;
      }

      Entry entry = table[x & 7 | (z & 7) << 3];
      if (entry == null || entry.x != x || entry.z != z) {
         return null;
      }

      // A ProtoChunk at SPAWN may be swapped for its finished chunk's imposter: the region's own answer then.
      ChunkAccess chunk = entry.chunk;
      return chunk instanceof ProtoChunk && !(chunk instanceof ImposterProtoChunk) && chunk.getPersistedStatus().isOrAfter(ChunkStatus.SPAWN) ? null : chunk;
   }

   /** Whether this kept chunk is one of the compared ones (about 1 in 256). */
   public static boolean sample() {
      return ThreadLocalRandom.current().nextInt(256) == 0;
   }

   /** Keeps the region's answer for an EMPTY request (vanilla throws for a chunk the region may not hand out: nothing is kept then). */
   public static ChunkAccess keep(Entry[] table, int x, int z, ChunkStatus status, ChunkAccess chunk) {
      if (chunk != null && status == ChunkStatus.EMPTY && !disabled) {
         table[x & 7 | (z & 7) << 3] = new Entry(x, z, chunk);
      }

      return chunk;
   }

   /** A compared chunk: the game's is used; a different one turns this off. */
   public static ChunkAccess verify(ChunkAccess kept, ChunkAccess real, int x, int z, Object region) {
      CALLS.add(256);
      CHECKED.increment();
      if (kept != real) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA region chunks: chunk {}, {} kept as {} ({}) but the region now gives {} ({}), region {}; off, the region looks each chunk up again",
               x, z, describe(kept), kept == null ? "-" : kept.getPersistedStatus(), describe(real), real == null ? "-" : real.getPersistedStatus(), region);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   private static String describe(ChunkAccess chunk) {
      return chunk == null ? "null" : chunk.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(chunk));
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA region chunks: %,d kept chunks handed out, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
