package com.wgaccel.stats;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * The counters behind the report: for every stage the total time, the number of calls and the slowest call. Written
 * from the worldgen threads without locks; read by {@link Reporter} as snapshots.
 */
public final class Stats {
   private static final int COUNT = Stage.values().length;
   private static final LongAdder[] NANOS = adders();
   private static final LongAdder[] CALLS = adders();
   private static final LongAccumulator[] WINDOW_MAX = maxes();
   private static final LongAccumulator[] RUN_MAX = maxes();
   /** Noise cell layers filled per chunk, and the layers its highest block (water included) needed, both summed. */
   private static final LongAdder LAYERS_FILLED = new LongAdder();
   private static final LongAdder LAYERS_NEEDED = new LongAdder();
   /** FreeTerraForged tiles made in this run, by position. */
   private static final Set<Long> TILES = ConcurrentHashMap.newKeySet();
   /** Entities that joined the world with a newly generated chunk. */
   private static final LongAdder ENTITIES_LOADED = new LongAdder();
   /** When the first and the last new chunk of the current run finished its noise (System.nanoTime, 0 = none yet). */
   private static final AtomicLong FIRST_CHUNK = new AtomicLong();
   private static final AtomicLong LAST_CHUNK = new AtomicLong();

   private Stats() {
   }

   public static void record(Stage stage, long nanos) {
      int i = stage.ordinal();
      NANOS[i].add(nanos);
      CALLS[i].increment();
      WINDOW_MAX[i].accumulate(nanos);
      RUN_MAX[i].accumulate(nanos);
      if (stage == Stage.NOISE) {
         long now = System.nanoTime();
         FIRST_CHUNK.compareAndSet(0L, now);
         LAST_CHUNK.set(now);
      }
   }

   /**
    * Runs a chunk step and records its time: at once when the step finished on this thread, else when the result
    * arrives. The step's own future is returned untouched.
    */
   public static <T> CompletableFuture<T> timeFuture(Stage stage, Supplier<CompletableFuture<T>> step) {
      long start = System.nanoTime();
      CompletableFuture<T> future = step.get();
      if (future.isDone()) {
         record(stage, System.nanoTime() - start);
      } else {
         future.whenComplete((result, failure) -> record(stage, System.nanoTime() - start));
      }

      return future;
   }

   public static void noiseLayers(int filled, int needed) {
      LAYERS_FILLED.add(filled);
      LAYERS_NEEDED.add(needed);
   }

   /** Records a FreeTerraForged tile being made; the set tells how many different tiles the run needed. */
   public static void tileMade(int tileX, int tileZ) {
      TILES.add((long)tileX << 32 | tileZ & 0xFFFFFFFFL);
   }

   /** Different tiles made since the run started. */
   public static int distinctTiles() {
      return TILES.size();
   }

   public static void entitiesLoaded(int count) {
      ENTITIES_LOADED.add(count);
   }

   public static Snapshot snapshot() {
      long[] nanos = new long[COUNT];
      long[] calls = new long[COUNT];
      for (int i = 0; i < COUNT; i++) {
         nanos[i] = NANOS[i].sum();
         calls[i] = CALLS[i].sum();
      }

      return new Snapshot(nanos, calls, LAYERS_FILLED.sum(), LAYERS_NEEDED.sum(), ENTITIES_LOADED.sum());
   }

   /** The slowest call of the stage since the last time this was asked (one report window). */
   public static long takeWindowMax(Stage stage) {
      return WINDOW_MAX[stage.ordinal()].getThenReset();
   }

   /** The slowest call of the stage since the run started. */
   public static long runMax(Stage stage) {
      return RUN_MAX[stage.ordinal()].get();
   }

   /** The time from the first to the last new chunk of the run, in nanoseconds (0 with fewer than two). */
   public static long chunkSpan() {
      long first = FIRST_CHUNK.get();
      long last = LAST_CHUNK.get();
      return first == 0L ? 0L : last - first;
   }

   /** Starts a new run: the slowest calls and the first/last chunk times are forgotten (totals are kept as a base). */
   public static void resetRun() {
      for (int i = 0; i < COUNT; i++) {
         RUN_MAX[i].reset();
         WINDOW_MAX[i].reset();
      }

      FIRST_CHUNK.set(0L);
      LAST_CHUNK.set(0L);
      TILES.clear();
   }

   private static LongAdder[] adders() {
      LongAdder[] adders = new LongAdder[COUNT];
      for (int i = 0; i < COUNT; i++) {
         adders[i] = new LongAdder();
      }

      return adders;
   }

   private static LongAccumulator[] maxes() {
      LongAccumulator[] maxes = new LongAccumulator[COUNT];
      for (int i = 0; i < COUNT; i++) {
         maxes[i] = new LongAccumulator(Math::max, 0L);
      }

      return maxes;
   }

   /** Totals at one moment; {@link #minus} gives what happened between two of them. */
   public record Snapshot(long[] nanos, long[] calls, long layersFilled, long layersNeeded, long entities) {
      public Snapshot minus(Snapshot base) {
         long[] n = new long[COUNT];
         long[] c = new long[COUNT];
         for (int i = 0; i < COUNT; i++) {
            n[i] = this.nanos[i] - base.nanos[i];
            c[i] = this.calls[i] - base.calls[i];
         }

         return new Snapshot(n, c, this.layersFilled - base.layersFilled, this.layersNeeded - base.layersNeeded, this.entities - base.entities);
      }

      public long nanos(Stage stage) {
         return this.nanos[stage.ordinal()];
      }

      public long calls(Stage stage) {
         return this.calls[stage.ordinal()];
      }

      /** The stage's average call in milliseconds (0 without calls). */
      public double avgMillis(Stage stage) {
         long calls = this.calls(stage);
         return calls == 0L ? 0.0 : this.nanos(stage) / 1.0e6 / calls;
      }

      public boolean idle() {
         for (long c : this.calls) {
            if (c != 0L) {
               return false;
            }
         }

         return true;
      }
   }
}
