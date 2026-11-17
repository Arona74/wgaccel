package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** FreeTerraForged 1.0.0013's chunk cells worked out once per chunk for the stand-ins and the chunk ({@link com.wgaccel.ftf.FtfChunkCells}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.filter.ChunkFilterGrid")
public abstract class ChunkCellsMixin {
   @WrapMethod(method = "generateChunkCells")
   private static Cell[] wgaccel$shared(int x, int z, GeneratorContext context, Operation<Cell[]> original) {
      return com.wgaccel.ftf.FtfChunkCells.cells(x, z, context, () -> original.call(x, z, context));
   }
}
