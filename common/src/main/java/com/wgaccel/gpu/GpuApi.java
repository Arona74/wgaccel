package com.wgaccel.gpu;

import com.wgaccel.gpu.cuda.CU;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The part of LWJGL's {@link CU} that the noise fill, biomes and surface use ({@link FillGpu}, {@link BiomeGpu},
 * {@link SurfaceGpu}), under the same names and arguments, going to CUDA or, with {@link GpuBackend#HIP}, to the same
 * call in HIP ({@link Hip}), or with {@link GpuBackend#OPENCL} to {@link OpenCl}. Those classes import it in place of CU,
 * so their code is the same for all three. HIP's and OpenCL's results use CUDA's numbers for what is asked here (0
 * success, 600 not ready).
 */
public final class GpuApi {
   public static final int CUDA_SUCCESS = CU.CUDA_SUCCESS;
   public static final int CUDA_ERROR_NOT_READY = CU.CUDA_ERROR_NOT_READY;
   public static final int CU_EVENT_DEFAULT = CU.CU_EVENT_DEFAULT;

   private GpuApi() {
   }

   /**
    * Under HIP every context is the device's primary one ({@link Gpu}): selecting the device makes it current. OpenCL has
    * one context, shared by every thread.
    */
   public static int cuCtxSetCurrent(long context) {
      if (GpuBackend.OPENCL) {
         return CUDA_SUCCESS;
      }

      return GpuBackend.HIP ? Hip.hipSetDevice(GpuDevice.index()) : CU.cuCtxSetCurrent(context);
   }

   public static int cuModuleGetFunction(PointerBuffer function, long module, ByteBuffer name) {
      if (GpuBackend.OPENCL) {
         return OpenCl.getFunction(function, module, name);
      }

      return GpuBackend.HIP ? Hip.hipModuleGetFunction(function, module, name) : CU.cuModuleGetFunction(function, module, name);
   }

   public static int cuLaunchKernel(long function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream,
         PointerBuffer params, PointerBuffer extra) {
      if (GpuBackend.OPENCL) {
         return OpenCl.launch(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params);
      }

      return GpuBackend.HIP
         ? Hip.hipModuleLaunchKernel(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params, MemoryUtil.memAddressSafe(extra))
         : CU.cuLaunchKernel(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params, extra);
   }

   public static int cuMemAlloc(PointerBuffer device, long bytes) {
      int result = GpuBackend.OPENCL ? OpenCl.allocate(device, bytes) : GpuBackend.HIP ? Hip.hipMalloc(device, bytes) : CU.cuMemAlloc(device, bytes);
      if (result == CUDA_SUCCESS) {
         GpuMemory.device(device.get(device.position()), bytes);
      }

      return result;
   }

   public static int cuMemFree(long device) {
      GpuMemory.deviceFreed(device);
      return GpuBackend.OPENCL ? OpenCl.free(device) : GpuBackend.HIP ? Hip.hipFree(device) : CU.cuMemFree(device);
   }

   public static int cuMemAllocHost(PointerBuffer host, long bytes) {
      int result = GpuBackend.OPENCL ? OpenCl.allocateHost(host, bytes) : GpuBackend.HIP ? Hip.hipHostMalloc(host, bytes, 0) : CU.cuMemAllocHost(host, bytes);
      if (result == CUDA_SUCCESS) {
         GpuMemory.host(host.get(host.position()), bytes);
      }

      return result;
   }

   public static int ncuMemFreeHost(long host) {
      GpuMemory.hostFreed(host);
      return GpuBackend.OPENCL ? OpenCl.freeHost(host) : GpuBackend.HIP ? Hip.hipHostFree(host) : CU.ncuMemFreeHost(host);
   }

   /** Copies a direct buffer's bytes from its position to its limit to the device (any element type, as CU's overloads). */
   public static int cuMemcpyHtoD(long device, Buffer buffer) {
      long bytes = (long)buffer.remaining() << shift(buffer);
      if (GpuBackend.OPENCL) {
         return OpenCl.copy(device, MemoryUtil.memAddress(buffer), bytes, 0L, true);
      }

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
      if (GpuBackend.OPENCL) {
         return OpenCl.copy(device, host, bytes, stream, false);
      }

      return GpuBackend.HIP ? Hip.hipMemcpyHtoDAsync(device, host, bytes, stream) : CU.ncuMemcpyHtoDAsync(device, host, bytes, stream);
   }

   public static int ncuMemcpyDtoHAsync(long host, long device, long bytes, long stream) {
      if (GpuBackend.OPENCL) {
         return OpenCl.copy(host, device, bytes, stream, false);
      }

      return GpuBackend.HIP ? Hip.hipMemcpyDtoHAsync(host, device, bytes, stream) : CU.ncuMemcpyDtoHAsync(host, device, bytes, stream);
   }

   public static int cuStreamSynchronize(long stream) {
      if (GpuBackend.OPENCL) {
         return OpenCl.synchronize(stream);
      }

      return GpuBackend.HIP ? Hip.hipStreamSynchronize(stream) : CU.cuStreamSynchronize(stream);
   }

   public static int cuEventCreate(PointerBuffer event, int flags) {
      if (GpuBackend.OPENCL) {
         return OpenCl.eventCreate(event);
      }

      return GpuBackend.HIP ? Hip.hipEventCreateWithFlags(event, flags) : CU.cuEventCreate(event, flags);
   }

   public static int cuEventRecord(long event, long stream) {
      if (GpuBackend.OPENCL) {
         return OpenCl.eventRecord(event, stream);
      }

      return GpuBackend.HIP ? Hip.hipEventRecord(event, stream) : CU.cuEventRecord(event, stream);
   }

   public static int cuEventQuery(long event) {
      if (GpuBackend.OPENCL) {
         return OpenCl.eventQuery(event);
      }

      return GpuBackend.HIP ? Hip.hipEventQuery(event) : CU.cuEventQuery(event);
   }

   public static int cuEventElapsedTime(FloatBuffer milliseconds, long start, long stop) {
      if (GpuBackend.OPENCL) {
         return OpenCl.eventElapsedTime();
      }

      return GpuBackend.HIP ? Hip.hipEventElapsedTime(milliseconds, start, stop) : CU.cuEventElapsedTime(milliseconds, start, stop);
   }
}
