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
 *   chunk objects has another static field, and ours stays off; so does one whose generateChunkCells also fills the
 *   surface envelope's cache (BiomeSurfaceEnvelope.prepopulateFromChunkGrid), which kept cells would skip;</li>
 *   <li>the surface envelope ({@link FtfSurfaceEnvelope}): BiomeSurfaceEnvelope's cache ("values") is an OwnerThreadCache
 *   (one per thread, so the biome look-ahead and the chunk's own biome step work every value out twice); any other cache
 *   type, an OwnerThreadCache with a table shared by all threads, or a cache FreeTerraForged fills ahead
 *   (prepopulateFromChunkGrid, 1.0.0013's later builds) and ours stays off.</li>
 * </ul>
 */
public final class FtfSlowPaths {
   private static volatile Boolean cells;
   /** generateChunkCells fills the surface envelope's cache: only our stand-ins may share cells ({@link #standInCells}). */
   private static volatile boolean cellsFillEnvelope;
   private static volatile Boolean envelope;

   private FtfSlowPaths() {
   }

   /**
    * How many threads a look-ahead runs: usual, or 6 with FreeTerraForged's per-chunk cells (no tiles), whose stand-ins
    * make the cells for the chunk too (the 2026-10-22 08:59 run: 5 look-ahead threads, the generation threads waiting
    * 1.7 s per area for them, the CPU 15 of 24 busy).
    */
   public static int lookAheadThreads(int usual) {
      // Whenever stand-ins make chunk grids (1.0.0013), shared or not: from 0.17.0 the sharing was off on builds whose
      // grids fill the surface envelope, and the look-aheads fell back to 3 and 2 threads; the 23 Oct 2026 run then had
      // 2,661 chunks' biomes not ready in time (19%, each a whole CPU biome step, ~18 ms, against ~6 ms for a stand-in).
      return standInCells() ? Math.max(usual, 6) : usual;
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

            // FreeTerraForged 1.0.0013's later builds: generateChunkCells also fills the surface envelope's cache from the
            // chunk's grid (the full 10 x 10 minimum, where an answer made on demand samples every other block and can be
            // a block higher). Handing out kept cells skips that, so more quarts would get the sampled answer than with
            // FreeTerraForged alone (the user, 22 Oct 2026: the world stays FreeTerraForged's own; ours stays off).
            Class<?> envelope = Class.forName("etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope");
            for (java.lang.reflect.Method method : envelope.getDeclaredMethods()) {
               if (method.getName().equals("prepopulateFromChunkGrid")) {
                  cellsFillEnvelope = true;
                  return "generateChunkCells also fills the surface envelope's cache; only our look-aheads' stand-ins share cells";
               }
            }

            return null;
         });
         cells = value;
      }

      return value;
   }

   /**
    * Whether our look-aheads' stand-ins may share chunk cells among themselves: always when {@link #chunkCells} applies,
    * and also when it is off only because generateChunkCells fills the surface envelope's cache. The real chunk then
    * makes its own cells through FreeTerraForged's call (so its cache is filled as with FreeTerraForged alone); only the
    * fill and biome look-aheads' second stand-in of a chunk gets the first one's (the 23 Oct 2026 profile: of 54,186
    * grids, 37,662 were our stand-ins', 1.15 per chunk from the fill look-ahead and 1.25 from the biome look-ahead).
    */
   public static boolean standInCells() {
      return chunkCells() || cellsFillEnvelope;
   }

   public static boolean surfaceEnvelope() {
      Boolean value = envelope;
      if (value == null) {
         if (!ftfPresent()) {
            envelope = false;
            return false;
         }

         synchronized (FtfSlowPaths.class) {
            // Once, not once per thread (the 22 Oct 2026 run logged it from 11 threads at once).
            value = envelope;
            if (value != null) {
               return value;
            }

            value = check("surface envelope", () -> {
               Class<?> type = Class.forName("etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope");
               Field values = type.getDeclaredField("values");
               if (!values.getType().getSimpleName().equals("OwnerThreadCache")) {
                  return "its cache is a " + values.getType().getSimpleName();
               }

               // FreeTerraForged 1.0.0013's later builds: the cache has a table all threads share (what ours did), and
               // ChunkFilterGrid fills it ahead from the chunk's grid (the full 10 x 10 minimum, where minimumSurfaceY
               // now samples every other block), so a quart's answer depends on which came first; ours then found 199
               // where FreeTerraForged's own gave 200 (22 Oct 2026, RX 9070 XT). Either way ours stays off.
               for (Field field : values.getType().getDeclaredFields()) {
                  if (field.getName().equals("shared")) {
                     return "its cache is shared by all threads already";
                  }
               }

               for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                  if (method.getName().equals("prepopulateFromChunkGrid")) {
                     return "it fills its cache ahead from the chunk grids";
                  }
               }

               return null;
            });
            envelope = value;
         }
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
