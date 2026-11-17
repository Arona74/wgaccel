package com.wgaccel.gpu;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The GPU programs being made for a world (compiled when the overworld is created, before its spawn area): each a
 * task, done or failed, so the server can wait for them and the world-loading screen can show a bar. No Minecraft
 * classes, so either side (and the standalone checks) can use it.
 */
public final class GpuPrepare {
   public static final class Task {
      final String name;
      final long start = System.nanoTime();
      volatile long end;
      volatile String failure;

      Task(String name) {
         this.name = name;
      }

      public void done() {
         this.end = System.nanoTime();
      }

      public void failed(String why) {
         this.failure = why;
         this.end = System.nanoTime();
      }
   }

   private static final List<Task> TASKS = new CopyOnWriteArrayList<>();
   private static volatile long started;

   private GpuPrepare() {
   }

   /** A new world: forget the last one's tasks. */
   public static synchronized void reset() {
      TASKS.clear();
      started = System.nanoTime();
   }

   public static Task begin(String name) {
      Task task = new Task(name);
      TASKS.add(task);
      return task;
   }

   /** Whether any task is still running (the loading screen shows the bar while so). */
   public static boolean active() {
      for (Task task : TASKS) {
         if (task.end == 0L) {
            return true;
         }
      }

      return false;
   }

   /** Tasks finished (done or failed) out of all, 0 to 1. */
   public static float progress() {
      int done = 0;
      for (Task task : TASKS) {
         done += task.end != 0L ? 1 : 0;
      }

      return TASKS.isEmpty() ? 1.0f : (float)done / TASKS.size();
   }

   /** "Preparing the GPU: noise fill (12 s), erosion filter done": the running ones with their time, then the finished ones. */
   public static String status() {
      StringBuilder text = new StringBuilder("Worldgen Accelerator: preparing the GPU");
      long now = System.nanoTime();
      String separator = ": ";
      for (Task task : TASKS) {
         text.append(separator).append(task.name);
         if (task.end == 0L) {
            text.append(String.format(Locale.ROOT, " (%d s)", (now - task.start) / 1_000_000_000L));
         } else {
            text.append(task.failure == null ? " done" : " off");
         }

         separator = ", ";
      }

      return text.toString();
   }

   /** A task as the world-loading screen lists it: its name, whether it is done or failed, and its seconds so far or in all. */
   public record Line(String name, boolean done, boolean failed, double seconds) {
   }

   /** The tasks in the order they began. */
   public static List<Line> lines() {
      long now = System.nanoTime();
      List<Line> lines = new java.util.ArrayList<>();
      for (Task task : TASKS) {
         long end = task.end;
         lines.add(new Line(task.name, end != 0L && task.failure == null, task.failure != null, ((end != 0L ? end : now) - task.start) / 1.0e9));
      }

      return lines;
   }

   /** Seconds from the first task's start to the last one's end, once all are done; else -1. */
   public static double totalSeconds() {
      long first = Long.MAX_VALUE;
      long last = 0L;
      for (Task task : TASKS) {
         if (task.end == 0L) {
            return -1.0;
         }

         first = Math.min(first, task.start);
         last = Math.max(last, task.end);
      }

      return TASKS.isEmpty() ? -1.0 : (last - first) / 1.0e9;
   }

   /** Waits (on the server thread, before the spawn area) until every task has finished, at most this long. */
   public static void await(long millis) throws InterruptedException {
      long until = System.nanoTime() + millis * 1_000_000L;
      while (active() && System.nanoTime() < until) {
         Thread.sleep(50L);
      }
   }

   public static long startedNanos() {
      return started;
   }
}
