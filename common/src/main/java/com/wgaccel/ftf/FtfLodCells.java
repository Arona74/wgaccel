package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Fast LOD stand-ins for FreeTerraForged (config distantHorizonsFastFrom): a stand-in chunk's FreeTerraForged cells made
 * for only the columns a coarse LOD level reads (every stride-th, 0 to 16), each straight from the heightmap
 * (WorldLookup.applyCell: Heightmap.apply, the beach rule, its continentalness), and handed to the chunk
 * (IChunkCellsHolder.setChunkCells), so the noise chunk FreeTerraForged makes for it reads them instead of building
 * the chunk's 4 x 4-chunk region (its 88 x 88 raw columns and filters).
 *
 * <p>Not the same terrain: the region filters (cliff relaxation, erosion, beach detection, steepness) are left out, and
 * cells another way already filtered are used as they are. The columns between the filled ones repeat the nearest
 * one (the GPU fill at that stride never reads them).
 */
public final class FtfLodCells {
   private static final boolean FTF = com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
   /** 18 x 18 padded cells after the 256 interior references (FreeTerraForged's ChunkFilterGrid holder layout). */
   private static final int HOLDER_LENGTH = 580;
   private static final int PADDED_OFFSET = 256;
   private static final int PADDED = 18;
   public static final LongAdder CHUNKS = new LongAdder();
   public static final LongAdder COLUMNS = new LongAdder();
   private static volatile Method setter;
   private static volatile boolean broken;

   private FtfLodCells() {
   }

   /**
    * Gives the stand-in chunk cells for its columns stride apart (1 to 16); false when it cannot (not a
    * FreeTerraForged world, a FreeTerraForged without the holder), and the chunk is then made the usual way.
    */
   public static boolean prepare(RandomState random, ChunkAccess chunk, int stride) {
      if (!FTF || broken || stride < 1 || !((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged()) {
         return false;
      }

      try {
         GeneratorContext context = ftf.generatorContext();
         if (context == null || context.lookup == null) {
            return false;
         }

         Method set = setter;
         if (set == null) {
            // IChunkCellsHolder.freeterraforged$setChunkCells (FreeTerraForged 1.0.0013 and later; the build compiles
            // against 1.0.0010's interface, without it).
            Class<?> holder = Class.forName("etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder", false, FTFRandomState.class.getClassLoader());
            set = holder.getMethod("freeterraforged$setChunkCells", Cell[].class);
            setter = set;
         }

         WorldLookup lookup = context.lookup;
         int minX = chunk.getPos().getMinBlockX();
         int minZ = chunk.getPos().getMinBlockZ();
         Cell[] made = new Cell[17 * 17];
         Cell[] cells = new Cell[HOLDER_LENGTH];
         for (int pz = 0; pz < PADDED; pz++) {
            int nz = nearest(pz - 1, stride);
            for (int px = 0; px < PADDED; px++) {
               int nx = nearest(px - 1, stride);
               Cell source = made[nx * 17 + nz];
               if (source == null) {
                  source = new Cell();
                  lookup.applyCell(source, minX + nx, minZ + nz, false, true);
                  made[nx * 17 + nz] = source;
                  COLUMNS.increment();
               }

               Cell cell = new Cell();
               cell.copyFrom(source);
               cells[PADDED_OFFSET + pz * PADDED + px] = cell;
               int lx = px - 1;
               int lz = pz - 1;
               if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                  cells[lz * 16 + lx] = cell;
               }
            }
         }

         set.invoke(chunk, (Object)cells);
         CHUNKS.increment();
         return true;
      } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA fast FreeTerraForged LOD cells off (stand-ins build their regions as before): {}", failure.toString());
         return false;
      }
   }

   /** The filled column nearest a position of the padded row (-1 to 16): a multiple of the stride, 0 to 16. */
   private static int nearest(int rel, int stride) {
      int n = Math.round((float)rel / stride) * stride;
      return Math.max(0, Math.min(16, n));
   }
}
