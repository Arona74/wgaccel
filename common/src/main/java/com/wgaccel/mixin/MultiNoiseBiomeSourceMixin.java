package com.wgaccel.mixin;

import com.wgaccel.gen.GpuBiomes;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C2 (vanilla): a cell's biome as MultiNoiseBiomeSource.getNoiseBiome works it out - the climate sample, then the
 * parameter list's search - with both parts from the GPU when GpuBiomes made the chunk's cells (ties and anything not
 * made: vanilla's own code). Checked chunks (every 64th) run vanilla's and compare. Only in worlds without
 * FreeTerraForged (its biome choice has its own hook). A HEAD inject, not @WrapMethod: that made an Operation, an
 * argument array and three boxed coordinates for every biome asked for, GPU biomes or not (1.7% of the CPU in vanilla
 * 26.1.2, where they are off; 31 Oct 2026).
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
   @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
   private void wgaccel$noiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler, CallbackInfoReturnable<Holder<Biome>> info) {
      if (!GpuBiomes.vanillaReady()) {
         return;
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
            info.setReturnValue(gpu);
            return;
         }

         Holder<Biome> biome = ((MultiNoiseBiomeSource)(Object)this).getNoiseBiome(target);
         if (GpuBiomes.checking()) {
            GpuBiomes.compareSearch(null, target, target, biome, false);
         }

         info.setReturnValue(biome);
      } finally {
         GpuBiomes.selected();
      }
   }
}
