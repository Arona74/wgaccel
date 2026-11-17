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
 * FreeTerraForged 1.0.0026's river carving: far rivers skipped before their noise, only their valley mask done
 * ({@link FtfRiverSkip}). {@link RiverSkipMixin} with 1.0.0026's updateValleyMask, which takes only the distance to the
 * current point (FtfMixinPlugin applies one or the other).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.UpliftRiverCarver")
public abstract class RiverSkip26Mixin {
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

      // carve's own first steps: the distance to the river at the warped point, then (before its exit) the valley mask.
      float distSqToCurr = this.getDistance2(currX, currZ, currT);
      float distance = (float)Math.sqrt(distSqToCurr);
      float bound = FtfRiverSkip.bound(cell, currT, this.getScaledSize(currT, this.bedWidth), this.fadeInv, this.zone2WidthFactor, this.config.bankWidth,
         this.lakeConfig, this.levels);
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
