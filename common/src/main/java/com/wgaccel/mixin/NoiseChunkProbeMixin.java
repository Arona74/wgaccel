package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.FillProbe;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

/** GPU step G2.1: times the noise fill's cell corners ({@link FillProbe}). */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkProbeMixin {
   @WrapMethod(method = "initializeForFirstCellX", require = 0)
   private void wgaccel$firstCorners(Operation<Void> original) {
      long start = System.nanoTime();
      original.call();
      FillProbe.corners(System.nanoTime() - start);
   }

   @WrapMethod(method = "advanceCellX", require = 0)
   private void wgaccel$corners(int cellX, Operation<Void> original) {
      long start = System.nanoTime();
      original.call(cellX);
      FillProbe.corners(System.nanoTime() - start);
   }
}
