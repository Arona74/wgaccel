package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * FreeTerraForged 1.0.0013 (no tiles) works a chunk's 16 x 16 cells out from scratch for every chunk object
 * (ChunkFilterGrid.generateChunkCells: the heightmap and the terrain filters with a border), and our look-aheads' stand-in
 * chunks are chunk objects too: the 2026-10-22 08:45 run worked each chunk's cells out three times (the fill look-ahead,
 * the biome look-ahead, the real chunk: 28% of all CPU, 60 to 90 chunks/s). Here the stand-ins' cells are kept by chunk
 * and handed out as copies; the real chunk takes them (and they are dropped). The cells are a function of the position
 * and the world only. 1 in 64 handed-out copies is worked out again and compared field by field; a difference turns
 * this off for good. FreeTerraForged 1.0.0010 never calls generateChunkCells (its tiles are cached already); a version
 * that caches cells itself turns this off ({@link FtfSlowPaths#chunkCells}). A version whose generateChunkCells also
 * fills the surface envelope's cache keeps it to the stand-ins ({@link FtfSlowPaths#standInCells}): the second stand-in
 * of a chunk gets the first one's cells, the chunk itself always makes its own.
 */
public final class FtfChunkCells {
   /** Kept cells at most (about 50 KB each); the oldest go first. Stand-ins the real chunk never takes end here. */
   private static final int MAX_KEPT = 4096;
   private static final ConcurrentHashMap<Key, Cell[]> KEPT = new ConcurrentHashMap<>();
   private static final ConcurrentLinkedQueue<Key> ORDER = new ConcurrentLinkedQueue<>();
   private static final AtomicInteger SIZE = new AtomicInteger();
   private static final LongAdder MADE = new LongAdder();
   private static final LongAdder SHARED = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(4096);
   private static volatile boolean off;
   private static volatile List<Field> fields;

   private record Key(int x, int z, GeneratorContext context) {
      @Override
      public boolean equals(Object other) {
         return other instanceof Key key && key.x == this.x && key.z == this.z && key.context == this.context;
      }

      @Override
      public int hashCode() {
         return (this.x * 31 + this.z) * 31 + System.identityHashCode(this.context);
      }
   }

   private FtfChunkCells() {
   }

   /** generateChunkCells(x, z, context) through the cache; make is FreeTerraForged's own call. */
   public static Cell[] cells(int x, int z, GeneratorContext context, Supplier<Cell[]> make) {
      if (off || !Config.FTF_SHARED_CHUNK_CELLS.get() || !FtfSlowPaths.standInCells()) {
         return make.get();
      }

      Key key = new Key(x, z, context);
      // Our look-ahead threads make stand-ins; any other thread is the chunk itself (or a feature reading it).
      boolean standIn = Thread.currentThread().getName().startsWith("WGA GPU");
      if (!standIn && !FtfSlowPaths.chunkCells()) {
         // Only the stand-ins share (FtfSlowPaths.standInCells): the chunk's own cells through FreeTerraForged's call, and
         // the kept ones are not needed any more.
         if (KEPT.remove(key) != null) {
            SIZE.decrementAndGet();
         }

         return make.get();
      }

      Cell[] kept = standIn ? KEPT.get(key) : KEPT.remove(key);
      if (kept == null) {
         Cell[] made = make.get();
         MADE.increment();
         if (standIn) {
            if (KEPT.putIfAbsent(key, copy(made)) == null) {
               ORDER.add(key);
               if (SIZE.incrementAndGet() > MAX_KEPT) {
                  Key oldest = ORDER.poll();
                  if (oldest != null && KEPT.remove(oldest) != null) {
                     SIZE.decrementAndGet();
                  }
               }
            }
         }

         return made;
      }

      if (!standIn) {
         SIZE.decrementAndGet();
      }

      SHARED.increment();
      Cell[] out = copy(kept);
      if (ThreadLocalRandom.current().nextInt(64) == 0) {
         CHECKED.increment();
         String difference = compare(out, make.get());
         if (difference != null) {
            DIFFERENT.increment();
            off = true;
            KEPT.clear();
            com.wgaccel.WgaChat.warnOff("WGA shared FTF chunk cells off: chunk cells at {} {} differ: {}", x, z, difference);
            return make.get();
         }
      }

      if (Due.due(NEXT_REPORT, SHARED.sum(), 2)) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return out;
   }

   private static Cell[] copy(Cell[] cells) {
      Cell[] out = new Cell[cells.length];
      for (int i = 0; i < cells.length; i++) {
         out[i] = new Cell();
         out[i].copyFrom(cells[i]);
      }

      return out;
   }

   /** The first difference between two cell arrays (every instance field of Cell), or null. */
   static String compare(Cell[] a, Cell[] b) {
      if (a.length != b.length) {
         return "length " + a.length + " vs " + b.length;
      }

      try {
         for (int i = 0; i < a.length; i++) {
            for (Field field : fields()) {
               Object x = field.get(a[i]);
               Object y = field.get(b[i]);
               boolean same = x instanceof Float f ? Float.floatToIntBits(f) == Float.floatToIntBits((Float)y)
                  : x instanceof Number || x instanceof Boolean || x instanceof Character ? x.equals(y) : x == y;
               if (!same) {
                  return "cell " + i + " " + field.getName() + " " + x + " vs " + y;
               }
            }
         }
      } catch (IllegalAccessException failure) {
         return failure.toString();
      }

      return null;
   }

   private static List<Field> fields() {
      List<Field> list = fields;
      if (list == null) {
         list = new ArrayList<>();
         for (Class<?> type = Cell.class; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
               if (!Modifier.isStatic(field.getModifiers())) {
                  field.setAccessible(true);
                  list.add(field);
               }
            }
         }

         fields = list;
      }

      return list;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA shared FTF chunk cells: %,d worked out, %,d shared (%,d kept now), checked %,d: %s", MADE.sum(), SHARED.sum(),
         SIZE.get(), CHECKED.sum(), DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
