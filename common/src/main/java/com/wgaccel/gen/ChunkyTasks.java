package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.reflect.Method;

/**
 * Chunky's public API (org.popcraft.chunky.api.ChunkyAPI, from ChunkyProvider.get().getApi()), by reflection: nothing
 * of Chunky is compiled in. Used by the Voxy LOD generator to generate the real chunks near a player once the LODs
 * around them are done (config voxyRealChunks). Every call returns false when Chunky is not there or its API differs.
 */
final class ChunkyTasks {
   private static volatile boolean missing;
   private static volatile boolean warned;

   private ChunkyTasks() {
   }

   private static Object api() {
      if (missing) {
         return null;
      }

      try {
         Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
         Object chunky = provider.getMethod("get").invoke(null);
         return chunky == null ? null : Class.forName("org.popcraft.chunky.Chunky", false, provider.getClassLoader()).getMethod("getApi").invoke(chunky);
      } catch (ClassNotFoundException absent) {
         missing = true;
         return null;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         if (!warned) {
            warned = true;
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: Chunky's API not usable ({}); no real chunks", failure.toString());
         }

         return null;
      }
   }

   static boolean installed() {
      return api() != null;
   }

   /** Whether a Chunky task runs in this world ("minecraft:overworld"). */
   static boolean running(String world) {
      Object api = api();
      if (api == null) {
         return false;
      }

      try {
         Method method = Class.forName("org.popcraft.chunky.api.ChunkyAPI", false, api.getClass().getClassLoader()).getMethod("isRunning", String.class);
         return (Boolean)method.invoke(api, world);
      } catch (ReflectiveOperationException | RuntimeException failure) {
         return false;
      }
   }

   /** A square task around (x, z) in blocks, radius in blocks, nearest first; false when not started (one already runs). */
   static boolean start(String world, double x, double z, double radius) {
      Object api = api();
      if (api == null) {
         return false;
      }

      try {
         Method method = Class.forName("org.popcraft.chunky.api.ChunkyAPI", false, api.getClass().getClassLoader()).getMethod("startTask", String.class, String.class, double.class, double.class, double.class, double.class,
            String.class);
         return (Boolean)method.invoke(api, world, "square", x, z, radius, radius, "concentric");
      } catch (ReflectiveOperationException | RuntimeException failure) {
         if (!warned) {
            warned = true;
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: Chunky's startTask failed ({}); no real chunks", failure.toString());
         }

         return false;
      }
   }
}
