package com.wgaccel.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Micro-batching for the surface step, as {@link FillBatcher} does for the noise fill: bounded queue backpressure,
 * cancellation-safe {@link CompletableFuture} completion, and clean shutdown fallback to the CPU. Uses no Minecraft classes.
 */
public final class SurfaceBatcher {
   private static final class Request {
      final SurfaceGpu.Job job;
      final short[] out;
      final long queued = System.nanoTime();
      final CompletableFuture<Boolean> done = new CompletableFuture<>();

      Request(SurfaceGpu.Job job, short[] out) {
         this.job = job;
         this.out = out;
      }
   }

   private final SurfaceGpu gpu;
   private final int max;
   private final long waitNanos;
   private final LinkedBlockingQueue<Request> queue;
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
      this.queue = new LinkedBlockingQueue<>(Math.max(this.max * 4, 64));
      for (int i = 0; i < threadCount; i++) {
         Thread thread = new Thread(this::run, "WGA GPU surface batch " + i);
         thread.setDaemon(true);
         this.threads.add(thread);
         thread.start();
      }
   }

   /** Whether the queue is already saturated so a caller should fall back to the CPU without waiting. */
   public boolean busy() {
      return this.stopped || this.queue.size() >= 2 * this.max || this.queue.remainingCapacity() == 0;
   }

   /** Submits a surface job without blocking; resolves to {@code false} when backpressured, stopped, or column needs CPU. */
   public CompletableFuture<Boolean> submit(SurfaceGpu.Job job, short[] out) {
      if (this.stopped) {
         return CompletableFuture.completedFuture(false);
      }

      Request request = new Request(job, out);
      if (!this.queue.offer(request)) {
         return CompletableFuture.completedFuture(false);
      }

      if (this.stopped && this.queue.remove(request)) {
         request.done.complete(false);
      }

      return request.done;
   }

   /** As {@link SurfaceGpu#run}: the chunk's surface in the next batch; false on CPU column, busy queue, stop, or cancel. */
   public boolean run(SurfaceGpu.Job job, short[] out) {
      CompletableFuture<Boolean> future = this.submit(job, out);
      try {
         return Boolean.TRUE.equals(future.get());
      } catch (CancellationException cancelled) {
         return false;
      } catch (InterruptedException interrupted) {
         future.cancel(false);
         Thread.currentThread().interrupt();
         return false;
      } catch (ExecutionException failure) {
         Throwable cause = failure.getCause();
         throw new IllegalStateException("GPU surface batch failed: " + cause, cause);
      }
   }

   /** A new world: the batch threads end; waiting chunks resolve {@code false}. */
   public void stop() {
      this.stopped = true;
      for (Thread thread : this.threads) {
         thread.interrupt();
      }

      List<Request> left = new ArrayList<>();
      this.queue.drainTo(left);
      for (Request request : left) {
         request.done.complete(false);
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
      List<Request> active = new ArrayList<>();
      List<SurfaceGpu.Job> jobs = new ArrayList<>();
      List<short[]> outs = new ArrayList<>();
      while (!this.stopped) {
         batch.clear();
         active.clear();
         boolean completed = false;
         try {
            Request first = this.queue.take();
            if (!first.done.isDone()) {
               batch.add(first);
            }
            this.queue.drainTo(batch, this.max - batch.size());
            if (batch.size() == 1 && this.waitNanos > 0L && !this.stopped) {
               Request second = this.queue.poll(this.waitNanos, TimeUnit.NANOSECONDS);
               if (second != null && !second.done.isDone()) {
                  batch.add(second);
               }
               if (!batch.isEmpty()) {
                  this.queue.drainTo(batch, this.max - batch.size());
               }
            }

            if (batch.isEmpty()) {
               completed = true;
               continue;
            }

            if (this.stopped) {
               continue;
            }

            jobs.clear();
            outs.clear();
            long start = System.nanoTime();
            for (Request request : batch) {
               if (!request.done.isDone()) {
                  this.queuedNanos.add(start - request.queued);
                  active.add(request);
                  jobs.add(request.job);
                  outs.add(request.out);
               }
            }

            if (active.isEmpty()) {
               completed = true;
               continue;
            }

            try {
               boolean[] usable = this.gpu.runBatch(jobs, outs);
               this.runNanos.add((System.nanoTime() - start) * active.size());
               this.batches.increment();
               this.chunks.add(active.size());
               for (int i = 0; i < active.size(); i++) {
                  active.get(i).done.complete(usable != null && usable[i]);
               }
            } catch (Throwable thrown) {
               for (Request request : active) {
                  request.done.completeExceptionally(thrown);
               }
            }
            completed = true;
         } catch (InterruptedException interrupted) {
            break;
         } finally {
            if (!completed) {
               for (Request request : batch) {
                  request.done.complete(false);
               }
            }
         }
      }
   }
}
