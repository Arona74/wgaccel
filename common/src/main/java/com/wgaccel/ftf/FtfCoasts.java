package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * FreeTerraForged 1.0.0024's distance to a continent's closed coast (UpliftContinentGenerator.getBoundaryDistanceSq) from
 * segments gathered once per continent cell (config ftfFastContinents). For every column, from the terrain and from the
 * climate's land value, it gathers the closed Voronoi edges of its continent cell (or, when the cell is bridged to a
 * neighbour, of the 5 x 5 cells around it, asking for each cell and each edge's neighbour whether it is an ocean) and takes
 * the nearest: the 3 Nov 2026 profile (JFR, r1000) had it at 31% of all CPU, 13% in the ocean question alone. Which
 * segments it gathers depends on the continent cell alone (the cells' Voronoi polygons, bridges and skipping are the
 * generator's, fixed), and a continent cell spans thousands of blocks; here they are gathered the same way, in the same
 * order and with the same float arithmetic, once per cell, and each column only measures its distance to them. About 1
 * column in 4,096 is worked out by FreeTerraForged's own method too and compared; a difference turns this off.
 */
public final class FtfCoasts {
   private static final int BITS = 10;
   private static final int CHECK_EVERY = 4096;
   private static final LongAdder MEASURED = new LongAdder();
   private static final LongAdder GATHERED = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static volatile boolean off;
   private final AtomicReferenceArray<Segments> table = new AtomicReferenceArray<>(1 << BITS);

   /** A continent cell's closed coast segments: x1, y1, x2, y2 for each, in FreeTerraForged's order. */
   private record Segments(int cellX, int cellY, float[] points) {
   }

   /** What gathering needs from the generator (its own methods: the mixin). */
   public interface Generator {
      PlateUpliftBridge wgaccel$bridge();

      float wgaccel$bridgeChance();

      boolean wgaccel$canBridge(int cellX, int cellY);

      boolean wgaccel$shouldSkip(int cellX, int cellY);
   }

   public static boolean on() {
      return !off && Config.FTF_FAST_CONTINENTS.getAsBoolean();
   }

   /** Whether this call is one to work out FreeTerraForged's way too and compare. */
   public static boolean sample() {
      return java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0;
   }

   /** getBoundaryDistanceSq(x, y, cellX, cellY, ...) from the cell's segments. */
   public float distanceSq(Generator generator, float x, float y, int cellX, int cellY) {
      int slot = slot(cellX, cellY);
      Segments segments = this.table.getAcquire(slot);
      if (segments == null || segments.cellX != cellX || segments.cellY != cellY) {
         segments = new Segments(cellX, cellY, gather(generator, cellX, cellY));
         this.table.setRelease(slot, segments);
         GATHERED.increment();
      }

      MEASURED.increment();
      float[] p = segments.points;
      float bestSq = Float.MAX_VALUE;
      for (int i = 0; i < p.length; i += 4) {
         float dSq = distToSegmentSq(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]);
         if (dSq < bestSq) {
            bestSq = dSq;
         }
      }

      return bestSq == Float.MAX_VALUE ? 4.0F : bestSq;
   }

   /** A sampled call: FreeTerraForged's own answer against this one. */
   public static void check(float own, float ours, int cellX, int cellY) {
      CHECKED.increment();
      if (Float.floatToIntBits(own) != Float.floatToIntBits(ours) && !off) {
         off = true;
         WorldgenAccelerator.LOG.warn("WGA continents: coast distance in cell {}, {}: {} from FreeTerraForged, {} from the kept segments; off, every column works its"
            + " coast out again", cellX, cellY, own, ours);
      }
   }

   /** For the end-of-run line: "; continents: ..." once used, else nothing. */
   public static String summary() {
      if (MEASURED.sum() == 0L) {
         return "";
      }

      return String.format(java.util.Locale.ROOT, "; continents: %,d coast distances from %,d cells' kept segments, %,d checked%s", MEASURED.sum(), GATHERED.sum(),
         CHECKED.sum(), off ? " (a difference: off)" : "");
   }

   /** getBoundaryDistanceSq's segment gathering, as its own loops do it. */
   private static float[] gather(Generator generator, int cellX, int cellY) {
      PlateUpliftBridge bridge = generator.wgaccel$bridge();
      PlateUpliftBridge.CellVoronoi v = bridge.getVoronoi(cellX, cellY);
      boolean hasAnyBridge = false;
      if (generator.wgaccel$bridgeChance() > 0.0F && generator.wgaccel$canBridge(cellX, cellY)) {
         for (int k = 0; k < v.edgeCount; k++) {
            if (v.isBridged[k]) {
               hasAnyBridge = true;
               break;
            }
         }
      }

      float[] points = new float[64];
      int n = 0;
      if (!hasAnyBridge) {
         for (int k = 0; k < v.edgeCount; k++) {
            points = room(points, n);
            n = add(points, n, v, k);
         }

         return java.util.Arrays.copyOf(points, n);
      }

      for (int dy = -2; dy <= 2; dy++) {
         int cy = cellY + dy;
         for (int dx = -2; dx <= 2; dx++) {
            int cx = cellX + dx;
            if (generator.wgaccel$shouldSkip(cx, cy)) {
               continue;
            }

            PlateUpliftBridge.CellVoronoi cv = bridge.getVoronoi(cx, cy);
            for (int k = 0; k < cv.edgeCount; k++) {
               if (cv.isBridged[k]) {
                  continue;
               }

               int ncx = cv.ncx[k];
               int ncy = cv.ncy[k];
               if (!generator.wgaccel$shouldSkip(ncx, ncy) && (ncx < cx || ncx == cx && ncy < cy) && Math.abs(ncx - cellX) <= 2 && Math.abs(ncy - cellY) <= 2) {
                  continue;
               }

               points = room(points, n);
               n = add(points, n, cv, k);
            }
         }
      }

      return java.util.Arrays.copyOf(points, n);
   }

   private static float[] room(float[] points, int n) {
      return n + 4 <= points.length ? points : java.util.Arrays.copyOf(points, points.length * 2);
   }

   private static int add(float[] points, int n, PlateUpliftBridge.CellVoronoi v, int k) {
      points[n] = v.mx[k] + v.lo[k] * v.tx[k];
      points[n + 1] = v.my[k] + v.lo[k] * v.ty[k];
      points[n + 2] = v.mx[k] + v.hi[k] * v.tx[k];
      points[n + 3] = v.my[k] + v.hi[k] * v.ty[k];
      return n + 4;
   }

   /** UpliftContinentGenerator.distToSegmentSq (its NoiseUtil.clamp: below the low end the low end, above the high end the high end). */
   private static float distToSegmentSq(float px, float py, float ax, float ay, float bx, float by) {
      float dx = bx - ax, dy = by - ay;
      float l2 = dx * dx + dy * dy;
      float t = l2 < 1.0e-12F ? 0.0F : clamp(((px - ax) * dx + (py - ay) * dy) / l2);
      float ex = px - (ax + t * dx), ey = py - (ay + t * dy);
      return ex * ex + ey * ey;
   }

   private static float clamp(float value) {
      return etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil.clamp(value, 0.0F, 1.0F);
   }

   private static int slot(int cellX, int cellY) {
      long h = (cellX * 0x9E3779B97F4A7C15L ^ cellY * 0xC2B2AE3D27D4EB4FL) * 0x165667B19E3779F9L;
      return (int)(h >>> 64 - BITS);
   }
}
