package com.wgaccel.mixin.ftf;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** T1: a TerrainPopulator's four noise values from the tile's GPU table when they are there (the same results, checked). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator")
public abstract class TileNoisePopulatorMixin {
   @Inject(method = "apply(Letcodehome/freeterraforged/world/worldgen/cell/Cell;FF)V", at = @At("HEAD"), cancellable = true)
   private void wgaccel$fromGpu(Cell cell, float x, float z, CallbackInfo info) {
      TerrainPopulator self = (TerrainPopulator)(Object)this;
      if (com.wgaccel.gen.TileNoise.apply(self, cell, x, z, self.type(), self.baseScale(), self.heightScale())) {
         info.cancel();
      }
   }

   @Inject(method = "apply(Letcodehome/freeterraforged/world/worldgen/cell/Cell;FF)V", at = @At("RETURN"))
   private void wgaccel$check(Cell cell, float x, float z, CallbackInfo info) {
      com.wgaccel.gen.TileNoise.after(this, cell);
   }
}
