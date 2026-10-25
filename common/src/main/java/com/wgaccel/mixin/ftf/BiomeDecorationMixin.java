package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Measuring only: the underground layout of FTF's biome decoration (cave biomes in bands and regions), the
 * climate resample at each region's "identity" position inside it, and how many different identity positions a chunk
 * asks for (if far fewer than the asks, caching them is the next speedup).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding$Layout")
public abstract class BiomeDecorationMixin {
   @WrapMethod(method = "findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIIFLetcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$TargetSampler;)Ljava/lang/Object;")
   private Object wgaccel$layout(Climate.TargetPoint target, int quartX, int quartY, int quartZ, float coverage, UndergroundBiomeBanding.TargetSampler sampler,
         Operation<Object> original) {
      long start = System.nanoTime();
      try {
         return original.call(target, quartX, quartY, quartZ, coverage, (UndergroundBiomeBanding.TargetSampler)(x, y, z) -> {
            long begin = System.nanoTime();
            try {
               return sampler.sample(x, y, z);
            } finally {
               Stats.record(Stage.BIOME_IDENTITY, System.nanoTime() - begin);
               BiomeFill.identity(x, y, z);
            }
         });
      } finally {
         Stats.record(Stage.BIOME_LAYOUT, System.nanoTime() - start);
      }
   }

   /** Measuring only: the climate-table searches inside the layout (background, original). */
   @WrapOperation(method = "findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIIFLetcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$TargetSampler;)Ljava/lang/Object;",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Climate$ParameterList;findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;"))
   private Object wgaccel$layoutSearch(Climate.ParameterList<?> list, Climate.TargetPoint target, Operation<Object> original) {
      long start = System.nanoTime();
      try {
         return original.call(list, target);
      } finally {
         Stats.record(Stage.BIOME_LAYOUT_SEARCH, System.nanoTime() - start);
      }
   }
}
