package com.wgaccel.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * G4a micro-batching for the noise fill: the generation threads hand their chunks in ({@link #fill}) and wait; a few
 * batch threads take whatever has gathered (at most {@code max} chunks, waiting a moment for a second one when only
 * one is there) and run it as one launch per kernel ({@link FillGpu#fillBatch}), so the card is asked once per batch
 * instead of once per chunk. Each chunk's codes are the same as alone. Uses no Minecraft classes.
 */
public final class FillBatcher {
   private static final class Request {
      final FillGpu.Job job;
      final byte[] codes;
      final Thread waiter = Thread.currentThread();
      volatile boolean done;
      volatile Throwable failure;

      Request(FillGpu.Job job, byte[] codes) {
         this.job = job;
         this.codes = codes;
      }
   }

   private final FillGpu gpu;
   private final int max;
   private final long waitNanos;
   private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<>();
   private final List<Thread> threads = new ArrayList<>();
   private volatile boolean stopped;
   public final LongAdder batches = new LongAdder();
   public final LongAdder chunks = new LongAdder();

   public FillBatcher(FillGpu gpu, int max, int threadCount, long waitMicros) {
      this.gpu = gpu;
      this.max = Math.max(1, max);
      this.waitNanos = waitMicros * 1000L;
      for (int i = 0; i < threadCount; i++) {
         Thread thread = new Thread(this::run, "WGA GPU fill batch " + i);
         thread.setDaemon(true);
         this.threads.add(thread);
         thread.start();
      }
   }

   /** Whether so many chunks are waiting that one more would wait longer than the CPU takes (it then fills on the CPU). */
   public boolean busy() {
      return this.queue.size() >= 2 * this.max;
   }

   /** Works the chunk out in the next batch and waits for it; rethrows a GPU failure. */
   public void fill(FillGpu.Job job, byte[] codes) {
      if (this.stopped) {
         throw new IllegalStateException("the fill batcher is stopped");
      }

      Request request = new Request(job, codes);
      this.queue.add(request);
      while (!request.done) {
         LockSupport.park(this);
         if (this.stopped && !request.done) {
            throw new IllegalStateException("the fill batcher stopped");
         }
      }

      if (request.failure != null) {
         throw new IllegalStateException("GPU fill batch failed: " + request.failure, request.failure);
      }
   }

   /** A new world: the batch threads end; waiting chunks get a failure (they then fill on the CPU). */
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

   private void run() {
      List<Request> batch = new ArrayList<>();
      List<FillGpu.Job> jobs = new ArrayList<>();
      List<byte[]> codes = new ArrayList<>();
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
         codes.clear();
         for (Request request : batch) {
            jobs.add(request.job);
            codes.add(request.codes);
         }

         Throwable failure = null;
         try {
            this.gpu.fillBatch(jobs, codes);
            this.batches.increment();
            this.chunks.add(batch.size());
         } catch (Throwable thrown) {
            failure = thrown;
         }

         for (Request request : batch) {
            request.failure = failure;
            request.done = true;
            LockSupport.unpark(request.waiter);
         }
      }
   }
}
