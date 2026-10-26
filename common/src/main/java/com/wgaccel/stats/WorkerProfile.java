package com.wgaccel.stats;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /wgaccel profile [seconds]}: where the generation threads' time goes, by sampling their stacks every 10 ms (the
 * timed steps of the report miss lighting, the chunk system and anything other mods do between them). Running samples
 * count as CPU: by the method on top (self), by every method on the stack (total; the frames nearly every sample has
 * are left out) and by the mod owning the top frame; samples of waiting threads by the first frame above the JDK's
 * waiting code. The threads are C2ME's workers, Minecraft's worker pool and the mod's own. Logged at the end.
 */
public final class WorkerProfile {
   private static final long INTERVAL_MILLIS = 10L;
   private static final int DEPTH = 96;
   private static volatile Thread running;

   private final Map<String, Integer> self = new HashMap<>();
   private final Map<String, Integer> total = new HashMap<>();
   private final Map<String, Integer> owners = new HashMap<>();
   private final Map<String, Integer> waits = new HashMap<>();
   private final Map<String, Integer> groups = new HashMap<>();
   private final Map<String, Integer> steps = new HashMap<>();
   private final Map<String, Map<String, Integer>> callers = new HashMap<>();
   private int cpuSamples;
   private int waitSamples;
   private int rounds;

   private WorkerProfile() {
   }

   /** Starts a profile of the given length; false when one is already running. */
   public static synchronized boolean start(int seconds) {
      if (running != null && running.isAlive()) {
         return false;
      }

      Thread thread = new Thread(() -> new WorkerProfile().run(seconds * 1000L), "WGA profile");
      thread.setDaemon(true);
      running = thread;
      thread.start();
      return true;
   }

   private static boolean watched(String name) {
      return name.startsWith("c2me-worker") || name.startsWith("Worker-Main") || name.startsWith("WGA ") && !name.equals("WGA profile");
   }

