package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * FreeTerraForged's river carving without the far rivers' noise (config ftfFastRivers; the same in 1.0.0010 and
 * 1.0.0013). UpliftRiverCarver.carve runs for every column against every river of a network's tree, and before its own
 * early exit (the column beyond the river's outer radius, zone 4) it always samples the width, asymmetry and valley-pinch
 * noises and the lake widening (another noise), because the radius depends on them: the 23 Oct 2026 profile (JFR, r1000,
 * 1.0.0013) had those noises at ~70% of the rivers' time, ~9% of all CPU, nearly all for rivers that then leave the
 * column alone.
 * <p>
 * Here an upper bound of that radius is worked out without the noises: FreeTerraForged's simplex noises are clamped to
 * [0, 1], so the width factor is at most 1.35, the side bias 1.4, the valley pinch 1.95 (its own clamp), the lake
 * widening 1 + 1.45 x the larger lake size; the rest (the bed's size along the river, the fade, the height discrepancy
 * from the cell's own fields) is FreeTerraForged's own arithmetic. Every step multiplies or adds non-negative numbers
 * (anything else and the river is carved as usual), which rounding cannot turn around, and a margin is added. A column at
 * or past the bound is past the real radius, so only the valley mask, which carve updates before its exit, is done.
 * About 1 such skip in 2,048 runs carve anyway and compares the cell, field by field, with what the skip leaves; a difference
 * turns this off.
 */
public final class FtfRiverSkip {
   /**
    * About 1 skip in this many is checked, picked by each thread's own random numbers: a shared counter (0.17.3's first
    * try, with a summary check summing every thread's counts after each skip) was 45% of the rivers' remaining time.
    */
   private static final int CHECK_EVERY = 2048;
   /** About 1 skip in this many looks whether a summary is due. */
   private static final int REPORT_EVERY = 65536;
   private static final float WIDTH_MAX = 1.0F + 1.0F * 0.35F;
   private static final float SIDE_MAX = 1.0F + 1.0F * 0.4F;
   private static final float PINCH_MAX = 1.95F;
   public static final LongAdder SKIPPED = new LongAdder();
   public static final LongAdder CARVED = new LongAdder();
   public static final LongAdder CHECKED = new LongAdder();
   private static final AtomicInteger NEXT_REPORT = new AtomicInteger(1 << 20);
   private static volatile boolean off;

   private FtfRiverSkip() {
   }

   public static boolean on() {
      return !off && Config.FTF_FAST_RIVERS.getAsBoolean();
   }

   /** Whether this skip is one to check instead (carve runs and {@link #check} compares). */
   public static boolean sample() {
      return java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0;
   }

   /**
   * At least UpliftRiverCarver.carve's zone 4 radius for this cell and position along the river (scaledBed: its
   * getScaledSize(currT, bedWidth)); +infinity when a bound cannot be had this way.
   */
   public static float bound(Cell cell, float currT, float scaledBed, float fadeInv, float zone2WidthFactor, int bankWidth, LakeConfig lake, Levels levels) {
      if (zone2WidthFactor < 0.0F || bankWidth < 0 || scaledBed < 0.0F) {
         return Float.POSITIVE_INFINITY;
      }

      float biased = 1.0F * WIDTH_MAX * SIDE_MAX;
      float lakeMax = 1.0F;
      if (lake != null) {
         float largest = Math.max(lake.sizeMin / 100.0F, lake.sizeMax / 100.0F);
         lakeMax = Math.max(1.0F, 1.0F + largest * 1.45F);
      }

      float zone1 = (float)Math.sqrt(scaledBed * biased) * lakeMax;
      float zone2 = zone1 + zone2WidthFactor * biased;
      float unshrunk = bankWidth * WIDTH_MAX * PINCH_MAX;
      float shrink = Math.max(0.0F, Math.min(1.0F, currT * fadeInv));
      float zone3 = zone2 + unshrunk * shrink * shrink;
      float target = ContinentalHydrology.getComplexWaterHeight(cell.waterTable, cell.globalContinentScale, cell.continentSizeModifier) + levels.water;
      float discrepancy = 1.0F + levels.scale(cell.height - target) / 100.0F;
      float fadeOut = 4.0F + discrepancy;
      if (!(fadeOut >= 0.0F)) {
         return Float.POSITIVE_INFINITY;
      }

      return (zone3 + unshrunk * fadeOut) * 1.001F + 1.0F;
   }

   /** A checked skip: carve ran on cell; expected is what the skip would have left. */
   public static void check(Cell cell, Cell expected) {
      CHECKED.increment();
      String difference = FtfChunkCells.compare(new Cell[] {expected}, new Cell[] {cell});
      if (difference != null) {
         off = true;
         com.wgaccel.WgaChat.warnOff("WGA fast FTF rivers off: a far river changed the cell after all ({})", difference);
      }
   }

   public static void skipped() {
      SKIPPED.increment();
      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(REPORT_EVERY) == 0 && Due.due(NEXT_REPORT, SKIPPED.sum(), 4)) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      long all = Math.max(1, SKIPPED.sum() + CARVED.sum());
      return String.format(Locale.ROOT, "WGA fast FTF rivers: %,d far rivers skipped (%.0f%% of the carves), checked %,d: %s", SKIPPED.sum(),
         100.0 * SKIPPED.sum() / all, CHECKED.sum(), off ? "DIFFER (off)" : "IDENTICAL");
   }
}
