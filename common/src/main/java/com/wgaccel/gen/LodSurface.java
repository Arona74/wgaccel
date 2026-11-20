package com.wgaccel.gen;

import com.wgaccel.gpu.FillGpu;
import com.wgaccel.gpu.SurfaceGpu;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The surface (grass, sand, snow, rock layers) of a LOD stand-in ({@link LodColumns}): the GPU surface program's job
 * ({@link GpuSurface}) made from the stand-in's fill, as {@link SurfaceAhead#filled} makes it from a real chunk's.
 *
 * <p>Whole chunks (stride 1) get the job the real chunk would have, except two things only the blocks deep below the
 * surface see: the quart biomes are asked of the biome source only from a little under the lowest ground to a little above
 * the highest top (the ones further down repeat the lowest asked), and no structure terrain. With a stride, each filled
 * column stands for its stride x stride square in the job (its neighbours' heights are the next filled columns', so the
 * steep tests see the slope over the stride rather than over a block).
 */
final class LodSurface {
   private LodSurface() {
   }

   /** The surface program for the stand-ins' world, or null (gpuSurface off or not ready, another world). */
   static GpuSurface.Program program(RandomState random) {
      // Even after a check turned the chunks' GPU surface off: the LODs would be bare stone without it.
      GpuSurface.Program p = GpuSurface.programForLods();
      return p != null && p.random() == random ? p : null;
   }

   /**
    * One filled chunk's surface job (codes as LodColumns.reduce reads them: n = 16 / stride columns a side), the level's
    * bottom and height, and the preliminary surface corners from the stand-in.
    */
   static SurfaceGpu.Job job(GpuSurface.Program p, BiomeSource biomeSource, RandomState random, ChunkPos pos, FillGpu.Job fill, byte[] codes, BlockState[] table,
         double[] prelim, int levelMinY, int levelHeight) {
      int stride = fill.stride;
      int n = 16 / stride;
      int fillMinY = fill.cellMinY * fill.cellHeight;
      int fillHeight = fill.cellCountY * fill.cellHeight;
      byte[] kindOf = new byte[16];
      for (int c = 0; c < table.length && c < 16; c++) {
         BlockState state = table[c];
         kindOf[c] = (byte)(state == null || state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : state == p.defaultBlock() ? 2 : 3);
      }

      SurfaceGpu.Job job = new SurfaceGpu.Job();
      // Per filled column: its top (any block) and its ground (the first solid block from the top).
      int[] heightOf = new int[n * n];
      int maxTop = levelMinY;
      int lowGround = Integer.MAX_VALUE;
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            int height = levelMinY - 1;
            int ground = levelMinY - 1;
            for (int ly = fillHeight - 1; ly >= 0; ly--) {
               int y = fillMinY + ly;
               if (y < levelMinY || y >= levelMinY + levelHeight) {
                  continue;
               }

               byte kind = kindOf[codes[(ly * n + j) * n + i] & 15];
               if (kind != 0 && height < levelMinY) {
                  height = y;
               }

               if (kind >= 2) {
                  ground = y;
                  break;
               }
            }

            heightOf[i * n + j] = height;
            maxTop = Math.max(maxTop, height + 1);
            lowGround = Math.min(lowGround, ground);
         }
      }

      // With a stride, each column of the job is its filled column's, copied over its stride x stride square. Vanilla's
      // steep test (rock instead of grass, FreeTerraForged's rock layers) compares a column's neighbours one block away,
      // which a stride cannot know: on FreeTerraForged 1.0.0032 some steep columns at detail 2-3 came out grass where the
      // real chunk had tuff (1-4 of 16; heights raised or lowered by interpolating between the filled columns did not
      // do better and were left out).
      for (int t = 0; t < 256; t++) {
         int column = (t / 16 / stride) * n + (t % 16) / stride;
         job.heights[t] = heightOf[column];
         job.tops[t] = heightOf[column] + 1;
      }

      int height = Math.max(1, Math.min(levelHeight, maxTop - levelMinY + 1));
      int minX = pos.getMinBlockX();
      int minZ = pos.getMinBlockZ();
      job.minY = levelMinY;
      job.height = height;
      job.minX = minX;
      job.minZ = minZ;
      job.kinds = new byte[256 * height];
      for (int y = levelMinY; y < levelMinY + height; y++) {
         int ly = y - fillMinY;
         if (ly < 0 || ly >= fillHeight) {
            continue;
         }

         int at = (y - levelMinY) * 256;
         int row = ly * n * n;
         for (int t = 0; t < 256; t++) {
            int i = t / 16 / stride;
            int j = (t % 16) / stride;
            job.kinds[at + t] = kindOf[codes[row + j * n + i] & 15];
         }
      }

      for (int t = 0; t < 256; t++) {
         int x = minX + t / 16;
         int z = minZ + t % 16;
         job.minBase[t] = GpuSurface.columnBase(Mth.floor(Mth.lerp2((double)((float)(x & 15) / 16.0F), (double)((float)(z & 15) / 16.0F), prelim[0], prelim[1],
            prelim[2], prelim[3])), x, z);
      }

      // The quart biomes around the chunk (6 x 6 quarts, from the bottom to the highest top), as SurfaceAhead.filled lays them out.
      int gx = (minX >> 2) - 1;
      int gz = (minZ >> 2) - 1;
      int gy = levelMinY >> 2;
      int fullQuarts = ((levelMinY + levelHeight - 1) >> 2) - gy + 1;
      int sy = Math.max(1, Math.min(fullQuarts, (((levelMinY + height - 1 - 2) >> 2) + 1) - gy + 1));
      job.grid[0] = gx;
      job.grid[1] = gy;
      job.grid[2] = gz;
      job.grid[3] = 6;
      job.grid[4] = sy;
      job.grid[5] = 6;
      job.quarts = new int[36 * sy];
      job.quartCount = 36 * sy;
      // Asked only around the surface: from below the lowest ground (the surface rules reach some blocks down, the biome
      // zoom a quart further) to the top; the quarts below repeat the lowest one asked.
      int from = Mth.clamp(((Math.min(lowGround, maxTop) - 16) >> 2) - gy, 0, sy - 1);
      net.minecraft.world.level.biome.Climate.Sampler sampler = random.sampler();
      for (int qx = 0; qx < 6; qx++) {
         for (int qz = 0; qz < 6; qz++) {
            int base = (qx * 6 + qz) * sy;
            int last = 0;
            for (int qy = sy - 1; qy >= from; qy--) {
               Holder<Biome> biome = biomeSource.getNoiseBiome(gx + qx, gy + qy, gz + qz, sampler);
               last = p.biomes().getId(biome.value());
               job.quarts[base + qy] = last;
            }

            for (int qy = from - 1; qy >= 0; qy--) {
               job.quarts[base + qy] = last;
            }
         }
      }

      job.zoomSeed = p.zoomSeed();
      return job;
   }
}
