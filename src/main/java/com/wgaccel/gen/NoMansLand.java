package com.wgaccel.gen;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * No Man's Land 1.6.2's Meeting Point altar: its BeardifierMixin adds a bias to Beardifier.compute around the altar
 * (solid up to 2 below its target y, air from 2 above, within its radius), kept on the Beardifier as
 * nomansland$altarBeards. The GPU fill has no version of it, so such chunks are filled on the CPU, and fill-to-ground
 * cuts above it. Without the mod (or another version without the method) there are no altars.
 */
public final class NoMansLand {
   private static final Method ALTARS = altarsMethod();
   private static volatile Method targetY;

   private NoMansLand() {
   }

   private static Method altarsMethod() {
      try {
         return Beardifier.class.getMethod("nomansland$altarBeards");
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

   /** Whether the chunk's Beardifier carries an altar (its terrain is shaped by more than the GPU's Beardifier). */
   public static boolean shapes(DensityFunctions.BeardifierOrMarker beardifier) {
      return ALTARS != null && !altars(beardifier).isEmpty();
   }

   /** The highest y an altar can make solid (its target y + 2), or Integer.MAX_VALUE when it cannot be read. */
   public static int top(DensityFunctions.BeardifierOrMarker beardifier) {
      int top = Integer.MIN_VALUE;
      for (Object altar : altars(beardifier)) {
         try {
            Method method = targetY;
            if (method == null || method.getDeclaringClass() != altar.getClass()) {
               method = altar.getClass().getMethod("targetY");
               method.setAccessible(true);
               targetY = method;
            }

            top = Math.max(top, (Integer)method.invoke(altar) + 2);
         } catch (ReflectiveOperationException | RuntimeException failed) {
            return Integer.MAX_VALUE;
         }
      }

      return top;
   }
}
