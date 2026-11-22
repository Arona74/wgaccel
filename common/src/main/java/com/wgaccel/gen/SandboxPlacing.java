package com.wgaccel.gen;

/**
 * Whether this thread is running features or structures on a Voxy LOD stand-in level ({@link FeatureSandbox}): no
 * template entities then, and no fast path that reads the level's chunk sections itself (the stand-in's chunks are empty;
 * its blocks come from getBlockState).
 */
public final class SandboxPlacing {
   private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

   private SandboxPlacing() {
   }

   public static boolean active() {
      return DEPTH.get()[0] > 0;
   }

   static void enter() {
      DEPTH.get()[0]++;
   }

   static void leave() {
      DEPTH.get()[0]--;
   }
}
