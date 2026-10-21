package com.wgaccel.ftf;

import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.TileCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The highest ground of a chunk, read from FreeTerraForged's finished tile (the same height its density uses, erosion
 * included). Only loaded when FreeTerraForged is installed.
 */
public final class FtfGround {
   public static final int UNKNOWN = Integer.MIN_VALUE;

   private FtfGround() {
   }

   /** The highest ground block of the chunk's 16 x 16 columns, or {@link #UNKNOWN} when it cannot be read safely. */
   public static int highest(RandomState random, ChunkPos pos) {
      // FTF adds this interface to RandomState by mixin, so the compiler only sees it through Object.
      if (!((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged() || ftf.preset() == null) {
         return UNKNOWN;
      }

      // Below this offset FTF's cave branch can apply just above the ground; then the ground is not a safe cut.
      if (ftf.preset().caves().cheeseCaveDepthOffset < 0.0F) {
         return UNKNOWN;
      }

      GeneratorContext context = ftf.generatorContext();
      if (context == null || context.cache == null) {
         return UNKNOWN;
      }

      float max = Float.NEGATIVE_INFINITY;
      try (TileCache.Lease lease = context.cache.acquireAtChunk(pos.x, pos.z)) {
         if (lease == null) {
            return UNKNOWN;
         }

         Tile.Chunk chunk = lease.tile().getChunkReader(pos.x, pos.z);
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               max = Math.max(max, chunk.getCell(x, z).height);
            }
         }
      }

      if (!Float.isFinite(max)) {
         return UNKNOWN;
      }

      // FTF's depth is zero at about 256 x height; its own scale is taken too, whichever is higher.
      return Math.max((int)Math.ceil(256.0 * max), context.levels.scale(max));
   }
}
