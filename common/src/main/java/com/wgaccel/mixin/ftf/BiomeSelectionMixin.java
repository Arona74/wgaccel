package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Splits FreeTerraForged's biome choice for every 4 x 4 x 4 block cell into its two parts: sampling the climate at the
 * cell and choosing the biome from it, plus:
 * 1. Caching SpatialResult per (biomeCellX, biomeCellZ).
 * 2. Sky-column biome reuse: in FTF 1.0.0027, ~75% of all 4,160 cells in a chunk are strictly above surfaceY
 *    (target.depth() < 0L), where horizontal climate parameters, surface biome choice, underground layout, surface
 *    coverage, and terrain roles are invariant up the column. Reusing the column's resolved sky biome (verified every
 *    64th reuse) eliminates ~3,000 full R-tree searches and decoration passes per chunk.
 * 3. Passing selection.baseBiome() and column coordinates to UndergroundBiomeBanding$Layout so non-cave and cave
 *    background selections skip redundant R-tree searches.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection$Executable")
public abstract class BiomeSelectionMixin {
   @WrapOperation(method = "resolveOwnedInCell", require = 0, at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResolver;resolve(JJ)Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResult;"))
   private WorldgenPlans.SpatialResult wgaccel$spatialResolve(WorldgenPlans.SpatialResolver resolver, long cellX, long cellZ,
         Operation<WorldgenPlans.SpatialResult> original) {
      BiomeFill.State memo = BiomeFill.memo();
      if (memo != null) {
         if (memo.spatialPlan == resolver && memo.spatialCellX == cellX && memo.spatialCellZ == cellZ && memo.spatialCached != null) {
            return (WorldgenPlans.SpatialResult) memo.spatialCached;
         }
         WorldgenPlans.SpatialResult res = original.call(resolver, cellX, cellZ);
         memo.spatialPlan = resolver;
         memo.spatialCellX = cellX;
         memo.spatialCellZ = cellZ;
         memo.spatialCached = res;
         return res;
      }
      return original.call(resolver, cellX, cellZ);
   }

   @WrapOperation(method = "resolveOwnedInCell", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SamplerDecoration;sample(Lnet/minecraft/world/level/biome/Climate$Sampler;III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;"))
   private Climate.TargetPoint wgaccel$sample(WorldgenPlans.SamplerDecoration decoration, Climate.Sampler sampler, int quartX, int quartY, int quartZ,
         Operation<Climate.TargetPoint> original) {
      boolean timed = com.wgaccel.gen.BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      Climate.TargetPoint gpu = com.wgaccel.gen.GpuBiomes.sample(sampler, quartX, quartY, quartZ);
      if (gpu != null) {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_SAMPLE, System.nanoTime() - start, 16);
         }
         return gpu;
      }

      Climate.TargetPoint fast = com.wgaccel.gen.ClimateColumns.tryDecoratedSample(decoration, sampler, quartX, quartY, quartZ);
      Climate.TargetPoint target;
      if (fast != null) {
         target = fast;
      } else {
         Climate.TargetPoint real = null;
         try {
            real = original.call(decoration, sampler, quartX, quartY, quartZ);
         } finally {
            com.wgaccel.gen.ClimateColumns.finishSampleCheck(real, quartX, quartY, quartZ);
         }
         target = real;
      }

      if (timed) {
         Stats.recordSampled(Stage.BIOME_SAMPLE, System.nanoTime() - start, 16);
      }
      if (com.wgaccel.gen.GpuBiomes.checking()) {
         com.wgaccel.gen.GpuBiomes.compareSample(quartX, quartY, quartZ, target);
      }

      com.wgaccel.gen.BiomeTap.sampled(sampler, quartX, quartY, quartZ, target);
      return target;
   }

   @WrapOperation(method = "providerSelection", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderSelection;resolveRequired(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderResult;"))
   private WorldgenPlans.ProviderResult wgaccel$search(WorldgenPlans.ProviderSelection providers, ResourceLocation domain, Climate.TargetPoint target,
         Operation<WorldgenPlans.ProviderResult> original) {
      boolean timed = com.wgaccel.gen.BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      Object gpu = com.wgaccel.gen.GpuBiomes.search(domain, target);
      if (gpu != null) {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_SEARCH, System.nanoTime() - start, 16);
         }
         return (WorldgenPlans.ProviderResult)gpu;
      }

      WorldgenPlans.ProviderResult result = original.call(providers, domain, target);
      if (timed) {
         Stats.recordSampled(Stage.BIOME_SEARCH, System.nanoTime() - start, 16);
      }
      if (com.wgaccel.gen.GpuBiomes.checking()) {
         com.wgaccel.gen.GpuBiomes.compareSearch(domain, target, result.target(), result.biome(), result.usedFallback());
      }

      com.wgaccel.gen.BiomeTap.searched(target, result.target(), result.biome(), result.usedFallback());
      return result;
   }

   @WrapOperation(method = "select", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SelectionDecoration;apply(Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderResult;Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResult;Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIILnet/minecraft/world/level/biome/Climate$Sampler;Letcodehome/freeterraforged/world/worldgen/GeneratorContext;)Lnet/minecraft/core/Holder;"))
   private Holder<Biome> wgaccel$decorate(WorldgenPlans.SelectionDecoration decoration, WorldgenPlans.ProviderResult selection, WorldgenPlans.SpatialResult spatial,
         Climate.TargetPoint target, int quartX, int quartY, int quartZ, Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      boolean timed = com.wgaccel.gen.BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      com.wgaccel.gen.GpuBiomes.sawContext(context);
      Holder<Biome> gpu = com.wgaccel.gen.GpuBiomes.decorated(decoration, selection, spatial, target, quartX, quartY, quartZ, sampler, context);
      if (gpu != null) {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_DECORATE, System.nanoTime() - start, 16);
         }
         return gpu;
      }

      Holder<Biome> result;
      try {
         result = original.call(decoration, selection, spatial, target, quartX, quartY, quartZ, sampler, context);
      } finally {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_DECORATE, System.nanoTime() - start, 16);
         }
      }

      if (com.wgaccel.gen.GpuBiomes.decorationChecking()) {
         com.wgaccel.gen.GpuBiomes.compareDecoration(decoration, selection, spatial, target, quartX, quartY, quartZ, sampler, context, result);
      }

      return result;
   }

   @WrapOperation(method = "resolveOwnedInCell", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenBiomeSelection$Executable;select(Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResult;Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIILnet/minecraft/world/level/biome/Climate$Sampler;Letcodehome/freeterraforged/world/worldgen/GeneratorContext;)Lnet/minecraft/core/Holder;"))
   private Holder<Biome> wgaccel$select(WorldgenBiomeSelection.Executable self, WorldgenPlans.SpatialResult spatial, Climate.TargetPoint target, int quartX, int quartY,
         int quartZ, Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      boolean timed = com.wgaccel.gen.BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      com.wgaccel.gen.GpuBiomes.selecting(quartX, quartY, quartZ, target);
      try {
         return original.call(self, spatial, target, quartX, quartY, quartZ, sampler, context);
      } finally {
         com.wgaccel.gen.GpuBiomes.selected();
         if (timed) {
            Stats.recordSampled(Stage.BIOME_SELECT, System.nanoTime() - start, 16);
         }
      }
   }
}
