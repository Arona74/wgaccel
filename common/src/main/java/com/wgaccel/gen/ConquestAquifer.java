package com.wgaccel.gen;

import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Conquest ReTerraForged's aquifer (its MixinNoiseChunk wraps the chunk's NoiseBasedAquifer): per block column of the
 * chunk it works out from its terrain cell, once, five numbers (in its own int[256] fields, declared in this order, by
 * column (z & 15) << 4 | (x & 15), a boolean[256] marking the columns done): the water top (Integer.MIN_VALUE: none),
 * the ground height, the lowest height its band gives "no fluid" from, the water sheet's level (0: none) and the sheet's
 * falling-water top. Within those bands it answers itself (water, a sheet's flowing water, air or no fluid); everything
 * else is the wrapped vanilla aquifer's. The GPU fill's kernel cq_aquifer does the same bands from these numbers and
 * then the vanilla aquifer it already has. The class is obfuscated (its names change with each release): it is found by
 * its package and shape, and the GPU fill's checks compare its blocks with the game's.
 */
public final class ConquestAquifer {
   private static final ClassValue<Shape> SHAPES = new ClassValue<>() {
      @Override
      protected Shape computeValue(Class<?> type) {
         return Shape.of(type);
      }
   };

   private ConquestAquifer() {
   }

   /** The wrapper's fields: the wrapped aquifer, the five columns' int arrays in order, the done flags. */
   private record Shape(Field inner, Field[] columns, Field done) {
      static Shape of(Class<?> type) {
         if (!Names.className(type).startsWith("conquest.reterraforged.") || !Aquifer.class.isAssignableFrom(type)) {
            return null;
         }

         Field inner = null;
         Field done = null;
         List<Field> columns = new ArrayList<>();
         for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
               continue;
            }

            field.setAccessible(true);
            if (field.getType() == Aquifer.class) {
               inner = field;
            } else if (field.getType() == int[].class) {
               columns.add(field);
            } else if (field.getType() == boolean[].class) {
               done = field;
            }
         }

         return inner != null && done != null && columns.size() == 5 ? new Shape(inner, columns.toArray(Field[]::new), done) : null;
      }
   }

   /** The vanilla aquifer the GPU works from: the aquifer itself, or the one Conquest's wraps; null for any other. */
   public static Aquifer.NoiseBasedAquifer vanilla(Aquifer aquifer) {
      if (aquifer instanceof Aquifer.NoiseBasedAquifer vanilla) {
         return vanilla;
      }

      Shape shape = aquifer == null ? null : SHAPES.get(aquifer.getClass());
      if (shape == null) {
         return null;
      }

      try {
         return shape.inner.get(aquifer) instanceof Aquifer.NoiseBasedAquifer vanilla ? vanilla : null;
      } catch (IllegalAccessException failure) {
         return null;
      }
   }

   public static boolean wraps(Aquifer aquifer) {
      return !(aquifer instanceof Aquifer.NoiseBasedAquifer) && vanilla(aquifer) != null;
   }

   /**
    * The chunk's five numbers per column into out from 256 on (number k of column lx * 16 + lz at 256 + k * 256 + it, as
    * the kernel reads them; out's first 256 are the lava levels). Each column is asked once with a positive density,
    * which makes the wrapper work its numbers out and then answers early (the wrapped aquifer's density check): no
    * block is decided by it. False when the wrapper did not fill a column.
    */
   private static final java.util.Map<Object, String> BUSY = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

   public static boolean columns(Aquifer aquifer, int chunkX, int chunkZ, int[] out) {
      Shape shape = SHAPES.get(aquifer.getClass());
      try {
         for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               aquifer.computeSubstance(new DensityFunction.SinglePointContext(chunkX + lx, 0, chunkZ + lz), 1.0);
            }
         }

         boolean[] done = (boolean[])shape.done.get(aquifer);
         int[][] numbers = new int[5][];
         for (int k = 0; k < 5; k++) {
            numbers[k] = (int[])shape.columns[k].get(aquifer);
            if (numbers[k].length != 256) {
               return false;
            }
         }

         for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               int own = lz << 4 | lx;
               if (!done[own]) {
                  return false;
               }

               for (int k = 0; k < 5; k++) {
                  out[256 + k * 256 + lx * 16 + lz] = numbers[k][own];
               }
            }
         }

         return true;
      } catch (IllegalAccessException | ClassCastException failure) {
         return false;
      }
   }
}
