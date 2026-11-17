package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** T1: a TerrainPopulator's four noise values from the tile's GPU table when they are there (the same results, checked). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator")
public abstract class TileNoisePopulatorMixin {
   @WrapMethod(method = "apply(Letcodehome/freeterraforged/world/worldgen/cell/Cell;FF)V")
   private void wgaccel$fromGpu(Cell cell, float x, float z, Operation<Void> original) {
      TerrainPopulator self = (TerrainPopulator)(Object)this;
      if (com.wgaccel.gen.TileNoise.apply(self, cell, x, z, self.type(), self.baseScale(), self.heightScale())) {
         com.wgaccel.gen.TileNoise.after(this, cell);
         return;
      }
      try {
         original.call(cell, x, z);
      } finally {
         com.wgaccel.gen.TileNoise.after(this, cell);
      }
   }
}
