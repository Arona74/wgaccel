package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import java.util.function.IntBinaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** FreeTerraForged's minimum surface heights shared by all threads ({@link com.wgaccel.ftf.FtfSurfaceEnvelope}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.BiomeSurfaceEnvelope")
public abstract class SurfaceEnvelopeMixin {
   @WrapMethod(method = "minimumSurfaceY(Letcodehome/freeterraforged/world/worldgen/cell/heightmap/WorldLookup;ZIILjava/util/function/IntBinaryOperator;)I")
   private int wgaccel$shared(WorldLookup lookup, boolean accurate, int quartX, int quartZ, IntBinaryOperator height, Operation<Integer> original) {
      return com.wgaccel.ftf.FtfSurfaceEnvelope.minimumSurfaceY(lookup, accurate, quartX, quartZ,
         () -> original.call(lookup, accurate, quartX, quartZ, height));
   }

   /** FreeTerraForged 1.0.0013's block height inside an answer (its heightmap noise at one block); 1.0.0010's has another shape. */
   @WrapMethod(method = "lambda$minimumSurfaceY$0(Letcodehome/freeterraforged/world/worldgen/GeneratorContext;Letcodehome/freeterraforged/world/worldgen/cell/Cell;II)I")
   private static int wgaccel$sharedBlock(etcodehome.freeterraforged.world.worldgen.GeneratorContext context, etcodehome.freeterraforged.world.worldgen.cell.Cell cell,
         int x, int z, Operation<Integer> original) {
      return com.wgaccel.ftf.FtfSurfaceEnvelope.blockHeight(context, x, z, () -> original.call(context, cell, x, z));
   }
}
