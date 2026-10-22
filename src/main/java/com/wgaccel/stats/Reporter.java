package com.wgaccel.stats;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.gen.FillToGround;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Writes what chunk generation did to the log every 10 seconds (only while something happens), and a summary for the
 * whole run on {@code /wgaccel}, on {@code /wgaccel reset} and when the world closes. Every line starts with "WGA".
 *
 * <p>A run starts when the world opens or on {@code /wgaccel reset}. Its CPU, thread and garbage collection figures
 * count only the 10-second windows in which new chunks were made, so idle time does not dilute them.
 */
public final class Reporter {
   private static final long PERIOD_SECONDS = 10L;
   private static final int TOP_GROUPS = 8;
   private static final Stage[] WORK = {
      Stage.NOISE_FILL, Stage.BIOME_FILL, Stage.FEATURES, Stage.SURFACE, Stage.CARVERS, Stage.STRUCTURE_STARTS,
      Stage.STRUCTURE_REFERENCES, Stage.SPAWN
   };
   private static final Stage[] WAITS = {Stage.BIOMES, Stage.NOISE, Stage.INIT_LIGHT, Stage.LIGHT, Stage.FULL};
   private static final int THREADS = Runtime.getRuntime().availableProcessors();

   private static ScheduledExecutorService scheduler;
   private static final ThreadCpu THREAD_CPU = new ThreadCpu();
   private static final Gc GC = new Gc();
   private static Stats.Snapshot previous = Stats.snapshot();
   private static Map<Named.Category, Map<String, long[]>> previousNamed = Named.snapshot();
   private static long previousTime = System.nanoTime();
   private static long previousCpu = processCpu();

   private static Stats.Snapshot runBase = previous;
   private static Map<Named.Category, Map<String, long[]>> runBaseNamed = previousNamed;
   private static String runLabel = "the world opened";
   private static final Map<String, Long> runThreads = new HashMap<>();
   private static long runCpu;
   private static double runActiveSeconds;
   private static long runGcPauseMillis;
   private static long runGcPauses;
   private static double runBestRate;

   private Reporter() {
   }

