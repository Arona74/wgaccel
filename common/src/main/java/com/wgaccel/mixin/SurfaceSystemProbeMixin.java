package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.wgaccel.gen.GpuSurface;
import com.wgaccel.gen.SurfaceProbe;
import com.wgaccel.gen.SurfaceTap;
import com.wgaccel.gpu.SurfaceCheck;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GPU step G3.1: the surface step's parts ({@link SurfaceProbe}). */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemProbeMixin {
   @WrapMethod(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      require = 0)
   private void wgaccel$buildSurface(RandomState random, BiomeManager biomes, Registry<Biome> registry, boolean legacy, WorldGenerationContext context,
         ChunkAccess chunk, NoiseChunk noise, SurfaceRules.RuleSource rules, Operation<Void> original) {
      SurfaceProbe.begin(rules);
      SurfaceCheck.maybeStart(random, context, registry, this, rules);
      if (SurfaceCheck.isOverworld(random)) {
         SurfaceTap.begin(chunk, biomes, noise, this);
      }

      GpuSurface.begin(random, biomes, registry, legacy, chunk, noise, rules, this);
      try {
         original.call(random, biomes, registry, legacy, context, chunk, noise, rules);
      } finally {
         GpuSurface.end(chunk);
         SurfaceTap.end();
         SurfaceProbe.end();
      }
   }

   @WrapOperation(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;apply(Ljava/lang/Object;)Ljava/lang/Object;"), require = 0)
   private Object wgaccel$ruleTree(SurfaceRules.RuleSource rules, Object context, Operation<Object> original) {
      long start = SurfaceProbe.beforeRules();
      Object made = original.call(rules, context);
      SurfaceProbe.rulesMade(start);
      SurfaceTap.built(context, made);
      GpuSurface.rulesMade();
      return made;
   }

   /**
    * GPU step G3.4: in a chunk whose surface the GPU wrote ({@link GpuSurface}), the eroded badlands test reads each
    * column's height from before the writes and the column loop starts below the world (no rows).
    */
   @WrapOperation(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", ordinal = 0),
      require = 0)
   private int wgaccel$columnHeight(ChunkAccess chunk, Heightmap.Types type, int x, int z, Operation<Integer> original) {
      return GpuSurface.skipping(chunk) ? GpuSurface.height(chunk, x, z, false, 0) : original.call(chunk, type, x, z);
   }

   @WrapOperation(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", ordinal = 1),
      require = 0)
   private int wgaccel$loopStart(ChunkAccess chunk, Heightmap.Types type, int x, int z, Operation<Integer> original) {
      return GpuSurface.skipping(chunk) ? GpuSurface.height(chunk, x, z, true, 0) : original.call(chunk, type, x, z);
   }

   @Inject(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"), require = 0)
   private void wgaccel$ruleBefore(CallbackInfo info) {
      SurfaceProbe.ruleStart();
   }

   @Inject(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;",
         shift = At.Shift.AFTER), require = 0)
   private void wgaccel$ruleAfter(CallbackInfo info) {
      SurfaceProbe.ruleEnd();
   }

   /** GPU step G3.2: each rule call's result, with its context, for the surface rule check ({@link SurfaceTap}). */
   @ModifyExpressionValue(method = "buildSurface(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/core/Registry;ZLnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"), require = 0)
   private BlockState wgaccel$ruleResult(BlockState state) {
      return SurfaceTap.result(state);
   }
}
