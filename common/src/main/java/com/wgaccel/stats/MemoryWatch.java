package com.wgaccel.stats;

import com.wgaccel.WorldgenAccelerator;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import javax.management.ObjectName;

/**
 * Where the memory goes when the heap fills (the user, 2026-10-22: "seems like the mod has a memory leak"): while the
 * heap is above 80% of its maximum, at most once a minute and 8 times a world, a class histogram is taken (it runs a
 * full collection, so it shows only what is still held) and goes to the log with the heap after that collection and the
 * sizes of the mod's own caches; while less than half the heap is still held, one line only. A collector such as ZGC
 * lets the heap fill before collecting, so the first report alone (Packtrix, 25 Oct 2026: 7.9 GB used, 3.0 GB held,
 * five seconds into a pregeneration that ran out of memory six minutes later) showed nothing; the later ones show what
 * grows. On its own thread; a pause of a second or so each time.
 */
public final class MemoryWatch {
   private static final double LEVEL = 0.80;
   private static final int MOST = 8;
   private static final long EVERY_NANOS = 60_000_000_000L;
   private static volatile int done;
   private static volatile long last;
   private static volatile boolean running;

   private MemoryWatch() {
   }

   /** From the 10 s report: the heap used now and its maximum. */
   public static void check(long used, long max) {
      long now = System.nanoTime();
      if (done >= MOST || running || used < LEVEL * max || done > 0 && now - last < EVERY_NANOS || !com.wgaccel.Config.MEMORY_REPORT.get()) {
         return;
      }

      done++;
      last = now;
      running = true;
      Thread thread = new Thread(() -> {
         try {
            report(used, max);
         } finally {
            running = false;
         }
      }, "WGA memory report");
      thread.setDaemon(true);
      thread.start();
   }

   /** A new world: the reports may run again. */
   public static void reset() {
      done = 0;
   }

   /** ProtoChunk, LevelChunk, ImposterProtoChunk and ChunkHolder: Fabric's intermediary names and NeoForge's. */
   private static final java.util.Set<String> CHUNK_CLASSES = java.util.Set.of("net.minecraft.class_2839", "net.minecraft.class_2818",
      "net.minecraft.class_2821", "net.minecraft.class_3193", "net.minecraft.world.level.chunk.ProtoChunk",
      "net.minecraft.world.level.chunk.LevelChunk", "net.minecraft.world.level.chunk.ImposterProtoChunk",
      "net.minecraft.server.level.ChunkHolder");

   private static void report(long used, long max) {
      try {
         String histogram = (String)ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
         long after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
         boolean full = done == 1 || after >= max / 2;
         StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
            "WGA memory report %d of %d: heap %.1f of %.1f GB before a full collection, %.1f GB still held after it; the mod's caches: %s%s",
            done, MOST, used / 1.073741824e9, max / 1.073741824e9, after / 1.073741824e9, caches(),
            full ? String.format(Locale.ROOT, "; the largest classes:%n") : " (less than half the heap held: no class list)"));
         String[] lines = full ? histogram.split("\n") : new String[0];
         for (int i = 0; i < Math.min(lines.length, 33); i++) {
            out.append("WGA memory:   ").append(lines[i].trim()).append('\n');
         }

         // Further down: the chunk classes (loaded chunks or leftovers held from elsewhere) and the largest of other mods'.
         int others = 0;
         for (int i = 33; i < lines.length; i++) {
            String[] row = lines[i].trim().split("\\s+");
            if (row.length < 4) {
               continue;
            }

            String name = row[3];
            boolean chunk = CHUNK_CLASSES.contains(name);
            boolean mod = !chunk && others < 15 && !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("[")
               && !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.") && !name.startsWith("it.unimi.")
               && !name.startsWith("com.google.") && !name.startsWith("sun.");
            if (chunk || mod) {
               others += mod ? 1 : 0;
               out.append("WGA memory:   ").append(lines[i].trim()).append(chunk ? "  (chunk class)" : "").append('\n');
            }
         }

         // Asked for in the config: past the Normal debug level's every-10th rule (its lines all start alike).
         WorldgenAccelerator.LOG.info(com.wgaccel.LevelLogger.ALWAYS, out.toString().trim());
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA memory report failed: {}", failure.toString());
      }
   }

   private static String caches() {
      StringBuilder out = new StringBuilder();
      try {
         out.append(com.wgaccel.gen.GpuNoiseFill.memorySummary());
         out.append(", ").append(com.wgaccel.gen.BiomesAhead.memorySummary());
         out.append(", ").append(com.wgaccel.gen.SurfaceAhead.memorySummary());
         out.append(", ").append(com.wgaccel.gen.GpuSurface.memorySummary());
         out.append(", ").append(com.wgaccel.gpu.TileNoiseGpu.memorySummary());
         out.append(", ").append(com.wgaccel.gpu.GpuMemory.summary());
      } catch (Throwable failure) {
         out.append(failure);
      }

      return out.toString();
   }
}
