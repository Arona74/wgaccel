package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Voxy World Gen V2 (VWG) seen from the Voxy LOD generator, by reflection: nothing of VWG is compiled in, called beyond
 * its settings, or changed on disk.
 * <ul>
 *   <li>The player's own settings: config/voxyworldgenv2.json (enabled, generationRadius, maxActiveTasks), only read.
 *   <li>Holding VWG back while our LODs inside its radius are made, then letting it run at a few tasks: its runtime
 *   settings (com.ethan.voxyworldgenv2.core.Config.DATA, public: the fields an op's config push sets through its
 *   applyServerConfig; its worker checks enabled every 100 ms). Its file is never written; {@link #restore} puts the
 *   player's own values back.
 * </ul>
 */
final class VwgControl {
   /** The player's own settings (from the file). */
   record Own(boolean enabled, int radius, int tasks) {
   }

   private static volatile boolean broken;
   private Own own;
   private long readAt;
   private boolean changed;

   static boolean installed() {
      return com.wgaccel.platform.Platform.get().isModLoaded("voxyworldgenv2");
   }

   /** The player's settings, read from the file at most every 30 s; null without VWG. */
   Own own() {
      if (!installed()) {
         return null;
      }

      long now = System.nanoTime();
      if (this.own != null && now - this.readAt < 30_000_000_000L) {
         return this.own;
      }

      this.readAt = now;
      Path file = com.wgaccel.platform.Platform.get().configDir().resolve("voxyworldgenv2.json");
      boolean enabled = true;
      int radius = 128;
      int tasks = 20;
      try {
         com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
         enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
         radius = json.has("generationRadius") ? json.get("generationRadius").getAsInt() : radius;
         tasks = json.has("maxActiveTasks") ? json.get("maxActiveTasks").getAsInt() : tasks;
      } catch (java.io.IOException | RuntimeException unreadable) {
         // VWG's own defaults.
      }

      this.own = new Own(enabled, Math.max(0, radius), Math.max(1, tasks));
      return this.own;
   }

   /** Sets VWG's runtime enabled and task count (only when they differ); false when its settings cannot be reached. */
   boolean apply(boolean enabled, int tasks) {
      if (broken) {
         return false;
      }

      try {
         Object data = data();
         Field enabledField = data.getClass().getField("enabled");
         Field tasksField = data.getClass().getField("maxActiveTasks");
         if (enabledField.getBoolean(data) != enabled) {
            enabledField.setBoolean(data, enabled);
         }

         if (tasksField.getInt(data) != tasks) {
            tasksField.setInt(data, tasks);
         }

         this.changed = true;
         return true;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: Voxy World Gen V2's settings not reachable ({}); it is left alone", failure.toString());
         return false;
      }
   }

   /** The player's own enabled and task count back (after we changed them). */
   void restore() {
      Own own = this.own;
      if (this.changed && own != null) {
         this.apply(own.enabled(), own.tasks());
         this.changed = false;
      }
   }

   private static Object data() throws ReflectiveOperationException {
      Class<?> config = Class.forName("com.ethan.voxyworldgenv2.core.Config");
      return config.getField("DATA").get(null);
   }
}
