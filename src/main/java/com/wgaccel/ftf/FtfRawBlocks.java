package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gen.TileNoise;
import com.wgaccel.gpu.TileNoiseGpu;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * GPU step T2 (with config gpuTileNoise): FreeTerraForged 1.0.0013's raw chunk cells (RawChunkCache.getRawChunk: its
 * Heightmap.apply over a chunk's 256 columns) with the terrain populators' noise from the GPU, as T1 does for 1.0.0010's
 * tiles. The 23 Oct 2026 profile (JFR, r1000, 1.0.0013, RX 9070 XT): making raw chunks was 24% of all CPU, the
 * populators 14% (their noise modules the top methods), rivers 12%, the rest ~5%.
 * <p>
 * The populators' values are made per block of 4 x 4 chunks, aligned to world coordinates: the first chunk asked in a
 * block runs one dry pass of Heightmap.applyTerrain over its 64 x 64 columns (the GPU's populators only note what they
 * would be asked: {@link TileNoise#record}) and one GPU job for those values ({@link TileNoiseGpu#fill}, every populator
 * with a GPU version: {@link TileNoiseGpu#allPopulators}); the block's table is kept. Each chunk is then made by the
 * thread asking for it, FreeTerraForged's own Heightmap.apply with the populators taking their values from the table
 * (every 64th checked against FreeTerraForged's own; a difference turns tile noise off), and goes into FreeTerraForged's
 * cache its own way (put if absent, the oldest dropped past its size), so a later request is its usual hit. A first try
 * (one thread making all 16 chunks, ~85 ms, others waiting for it) left the CPU 13 of 16 busy at the same speed.
 * Raw cells are the position's own (no side effects): making them early or in another order changes nothing.
 */
public final class FtfRawBlocks {
   /** Chunks per side of a block. */
   private static final int BLOCK = 4;
   /** Blocks whose table is kept at most (a block's chunks are asked close together; the oldest go first). */
   private static final int KEPT = 128;

   /** A block's table, ready once its dry pass and GPU job are done. */
   private static final class Block {
      final CompletableFuture<TileNoiseGpu.Table> table = new CompletableFuture<>();
      final Object key = new Object();
   }

   private static final ConcurrentHashMap<Long, Block> BLOCKS_KEPT = new ConcurrentHashMap<>();
   private static final ConcurrentLinkedQueue<Long> BLOCK_ORDER = new ConcurrentLinkedQueue<>();
   private static final LongAdder BLOCKS = new LongAdder();
   private static final LongAdder CHUNKS = new LongAdder();
   private static final LongAdder TABLE_NANOS = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(1024);
   private static volatile boolean off;
   private static volatile Field cacheField;
   private static volatile Field queueField;
   private static volatile Field sizeField;
   private static volatile Field contextField;
   private static volatile Field heightmapField;

   private FtfRawBlocks() {
   }

   /** RawChunkCache.getRawChunk(chunkX, chunkZ) with the populators from the block's table; original is FreeTerraForged's own call. */
   @SuppressWarnings("unchecked")
   public static Cell[] get(Object rawCache, int chunkX, int chunkZ, Supplier<Cell[]> original) {
      if (off || !TileNoise.on() || Config.GPU_MODE.get() == Config.GpuMode.OFF) {
         return original.get();
      }

      try {
         Map<Long, Cell[]> cache = (Map<Long, Cell[]>)field(rawCache, "cache").get(rawCache);
         long chunkKey = pack(chunkX, chunkZ);
         if (cache.containsKey(chunkKey)) {
            return original.get();
         }

         Object context = field(rawCache, "context").get(rawCache);
         Heightmap heightmap = (Heightmap)field(context, "heightmap").get(context);
         TileNoiseGpu.Table table = table(heightmap, Math.floorDiv(chunkX, BLOCK), Math.floorDiv(chunkZ, BLOCK));
         if (table == null) {
            return original.get();
         }

         // FreeTerraForged's own cells (RawChunkCache.getRawChunk's loop), the populators from the block's table.
         Cell[] cells = new Cell[256];
         int minX = chunkX << 4;
         int minZ = chunkZ << 4;
         TileNoise.batch(table);
         try {
            for (int lz = 0; lz < 16; lz++) {
               int row = lz * 16;
               for (int lx = 0; lx < 16; lx++) {
                  Cell cell = new Cell();
                  heightmap.apply(cell, minX + lx, minZ + lz, true);
                  cells[row + lx] = cell;
               }
            }
         } finally {
            TileNoise.batch(null);
         }

         put(rawCache, cache, chunkKey, cells);
         CHUNKS.increment();
         if (Due.due(NEXT_REPORT, CHUNKS.sum(), 2)) {
            WorldgenAccelerator.LOG.info(summary());
         }
      } catch (Throwable failure) {
         off = true;
         TileNoise.batch(null);
         WorldgenAccelerator.LOG.warn("WGA GPU raw chunk blocks off (FreeTerraForged makes raw chunks alone): {}", failure.toString());
      }

      // A hit now (or FreeTerraForged makes it, as before).
      return original.get();
   }

   /** The block's table: made by the first thread to ask (dry pass and GPU job), waited for by the others; null without a program. */
   private static TileNoiseGpu.Table table(Heightmap heightmap, int blockX, int blockZ) {
      long blockKey = pack(blockX, blockZ);
      Block block = BLOCKS_KEPT.get(blockKey);
      if (block == null) {
         Block mine = new Block();
         block = BLOCKS_KEPT.putIfAbsent(blockKey, mine);
         if (block == null) {
            block = mine;
            BLOCK_ORDER.add(blockKey);
            while (BLOCKS_KEPT.size() > KEPT) {
               Long oldest = BLOCK_ORDER.poll();
               if (oldest == null) {
                  break;
               }

               Block dropped = BLOCKS_KEPT.remove(oldest);
               if (dropped != null) {
                  TileNoiseGpu.end(dropped.key);
               }
            }

            try {
               mine.table.complete(make(heightmap, mine.key, blockX, blockZ));
            } catch (Throwable failure) {
               mine.table.completeExceptionally(failure);
               throw failure;
            }
         }
      }

      // Never forever (as ErosionGridBatch): past this, FreeTerraForged makes raw chunks alone (the caller turns this off).
      try {
         return block.table.get(30L, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException late) {
         throw new IllegalStateException("a block's populator values did not come in 30 s");
      } catch (java.util.concurrent.ExecutionException failure) {
         throw new IllegalStateException(failure.getCause());
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("interrupted");
      }
   }

   /** The block's dry pass over its 64 x 64 columns and the GPU job for what they asked. */
   private static TileNoiseGpu.Table make(Heightmap heightmap, Object key, int blockX, int blockZ) {
      long start = System.nanoTime();
      TileNoiseGpu.allPopulators = true;
      int size = BLOCK * 16;
      int minX = blockX * size;
      int minZ = blockZ * size;
      TileNoiseGpu.Table table = TileNoiseGpu.begin(key, heightmap, minX, minZ, size);
      if (table == null) {
         return null;
      }

      // The dry pass: which populators each column asks for (placeholders let the rest of the chain run).
      TileNoise.batch(table);
      TileNoise.record(true);
      IntArrayList[] needs;
      try {
         Cell scratch = new Cell();
         for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
               heightmap.applyTerrain(scratch.reset(), minX + dx, minZ + dz);
            }
         }
      } finally {
         needs = TileNoise.record(false);
         TileNoise.batch(null);
      }

      TileNoiseGpu.fill(table, needs);
      BLOCKS.increment();
      TABLE_NANOS.add(System.nanoTime() - start);
      return table;
   }

   /** RawChunkCache.getRawChunk's own insert: put if absent, then past its size (ours: config ftfRawChunkCache) the oldest dropped. */
   @SuppressWarnings("unchecked")
   private static void put(Object rawCache, Map<Long, Cell[]> cache, long key, Cell[] cells) throws ReflectiveOperationException {
      if (cache.putIfAbsent(key, cells) != null) {
         return;
      }

      ConcurrentLinkedQueue<Long> queue = (ConcurrentLinkedQueue<Long>)field(rawCache, "evictionQueue").get(rawCache);
      AtomicInteger count = (AtomicInteger)field(rawCache, "size").get(rawCache);
      queue.offer(key);
      int wanted = Config.FTF_RAW_CHUNK_CACHE.getAsInt();
      int max = wanted > 0 ? Math.max(1024, wanted) : 1024;
      if (count.incrementAndGet() > max) {
         Long oldest = queue.poll();
         if (oldest != null) {
            cache.remove(oldest);
            count.decrementAndGet();
         }
      }
   }

   /** FreeTerraForged's PosUtil.pack(left, right). */
   private static long pack(int left, int right) {
      return ((long)right & 0xFFFFFFFFL) | ((long)left & 0xFFFFFFFFL) << 32;
   }

   private static Field field(Object target, String name) throws NoSuchFieldException {
      Field known = switch (name) {
         case "cache" -> cacheField;
         case "evictionQueue" -> queueField;
         case "size" -> sizeField;
         case "context" -> contextField;
         default -> heightmapField;
      };
      if (known != null) {
         return known;
      }

      Field found = null;
      for (Class<?> type = target.getClass(); type != null && found == null; type = type.getSuperclass()) {
         try {
            found = type.getDeclaredField(name);
         } catch (NoSuchFieldException ignored) {
         }
      }

      if (found == null) {
         throw new NoSuchFieldException(target.getClass().getName() + "." + name);
      }

      found.setAccessible(true);
      switch (name) {
         case "cache" -> cacheField = found;
         case "evictionQueue" -> queueField = found;
         case "size" -> sizeField = found;
         case "context" -> contextField = found;
         default -> heightmapField = found;
      }

      return found;
   }

   public static String summary() {
      long blocks = Math.max(1, BLOCKS.sum());
      return String.format(Locale.ROOT, "WGA GPU raw chunk blocks: %,d chunks made from %,d block tables (dry pass and GPU %.1f ms per table); %s", CHUNKS.sum(),
         BLOCKS.sum(), TABLE_NANOS.sum() / 1.0e6 / blocks, TileNoiseGpu.summary());
   }
}
