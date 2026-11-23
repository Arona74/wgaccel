package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.LevelLogger;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Voxy Server Side (LOD Server Support, MIT) fed with our LODs on a server (config voxyForVss): VSS answers a player's
 * request for a distant chunk from the loaded chunk, the world's files or its own LOD store, and when the chunk was never
 * generated it generates it, the slow part. Those generations come here instead ({@code VssGenerationMixin}): the chunk's
 * tile ({@link VoxyWriter}, voxyVssRings' stride for its distance from the player) made from the GPU columns, each of its
 * chunks a column in VSS's format ({@link VssColumns}), the one asked for handed back to VSS as its generation's result
 * (VSS then sends it and keeps it in its store as it does a generated chunk's), the tile's others kept a while for the
 * requests that follow. A chunk generated later (a player going there) replaces its LOD: VSS sends a saved chunk again.
 *
 * <p>Threads: {@link #submit} and {@link #drain} on the server thread (VSS's generation service runs there), the tiles on
 * our workers.
 */
public final class VssLods {
   /** A finished request: the column's bytes (null: all air) or failed (VSS's request is dropped; the client asks again). */
   public record Result(Object ticket, int x, int z, String dimension, byte[] bytes, long stamp, boolean failed) {
   }

   private record Request(Object ticket, int x, int z, long since) {
   }

   private static final class Job implements Comparable<Job> {
      final long key;
      final int stride;
      final int tx;
      final int tz;
      final long distance;
      final long order;
      final List<Request> waiting = new ArrayList<>();

      Job(long key, int stride, int tx, int tz, long distance, long order) {
         this.key = key;
         this.stride = stride;
         this.tx = tx;
         this.tz = tz;
         this.distance = distance;
         this.order = order;
      }

      @Override
      public int compareTo(Job other) {
         int byDistance = Long.compare(this.distance, other.distance);
         return byDistance != 0 ? byDistance : Long.compare(this.order, other.order);
      }
   }

   /** The bytes a column takes in the cache besides its own. */
   private static final int ENTRY_BYTES = 64;
   private static final long CACHE_BYTES = 256L << 20;
   /** A request not answered by then is given back to VSS as failed (its own generation times out at 60 s). */
   private static final long REQUEST_NANOS = 45_000_000_000L;
   private static final byte[] AIR = new byte[0];
   /** A rough column (LodBuilder.Column#rough: the CPU's heights over plain stone): never sent, left to VSS. */
   private static final byte[] ROUGH = new byte[0];
   /** Rough chunks remembered (VSS generates them the next time it asks), at most this many. */
   private static final int MAX_ROUGH = 65536;
   private static volatile VssColumns.Translator translator;
   private static volatile VssLods instance;
   private static volatile boolean said;
   /** voxyVssRings' text and its rings, parsed once. */
   private static volatile Object[] ringsParsed;

   private final MinecraftServer server;
   private final ServerLevel level;
   private final String dimension;
   private final VssColumns columns;
   private final VoxyWriter writer;
   private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
   /** The tiles queued or being made, by key; guarded by this. */
   private final Map<Long, Job> jobs = new HashMap<>();
   /** Columns made and not asked for yet (AIR: all air), eldest first; guarded by this. */
   private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(4096, 0.75F, true);
   private long cacheBytes;
   private final ConcurrentLinkedQueue<Result> ready = new ConcurrentLinkedQueue<>();
   /** Chunks whose column came out rough: VSS generates them itself; guarded by this. */
   private final java.util.Set<Long> leftRough = new java.util.HashSet<>();
   private final List<Thread> workers = new ArrayList<>();
   private final AtomicInteger workersReady = new AtomicInteger();
   private final AtomicLong order = new AtomicLong();
   private volatile boolean stopped;
   private volatile boolean unavailable;
   /** VSS's translation failed: off for the session. */
   private volatile boolean broken;
   private volatile double recentMspt;
   private final LongAdder asked = new LongAdder();
   private final LongAdder answered = new LongAdder();
   private final LongAdder fromCache = new LongAdder();
   private final LongAdder failed = new LongAdder();
   private final LongAdder tiles = new LongAdder();
   private final LongAdder real = new LongAdder();
   private final LongAdder rough = new LongAdder();

   private VssLods(MinecraftServer server, ServerLevel level) {
      this.server = server;
      this.level = level;
      this.dimension = Mc.id(level.dimension());
      this.columns = new VssColumns(level, VssLods::translate);
      this.writer = new VoxyWriter(level, this.columns, "VSS LODs");
   }

   /** Set by the mixin on VSS's generation service, before its first request: VSS's column translation. */
   public static void translator(VssColumns.Translator value) {
      translator = value;
   }

   /** VSS's translation of a column's native bytes (a failure turns us off: VSS generates its chunks itself again). */
   private static byte[] translate(byte[] body, ServerLevel level) {
      VssColumns.Translator found = translator;
      try {
         if (found == null) {
            throw new IllegalStateException("not found");
         }

         return found.translate(body, level);
      } catch (Throwable failure) {
         VssLods lods = instance;
         if (lods != null && !lods.broken) {
            lods.broken = true;
            WorldgenAccelerator.LOG.warn("WGA LODs for Voxy Server Side: VSS's column translation failed (another VSS version?); off, VSS"
               + " generates its chunks itself", failure);
         }

         throw new IllegalStateException("VSS's column translation", failure);
      }
   }

   /** From the server's start: our workers, when VSS is installed (they wait for the GPU fill). */
   public static void start(MinecraftServer server) {
      if (!com.wgaccel.platform.Platform.get().isModLoaded("lss")) {
         return;
      }

      VssLods lods = new VssLods(server, server.overworld());
      instance = lods;
      Thread scheduler = new Thread(lods::schedule, "WGA VSS LODs");
      scheduler.setDaemon(true);
      scheduler.start();
   }

   /** From the server's stop. */
   public static void stop() {
      VssLods lods = instance;
      instance = null;
      if (lods == null) {
         return;
      }

      lods.stopped = true;
      lods.writer.close();
      for (Thread worker : lods.workers) {
         try {
            worker.join(5_000L);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      lods.writer.release();
   }

   /**
    * VSS asks for chunk (x, z) to be generated for a player (server thread): true when we make it (its result comes from
    * {@link #drain}), false to leave it to VSS.
    */
   public static boolean submit(ServerLevel level, UUID player, Object ticket, int x, int z) {
      VssLods lods = instance;
      if (lods == null || level != lods.level || !Config.VOXY_FOR_VSS.get() || lods.workersReady.get() == 0 || lods.unavailable
         || lods.broken || translator == null) {
         return false;
      }

      return lods.request(player, ticket, x, z);
   }

   /** The requests finished since the last call (server thread). */
   public static List<Result> drain() {
      VssLods lods = instance;
      if (lods == null || lods.ready.isEmpty() && lods.jobs.isEmpty()) {
         return List.of();
      }

      lods.expire();
      List<Result> done = new ArrayList<>();
      Result result;
      while ((result = lods.ready.poll()) != null) {
         done.add(result);
      }

      return done;
   }

   private boolean request(UUID uuid, Object ticket, int x, int z) {
      long distance = 0;
      ServerPlayer player = this.server.getPlayerList().getPlayer(uuid);
      if (player != null && player.level() == this.level) {
         int px = Mth.floor(player.getX()) >> 4;
         int pz = Mth.floor(player.getZ()) >> 4;
         distance = Math.max(Math.abs(x - px), Math.abs(z - pz));
      }

      int realRadius = Config.VOXY_VSS_REAL_RADIUS.get();
      if (realRadius > 0 && distance < realRadius) {
         this.real.increment();
         return false;
      }

      long key = Mc.key(x, z);
      long now = System.nanoTime();
      synchronized (this) {
         if (this.leftRough.remove(key)) {
            // Its column came out rough: VSS generates the real chunk.
            return false;
         }

         this.asked.increment();
         byte[] cached = this.cache.remove(key);
         if (cached != null) {
            this.cacheBytes -= cached.length + ENTRY_BYTES;
            this.fromCache.increment();
            this.ready.add(this.result(ticket, x, z, cached));
            return true;
         }

         int stride = stride(distance);
         int unit = VoxyWriter.unit(stride);
         int tx = Math.floorDiv(x, unit);
         int tz = Math.floorDiv(z, unit);
         long jobKey = (long)Integer.numberOfTrailingZeros(stride) << 56 | ((long)tx & 0xFFFFFFFL) << 28 | (long)tz & 0xFFFFFFFL;
         Job job = this.jobs.get(jobKey);
         if (job == null) {
            job = new Job(jobKey, stride, tx, tz, distance, this.order.getAndIncrement());
            this.jobs.put(jobKey, job);
            this.queue.add(job);
         }

         job.waiting.add(new Request(ticket, x, z, now));
      }

      return true;
   }

   /** voxyVssRings' stride for a chunk this far from the player (past the last ring: the last ring's). */
   private static int stride(long distance) {
      String text = String.join(",", Config.VOXY_VSS_RINGS.get());
      Object[] parsed = ringsParsed;
      if (parsed == null || !parsed[0].equals(text)) {
         parsed = new Object[]{text, VoxyAuto.rings(text)};
         ringsParsed = parsed;
      }

      @SuppressWarnings("unchecked")
      List<int[]> rings = (List<int[]>)parsed[1];
      if (rings.isEmpty()) {
         return 1;
      }

      for (int[] ring : rings) {
         if (distance < ring[0]) {
            return ring[1];
         }
      }

      return rings.get(rings.size() - 1)[1];
   }

   private Result result(Object ticket, int x, int z, byte[] bytes) {
      this.answered.increment();
      return new Result(ticket, x, z, this.dimension, bytes == AIR ? null : bytes, System.currentTimeMillis() / 1000L, false);
   }

   /** Requests waiting too long given back as failed (VSS's client asks again later). */
   private synchronized void expire() {
      long now = System.nanoTime();
      for (Job job : this.jobs.values()) {
         job.waiting.removeIf(request -> {
            if (now - request.since() > REQUEST_NANOS) {
               this.failed.increment();
               this.ready.add(new Result(request.ticket(), request.x(), request.z(), this.dimension, null, 0L, true));
               return true;
            }

            return false;
         });
      }
   }

   private boolean stopping() {
      return this.stopped || !this.server.isRunning();
   }

   // ---- the scheduler: the workers and a line in the log a minute ----

   private void schedule() {
      long nextLog = System.nanoTime() + 60_000_000_000L;
      long lastAnswered = 0;
      long lastTime = System.nanoTime();
      while (!this.stopping()) {
         try {
            Thread.sleep(500L);
         } catch (InterruptedException interrupted) {
            return;
         }

         this.recentMspt = VoxyAuto.recentMspt(this.server);
         Config.VoxyPerformance chosen = Config.VOXY_PERFORMANCE.get();
         Config.VoxyPerformance performance = chosen == Config.VoxyPerformance.DYNAMIC ? Config.VoxyPerformance.HIGH : chosen;
         int configured = Config.VOXY_THREADS.get();
         int wanted = configured > 0 ? configured : performance.threads(Runtime.getRuntime().availableProcessors());
         while (this.workers.size() < wanted) {
            int index = this.workers.size();
            Thread worker = new Thread(() -> this.work(index), "WGA VSS LODs " + index);
            worker.setDaemon(true);
            worker.setPriority(performance.priority);
            worker.start();
            this.workers.add(worker);
         }

         long now = System.nanoTime();
         if (now > nextLog) {
            nextLog = now + 60_000_000_000L;
            long answeredNow = this.answered.sum();
            if (answeredNow != lastAnswered || !this.jobs.isEmpty()) {
               double rate = (answeredNow - lastAnswered) / ((now - lastTime) / 1.0e9);
               WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, this.line(rate));
            }

            lastAnswered = answeredNow;
            lastTime = now;
         }
      }
   }

   /** The state in one line (the log, /wgaccel status). */
   private String line(double rate) {
      int queued;
      int waiting = 0;
      synchronized (this) {
         queued = this.jobs.size();
         for (Job job : this.jobs.values()) {
            waiting += job.waiting.size();
         }
      }

      return String.format(Locale.ROOT,
         "WGA LODs for Voxy Server Side: %,d columns given (%.0f/s), %,d of them made before they were asked for; %,d tiles made, %d queued"
            + " (%d requests waiting); %,d failed, %,d left to VSS (voxyVssRealRadius), %,d rough (left to VSS: no GPU stand-in);"
            + " cache %,d columns (%d MB); %d threads",
         this.answered.sum(), rate, this.fromCache.sum(), this.tiles.sum(), queued, waiting, this.failed.sum(), this.real.sum(),
         this.rough.sum(),
         this.cache.size(), this.cacheBytes >> 20, this.workers.size());
   }

   private void work(int slot) {
      LodBuilder.Worker worker = null;
      Map<Long, byte[]> made = new HashMap<>();
      this.columns.out.set((key, bytes, isRough) -> made.put(key, isRough ? ROUGH : bytes == null ? AIR : bytes));
      while (!this.stopping()) {
         try {
            if (worker == null) {
               worker = this.writer.worker(this::stopping);
               if (worker == null) {
                  if (!this.unavailable && this.workersReady.get() == 0) {
                     this.unavailable = true;
                     WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS,
                        "WGA LODs for Voxy Server Side: off in this world ({}); VSS generates its chunks itself", GpuNoiseFill.StandIns.unavailable());
                  }

                  Thread.sleep(5_000L);
                  continue;
               }

               if (this.workersReady.getAndIncrement() == 0) {
                  this.unavailable = false;
                  if (!said) {
                     said = true;
                     WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS,
                        "WGA LODs for Voxy Server Side: on; the chunks VSS would generate for its players' LODs are made from the GPU columns"
                           + " (rings {}{})", String.join(", ", Config.VOXY_VSS_RINGS.get()),
                        Config.VOXY_VSS_REAL_RADIUS.get() > 0 ? ", real chunks within " + Config.VOXY_VSS_REAL_RADIUS.get() + " chunks" : "");
                  }
               }
            }

            int limit = Config.VOXY_PAUSE_ABOVE_MSPT.get();
            double mspt = Math.max(this.server.getAverageTickTimeNanos() / 1.0e6, this.recentMspt);
            if (limit > 0 && mspt > limit) {
               Thread.sleep(250L);
               continue;
            }

            Job job = this.queue.poll(500L, TimeUnit.MILLISECONDS);
            if (job == null) {
               continue;
            }

            synchronized (this) {
               if (job.waiting.isEmpty()) {
                  // Every request expired (or none came): nothing to make.
                  this.jobs.remove(job.key);
                  continue;
               }
            }

            made.clear();
            try {
               this.writer.tile(worker, job.tx, job.tz, job.stride, false, (x, z) -> true);
            } finally {
               this.finish(job, made);
            }

            this.tiles.increment();
         } catch (InterruptedException interrupted) {
            return;
         } catch (java.util.concurrent.CancellationException closing) {
            return;
         } catch (RuntimeException failure) {
            WorldgenAccelerator.LOG.warn("WGA LODs for Voxy Server Side: a tile failed", failure);
            try {
               Thread.sleep(1_000L);
            } catch (InterruptedException interrupted) {
               return;
            }
         }
      }
   }

   /** The tile's requests answered (failed when its column is missing), its other columns kept. */
   private synchronized void finish(Job job, Map<Long, byte[]> made) {
      this.jobs.remove(job.key);
      for (Request request : job.waiting) {
         long key = Mc.key(request.x(), request.z());
         byte[] bytes = made.remove(key);
         if (bytes == ROUGH) {
            // Given back (transient: the client asks again), and then left to VSS's own generation.
            this.rough.increment();
            if (this.leftRough.size() < MAX_ROUGH) {
               this.leftRough.add(key);
            }

            this.ready.add(new Result(request.ticket(), request.x(), request.z(), this.dimension, null, 0L, true));
         } else if (bytes != null) {
            this.ready.add(this.result(request.ticket(), request.x(), request.z(), bytes));
         } else {
            this.failed.increment();
            this.ready.add(new Result(request.ticket(), request.x(), request.z(), this.dimension, null, 0L, true));
         }
      }

      job.waiting.clear();
      for (Map.Entry<Long, byte[]> entry : made.entrySet()) {
         if (entry.getValue() == ROUGH) {
            this.rough.increment();
            if (this.leftRough.size() < MAX_ROUGH) {
               this.leftRough.add(entry.getKey());
            }

            continue;
         }

         byte[] old = this.cache.put(entry.getKey(), entry.getValue());
         if (old != null) {
            this.cacheBytes -= old.length + ENTRY_BYTES;
         }

         this.cacheBytes += entry.getValue().length + ENTRY_BYTES;
      }

      var eldest = this.cache.entrySet().iterator();
      while (this.cacheBytes > CACHE_BYTES && eldest.hasNext()) {
         byte[] gone = eldest.next().getValue();
         this.cacheBytes -= gone.length + ENTRY_BYTES;
         eldest.remove();
      }
   }
}
