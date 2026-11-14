package com.wgaccel.ftf;

/** What the installed FreeTerraForged's version changes for the ports that copy its code. */
public final class FtfVersion {
   /**
    * FreeTerraForged 1.0.0025 and later: a cell's erosion comes from the climate (ClimateModule's own erosion noise), not
    * from the terrain populators, whose erosion noise is no longer worked out (TerrainPopulator.apply leaves cell.erosion
    * alone); and the chunk grids' erosion filter ends by setting each cell's weirdness from its sediment (sediment -0.5 and
    * weirdness 0 where masked or at ground level or below).
    */
   public static final boolean CLIMATE_EROSION = atLeast("1.0.0025");
   /**
    * FreeTerraForged 1.0.0026 and later: the chunk grids' erosion filter leaves cells at ground level or below alone, still
    * updates the sediment where the modifier is 0 or less (only the carve is skipped), counts river, lake and wetland
    * cells at or below their water level as water in its last step, and ReferenceWeirdness.fromSediment's magnitude is at
    * least 0.055; UpliftRiverCarver.updateValleyMask takes only the distance to the current point.
    */
   public static final boolean GRID_WATER = atLeast("1.0.0026");

   private FtfVersion() {
   }

   /** Whether the installed FreeTerraForged is this version or later (false without one, or outside the game). */
   public static boolean atLeast(String version) {
      try {
         return com.wgaccel.platform.Platform.get().modAtLeast("freeterraforged", version);
      } catch (RuntimeException | LinkageError outsideTheGame) {
         return false;
      }
   }
}
