package com.wgaccel.mixin.ftf;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** T1: the block column FreeTerraForged's heightmap is working on, for the populators' GPU lookups. */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap")
public abstract class TileNoiseHeightmapMixin {
   @Inject(method = "applyTerrain(Letcodehome/freeterraforged/world/worldgen/cell/Cell;FF)V", at = @At("HEAD"))
   private void wgaccel$enter(Cell cell, float x, float z, CallbackInfo info) {
      com.wgaccel.gen.TileNoise.enter(x, z);
   }

   @Inject(method = "applyTerrain(Letcodehome/freeterraforged/world/worldgen/cell/Cell;FF)V", at = @At("RETURN"))
   private void wgaccel$exit(Cell cell, float x, float z, CallbackInfo info) {
      com.wgaccel.gen.TileNoise.exit();
   }
}
