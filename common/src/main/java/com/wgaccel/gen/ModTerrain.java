package com.wgaccel.gen;

import com.wgaccel.platform.Names;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Other mods' terrain added to Beardifier.compute, which the GPU fill's Beardifier has no version of: such chunks are
 * filled on the CPU, and fill-to-ground keeps everything up to the highest block they can make solid.
 * <ul>
 * <li>No Man's Land 1.6.2's Meeting Point altar (its BeardifierMixin, nomansland$altarBeards): solid up to 2 below its
 * target y, air from 2 above, within its radius.</li>
 * <li>RoadWeaver 2.3.2's roads (its BeardifierMixin, roadweaver$getRoadChunkPlan): the chunk plan's density stamp, per
 * column a fill below its fill target y (7 blocks deep) and a carve above its carve target y; an empty stamp adds
 * nothing.</li>
 * </ul>
 * Without these mods (or other versions without the methods) nothing is added.
 */
public final class ModTerrain {
   private static final Method ALTARS = method(Beardifier.class, "nomansland$altarBeards");
   private static final Method ROAD_PLAN = method(Beardifier.class, "roadweaver$getRoadChunkPlan");
   private static volatile Method targetY;
   private static volatile Method stampOf;
   private static volatile Method stampEmpty;
   private static volatile Method stampTarget;
   private static volatile Method stampFill;

   private ModTerrain() {
   }

   private static Method method(Class<?> type, String name) {
      try {
         return Names.method(type, name);
      } catch (NoSuchMethodException | LinkageError absent) {
         return null;
      }
   }

   private static List<?> altars(DensityFunctions.BeardifierOrMarker beardifier) {
      if (ALTARS == null || !(beardifier instanceof Beardifier)) {
         return List.of();
      }

      try {
         Object altars = ALTARS.invoke(beardifier);
         return altars instanceof List<?> list ? list : List.of();
      } catch (ReflectiveOperationException | RuntimeException failed) {
         return List.of();
      }
   }

   /** RoadWeaver's density stamp of the chunk when it adds something, else null. */
   private static Object roadStamp(DensityFunctions.BeardifierOrMarker beardifier) throws ReflectiveOperationException {
      if (ROAD_PLAN == null || !(beardifier instanceof Beardifier)) {
         return null;
      }

      Object plan = ROAD_PLAN.invoke(beardifier);
      if (plan == null) {
         return null;
      }

      Method of = stampOf;
      if (of == null || of.getDeclaringClass() != plan.getClass()) {
         of = Names.method(plan.getClass(), "densityStamp");
         stampOf = of;
      }

      Object stamp = of.invoke(plan);
      if (stamp == null) {
         return null;
      }

      Method empty = stampEmpty;
      if (empty == null || empty.getDeclaringClass() != stamp.getClass()) {
         empty = Names.method(stamp.getClass(), "isEmpty");
         stampTarget = Names.method(stamp.getClass(), "targetY", int.class, int.class);
         stampFill = Names.method(stamp.getClass(), "fillLateral", int.class, int.class);
         stampEmpty = empty;
      }

      return (Boolean)empty.invoke(stamp) ? null : stamp;
   }

   /** Whether the chunk's Beardifier carries other mods' terrain (an altar, a road). */
   public static boolean shapes(DensityFunctions.BeardifierOrMarker beardifier) {
      if (ALTARS == null && ROAD_PLAN == null) {
         return false;
      }

      if (!altars(beardifier).isEmpty()) {
         return true;
      }

      try {
         return roadStamp(beardifier) != null;
      } catch (ReflectiveOperationException | RuntimeException failed) {
         // Not readable: treated as shaped, so the chunk is filled the game's way.
         return true;
      }
   }

   /** The highest y other mods' terrain can make solid, Integer.MIN_VALUE for none, or Integer.MAX_VALUE when unreadable. */
   public static int top(DensityFunctions.BeardifierOrMarker beardifier) {
      int top = Integer.MIN_VALUE;
      for (Object altar : altars(beardifier)) {
         try {
            Method method = targetY;
            if (method == null || method.getDeclaringClass() != altar.getClass()) {
               method = Names.method(altar.getClass(), "targetY");
               method.setAccessible(true);
               targetY = method;
            }

            top = Math.max(top, (Integer)method.invoke(altar) + 2);
         } catch (ReflectiveOperationException | RuntimeException failed) {
            return Integer.MAX_VALUE;
         }
      }

      try {
         Object stamp = roadStamp(beardifier);
         if (stamp != null) {
            // A road's fill makes blocks below its target y solid (the target y itself stays a carve's or the terrain's).
            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  if ((Float)stampFill.invoke(stamp, x, z) != 0.0F) {
                     top = Math.max(top, (Integer)stampTarget.invoke(stamp, x, z));
                  }
               }
            }
         }
      } catch (ReflectiveOperationException | RuntimeException failed) {
         return Integer.MAX_VALUE;
      }

      return top;
   }
}
