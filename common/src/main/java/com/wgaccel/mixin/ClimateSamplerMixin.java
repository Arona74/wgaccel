package com.wgaccel.mixin;

import com.wgaccel.gen.ClimateColumns;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Climate.Sampler.sample's temperature and humidity (its first two compute calls) kept per column for the chunk's own
 * sampler ({@link ClimateColumns}). Redirects: asked at every quart of every chunk (a @WrapOperation makes an object per call).
 */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin {
   @Redirect(method = "sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", ordinal = 0))
   private double wgaccel$temperature(DensityFunction function, DensityFunction.FunctionContext context) {
      return ClimateColumns.value(this, 0, function, context);
   }

   @Redirect(method = "sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", ordinal = 1))
   private double wgaccel$humidity(DensityFunction function, DensityFunction.FunctionContext context) {
      return ClimateColumns.value(this, 1, function, context);
   }
}