   public static synchronized void start() {
      stopScheduler();
      reset("the world opened");
      scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
         Thread thread = new Thread(task, "WGA report");
         thread.setDaemon(true);
         return thread;
      });
      scheduler.scheduleAtFixedRate(Reporter::safeTick, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
   }

   public static synchronized void stop() {
      tick();
      summary().forEach(WorldgenAccelerator.LOG::info);
      stopScheduler();
   }

   /** Starts a new run; the summary of the one before it is logged and returned. */
   public static synchronized List<String> reset(String label) {
      tick();
      List<String> before = runBase == null ? List.of() : summary();
      runBase = Stats.snapshot();
      runBaseNamed = Named.snapshot();
      runLabel = label;
      Stats.resetRun();
      runThreads.clear();
      runCpu = 0L;
      runActiveSeconds = 0.0;
      runGcPauseMillis = 0L;
      runGcPauses = 0L;
      runBestRate = 0.0;
      return before;
   }

   private static void stopScheduler() {
      if (scheduler != null) {
         scheduler.shutdownNow();
         scheduler = null;
      }
   }

   private static void safeTick() {
      try {
         synchronized (Reporter.class) {
            tick();
         }
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA report failed", failure);
      }
   }

   private static void tick() {
      com.wgaccel.WgaChat.flush();
      long now = System.nanoTime();
      double seconds = (now - previousTime) / 1.0e9;
      Stats.Snapshot current = Stats.snapshot();
      Stats.Snapshot window = current.minus(previous);
      Map<Named.Category, Map<String, long[]>> currentNamed = Named.snapshot();
      Map<Named.Category, Map<String, long[]>> windowNamed = Named.minus(currentNamed, previousNamed);
      Map<String, Long> threads = THREAD_CPU.delta();
      long cpuNow = processCpu();
      long cpu = cpuNow - previousCpu;
      long[] gc = GC.delta();
      long[] slowest = new long[Stage.values().length];
      for (Stage stage : Stage.values()) {
         slowest[stage.ordinal()] = Stats.takeWindowMax(stage);
      }

      previous = current;
      previousNamed = currentNamed;
      previousTime = now;
      previousCpu = cpuNow;
      if (window.idle() || seconds <= 0.0) {
         return;
      }

      long newChunks = window.calls(Stage.NOISE);
      if (newChunks > 0L) {
         runActiveSeconds += seconds;
         runCpu += cpu;
         runGcPauseMillis += gc[0];
         runGcPauses += gc[1];
         threads.forEach((group, nanos) -> runThreads.merge(group, nanos, Long::sum));
         runBestRate = Math.max(runBestRate, newChunks / seconds);
      }

      Runtime runtime = Runtime.getRuntime();
      double usedGb = (runtime.totalMemory() - runtime.freeMemory()) / 1.073741824e9;
      double maxGb = runtime.maxMemory() / 1.073741824e9;
      MemoryWatch.check(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory());
      log(String.format(Locale.ROOT, "WGA %.0fs: %,d new chunks (%.1f/s), %,d full | CPU %.1f of %d threads | GC pauses %,d ms (%d) | heap %.1f / %.1f GB",
         seconds, newChunks, newChunks / seconds, window.calls(Stage.FULL), cpu / 1.0e9 / seconds, THREADS, gc[0], gc[1], usedGb, maxGb) + com.wgaccel.c2me.AreaStep.summary());
      log("WGA work per chunk (ms, share): " + work(window, null) + layers(window));
      log("WGA waits per chunk (ms): " + waits(window, null) + " | saving: " + saving(window, null) + ftf(window, null));
      log("WGA features (average per call, share of feature time): " + Named.top(windowNamed.get(Named.Category.FEATURE), 6, false)
         + restOfFeatures(window, windowNamed));
      log("WGA structures: starts " + Named.top(windowNamed.get(Named.Category.STRUCTURE_START), 4, true)
         + " | pieces " + Named.top(windowNamed.get(Named.Category.STRUCTURE_PIECES), 4, false));
      log("WGA biome step per chunk (ms): " + biomeParts(window));
      log("WGA noise fill per chunk (ms): " + fillParts(window));
      log("WGA surface per chunk (ms): " + surfaceParts(window));
      log("WGA main thread per full chunk (ms): " + mainThread(window));
      log("WGA cores by thread group: " + groups(threads, seconds));
   }

   /** The features step's time not in placed features or structure pieces: Settlements 2, mods' hooks, heightmaps. */
   private static String restOfFeatures(Stats.Snapshot s, Map<Named.Category, Map<String, long[]>> named) {
      long chunks = s.calls(Stage.FEATURES);
      if (chunks == 0L) {
         return "";
      }

      long rest = s.nanos(Stage.FEATURES) - Named.totalNanos(named.get(Named.Category.FEATURE)) - Named.totalNanos(named.get(Named.Category.STRUCTURE_PIECES));
      return String.format(Locale.ROOT, " | per chunk: placed features %.2f ms, structure pieces %.2f ms, the rest (Settlements 2, mods' hooks) %.2f ms",
         Named.totalNanos(named.get(Named.Category.FEATURE)) / 1.0e6 / chunks,
         Named.totalNanos(named.get(Named.Category.STRUCTURE_PIECES)) / 1.0e6 / chunks, Math.max(0L, rest) / 1.0e6 / chunks);
   }

   /** The biome step's parts per chunk: reading the ground (waits for FTF's tile), climate sampling, biome choosing. */
   private static String biomeParts(Stats.Snapshot s) {
      long chunks = s.calls(Stage.BIOME_FILL);
      if (chunks == 0L) {
         return "none";
      }

      long probed = BiomeFill.CHUNKS.sum();
      return String.format(Locale.ROOT, "all %.2f = ground read (tile wait) %.2f + climate sampling %.2f (%,d cells, %.2f us each) + biome choosing %.2f (%.2f us each:"
            + " table search %.2f us, FTF decoration %.2f us) + the rest | decoration per chunk (ms): surface coverage %.2f, underground layout %.2f"
            + " (of it identity resample %.2f: %,d per chunk, %.2f us each, %.0f different per chunk so far; table searches %.2f, %,d per chunk;"
            + " 3D regions %.2f, %,d per chunk; stage choice %.2f, %,d per chunk), terrain roles %.2f"
            + " | faster biome choice so far: column terrain reused %,d (read %,d), coverage reused %,d, cave-biome choices reused %,d, checks %,d, differences %,d",
         s.nanos(Stage.BIOME_FILL) / 1.0e6 / chunks, s.nanos(Stage.FTF_GROUND) / 1.0e6 / chunks, s.nanos(Stage.BIOME_SAMPLE) / 1.0e6 / chunks,
         s.calls(Stage.BIOME_SAMPLE) / chunks, s.avgMillis(Stage.BIOME_SAMPLE) * 1000.0, s.nanos(Stage.BIOME_SELECT) / 1.0e6 / chunks,
         s.avgMillis(Stage.BIOME_SELECT) * 1000.0, s.avgMillis(Stage.BIOME_SEARCH) * 1000.0, s.avgMillis(Stage.BIOME_DECORATE) * 1000.0,
         s.nanos(Stage.BIOME_COVERAGE) / 1.0e6 / chunks, s.nanos(Stage.BIOME_LAYOUT) / 1.0e6 / chunks, s.nanos(Stage.BIOME_IDENTITY) / 1.0e6 / chunks,
         s.calls(Stage.BIOME_IDENTITY) / chunks, s.avgMillis(Stage.BIOME_IDENTITY) * 1000.0,
         probed == 0L ? 0.0 : (double)BiomeFill.DISTINCT.sum() / probed,
         s.nanos(Stage.BIOME_LAYOUT_SEARCH) / 1.0e6 / chunks, s.calls(Stage.BIOME_LAYOUT_SEARCH) / chunks,
         s.nanos(Stage.BIOME_SAMPLE3D) / 1.0e6 / chunks, s.calls(Stage.BIOME_SAMPLE3D) / chunks,
         s.nanos(Stage.BIOME_STAGE) / 1.0e6 / chunks, s.calls(Stage.BIOME_STAGE) / chunks, s.nanos(Stage.BIOME_ROLES) / 1.0e6 / chunks,
         BiomeFill.ROLE_HITS.sum(), BiomeFill.ROLE_MISSES.sum(), BiomeFill.COVERAGE_HITS.sum(), BiomeFill.STAGE_HITS.sum(), BiomeFill.CHECKS.sum(), BiomeFill.MISMATCHES.sum());
   }

   /** The noise fill's parts (GPU step G2.1): the cell corners, and the rest (per-block density, aquifer, ore veins, writing blocks). */
   private static String fillParts(Stats.Snapshot s) {
      long chunks = s.calls(Stage.NOISE_FILL);
      if (chunks == 0L) {
         return "none";
      }

      double fill = s.nanos(Stage.NOISE_FILL) / 1.0e6 / chunks;
      double corners = s.nanos(Stage.FILL_CORNERS) / 1.0e6 / chunks;
      return String.format(Locale.ROOT, "all %.2f = cell corners %.2f (%.1f slices per chunk) + per-block density, aquifer, ore veins and writing blocks %.2f",
         fill, corners, (double)s.calls(Stage.FILL_CORNERS) / chunks, fill - corners);
   }

   /** GPU step G3.1: the surface step split by {@link com.wgaccel.gen.SurfaceProbe}. */
   private static String surfaceParts(Stats.Snapshot s) {
      long chunks = s.calls(Stage.SURFACE);
      long built = s.calls(Stage.SURFACE_SYSTEM);
      if (chunks == 0L || built == 0L) {
         return "none";
      }

      double all = s.nanos(Stage.SURFACE) / 1.0e6 / chunks;
      double system = s.nanos(Stage.SURFACE_SYSTEM) / 1.0e6 / built;
      double before = s.nanos(Stage.SURFACE_BEFORE) / 1.0e6 / Math.max(1, s.calls(Stage.SURFACE_BEFORE));
      double tree = s.nanos(Stage.SURFACE_RULE_TREE) / 1.0e6 / Math.max(1, s.calls(Stage.SURFACE_RULE_TREE));
      long sampled = s.calls(Stage.SURFACE_RULES);
      double rules = sampled == 0 ? 0.0 : s.nanos(Stage.SURFACE_RULES) / 1.0e6 / sampled;
      long calls = com.wgaccel.gen.SurfaceProbe.RULE_CALLS.sum();
      long sampledChunks = Math.max(1, com.wgaccel.gen.SurfaceProbe.SAMPLED_CHUNKS.sum());
      return String.format(Locale.ROOT,
         "all %.2f = surface system %.2f (before the rules, FTF's river water, %.2f + rule tree made %.2f + the column loop %.2f, of it the rules tried %.2f: %,d calls per chunk, timed in 1 chunk of 8) + the rest %.2f",
         all, system, before, tree, system - before - tree, rules, calls / sampledChunks, all - system);
   }

   private static String mainThread(Stats.Snapshot s) {
      long chunks = s.calls(Stage.FULL);
      if (chunks == 0L) {
         return "none";
      }

      return String.format(Locale.ROOT, "entities %.2f (%,d entities joined), block entities %.2f, chunk-load listeners %.2f",
         s.nanos(Stage.ENTITIES) / 1.0e6 / chunks, s.entities(), s.nanos(Stage.BLOCK_ENTITIES) / 1.0e6 / chunks,
         s.nanos(Stage.LOAD_EVENT) / 1.0e6 / chunks);
   }

   /** The run so far, as lines for the log or the chat. */
   public static synchronized List<String> summary() {
      Stats.Snapshot run = Stats.snapshot().minus(runBase);
      List<String> lines = new ArrayList<>();
      long chunks = run.calls(Stage.NOISE);
      double span = Stats.chunkSpan() / 1.0e9;
      if (chunks == 0L) {
         lines.add("WGA summary (since " + runLabel + "): no new chunks yet");
         return lines;
      }

      double rate = span > 0.0 ? chunks / span : 0.0;
      lines.add(String.format(Locale.ROOT, "WGA summary (since %s): %,d new chunks in %.1f s = %.1f chunks/s (best 10 s window %.1f/s)",
         runLabel, chunks, span, rate, runBestRate));
      if (runActiveSeconds > 0.0) {
         lines.add(String.format(Locale.ROOT, "WGA   while generating: CPU %.1f of %d threads, GC pauses %,d ms (%d)",
            runCpu / 1.0e9 / runActiveSeconds, THREADS, runGcPauseMillis, runGcPauses));
      }

      lines.add("WGA   work per chunk (ms average / slowest, share): " + work(run, Stats::runMax) + layers(run));
      lines.add("WGA   waits per chunk (ms average / slowest): " + waits(run, Stats::runMax));
      lines.add("WGA   saving: " + saving(run, Stats::runMax) + ftf(run, Stats::runMax));
      Map<Named.Category, Map<String, long[]>> named = Named.minus(Named.snapshot(), runBaseNamed);
      lines.add("WGA   features (average per call, share of feature time): " + Named.top(named.get(Named.Category.FEATURE), 15, false)
         + restOfFeatures(run, named));
      lines.add("WGA   structure starts: " + Named.top(named.get(Named.Category.STRUCTURE_START), 10, true));
      lines.add("WGA   structure pieces: " + Named.top(named.get(Named.Category.STRUCTURE_PIECES), 10, false));
      lines.add("WGA   biome step per chunk (ms): " + biomeParts(run));
      lines.add("WGA   noise fill per chunk (ms): " + fillParts(run));
      lines.add("WGA   surface per chunk (ms): " + surfaceParts(run));
      lines.add("WGA   main thread per full chunk (ms): " + mainThread(run));
      if (runActiveSeconds > 0.0) {
         lines.add("WGA   cores by thread group: " + groups(runThreads, runActiveSeconds));
      }

      return lines;
   }

   private static String work(Stats.Snapshot s, SlowestLookup slowest) {
      long total = 0L;
      for (Stage stage : WORK) {
         total += s.nanos(stage);
      }

      List<Stage> stages = new ArrayList<>(List.of(WORK));
      stages.removeIf(stage -> s.calls(stage) == 0L);
      stages.sort(Comparator.comparingLong((Stage stage) -> s.nanos(stage)).reversed());
      List<String> parts = new ArrayList<>();
      for (Stage stage : stages) {
         double share = total == 0L ? 0.0 : 100.0 * s.nanos(stage) / total;
         parts.add(slowest == null
            ? String.format(Locale.ROOT, "%s %.2f (%.0f%%)", stage.label, s.avgMillis(stage), share)
            : String.format(Locale.ROOT, "%s %.2f / %.1f (%.0f%%)", stage.label, s.avgMillis(stage), slowest.get(stage) / 1.0e6, share));
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }

   private static String layers(Stats.Snapshot s) {
      long chunks = s.calls(Stage.NOISE_FILL);
      if (chunks == 0L || s.layersFilled() == 0L) {
         return "";
      }

      return String.format(Locale.ROOT, " | noise layers per chunk: %.0f filled, %.0f needed by the highest block (%.0f%%) | fill-to-ground so far: %,d chunks cut, %,d warnings",
         (double)s.layersFilled() / chunks, (double)s.layersNeeded() / chunks, 100.0 * s.layersNeeded() / s.layersFilled(),
         FillToGround.CUT.sum(), FillToGround.TOUCHED.sum());
   }

   private static String waits(Stats.Snapshot s, SlowestLookup slowest) {
      List<String> parts = new ArrayList<>();
      for (Stage stage : WAITS) {
         if (s.calls(stage) > 0L) {
            parts.add(slowest == null
               ? String.format(Locale.ROOT, "%s %.2f", stage.label, s.avgMillis(stage))
               : String.format(Locale.ROOT, "%s %.2f / %.1f", stage.label, s.avgMillis(stage), slowest.get(stage) / 1.0e6));
         }
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }

   private static String saving(Stats.Snapshot s, SlowestLookup slowest) {
      List<String> parts = new ArrayList<>();
      for (Stage stage : new Stage[]{Stage.SERIALIZE, Stage.REGION_WRITE}) {
         if (s.calls(stage) > 0L) {
            parts.add(slowest == null
               ? String.format(Locale.ROOT, "%s %.2f ms x %,d", stage.label, s.avgMillis(stage), s.calls(stage))
               : String.format(Locale.ROOT, "%s %.2f ms x %,d (slowest %.1f)", stage.label, s.avgMillis(stage), s.calls(stage), slowest.get(stage) / 1.0e6));
         }
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }

   private static String ftf(Stats.Snapshot s, SlowestLookup slowest) {
      long tiles = s.calls(Stage.FTF_TILE);
      long filters = s.calls(Stage.FTF_FILTERS);
      if (tiles == 0L && filters == 0L) {
         return "";
      }

      String tileText = slowest == null
         ? String.format(Locale.ROOT, "%,d tiles (%.0f ms each from request to ready)", tiles, s.avgMillis(Stage.FTF_TILE))
         : String.format(Locale.ROOT, "%,d tiles (%.0f ms each from request to ready, slowest %.0f)", tiles, s.avgMillis(Stage.FTF_TILE), slowest.get(Stage.FTF_TILE) / 1.0e6);
      return String.format(Locale.ROOT, " | FreeTerraForged: %s, filters %.1f ms each, %,d different tiles so far this run", tileText,
         s.avgMillis(Stage.FTF_FILTERS), Stats.distinctTiles());
   }

   private static String groups(Map<String, Long> threads, double seconds) {
      List<Map.Entry<String, Long>> entries = new ArrayList<>(threads.entrySet());
      entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Long> entry : entries) {
         double cores = entry.getValue() / 1.0e9 / seconds;
         if (parts.size() >= TOP_GROUPS || cores < 0.05) {
            break;
         }

         parts.add(String.format(Locale.ROOT, "%s %.1f", entry.getKey(), cores));
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }

   private static long processCpu() {
      return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : 0L;
   }

   private static void log(String line) {
      WorldgenAccelerator.LOG.info(line);
   }

   public static String timeLabel() {
      return "/wgaccel reset at " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
   }

   @FunctionalInterface
   private interface SlowestLookup {
      long get(Stage stage);
   }

   /** CPU time per thread group (thread names with their numbers replaced by #) since the last call. */
   private static final class ThreadCpu {
      private final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      private final Map<Long, Long> last = new HashMap<>();
      private boolean primed;

      ThreadCpu() {
         try {
            if (this.bean.isThreadCpuTimeSupported() && !this.bean.isThreadCpuTimeEnabled()) {
               this.bean.setThreadCpuTimeEnabled(true);
            }
         } catch (UnsupportedOperationException | SecurityException ignored) {
         }
      }

      Map<String, Long> delta() {
         Map<String, Long> groups = new HashMap<>();
         if (!this.bean.isThreadCpuTimeSupported()) {
            return groups;
         }

         long[] ids = this.bean.getAllThreadIds();
         ThreadInfo[] infos = this.bean.getThreadInfo(ids);
         Map<Long, Long> seen = new HashMap<>();
         for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            long cpu = this.bean.getThreadCpuTime(ids[i]);
            if (info == null || cpu < 0L) {
               continue;
            }

            seen.put(ids[i], cpu);
            if (this.primed) {
               long before = this.last.getOrDefault(ids[i], 0L);
               long used = cpu - before;
               if (used > 0L) {
                  groups.merge(info.getThreadName().replaceAll("\\d+", "#"), used, Long::sum);
               }
            }
         }

         this.last.clear();
         this.last.putAll(seen);
         this.primed = true;
         return groups;
      }
   }

   /** Garbage collection since the last call: {pause milliseconds, pauses}. */
   private static final class Gc {
      private final Map<String, long[]> last = new HashMap<>();

      long[] delta() {
         long pauseMillis = 0L;
         long pauses = 0L;
         for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = bean.getName();
            long time = Math.max(0L, bean.getCollectionTime());
            long count = Math.max(0L, bean.getCollectionCount());
            long[] before = this.last.put(name, new long[]{time, count});
            // ZGC reports its concurrent cycles apart from its pauses; G1's young and old collections are pauses.
            boolean concurrent = name.contains("Cycles") || name.contains("Concurrent");
            if (before != null && !concurrent) {
               pauseMillis += time - before[0];
               pauses += count - before[1];
            }
         }

         return new long[]{pauseMillis, pauses};
      }
   }
}
