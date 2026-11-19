package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.FillGpu;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;

/**
 * FreeTerraForged's river water for the LOD stand-ins: its SurfaceSystem.buildSurface starts by placing the water of
 * rivers, lakes and wetlands from its cells, and stone where a bank meets higher water (MixinSurfaceSystem
 * placeRiverWater, 1.0.0032), before the surface rules. A stand-in has no surface step, so here FreeTerraForged's own
 * method is run on the stand-in's empty chunk (it only writes, from the cells the chunk holds) and what it wrote goes
 * into the stand-in's codes: water and flowing water as the fill's water, stone as the fill's default block (ice, at
 * the top of a frozen river's step, as water too).
 */
public final class FtfLodWater {
   private static final boolean FTF = com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
   public static final LongAdder CHUNKS = new LongAdder();
   public static final LongAdder BLOCKS = new LongAdder();
   private static volatile Method place;
   private static volatile boolean absent;

   private FtfLodWater() {
   }

   /**
    * Runs FreeTerraForged's river water on the stand-in's chunk and writes it into the fill's codes (n = 16 / stride
    * columns a side, as LodColumns reads them); false when there is none to run (not FreeTerraForged, an older one).
    */
   public static boolean apply(RandomState random, SurfaceSystem system, ChunkAccess chunk, NoiseChunk noise, BiomeManager biomes, FillGpu.Job fill, byte[] codes,
         BlockState[] table, BlockState defaultBlock) {
      if (!FTF || absent || chunk == null || !((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged()) {
         return false;
      }

      GeneratorContext context = ftf.generatorContext();
      if (context == null) {
         return false;
      }

      int water = -1;
      int stone = -1;
      for (int c = 0; c < table.length && c < 16; c++) {
         if (water < 0 && table[c] == Blocks.WATER.defaultBlockState()) {
            water = c;
         }

         if (stone < 0 && table[c] == defaultBlock) {
            stone = c;
         }
      }

      if (water < 0 || stone < 0) {
         return false;
      }

      try {
         Method method = place;
         if (method == null) {
            for (Method declared : SurfaceSystem.class.getDeclaredMethods()) {
               if (declared.getName().contains("placeRiverWater") && declared.getParameterCount() == 4) {
                  declared.setAccessible(true);
                  method = declared;
                  break;
               }
            }

            if (method == null) {
               absent = true;
               WorldgenAccelerator.LOG.info("WGA LOD stand-ins: this FreeTerraForged places no river water in its surface step");
               return false;
            }

            place = method;
         }

         method.invoke(system, chunk, biomes, context, noise);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         absent = true;
         WorldgenAccelerator.LOG.warn("WGA LOD stand-ins: FreeTerraForged's river water could not be run (LODs without it): {}", failure.toString());
         return false;
      }

      // What it wrote, at the filled columns.
      int stride = fill.stride;
      int n = 16 / stride;
      int fillMinY = fill.cellMinY * fill.cellHeight;
      int fillHeight = fill.cellCountY * fill.cellHeight;
      LevelChunkSection[] sections = chunk.getSections();
      int written = 0;
      for (int s = 0; s < sections.length; s++) {
         LevelChunkSection section = sections[s];
         if (section == null || section.hasOnlyAir()) {
            continue;
         }

         int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(s));
         for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
               for (int ly = 0; ly < 16; ly++) {
                  BlockState state = section.getBlockState(i * stride, ly, j * stride);
                  if (state.isAir()) {
                     continue;
                  }

                  int fy = baseY + ly - fillMinY;
                  if (fy < 0 || fy >= fillHeight) {
                     continue;
                  }

                  boolean wet = !state.getFluidState().isEmpty() || state.is(Blocks.ICE);
                  codes[(fy * n + j) * n + i] = (byte)(wet ? water : stone);
                  written++;
               }
            }
         }
      }

      CHUNKS.increment();
      BLOCKS.add(written);
      return true;
   }
}
