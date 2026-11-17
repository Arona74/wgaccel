package com.wgaccel.mixin;

import com.wgaccel.gen.FastNoiseInterpolator;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public abstract class NoiseCacheAllInCellMixin implements FastNoiseInterpolator.CellCache {
   @Shadow
   @Final
   private DensityFunction noiseFiller;

   @Shadow
   @Final
   private double[] values;

   @Override
   public void wgaccel$fill(DensityFunction.ContextProvider provider) {
      this.noiseFiller.fillArray(this.values, provider);
   }
}
