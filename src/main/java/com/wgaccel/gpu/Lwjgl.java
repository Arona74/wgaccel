package com.wgaccel.gpu;

/**
 * Whether LWJGL (Minecraft's own copy, which the CUDA bindings use) is loaded. The client always has it; a dedicated
 * server only when its launch arguments add it (HANDOFF: the server setup). Without it every GPU feature stays off and
 * the work runs on the CPU as usual. No LWJGL classes are named here, so this class loads either way.
 */
public final class Lwjgl {
   public static final boolean PRESENT = present();

   private Lwjgl() {
   }

   private static boolean present() {
      try {
         Class.forName("org.lwjgl.system.MemoryUtil", false, Lwjgl.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException | LinkageError missing) {
         return false;
      }
   }

   public static final String MISSING = "LWJGL is not loaded (a dedicated server has none; add lwjgl-3.3.3.jar and its natives-windows jar to the server's"
      + " legacyClassPath in libraries/net/neoforged/neoforge/<version>/win_args.txt)";
}
