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
 * G4a micro-batching for the noise fill: the generation threads hand their chunks in ({@link #fill} or
 * {@link #submit}) into a bounded queue; batch threads take whatever has gathered (at most {@code max} chunks, waiting
 * a moment for a second one when only one is there) and run it as one launch per kernel ({@link FillGpu#fillBatch}).
 * Saturated or stopped batchers decline new work without failing the GPU path so callers fall back to the CPU.
 */
public final class FillBatcher {
   private static final class Request {
      final FillGpu.Job job;
      final byte[] codes;
      final CompletableFuture<Boolean> done = new CompletableFuture<>();

      Request(FillGpu.Job job, byte[] codes) {
         this.job = job;
         this.codes = codes;
      }
   }

   private final FillGpu gpu;
   private final int max;
   private final long waitNanos;
   private final LinkedBlockingQueue<Request> queue;
   private final List<Thread> threads = new ArrayList<>();
   private volatile boolean stopped;
   public final LongAdder batches = new LongAdder();
   public final LongAdder chunks = new LongAdder();

   public FillBatcher(FillGpu gpu, int max, int threadCount, long waitMicros) {
      this.gpu = gpu;
      this.max = Math.max(1, max);
      this.waitNanos = waitMicros * 1000L;
      this.queue = new LinkedBlockingQueue<>(Math.max(this.max * 4, 64));
      for (int i = 0; i < threadCount; i++) {
         Thread thread = new Thread(this::run, "WGA GPU fill batch " + i);
         thread.setDaemon(true);
         this.threads.add(thread);
         thread.start();
      }
   }

   /** Whether so many chunks are waiting that one more would wait longer than the CPU takes (it then fills on the CPU). */
   public boolean busy() {
      return this.stopped || this.queue.size() >= 2 * this.max || this.queue.remainingCapacity() == 0;
   }

   /** Submits a chunk without blocking; resolves to {@code true} when written or {@code false} when backpressured/stopped. */
   public CompletableFuture<Boolean> submit(FillGpu.Job job, byte[] codes) {
      if (this.stopped) {
         return CompletableFuture.completedFuture(false);
      }

      Request request = new Request(job, codes);
      if (!this.queue.offer(request)) {
         return CompletableFuture.completedFuture(false);
      }

      if (this.stopped && this.queue.remove(request)) {
         request.done.complete(false);
      }

      return request.done;
   }

   /** Works the chunk out in the next batch; returns {@code false} on backpressure/stop/cancellation, rethrows GPU errors. */
   public boolean fill(FillGpu.Job job, byte[] codes) {
      CompletableFuture<Boolean> future = this.submit(job, codes);
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
         throw new IllegalStateException("GPU fill batch failed: " + cause, cause);
      }
   }

   /** A new world: the batch threads end; waiting chunks resolve {@code false} and fall back to the CPU. */
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

   private void run() {
      List<Request> batch = new ArrayList<>();
      List<FillGpu.Job> jobs = new ArrayList<>();
      List<byte[]> codes = new ArrayList<>();
      while (!this.stopped) {
         batch.clear();
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
            codes.clear();
            List<Request> active = new ArrayList<>(batch.size());
            for (Request request : batch) {
               if (!request.done.isDone()) {
                  active.add(request);
                  jobs.add(request.job);
                  codes.add(request.codes);
               }
            }

            if (active.isEmpty()) {
               completed = true;
               continue;
            }

            try {
               this.gpu.fillBatch(jobs, codes);
               this.batches.increment();
               this.chunks.add(active.size());
               for (Request request : active) {
                  request.done.complete(true);
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
