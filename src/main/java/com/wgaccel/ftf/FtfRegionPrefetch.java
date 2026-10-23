package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * FreeTerraForged's region filter grids (its 740a69e3 build: 4 x 4 chunks, 88 x 88 cells) with their raw chunks made in
 * parallel. RegionFilterGrid.init asks RawChunkCache for the region's 6 x 6 chunks one after another on the one thread
 * making the region, while every other thread wanting a chunk of it waits (FilteredChunkCache's in-flight region): the
 * 23 Oct 2026 run had the biome step at 14 ms a chunk (9 before), the look-ahead threads mostly waiting, the CPU 12 of 16
 * busy. Here, as a region starts, the raw chunks not cached yet are made on a few threads of our own (each the usual way,
 * RawChunkCache.getRawChunk, with the populators from the GPU) and the region's own walk then finds them cached. Raw
 * cells are the position's own: made earlier or elsewhere, they are the same.
 */
public final class FtfRegionPrefetch {
   private static final int THREADS = 4;
   private static final AtomicInteger NAMES = new AtomicInteger();
   private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, task -> {
      Thread thread = new Thread(task, "WGA raw chunks " + NAMES.getAndIncrement());
      thread.setDaemon(true);
      return thread;
   });
   private static final LongAdder REGIONS = new LongAdder();
   private static final LongAdder PREFETCHED = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(64);
   private static volatile boolean off;

   private FtfRegionPrefetch() {
   }

   private static volatile java.lang.reflect.Field rawField;

   /** RegionFilterGrid.init(regionChunkX, regionChunkZ, context) starts: its raw chunks made in parallel first. */
   public static void before(int regionChunkX, int regionChunkZ, Object context) {
      if (off || context == null || !com.wgaccel.Config.FTF_REGION_PREFETCH.getAsBoolean()) {
         return;
      }

      try {
         // GeneratorContext.rawChunkCache (1.0.0013; the build compiles against 1.0.0010's GeneratorContext, without it).
         java.lang.reflect.Field field = rawField;
         if (field == null) {
            field = context.getClass().getDeclaredField("rawChunkCache");
            field.setAccessible(true);
            rawField = field;
         }

         RawChunkCache raw = (RawChunkCache)field.get(context);
         if (raw == null) {
            return;
         }

         List<Future<?>> made = new ArrayList<>();
         List<int[]> chunks = new ArrayList<>();
         // RegionFilterGrid.init's chunks: the region and a ring of one (its 12-cell border reaches into them).
         for (int cz = regionChunkZ - 1; cz <= regionChunkZ + 4; cz++) {
            for (int cx = regionChunkX - 1; cx <= regionChunkX + 4; cx++) {
               if (raw.getExistingRawCell(cx << 4, cz << 4) == null) {
                  int x = cx;
                  int z = cz;
                  chunks.add(new int[] {x, z});
                  made.add(POOL.submit(() -> raw.getRawChunk(x, z)));
               }
            }
         }

         // This thread helps from the other end rather than only waiting (regions starting together share the pool); a
         // chunk made by both is put once (RawChunkCache's put if absent).
         for (int i = chunks.size() - 1; i >= 0; i--) {
            if (!made.get(i).isDone()) {
               raw.getRawChunk(chunks.get(i)[0], chunks.get(i)[1]);
            }
         }

         // The region's own walk then takes them from the cache (one late or failed is simply made there, as before).
         for (Future<?> future : made) {
            try {
               future.get(30L, TimeUnit.SECONDS);
            } catch (Exception late) {
               // Left to the region's own walk.
            }
         }

         REGIONS.increment();
         PREFETCHED.add(made.size());
         if (Due.due(NEXT_REPORT, REGIONS.sum(), 4)) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA FreeTerraForged regions: %,d regions, %,d raw chunks made ahead in parallel (%.1f per region)",
               REGIONS.sum(), PREFETCHED.sum(), (double)PREFETCHED.sum() / Math.max(1, REGIONS.sum())));
         }
      } catch (Throwable failure) {
         off = true;
         WorldgenAccelerator.LOG.warn("WGA FreeTerraForged regions: raw chunks no longer made ahead: {}", failure.toString());
      }
   }
}
