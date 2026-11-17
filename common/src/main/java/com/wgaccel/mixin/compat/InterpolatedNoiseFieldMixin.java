package com.wgaccel.mixin.compat;

import com.farcr.nomansland.common.world.NoiseFieldFiller;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import java.util.BitSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * No Man's Land 1.6.2's InterpolatedNoiseField.fill (its ore veins' three noise fields, every chunk) fills each run of
 * missing layers [start, end), then goes on from start + 1, still in the run: it fills [start + 1, end), [start + 2,
 * end) and so on, about n * n / 2 layers for n (30 of the veins' 37 ms a chunk in FreeTerraForged's 768-block world).
 * Its fillers are noises, the same value at the same place whatever the order, so filling each run once leaves the same
 * field (config {@code fastLayerPlacement}, the CPU fixes of other mods' features).
 */
@Pseudo
@Mixin(targets = "com.farcr.nomansland.common.world.InterpolatedNoiseField")
public abstract class InterpolatedNoiseFieldMixin {
   @Shadow
   @Final
   public int yCellScale;
   @Shadow
   @Final
   public int yCellCount;
   @Shadow
   @Final
   BitSet filledLayers;
   @Shadow
   @Final
   BitSet fillMask;

   @Shadow
   public abstract void fillInternal(int startCellY, int endCellY, int minX, int minY, int minZ, NoiseFieldFiller filler);

   @WrapMethod(method = "fill")
   private void wgaccel$fillOnce(int startY, int endY, int minX, int minY, int minZ, NoiseFieldFiller filler, Operation<Void> original) {
      if (!Config.FAST_LAYER_PLACEMENT.getAsBoolean()) {
         original.call(startY, endY, minX, minY, minZ, filler);
         return;
      }

      this.fillMask.clear();
      this.fillMask.set(Math.max(0, startY >> this.yCellScale), Math.min(this.yCellCount - 1, endY >> this.yCellScale) + 1);
      this.fillMask.andNot(this.filledLayers);
      for (int start = this.fillMask.nextSetBit(0); start >= 0; ) {
         int end = this.fillMask.nextClearBit(start);
         this.fillInternal(start, end - 1, minX, minY, minZ, filler);
         start = this.fillMask.nextSetBit(end);
      }

      this.filledLayers.or(this.fillMask);
   }
}
