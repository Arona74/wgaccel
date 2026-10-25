package com.wgaccel.mixin.ftf;

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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FreeTerraForged's river carving: far rivers skipped before their noise, only their valley mask done ({@link FtfRiverSkip}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.rivermap.river.UpliftRiverCarver")
public abstract class RiverSkipMixin {
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

   /** A checked skip in progress on this thread: the carver and what the skip would have left. */
   @Unique
   private static final ThreadLocal<Object[]> wgaccel$checking = new ThreadLocal<>();

   @Shadow
   private float getDistance2(float x, float y, float t) {
      throw new AssertionError();
   }

   @Shadow
   private float getScaledSize(float t, Range range) {
      throw new AssertionError();
   }

   @Shadow
   private void updateValleyMask(float prevX, float prevZ, float prevT, float currT, float distSqToCurr, float sqScaleFactor, Cell cell) {
      throw new AssertionError();
   }

   @Inject(method = "carve", at = @At("HEAD"), cancellable = true)
   private void wgaccel$farRiver(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT, CallbackInfo info) {
      if (!FtfRiverSkip.on()) {
         return;
      }

      // carve's own first steps: the distance to the river at the warped point, then (before its exit) the valley mask.
      float distSqToCurr = this.getDistance2(currX, currZ, currT);
      float distance = (float)Math.sqrt(distSqToCurr);
      float bound = FtfRiverSkip.bound(cell, currT, this.getScaledSize(currT, this.bedWidth), this.fadeInv, this.zone2WidthFactor, this.config.bankWidth,
         this.lakeConfig, this.levels);
      if (!(distance >= bound)) {
         FtfRiverSkip.CARVED.increment();
         return;
      }

      if (FtfRiverSkip.sample()) {
         // Checked: carve runs; what the skip would leave is compared at its return.
         Cell expected = new Cell();
         expected.copyFrom(cell);
         this.updateValleyMask(prevX, prevZ, prevT, currT, distSqToCurr, 1.0F, expected);
         wgaccel$checking.set(new Object[] {this, expected});
         return;
      }

      this.updateValleyMask(prevX, prevZ, prevT, currT, distSqToCurr, 1.0F, cell);
      FtfRiverSkip.skipped();
      info.cancel();
   }

   @Inject(method = "carve", at = @At("RETURN"))
   private void wgaccel$checkFarRiver(Cell cell, float prevX, float prevZ, float prevT, float currX, float currZ, float currT, CallbackInfo info) {
      Object[] checking = wgaccel$checking.get();
      if (checking != null && checking[0] == this) {
         wgaccel$checking.remove();
         FtfRiverSkip.check(cell, (Cell)checking[1]);
      }
   }
}
