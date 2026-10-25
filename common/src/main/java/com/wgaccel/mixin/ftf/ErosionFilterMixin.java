package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gpu.ErosionFilterGpu;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Filterable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** FreeTerraForged's tile erosion filter, on the GPU when config gpuErosionFilter says so (see {@link ErosionFilterGpu}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Erosion")
public abstract class ErosionFilterMixin {
   @WrapMethod(method = "apply(Letcodehome/freeterraforged/world/worldgen/densityfunction/tile/filter/Filterable;III)V")
   private void wgaccel$gpu(Filterable map, int regionX, int regionZ, int iterationsPerChunk, Operation<Void> original) {
      ErosionFilterGpu.apply(this, map.getBacking(), map.getBlockSize().total(), map.getBlockX(), map.getBlockZ(),
         () -> original.call(map, regionX, regionZ, iterationsPerChunk));
   }
}
