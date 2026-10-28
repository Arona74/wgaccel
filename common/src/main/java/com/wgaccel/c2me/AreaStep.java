package com.wgaccel.c2me;

import com.wgaccel.platform.Mc;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;

/**
 * The 4 x 4 area step's work (see {@link AreaStatus}): when an aligned area's origin reaches the area status, all 16
 * area chunks have reached STRUCTURE_REFERENCES, so the noise fill's and the biomes' look-aheads have them queued
 * (chunks with structure terrain are skipped by the fill as before). The area's chunks go to the front of both queues,
 * and the status completes once neither has any of them still queued: every area chunk's BIOMES step waits for this,
 * so its biomes and its fill are ready when its steps come (0.9.82, vanilla: ~40,000 "not ready in time"). It never
 * holds the chunk system up for long: after 3 s it completes anyway (counted). No C2ME types here (the reports read
 * {@link #summary} with or without C2ME).
 */
public final class AreaStep {
   private static final long TIMEOUT_NANOS = 3_000_000_000L;
   private static final LongAdder AREAS = new LongAdder();
   private static final LongAdder WAIT_NANOS = new LongAdder();
   private static final LongAdder TIMEOUTS = new LongAdder();
   private static final LongAdder NOTHING_TO_WAIT = new LongAdder();
   private static final ConcurrentLinkedQueue<Waiter> WAITING = new ConcurrentLinkedQueue<>();
   private static volatile Thread watcher;

   private record Waiter(long[] keys, long start, CompletableFuture<Void> done) {
   }

   private AreaStep() {
   }

   /** Config gpuAreaStep (read while the server runs; C2ME's status list itself is built before any config). */
   public static boolean on() {
      try {
         return com.wgaccel.Config.GPU_AREA_STEP.get();
      } catch (Throwable notLoaded) {
         return false;
      }
   }

   /** At a generating area origin, once its area's chunks are at STRUCTURE_REFERENCES: the area's results made. */
   static CompletionStage<Void> run(Object context, ChunkPos origin) {
      AREAS.increment();
      long[] keys = new long[16];
      int n = 0;
      for (int dx = 0; dx < 4; dx++) {
         for (int dz = 0; dz < 4; dz++) {
            long key = Mc.key(Mc.x(origin) + dx, Mc.z(origin) + dz);
            keys[n++] = key;
            com.wgaccel.gen.GpuNoiseFill.aheadSoon(key);
            com.wgaccel.gen.BiomesAhead.soonKey(key);
         }
      }

      if (!pending(keys)) {
         NOTHING_TO_WAIT.increment();
         return CompletableFuture.completedFuture(null);
      }

      CompletableFuture<Void> done = new CompletableFuture<>();
      WAITING.add(new Waiter(keys, System.nanoTime(), done));
      ensureWatcher();
      return done;
   }

   private static boolean pending(long[] keys) {
      for (long key : keys) {
         if (com.wgaccel.gen.GpuNoiseFill.aheadPending(key) || com.wgaccel.gen.BiomesAhead.pending(key)) {
            return true;
         }
      }

      return false;
   }

   /** One daemon thread looks at the waiting areas every half millisecond and completes the ready ones. */
   private static synchronized void ensureWatcher() {
      if (watcher != null && watcher.isAlive()) {
         return;
      }

      Thread thread = new Thread(() -> {
         while (true) {
            long now = System.nanoTime();
            for (Waiter waiter : WAITING) {
               boolean late = now - waiter.start() > TIMEOUT_NANOS;
               if (late || !pending(waiter.keys())) {
                  if (WAITING.remove(waiter)) {
                     WAIT_NANOS.add(now - waiter.start());
                     if (late) {
                        TIMEOUTS.increment();
                     }

                     waiter.done().complete(null);
                  }
               }
            }

            java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
         }
      }, "WGA area step");
      thread.setDaemon(true);
      thread.start();
      watcher = thread;
   }

   /** For the reports: areas, how long they waited for their results, timeouts. */
   public static String summary() {
      long areas = AREAS.sum();
      if (areas == 0L) {
         return "";
      }

      long waited = Math.max(1L, areas - NOTHING_TO_WAIT.sum());
      return String.format(java.util.Locale.ROOT, " | area step: %,d areas (%,d with nothing to wait for), %.2f ms waiting on average, %,d timed out", areas,
         NOTHING_TO_WAIT.sum(), WAIT_NANOS.sum() / 1.0e6 / waited, TIMEOUTS.sum());
   }
}
