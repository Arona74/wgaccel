package com.wgaccel.gen;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Direct indexed hooks implemented on {@code NoiseChunk.NoiseInterpolator} and {@code NoiseChunk.CacheAllInCell} via
 * Mixin so {@code NoiseChunk} can iterate cached arrays instead of allocating capturing {@code Consumer} lambdas on
 * every block and cell during CPU worldgen.
 */
public interface FastNoiseInterpolator {
   void wgaccel$selectCellYZ(int cellY, int cellZ);

   void wgaccel$updateForY(double deltaY);

   void wgaccel$updateForX(double deltaX);

   void wgaccel$updateForZ(double deltaZ);

   interface CellCache {
      void wgaccel$fill(DensityFunction.ContextProvider provider);
   }
}
