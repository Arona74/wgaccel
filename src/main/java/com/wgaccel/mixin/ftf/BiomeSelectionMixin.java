package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
 * cell (temperature, moisture, depth and the rest) and choosing the biome from it. Measuring only.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.runtime.WorldgenBiomeSelection$Executable")
public abstract class BiomeSelectionMixin {
   @WrapOperation(method = "resolveOwnedInCell", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SamplerDecoration;sample(Lnet/minecraft/world/level/biome/Climate$Sampler;III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;"))
   private Climate.TargetPoint wgaccel$sample(WorldgenPlans.SamplerDecoration decoration, Climate.Sampler sampler, int quartX, int quartY, int quartZ,
         Operation<Climate.TargetPoint> original) {
      long start = System.nanoTime();
      Climate.TargetPoint gpu = com.wgaccel.gen.GpuBiomes.sample(sampler, quartX, quartY, quartZ);
      if (gpu != null) {
         Stats.record(Stage.BIOME_SAMPLE, System.nanoTime() - start);
         return gpu;
      }

      Climate.TargetPoint target = original.call(decoration, sampler, quartX, quartY, quartZ);
      Stats.record(Stage.BIOME_SAMPLE, System.nanoTime() - start);
      if (com.wgaccel.gen.GpuBiomes.checking()) {
         com.wgaccel.gen.GpuBiomes.compareSample(quartX, quartY, quartZ, target);
      }

      com.wgaccel.gen.BiomeTap.sampled(sampler, quartX, quartY, quartZ, target);
      return target;
   }

   /** Inside choosing: the climate-table search (Minecraft's parameter list, after FTF's ocean projection). */
   @WrapOperation(method = "select", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderSelection;resolveRequired(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderResult;"))
   private WorldgenPlans.ProviderResult wgaccel$search(WorldgenPlans.ProviderSelection providers, ResourceLocation domain, Climate.TargetPoint target,
         Operation<WorldgenPlans.ProviderResult> original) {
      long start = System.nanoTime();
      Object gpu = com.wgaccel.gen.GpuBiomes.search(domain, target);
      if (gpu != null) {
         Stats.record(Stage.BIOME_SEARCH, System.nanoTime() - start);
         return (WorldgenPlans.ProviderResult)gpu;
      }

      WorldgenPlans.ProviderResult result = original.call(providers, domain, target);
      Stats.record(Stage.BIOME_SEARCH, System.nanoTime() - start);
      if (com.wgaccel.gen.GpuBiomes.checking()) {
         com.wgaccel.gen.GpuBiomes.compareSearch(target, result.target(), result.biome(), result.usedFallback());
      }

      com.wgaccel.gen.BiomeTap.searched(target, result.target(), result.biome(), result.usedFallback());
      return result;
   }

   /** Inside choosing: FTF's decoration stages that adjust the searched biome (rivers, beaches and the like). */
   @WrapOperation(method = "select", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SelectionDecoration;apply(Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$ProviderResult;Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResult;Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIILnet/minecraft/world/level/biome/Climate$Sampler;Letcodehome/freeterraforged/world/worldgen/GeneratorContext;)Lnet/minecraft/core/Holder;"))
   private Holder<Biome> wgaccel$decorate(WorldgenPlans.SelectionDecoration decoration, WorldgenPlans.ProviderResult selection, WorldgenPlans.SpatialResult spatial,
         Climate.TargetPoint target, int quartX, int quartY, int quartZ, Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      long start = System.nanoTime();
      com.wgaccel.gen.GpuBiomes.sawContext(context);
      Holder<Biome> gpu = com.wgaccel.gen.GpuBiomes.decorated(target);
      if (gpu != null) {
         Stats.record(Stage.BIOME_DECORATE, System.nanoTime() - start);
         return gpu;
      }

      Holder<Biome> result;
      try {
         result = original.call(decoration, selection, spatial, target, quartX, quartY, quartZ, sampler, context);
      } finally {
         Stats.record(Stage.BIOME_DECORATE, System.nanoTime() - start);
      }

      if (com.wgaccel.gen.GpuBiomes.decorationChecking()) {
         com.wgaccel.gen.GpuBiomes.compareDecoration(target, result);
      }

      return result;
   }

   @WrapOperation(method = "resolveOwnedInCell", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenBiomeSelection$Executable;select(Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$SpatialResult;Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIILnet/minecraft/world/level/biome/Climate$Sampler;Letcodehome/freeterraforged/world/worldgen/GeneratorContext;)Lnet/minecraft/core/Holder;"))
   private Holder<Biome> wgaccel$select(WorldgenBiomeSelection.Executable self, WorldgenPlans.SpatialResult spatial, Climate.TargetPoint target, int quartX, int quartY,
         int quartZ, Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      long start = System.nanoTime();
      com.wgaccel.gen.GpuBiomes.selecting(quartX, quartY, quartZ, target);
      try {
         return original.call(self, spatial, target, quartX, quartY, quartZ, sampler, context);
      } finally {
         com.wgaccel.gen.GpuBiomes.selected();
         Stats.record(Stage.BIOME_SELECT, System.nanoTime() - start);
      }
   }
}