   private void run(long millis) {
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      long end = System.currentTimeMillis() + millis;
      try {
         while (System.currentTimeMillis() < end) {
            List<Long> ids = new ArrayList<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
               if (watched(thread.getName())) {
                  ids.add(thread.threadId());
               }
            }

            long[] array = ids.stream().mapToLong(Long::longValue).toArray();
            for (ThreadInfo info : bean.getThreadInfo(array, DEPTH)) {
               if (info != null) {
                  this.sample(info);
               }
            }

            this.rounds++;
            Thread.sleep(INTERVAL_MILLIS);
         }

         this.log(millis);
      } catch (InterruptedException stopped) {
         Thread.currentThread().interrupt();
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA profile failed: {}", failure.toString());
      }
   }

   private void sample(ThreadInfo info) {
      StackTraceElement[] stack = info.getStackTrace();
      if (stack.length == 0) {
         return;
      }

      boolean cpu = info.getThreadState() == Thread.State.RUNNABLE && !waiting(stack[0]);
      String group = info.getThreadName().replaceAll("\\d+", "#");
      if (!cpu) {
         this.waitSamples++;
         int i = 0;
         while (i < stack.length - 1 && jdk(stack[i])) {
            i++;
         }

         add(this.waits, frame(stack[i]));
         return;
      }

      this.cpuSamples++;
      add(this.groups, group);
      String top = frame(stack[0]);
      add(this.self, top);
      add(this.owners, owner(stack));
      add(this.steps, step(stack));
      add(this.callers.computeIfAbsent(top, key -> new HashMap<>()), callers(stack));
      Set<String> seen = new HashSet<>();
      for (StackTraceElement element : stack) {
         String frame = frame(element);
         if (!plumbing(frame) && seen.add(frame)) {
            add(this.total, frame);
         }
      }
   }

   /** Mixin wrappers, lambdas and the thread pools' own frames: on every stack, telling nothing. */
   private static boolean plumbing(String frame) {
      return frame.contains("$$Lambda") || frame.contains("lambda$") || frame.contains("mixinextras") || frame.contains("wrapMethod$")
         || frame.contains("wrapOperation$") || frame.startsWith("CompletableFuture") || frame.startsWith("WorkerThread.")
         || frame.startsWith("ScheduledTask.") || frame.startsWith("Stats.time") || frame.startsWith("ChunkStep.apply")
         || frame.startsWith("VanillaWorldGenerationDelegate") || frame.startsWith("Thread.run") || frame.startsWith("ForkJoin");
   }

   /** The three frames below the top one, past the plumbing and repeats of the top's own class. */
   private static String callers(StackTraceElement[] stack) {
      List<String> path = new ArrayList<>();
      String last = frame(stack[0]);
      for (int i = 1; i < stack.length && path.size() < 3; i++) {
         String frame = frame(stack[i]);
         if (!plumbing(frame) && !frame.equals(last)) {
            path.add(frame);
            last = frame;
         }
      }

      return path.isEmpty() ? "-" : String.join(" < ", path);
   }

   /** The chunk step (or other work) a running sample belongs to, by the frames on its stack. */
   private static String step(StackTraceElement[] stack) {
      for (StackTraceElement element : stack) {
         String name = Names.frame(element.getClassName(), element.getMethodName());
         for (String[] pair : STEPS) {
            if (name.startsWith(pair[0])) {
               return pair[1];
            }
         }
      }

      return "other: " + callers(stack).split(" < ")[0];
   }

   private static final String[][] STEPS = {
      {"Deflater.", "compressing chunks (saving)"}, {"Inflater.", "decompressing chunks"}, {"ChunkSerializer.", "chunk serializing"},
      {"NbtIo.", "chunk serializing"}, {"CompoundTag.write", "chunk serializing"},
      {"ChunkStatusTasks.generateStructureStarts", "structure starts"}, {"ChunkStatusTasks.generateStructureReferences", "structure refs"},
      {"ChunkStatusTasks.generateBiomes", "biomes"}, {"ChunkStatusTasks.generateNoise", "noise fill"},
      {"ChunkStatusTasks.generateSurface", "surface"}, {"ChunkStatusTasks.generateCarvers", "carvers"},
      {"ChunkStatusTasks.generateFeatures", "features"}, {"ChunkStatusTasks.initializeLight", "init light"},
      {"ChunkStatusTasks.light", "light"}, {"ChunkStatusTasks.generateSpawn", "spawn"}, {"ChunkStatusTasks.full", "full"},
      {"StarLightEngine", "light"}, {"StarLightInterface", "light"}, {"LightEngine", "light"},
      {"GpuNoiseFill$Ahead", "fill look-ahead"}, {"BiomesAhead", "biomes look-ahead"}, {"FtfRawBlocks", "FTF raw chunks"},
      {"TileCache", "FTF tiles"}, {"TileGenerator", "FTF tiles"}};

   /** Native frames a running thread shows while it waits (park, a socket or file read, a GPU call that blocks). */
   private static boolean waiting(StackTraceElement top) {
      String method = top.getMethodName();
      // LWJGL's JNI calls are the GPU driver's (its waits for the card among them): counted with the waits.
      return top.isNativeMethod() && (method.equals("park") || method.startsWith("wait") || method.equals("sleep") || top.getClassName().endsWith(".JNI"));
   }

   private static boolean jdk(StackTraceElement element) {
      String name = element.getClassName();
      return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.");
   }

   private static String frame(StackTraceElement element) {
      return Names.frame(element.getClassName(), element.getMethodName());
   }

   /** The mod (by package) of the first frame outside the JDK and fastutil; Minecraft's as "minecraft". */
   private static String owner(StackTraceElement[] stack) {
      for (StackTraceElement element : stack) {
         String name = element.getClassName();
         if (jdk(element) || name.startsWith("it.unimi.")) {
            continue;
         }

         if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) {
            return "minecraft";
         }

         String[] parts = name.split("\\.");
         return parts.length >= 3 ? parts[0] + "." + parts[1] + "." + parts[2] : name;
      }

      return "the JDK";
   }

   private static void add(Map<String, Integer> map, String key) {
      map.merge(key, 1, Integer::sum);
   }

   private void log(long millis) {
      List<String> lines = new ArrayList<>();
      lines.add(String.format(Locale.ROOT, "WGA profile: %.0f s, %,d rounds of the generation threads every %d ms: %,d samples running (%.1f threads on average), %,d waiting (with the GPU driver's calls)",
         millis / 1000.0, this.rounds, INTERVAL_MILLIS, this.cpuSamples, (double)this.cpuSamples / Math.max(1, this.rounds), this.waitSamples));
      lines.add("WGA profile threads running: " + top(this.groups, this.cpuSamples, 10, 1.0));
      lines.add("WGA profile running, by mod of the top frame: " + top(this.owners, this.cpuSamples, 15, 1.0));
      lines.add("WGA profile running, by step: " + top(this.steps, this.cpuSamples, 25, 1.0));
      lines.add("WGA profile running, self (the method on top): " + top(this.self, this.cpuSamples, 40, 1.0));
      List<Map.Entry<String, Integer>> hottest = new ArrayList<>(this.self.entrySet());
      hottest.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
      for (Map.Entry<String, Integer> entry : hottest.subList(0, Math.min(15, hottest.size()))) {
         lines.add(String.format(Locale.ROOT, "WGA profile   %s %.1f%% called from: %s", entry.getKey(), 100.0 * entry.getValue() / Math.max(1, this.cpuSamples),
            top(this.callers.get(entry.getKey()), entry.getValue(), 4, 1.0)));
      }

      lines.add("WGA profile running, total (methods on the stack, wrappers and those in over 90% of samples left out): " + top(this.total, this.cpuSamples, 60, 0.9));
      lines.add("WGA profile waiting, by the first frame above the JDK's waiting code: " + top(this.waits, this.waitSamples, 20, 1.0));
      // Asked for with a command: past the Normal debug level's every-10th rule.
      lines.forEach(line -> WorldgenAccelerator.LOG.info(com.wgaccel.LevelLogger.ALWAYS, line));
   }

   private static String top(Map<String, Integer> counts, int all, int most, double cut) {
      List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
      entries.removeIf(entry -> entry.getValue() > cut * all && cut < 1.0);
      entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Integer> entry : entries.subList(0, Math.min(most, entries.size()))) {
         parts.add(String.format(Locale.ROOT, "%s %.1f%%", entry.getKey(), 100.0 * entry.getValue() / Math.max(1, all)));
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }
}
