package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.gen.DensityTap;
import com.wgaccel.gen.FillToGround;
import com.wgaccel.gen.GpuNoiseFill;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.injection.At;
import com.wgaccel.mixin.access.NoiseBasedChunkGeneratorInvoker;
import com.wgaccel.mixin.access.NoiseChunkAccessor;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times the real work of the biome and noise steps (the steps themselves run these on a background thread), and
 * counts how many noise cell layers each chunk fills against how many its highest block needs: FreeTerraForged fills
 * its whole configured height, and the difference is what filling only up to the ground would save (plan item B1).
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
   /** S8 C1: the carvers' biome of each chunk within 8 read from the chunk when it holds its biomes ({@link com.wgaccel.gen.CarverBiomes}). */
   @WrapOperation(method = "applyCarvers", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/ChunkAccess;carverBiome(Ljava/util/function/Supplier;)Lnet/minecraft/world/level/biome/BiomeGenerationSettings;"))
   private net.minecraft.world.level.biome.BiomeGenerationSettings wgaccel$carverBiome(ChunkAccess chunk,
         java.util.function.Supplier<net.minecraft.world.level.biome.BiomeGenerationSettings> supplier,
         Operation<net.minecraft.world.level.biome.BiomeGenerationSettings> original) {
      return original.call(chunk, com.wgaccel.gen.CarverBiomes.supplier((NoiseBasedChunkGenerator)(Object)this, chunk, supplier));
   }

   @WrapMethod(method = "doFill")
   private ChunkAccess wgaccel$doFill(Blender blender, StructureManager structures, RandomState random, ChunkAccess chunk, int minCellY, int cellCountY, Operation<ChunkAccess> original) {
      long start = System.nanoTime();
      // The noise chunk (usually made in the biomes step) may be cut to the ground (B1): fill only its cells.
      NoiseBasedChunkGeneratorInvoker self = (NoiseBasedChunkGeneratorInvoker)this;
      NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> self.wgaccel$createNoiseChunk(c, structures, blender, random));
      int cells = Math.min(cellCountY, ((NoiseChunkAccessor)noise).wgaccel$cellCountY());
      // GPU step G2.5: the chunk from the GPU (ON), or worked out on the GPU and compared with the CPU's fill (CHECK).
      GpuNoiseFill.Pending gpu = GpuNoiseFill.prepare(noise, chunk, random, minCellY, cells);
      ChunkAccess filled;
      if (gpu != null && !gpu.check()) {
         GpuNoiseFill.write(gpu);
         filled = chunk;
      } else {
         long cpuStart = System.nanoTime();
         DensityTap.begin(noise, chunk);
         try {
            filled = original.call(blender, structures, random, chunk, minCellY, cells);
         } finally {
            DensityTap.end();
         }

         if (gpu != null) {
            GpuNoiseFill.compare(gpu, System.nanoTime() - cpuStart);
         }
      }

      Stats.record(Stage.NOISE_FILL, System.nanoTime() - start);

      int cellHeight = ((NoiseBasedChunkGenerator)(Object)this).generatorSettings().value().noiseSettings().getCellHeight();
      int needed = neededLayers(filled, minCellY * cellHeight, cellHeight, cellCountY);
      Stats.noiseLayers(cells, needed);
      FillToGround.check(chunk.getPos(), cells, cellCountY, needed);
      return filled;
   }

   @WrapMethod(method = "doCreateBiomes")
   private void wgaccel$doCreateBiomes(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk, Operation<Void> original) {
      long start = System.nanoTime();
      com.wgaccel.gpu.BiomeCheck.maybeStart(random);
      com.wgaccel.gen.BiomeTap.begin(chunk, random);
      com.wgaccel.gen.GpuBiomes.begin(chunk, random);
      try {
         original.call(blender, random, structures, chunk);
      } finally {
         com.wgaccel.gen.GpuBiomes.end();
         com.wgaccel.gen.BiomeTap.end();
      }

      com.wgaccel.gen.SurfaceAhead.biomesDone(chunk, random);
      com.wgaccel.gen.GpuNoiseFill.afterBiomes(chunk, random);

      Stats.record(Stage.BIOME_FILL, System.nanoTime() - start);
   }

   /** Marks one chunk's biome fill: BiomeFill's memos live only inside it (and it counts the region identities sampled). */
   @WrapOperation(method = "doCreateBiomes", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
   private void wgaccel$biomeFill(ChunkAccess chunk, BiomeResolver resolver, Climate.Sampler sampler, Operation<Void> original) {
      BiomeFill.begin();
      try {
         original.call(chunk, resolver, sampler);
      } finally {
         BiomeFill.end();
      }
   }

   /** Layers from the bottom up to one cell above the chunk's highest block, water included. */
   private static int neededLayers(ChunkAccess chunk, int minY, int cellHeight, int filled) {
      int top = minY;
      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            top = Math.max(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
         }
      }

      int layers = Math.floorDiv(top - minY + cellHeight - 1, cellHeight) + 1;
      return Math.max(0, Math.min(filled, layers));
   }
}
