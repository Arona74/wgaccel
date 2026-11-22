package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * {@code /wgaccel lod voxy [radius] [threads] [stride] [from]}: Voxy's LODs of the square of chunks around the caller
 * (from: chunks nearer than that left out, a ring) made from the GPU columns by a {@link VoxyWriter}, nearest first.
 * Chunks the server has loaded are left out (handed to Voxy's own ingest instead); chunks generated before but not
 * loaded are written over. The automatic generator is {@link VoxyAuto}.
 *
 * <p>Also Voxy's spawn re-ingest ({@link #onPlayerLoggedIn}).
 */
public final class VoxyLodGenerator {
   private static final AtomicReference<VoxyLodGenerator> RUNNING = new AtomicReference<>();

   private final ServerLevel level;
   private final VoxyWriter writer;
   private final int stride;
   private final int centerX;
   private final int centerZ;
   private final int radius;
   /** Chunks closer than this to the centre (in x or z) are left out: a ring. */
   private final int inner;
   private final List<long[]> tiles;
   private final AtomicInteger nextTile = new AtomicInteger();
   private final int totalChunks;
   private final LongAdder skipped = new LongAdder();
   private volatile boolean stopped;

   private VoxyLodGenerator(ServerLevel level, VoxyAccess.Target target, int centerX, int centerZ, int radius, int inner, int stride) {
      this.level = level;
      this.writer = new VoxyWriter(level, target, "Voxy LOD generator");
      this.stride = stride;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = radius;
      this.inner = inner;
      int unit = VoxyWriter.unit(stride);
      // Tiles covering the square (without the inner square), nearest the centre first.
      List<long[]> tiles = new ArrayList<>();
      long total = 0;
      for (int tx = Math.floorDiv(centerX - radius, unit); tx <= Math.floorDiv(centerX + radius, unit); tx++) {
         for (int tz = Math.floorDiv(centerZ - radius, unit); tz <= Math.floorDiv(centerZ + radius, unit); tz++) {
            int wanted = 0;
            for (int x = tx * unit; x < (tx + 1) * unit; x++) {
               for (int z = tz * unit; z < (tz + 1) * unit; z++) {
                  wanted += this.wanted(x, z) ? 1 : 0;
               }
            }

            if (wanted > 0) {
               long dx = (long)tx * unit + unit / 2 - centerX;
               long dz = (long)tz * unit + unit / 2 - centerZ;
               tiles.add(new long[]{tx, tz, dx * dx + dz * dz});
               total += wanted;
            }
         }
      }

      tiles.sort((a, b) -> Long.compare(a[2], b[2]));
      this.tiles = tiles;
      this.totalChunks = (int)total;
   }

   /** Whether the chunk is in the square and outside the inner one. */
   private boolean wanted(int x, int z) {
      int d = Math.max(Math.abs(x - this.centerX), Math.abs(z - this.centerZ));
      return d <= this.radius && d >= this.inner;
   }

   /** {@code /wgaccel lod voxy}: starts filling the square around the chunk; false (reported) when it cannot. */
   public static boolean start(ServerLevel level, int chunkX, int chunkZ, int radius, int inner, int threads, int stride, Consumer<String> report) {
      VoxyAccess access = VoxyAccess.get();
      if (access == null) {
         report.accept("WGA Voxy LOD generator: Voxy's section insert is not there (" + VoxyAccess.failure() + ")");
         return false;
      }

      if (RUNNING.get() != null) {
         report.accept("WGA Voxy LOD generator: one is running already (/wgaccel lod voxy stop)");
         return false;
      }

      VoxyAccess.Target target = access.target(level);
      if (target == null) {
         report.accept("WGA Voxy LOD generator: Voxy has no world for this level (is Voxy running?)");
         return false;
      }

      VoxyLodGenerator generator = new VoxyLodGenerator(level, target, chunkX, chunkZ, radius, inner, stride);
      if (!RUNNING.compareAndSet(null, generator)) {
         target.release();
         report.accept("WGA Voxy LOD generator: one is running already");
         return false;
      }

      // The loaded chunks (left out below) from the real chunks, in case Voxy missed them.
      int[] ingested = ingestLoaded(level, chunkX, chunkZ, radius);
      report.accept(String.format(Locale.ROOT, "WGA Voxy LOD generator: %,d loaded chunks handed to Voxy's own ingest (%,d not taken)", ingested[0], ingested[1]));
      report.accept(String.format(Locale.ROOT, "WGA Voxy LOD generator: %,d chunks around chunk %d, %d (radius %d%s), %d threads, a column every %d blocks%s...",
         generator.totalChunks, chunkX, chunkZ, radius, inner > 0 ? ", from " + inner : "", threads, stride, stride > 16 ? " (heights between them interpolated)" : ""));
      Thread thread = new Thread(() -> generator.run(threads, report), "WGA Voxy LOD");
      thread.setDaemon(true);
      thread.start();
      return true;
   }

   /**
    * The chunks the server has loaded in the square, handed to Voxy's own ingest again (on the server thread). Voxy
    * ingests a chunk when the client shows it, and gives up when its light is not there yet: on a world's first load the
    * spawn area's chunks come before their light, so Voxy misses them until they leave the view (or /voxy import).
    *
    * @return {taken, not taken}
    */
   public static int[] ingestLoaded(ServerLevel level, int centerX, int centerZ, int radius) {
      VoxyAccess access = VoxyAccess.get();
      int[] counts = new int[2];
      if (access == null) {
         return counts;
      }

      for (int x = centerX - radius; x <= centerX + radius; x++) {
         for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk != null) {
               boolean taken = access.ingest(chunk);
               counts[taken ? 0 : 1]++;
               if (taken) {
                  // Voxy has it now: the generator leaves it alone.
                  VoxyAuto.sent(level, chunk.getPos());
               }
            }
         }
      }

      return counts;
   }

   /**
    * When a player joins with Voxy installed: the loaded chunks around them handed to Voxy again 10 s and 30 s later (the
    * spawn area Voxy misses on a world's first load, see {@link #ingestLoaded}).
    */
   public static void onPlayerLoggedIn(net.minecraft.server.level.ServerPlayer player) {
      if (!com.wgaccel.platform.Platform.get().isModLoaded("voxy") || VoxyAccess.get() == null) {
         return;
      }

      Thread thread = new Thread(() -> {
         for (long delay : new long[]{10_000L, 20_000L}) {
            try {
               Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
               return;
            }

            net.minecraft.server.MinecraftServer server = ((ServerLevel)player.level()).getServer();
            if (!server.isRunning() || player.hasDisconnected()) {
               return;
            }

            server.execute(() -> {
               ServerLevel level = (ServerLevel)player.level();
               int radius = server.getPlayerList().getViewDistance() + 1;
               int[] counts = ingestLoaded(level, Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4, radius);
               WorldgenAccelerator.LOG.info(com.wgaccel.LevelLogger.ALWAYS,
                  "WGA Voxy: {} loaded chunks around {} handed to Voxy's ingest again ({} not taken: no light yet, or Voxy's ingest off)",
                  counts[0], player.getName().getString(), counts[1]);
            });
         }
      }, "WGA Voxy ingest");
      thread.setDaemon(true);
      thread.start();
   }

   /** Whether a {@code /wgaccel lod voxy} run is going (its progress is the one shown then). */
   public static boolean running() {
      return RUNNING.get() != null;
   }

   /** {@code /wgaccel lod voxy stop}. */
   public static boolean stop(Consumer<String> report) {
      VoxyLodGenerator generator = RUNNING.get();
      if (generator == null) {
         report.accept("WGA Voxy LOD generator: none is running");
         return false;
      }

      generator.stopped = true;
      report.accept("WGA Voxy LOD generator: stopping");
      return true;
   }

   private boolean stopping() {
      return this.stopped || !this.level.getServer().isRunning();
   }

   private void run(int threads, Consumer<String> report) {
      long start = System.nanoTime();
      List<Thread> workers = new ArrayList<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      try {
         for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
               try {
                  this.work();
               } catch (Throwable failure) {
                  if (!(failure instanceof java.util.concurrent.CancellationException)) {
                     failed.compareAndSet(null, failure);
                     this.stopped = true;
                  }
               }
            }, "WGA Voxy LOD " + t);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
         }

         long nextReport = System.nanoTime() + 10_000_000_000L;
         long lastTime = System.nanoTime();
         long lastCovered = 0;
         double rate = 0.0;
         for (Thread worker : workers) {
            while (worker.isAlive()) {
               worker.join(500L);
               long now = System.nanoTime();
               long covered = this.writer.chunks.sum() + this.skipped.sum();
               if (now - lastTime > 400_000_000L) {
                  double instant = (covered - lastCovered) / ((now - lastTime) / 1.0e9);
                  rate = rate == 0.0 ? instant : rate * 0.8 + instant * 0.2;
                  lastTime = now;
                  lastCovered = covered;
               }

               this.publish(VoxyProgress.State.RUNNING, rate, threads);
               if (now > nextReport) {
                  nextReport += 10_000_000_000L;
                  report.accept(this.line("so far", start));
               }
            }
         }
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         this.stopped = true;
      } finally {
         this.writer.release();
         this.publish(VoxyProgress.State.DONE, 0.0, threads);
         RUNNING.compareAndSet(this, null);
      }

      if (failed.get() != null) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator failed", failed.get());
         report.accept("WGA Voxy LOD generator failed: " + failed.get());
      }

      report.accept(this.line(this.stopped ? "stopped" : "done", start));
   }

   private void publish(VoxyProgress.State state, double rate, int threads) {
      long covered = Math.min(this.totalChunks, this.writer.chunks.sum() + this.skipped.sum());
      VoxyProgress.publish(new VoxyProgress("command", state, "", List.of(new VoxyProgress.Ring(this.radius, this.stride, covered, this.totalChunks, rate)), rate,
         threads, System.nanoTime()));
   }

   private void work() {
      LodBuilder.Worker worker = this.writer.worker(this::stopping);
      VoxyWriter.ChunkTest test = (x, z) -> {
         if (!this.wanted(x, z)) {
            return false;
         }

         if (this.level.getChunkSource().hasChunk(x, z)) {
            this.skipped.increment();
            return false;
         }

         return true;
      };
      while (!this.stopping()) {
         int index = this.nextTile.getAndIncrement();
         if (index >= this.tiles.size()) {
            return;
         }

         long[] tile = this.tiles.get(index);
         this.writer.tile(worker, (int)tile[0], (int)tile[1], this.stride, test);
      }
   }

   private String line(String what, long start) {
      double seconds = (System.nanoTime() - start) / 1.0e9;
      VoxyWriter w = this.writer;
      LodBuilder b = w.builder();
      long done = Math.max(1, w.chunks.sum());
      return String.format(Locale.ROOT,
         "WGA Voxy LOD generator %s: %,d of %,d chunks (%,d loaded left out) in %.1f s = %,.0f chunks/s; %,d sections; per chunk on its thread: columns %.2f ms (fill inputs %.2f, fill GPU %.2f, surface inputs %.2f, surface GPU %.2f), ids %.2f ms, Voxy insert %.2f ms; %,d columns from the CPU",
         what, w.chunks.sum(), this.totalChunks, this.skipped.sum(), seconds, w.chunks.sum() / Math.max(seconds, 1.0e-3), w.sections.sum(),
         w.columnNanos.sum() / 1.0e6 / done, b.fillInputNanos.sum() / 1.0e6 / done, b.fillNanos.sum() / 1.0e6 / done,
         b.surfaceInputNanos.sum() / 1.0e6 / done, b.surfaceGpuNanos.sum() / 1.0e6 / done, w.writeNanos.sum() / 1.0e6 / done,
         w.insertNanos.sum() / 1.0e6 / done, b.cpuColumns.sum());
   }
}
