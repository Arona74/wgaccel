package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.ftf.FtfRiverSkip;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.Range;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.RiverConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * FreeTerraForged 1.0.0027+'s river carving: honors {@code maxZone4RadiusSq} early exit before {@code updateValleyMask}
 * and bounds mountain vertical fadeout via {@link FtfRiverSkip#bound27}.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.UpliftRiverCarver")
public abstract class RiverSkip27Mixin {
   @Shadow
   public RiverConfig config;
   @Shadow
   public LakeConfig lakeConfig;
   @Shadow
   private float fadeInv;
   @Shadow
   private Range bedWidth;
   @Shadow
   private Levels levels;
   @Shadow
   @Final
   private float zone2WidthFactor;
   @Shadow
   @Final
   private float maxZone4Radius;
   @Shadow
   @Final
   private float maxZone4RadiusSq;

   @Shadow
   private float getDistance2(float x, float y, float t) {
      throw new AssertionError();
   }

   @Shadow
   private float getScaledSize(float t, Range range) {
      throw new AssertionError();
   }

   @Shadow
   private void updateValleyMask(float currT, float distSqToCurr, float sqScaleFactor, Cell cell) {
      throw new AssertionError();
   }

   @WrapMethod(method = "carve")
   private void wgaccel$farRiver(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT, Operation<Void> original) {
      if (!FtfRiverSkip.on()) {
         original.call(cell, prevX, prevZ, prevT, currX, currZ, currT);
         return;
      }

      float distSqToCurr = this.getDistance2(currX, currZ, currT);
      if (distSqToCurr > this.maxZone4RadiusSq) {
         FtfRiverSkip.skipped();
         return;
      }

      float distance = (float)Math.sqrt(distSqToCurr);
      float bound = FtfRiverSkip.bound27(cell, currT, this.getScaledSize(currT, this.bedWidth), this.fadeInv, this.zone2WidthFactor,
         this.config.bankWidth, this.lakeConfig, this.levels, this.maxZone4Radius);
      if (!(distance >= bound)) {
         FtfRiverSkip.CARVED.increment();
         original.call(cell, prevX, prevZ, prevT, currX, currZ, currT);
         return;
      }

      if (FtfRiverSkip.sample()) {
         Cell expected = new Cell();
         expected.copyFrom(cell);
         this.updateValleyMask(currT, distSqToCurr, 1.0F, expected);
         original.call(cell, prevX, prevZ, prevT, currX, currZ, currT);
         FtfRiverSkip.check(cell, expected);
         return;
      }

      this.updateValleyMask(currT, distSqToCurr, 1.0F, cell);
      FtfRiverSkip.skipped();
   }
}
