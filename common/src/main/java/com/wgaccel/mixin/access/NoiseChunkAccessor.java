package com.wgaccel.mixin.access;

import java.util.Map;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A noise chunk's size (plan item B1) and, for the GPU check of step G2.3, its wrapped functions, beardifier and blender. */
@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {
   @Accessor("cellCountY")
   int wgaccel$cellCountY();

   @Accessor("cellNoiseMinY")
   int wgaccel$cellNoiseMinY();

   @Accessor("cellHeight")
   int wgaccel$cellHeight();

   @Accessor("cellWidth")
   int wgaccel$cellWidth();

   @Accessor("wrapped")
   Map<DensityFunction, DensityFunction> wgaccel$wrapped();

   @Accessor("beardifier")
   DensityFunctions.BeardifierOrMarker wgaccel$beardifier();

   @Accessor("blender")
   Blender wgaccel$blender();
}
