package com.wgaccel.cudacheck;

import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.cuda.CU;
import org.lwjgl.cuda.NVRTC;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;
import org.lwjgl.opencl.CL12;
import org.lwjgl.opencl.CL20;
import org.lwjgl.opencl.CL30;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Whether an OpenCL backend is worth making (Intel GPUs have no CUDA or HIP), run on any PC with Java:
 * `java -jar wgaccel-openclprobe.jar` (`gradlew.bat openClProbeJar` makes it, `openclCheck` runs it here). For every
 * OpenCL GPU: what matters for the mod (double precision, shared virtual memory, generic pointers, correctly rounded
 * division), the mod's CUDA kernels turned into OpenCL C by plain text replacements and checked bit for bit (the
 * self-test's float kernel, vanilla's NormalNoise in doubles against the real game's values), then times: a heavy float
 * kernel, the noise kernel, and a round trip (copy in, a tiny kernel, copy out, wait) as the mod does thousands of. The
 * same kernels through CUDA when an NVIDIA card and NVRTC are there, to compare the two on one card. Writes
 * wgaccel-openclprobe-result.txt beside where it runs.
 */
public final class OpenClProbe {
   private static final String EXTRA = """
      extern "C" __global__ void floatWork(float* a, int n, int iters) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= n) return;
          float x = a[i];
          for (int k = 0; k < iters; k++) {
              x = x * 1.000001f + 0.5f;
              x = x - floorf(x * 0.001f) * 1000.0f;
          }
          a[i] = x;
      }
      extern "C" __global__ void touch(float* a, int n) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i < n) a[i] = a[i] + 1.0f;
      }
      """;
   private static final int FLOATS = 1 << 20;
   private static final int ITERATIONS = 512;
   private static final int ROUND_TRIPS = 2000;
   private static final int TRIP_FLOATS = 4096;
   private static PrintStream out;

   // The data OpenClProbeData made.
   private static String selfTestSource;
   private static String noiseSource;
   private static int noiseCount;
   private static double[] noiseD;
   private static int[] noiseP;
   private static int[] noiseOffsets;
   private static int points;
   private static double[] xs;
   private static double[] ys;
   private static double[] zs;
   private static double[] expected;

   private OpenClProbe() {
   }

