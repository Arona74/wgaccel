package com.wgaccel.gpu;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.cuda.CU;
import org.lwjgl.system.MemoryUtil;

/**
 * The part of LWJGL's {@link CU} that the noise fill, biomes and surface use ({@link FillGpu}, {@link BiomeGpu},
 * {@link SurfaceGpu}), under the same names and arguments, going to CUDA or, with {@link GpuBackend#HIP}, to the same
 * call in HIP ({@link Hip}). Those classes import it in place of CU, so their code is the same for both. HIP's results
 * use CUDA's numbers for what is asked here (0 success, 600 not ready).
 */
public final class GpuApi {
   public static final int CUDA_SUCCESS = CU.CUDA_SUCCESS;
   public static final int CUDA_ERROR_NOT_READY = CU.CUDA_ERROR_NOT_READY;
   public static final int CU_EVENT_DEFAULT = CU.CU_EVENT_DEFAULT;

   private GpuApi() {
   }

   /** Under HIP every context is the device's primary one ({@link Gpu}): selecting the device makes it current. */
   public static int cuCtxSetCurrent(long context) {
      return GpuBackend.HIP ? Hip.hipSetDevice(GpuDevice.index()) : CU.cuCtxSetCurrent(context);
   }

   public static int cuModuleGetFunction(PointerBuffer function, long module, ByteBuffer name) {
      return GpuBackend.HIP ? Hip.hipModuleGetFunction(function, module, name) : CU.cuModuleGetFunction(function, module, name);
   }

   public static int cuLaunchKernel(long function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream,
         PointerBuffer params, PointerBuffer extra) {
      return GpuBackend.HIP
         ? Hip.hipModuleLaunchKernel(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params, MemoryUtil.memAddressSafe(extra))
         : CU.cuLaunchKernel(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params, extra);
   }

   public static int cuMemAlloc(PointerBuffer device, long bytes) {
      return GpuBackend.HIP ? Hip.hipMalloc(device, bytes) : CU.cuMemAlloc(device, bytes);
   }

   public static int cuMemFree(long device) {
      return GpuBackend.HIP ? Hip.hipFree(device) : CU.cuMemFree(device);
   }

   public static int cuMemAllocHost(PointerBuffer host, long bytes) {
      return GpuBackend.HIP ? Hip.hipHostMalloc(host, bytes, 0) : CU.cuMemAllocHost(host, bytes);
   }

   public static int ncuMemFreeHost(long host) {
      return GpuBackend.HIP ? Hip.hipHostFree(host) : CU.ncuMemFreeHost(host);
   }

   /** Copies a direct buffer's bytes from its position to its limit to the device (any element type, as CU's overloads). */
   public static int cuMemcpyHtoD(long device, Buffer buffer) {
      long bytes = (long)buffer.remaining() << shift(buffer);
      return GpuBackend.HIP ? Hip.hipMemcpyHtoD(device, MemoryUtil.memAddress(buffer), bytes) : CU.ncuMemcpyHtoD(device, MemoryUtil.memAddress(buffer), bytes);
   }

   private static int shift(Buffer buffer) {
      if (buffer instanceof ByteBuffer) {
         return 0;
      } else if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) {
         return 1;
      } else {
         return buffer instanceof DoubleBuffer || buffer instanceof LongBuffer ? 3 : 2;
      }
   }

   public static int ncuMemcpyHtoDAsync(long device, long host, long bytes, long stream) {
      return GpuBackend.HIP ? Hip.hipMemcpyHtoDAsync(device, host, bytes, stream) : CU.ncuMemcpyHtoDAsync(device, host, bytes, stream);
   }

   public static int ncuMemcpyDtoHAsync(long host, long device, long bytes, long stream) {
      return GpuBackend.HIP ? Hip.hipMemcpyDtoHAsync(host, device, bytes, stream) : CU.ncuMemcpyDtoHAsync(host, device, bytes, stream);
   }

   public static int cuStreamSynchronize(long stream) {
      return GpuBackend.HIP ? Hip.hipStreamSynchronize(stream) : CU.cuStreamSynchronize(stream);
   }

   public static int cuEventCreate(PointerBuffer event, int flags) {
      return GpuBackend.HIP ? Hip.hipEventCreateWithFlags(event, flags) : CU.cuEventCreate(event, flags);
   }

   public static int cuEventRecord(long event, long stream) {
      return GpuBackend.HIP ? Hip.hipEventRecord(event, stream) : CU.cuEventRecord(event, stream);
   }

   public static int cuEventQuery(long event) {
      return GpuBackend.HIP ? Hip.hipEventQuery(event) : CU.cuEventQuery(event);
   }

   public static int cuEventElapsedTime(FloatBuffer milliseconds, long start, long stop) {
      return GpuBackend.HIP ? Hip.hipEventElapsedTime(milliseconds, start, stop) : CU.cuEventElapsedTime(milliseconds, start, stop);
   }
}
