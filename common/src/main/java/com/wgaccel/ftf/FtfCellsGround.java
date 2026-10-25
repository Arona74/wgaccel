package com.wgaccel.ftf;

import com.wgaccel.platform.Names;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.lang.reflect.Method;
import net.minecraft.world.level.chunk.ChunkAccess;
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
