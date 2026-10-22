package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.GpuBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * C2 (vanilla): a cell's biome as MultiNoiseBiomeSource.getNoiseBiome works it out - the climate sample, then the
 * parameter list's search - with both parts from the GPU when GpuBiomes made the chunk's cells (ties and anything not
 * made: vanilla's own code). Checked chunks (every 64th) run vanilla's and compare. Only in worlds without
 * FreeTerraForged (its biome choice has its own hook).
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
   @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
   private Holder<Biome> wgaccel$noiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
      if (!GpuBiomes.vanillaReady()) {
         return original.call(quartX, quartY, quartZ, sampler);
      }

      Climate.TargetPoint target = GpuBiomes.sample(sampler, quartX, quartY, quartZ);
      if (target == null) {
         target = sampler.sample(quartX, quartY, quartZ);
         if (GpuBiomes.checking()) {
            GpuBiomes.compareSample(quartX, quartY, quartZ, target);
         }
      }

      GpuBiomes.selecting(quartX, quartY, quartZ, target);
      try {
         Holder<Biome> gpu = GpuBiomes.searchVanilla(target);
         if (gpu != null) {
            return gpu;
         }

         Holder<Biome> biome = ((MultiNoiseBiomeSource)(Object)this).getNoiseBiome(target);
         if (GpuBiomes.checking()) {
            GpuBiomes.compareSearch(null, target, target, biome, false);
         }

         return biome;
      } finally {
         GpuBiomes.selected();
      }
   }
}
