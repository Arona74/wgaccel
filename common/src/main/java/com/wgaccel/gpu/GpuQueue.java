package com.wgaccel.gpu;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How many chunks may be on the GPU at once (config gpuQueueLimit), shared by the noise fill and the surface: a chunk
 * that finds the card busy does that step on the CPU instead (the same blocks either way), so the generation threads
 * do not stand waiting while the card is behind (it is shared with the game's rendering). The noise fill saves the
 * most CPU per GPU millisecond, so the surface only takes a place while more than one stays free for it.
 *
 * <p>S4 (config gpuQueueAdaptive): the limit then follows what the surface step measures. Both ways of doing a
 * chunk's surface are timed (from the start of its surface step to its end); every 32 timings, if the GPU way takes
 * less than gpuQueueOpenBelow of the CPU way while chunks were still sent to the CPU as busy, one more place is opened; if the GPU
 * way takes as long as the CPU way, one is closed (between 2 and 32). So the GPU and the CPU each take the chunks they
 * do faster at that moment.
 */
public final class GpuQueue {
   private static final class Places extends Semaphore {
      Places(int permits) {
         super(permits);
      }

      void shrink() {
         this.reducePermits(1);
      }
   }

   private static volatile Places places;
   private static volatile boolean adaptive;
   private static volatile double openBelow = 0.9;
   private static final AtomicInteger LIMIT = new AtomicInteger();
   private static final AtomicLong GPU_SUM = new AtomicLong();
   private static final AtomicLong CPU_SUM = new AtomicLong();
   private static final AtomicInteger GPU_COUNT = new AtomicInteger();
   private static final AtomicInteger CPU_COUNT = new AtomicInteger();
   private static final AtomicInteger BUSY = new AtomicInteger();
   private static final AtomicInteger SAMPLES = new AtomicInteger();
   private static volatile double gpuAverage;
   private static volatile double cpuAverage;
   private static final int MIN = 2;
   private static final int MAX = 32;

   private GpuQueue() {
   }

   /** When a world loads: the limit (0: no limit), and whether it follows the measured times. */
   public static void configure(int limit, boolean adapt, double below) {
      places = limit <= 0 ? null : new Places(limit);
      openBelow = below;
      adaptive = adapt && limit > 0;
      LIMIT.set(limit);
      GPU_SUM.set(0);
      CPU_SUM.set(0);
      GPU_COUNT.set(0);
      CPU_COUNT.set(0);
      BUSY.set(0);
      SAMPLES.set(0);
      gpuAverage = 0;
      cpuAverage = 0;
   }

   /** A place for a noise fill: the semaphore to give back with {@link #release}, NONE without a limit, or null when busy. */
   public static Object tryFill() {
      Semaphore s = places;
      return s == null ? NONE : s.tryAcquire() ? s : null;
   }

   /** A place for a surface, only while at least one more stays free for the noise fill; as {@link #tryFill}. */
   public static Object trySurface() {
      Semaphore s = places;
      if (s == null) {
         return NONE;
      }

      if (s.availablePermits() > 1 && s.tryAcquire()) {
         return s;
      }

      BUSY.incrementAndGet();
      return null;
   }

   public static void release(Object place) {
      if (place instanceof Semaphore s) {
         s.release();
      }
   }

   /** A surface step done the GPU way (true) or the CPU way because the card was busy (false), start to end. */
   public static void surfaceTimed(boolean gpu, long nanos) {
      if (!adaptive) {
         return;
      }

      if (gpu) {
         GPU_SUM.addAndGet(nanos);
         GPU_COUNT.incrementAndGet();
      } else {
         CPU_SUM.addAndGet(nanos);
         CPU_COUNT.incrementAndGet();
      }

      if (SAMPLES.incrementAndGet() % 32 != 0) {
         return;
      }

      int g = GPU_COUNT.getAndSet(0);
      int c = CPU_COUNT.getAndSet(0);
      long gs = GPU_SUM.getAndSet(0);
      long cs = CPU_SUM.getAndSet(0);
      int busy = BUSY.getAndSet(0);
      // Averages that keep the last windows' weight, so a window with few of one kind does not swing them.
      if (g > 0) {
         gpuAverage = gpuAverage == 0 ? (double)gs / g : gpuAverage * 0.7 + 0.3 * gs / g;
      }

      if (c > 0) {
         cpuAverage = cpuAverage == 0 ? (double)cs / c : cpuAverage * 0.7 + 0.3 * cs / c;
      }

      Places s = places;
      if (s == null || gpuAverage == 0 || cpuAverage == 0) {
         return;
      }

      int limit = LIMIT.get();
      if (gpuAverage < openBelow * cpuAverage && busy > 0 && limit < MAX && LIMIT.compareAndSet(limit, limit + 1)) {
         s.release();
      } else if (gpuAverage > cpuAverage && limit > MIN && LIMIT.compareAndSet(limit, limit - 1)) {
         s.shrink();
      }
   }

   /** For the logs: the current limit and the measured surface times. */
   public static String summary() {
      if (!adaptive) {
         return "";
      }

      return String.format(java.util.Locale.ROOT, "; GPU places %d (surface the GPU way %.1f ms, the CPU way %.1f ms)", LIMIT.get(), gpuAverage / 1.0e6,
         cpuAverage / 1.0e6);
   }

   public static final Object NONE = new Object();
}
