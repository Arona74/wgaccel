package com.wgaccel.stats;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Chunks turned into save data and read back, by their status (ChunkSerializer.write / read): tells a generator that
 * keeps evicting and reloading unfinished neighbours (many saves and loads of early statuses per new chunk) from one
 * that walks over finished chunks again (loads of full ones).
 */
public final class ChunkIo {
   private static final Map<String, LongAdder> WRITES = new ConcurrentHashMap<>();
   private static final Map<String, LongAdder> READS = new ConcurrentHashMap<>();
   /** Save data bytes (Tag.sizeInBytes, about the chunk's size in memory) by status. */
   private static final Map<String, LongAdder> BYTES = new ConcurrentHashMap<>();
   /** Each chunk's last save, as the number of saves made before it (its sequence number). */
   private static final Map<Long, Long> LAST_SAVE = new ConcurrentHashMap<>();
   private static final java.util.concurrent.atomic.AtomicLong SAVES = new java.util.concurrent.atomic.AtomicLong();
   /** Reloads by how many saves came between the chunk's last save and its reload (a cache of that many chunks keeps it). */
   private static final long[] REUSE_LIMITS = {256, 1024, 4096, 16384, 65536};
   private static final LongAdder[] REUSE = newAdders(REUSE_LIMITS.length + 1);
   private static final Map<String, LongAdder[]> REUSE_BY_STATUS = new ConcurrentHashMap<>();
   private static final LongAdder FIRST_LOADS = new LongAdder();

   private ChunkIo() {
   }

   /** Starts the counts again (a new world, /wgaccel reset). */
   public static void reset() {
      WRITES.clear();
      READS.clear();
      BYTES.clear();
      LAST_SAVE.clear();
      REUSE_BY_STATUS.clear();
      for (LongAdder adder : REUSE) {
         adder.reset();
      }

      FIRST_LOADS.reset();
   }

   private static LongAdder[] newAdders(int count) {
      LongAdder[] adders = new LongAdder[count];
      for (int i = 0; i < count; i++) {
         adders[i] = new LongAdder();
      }

      return adders;
   }

   /** A chunk turned into save data: its position (ChunkPos.toLong), status and save data size in bytes. */
   public static void written(long pos, Object status, long bytes) {
      String name = String.valueOf(status);
      WRITES.computeIfAbsent(name, s -> new LongAdder()).increment();
      BYTES.computeIfAbsent(name, s -> new LongAdder()).add(bytes);
      LAST_SAVE.put(pos, SAVES.incrementAndGet());
   }

   /** A chunk read back from save data: how many saves since its own last one (none: saved before this world opened). */
   public static void read(long pos, Object status) {
      String name = String.valueOf(status);
      READS.computeIfAbsent(name, s -> new LongAdder()).increment();
      Long saved = LAST_SAVE.get(pos);
      if (saved == null) {
         FIRST_LOADS.increment();
         return;
      }

      long distance = SAVES.get() - saved;
      int bucket = 0;
      while (bucket < REUSE_LIMITS.length && distance > REUSE_LIMITS[bucket]) {
         bucket++;
      }

      REUSE[bucket].increment();
      REUSE_BY_STATUS.computeIfAbsent(name, s -> newAdders(REUSE_LIMITS.length + 1))[bucket].increment();
   }

   /** "saved: full 1,234, features 567; loaded: ..." or "" when nothing was counted. */
   public static String summary() {
      if (WRITES.isEmpty() && READS.isEmpty()) {
         return "";
      }

      StringBuilder out = new StringBuilder("saved by status " + list(WRITES) + "; loaded by status " + list(READS));
      out.append("; save data KB per chunk by status ");
      boolean first = true;
      for (Map.Entry<String, Long> entry : sorted(BYTES).entrySet()) {
         LongAdder writes = WRITES.get(entry.getKey());
         long count = writes == null ? 0L : writes.sum();
         out.append(first ? "" : ", ").append(String.format(Locale.ROOT, "%s %.1f", entry.getKey().replace("minecraft:", ""), count == 0L ? 0.0 : entry.getValue() / 1024.0 / count));
         first = false;
      }

      out.append("; reloads kept by a cache of the last N saved chunks: ").append(reuse(REUSE));
      for (Map.Entry<String, LongAdder[]> entry : new TreeMap<>(REUSE_BY_STATUS).entrySet()) {
         out.append(" | ").append(entry.getKey().replace("minecraft:", "")).append(' ').append(reuse(entry.getValue()));
      }

      out.append(String.format(Locale.ROOT, "; loads of chunks not saved since the world opened %,d", FIRST_LOADS.sum()));
      return out.toString();
   }

   /** Cumulative share of the reloads within each cache size: "N 256 12%, 1,024 40%, ..., more 3% (of 12,345)". */
   private static String reuse(LongAdder[] buckets) {
      long total = 0L;
      for (LongAdder bucket : buckets) {
         total += bucket.sum();
      }

      if (total == 0L) {
         return "none";
      }

      StringBuilder out = new StringBuilder();
      long within = 0L;
      for (int i = 0; i < REUSE_LIMITS.length; i++) {
         within += buckets[i].sum();
         out.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%,d %.0f%%", REUSE_LIMITS[i], 100.0 * within / total));
      }

      return out.append(String.format(Locale.ROOT, " (of %,d)", total)).toString();
   }

   private static String list(Map<String, LongAdder> counts) {
      if (counts.isEmpty()) {
         return "none";
      }

      StringBuilder out = new StringBuilder();
      for (Map.Entry<String, Long> entry : sorted(counts).entrySet()) {
         out.append(out.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "%s %,d", entry.getKey().replace("minecraft:", ""), entry.getValue()));
      }

      return out.toString();
   }

   private static TreeMap<String, Long> sorted(Map<String, LongAdder> counts) {
      TreeMap<String, Long> out = new TreeMap<>();
      counts.forEach((k, v) -> out.put(k, v.sum()));
      return out;
   }
}
