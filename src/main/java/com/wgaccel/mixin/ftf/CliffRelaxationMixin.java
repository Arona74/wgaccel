package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gpu.CliffRelaxationGpu;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.Filterable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * S6.2: FreeTerraForged's CliffRelaxation filter (21 Oct and later; absent before, then this does nothing), on the GPU
 * with the erosion filter's mode (see {@link CliffRelaxationGpu}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter.CliffRelaxation")
public abstract class CliffRelaxationMixin {
   @WrapMethod(method = "apply(Letcodehome/freeterraforged/world/worldgen/densityfunction/tile/filter/Filterable;III)V")
   private void wgaccel$gpu(Filterable map, int regionX, int regionZ, int iterationsPerChunk, Operation<Void> original) {
      CliffRelaxationGpu.apply(this, map, () -> original.call(map, regionX, regionZ, iterationsPerChunk));
   }
}
