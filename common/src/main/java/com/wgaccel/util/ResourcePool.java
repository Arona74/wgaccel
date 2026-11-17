package com.wgaccel.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;

/** Bounded leases. Closing discards idle entries now and active entries only when their owners return them. */
public final class ResourcePool<T extends ResourcePool.Entry> implements AutoCloseable {
   public interface Entry extends AutoCloseable {
      int capacity();
      @Override void close();
   }
   private final ArrayDeque<T> idle = new ArrayDeque<>();
   private final Semaphore permits;
   private final IntFunction<T> factory;
   private final int retainedCapacity;
   private int active;
   private boolean closed;
   private boolean disposingIdle;
   private Runnable onDrained;
   public final LongAdder made = new LongAdder();
   public final LongAdder reused = new LongAdder();

   public ResourcePool(int slots, int retainedCapacity, IntFunction<T> factory, Runnable onDrained) {
      if (slots < 1 || retainedCapacity < 1) throw new IllegalArgumentException("pool bounds");
      this.permits = new Semaphore(slots);
      this.retainedCapacity = retainedCapacity;
      this.factory = factory;
      this.onDrained = onDrained;
   }

   public Lease borrow(int capacity) {
      if (capacity < 1) throw new IllegalArgumentException("capacity");
      try { this.permits.acquire(); }
      catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("interrupted while waiting for a GPU buffer slot", interrupted);
      }
      T entry;
      synchronized (this.idle) {
         if (this.closed) {
            this.permits.release();
            throw new IllegalStateException("pool closed");
         }
         this.active++;
         entry = this.idle.pollFirst();
      }
      try {
         if (entry != null && entry.capacity() < capacity) {
            T small = entry; entry = null;
            small.close();
         }
         if (entry == null) {
            entry = this.factory.apply(capacity);
            this.made.increment();
         } else this.reused.increment();
         return new Lease(entry);
      } catch (Throwable failure) {
         try { this.release(entry, false); }
         catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
         throw failure;
      }
   }

   public final class Lease implements AutoCloseable {
      private T entry;
      private boolean reusable = true;
      private Lease(T entry) { this.entry = entry; }
      public T get() {
         if (this.entry == null) throw new IllegalStateException("lease already returned");
         return this.entry;
      }
      public void discard() { this.reusable = false; }
      @Override public void close() {
         T old = this.entry;
         if (old == null) return;
         this.entry = null;
         ResourcePool.this.release(old, this.reusable);
      }
   }

   private void release(T entry, boolean reusable) {
      try {
         boolean retained = false;
         synchronized (this.idle) {
            if (entry != null && reusable && !this.closed && entry.capacity() <= this.retainedCapacity) {
               this.idle.addLast(entry); retained = true;
            }
         }
         if (entry != null && !retained) entry.close();
      } finally {
         Runnable drained;
         synchronized (this.idle) { this.active--; drained = this.drained(); }
         this.permits.release();
         if (drained != null) drained.run();
      }
   }

   /** Called under idle's lock. The callback is invoked once, after every disposal and active lease is finished. */
   private Runnable drained() {
      if (!this.closed || this.disposingIdle || this.active != 0) return null;
      Runnable callback = this.onDrained; this.onDrained = null;
      return callback;
   }

   @Override public void close() {
      List<T> dispose;
      synchronized (this.idle) {
         if (this.closed) return;
         this.closed = true; this.disposingIdle = true;
         dispose = new ArrayList<>(this.idle); this.idle.clear();
      }
      RuntimeException failure = null;
      try {
         for (T entry : dispose) {
            try { entry.close(); }
            catch (RuntimeException problem) {
               if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
         }
      } finally {
         Runnable drained;
         synchronized (this.idle) { this.disposingIdle = false; drained = this.drained(); }
         if (drained != null) drained.run();
      }
      if (failure != null) throw failure;
   }
}
