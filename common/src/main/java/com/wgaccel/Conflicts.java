package com.wgaccel;

import com.wgaccel.platform.Platform;


/**
 * Mods Worldgen Accelerator steps aside for (the user, 2026-10-22: with C2ME's OpenCL module installed, the whole mod is
 * off). Asked of the mod loader as it loads mods, so the mixin plugins can ask it before any mixin applies; no game classes here.
 */
public final class Conflicts {
   /** C2ME's OpenCL acceleration module (its own mod id inside the C2ME OpenCL jar). */
   public static final String C2ME_OPENCL = "c2me_opts_accel_opencl";
   private static volatile Boolean openCl;

   private Conflicts() {
   }

   /** True when C2ME's OpenCL module is installed: Worldgen Accelerator then does nothing (no mixins, no GPU, no events). */
   public static boolean openCl() {
      Boolean value = openCl;
      if (value == null) {
         try {
            value = Platform.get().isModLoaded(C2ME_OPENCL);
         } catch (RuntimeException | LinkageError failure) {
            value = false;
         }

         openCl = value;
      }

      return value;
   }
}
