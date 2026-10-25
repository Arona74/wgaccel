package com.wgaccel.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Micro-batching for the surface step, as {@link FillBatcher} does for the noise fill: the generation threads hand
 * their chunks in ({@link #run}) and wait; a few batch threads take whatever has gathered (at most {@code max}
 * chunks, waiting a moment for a second one when only one is there) and run it as one launch per kernel
 * ({@link SurfaceGpu#runBatch}, the surface look-ahead's batch path: per chunk the same blocks as alone). The run of
 * 0.9.45 had one launch per chunk, about 15 ms of GPU waiting per surface and 62% of surfaces on the CPU as the card
 * was busy. Uses no Minecraft classes.
 */
public final class SurfaceBatcher {
   private static final class Request {
      final SurfaceGpu.Job job;
      final short[] out;
      final Thread waiter = Thread.currentThread();
      final long queued = System.nanoTime();
      volatile boolean done;
      volatile boolean usable;
      volatile Throwable failure;

      Request(SurfaceGpu.Job job, short[] out) {
         this.job = job;
         this.out = out;
      }
   }

   private final SurfaceGpu gpu;
   private final int max;
   private final long waitNanos;
   private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<>();
   private final List<Thread> threads = new ArrayList<>();
   private volatile boolean stopped;
   public final LongAdder batches = new LongAdder();
   public final LongAdder chunks = new LongAdder();
   private final LongAdder queuedNanos = new LongAdder();
   private final LongAdder runNanos = new LongAdder();

   public SurfaceBatcher(SurfaceGpu gpu, int max, int threadCount, long waitMicros) {
      this.gpu = gpu;
      this.max = Math.max(1, max);
      this.waitNanos = waitMicros * 1000L;
      for (int i = 0; i < threadCount; i++) {
         Thread thread = new Thread(this::run, "WGA GPU surface batch " + i);
         thread.setDaemon(true);
         this.threads.add(thread);
         thread.start();
      }
   }

   /** As {@link SurfaceGpu#run}: the chunk's surface in the next batch, waited for; false when a column needs the CPU. */
   public boolean run(SurfaceGpu.Job job, short[] out) {
      if (this.stopped) {
         throw new IllegalStateException("the surface batcher is stopped");
      }

      Request request = new Request(job, out);
      this.queue.add(request);
      while (!request.done) {
         LockSupport.park(this);
         if (this.stopped && !request.done) {
            throw new IllegalStateException("the surface batcher stopped");
         }
      }

      if (request.failure != null) {
         throw new IllegalStateException("GPU surface batch failed: " + request.failure, request.failure);
      }

      return request.usable;
   }

   /** A new world: the batch threads end; waiting chunks get a failure. */
   public void stop() {
      this.stopped = true;
      for (Thread thread : this.threads) {
         thread.interrupt();
      }

      List<Request> left = new ArrayList<>();
      this.queue.drainTo(left);
      for (Request request : left) {
         request.failure = new IllegalStateException("stopped");
         request.done = true;
         LockSupport.unpark(request.waiter);
      }
   }

   /** Average chunks per launch so far, for the report. */
   public String summary() {
      long b = this.batches.sum();
      long c = Math.max(1L, this.chunks.sum());
      return String.format(java.util.Locale.ROOT, "; batched %,d in %,d launches (%.1f each; per chunk %.2f ms waiting for its batch, %.2f ms its batch on the GPU)",
         this.chunks.sum(), b, b == 0 ? 0.0 : (double)this.chunks.sum() / b, this.queuedNanos.sum() / 1.0e6 / c, this.runNanos.sum() / 1.0e6 / c);
   }

   private void run() {
      List<Request> batch = new ArrayList<>();
      List<SurfaceGpu.Job> jobs = new ArrayList<>();
      List<short[]> outs = new ArrayList<>();
      while (!this.stopped) {
         try {
            batch.clear();
            Request first = this.queue.take();
            batch.add(first);
            this.queue.drainTo(batch, this.max - 1);
            if (batch.size() == 1 && this.waitNanos > 0L) {
               Request second = this.queue.poll(this.waitNanos, TimeUnit.NANOSECONDS);
               if (second != null) {
                  batch.add(second);
                  this.queue.drainTo(batch, this.max - 2);
               }
            }
         } catch (InterruptedException interrupted) {
            break;
         }

         jobs.clear();
         outs.clear();
         for (Request request : batch) {
            jobs.add(request.job);
            outs.add(request.out);
         }

         Throwable failure = null;
         boolean[] usable = null;
         long start = System.nanoTime();
         for (Request request : batch) {
            this.queuedNanos.add(start - request.queued);
         }

         try {
            usable = this.gpu.runBatch(jobs, outs);
            this.runNanos.add((System.nanoTime() - start) * batch.size());
            this.batches.increment();
            this.chunks.add(batch.size());
         } catch (Throwable thrown) {
            failure = thrown;
         }

         for (int i = 0; i < batch.size(); i++) {
            Request request = batch.get(i);
            request.failure = failure;
            request.usable = usable != null && usable[i];
            request.done = true;
            LockSupport.unpark(request.waiter);
         }
      }
   }
}
