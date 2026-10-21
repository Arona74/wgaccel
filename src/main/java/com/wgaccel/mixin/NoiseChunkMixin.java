package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.wgaccel.gen.FillToGround;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Plan item B1: a chunk's noise chunk is made only as tall as its terrain needs ({@link FillToGround}). */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
   @WrapOperation(method = "forChunk", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/NoiseSettings;clampToHeightAccessor(Lnet/minecraft/world/level/LevelHeightAccessor;)Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
   private static NoiseSettings wgaccel$fillToGround(NoiseSettings settings, LevelHeightAccessor height, Operation<NoiseSettings> original,
         @Local(argsOnly = true) ChunkAccess chunk, @Local(argsOnly = true) RandomState random,
         @Local(argsOnly = true) DensityFunctions.BeardifierOrMarker beardifier, @Local(argsOnly = true) NoiseGeneratorSettings generatorSettings,
         @Local(argsOnly = true) Blender blender) {
      return FillToGround.trim(original.call(settings, height), chunk, random, beardifier, generatorSettings, blender);
   }

   /** The aquifer of a cut noise chunk keeps the full height (carvers use it up there; {@link FillToGround#aquiferRange}). */
   @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
   private Aquifer wgaccel$fullAquifer(NoiseChunk noise, ChunkPos pos, NoiseRouter router, PositionalRandomFactory random, int minY, int height,
         Aquifer.FluidPicker picker, Operation<Aquifer> original) {
      int[] range = FillToGround.aquiferRange(minY, height);
      return original.call(noise, pos, router, random, range[0], range[1], picker);
   }
}
