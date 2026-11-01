package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * FreeTerraForged's filtered regions (1.0.0013 and later: 4 x 4 chunks, FilteredChunkCache) made ahead, at their chunks'
 * structure starts. FTF makes a region on the first thread asking for one of its chunks, and every other thread asking
 * waits for it: the 31 Oct 2026 profile (1.0.0018, r1000, C2ME 12 workers) had the look-aheads and the workers waiting
 * there ~50 s in a 35 s window (biomes look-ahead 25.6 s, workers 15.4 s, noise look-ahead 8.0 s), the look-aheads taking
 * chunks in order and so mostly the same region. Here each region is queued once, at the first structure start of its
 * chunks (well before their noise), and made on a few threads of our own, each a different region, the usual way
 * (getFilteredChunk). The cells are FreeTerraForged's own whoever makes them. A region already in FTF's cache is skipped; past
 * the queue's bound the oldest are dropped (their chunks make them as before). Off by default (config ftfRegionsAhead):
 * the 1 Nov 2026 runs halved the biome step's wait (ground read 9.4-11.3 -> 3.4-5.6 ms a chunk) but not the time, as the
 * GPU surface's wait grew by as much, and ~20% more regions were made (the structure-starts-only edge, more dropped from
 * FTF's cache before their chunks came).
 */
public final class FtfRegionsAhead {
   private static final int THREADS = 3;
   private static final int QUEUE = 512;
   private static final int REMEMBER = 16_384;
   private static final AtomicInteger NAMES = new AtomicInteger();
   private static final LinkedBlockingDeque<Region> QUEUED = new LinkedBlockingDeque<>();
   private static final ConcurrentHashMap<Long, Boolean> SEEN = new ConcurrentHashMap<>();
   private static final ConcurrentLinkedQueue<Long> SEEN_ORDER = new ConcurrentLinkedQueue<>();
   private static final LongAdder NOTICED = new LongAdder();
   private static final LongAdder MADE = new LongAdder();
   private static final LongAdder CACHED = new LongAdder();
   private static final LongAdder DROPPED = new LongAdder();
   private static final LongAdder NANOS = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(64);
   private static final boolean FTF = com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
   private static volatile boolean off;
   private static volatile boolean started;
   private static volatile Object lastRandom;
   private static volatile Object lastCache;
   private static volatile Method getFiltered;
   private static volatile Method getIfPresent;

   private FtfRegionsAhead() {
   }

   private record Region(Object cache, int x, int z) {
   }

   /** At a new chunk's structure starts: its region queued once. */
   public static void noticed(ServerLevel level, ChunkPos pos) {
      if (!FTF || off || !Config.FTF_REGIONS_AHEAD.getAsBoolean() || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
         return;
      }

      try {
         Object cache = cache(level);
         if (cache == null) {
            return;
         }

         int x = com.wgaccel.platform.Mc.x(pos) & -4;
         int z = com.wgaccel.platform.Mc.z(pos) & -4;
         long key = (long)x << 32 | z & 0xFFFFFFFFL;
         if (SEEN.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
         }

         SEEN_ORDER.add(key);
         if (SEEN.size() > REMEMBER) {
            Long old = SEEN_ORDER.poll();
            if (old != null) {
               SEEN.remove(old);
            }
         }

         NOTICED.increment();
         start();
         QUEUED.addLast(new Region(cache, x, z));
         while (QUEUED.size() > QUEUE && QUEUED.pollFirst() != null) {
            DROPPED.increment();
         }
      } catch (Throwable failure) {
         off(failure);
      }
   }

   /** The overworld's FilteredChunkCache, or null (not FreeTerraForged, or a version without one). */
   private static Object cache(ServerLevel level) throws ReflectiveOperationException {
      Object random = level.getChunkSource().randomState();
      if (random == lastRandom) {
         return lastCache;
      }

      Object cache = null;
      if (random instanceof etcodehome.freeterraforged.world.worldgen.FTFRandomState ftf && ftf.isTerraForged() && ftf.generatorContext() != null) {
         Object context = ftf.generatorContext();
         Field field;
         try {
            // GeneratorContext.filteredChunkCache (1.0.0013; the build compiles against 1.0.0010's, without it).
            field = Names.declaredField(context.getClass(), "filteredChunkCache");
         } catch (NoSuchFieldException none) {
            field = null;
         }

         if (field != null) {
            field.setAccessible(true);
            cache = field.get(context);
            if (cache != null && getFiltered == null) {
               getIfPresent = Names.method(cache.getClass(), "getIfPresent", int.class, int.class);
               getFiltered = Names.method(cache.getClass(), "getFilteredChunk", int.class, int.class);
            }
         }
      }

      if (cache != lastCache) {
         // A new world: what was queued for the last one is not wanted.
         QUEUED.clear();
         SEEN.clear();
         SEEN_ORDER.clear();
      }

      lastCache = cache;
      lastRandom = random;
      return cache;
   }

   private static synchronized void start() {
      if (started) {
         return;
      }

      started = true;
      for (int i = 0; i < THREADS; i++) {
         Thread thread = new Thread(FtfRegionsAhead::run, "WGA FTF regions ahead " + NAMES.getAndIncrement());
         thread.setDaemon(true);
         thread.start();
      }

      WorldgenAccelerator.LOG.info("WGA FreeTerraForged regions ahead: on ({} threads; regions noticed at their chunks' structure starts)", THREADS);
   }

   private static void run() {
      while (!off) {
         Region region;
         try {
            region = QUEUED.pollFirst(1, TimeUnit.SECONDS);
         } catch (InterruptedException interrupted) {
            return;
         }

         if (region == null || region.cache != lastCache || !Config.FTF_REGIONS_AHEAD.getAsBoolean()) {
            continue;
         }

         try {
            // The region's first and last chunk cached: made already (by a chunk of it, before we got to it).
            if (getIfPresent.invoke(region.cache, region.x, region.z) != null && getIfPresent.invoke(region.cache, region.x + 3, region.z + 3) != null) {
               CACHED.increment();
               continue;
            }

            long start = System.nanoTime();
            getFiltered.invoke(region.cache, region.x, region.z);
            NANOS.add(System.nanoTime() - start);
            MADE.increment();
            if (Due.due(NEXT_REPORT, MADE.sum(), 2)) {
               WorldgenAccelerator.LOG.info(summary());
            }
         } catch (Throwable failure) {
            off(failure instanceof java.lang.reflect.InvocationTargetException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure);
         }
      }
   }

   private static void off(Throwable failure) {
      if (!off) {
         off = true;
         QUEUED.clear();
         WorldgenAccelerator.LOG.warn("WGA FreeTerraForged regions ahead: off ({}); regions are made by their chunks as before", failure.toString());
      }
   }

   public static String summary() {
      long made = MADE.sum();
      return String.format(Locale.ROOT, "WGA FreeTerraForged regions ahead: %,d noticed, %,d made ahead (%.1f ms each), %,d found made already, %,d dropped (queue full)",
         NOTICED.sum(), made, made == 0 ? 0.0 : NANOS.sum() / 1.0e6 / made, CACHED.sum(), DROPPED.sum());
   }
}
