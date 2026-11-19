package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.CriteriaMemo;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FreeTerraForged's Lithostitched biome injection criteria: their density functions' values kept per point and, for
 * functions of x and z only, per column ({@link CriteriaMemo}). matches asks one function directly, several through a
 * lambda (lambda$matches$2 in 1.0.0033).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge$ParameterCriteria")
public abstract class LithostitchedCriteriaMixin {
   @WrapOperation(method = "matches", require = 0, at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
   private double wgaccel$compute(DensityFunction function, DensityFunction.FunctionContext context, Operation<Double> original) {
      return CriteriaMemo.compute(function, context, original);
   }

   @WrapOperation(method = "lambda$matches$2", require = 0, at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
   private static double wgaccel$computeEach(DensityFunction function, DensityFunction.FunctionContext context, Operation<Double> original) {
      return CriteriaMemo.compute(function, context, original);
   }
}
