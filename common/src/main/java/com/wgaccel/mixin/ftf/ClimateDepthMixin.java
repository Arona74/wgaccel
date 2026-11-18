package com.wgaccel.mixin.ftf;

import com.wgaccel.ftf.FtfDepthFunction;
import com.wgaccel.gen.ClimateColumns;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Exposes FreeTerraForged's {@code ClimateDepthFunction} bounds and captures the computed {@code surfaceY} during a
 * column miss so {@link ClimateColumns} can keep a chunk's 16 columns across the z-inner quart loop.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.ClimateDepthFunction")
public abstract class ClimateDepthMixin implements FtfDepthFunction {
   @Shadow
   @Final
   private int worldDepth;

   @Shadow
   @Final
   private int worldHeight;

   @Override
   public int wgaccel$worldDepth() {
      return this.worldDepth;
   }

   @Override
   public int wgaccel$worldHeight() {
      return this.worldHeight;
   }

   @Redirect(method = "compute", require = 0, at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/cell/heightmap/Levels;scale(F)I"))
   private int wgaccel$captureSurfaceY(Levels levels, float height) {
      int surfaceY = levels.scale(height);
      ClimateColumns.recordPendingDepth(this, surfaceY);
      return surfaceY;
   }
}
