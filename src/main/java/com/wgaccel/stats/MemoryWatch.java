package com.wgaccel.stats;

import com.wgaccel.WorldgenAccelerator;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import javax.management.ObjectName;

/**
 * Where the memory goes when the heap fills (the user, 2026-10-22: "seems like the mod has a memory leak"): the first
 * time the heap passes 80% of its maximum, and again at 95%, a class histogram is taken (it runs a full collection, so
 * it shows only what is still held) and its top lines go to the log with the heap after that collection and the sizes
 * of the mod's own caches. On its own thread; a pause of a second or so each time.
 */
public final class MemoryWatch {
   private static final double[] LEVELS = {0.80, 0.95};
   private static volatile int next;
   private static volatile boolean running;

   private MemoryWatch() {
   }

   /** From the 10 s report: the heap used now and its maximum. */
   public static void check(long used, long max) {
      int level = next;
      if (level >= LEVELS.length || running || used < LEVELS[level] * max || !com.wgaccel.Config.MEMORY_REPORT.get()) {
         return;
      }

      next = level + 1;
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
      next = 0;
   }

   private static void report(long used, long max) {
      try {
         String histogram = (String)ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
         long after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
         StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
            "WGA memory: heap %.1f of %.1f GB before a full collection, %.1f GB still held after it; the mod's caches: %s; the largest classes:%n",
            used / 1.073741824e9, max / 1.073741824e9, after / 1.073741824e9, caches()));
         String[] lines = histogram.split("\n");
         for (int i = 0; i < Math.min(lines.length, 33); i++) {
            out.append("WGA memory:   ").append(lines[i].trim()).append('\n');
         }

         WorldgenAccelerator.LOG.info(out.toString().trim());
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
