package com.wgaccel.mixin;

import com.wgaccel.gen.SurfaceLevels;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A noise chunk's preliminary surface levels shared with the other noise chunks of its world ({@link SurfaceLevels}):
 * decided as the blender is set (before the aquifer, which asks for levels as it is made); a kept level at HEAD of the
 * level's computation (vanilla's own per-chunk map stays in front of it), the worked-out one kept or compared at RETURN.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkLevelsMixin {
   @Unique
   private SurfaceLevels wgaccel$levels;
   @Unique
   private int wgaccel$calls;

   @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;blender:Lnet/minecraft/world/level/levelgen/blending/Blender;",
      opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
   private void wgaccel$levels(int cellCountXZ, RandomState random, int chunkMinBlockX, int chunkMinBlockZ, NoiseSettings noiseSettings,
         DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluidPicker, Blender blender, CallbackInfo info) {
      // A blended chunk's levels follow the old terrain next to it, not the world's alone.
      this.wgaccel$levels = blender == Blender.empty() ? SurfaceLevels.of(random) : null;
   }

   @Inject(method = "computePreliminarySurfaceLevel", at = @At("HEAD"), cancellable = true)
   private void wgaccel$kept(long column, CallbackInfoReturnable<Integer> info) {
      SurfaceLevels levels = this.wgaccel$levels;
      if (levels == null || !SurfaceLevels.on()) {
         return;
      }

      int kept = levels.kept(column);
      if (kept != SurfaceLevels.MISSING && (++this.wgaccel$calls & 255) != 0) {
         info.setReturnValue(kept);
      }
   }

   @Inject(method = "computePreliminarySurfaceLevel", at = @At("RETURN"))
   private void wgaccel$made(long column, CallbackInfoReturnable<Integer> info) {
      SurfaceLevels levels = this.wgaccel$levels;
      if (levels != null) {
         levels.made(column, info.getReturnValueI());
      }
   }
}
