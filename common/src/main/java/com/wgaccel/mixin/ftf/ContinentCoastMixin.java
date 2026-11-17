package com.wgaccel.mixin.ftf;

import com.wgaccel.ftf.FtfCoasts;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.PlateUpliftBridge;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FreeTerraForged 1.0.0024's coast distance from segments kept per continent cell ({@link FtfCoasts}): the
 * getBoundaryDistanceSq calls of apply (the terrain) and getEdgeValue (the climate's land value). Redirects, not
 * wrappers: no object per call. Applied with 1.0.0024 and later only (FtfMixinPlugin).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator")
public abstract class ContinentCoastMixin implements FtfCoasts.Generator {
   @Unique
   private final FtfCoasts wgaccel$coasts = new FtfCoasts();
   @Shadow
   protected PlateUpliftBridge plateUpliftBridge;
   @Shadow
   protected volatile float bridgeChance;

   @Shadow
   protected abstract boolean shouldSkip(int cellX, int cellY);

   @Shadow
   public abstract boolean canBridge(int cellX, int cellY);

   @Shadow
   abstract float getBoundaryDistanceSq(float x, float y, int cellX, int cellY, float ax, float ay);

   @Override
   public PlateUpliftBridge wgaccel$bridge() {
      return this.plateUpliftBridge;
   }

   @Override
   public float wgaccel$bridgeChance() {
      return this.bridgeChance;
   }

   @Override
   public boolean wgaccel$canBridge(int cellX, int cellY) {
      return this.canBridge(cellX, cellY);
   }

   @Override
   public boolean wgaccel$shouldSkip(int cellX, int cellY) {
      return this.shouldSkip(cellX, cellY);
   }

   @Redirect(method = {"apply", "getEdgeValue"}, at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/cell/continent/uplift/UpliftContinentGenerator;getBoundaryDistanceSq(FFIIFF)F"), require = 0)
   private float wgaccel$coast(UpliftContinentGenerator self, float x, float y, int cellX, int cellY, float ax, float ay) {
      if (!FtfCoasts.on()) {
         return this.getBoundaryDistanceSq(x, y, cellX, cellY, ax, ay);
      }

      float ours = this.wgaccel$coasts.distanceSq(this, x, y, cellX, cellY);
      if (FtfCoasts.sample()) {
         FtfCoasts.check(this.getBoundaryDistanceSq(x, y, cellX, cellY, ax, ay), ours, cellX, cellY);
      }

      return ours;
   }
}
