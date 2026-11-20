package com.wgaccel.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code /wgaccel lod trees [radius]}: trees per chunk, the real chunks loaded around the caller (trunk bases: a log on
 * soil, a 2 x 2 trunk counting 4) next to the Voxy LOD generator's trees in the same chunks (what it drew in each chunk
 * it wrote since the world opened), by each chunk's centre biome; and the share of dry columns topped by soil, both sides.
 */
public final class LodTreeStats {
   /** Chunks the LOD generator wrote (key: Mc.key): its trees standing in the chunk, dry columns, soil-topped ones. */
   private static final Map<Long, int[]> LOD = new ConcurrentHashMap<>();
   private static final int MAX = 400_000;

   private LodTreeStats() {
   }

   /** A chunk being written: its record, started again (null when too many are kept). */
   static int[] chunk(long key) {
      if (LOD.size() >= MAX && !LOD.containsKey(key)) {
         return null;
      }

      int[] record = new int[3];
      LOD.put(key, record);
      return record;
   }

   private static boolean touches(int[] bases, int x, int z, int y) {
      return x >= 0 && x < 16 && z >= 0 && z < 16 && Math.abs(bases[z << 4 | x] - y) <= 1 && bases[z << 4 | x] != Integer.MIN_VALUE;
   }

   /** The comparison lines (server thread: reads the loaded chunks). */
   public static List<String> compare(ServerLevel level, BlockPos at, int radius) {
      // Per centre biome: real trees, chunks, real dry columns, real soil ones; LOD chunks, trees, dry, soil.
      Map<String, long[]> by = new TreeMap<>();
      int cx0 = at.getX() >> 4;
      int cz0 = at.getZ() >> 4;
      int chunks = 0;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
         for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
               continue;
            }

            chunks++;
            int x0 = cx * 16;
            int z0 = cz * 16;
            Holder<Biome> centre = chunk.getNoiseBiome(QuartPos.fromBlock(x0 + 8), QuartPos.fromBlock(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8)),
               QuartPos.fromBlock(z0 + 8));
            long[] counts = by.computeIfAbsent(centre.getRegisteredName(), key -> new long[8]);
            counts[1]++;
            int[] lod = LOD.get(com.wgaccel.platform.Mc.key(cx, cz));
            if (lod != null) {
               counts[4]++;
               counts[5] += lod[0];
               counts[6] += lod[1];
               counts[7] += lod[2];
            }

            int[] bases = new int[256];
            java.util.Arrays.fill(bases, Integer.MIN_VALUE);
            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  // The top that is not leaves nor a log (the ground a sapling stood on, or would): soil or not.
                  int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                  pos.set(x0 + x, ground, z0 + z);
                  while (ground > com.wgaccel.platform.Mc.minY(level) && chunk.getBlockState(pos).is(BlockTags.LOGS)) {
                     pos.setY(--ground);
                  }

                  if (chunk.getFluidState(pos).isEmpty()) {
                     counts[2]++;
                     counts[3] += chunk.getBlockState(pos).is(BlockTags.DIRT) ? 1 : 0;
                  }

                  int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                  for (int y = top; y > top - 48; y--) {
                     pos.set(x0 + x, y, z0 + z);
                     if (chunk.getBlockState(pos).is(BlockTags.LOGS)) {
                        pos.setY(y - 1);
                        if (chunk.getBlockState(pos).is(BlockTags.DIRT)) {
                           // One tree per trunk: a base touching one already seen (west, north; a 2 x 2 or wider
                           // trunk) is the same tree.
                           bases[z << 4 | x] = y;
                           if (!touches(bases, x - 1, z, y) && !touches(bases, x - 1, z - 1, y) && !touches(bases, x - 1, z + 1, y)
                              && !touches(bases, x, z - 1, y)) {
                              counts[0]++;
                           }

                           break;
                        }
                     }
                  }
               }
            }
         }
      }

      List<String> lines = new ArrayList<>();
      lines.add(String.format(Locale.ROOT, "WGA trees per chunk: %d loaded chunks within %d of you, by their centre's biome (real: trunks on soil, touching trunk blocks one tree; LODs: the same chunks' LODs)",
         chunks, radius));
      for (Map.Entry<String, long[]> entry : by.entrySet()) {
         long[] c = entry.getValue();
         String lod = c[4] == 0 ? "not written yet" : String.format(Locale.ROOT, "%.2f (%d chunks)", c[5] / (double)c[4], c[4]);
         String soil = String.format(Locale.ROOT, "; soil on top: real %.0f%%, LODs %s", c[2] == 0 ? 0 : 100.0 * c[3] / c[2],
            c[6] == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * c[7] / c[6]));
         lines.add(String.format(Locale.ROOT, "WGA trees per chunk %s: real %.2f (%d chunks), LODs %s%s", entry.getKey(), c[0] / (double)c[1], c[1], lod, soil));
      }

      return lines;
   }
}
