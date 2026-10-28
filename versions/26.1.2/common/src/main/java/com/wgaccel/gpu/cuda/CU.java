package com.wgaccel.gpu.cuda;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.PointerBuffer;

/**
 * The CUDA driver calls the mod makes, named as LWJGL's (the shared code calls them through this class, as each
 * Minecraft version has its own). Not on 26.1.2 yet: LWJGL 3.4 has no CUDA module, and 3.3's needs JNI helpers 3.4's
 * core no longer has. cuInit finds no device, so the mod uses OpenCL; the other calls are never reached.
 */
public final class CU {
   /** Whether CUDA can be used on this version (GpuBackend chooses something else otherwise). */
   public static final boolean PRESENT = false;
   public static final int CUDA_ERROR_NO_DEVICE = 100;

   public static final int CU_STREAM_NON_BLOCKING = 1;

   public static final int CU_EVENT_DEFAULT = 0;

   public static final int CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT = 16;

   public static final int CU_DEVICE_ATTRIBUTE_INTEGRATED = 18;

   public static final int CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR = 75;

   public static final int CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR = 76;

   public static final int CU_JIT_INPUT_PTX = 1;

   public static final int CUDA_SUCCESS = 0;

   public static final int CUDA_ERROR_UNSUPPORTED_PTX_VERSION = 222;

   public static final int CUDA_ERROR_NOT_READY = 600;

   public static int cuInit(int a0) {
      return CUDA_ERROR_NO_DEVICE;
   }

   public static int cuDriverGetVersion(IntBuffer a0) {
      throw missing();
   }

   public static int cuDeviceGet(IntBuffer a0, int a1) {
      throw missing();
   }

   public static int cuDeviceGetCount(IntBuffer a0) {
      throw missing();
   }

   public static int cuDeviceGetName(ByteBuffer a0, int a1) {
      throw missing();
   }

   public static int cuDeviceTotalMem(PointerBuffer a0, int a1) {
      throw missing();
   }

   public static int cuDeviceGetAttribute(IntBuffer a0, int a1, int a2) {
      throw missing();
   }

   public static int cuCtxCreate(PointerBuffer a0, int a1, int a2) {
      throw missing();
   }

   public static int cuCtxDestroy(long a0) {
      throw missing();
   }

   public static int cuCtxSetCurrent(long a0) {
      throw missing();
   }

   public static int cuCtxSynchronize() {
      throw missing();
   }

   public static int cuCtxGetStreamPriorityRange(IntBuffer a0, IntBuffer a1) {
      throw missing();
   }

   public static int cuModuleLoadData(PointerBuffer a0, ByteBuffer a1) {
      throw missing();
   }

   public static int cuModuleUnload(long a0) {
      throw missing();
   }

   public static int cuModuleGetFunction(PointerBuffer a0, long a1, ByteBuffer a2) {
      throw missing();
   }

   public static int cuModuleGetFunction(PointerBuffer a0, long a1, CharSequence a2) {
      throw missing();
   }

   public static int cuLinkCreate(IntBuffer a0, PointerBuffer a1, PointerBuffer a2) {
      throw missing();
   }

   public static int cuLinkAddData(long a0, int a1, ByteBuffer a2, ByteBuffer a3, IntBuffer a4, PointerBuffer a5) {
      throw missing();
   }

   public static int cuLinkAddData(long a0, int a1, ByteBuffer a2, CharSequence a3, IntBuffer a4, PointerBuffer a5) {
      throw missing();
   }

   public static int cuLinkComplete(long a0, PointerBuffer a1, PointerBuffer a2) {
      throw missing();
   }

   public static int cuLinkDestroy(long a0) {
      throw missing();
   }

   public static int cuMemAlloc(PointerBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemFree(long a0) {
      throw missing();
   }

   public static int cuMemAllocHost(PointerBuffer a0, long a1) {
      throw missing();
   }

   public static int ncuMemFreeHost(long a0) {
      throw missing();
   }

   public static int ncuMemcpyHtoD(long a0, long a1, long a2) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, ByteBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, ShortBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, IntBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, LongBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, FloatBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, DoubleBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyHtoD(long a0, PointerBuffer a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(ByteBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(ShortBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(IntBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(LongBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(FloatBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(DoubleBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoH(PointerBuffer a0, long a1) {
      throw missing();
   }

   public static int cuMemcpyDtoD(long a0, long a1, long a2) {
      throw missing();
   }

   public static int ncuMemcpyHtoDAsync(long a0, long a1, long a2, long a3) {
      throw missing();
   }

   public static int ncuMemcpyDtoHAsync(long a0, long a1, long a2, long a3) {
      throw missing();
   }

   public static int cuMemcpyDtoDAsync(long a0, long a1, long a2, long a3) {
      throw missing();
   }

   public static int cuStreamCreate(PointerBuffer a0, int a1) {
      throw missing();
   }

   public static int cuStreamCreateWithPriority(PointerBuffer a0, int a1, int a2) {
      throw missing();
   }

   public static int cuStreamSynchronize(long a0) {
      throw missing();
   }

   public static int cuEventCreate(PointerBuffer a0, int a1) {
      throw missing();
   }

   public static int cuEventRecord(long a0, long a1) {
      throw missing();
   }

   public static int cuEventQuery(long a0) {
      throw missing();
   }

   public static int cuEventElapsedTime(FloatBuffer a0, long a1, long a2) {
      throw missing();
   }

   public static int cuLaunchKernel(long a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, long a8, PointerBuffer a9, PointerBuffer a10) {
      throw missing();
   }

   private CU() {
   }

   private static UnsupportedOperationException missing() {
      return new UnsupportedOperationException("CUDA is not available on this Minecraft version");
   }
}
