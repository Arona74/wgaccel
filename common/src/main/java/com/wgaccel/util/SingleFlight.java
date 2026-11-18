package com.wgaccel.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Coalesces simultaneous misses without holding a map lock while the supplier runs. Keeps no completed values. */
public final class SingleFlight<K, V> {
   // A nested supplier never waits on another owner: dependencies between keys cannot form a wait cycle.
   private static final ThreadLocal<Boolean> MAKING = ThreadLocal.withInitial(() -> false);
   private final ConcurrentHashMap<K, CompletableFuture<V>> running = new ConcurrentHashMap<>();
   private final LongAdder joined = new LongAdder();

   public V get(K key, Supplier<V> make) {
      if (MAKING.get()) {
         return make.get();
      }
      CompletableFuture<V> mine = new CompletableFuture<>();
      CompletableFuture<V> owner = this.running.putIfAbsent(key, mine);
      if (owner != null) {
         this.joined.increment();
         try {
            return owner.join();
         } catch (CompletionException failure) {
            throw propagate(failure.getCause());
         }
      }
      MAKING.set(true);
      try {
         V value = make.get();
         mine.complete(value);
         return value;
      } catch (Throwable failure) {
         mine.completeExceptionally(failure);
         throw propagate(failure);
      } finally {
         this.running.remove(key, mine);
         MAKING.remove();
      }
   }

   private static RuntimeException propagate(Throwable failure) {
      if (failure instanceof Error error) throw error;
      return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
   }

   public long joined() { return this.joined.sum(); }
   public int running() { return this.running.size(); }
}
