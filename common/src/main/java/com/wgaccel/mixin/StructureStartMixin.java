package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Named;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/** Times placing a structure's pieces into one chunk (the blocks, block entities and any entities they carry), by structure. */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
   @WrapMethod(method = "placeInChunk")
   private void wgaccel$placeInChunk(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos pos,
         Operation<Void> original) {
      long start = System.nanoTime();
      try {
         original.call(level, structures, generator, random, box, pos);
      } finally {
         long nanos = System.nanoTime() - start;
         Named.record(Named.Category.STRUCTURE_PIECES, Named.structure(level, ((StructureStart)(Object)this).getStructure()), nanos, false);
      }
   }
}
