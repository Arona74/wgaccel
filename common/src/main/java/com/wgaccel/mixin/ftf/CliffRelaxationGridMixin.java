package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gpu.CliffRelaxationGpu;
import etcodehome.freeterraforged.world.worldgen.cell.filter.FilterGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Current FTF's spacing-aware FilterGrid cliff filter. Existing opt-in/check settings apply. */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.filter.CliffRelaxation")
public abstract class CliffRelaxationGridMixin {
   @WrapMethod(method = "apply(Letcodehome/freeterraforged/world/worldgen/cell/filter/FilterGrid;III)V")
   private void wgaccel$grid(FilterGrid map, int regionX, int regionZ, int iterations, Operation<Void> original) {
      CliffRelaxationGpu.applyGrid(this, map, () -> original.call(map, regionX, regionZ, iterations));
   }
}
