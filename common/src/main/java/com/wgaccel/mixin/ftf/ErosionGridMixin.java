package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gpu.ErosionFilterGpu;
import etcodehome.freeterraforged.world.worldgen.cell.filter.FilterGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FreeTerraForged 1.0.0013's erosion filter on a chunk's 40 x 40 grid (cell.filter.Erosion), on the GPU when config
 * gpuErosionFilter says so (see {@link ErosionFilterGpu}, algorithm 3).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.filter.Erosion")
public abstract class ErosionGridMixin {
   @WrapMethod(method = "apply(Letcodehome/freeterraforged/world/worldgen/cell/filter/FilterGrid;III)V")
   private void wgaccel$gpu(FilterGrid map, int regionX, int regionZ, int iterationsPerChunk, Operation<Void> original) {
      ErosionFilterGpu.apply(this, map.getBacking(), map.getTotalSize(), map.getBlockX(), map.getBlockZ(),
         () -> original.call(map, regionX, regionZ, iterationsPerChunk));
   }
}
