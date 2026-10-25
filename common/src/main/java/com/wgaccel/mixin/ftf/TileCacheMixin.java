package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.Config;
import com.llamalad7.mixinextras.sugar.Local;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.ftf.NoiseInventory;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Plan item B3: a bigger cache for FreeTerraForged's terrain tiles (config {@code tileCacheTiles}). FTF keeps at most
 * about a million columns (4 to 32 tiles) and drops the oldest tile on every new one, so a pregeneration front wider
 * than that makes most tiles two or three times. The tiles themselves are unchanged.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.tile.TileCache")
public abstract class TileCacheMixin {
   @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/densityfunction/tile/TileCache;cacheCapacity(I)I"))
   private int wgaccel$capacity(int cellsPerTile, Operation<Integer> original, @Local(argsOnly = true) TileGenerator generator) {
      NoiseInventory.log(generator == null ? null : generator.getHeightmap());
      int own = original.call(cellsPerTile);
      int wanted = Config.TILE_CACHE_TILES.getAsInt();
      int capacity = wanted > 0 ? Math.max(own, wanted) : own;
      WorldgenAccelerator.LOG.info("WGA FreeTerraForged tile cache: {} tiles ({} columns each; FreeTerraForged's own size {})",
         capacity, cellsPerTile, own);
      return capacity;
   }
}
