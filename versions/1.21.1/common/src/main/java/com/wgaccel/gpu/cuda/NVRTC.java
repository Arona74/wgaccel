package com.wgaccel.gpu.cuda;

import org.lwjgl.system.Configuration;

/** NVRTC's calls (see {@link CU}): LWJGL's own on 1.21.1, inherited. */
public class NVRTC extends org.lwjgl.cuda.NVRTC {
   protected NVRTC() {
   }

   /** The NVRTC library to load (a path or a name), before its first call. */
   public static void libraryName(String name) {
      Configuration.CUDA_NVRTC_LIBRARY_NAME.set(name);
   }

   /** NVRTC's builtins library beside it, before its first call. */
   public static void builtinsLibraryName(String name) {
      Configuration.CUDA_NVRTC_BUILTINS_LIBRARY_NAME.set(name);
   }
}
