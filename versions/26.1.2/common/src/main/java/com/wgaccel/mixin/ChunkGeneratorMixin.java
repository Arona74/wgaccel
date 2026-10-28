package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Named;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times every attempt to start a structure in a chunk, by structure: the biome check, the jigsaw assembly and the
 * pieces' layout. "Started" counts the attempts that made a structure.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
   @WrapMethod(method = "tryGenerateStructure")
   private boolean wgaccel$tryGenerateStructure(StructureSet.StructureSelectionEntry entry, StructureManager structures, RegistryAccess registries, RandomState random,
         StructureTemplateManager templates, long seed, ChunkAccess chunk, ChunkPos pos, SectionPos section, ResourceKey<Level> dimension,
         Operation<Boolean> original) {
      long start = System.nanoTime();
      boolean started = false;
      try {
         started = original.call(entry, structures, registries, random, templates, seed, chunk, pos, section, dimension);
         return started;
      } finally {
         Named.record(Named.Category.STRUCTURE_START, Named.structure(entry), System.nanoTime() - start, started);
      }
   }
}
