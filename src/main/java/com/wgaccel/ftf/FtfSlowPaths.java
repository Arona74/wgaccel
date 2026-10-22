package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Our fixes for FreeTerraForged's slow paths apply only while FreeTerraForged still has them (the user, 2026-10-22: "should
 * automatically turn off once ftf updates with a fix"). Checked once, from FreeTerraForged's own classes:
 * <ul>
 *   <li>chunk cells ({@link FtfChunkCells}): ChunkFilterGrid.generateChunkCells exists and ChunkFilterGrid keeps no cache of
 *   its own (its only static field is the thread's working grid, THREAD_LOCAL): a fixed version that keeps cells across
 *   chunk objects has another static field, and ours stays off;</li>
 *   <li>the surface envelope ({@link FtfSurfaceEnvelope}): BiomeSurfaceEnvelope's cache ("values") is an OwnerThreadCache
 *   (one per thread, so the biome look-ahead and the chunk's own biome step work every value out twice); any other cache
 *   type and ours stays off.</li>
 * </ul>
 */
public final class FtfSlowPaths {
   private static volatile Boolean cells;
   private static volatile Boolean envelope;

   private FtfSlowPaths() {
   }

   /**
    * How many threads a look-ahead runs: usual, or 6 with FreeTerraForged's per-chunk cells (no tiles), whose stand-ins
    * make the cells for the chunk too (the 2026-10-22 08:59 run: 5 look-ahead threads, the generation threads waiting
    * 1.7 s per area for them, the CPU 15 of 24 busy).
    */
   public static int lookAheadThreads(int usual) {
      return chunkCells() && com.wgaccel.Config.FTF_SHARED_CHUNK_CELLS.get() ? Math.max(usual, 6) : usual;
   }

   public static boolean chunkCells() {
      Boolean value = cells;
      if (value == null) {
         if (!ftfPresent()) {
            cells = false;
            return false;
         }

         value = check("chunk cells", () -> {
            Class<?> grid = Class.forName("etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid");
            boolean generate = false;
            for (java.lang.reflect.Method method : grid.getDeclaredMethods()) {
               generate |= method.getName().equals("generateChunkCells");
            }

            if (!generate) {
               return "no ChunkFilterGrid.generateChunkCells";
            }

            for (Field field : grid.getDeclaredFields()) {
               if (Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive() && !field.getName().equals("THREAD_LOCAL")) {
                  return "ChunkFilterGrid has a cache of its own (" + field.getName() + ")";
               }
            }

            return null;
         });
         cells = value;
      }

      return value;
   }

   public static boolean surfaceEnvelope() {
      Boolean value = envelope;
      if (value == null) {
         if (!ftfPresent()) {
            envelope = false;
            return false;
         }

         value = check("surface envelope", () -> {
            Class<?> type = Class.forName("etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope");
            Field values = type.getDeclaredField("values");
            return values.getType().getSimpleName().equals("OwnerThreadCache") ? null : "its cache is a " + values.getType().getSimpleName();
         });
         envelope = value;
      }

      return value;
   }

   private static boolean ftfPresent() {
      try {
         return net.neoforged.fml.ModList.get().isLoaded("freeterraforged");
      } catch (Throwable failure) {
         return false;
      }
   }

   private interface Check {
      /** Null: the slow path is there (our fix applies); else why not. */
      String run() throws Exception;
   }

   private static boolean check(String name, Check check) {
      String reason;
      try {
         reason = check.run();
      } catch (Throwable failure) {
         reason = failure.toString();
      }

      if (reason == null) {
         WorldgenAccelerator.LOG.info("WGA FreeTerraForged {}: the slow path is there; our fix is on", name);
         return true;
      }

      WorldgenAccelerator.LOG.info("WGA FreeTerraForged {}: our fix is off ({}; FreeTerraForged has fixed it or changed)", name, reason);
      return false;
   }
}
