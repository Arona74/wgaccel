package com.wgaccel.gpu.cuda;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;

/** NVRTC's calls (see {@link CU}): not on 26.1.2 yet, never reached. */
public final class NVRTC {
   public static final int NVRTC_SUCCESS = 0;

   public static String nvrtcGetErrorString(int a0) {
      return "NVRTC is not available";
   }

   public static int nvrtcVersion(IntBuffer a0, IntBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetNumSupportedArchs(IntBuffer a0) {
      throw missing();
   }

   public static int nvrtcGetSupportedArchs(IntBuffer a0) {
      throw missing();
   }

   public static int nvrtcCreateProgram(PointerBuffer a0, ByteBuffer a1, ByteBuffer a2, PointerBuffer a3, PointerBuffer a4) {
      throw missing();
   }

   public static int nvrtcCreateProgram(PointerBuffer a0, CharSequence a1, CharSequence a2, PointerBuffer a3, PointerBuffer a4) {
      throw missing();
   }

   public static int nvrtcDestroyProgram(PointerBuffer a0) {
      throw missing();
   }

   public static int nvrtcCompileProgram(long a0, PointerBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetPTXSize(long a0, PointerBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetPTX(long a0, ByteBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetCUBINSize(long a0, PointerBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetCUBIN(long a0, ByteBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetProgramLogSize(long a0, PointerBuffer a1) {
      throw missing();
   }

   public static int nvrtcGetProgramLog(long a0, ByteBuffer a1) {
      throw missing();
   }

   private NVRTC() {
   }

   /** The NVRTC library to load (a path or a name), before its first call. */
   public static void libraryName(String name) {
   }

   /** NVRTC's builtins library beside it, before its first call. */
   public static void builtinsLibraryName(String name) {
   }

   private static UnsupportedOperationException missing() {
      return new UnsupportedOperationException("NVRTC is not available on this Minecraft version");
   }
}
