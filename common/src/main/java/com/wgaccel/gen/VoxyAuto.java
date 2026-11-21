package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.LevelLogger;
import com.wgaccel.WorldgenAccelerator;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The automatic Voxy LOD generator (config voxyGenerator, section [voxy]): Voxy's distant terrain made by a {@link
 * VoxyWriter} in rings around the overworld's players (voxyRings: "radius:stride", nearest first), each ring's tiles
 * nearest the players first, on voxyThreads threads.
 *
 * <ul>
 *   <li>A scheduler thread plans the tiles from the players' chunks (again when one moves by a tile, or the rings change),
 *   publishes the progress ({@link VoxyProgress}) twice a second and logs it every minute.
 *   <li>The workers wait while the server's average tick is over voxyPauseAboveMspt, while Voxy has no world yet (the
 *   game still joining) and while the GPU fill is not ready.
 *   <li>Chunks already generated (saved finished in the world's region files, or loaded) are never written: Voxy has them
 *   from the real chunks. Chunks saved at an earlier step (the neighbours started around finished chunks) are written. A far ring's tile leaves out the chunks of near tiles already done (full detail is not written over).
 *   <li>The tiles done are kept in the world's data/wgaccel_voxy_lod.dat, so a restart does not make them again
 *   ({@code /wgaccel lod voxy reset} forgets them, after Voxy's own LODs were deleted).
 *   <li>voxyPerformance sets the threads, their priority and how many write into Voxy at once (changes apply at once).
 *   The workers also wait while the last 20 ticks were slow.
 *   <li>A watchdog: a write into Voxy that has not come back after 30 s stops the generator for the session (Voxy 0.2.16's
 *   section race leaves its threads waiting for ever; see {@link VoxyWriter}).
 *   <li>voxyWithVwg, with Voxy World Gen V2 on ({@link VwgControl}): AFTER holds it until our rings within its
 *   generationRadius are done, then lets it run at voxyVwgTasks tasks; AVOID leaves its circle (dx^2 + dz^2 <= r^2 in
 *   chunks, as its DistanceGraph counts) to it.
 *   <li>voxyRealChunks: once the rings around a player are done, a Chunky task generates the real chunks near them, and
 *   each one is handed to Voxy's ingest as it is saved (Voxy only takes the chunks the client is shown).
 * </ul>
 */
public final class VoxyAuto {
   private static final String FILE = "wgaccel_voxy_lod.dat";
   private static final int MAGIC = 0x57474156;
   /** Strides on the 8 x 8-chunk grid (every chunk). */
   private static final int[] NEAR_STRIDES = {1, 2, 4, 8, 16};
   private static final long WATCHDOG_NANOS = 30_000_000_000L;
   private static volatile VoxyAuto instance;

   private final MinecraftServer server;
   private final ServerLevel level;
   private final Path regionDir;
   private final Path doneFile;
   private final Set<Long> done = ConcurrentHashMap.newKeySet();
   private final LongAdder covered = new LongAdder();
   private final List<Thread> workers = new ArrayList<>();
   private volatile boolean stopped;
   private volatile Plan plan;
   private volatile VoxyWriter writer;
   private volatile VoxyProgress.State state = VoxyProgress.State.WAITING;
   private volatile String why = "starting";
   private volatile int threads;
   private volatile boolean dirty;
   /** Stopped for this session: a write into Voxy never came back. */
   private volatile boolean broken;
   /** The mean of the last 20 ticks (ms), worked out by the scheduler. */
   private volatile double recentMspt;
   private Config.VoxyPerformance lastPerformance;
   private int lastThreads = -1;
   /** voxyRealChunks: our Chunky task runs; the places done this session (by 8-chunk tile); the progress line. */
   private volatile boolean realActive;
   private boolean realOurs;
   private final Set<Long> realCentres = new java.util.HashSet<>();
   private volatile String realLine = "";
   private boolean chunkyMissingLogged;
   private final LongAdder realIngested = new LongAdder();
   /** voxyWithVwg AVOID: Voxy World Gen V2's radius in chunks left to it (0: none). */
   private volatile int vwgRadius;
   private int vwgLogged = -1;
   private final VwgControl vwg = new VwgControl();
   private volatile String vwgLine = "";
   private Boolean vwgHeld;
   private int vwgTasks = -1;
   /** Each detail's rate (chunks/s, by stride; an average over about 5 s of the time its ring was being made). */
   private final Map<Integer, Double> strideRates = new ConcurrentHashMap<>();
   /** The plan and its rings' counts at the last scheduler step (for the rates). */
   private Plan ratePlan;
   private long[] rateDone;
   private long rateTime;

   /** The tiles to make, in order, and the rings' counts. */
   private record Plan(List<Job> jobs, AtomicInteger next, int[] radii, int[] strides, long[] totals, LongAdder[] done, List<int[]> centres, String rings) {
   }

   /** One tile of a ring: its chunks in the ring counted for the progress. */
   private record Job(int ring, int stride, int tx, int tz, long distance, int chunks) {
   }

   private VoxyAuto(MinecraftServer server) {
      this.server = server;
      this.level = server.overworld();
      Path root = server.getWorldPath(LevelResource.ROOT);
      this.regionDir = root.resolve("region");
      this.doneFile = root.resolve("data").resolve(FILE);
   }

   private final long[] lastTimes = new long[6];

   /** The last minute's time per chunk written (ms, all threads): columns, trees, the rest of the fill, Voxy's insert; sections per chunk. */
   private String times() {
      VoxyWriter writer = this.writer;
      if (writer == null) {
         return "";
      }

      long[] now = {writer.chunks.sum(), writer.sections.sum(), writer.columnNanos.sum(), writer.treeNanos.sum(), writer.writeNanos.sum(), writer.insertNanos.sum()};
      long chunks = now[0] - this.lastTimes[0];
      double[] d = new double[6];
      for (int i = 0; i < 6; i++) {
         d[i] = now[i] - this.lastTimes[i];
      }

      System.arraycopy(now, 0, this.lastTimes, 0, 6);
      if (chunks <= 0) {
         return "";
      }

      return String.format(Locale.ROOT, " | per chunk (ms of thread time): columns %.3f, trees %.3f, fill %.3f, Voxy insert %.3f; %.1f sections",
         d[2] / chunks / 1e6, d[3] / chunks / 1e6, (d[4] - d[3]) / chunks / 1e6, d[5] / chunks / 1e6, d[1] / chunks);
   }

   /** The running generator's writer (null when none yet): {@code /wgaccel lod trees} works out trees with it. */
   public static VoxyWriter writer() {
      VoxyAuto auto = instance;
      return auto == null ? null : auto.writer;
   }

   /** From the server's start: the generator's scheduler (it does nothing while voxyGenerator is off). */
   public static void start(MinecraftServer server) {
      if (!com.wgaccel.platform.Platform.get().isModLoaded("voxy")) {
         return;
      }

      VoxyAuto auto = new VoxyAuto(server);
      auto.load();
      instance = auto;
      Thread scheduler = new Thread(auto::schedule, "WGA Voxy auto");
      scheduler.setDaemon(true);
      scheduler.start();
   }

   /** From the server's stop: the workers stopped (waited for), the tiles done saved, Voxy's world given back. */
   public static void stop() {
      VoxyAuto auto = instance;
      instance = null;
      if (auto == null) {
         return;
      }

      auto.stopped = true;
      auto.vwg.restore();
      VoxyWriter writer = auto.writer;
      if (writer != null) {
         // Workers waiting for their turn to write leave at once.
         writer.close();
      }

      // At most 10 s for all of them: a worker inside a Voxy write that never returns (its 0.2.16 race: "Section freed
      // while marked as dirty" leaves a lock of its section tracker held) is a daemon and is left behind.
      long until = System.nanoTime() + 10_000_000_000L;
      for (Thread worker : auto.workers) {
         long left = (until - System.nanoTime()) / 1_000_000L;
         if (left <= 0) {
            break;
         }

         try {
            worker.join(left);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      auto.save();
      if (writer != null) {
         if (writer.longestWriteNanos() > 0) {
            // Giving Voxy's world back would wait on the same held lock: the game could not close.
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: a write into Voxy never returned (Voxy's own error earlier in the log); its world is left to Voxy");
         } else {
            writer.release();
         }
      }

      VoxyProgress.publish(null);
   }

   /** {@code /wgaccel lod voxy reset}: the tiles done forgotten (made again). Returns how many there were, or -1 when not running. */
   public static int reset() {
      VoxyAuto auto = instance;
      if (auto == null) {
         return -1;
      }

      int count = auto.done.size();
      auto.done.clear();
      auto.dirty = true;
      auto.plan = null;
      auto.save();
      return count;
   }

   private boolean stopping() {
      return this.stopped || !this.server.isRunning();
   }

   // ---- the scheduler ----

   private void schedule() {
      long nextLog = System.nanoTime() + 60_000_000_000L;
      long nextSave = System.nanoTime() + 30_000_000_000L;
      long[] times = new long[11];
      long[] counts = new long[11];
      int sample = 0;
      boolean wasOn = false;
      while (!this.stopping()) {
         try {
            Thread.sleep(500L);
         } catch (InterruptedException interrupted) {
            return;
         }

         boolean on = Config.VOXY_GENERATOR.get();
         if (on != wasOn) {
            WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Voxy LOD generator: {}", on ? "on (config voxyGenerator)" : "off");
            wasOn = on;
         }

         if (!on) {
            this.plan = null;
            this.vwg.restore();
            this.vwgLine = "";
            VoxyProgress.publish(null);
            continue;
         }

         try {
            this.tick();
         } catch (RuntimeException failure) {
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: planning failed", failure);
         }

         // The rate over the last 5 s (11 samples, 0.5 s apart).
         long now = System.nanoTime();
         times[sample % times.length] = now;
         counts[sample % counts.length] = this.covered.sum();
         int oldest = sample >= times.length - 1 ? (sample + 1) % times.length : 0;
         sample++;
         double seconds = (now - times[oldest]) / 1.0e9;
         double rate = seconds > 0.4 ? (counts[(sample - 1) % counts.length] - counts[oldest]) / seconds : 0.0;
         this.measureRings(now);
         VoxyProgress progress = this.progress(this.state == VoxyProgress.State.RUNNING ? rate : 0.0);
         if (progress != null && !VoxyLodGenerator.running()) {
            VoxyProgress.publish(progress);
         }

         if (progress != null && now > nextLog) {
            nextLog = now + 60_000_000_000L;
            if (progress.state() != VoxyProgress.State.DONE || this.dirty) {
               WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, progress.line() + this.times());
            }
         }

         if (now > nextSave) {
            nextSave = now + 30_000_000_000L;
            this.save();
         }
      }
   }

   /** One scheduler step: Voxy's world, the workers, the plan. */
   private void tick() {
      // Voxy World Gen V2 held from the first step (no plan yet: not ready), not only once our generator has one.
      this.controlVwg();
      VoxyAccess access = VoxyAccess.get();
      if (access == null) {
         this.waitFor("Voxy's insert not found");
         return;
      }

      if (this.writer == null) {
         VoxyAccess.Target target = access.target(this.level);
         if (target == null) {
            this.waitFor("Voxy not ready");
            return;
         }

         this.writer = new VoxyWriter(this.level, target, "Voxy LOD generator");
      }

      if (this.broken) {
         this.state = VoxyProgress.State.STOPPED;
         this.why = "Voxy stopped answering; restart the game";
         this.vwg.restore();
         this.vwgLine = "";
         return;
      }

      if (this.writer.longestWriteNanos() > WATCHDOG_NANOS) {
         this.broken = true;
         this.state = VoxyProgress.State.STOPPED;
         this.why = "Voxy stopped answering; restart the game";
         WorldgenAccelerator.LOG.warn(LevelLogger.ALWAYS,
            "WGA Voxy LOD generator: a write into Voxy has not come back for 30 s; stopped for this session. Voxy can lose track of a section"
               + " when many threads write while it draws and unloads the same sections (Voxy 0.2.16: \"Section freed while marked as dirty\");"
               + " its threads then wait for that section for ever. If the game hangs, restart it and set voxyPerformance lower (or voxyGenerator off).");
         return;
      }

      this.recentMspt = recentMspt(this.server);
      Config.VoxyPerformance chosen = Config.VOXY_PERFORMANCE.get();
      Config.VoxyPerformance performance = chosen == Config.VoxyPerformance.DYNAMIC ? dynamic(this.plan) : chosen;
      int configured = Config.VOXY_THREADS.get();
      int wanted = configured > 0 ? configured : performance.threads(Runtime.getRuntime().availableProcessors());
      this.threads = wanted;
      this.writer.maxWriters(performance.writers);
      while (this.workers.size() < wanted) {
         int index = this.workers.size();
         Thread worker = new Thread(() -> this.work(index), "WGA Voxy auto " + index);
         worker.setDaemon(true);
         worker.start();
         this.workers.add(worker);
      }

      for (Thread worker : this.workers) {
         worker.setPriority(performance.priority);
      }

      if (performance != this.lastPerformance || wanted != this.lastThreads) {
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS,
            "WGA Voxy LOD generator: {} threads, {} writing into Voxy at once ({} performance), rings {}, {} tiles already done", wanted,
            performance.writers, chosen == Config.VoxyPerformance.DYNAMIC ? "DYNAMIC: " + performance : performance,
            String.join(", ", Config.VOXY_RINGS.get()), this.done.size());
         this.lastPerformance = performance;
         this.lastThreads = wanted;
      }

      List<int[]> centres = new ArrayList<>();
      for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
         if (player.level() == this.level) {
            centres.add(new int[]{Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4});
         }
      }

      if (centres.isEmpty()) {
         this.plan = null;
         this.waitFor("no player in the overworld");
         return;
      }

      this.readVwg();
      String rings = String.join(",", Config.VOXY_RINGS.get()) + "|vwg " + this.vwgRadius;
      Plan plan = this.plan;
      if (plan == null || !plan.rings.equals(rings) || moved(plan.centres, centres)) {
         this.plan = this.plan(centres, rings);
      }

      this.realChunks(centres);
      this.controlVwg();
   }

   /**
    * voxyWithVwg AFTER: Voxy World Gen V2 held while our rings within its radius are not done, then let run at
    * voxyVwgTasks tasks (its runtime settings only); otherwise the player's own settings back.
    */
   private void controlVwg() {
      VwgControl.Own own = this.vwg.own();
      if (own == null || !own.enabled() || Config.VOXY_WITH_VWG.get() != Config.VoxyWithVwg.AFTER) {
         this.vwg.restore();
         this.vwgLine = "";
         this.vwgHeld = null;
         this.vwgTasks = -1;
         return;
      }

      Plan plan = this.plan;
      int after = Config.VOXY_VWG_AFTER_RADIUS.get() > 0 ? Config.VOXY_VWG_AFTER_RADIUS.get() : own.radius();
      boolean ready = plan != null && doneWithin(plan, after);
      int configured = Config.VOXY_VWG_TASKS.get();
      int tasks = configured > 0 ? configured : own.tasks();
      if (Config.VOXY_PERFORMANCE.get() == Config.VoxyPerformance.DYNAMIC) {
         // The other way round from ours: half during our first ring, all during the second, twice after, its own once ours
         // are done.
         int ring = ringBeingMade(plan);
         tasks = ring < 0 ? Math.max(own.tasks(), tasks) : ring == 0 ? Math.max(1, tasks / 2) : ring == 1 ? tasks : Math.min(128, tasks * 2);
      }

      if (!this.vwg.apply(ready, tasks)) {
         this.vwgLine = "";
         return;
      }

      if (this.vwgHeld == null || this.vwgHeld == ready || ready && tasks != this.vwgTasks) {
         this.vwgHeld = !ready;
         this.vwgTasks = tasks;
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, ready
            ? "WGA Voxy LOD generator: our LODs within {} chunks are done; Voxy World Gen V2 runs ({} tasks)"
            : "WGA Voxy LOD generator: Voxy World Gen V2 held until our LODs within {} chunks are done", after, tasks);
      }

      this.vwgLine = ready ? String.format(Locale.ROOT, "Voxy World Gen V2: running, %d tasks", tasks)
         : String.format(Locale.ROOT, "Voxy World Gen V2: waiting for the LODs within %d chunks", after);
   }

   /** The first ring not done, -1 when all are (or there is no plan: 0). */
   private static int ringBeingMade(Plan plan) {
      if (plan == null) {
         return 0;
      }

      for (int r = 0; r < plan.totals.length; r++) {
         if (plan.done[r].sum() < plan.totals[r]) {
            return r;
         }
      }

      return -1;
   }

   /** DYNAMIC: HIGH for the first ring, MEDIUM for the second, LOW after (and once all are done). */
   private static Config.VoxyPerformance dynamic(Plan plan) {
      int ring = ringBeingMade(plan);
      return ring == 0 ? Config.VoxyPerformance.HIGH : ring == 1 ? Config.VoxyPerformance.MEDIUM : Config.VoxyPerformance.LOW;
   }

   /** Whether every ring that reaches inside the radius (its inner edge under it) is done. */
   private static boolean doneWithin(Plan plan, int radius) {
      int inner = 0;
      for (int r = 0; r < plan.totals.length; r++) {
         if (inner < radius && plan.done[r].sum() < plan.totals[r]) {
            return false;
         }

         inner = plan.radii[r];
      }

      return true;
   }

   /**
    * Voxy World Gen V2's generationRadius when it is installed, on ("enabled") and voxyAvoidVwg is on, else 0. Its JSON
    * config is only read (a missing or unreadable file: 0).
    */
   private void readVwg() {
      VwgControl.Own own = this.vwg.own();
      int radius = own != null && own.enabled() && Config.VOXY_WITH_VWG.get() == Config.VoxyWithVwg.AVOID ? own.radius() : 0;
      this.vwgRadius = radius;
      if (radius != this.vwgLogged) {
         this.vwgLogged = radius;
         if (radius > 0) {
            WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS,
               "WGA Voxy LOD generator: Voxy World Gen V2 makes real chunks within {} chunks of each player (its config); that circle is left to it", radius);
         }
      }
   }

   /** Inside Voxy World Gen V2's circle around a player (its own test: dx^2 + dz^2 <= r^2 in chunks). */
   private static boolean inVwg(List<int[]> centres, int radius, int x, int z) {
      if (radius <= 0) {
         return false;
      }

      long r2 = (long)radius * radius;
      for (int[] c : centres) {
         long dx = x - c[0];
         long dz = z - c[1];
         if (dx * dx + dz * dz <= r2) {
            return true;
         }
      }

      return false;
   }

   /** The mean of the server's last 20 tick times (ms): the average over 100 ticks hides a server that just fell behind. */
   private static double recentMspt(MinecraftServer server) {
      long[] times = server.getTickTimesNanos();
      int count = server.getTickCount();
      int n = Math.min(20, Math.min(times.length, count));
      if (n <= 0) {
         return 0.0;
      }

      double sum = 0.0;
      for (int i = 1; i <= n; i++) {
         sum += times[Math.floorMod(count - i, times.length)];
      }

      return sum / n / 1.0e6;
   }

   private static boolean allDone(Plan plan) {
      for (int r = 0; r < plan.totals.length; r++) {
         if (plan.done[r].sum() < plan.totals[r]) {
            return false;
         }
      }

      return true;
   }

   /**
    * voxyRealChunks: when every ring is done, a Chunky task for the real chunks within voxyRealChunksRadius (0: the first
    * ring's) of a player whose place was not done yet this session; none while any Chunky task runs in the overworld.
    */
   private void realChunks(List<int[]> centres) {
      if (!Config.VOXY_REAL_CHUNKS.get()) {
         this.realActive = false;
         this.realLine = "";
         return;
      }

      String world = com.wgaccel.platform.Mc.id(this.level.dimension());
      boolean running = ChunkyTasks.running(world);
      if (this.realOurs && !running) {
         this.realOurs = false;
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Voxy LOD generator: real chunks done (Chunky); {} chunks handed to Voxy so far",
            this.realIngested.sum());
      }

      this.realActive = this.realOurs && running;
      if (running) {
         if (!this.realOurs) {
            this.realLine = "";
         }

         return;
      }

      this.realLine = "";
      Plan plan = this.plan;
      if (plan == null || plan.radii.length == 0 || !allDone(plan)) {
         return;
      }

      if (!ChunkyTasks.installed()) {
         if (!this.chunkyMissingLogged) {
            this.chunkyMissingLogged = true;
            WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Voxy LOD generator: voxyRealChunks needs Chunky; it is not installed");
         }

         return;
      }

      int configured = Config.VOXY_REAL_CHUNKS_RADIUS.get();
      int radius = configured > 0 ? configured : plan.radii[0];
      for (int[] c : centres) {
         long place = (long)Math.floorDiv(c[0], VoxyWriter.TILE) << 32 | Math.floorDiv(c[1], VoxyWriter.TILE) & 0xFFFFFFFFL;
         if (this.realCentres.contains(place)) {
            continue;
         }

         if (ChunkyTasks.start(world, c[0] * 16 + 8, c[1] * 16 + 8, radius * 16)) {
            this.realCentres.add(place);
            this.realOurs = true;
            this.realActive = true;
            this.realLine = String.format(Locale.ROOT, "Real chunks: Chunky, %d chunks around %d, %d", radius, c[0] * 16 + 8, c[1] * 16 + 8);
            WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, "WGA Voxy LOD generator: LODs done; real chunks within {} chunks of {}, {} started with Chunky",
               radius, c[0] * 16 + 8, c[1] * 16 + 8);
         }

         return;
      }
   }

   /** A chunk being saved (ChunkSerializer.write / SerializableChunkData.copyOf): handed to Voxy while our Chunky task runs. */
   public static void saved(ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
      VoxyAuto auto = instance;
      if (auto == null || !auto.realActive || level != auto.level || !(chunk instanceof net.minecraft.world.level.chunk.LevelChunk full)) {
         return;
      }

      try {
         VoxyAccess access = VoxyAccess.get();
         if (access != null && access.ingest(full)) {
            auto.realIngested.increment();
         }
      } catch (RuntimeException failure) {
         // Never break saving.
      }
   }

   private void waitFor(String reason) {
      this.state = VoxyProgress.State.WAITING;
      this.why = reason;
   }

   /** Whether the players moved by a near tile (8 chunks) or more, or their number changed. */
   private static boolean moved(List<int[]> before, List<int[]> now) {
      if (before.size() != now.size()) {
         return true;
      }

      for (int i = 0; i < now.size(); i++) {
         if (Math.max(Math.abs(before.get(i)[0] - now.get(i)[0]), Math.abs(before.get(i)[1] - now.get(i)[1])) >= VoxyWriter.TILE) {
            return true;
         }
      }

      return false;
   }

   /** "radius:stride" entries, checked: radii increasing, strides powers of two 1-256. Bad entries are left out (logged). */
   private static List<int[]> rings(String text) {
      List<int[]> rings = new ArrayList<>();
      int last = -1;
      for (String entry : text.split(",")) {
         String[] parts = entry.trim().split(":");
         try {
            int radius = Integer.parseInt(parts[0].trim());
            int stride = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
            if (radius > last && radius <= 4096 && stride >= 1 && stride <= 256 && Integer.bitCount(stride) == 1) {
               rings.add(new int[]{radius, stride});
               last = radius;
               continue;
            }
         } catch (RuntimeException bad) {
            // Logged below.
         }

         if (!entry.isBlank()) {
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: ring \"{}\" left out (\"radius:stride\", radii increasing, stride 1-256, a power of two)", entry.trim());
         }
      }

      return rings;
   }

   private Plan plan(List<int[]> centres, String text) {
      List<int[]> rings = rings(text.substring(0, text.indexOf("|vwg")));
      int vwg = this.vwgRadius;
      int[] radii = new int[rings.size()];
      int[] strides = new int[rings.size()];
      long[] totals = new long[rings.size()];
      LongAdder[] doneByRing = new LongAdder[rings.size()];
      List<Job> jobs = new ArrayList<>();
      int inner = 0;
      for (int r = 0; r < rings.size(); r++) {
         int radius = rings.get(r)[0];
         int stride = rings.get(r)[1];
         radii[r] = radius;
         strides[r] = stride;
         doneByRing[r] = new LongAdder();
         int unit = VoxyWriter.unit(stride);
         Map<Long, Boolean> seen = new HashMap<>();
         for (int[] centre : centres) {
            for (int tx = Math.floorDiv(centre[0] - radius, unit); tx <= Math.floorDiv(centre[0] + radius, unit); tx++) {
               for (int tz = Math.floorDiv(centre[1] - radius, unit); tz <= Math.floorDiv(centre[1] + radius, unit); tz++) {
                  if (seen.putIfAbsent(key(stride, tx, tz), Boolean.TRUE) != null) {
                     continue;
                  }

                  // The tile's chunks in this ring (between the inner ring and this one, from the nearest player).
                  int chunks = 0;
                  long nearest = Long.MAX_VALUE;
                  for (int x = tx * unit; x < (tx + 1) * unit; x++) {
                     for (int z = tz * unit; z < (tz + 1) * unit; z++) {
                        int d = distance(centres, x, z);
                        if (d <= radius && (r == 0 || d > inner) && !inVwg(centres, vwg, x, z)) {
                           chunks++;
                        }
                     }
                  }

                  if (chunks == 0) {
                     continue;
                  }

                  int midX = tx * unit + unit / 2;
                  int midZ = tz * unit + unit / 2;
                  for (int[] c : centres) {
                     long dx = midX - c[0];
                     long dz = midZ - c[1];
                     nearest = Math.min(nearest, dx * dx + dz * dz);
                  }

                  totals[r] += chunks;
                  if (this.isDone(stride, tx, tz)) {
                     doneByRing[r].add(chunks);
                  } else {
                     jobs.add(new Job(r, stride, tx, tz, nearest, chunks));
                  }
               }
            }
         }

         inner = radius;
      }

      jobs.sort((a, b) -> a.ring != b.ring ? Integer.compare(a.ring, b.ring) : Long.compare(a.distance, b.distance));
      return new Plan(jobs, new AtomicInteger(), radii, strides, totals, doneByRing, centres, text);
   }

   /** The chunk's distance (in x or z, the larger) from the nearest player. */
   private static int distance(List<int[]> centres, int x, int z) {
      int best = Integer.MAX_VALUE;
      for (int[] c : centres) {
         best = Math.min(best, Math.max(Math.abs(x - c[0]), Math.abs(z - c[1])));
      }

      return best;
   }

   /** Each ring's rate from its count's change since the last step (only while it moves: the ring being made). */
   private void measureRings(long now) {
      Plan plan = this.plan;
      if (plan == null) {
         this.ratePlan = null;
         return;
      }

      long[] done = new long[plan.done.length];
      for (int r = 0; r < done.length; r++) {
         done[r] = plan.done[r].sum();
      }

      if (plan == this.ratePlan && this.rateDone != null && now > this.rateTime) {
         double seconds = (now - this.rateTime) / 1.0e9;
         for (int r = 0; r < done.length; r++) {
            long delta = done[r] - this.rateDone[r];
            if (delta > 0) {
               double instant = delta / seconds;
               this.strideRates.merge(plan.strides[r], instant, (old, add) -> old * 0.9 + add * 0.1);
            }
         }
      }

      this.ratePlan = plan;
      this.rateDone = done;
      this.rateTime = now;
   }

   /**
    * A ring's rate: measured at its stride, else estimated from a measured one (the cost is per chunk worked out: one
    * column per s x s chunks makes s^2 times fewer of them; measured 9,300 against 890 at 4 x 4), 0 when none is.
    */
   private double ringRate(int stride) {
      Double measured = this.strideRates.get(stride);
      if (measured != null) {
         return measured;
      }

      for (Map.Entry<Integer, Double> known : this.strideRates.entrySet()) {
         double from = Math.max(1, known.getKey() / 16);
         double to = Math.max(1, stride / 16);
         return known.getValue() * (to * to) / (from * from);
      }

      return 0.0;
   }

   private VoxyProgress progress(double rate) {
      Plan plan = this.plan;
      if (plan == null || this.broken) {
         return this.state == VoxyProgress.State.WAITING || this.broken
            ? new VoxyProgress("auto", this.broken ? VoxyProgress.State.STOPPED : this.state, this.why, List.of(), 0.0, this.threads, System.nanoTime())
            : null;
      }

      List<VoxyProgress.Ring> rings = new ArrayList<>();
      boolean all = true;
      for (int r = 0; r < plan.radii.length; r++) {
         long ringDone = Math.min(plan.totals[r], plan.done[r].sum());
         rings.add(new VoxyProgress.Ring(plan.radii[r], plan.strides[r], ringDone, plan.totals[r], this.ringRate(plan.strides[r])));
         all &= ringDone >= plan.totals[r];
      }

      VoxyProgress.State state = all ? VoxyProgress.State.DONE : this.state;
      if (state == VoxyProgress.State.DONE) {
         rate = 0.0;
      }

      String real = this.realLine;
      if (!real.isEmpty()) {
         real = real + String.format(Locale.ROOT, " (%,d to Voxy)", this.realIngested.sum());
      }

      String vwgLine = this.vwgLine;
      if (!vwgLine.isEmpty()) {
         real = real.isEmpty() ? vwgLine : real + "; " + vwgLine;
      }

      return new VoxyProgress("auto", state, state == VoxyProgress.State.DONE || state == VoxyProgress.State.RUNNING ? "" : this.why, rings, rate,
         this.threads, System.nanoTime(), real);
   }

   // ---- the workers ----

   private void work(int slot) {
      LodBuilder.Worker worker = null;
      while (!this.stopping()) {
         try {
            if (!Config.VOXY_GENERATOR.get() || this.writer == null || this.plan == null || this.broken || slot >= this.threads) {
               Thread.sleep(500L);
               continue;
            }

            int limit = Config.VOXY_PAUSE_ABOVE_MSPT.get();
            double mspt = Math.max(this.server.getAverageTickTimeNanos() / 1.0e6, this.recentMspt);
            if (limit > 0 && mspt > limit) {
               this.state = VoxyProgress.State.PAUSED;
               this.why = String.format(Locale.ROOT, "server busy: %.0f ms a tick", mspt);
               Thread.sleep(250L);
               continue;
            }

            if (worker == null) {
               worker = this.writer.worker(this::stopping);
               if (worker == null) {
                  this.waitFor(GpuNoiseFill.StandIns.unavailable());
                  Thread.sleep(2_000L);
                  continue;
               }
            }

            Plan plan = this.plan;
            int index = plan.next.getAndIncrement();
            if (index >= plan.jobs.size()) {
               Thread.sleep(500L);
               continue;
            }

            this.state = VoxyProgress.State.RUNNING;
            this.why = "";
            Job job = plan.jobs.get(index);
            if (!this.isDone(job.stride, job.tx, job.tz)) {
               this.run(worker, job);
               this.done.add(key(job.stride, job.tx, job.tz));
               this.dirty = true;
            }

            plan.done[job.ring].add(job.chunks);
            this.covered.add(job.chunks);
         } catch (InterruptedException interrupted) {
            return;
         } catch (java.util.concurrent.CancellationException closing) {
            return;
         } catch (RuntimeException failure) {
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: a tile failed", failure);
            try {
               Thread.sleep(1_000L);
            } catch (InterruptedException interrupted) {
               return;
            }
         }
      }
   }

   private void run(LodBuilder.Worker worker, Job job) {
      RegionHeaders regions = new RegionHeaders(this.regionDir);
      boolean far = job.stride > 16;
      // The saved chunks' statuses, read together (each read goes through the storage thread).
      int unit = VoxyWriter.unit(job.stride);
      Map<Long, java.util.concurrent.CompletableFuture<Boolean>> saved = new HashMap<>();
      for (int x = job.tx * unit; x < (job.tx + 1) * unit; x++) {
         for (int z = job.tz * unit; z < (job.tz + 1) * unit; z++) {
            if (regions.exists(x, z) && !this.level.getChunkSource().hasChunk(x, z)) {
               saved.put(com.wgaccel.platform.Mc.key(x, z), com.wgaccel.platform.Mc.savedFull(this.level, x, z));
            }
         }
      }

      Plan plan = this.plan;
      int vwg = this.vwgRadius;
      this.writer.tile(worker, job.tx, job.tz, job.stride, (x, z) -> {
         if (plan != null && inVwg(plan.centres, vwg, x, z)) {
            return false;
         }

         // Loaded (Voxy takes them from the real chunks), or saved finished. A chunk saved at an earlier step (around finished
         // chunks: structure starts reach 8 chunks out) is never shown to the client, so Voxy never gets it: written here.
         if (this.level.getChunkSource().hasChunk(x, z)) {
            return false;
         }

         java.util.concurrent.CompletableFuture<Boolean> full = saved.get(com.wgaccel.platform.Mc.key(x, z));
         if (full != null && full.join()) {
            return false;
         }

         // A far tile leaves the chunks of near tiles already done (finer) alone.
         return !far || !this.nearDone(x, z);
      });
   }

   private boolean nearDone(int x, int z) {
      int tx = Math.floorDiv(x, VoxyWriter.TILE);
      int tz = Math.floorDiv(z, VoxyWriter.TILE);
      for (int stride : NEAR_STRIDES) {
         if (this.done.contains(key(stride, tx, tz))) {
            return true;
         }
      }

      return false;
   }

   /** Done at this stride, or (near tiles) at a finer one on the same grid. */
   private boolean isDone(int stride, int tx, int tz) {
      if (stride > 16) {
         return this.done.contains(key(stride, tx, tz));
      }

      for (int s : NEAR_STRIDES) {
         if (s <= stride && this.done.contains(key(s, tx, tz))) {
            return true;
         }
      }

      return false;
   }

   private static long key(int stride, int tx, int tz) {
      return (long)Integer.numberOfTrailingZeros(stride) << 56 | ((long)tx & 0xFFFFFFFL) << 28 | (long)tz & 0xFFFFFFFL;
   }

   // ---- the tiles done, in the world folder ----

   private void load() {
      if (!Files.exists(this.doneFile)) {
         return;
      }

      try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(this.doneFile)))) {
         if (in.readInt() != MAGIC || in.readInt() != 1) {
            return;
         }

         int count = in.readInt();
         for (int i = 0; i < count; i++) {
            this.done.add(in.readLong());
         }
      } catch (IOException failure) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: {} could not be read; its tiles will be made again", this.doneFile, failure);
         this.done.clear();
      }
   }

   private synchronized void save() {
      if (!this.dirty) {
         return;
      }

      this.dirty = false;
      try {
         Files.createDirectories(this.doneFile.getParent());
         Path temp = this.doneFile.resolveSibling(FILE + ".tmp");
         List<Long> keys = new ArrayList<>(this.done);
         try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeInt(keys.size());
            for (long key : keys) {
               out.writeLong(key);
            }
         }

         Files.move(temp, this.doneFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException failure) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: {} could not be written", this.doneFile, failure);
      }
   }

   /**
    * Which chunks the world's region files hold (their 4 KiB headers: a chunk's offset is 0 when it was never saved),
    * read once per tile. A header that cannot be read counts as every chunk there (never written over).
    */
   private static final class RegionHeaders {
      private final Path dir;
      private final Map<Long, int[]> headers = new HashMap<>();

      RegionHeaders(Path dir) {
         this.dir = dir;
      }

      boolean exists(int chunkX, int chunkZ) {
         int rx = chunkX >> 5;
         int rz = chunkZ >> 5;
         int[] offsets = this.headers.computeIfAbsent((long)rx << 32 | rz & 0xFFFFFFFFL, k -> read(this.dir.resolve("r." + rx + "." + rz + ".mca")));
         return offsets == null || offsets[(chunkX & 31) + (chunkZ & 31) * 32] != 0;
      }

      /** The region's 1,024 chunk offsets (all 0 when the file is not there), or null when it cannot be read. */
      private static int[] read(Path file) {
         int[] offsets = new int[1024];
         if (!Files.exists(file)) {
            return offsets;
         }

         try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            if (in.length() < 4096) {
               return offsets;
            }

            byte[] header = new byte[4096];
            in.readFully(header);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(header);
            for (int i = 0; i < 1024; i++) {
               offsets[i] = buffer.getInt();
            }

            return offsets;
         } catch (IOException failure) {
            return null;
         }
      }
   }
}