   public static void main(String[] args) throws Exception {
      File file = new File("wgaccel-openclprobe-result.txt");
      try (PrintStream result = new PrintStream(file, StandardCharsets.UTF_8)) {
         out = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
               System.out.write(b);
               result.write(b);
            }

            @Override
            public void flush() {
               System.out.flush();
               result.flush();
            }
         }, true, StandardCharsets.UTF_8);
         out.println("WGA OpenCL probe, Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
         load();
         try {
            openCl();
         } catch (Throwable failure) {
            out.println("OpenCL: " + failure);
         }

         try {
            cuda();
         } catch (Throwable failure) {
            out.println("CUDA: not run");
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
               out.println("   caused by " + cause);
               StackTraceElement[] trace = cause.getStackTrace();
               for (int i = 0; i < Math.min(4, trace.length); i++) {
                  out.println("      at " + trace[i]);
               }
            }
         }

         // HIP (or CUDA) through the mod's own route, only where its classes are (gradlew.bat openclCheck, not the jar).
         try {
            Class.forName("com.wgaccel.gpu.Gpu");
            Class<?> hip = Class.forName("com.wgaccel.cudacheck.OpenClProbeHip");
            java.lang.reflect.Method run = hip.getDeclaredMethods()[0];
            for (java.lang.reflect.Method method : hip.getDeclaredMethods()) {
               if (method.getName().equals("run")) {
                  run = method;
               }
            }

            run.setAccessible(true);
            run.invoke(null, out, selfTestSource + EXTRA, noiseSource, FLOATS, ITERATIONS, ROUND_TRIPS, TRIP_FLOATS, noiseD, noiseP, noiseOffsets, noiseCount, points,
               xs, ys, zs, expected);
         } catch (ClassNotFoundException absent) {
            // The jar for other PCs: no mod classes.
         } catch (Throwable failure) {
            out.println("The mod's own GPU route: not run (" + (failure.getCause() != null ? failure.getCause() : failure) + ")");
         }
      }

      System.out.println("Written to " + file.getAbsolutePath());
   }

   private static void load() throws Exception {
      try (InputStream stream = OpenClProbe.class.getResourceAsStream("/openclprobe.bin"); DataInputStream data = new DataInputStream(stream)) {
         if (data.readInt() != OpenClProbeData.MAGIC) {
            throw new IllegalStateException("not the probe's data");
         }

         selfTestSource = text(data);
         noiseSource = text(data);
         noiseCount = data.readInt();
         noiseD = doubles(data);
         noiseP = ints(data);
         noiseOffsets = ints(data);
         points = data.readInt();
         xs = doubles(data);
         ys = doubles(data);
         zs = doubles(data);
         expected = new double[noiseCount * points];
         for (int i = 0; i < expected.length; i++) {
            expected[i] = data.readDouble();
         }
      }
   }

   private static String text(DataInputStream data) throws java.io.IOException {
      byte[] bytes = new byte[data.readInt()];
      data.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
   }

   private static double[] doubles(DataInputStream data) throws java.io.IOException {
      double[] values = new double[data.readInt()];
      for (int i = 0; i < values.length; i++) {
         values[i] = data.readDouble();
      }

      return values;
   }

   private static int[] ints(DataInputStream data) throws java.io.IOException {
      int[] values = new int[data.readInt()];
      for (int i = 0; i < values.length; i++) {
         values[i] = data.readInt();
      }

      return values;
   }

   // ---- The CUDA C to OpenCL C replacements (what a real backend would have to do to every generated kernel). ----

   private static final String PRELUDE = """
      #pragma OPENCL FP_CONTRACT OFF
      #define sqrtf sqrt
      #define fminf fmin
      #define fmaxf fmax
      #define floorf floor
      """;

   /**
    * CUDA C to OpenCL C. generic: pointers in functions left unqualified (OpenCL 2.0 / 3.0 generic address space);
    * else every const pointer outside the kernels' own parameters marked __global (OpenCL 1.2).
    */
   static String toOpenCl(String cuda, boolean doubles, boolean generic) {
      String s = cuda.replace("extern \"C\" __global__ void", "__kernel void")
         .replace("unsigned long long", "ulong").replace("long long", "long")
         .replace("__double2int_rz(", "convert_int_rtz(").replace("__double2ll_rz(", "convert_long_rtz(")
         .replace("blockIdx.x", "(int)get_group_id(0)").replace("blockDim.x", "(int)get_local_size(0)").replace("threadIdx.x", "(int)get_local_id(0)")
         .replace("__constant__ ", "__constant ")
         .replace("const int* g = MC_GRADIENT", "__constant int* g = MC_GRADIENT")
         .replace("__device__ __forceinline__ ", "inline ").replace("__device__ __noinline__ ", "").replace("__device__ ", "");
      // The kernels' pointer parameters are always __global.
      Matcher kernels = Pattern.compile("__kernel void (\\w+)\\(([^)]*)\\)").matcher(s);
      StringBuilder built = new StringBuilder();
      while (kernels.find()) {
         String parameters = kernels.group(2).replaceAll("(const\\s+)?(\\w+)\\s*\\*", "__global $1$2*");
         kernels.appendReplacement(built, Matcher.quoteReplacement("__kernel void " + kernels.group(1) + "(" + parameters + ")"));
      }

      kernels.appendTail(built);
      s = built.toString();
      if (!generic) {
         s = s.replaceAll("(?<!__global )(?<!__constant )const (double|int|float)\\*", "__global const $1*");
      }

      return (doubles ? "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n" : "") + PRELUDE + s;
   }

   // ---- OpenCL ----

   private static void openCl() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         int found = CL10.clGetPlatformIDs(null, count);
         if (found != CL10.CL_SUCCESS || count.get(0) == 0) {
            out.println("OpenCL: no platform (" + found + ")");
            return;
         }

         PointerBuffer platforms = stack.mallocPointer(count.get(0));
         CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
         for (int p = 0; p < platforms.capacity(); p++) {
            long platform = platforms.get(p);
            out.println();
            out.println("== OpenCL platform: " + platformString(platform, CL10.CL_PLATFORM_NAME) + " (" + platformString(platform, CL10.CL_PLATFORM_VERSION) + ")");
            IntBuffer devices = stack.mallocInt(1);
            if (CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, null, devices) != CL10.CL_SUCCESS || devices.get(0) == 0) {
               out.println("   no GPU");
               continue;
            }

            PointerBuffer list = stack.mallocPointer(devices.get(0));
            CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, list, (IntBuffer)null);
            for (int d = 0; d < list.capacity(); d++) {
               try {
                  device(platform, list.get(d));
               } catch (Throwable failure) {
                  out.println("   device failed: " + failure);
               }
            }
         }
      }
   }

   private static void device(long platform, long device) {
      String version = deviceString(device, CL10.CL_DEVICE_VERSION);
      String cVersion = deviceString(device, CL11.CL_DEVICE_OPENCL_C_VERSION);
      String extensions = deviceString(device, CL10.CL_DEVICE_EXTENSIONS);
      long doubleConfig = deviceLong(device, CL12.CL_DEVICE_DOUBLE_FP_CONFIG);
      long singleConfig = deviceLong(device, CL10.CL_DEVICE_SINGLE_FP_CONFIG);
      long svm = version.contains("OpenCL 2") || version.contains("OpenCL 3") ? deviceLong(device, CL20.CL_DEVICE_SVM_CAPABILITIES) : 0L;
      long generic = version.contains("OpenCL 3") ? deviceInt(device, CL30.CL_DEVICE_GENERIC_ADDRESS_SPACE_SUPPORT) : version.contains("OpenCL 2") ? 1L : 0L;
      boolean fp64 = doubleConfig != 0L || extensions.contains("cl_khr_fp64");
      out.println("-- " + deviceString(device, CL10.CL_DEVICE_NAME) + " (" + deviceString(device, CL10.CL_DEVICE_VENDOR) + ")");
      out.printf(Locale.ROOT, "   %s, %s, driver %s%n", version, cVersion, deviceString(device, CL10.CL_DRIVER_VERSION));
      out.printf(Locale.ROOT, "   %d compute units, %d MHz, %.1f GB, unified memory with the host: %s%n", deviceInt(device, CL10.CL_DEVICE_MAX_COMPUTE_UNITS),
         deviceInt(device, CL10.CL_DEVICE_MAX_CLOCK_FREQUENCY), deviceLong(device, CL10.CL_DEVICE_GLOBAL_MEM_SIZE) / 1.073741824e9,
         deviceInt(device, CL11.CL_DEVICE_HOST_UNIFIED_MEMORY) != 0 ? "yes" : "no");
      out.printf(Locale.ROOT, "   double precision: %s; correctly rounded float division and square root: %s%n",
         fp64 ? "YES (config 0x" + Long.toHexString(doubleConfig) + ")" : "NO", (singleConfig & CL12.CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT) != 0 ? "yes" : "no");
      out.printf(Locale.ROOT, "   shared virtual memory: %s; generic pointers: %s; Intel unified shared memory: %s; queue priorities: %s; C++ for OpenCL: %s%n",
         svm == 0L ? "no" : "0x" + Long.toHexString(svm), generic != 0L ? "yes" : "no", extensions.contains("cl_intel_unified_shared_memory") ? "yes" : "no",
         extensions.contains("cl_khr_priority_hints") ? "yes" : "no", extensions.contains("__opencl_c_ext_cxx") || cVersion.contains("C++") ? "listed" : "not listed");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long context = CL10.clCreateContext(stack.pointers(CL10.CL_CONTEXT_PLATFORM, platform, 0L), device, null, 0L, error);
         check(error.get(0), "clCreateContext");
         long queue = CL10.clCreateCommandQueue(context, device, 0L, error);
         check(error.get(0), "clCreateCommandQueue");
         try {
            // OpenCL 3.0 devices may say OpenCL C 1.2 (its default) and still take 3.0 with generic pointers when asked.
            String std = cVersion.contains("3.0") || version.contains("OpenCL 3") && generic != 0L ? " -cl-std=CL3.0" : cVersion.contains("2.") ? " -cl-std=CL2.0" : "";
            String exact = (singleConfig & CL12.CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT) != 0 ? "-cl-fp32-correctly-rounded-divide-sqrt" : "";
            long floats = build(context, device, toOpenCl(selfTestSource + EXTRA, false, false), exact, "float kernels");
            if (floats != 0L) {
               floatCheck(queue, context, floats);
               timeFloat(queue, context, floats);
               roundTrips(queue, context, floats);
            }

            if (fp64) {
               long noise = generic != 0L ? build(context, device, toOpenCl(noiseSource, true, true), exact + std, "vanilla noise, generic pointers") : 0L;
               if (noise == 0L) {
                  noise = build(context, device, toOpenCl(noiseSource, true, false), exact, "vanilla noise, __global pointers");
               }

               if (noise != 0L) {
                  noiseCheck(queue, context, noise);
               }
            } else {
               out.println("   vanilla noise (doubles): skipped, no double precision");
            }
         } finally {
            CL10.clReleaseCommandQueue(queue);
            CL10.clReleaseContext(context);
         }
      }
   }

   private static long build(long context, long device, String source, String options, String what) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long program = CL10.clCreateProgramWithSource(context, source, error);
         check(error.get(0), "clCreateProgramWithSource");
         long start = System.nanoTime();
         int built = CL10.clBuildProgram(program, device, options, null, 0L);
         double ms = (System.nanoTime() - start) / 1.0e6;
         if (built != CL10.CL_SUCCESS) {
            PointerBuffer size = stack.mallocPointer(1);
            CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, (ByteBuffer)null, size);
            ByteBuffer log = MemoryUtil.memAlloc((int)size.get(0));
            CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, log, null);
            String text = MemoryUtil.memUTF8(log, Math.max(0, (int)size.get(0) - 1));
            MemoryUtil.memFree(log);
            out.printf(Locale.ROOT, "   build of %s FAILED (%d): %s%n", what, built, text.length() > 1500 ? text.substring(0, 1500) + " ..." : text);
            CL10.clReleaseProgram(program);
            return 0L;
         }

         out.printf(Locale.ROOT, "   build of %s: ok in %.0f ms (options \"%s\")%n", what, ms, options);
         return program;
      }
   }

   private static long kernel(long program, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long kernel = CL10.clCreateKernel(program, name, error);
         check(error.get(0), "clCreateKernel " + name);
         return kernel;
      }
   }

   private static long buffer(long context, long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long buffer = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_WRITE, bytes, error);
         check(error.get(0), "clCreateBuffer");
         return buffer;
      }
   }

   private static void run(long queue, long kernel, long items) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         check(CL10.clEnqueueNDRangeKernel(queue, kernel, 1, null, stack.pointers(items), null, null, null), "clEnqueueNDRangeKernel");
      }
   }

   private static void floatCheck(long queue, long context, long program) {
      int n = 65536;
      FloatBuffer a = MemoryUtil.memAllocFloat(n);
      FloatBuffer b = MemoryUtil.memAllocFloat(n);
      FloatBuffer result = MemoryUtil.memAllocFloat(n);
      Random random = new Random(1L);
      for (int i = 0; i < n; i++) {
         a.put(i, 0.5f + random.nextFloat() * 100.0f);
         b.put(i, 0.5f + random.nextFloat() * 100.0f);
      }

      long ka = buffer(context, 4L * n);
      long kb = buffer(context, 4L * n);
      long ko = buffer(context, 4L * n);
      CL10.clEnqueueWriteBuffer(queue, ka, true, 0L, a, null, null);
      CL10.clEnqueueWriteBuffer(queue, kb, true, 0L, b, null, null);
      long kernel = kernel(program, "terrainLike");
      CL10.clSetKernelArg1p(kernel, 0, ka);
      CL10.clSetKernelArg1p(kernel, 1, kb);
      CL10.clSetKernelArg1p(kernel, 2, ko);
      CL10.clSetKernelArg1i(kernel, 3, n);
      run(queue, kernel, n);
      CL10.clEnqueueReadBuffer(queue, ko, true, 0L, result, null, null);
      int different = 0;
      for (int i = 0; i < n; i++) {
         if (Float.floatToRawIntBits(result.get(i)) != Float.floatToRawIntBits(terrainLike(a.get(i), b.get(i)))) {
            different++;
         }
      }

      out.printf(Locale.ROOT, "   float kernel (the self-test's): %,d values %s%n", n, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
      CL10.clReleaseKernel(kernel);
      CL10.clReleaseMemObject(ka);
      CL10.clReleaseMemObject(kb);
      CL10.clReleaseMemObject(ko);
      MemoryUtil.memFree(a);
      MemoryUtil.memFree(b);
      MemoryUtil.memFree(result);
   }

   /** GpuSelfTest's terrainLike in Java. */
   static float terrainLike(float x, float y) {
      float r = x * y + y / x;
      r = r - (float)Math.sqrt(x + y);
      r = r + Math.min(x, y) * 0.37f - Math.max(x, y) * 0.11f;
      return r * 0.5f + (float)Math.floor(x * 3.7f) / 7.0f;
   }

   private static void timeFloat(long queue, long context, long program) {
      FloatBuffer a = MemoryUtil.memAllocFloat(FLOATS);
      for (int i = 0; i < FLOATS; i++) {
         a.put(i, i * 0.001f);
      }

      long buffer = buffer(context, 4L * FLOATS);
      CL10.clEnqueueWriteBuffer(queue, buffer, true, 0L, a, null, null);
      long kernel = kernel(program, "floatWork");
      CL10.clSetKernelArg1p(kernel, 0, buffer);
      CL10.clSetKernelArg1i(kernel, 1, FLOATS);
      CL10.clSetKernelArg1i(kernel, 2, ITERATIONS);
      run(queue, kernel, FLOATS);
      CL10.clFinish(queue);
      long start = System.nanoTime();
      for (int r = 0; r < 10; r++) {
         run(queue, kernel, FLOATS);
      }

      CL10.clFinish(queue);
      out.printf(Locale.ROOT, "   time, heavy float kernel (%,d x %d steps): %.2f ms per launch%n", FLOATS, ITERATIONS, (System.nanoTime() - start) / 1.0e7);
      CL10.clReleaseKernel(kernel);
      CL10.clReleaseMemObject(buffer);
      MemoryUtil.memFree(a);
   }

   private static void roundTrips(long queue, long context, long program) {
      FloatBuffer a = MemoryUtil.memAllocFloat(TRIP_FLOATS);
      long buffer = buffer(context, 4L * TRIP_FLOATS);
      long kernel = kernel(program, "touch");
      CL10.clSetKernelArg1p(kernel, 0, buffer);
      CL10.clSetKernelArg1i(kernel, 1, TRIP_FLOATS);
      long start = 0L;
      for (int r = -100; r < ROUND_TRIPS; r++) {
         if (r == 0) {
            start = System.nanoTime();
         }

         CL10.clEnqueueWriteBuffer(queue, buffer, false, 0L, a, null, null);
         run(queue, kernel, TRIP_FLOATS);
         CL10.clEnqueueReadBuffer(queue, buffer, false, 0L, a, null, null);
         CL10.clFinish(queue);
      }

      out.printf(Locale.ROOT, "   time, round trip (copy 16 KB in, tiny kernel, copy out, wait): %.1f us each%n", (System.nanoTime() - start) / 1.0e3 / ROUND_TRIPS);
      CL10.clReleaseKernel(kernel);
      CL10.clReleaseMemObject(buffer);
      MemoryUtil.memFree(a);
   }

   private static void noiseCheck(long queue, long context, long program) {
      int values = noiseCount * points;
      DoubleBuffer d = MemoryUtil.memAllocDouble(noiseD.length).put(0, noiseD);
      IntBuffer p = MemoryUtil.memAllocInt(noiseP.length).put(0, noiseP);
      IntBuffer offsets = MemoryUtil.memAllocInt(noiseOffsets.length).put(0, noiseOffsets);
      DoubleBuffer x = MemoryUtil.memAllocDouble(points).put(0, xs);
      DoubleBuffer y = MemoryUtil.memAllocDouble(points).put(0, ys);
      DoubleBuffer z = MemoryUtil.memAllocDouble(points).put(0, zs);
      DoubleBuffer result = MemoryUtil.memAllocDouble(values);
      long[] buffers = {buffer(context, 8L * noiseD.length), buffer(context, 4L * noiseP.length), buffer(context, 4L * noiseOffsets.length),
         buffer(context, 8L * points), buffer(context, 8L * points), buffer(context, 8L * points), buffer(context, 8L * values)};
      CL10.clEnqueueWriteBuffer(queue, buffers[0], true, 0L, d, null, null);
      CL10.clEnqueueWriteBuffer(queue, buffers[1], true, 0L, p, null, null);
      CL10.clEnqueueWriteBuffer(queue, buffers[2], true, 0L, offsets, null, null);
      CL10.clEnqueueWriteBuffer(queue, buffers[3], true, 0L, x, null, null);
      CL10.clEnqueueWriteBuffer(queue, buffers[4], true, 0L, y, null, null);
      CL10.clEnqueueWriteBuffer(queue, buffers[5], true, 0L, z, null, null);
      long kernel = kernel(program, "mcNormalCheck");
      CL10.clSetKernelArg1p(kernel, 0, buffers[0]);
      CL10.clSetKernelArg1p(kernel, 1, buffers[1]);
      CL10.clSetKernelArg1p(kernel, 2, buffers[2]);
      CL10.clSetKernelArg1i(kernel, 3, noiseCount);
      CL10.clSetKernelArg1p(kernel, 4, buffers[3]);
      CL10.clSetKernelArg1p(kernel, 5, buffers[4]);
      CL10.clSetKernelArg1p(kernel, 6, buffers[5]);
      CL10.clSetKernelArg1i(kernel, 7, points);
      CL10.clSetKernelArg1p(kernel, 8, buffers[6]);
      run(queue, kernel, values);
      CL10.clEnqueueReadBuffer(queue, buffers[6], true, 0L, result, null, null);
      int different = 0;
      for (int i = 0; i < values; i++) {
         if (Double.doubleToRawLongBits(result.get(i)) != Double.doubleToRawLongBits(expected[i]) && different++ < 3) {
            out.printf(Locale.ROOT, "   noise value %d: game %s, OpenCL %s%n", i, expected[i], result.get(i));
         }
      }

      out.printf(Locale.ROOT, "   vanilla NormalNoise (doubles, the real game's values): %,d values %s%n", values, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
      long start = System.nanoTime();
      for (int r = 0; r < 20; r++) {
         run(queue, kernel, values);
      }

      CL10.clFinish(queue);
      out.printf(Locale.ROOT, "   time, noise kernel (%,d values): %.3f ms per launch%n", values, (System.nanoTime() - start) / 2.0e7);
      CL10.clReleaseKernel(kernel);
      for (long buffer : buffers) {
         CL10.clReleaseMemObject(buffer);
      }

      for (java.nio.Buffer buffer : new java.nio.Buffer[] {d, p, offsets, x, y, z, result}) {
         MemoryUtil.memFree(buffer);
      }
   }

   private static void check(int error, String what) {
      if (error != CL10.CL_SUCCESS) {
         throw new IllegalStateException(what + " failed: " + error);
      }
   }

   private static String platformString(long platform, int name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (CL10.clGetPlatformInfo(platform, name, (ByteBuffer)null, size) != CL10.CL_SUCCESS) {
            return "?";
         }

         ByteBuffer value = MemoryUtil.memAlloc((int)size.get(0));
         try {
            CL10.clGetPlatformInfo(platform, name, value, null);
            return MemoryUtil.memUTF8(value, Math.max(0, (int)size.get(0) - 1)).trim();
         } finally {
            MemoryUtil.memFree(value);
         }
      }
   }

   private static String deviceString(long device, int name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (CL10.clGetDeviceInfo(device, name, (ByteBuffer)null, size) != CL10.CL_SUCCESS) {
            return "?";
         }

         ByteBuffer value = MemoryUtil.memAlloc((int)size.get(0));
         try {
            CL10.clGetDeviceInfo(device, name, value, null);
            return MemoryUtil.memUTF8(value, Math.max(0, (int)size.get(0) - 1)).trim();
         } finally {
            MemoryUtil.memFree(value);
         }
      }
   }

   private static long deviceLong(long device, int name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         LongBuffer value = stack.callocLong(1);
         return CL10.clGetDeviceInfo(device, name, value, null) == CL10.CL_SUCCESS ? value.get(0) : 0L;
      }
   }

   private static long deviceInt(long device, int name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer value = stack.callocInt(1);
         return CL10.clGetDeviceInfo(device, name, value, null) == CL10.CL_SUCCESS ? value.get(0) & 0xFFFFFFFFL : 0L;
      }
   }

   // ---- CUDA, for the same kernels on an NVIDIA card ----

   private static void cuda() {
      if (!new File(System.getenv().getOrDefault("SystemRoot", "C:/Windows"), "System32/nvcuda.dll").isFile()) {
         out.println();
         out.println("CUDA: no NVIDIA driver, not compared");
         return;
      }

      String nvrtc = findNvrtc();
      out.println();
      out.println("== CUDA (the same kernels, for comparison)");
      if (nvrtc == null) {
         out.println("   no NVRTC (nvrtc64_*.dll) found: install the CUDA Toolkit 12.x, or run with -Dwgaccel.nvrtc=<path>; not compared");
         return;
      }

      // NVRTC's builtins beside it first (CUDA 13's bin/x64 is not on PATH; the mod's GpuSelfTest.preloadBuiltins).
      File[] builtins = new File(nvrtc).getAbsoluteFile().getParentFile().listFiles((dir, file) -> file.toLowerCase(Locale.ROOT).startsWith("nvrtc-builtins")
         && file.toLowerCase(Locale.ROOT).endsWith(".dll"));
      if (builtins != null && builtins.length > 0) {
         // LWJGL loads the builtins on its own too, from this setting or else from the toolkit's bin folder only.
         Arrays.sort(builtins, (a, b) -> b.getName().compareTo(a.getName()));
         Configuration.CUDA_NVRTC_BUILTINS_LIBRARY_NAME.set(builtins[0].getAbsolutePath());
         for (File library : builtins) {
            try {
               org.lwjgl.system.Library.loadNative(OpenClProbe.class, "org.lwjgl", library.getAbsolutePath());
               out.println("   loaded first: " + library.getName());
            } catch (Throwable failure) {
               out.println("   could not load " + library.getName() + ": " + failure);
            }
         }
      }

      Configuration.CUDA_NVRTC_LIBRARY_NAME.set(nvrtc);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         check(CU.cuInit(0), "cuInit", true);
         IntBuffer device = stack.mallocInt(1);
         check(CU.cuDeviceGet(device, 0), "cuDeviceGet", true);
         ByteBuffer name = stack.malloc(256);
         CU.cuDeviceGetName(name, device.get(0));
         IntBuffer major = stack.mallocInt(1);
         IntBuffer minor = stack.mallocInt(1);
         CU.cuDeviceGetAttribute(major, CU.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device.get(0));
         CU.cuDeviceGetAttribute(minor, CU.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device.get(0));
         out.printf(Locale.ROOT, "-- %s, compute %d.%d, NVRTC %s%n", MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name)), major.get(0), minor.get(0), nvrtc);
         PointerBuffer context = stack.mallocPointer(1);
         out.println("   step: the driver's context (cuCtxCreate)");
         check(CU.cuCtxCreate(context, 0, device.get(0)), "cuCtxCreate", true);
         out.println("   step: loading NVRTC");
         IntBuffer nvrtcMajor = stack.mallocInt(1);
         IntBuffer nvrtcMinor = stack.mallocInt(1);
         NVRTC.nvrtcVersion(nvrtcMajor, nvrtcMinor);
         out.printf(Locale.ROOT, "   NVRTC %d.%d loaded%n", nvrtcMajor.get(0), nvrtcMinor.get(0));
         try {
            long floats = cudaModule(selfTestSource + EXTRA, major.get(0) * 10 + minor.get(0));
            long noise = cudaModule(noiseSource, major.get(0) * 10 + minor.get(0));
            cudaTimeFloat(floats);
            cudaRoundTrips(floats);
            cudaNoise(noise);
         } finally {
            CU.cuCtxDestroy(context.get(0));
         }
      }
   }

   private static String findNvrtc() {
      String explicit = System.getProperty("wgaccel.nvrtc");
      if (explicit != null) {
         return explicit;
      }

      List<File> folders = new ArrayList<>();
      for (var entry : System.getenv().entrySet()) {
         if (entry.getKey().toUpperCase(Locale.ROOT).startsWith("CUDA_PATH")) {
            folders.add(new File(entry.getValue(), "bin"));
            folders.add(new File(entry.getValue(), "bin/x64"));
         }
      }

      File[] versions = new File(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), "NVIDIA GPU Computing Toolkit/CUDA").listFiles(File::isDirectory);
      if (versions != null) {
         Arrays.sort(versions, (a, b) -> b.getName().compareTo(a.getName()));
         for (File version : versions) {
            folders.add(new File(version, "bin"));
            folders.add(new File(version, "bin/x64"));
         }
      }

      for (File folder : folders) {
         File[] dlls = folder.listFiles((dir, file) -> file.toLowerCase(Locale.ROOT).startsWith("nvrtc64_") && file.toLowerCase(Locale.ROOT).endsWith(".dll")
            && !file.toLowerCase(Locale.ROOT).contains(".alt."));
         if (dlls != null && dlls.length > 0) {
            Arrays.sort(dlls, (a, b) -> b.getName().compareTo(a.getName()));
            return dlls[0].getAbsolutePath();
         }
      }

      return null;
   }

   /**
    * Machine code for the card itself first (sm_XX: a cubin, which a driver of the same CUDA major version loads even when
    * the toolkit is newer), then PTX for the driver to translate (compute_XX: needs a driver at least as new as the
    * toolkit). Every attempt's outcome is printed.
    */
   private static long cudaModule(String source, int capability) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer driver = stack.mallocInt(1);
         CU.cuDriverGetVersion(driver);
         out.printf(Locale.ROOT, "   driver's CUDA version %d.%d%n", driver.get(0) / 1000, driver.get(0) % 1000 / 10);
         for (String arch : new String[] {"sm_" + capability, "compute_" + capability, "compute_89", "compute_75"}) {
            boolean cubin = arch.startsWith("sm_");
            PointerBuffer program = stack.mallocPointer(1);
            check(NVRTC.nvrtcCreateProgram(program, stack.UTF8(source), stack.UTF8("probe.cu"), null, null), "nvrtcCreateProgram", false);
            PointerBuffer options = stack.pointers(stack.UTF8("--gpu-architecture=" + arch), stack.UTF8("--fmad=false"), stack.UTF8("--prec-div=true"),
               stack.UTF8("--prec-sqrt=true"), stack.UTF8("--ftz=false"));
            int compiled = NVRTC.nvrtcCompileProgram(program.get(0), options);
            if (compiled != NVRTC.NVRTC_SUCCESS) {
               PointerBuffer logSize = stack.mallocPointer(1);
               NVRTC.nvrtcGetProgramLogSize(program.get(0), logSize);
               ByteBuffer log = MemoryUtil.memAlloc((int)Math.max(1, logSize.get(0)));
               NVRTC.nvrtcGetProgramLog(program.get(0), log);
               String text = MemoryUtil.memASCII(log, Math.max(0, (int)logSize.get(0) - 1)).trim();
               MemoryUtil.memFree(log);
               out.printf(Locale.ROOT, "   %s: compile failed (%d): %s%n", arch, compiled, text.length() > 600 ? text.substring(0, 600) + " ..." : text);
               NVRTC.nvrtcDestroyProgram(program);
               continue;
            }

            PointerBuffer size = stack.mallocPointer(1);
            ByteBuffer image;
            if (cubin) {
               NVRTC.nvrtcGetCUBINSize(program.get(0), size);
               image = MemoryUtil.memAlloc((int)size.get(0));
               NVRTC.nvrtcGetCUBIN(program.get(0), image);
            } else {
               NVRTC.nvrtcGetPTXSize(program.get(0), size);
               image = MemoryUtil.memAlloc((int)size.get(0));
               NVRTC.nvrtcGetPTX(program.get(0), image);
            }

            NVRTC.nvrtcDestroyProgram(program);
            PointerBuffer module = stack.mallocPointer(1);
            int loaded = CU.cuModuleLoadData(module, image);
            MemoryUtil.memFree(image);
            if (loaded == CU.CUDA_SUCCESS) {
               out.printf(Locale.ROOT, "   %s: compiled and loaded (%s)%n", arch, cubin ? "machine code" : "PTX, translated by the driver");
               return module.get(0);
            }

            out.printf(Locale.ROOT, "   %s: compiled, but the driver would not load it (CUDA error %d%s)%n", arch, loaded,
               loaded == 222 ? ": PTX newer than this driver" : loaded == 209 ? ": no code for this card" : "");
         }

         throw new IllegalStateException("NVRTC could not compile for this card (see the attempts above)");
      }
   }

   private static long function(long module, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         check(CU.cuModuleGetFunction(function, module, stack.ASCII(name)), "cuModuleGetFunction " + name, true);
         return function.get(0);
      }
   }

   private static long cudaBuffer(long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         check(CU.cuMemAlloc(pointer, bytes), "cuMemAlloc", true);
         return pointer.get(0);
      }
   }

   /** Launches with 256 threads a block; arguments: Long (device pointer) or Integer. */
   private static void launch(long function, int items, Object... arguments) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer params = stack.mallocPointer(arguments.length);
         for (int i = 0; i < arguments.length; i++) {
            params.put(i, arguments[i] instanceof Long pointer ? MemoryUtil.memAddress(stack.longs(pointer)) : MemoryUtil.memAddress(stack.ints((Integer)arguments[i])));
         }

         check(CU.cuLaunchKernel(function, (items + 255) / 256, 1, 1, 256, 1, 1, 0, 0L, params, null), "cuLaunchKernel", true);
      }
   }

   private static void cudaTimeFloat(long module) {
      FloatBuffer a = MemoryUtil.memAllocFloat(FLOATS);
      for (int i = 0; i < FLOATS; i++) {
         a.put(i, i * 0.001f);
      }

      long buffer = cudaBuffer(4L * FLOATS);
      CU.cuMemcpyHtoD(buffer, a);
      long function = function(module, "floatWork");
      launch(function, FLOATS, buffer, FLOATS, ITERATIONS);
      CU.cuCtxSynchronize();
      long start = System.nanoTime();
      for (int r = 0; r < 10; r++) {
         launch(function, FLOATS, buffer, FLOATS, ITERATIONS);
      }

      CU.cuCtxSynchronize();
      out.printf(Locale.ROOT, "   time, heavy float kernel (%,d x %d steps): %.2f ms per launch%n", FLOATS, ITERATIONS, (System.nanoTime() - start) / 1.0e7);
      CU.cuMemFree(buffer);
      MemoryUtil.memFree(a);
   }

   private static void cudaRoundTrips(long module) {
      FloatBuffer a = MemoryUtil.memAllocFloat(TRIP_FLOATS);
      long buffer = cudaBuffer(4L * TRIP_FLOATS);
      long function = function(module, "touch");
      long start = 0L;
      for (int r = -100; r < ROUND_TRIPS; r++) {
         if (r == 0) {
            start = System.nanoTime();
         }

         CU.cuMemcpyHtoD(buffer, a);
         launch(function, TRIP_FLOATS, buffer, TRIP_FLOATS);
         CU.cuMemcpyDtoH(a, buffer);
         CU.cuCtxSynchronize();
      }

      out.printf(Locale.ROOT, "   time, round trip (copy 16 KB in, tiny kernel, copy out, wait): %.1f us each%n", (System.nanoTime() - start) / 1.0e3 / ROUND_TRIPS);
      CU.cuMemFree(buffer);
      MemoryUtil.memFree(a);
   }

   private static void cudaNoise(long module) {
      int values = noiseCount * points;
      DoubleBuffer result = MemoryUtil.memAllocDouble(values);
      long d = cudaBuffer(8L * noiseD.length);
      long p = cudaBuffer(4L * noiseP.length);
      long offsets = cudaBuffer(4L * noiseOffsets.length);
      long x = cudaBuffer(8L * points);
      long y = cudaBuffer(8L * points);
      long z = cudaBuffer(8L * points);
      long o = cudaBuffer(8L * values);
      CU.cuMemcpyHtoD(d, MemoryUtil.memAllocDouble(noiseD.length).put(0, noiseD));
      CU.cuMemcpyHtoD(p, MemoryUtil.memAllocInt(noiseP.length).put(0, noiseP));
      CU.cuMemcpyHtoD(offsets, MemoryUtil.memAllocInt(noiseOffsets.length).put(0, noiseOffsets));
      CU.cuMemcpyHtoD(x, MemoryUtil.memAllocDouble(points).put(0, xs));
      CU.cuMemcpyHtoD(y, MemoryUtil.memAllocDouble(points).put(0, ys));
      CU.cuMemcpyHtoD(z, MemoryUtil.memAllocDouble(points).put(0, zs));
      long function = function(module, "mcNormalCheck");
      launch(function, values, d, p, offsets, noiseCount, x, y, z, points, o);
      CU.cuMemcpyDtoH(result, o);
      int different = 0;
      for (int i = 0; i < values; i++) {
         if (Double.doubleToRawLongBits(result.get(i)) != Double.doubleToRawLongBits(expected[i])) {
            different++;
         }
      }

      out.printf(Locale.ROOT, "   vanilla NormalNoise (doubles, the real game's values): %,d values %s%n", values, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
      long start = System.nanoTime();
      for (int r = 0; r < 20; r++) {
         launch(function, values, d, p, offsets, noiseCount, x, y, z, points, o);
      }

      CU.cuCtxSynchronize();
      out.printf(Locale.ROOT, "   time, noise kernel (%,d values): %.3f ms per launch%n", values, (System.nanoTime() - start) / 2.0e7);
      for (long buffer : new long[] {d, p, offsets, x, y, z, o}) {
         CU.cuMemFree(buffer);
      }

      MemoryUtil.memFree(result);
   }

   private static void check(int result, String what, boolean cuda) {
      if (result != 0) {
         throw new IllegalStateException(what + " failed: " + result);
      }
   }
}
