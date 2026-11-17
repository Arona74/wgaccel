package com.wgaccel.mixin;

import com.wgaccel.gen.FastNoiseInterpolator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements FastNoiseInterpolator {
   @Shadow
   abstract void selectCellYZ(int cellY, int cellZ);

   @Shadow
   abstract void updateForY(double deltaY);

   @Shadow
   abstract void updateForX(double deltaX);

   @Shadow
   abstract void updateForZ(double deltaZ);

   @Override
   public void wgaccel$selectCellYZ(int cellY, int cellZ) {
      this.selectCellYZ(cellY, cellZ);
   }

   @Override
   public void wgaccel$updateForY(double deltaY) {
      this.updateForY(deltaY);
   }

   @Override
   public void wgaccel$updateForX(double deltaX) {
      this.updateForX(deltaX);
   }

   @Override
   public void wgaccel$updateForZ(double deltaZ) {
      this.updateForZ(deltaZ);
   }
}
