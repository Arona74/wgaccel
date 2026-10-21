package com.wgaccel.mixin.access;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorInvoker {
   @Invoker("createNoiseChunk")
   NoiseChunk wgaccel$createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random);
}
