package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times every step a new chunk goes through. C2ME's chunk system still runs these vanilla steps, so the timing holds
 * with it. Steps that finish on the calling thread are pure work; biomes, noise, the two light steps and "full" hand
 * their work to other threads and are timed up to their result.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
   @WrapMethod(method = "generateStructureStarts")
   private static CompletableFuture<ChunkAccess> wgaccel$structureStarts(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      // G5.3: its biomes can be worked out ahead from here (the biome step comes right after structure references).
      com.wgaccel.gen.BiomesAhead.noticed(context.level(), chunk.getPos());
      return Stats.timeFuture(Stage.STRUCTURE_STARTS, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateStructureReferences")
   private static CompletableFuture<ChunkAccess> wgaccel$structureReferences(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      // G4b: its noise fill can be worked out ahead from here (structure starts also run in a wide ring of chunks that
      // never get further; structure references only where the chunk itself is going to be generated).
      com.wgaccel.gen.BiomesAhead.soon(context.level(), chunk.getPos());
      // The fill once the references are known: a chunk with structure terrain is filled at its noise step with its
      // Beardifier, so it is not made ahead (its area mates still are).
      return Stats.timeFuture(Stage.STRUCTURE_REFERENCES, () -> original.call(context, step, cache, chunk)).thenApply(done -> {
         com.wgaccel.gen.GpuNoiseFill.noticed(context.level(), done.getPos(), com.wgaccel.gen.GpuNoiseFill.structureTerrain(done));
         return done;
      });
   }

   @WrapMethod(method = "generateBiomes")
   private static CompletableFuture<ChunkAccess> wgaccel$biomes(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.BIOMES, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateNoise")
   private static CompletableFuture<ChunkAccess> wgaccel$noise(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.NOISE, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateSurface")
   private static CompletableFuture<ChunkAccess> wgaccel$surface(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.SURFACE, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateCarvers")
   private static CompletableFuture<ChunkAccess> wgaccel$carvers(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.CARVERS, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateFeatures")
   private static CompletableFuture<ChunkAccess> wgaccel$features(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.FEATURES, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "initializeLight")
   private static CompletableFuture<ChunkAccess> wgaccel$initializeLight(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.INIT_LIGHT, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "light")
   private static CompletableFuture<ChunkAccess> wgaccel$light(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.LIGHT, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "generateSpawn")
   private static CompletableFuture<ChunkAccess> wgaccel$spawn(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.SPAWN, () -> original.call(context, step, cache, chunk));
   }

   @WrapMethod(method = "full")
   private static CompletableFuture<ChunkAccess> wgaccel$full(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
      return Stats.timeFuture(Stage.FULL, () -> original.call(context, step, cache, chunk));
   }

}
