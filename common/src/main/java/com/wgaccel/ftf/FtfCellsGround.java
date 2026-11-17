package com.wgaccel.ftf;

import com.wgaccel.platform.Names;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.lang.reflect.Method;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The highest ground of a chunk on FreeTerraForged 1.0.0013 and later, which removed its tiles: each chunk keeps its
 * own 16 x 16 cells (FTF's mixin gives ChunkAccess freeterraforged$getOrCreateChunkCells, the cells its density reads,
 * erosion included), made once and kept on the chunk. Found by reflection (the method is not in 1.0.0010); the same
 * height formula as {@link FtfGround}.
 */
public final class FtfCellsGround {
   private static volatile Method getOrCreate;
   private static volatile boolean absent;

   private FtfCellsGround() {
   }

   /** Whether this FreeTerraForged keeps cells on its chunks. */
   public static boolean available(ChunkAccess chunk) {
      return !absent && method(chunk) != null;
   }

   /** The highest ground block of the chunk, or {@link FtfGround#UNKNOWN}. */
   public static int highest(RandomState random, ChunkAccess chunk) throws ReflectiveOperationException {
      if (!((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged() || ftf.preset() == null) {
         return FtfGround.UNKNOWN;
      }

      // Below this offset FTF's cave branch can apply just above the ground; then the ground is not a safe cut.
      if (ftf.preset().caves().cheeseCaveDepthOffset < 0.0F) {
         return FtfGround.UNKNOWN;
      }

      GeneratorContext context = ftf.generatorContext();
      Method method = method(chunk);
      if (context == null || method == null) {
         return FtfGround.UNKNOWN;
      }

      Object cells = method.invoke(chunk, context);
      if (!(cells instanceof Cell[] array) || array.length < 256) {
         return FtfGround.UNKNOWN;
      }

      float max = Float.NEGATIVE_INFINITY;
      for (Cell cell : array) {
         if (cell != null) {
            max = Math.max(max, cell.height);
         }
      }

      if (!Float.isFinite(max)) {
         return FtfGround.UNKNOWN;
      }

      return Math.max((int)Math.ceil(256.0 * max), context.levels.scale(max));
   }

   /** Exact effective {@code cellCountY} trimmed by FTF's {@code wrapDoFill}, or {@code Integer.MAX_VALUE} if unavailable. */
   public static int ftfWrapCells(RandomState random, ChunkAccess chunk, int seaLevel, int minY, int cellHeight, int fullCells) {
      if (!((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged()) {
         return Integer.MAX_VALUE;
      }

      GeneratorContext context = ftf.generatorContext();
      Method method = method(chunk);
      if (context == null || method == null) {
         return Integer.MAX_VALUE;
      }

      try {
         Object cells = method.invoke(chunk, context);
         if (!(cells instanceof Cell[] array) || array.length == 0) {
            return Integer.MAX_VALUE;
         }

         float maxHeight = array[0].height;
         for (int i = 1; i < array.length; i++) {
            if (array[i].height > maxHeight) {
               maxHeight = array[i].height;
            }
         }

         int maxTerrainY = context.levels.scale(maxHeight);
         int targetMaxY = Math.max(maxTerrainY, seaLevel) + 16;
         int neededCells = Math.floorDiv(targetMaxY - minY + cellHeight - 1, cellHeight);
         return Math.min(fullCells, Math.max(1, neededCells));
      } catch (ReflectiveOperationException ignored) {
         return Integer.MAX_VALUE;
      }
   }

   /** Clamps {@code cells} to the exact cell count FTF's {@code wrapDoFill} uses when FTF cells apply. */
   public static int clampWrapCells(int cells, RandomState random, ChunkAccess chunk, int seaLevel, NoiseSettings full) {
      if (!available(chunk)) {
         return cells;
      }

      int cellHeight = full.getCellHeight();
      int fullCells = Math.floorDiv(full.height(), cellHeight);
      return Math.min(cells, ftfWrapCells(random, chunk, seaLevel, full.minY(), cellHeight, fullCells));
   }

   private static Method method(ChunkAccess chunk) {
      Method found = getOrCreate;
      if (found != null || absent) {
         return found;
      }

      try {
         found = Names.method(chunk.getClass(), "freeterraforged$getOrCreateChunkCells", GeneratorContext.class);
         getOrCreate = found;
         return found;
      } catch (NoSuchMethodException none) {
         absent = true;
         return null;
      }
   }
}
