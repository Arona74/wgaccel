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
      if (args.length > 0 && args[0].equals("--svm")) {
         svmChild(args.length > 1 && args[1].equals("cuinit"));
         return;
      }

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

         svmAsInTheGame();
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
            if (svm != 0L) {
               svmCheck(context, device, queue, extensions);
            }

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

               long doubles = build(context, device, DOUBLE_OPS, exact + std, "double arithmetic");
               if (doubles != 0L) {
                  doubleCheck(queue, context, doubles);
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

   /**
    * Double precision op by op, and the 64-bit integer steps of Minecraft's Xoroshiro random (a GPU without double
    * hardware emulates both; the ore veins compare Mth.clampedMap's division with a random float: an Intel Arc
    * integrated GPU gave other vein blocks than the game, 23 Oct 2026).
    */
   private static final String DOUBLE_OPS = """
      #pragma OPENCL EXTENSION cl_khr_fp64 : enable
      #pragma OPENCL FP_CONTRACT OFF
      __kernel void dops(__global const double* a, __global const double* b, __global const double* c, __global double* out, int n) {
          int i = get_global_id(0);
          if (i >= n) return;
          double x = a[i], y = b[i], z = c[i];
          out[i * 9 + 0] = x + y;
          out[i * 9 + 1] = x - y;
          out[i * 9 + 2] = x * y;
          out[i * 9 + 3] = x / y;
          out[i * 9 + 4] = sqrt(fabs(x));
          out[i * 9 + 5] = fma(x, y, z);
          out[i * 9 + 6] = floor(x);
          double t = (x - 0.4000000059604645) / (0.6000000238418579 - 0.4000000059604645);
          out[i * 9 + 7] = t < 0.0 ? 0.10000000149011612 : (t > 1.0 ? 0.30000001192092896 : 0.10000000149011612 + t * (0.30000001192092896 - 0.10000000149011612));
          out[i * 9 + 8] = (double)(float)x;
      }
      __kernel void lops(__global const ulong* a, __global const ulong* b, __global ulong* out, int n) {
          int i = get_global_id(0);
          if (i >= n) return;
          ulong lo = a[i], hi = b[i];
          ulong s = lo + hi;
          ulong k = ((s << 17) | (s >> 47)) + lo;
          ulong j = hi ^ lo;
          out[i * 4 + 0] = k;
          out[i * 4 + 1] = ((lo << 49) | (lo >> 15)) ^ j ^ (j << 21);
          out[i * 4 + 2] = lo * hi;
          out[i * 4 + 3] = (ulong)((long)lo >> 16);
      }
      // Minecraft's XoroshiroRandomSource.nextFloat ((nextLong() >>> 40) * 2^-24) written seven ways, the long from a real
      // Xoroshiro step: Intel's compiler gave 255 + the value for the first when bit 63 was set.
      __kernel void nfloat(__global const ulong* a, __global const ulong* b, __global float* out, int n) {
          int i = get_global_id(0);
          if (i >= n) return;
          ulong lo = a[i], hi = b[i];
          ulong s = lo + hi;
          ulong x = ((s << 17) | (s >> 47)) + lo;
          out[i * 7 + 0] = (float)(long)(x >> 40) * 5.9604645E-8f;
          out[i * 7 + 1] = (float)(long)((x >> 40) & 16777215UL) * 5.9604645E-8f;
          out[i * 7 + 2] = (float)(int)(((uint)(x >> 32)) >> 8) * 5.9604645E-8f;
          out[i * 7 + 3] = (float)(int)(as_uint2(x).s1 >> 8) * 5.9604645E-8f;
          volatile ulong t = x >> 40;
          out[i * 7 + 4] = (float)(long)t * 5.9604645E-8f;
          out[i * 7 + 5] = (float)((double)(x >> 40) * 5.9604644775390625E-8);
          out[i * 7 + 6] = (float)(uint)(x >> 40) * 5.9604645E-8f;
      }
      """;

   private static final String[] FLOAT_WAYS = {"A (float)(long)(x >> 40)", "B masked to 24 bits", "C high word >> 8 (32-bit)", "D as_uint2(x).s1 >> 8",
      "E through a volatile", "F through double", "G (float)(uint)(x >> 40)"};

   private static void nextFloatCheck(long queue, long context, long program) {
      int n = 1 << 18;
      long[] los = new long[n];
      long[] his = new long[n];
      Random random = new Random(5L);
      for (int i = 0; i < n; i++) {
         los[i] = random.nextLong();
         his[i] = random.nextLong();
      }

      java.nio.LongBuffer a = MemoryUtil.memAllocLong(n).put(0, los);
      java.nio.LongBuffer b = MemoryUtil.memAllocLong(n).put(0, his);
      FloatBuffer result = MemoryUtil.memAllocFloat(7 * n);
      long ka = buffer(context, 8L * n);
      long kb = buffer(context, 8L * n);
      long ko = buffer(context, 28L * n);
      CL10.nclEnqueueWriteBuffer(queue, ka, 1, 0L, 8L * n, MemoryUtil.memAddress(a), 0, 0L, 0L);
      CL10.nclEnqueueWriteBuffer(queue, kb, 1, 0L, 8L * n, MemoryUtil.memAddress(b), 0, 0L, 0L);
      long kernel = kernel(program, "nfloat");
      CL10.clSetKernelArg1p(kernel, 0, ka);
      CL10.clSetKernelArg1p(kernel, 1, kb);
      CL10.clSetKernelArg1p(kernel, 2, ko);
      CL10.clSetKernelArg1i(kernel, 3, n);
      run(queue, kernel, n);
      CL10.clEnqueueReadBuffer(queue, ko, true, 0L, result, null, null);
      int[] different = new int[7];
      String[] example = new String[7];
      for (int i = 0; i < n; i++) {
         long x = Long.rotateLeft(los[i] + his[i], 17) + los[i];
         float expected = (float)(x >>> 40) * 5.9604645E-8F;
         for (int k = 0; k < 7; k++) {
            float got = result.get(i * 7 + k);
            if (Float.floatToRawIntBits(got) != Float.floatToRawIntBits(expected) && different[k]++ == 0) {
               example[k] = String.format(Locale.ROOT, "java %s, gpu %s", expected, got);
            }
         }
      }

      for (int k = 0; k < 7; k++) {
         out.printf(Locale.ROOT, "   nextFloat %-28s %,d values: %s%n", FLOAT_WAYS[k], n, different[k] == 0 ? "BIT-IDENTICAL" : different[k] + " DIFFER, e.g. " + example[k]);
      }

      CL10.clReleaseMemObject(ka);
      CL10.clReleaseMemObject(kb);
      CL10.clReleaseMemObject(ko);
      CL10.clReleaseKernel(kernel);
      MemoryUtil.memFree(a);
      MemoryUtil.memFree(b);
      MemoryUtil.memFree(result);
   }

   private static final String[] DOUBLE_NAMES = {"a + b", "a - b", "a * b", "a / b", "sqrt", "fma", "floor", "clampedMap (veins)", "(float) cast"};

   private static void doubleCheck(long queue, long context, long program) {
      int n = 1 << 18;
      double[] xs = new double[n];
      double[] ys = new double[n];
      double[] zs = new double[n];
      Random random = new Random(3L);
      for (int i = 0; i < n; i++) {
         switch (i % 4) {
            // As the veins: |noise| near 0.4..0.6, and distances 0..20 over 20.
            case 0 -> {
               xs[i] = 0.3 + random.nextDouble() * 0.4;
               ys[i] = 20.0;
               zs[i] = random.nextDouble();
            }
            case 1 -> {
               xs[i] = random.nextInt(21);
               ys[i] = 20.0;
               zs[i] = -0.2;
            }
            // Any magnitude, either sign, subnormals among them.
            case 2 -> {
               xs[i] = Double.longBitsToDouble(random.nextLong());
               ys[i] = Double.longBitsToDouble(random.nextLong());
               zs[i] = Double.longBitsToDouble(random.nextLong());
            }
            default -> {
               xs[i] = (random.nextDouble() - 0.5) * Math.pow(2.0, random.nextInt(80) - 40);
               ys[i] = (random.nextDouble() - 0.5) * Math.pow(2.0, random.nextInt(80) - 40);
               zs[i] = (random.nextDouble() - 0.5) * Math.pow(2.0, random.nextInt(80) - 40);
            }
         }
      }

      DoubleBuffer a = MemoryUtil.memAllocDouble(n).put(0, xs);
      DoubleBuffer b = MemoryUtil.memAllocDouble(n).put(0, ys);
      DoubleBuffer c = MemoryUtil.memAllocDouble(n).put(0, zs);
      DoubleBuffer result = MemoryUtil.memAllocDouble(9 * n);
      long ka = buffer(context, 8L * n);
      long kb = buffer(context, 8L * n);
      long kc = buffer(context, 8L * n);
      long ko = buffer(context, 72L * n);
      CL10.clEnqueueWriteBuffer(queue, ka, true, 0L, a, null, null);
      CL10.clEnqueueWriteBuffer(queue, kb, true, 0L, b, null, null);
      CL10.clEnqueueWriteBuffer(queue, kc, true, 0L, c, null, null);
      long kernel = kernel(program, "dops");
      CL10.clSetKernelArg1p(kernel, 0, ka);
      CL10.clSetKernelArg1p(kernel, 1, kb);
      CL10.clSetKernelArg1p(kernel, 2, kc);
      CL10.clSetKernelArg1p(kernel, 3, ko);
      CL10.clSetKernelArg1i(kernel, 4, n);
      run(queue, kernel, n);
      CL10.clEnqueueReadBuffer(queue, ko, true, 0L, result, null, null);
      int[] different = new int[9];
      String[] example = new String[9];
      for (int i = 0; i < n; i++) {
         double x = xs[i];
         double y = ys[i];
         double z = zs[i];
         double t = (x - (double)0.4f) / ((double)0.6f - (double)0.4f);
         double[] expected = {x + y, x - y, x * y, x / y, Math.sqrt(Math.abs(x)), Math.fma(x, y, z), Math.floor(x),
            t < 0.0 ? (double)0.1f : (t > 1.0 ? (double)0.3f : (double)0.1f + t * ((double)0.3f - (double)0.1f)), (double)(float)x};
         for (int k = 0; k < 9; k++) {
            double got = result.get(i * 9 + k);
            boolean same = Double.doubleToRawLongBits(got) == Double.doubleToRawLongBits(expected[k]) || Double.isNaN(got) && Double.isNaN(expected[k]);
            if (!same && different[k]++ == 0) {
               example[k] = String.format(Locale.ROOT, "a=%s b=%s c=%s: java %s, gpu %s", x, y, z, expected[k], got);
            }
         }
      }

      for (int k = 0; k < 9; k++) {
         out.printf(Locale.ROOT, "   double %-20s %,d values: %s%n", DOUBLE_NAMES[k], n, different[k] == 0 ? "BIT-IDENTICAL" : different[k] + " DIFFER, e.g. " + example[k]);
      }

      CL10.clReleaseMemObject(ka);
      CL10.clReleaseMemObject(kb);
      CL10.clReleaseMemObject(kc);
      CL10.clReleaseMemObject(ko);
      CL10.clReleaseKernel(kernel);
      MemoryUtil.memFree(a);
      MemoryUtil.memFree(b);
      MemoryUtil.memFree(c);
      MemoryUtil.memFree(result);
      longCheck(queue, context, program);
   }

   private static void longCheck(long queue, long context, long program) {
      int n = 1 << 18;
      long[] los = new long[n];
      long[] his = new long[n];
      Random random = new Random(4L);
      for (int i = 0; i < n; i++) {
         los[i] = random.nextLong();
         his[i] = random.nextLong();
      }

      java.nio.LongBuffer a = MemoryUtil.memAllocLong(n).put(0, los);
      java.nio.LongBuffer b = MemoryUtil.memAllocLong(n).put(0, his);
      java.nio.LongBuffer result = MemoryUtil.memAllocLong(4 * n);
      long ka = buffer(context, 8L * n);
      long kb = buffer(context, 8L * n);
      long ko = buffer(context, 32L * n);
      CL10.nclEnqueueWriteBuffer(queue, ka, 1, 0L, 8L * n, MemoryUtil.memAddress(a), 0, 0L, 0L);
      CL10.nclEnqueueWriteBuffer(queue, kb, 1, 0L, 8L * n, MemoryUtil.memAddress(b), 0, 0L, 0L);
      long kernel = kernel(program, "lops");
      CL10.clSetKernelArg1p(kernel, 0, ka);
      CL10.clSetKernelArg1p(kernel, 1, kb);
      CL10.clSetKernelArg1p(kernel, 2, ko);
      CL10.clSetKernelArg1i(kernel, 3, n);
      run(queue, kernel, n);
      CL10.nclEnqueueReadBuffer(queue, ko, 1, 0L, 32L * n, MemoryUtil.memAddress(result), 0, 0L, 0L);
      int different = 0;
      for (int i = 0; i < n; i++) {
         long lo = los[i];
         long hi = his[i];
         long j = hi ^ lo;
         long[] expected = {Long.rotateLeft(lo + hi, 17) + lo, Long.rotateLeft(lo, 49) ^ j ^ (j << 21), lo * hi, lo >> 16};
         for (int k = 0; k < 4; k++) {
            different += result.get(i * 4 + k) == expected[k] ? 0 : 1;
         }
      }

      out.printf(Locale.ROOT, "   64-bit integers (Xoroshiro steps, multiply, shift) %,d values: %s%n", 4 * n, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
      nextFloatCheck(queue, context, program);
      CL10.clReleaseMemObject(ka);
      CL10.clReleaseMemObject(kb);
      CL10.clReleaseMemObject(ko);
      CL10.clReleaseKernel(kernel);
      MemoryUtil.memFree(a);
      MemoryUtil.memFree(b);
      MemoryUtil.memFree(result);
   }

   private static final String SVM_SOURCE = """
      __kernel void svmAdd(__global int* a, int n) { int i = get_global_id(0); if (i < n) a[i] += 1; }
      __kernel void svmTable(__global const ulong* table, int n) {
          __global int* a = (__global int*)table[0];
          int i = get_global_id(0);
          if (i < n) a[i] += 10;
      }
      """;

   /**
    * Shared virtual memory step by step, as the mod's OpenCL backend uses it (each call's result printed): allocate, a
    * second queue made the OpenCL 2.0 way, copies in and out on both queues, map and unmap, a kernel given the pointer
    * as its argument and one reading it from a table (the batches do), told of the allocations through SVM_PTRS. An RTX
    * 5060 laptop's OpenCL said it has coarse-grained SVM, yet the mod's copies failed with -36 and its launches with -6
    * (24 Oct 2026).
    */
   private static void svmCheck(long context, long device, long queue, String extensions) {
      String related = java.util.Arrays.stream(extensions.split(" ")).filter(e -> e.contains("address") || e.contains("svm") || e.contains("unified")
         || e.contains("_nv_")).collect(java.util.stream.Collectors.joining(" "));
      out.println("   SVM step by step (extensions about memory: " + (related.isEmpty() ? "none" : related) + "):");
      int n = 1024;
      long bytes = 4L * n;
      long svm = CL20.nclSVMAlloc(context, CL10.CL_MEM_READ_WRITE, bytes, 0);
      out.println("     clSVMAlloc: " + (svm == 0L ? "FAILED (null)" : "ok"));
      if (svm == 0L) {
         return;
      }

      java.nio.IntBuffer host = MemoryUtil.memAllocInt(n);
      long tableSvm = CL20.nclSVMAlloc(context, CL10.CL_MEM_READ_WRITE, 8L, 0);
      long queue2 = 0L;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         queue2 = CL20.clCreateCommandQueueWithProperties(context, device, (java.nio.LongBuffer)null, error);
         out.println("     clCreateCommandQueueWithProperties: " + error.get(0));
         for (int i = 0; i < n; i++) {
            host.put(i, i);
         }

         long hostAddress = MemoryUtil.memAddress(host);
         out.println("     clEnqueueSVMMemcpy in, OpenCL 1.x queue: " + CL20.nclEnqueueSVMMemcpy(queue, 1, svm, hostAddress, bytes, 0, 0L, 0L));
         if (queue2 != 0L) {
            out.println("     clEnqueueSVMMemcpy in, OpenCL 2.0 queue: " + CL20.nclEnqueueSVMMemcpy(queue2, 1, svm, hostAddress, bytes, 0, 0L, 0L));
         }

         int mapped = CL20.nclEnqueueSVMMap(queue, 1, CL10.CL_MAP_READ | CL10.CL_MAP_WRITE, svm, bytes, 0, 0L, 0L);
         out.println("     clEnqueueSVMMap: " + mapped);
         if (mapped == CL10.CL_SUCCESS) {
            // Mapped: the host may write the SVM block directly (fills it again, in case the copies failed).
            for (int i = 0; i < n; i++) {
               MemoryUtil.memPutInt(svm + 4L * i, i);
            }

            out.println("     clEnqueueSVMUnmap: " + CL20.nclEnqueueSVMUnmap(queue, svm, 0, 0L, 0L) + ", finish " + CL10.clFinish(queue));
         }

         long program = build(context, device, SVM_SOURCE, "", "SVM kernels");
         if (program == 0L) {
            return;
         }

         long add = kernel(program, "svmAdd");
         long fromTable = kernel(program, "svmTable");
         out.println("     clSetKernelArgSVMPointer: " + CL20.nclSetKernelArgSVMPointer(add, 0, svm) + ", int argument " + CL10.clSetKernelArg1i(add, 1, n));
         PointerBuffer live = stack.pointers(svm);
         out.println("     clSetKernelExecInfo SVM_PTRS: " + CL20.nclSetKernelExecInfo(add, CL20.CL_KERNEL_EXEC_INFO_SVM_PTRS, 8L, MemoryUtil.memAddress(live)));
         PointerBuffer global = stack.pointers(n);
         out.println("     launch with the pointer argument: " + CL10.clEnqueueNDRangeKernel(queue, add, 1, null, global, null, null, null) + ", finish "
            + CL10.clFinish(queue));
         if (tableSvm != 0L) {
            int filled = CL20.nclEnqueueSVMMap(queue, 1, CL10.CL_MAP_WRITE, tableSvm, 8L, 0, 0L, 0L);
            if (filled == CL10.CL_SUCCESS) {
               MemoryUtil.memPutLong(tableSvm, svm);
               CL20.nclEnqueueSVMUnmap(queue, tableSvm, 0, 0L, 0L);
            }

            out.println("     table filled (map " + filled + "); arguments " + CL20.nclSetKernelArgSVMPointer(fromTable, 0, tableSvm) + ", "
               + CL10.clSetKernelArg1i(fromTable, 1, n) + "; SVM_PTRS " + CL20.nclSetKernelExecInfo(fromTable, CL20.CL_KERNEL_EXEC_INFO_SVM_PTRS, 16L,
               MemoryUtil.memAddress(stack.pointers(svm, tableSvm))));
            out.println("     launch reading the address from a table: " + CL10.clEnqueueNDRangeKernel(queue, fromTable, 1, null, global, null, null, null)
               + ", finish " + CL10.clFinish(queue));
         }

         for (int i = 0; i < n; i++) {
            host.put(i, -1);
         }

         int back = CL20.nclEnqueueSVMMemcpy(queue, 1, hostAddress, svm, bytes, 0, 0L, 0L);
         if (back != CL10.CL_SUCCESS && CL20.nclEnqueueSVMMap(queue, 1, CL10.CL_MAP_READ, svm, bytes, 0, 0L, 0L) == CL10.CL_SUCCESS) {
            MemoryUtil.memCopy(svm, hostAddress, bytes);
            CL20.nclEnqueueSVMUnmap(queue, svm, 0, 0L, 0L);
            CL10.clFinish(queue);
            back = 1000 + back;
         }

         int wrong = 0;
         for (int i = 0; i < n; i++) {
            wrong += host.get(i) != i + 11 ? 1 : 0;
         }

         out.println("     copy out: " + (back >= 1000 ? "failed (" + (back - 1000) + "), read through a map instead" : String.valueOf(back)) + "; values "
            + (wrong == 0 ? "RIGHT (both kernels ran)" : wrong + " of " + n + " wrong (first: " + host.get(0) + ", expected 11)"));
         CL10.clReleaseKernel(add);
         CL10.clReleaseKernel(fromTable);
         CL10.clReleaseProgram(program);
      } catch (Throwable failure) {
         out.println("     SVM check failed: " + failure);
      } finally {
         if (queue2 != 0L) {
            CL10.clReleaseCommandQueue(queue2);
         }

         CL10.clFinish(queue);
         CL20.nclSVMFree(context, svm);
         if (tableSvm != 0L) {
            CL20.nclSVMFree(context, tableSvm);
         }

         MemoryUtil.memFree(host);
      }
   }

   /**
    * NVIDIA's OpenCL as the game starts it: the probe's SVM steps passed on an RTX 5060 laptop, yet in the game every copy
    * failed with -36 and every launch with -6 (24 Oct 2026). The game sets CUDA_DEVICE_MAX_CONNECTIONS=32 before the
    * driver starts and calls cuInit before OpenCL (its GPU list); each case runs in a process of its own (both act at the
    * driver's start), with a 16 MB copy and a copy from another thread added.
    */
   private static void svmAsInTheGame() {
      String launcher = ProcessHandle.current().info().command().orElse("java");
      String jar;
      try {
         jar = new File(OpenClProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
      } catch (Exception failure) {
         return;
      }

      out.println();
      out.println("== NVIDIA's OpenCL as the game starts it (each in a process of its own)");
      String[][] cases = {{"plain", "", ""}, {"CUDA_DEVICE_MAX_CONNECTIONS=32", "32", ""}, {"cuInit first", "", "cuinit"},
         {"CUDA_DEVICE_MAX_CONNECTIONS=32 and cuInit first", "32", "cuinit"}};
      for (String[] each : cases) {
         out.println("-- " + each[0]);
         try {
            ProcessBuilder builder = new ProcessBuilder(launcher, "-cp", jar, OpenClProbe.class.getName(), "--svm", each[2]).redirectErrorStream(true);
            builder.environment().remove("CUDA_DEVICE_MAX_CONNECTIONS");
            if (!each[1].isEmpty()) {
               builder.environment().put("CUDA_DEVICE_MAX_CONNECTIONS", each[1]);
            }

            Process process = builder.start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            text.lines().filter(line -> !line.startsWith("WARNING") && !line.startsWith("[LWJGL]") && !line.isBlank()).forEach(out::println);
            out.println(done ? "   (exit " + process.exitValue() + ")" : "   (did not finish in 2 minutes)");
         } catch (Exception failure) {
            out.println("   not run: " + failure);
         }
      }
   }

   /** The child process of {@link #svmAsInTheGame}: the SVM steps on every NVIDIA OpenCL GPU, then a big copy and one from another thread. */
   private static void svmChild(boolean cuInit) {
      out = System.out;
      if (cuInit) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            out.println("   cuInit " + org.lwjgl.cuda.CU.cuInit(0) + ", cuDeviceGetCount " + org.lwjgl.cuda.CU.cuDeviceGetCount(count) + " (" + count.get(0) + ")");
         } catch (Throwable failure) {
            out.println("   cuInit failed: " + failure);
         }
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         if (CL10.clGetPlatformIDs(null, count) != CL10.CL_SUCCESS) {
            out.println("   no OpenCL");
            return;
         }

         PointerBuffer platforms = stack.mallocPointer(count.get(0));
         CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
         for (int p = 0; p < platforms.capacity(); p++) {
            if (!platformString(platforms.get(p), CL10.CL_PLATFORM_NAME).contains("NVIDIA")) {
               continue;
            }

            IntBuffer found = stack.mallocInt(1);
            if (CL10.clGetDeviceIDs(platforms.get(p), CL10.CL_DEVICE_TYPE_GPU, null, found) != CL10.CL_SUCCESS || found.get(0) == 0) {
               continue;
            }

            PointerBuffer ids = stack.mallocPointer(found.get(0));
            CL10.clGetDeviceIDs(platforms.get(p), CL10.CL_DEVICE_TYPE_GPU, ids, (IntBuffer)null);
            long device = ids.get(0);
            IntBuffer error = stack.mallocInt(1);
            long context = CL10.clCreateContext(stack.pointers(CL10.CL_CONTEXT_PLATFORM, platforms.get(p), 0L), device, null, 0L, error);
            out.println("   clCreateContext " + error.get(0));
            // The mod's queues are made the OpenCL 2.0 way.
            long queue = CL20.clCreateCommandQueueWithProperties(context, device, (java.nio.LongBuffer)null, error);
            out.println("   clCreateCommandQueueWithProperties " + error.get(0));
            svmCheck(context, device, queue, deviceString(device, CL10.CL_DEVICE_EXTENSIONS));
            bigAndThreaded(context, queue);
            underLoad(context, device);
            CL10.clReleaseCommandQueue(queue);
            CL10.clReleaseContext(context);
         }
      }
   }

   private static final String ADDRESS_SOURCE = """
      __kernel void spin(__global int* x, int n) {
          int v = x[0] + (int)get_global_id(0);
          for (int i = 0; i < n; i++) v = v * 1664525 + 1013904223;
          x[1 + get_global_id(0)] = v;
      }
      __kernel void address(__global char* buffer, __global ulong* out) { out[0] = (ulong)buffer; }
      __kernel void byAddress(ulong a, int n) {
          __global int* p = (__global int*)a;
          int i = get_global_id(0);
          if (i < n) p[i] += 1;
      }
      __kernel void byTable(ulong table, int n) {
          __global int* p = (__global int*)((__global const ulong*)table)[0];
          int i = get_global_id(0);
          if (i < n) p[i] += 10;
      }
      """;

   /**
    * Under load, as in the game, where SVM failed (an allocation -4 and the process gone, RTX 5060 laptop, 24 Oct 2026):
    * (1) SVM allocated and copied on one thread while a long kernel runs on another; (2) plain device buffers used by
    * their GPU address (read by a kernel, passed as a number, read from a table; written and read while another kernel
    * runs), the way the mod could use NVIDIA's OpenCL without SVM.
    */
   private static void underLoad(long context, long device) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long busy = CL20.clCreateCommandQueueWithProperties(context, device, (java.nio.LongBuffer)null, error);
         long other = CL20.clCreateCommandQueueWithProperties(context, device, (java.nio.LongBuffer)null, error);
         long program = build(context, device, ADDRESS_SOURCE, "", "address kernels");
         if (program == 0L) {
            return;
         }

         long spin = kernel(program, "spin");
         int threads = 1 << 16;
         long spinBuffer = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_WRITE, 4L * (threads + 1), error);
         CL10.clSetKernelArg1p(spin, 0, spinBuffer);
         // A few hundred ms on an RTX 5060 (its time is printed), under Windows' 2 s GPU timeout, so the calls below happen while it runs.
         int steps = 1 << 25;
         CL10.clSetKernelArg1i(spin, 1, steps);
         long started = System.nanoTime();
         CL10.clEnqueueNDRangeKernel(busy, spin, 1, null, stack.pointers(threads), null, null, null);
         CL10.clFinish(busy);
         out.printf(Locale.ROOT, "   a long kernel alone: %.0f ms%n", (System.nanoTime() - started) / 1.0e6);

         // (1) SVM while a kernel runs.
         CL10.clEnqueueNDRangeKernel(busy, spin, 1, null, stack.pointers(threads), null, null, null);
         CL10.clFlush(busy);
         long svm = CL20.nclSVMAlloc(context, CL10.CL_MEM_READ_WRITE, 1L << 20, 0);
         java.nio.ByteBuffer host = MemoryUtil.memAlloc(1 << 20);
         int copy = svm == 0L ? 1 : CL20.nclEnqueueSVMMemcpy(other, 1, svm, MemoryUtil.memAddress(host), 1L << 20, 0, 0L, 0L);
         out.println("   SVM while a kernel runs: alloc " + (svm == 0L ? "FAILED" : "ok") + ", copy in " + (svm == 0L ? "-" : String.valueOf(copy)) + ", the kernel's finish "
            + CL10.clFinish(busy));
         if (svm != 0L) {
            CL20.nclSVMFree(context, svm);
         }

         // (2) Plain buffers by address.
         int n = 1024;
         long data = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_WRITE, 4L * n, error);
         long table = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_WRITE, 8L, error);
         long result = CL10.clCreateBuffer(context, CL10.CL_MEM_READ_WRITE, 8L, error);
         long address = kernel(program, "address");
         CL10.clSetKernelArg1p(address, 0, data);
         CL10.clSetKernelArg1p(address, 1, result);
         int ran = CL10.clEnqueueNDRangeKernel(other, address, 1, null, stack.pointers(1), null, null, null);
         java.nio.ByteBuffer value = stack.malloc(8);
         int read = CL10.clEnqueueReadBuffer(other, result, true, 0L, value, null, null);
         long gpuAddress = value.getLong(0);
         out.printf(Locale.ROOT, "   a buffer's GPU address: kernel %d, read %d, 0x%x%n", ran, read, gpuAddress);
         java.nio.IntBuffer ints = MemoryUtil.memAllocInt(n);
         for (int i = 0; i < n; i++) {
            ints.put(i, i);
         }

         // The long kernel runs again meanwhile: the writes, launches and reads below happen beside it.
         CL10.clEnqueueNDRangeKernel(busy, spin, 1, null, stack.pointers(threads), null, null, null);
         CL10.clFlush(busy);
         int write = CL10.clEnqueueWriteBuffer(other, data, true, 0L, ints, null, null);
         value.putLong(0, gpuAddress);
         int writeTable = CL10.clEnqueueWriteBuffer(other, table, true, 0L, value, null, null);
         long byAddress = kernel(program, "byAddress");
         CL10.clSetKernelArg1l(byAddress, 0, gpuAddress);
         CL10.clSetKernelArg1i(byAddress, 1, n);
         int first = CL10.clEnqueueNDRangeKernel(other, byAddress, 1, null, stack.pointers(n), null, null, null);
         // The table's own address, then its kernel.
         CL10.clSetKernelArg1p(address, 0, table);
         CL10.clEnqueueNDRangeKernel(other, address, 1, null, stack.pointers(1), null, null, null);
         CL10.clEnqueueReadBuffer(other, result, true, 0L, value, null, null);
         long byTable = kernel(program, "byTable");
         CL10.clSetKernelArg1l(byTable, 0, value.getLong(0));
         CL10.clSetKernelArg1i(byTable, 1, n);
         int second = CL10.clEnqueueNDRangeKernel(other, byTable, 1, null, stack.pointers(n), null, null, null);
         int back = CL10.clEnqueueReadBuffer(other, data, true, 0L, ints, null, null);
         int wrong = 0;
         for (int i = 0; i < n; i++) {
            wrong += ints.get(i) != i + 11 ? 1 : 0;
         }

         out.println("   buffers by address, beside a running kernel: write " + write + ", table " + writeTable + ", launch by address " + first
            + ", launch by table " + second + ", read " + back + ", the long kernel's finish " + CL10.clFinish(busy) + "; values "
            + (wrong == 0 ? "RIGHT" : wrong + " of " + n + " wrong (first " + ints.get(0) + ", expected 11)"));
         MemoryUtil.memFree(ints);
         MemoryUtil.memFree(host);
         CL10.clReleaseMemObject(data);
         CL10.clReleaseMemObject(table);
         CL10.clReleaseMemObject(result);
         CL10.clReleaseMemObject(spinBuffer);
         CL10.clReleaseCommandQueue(busy);
         CL10.clReleaseCommandQueue(other);
      } catch (Throwable failure) {
         out.println("   under load: failed: " + failure);
      }
   }

   /** A 16 MB copy in and out (the self-test's size), then a copy on this queue from another thread. */
   private static void bigAndThreaded(long context, long queue) {
      long bytes = 16L << 20;
      long svm = CL20.nclSVMAlloc(context, CL10.CL_MEM_READ_WRITE, bytes, 0);
      java.nio.ByteBuffer host = MemoryUtil.memAlloc((int)bytes);
      try {
         for (int i = 0; i < bytes; i += 4) {
            host.putInt(i, i);
         }

         out.println("   16 MB: alloc " + (svm == 0L ? "FAILED" : "ok") + ", copy in " + CL20.nclEnqueueSVMMemcpy(queue, 1, svm, MemoryUtil.memAddress(host), bytes, 0, 0L, 0L));
         host.putInt(0, -1);
         int back = CL20.nclEnqueueSVMMemcpy(queue, 1, MemoryUtil.memAddress(host), svm, bytes, 0, 0L, 0L);
         out.println("   16 MB: copy out " + back + ", values " + (host.getInt(4096) == 4096 && host.getInt(0) == 0 ? "RIGHT" : "WRONG"));
         int[] result = new int[1];
         Thread other = new Thread(() -> result[0] = CL20.nclEnqueueSVMMemcpy(queue, 1, svm, MemoryUtil.memAddress(host), 4096L, 0, 0L, 0L));
         other.start();
         other.join();
         out.println("   copy from another thread on this queue: " + result[0]);
      } catch (Throwable failure) {
         out.println("   big copy failed: " + failure);
      } finally {
         MemoryUtil.memFree(host);
         if (svm != 0L) {
            CL20.nclSVMFree(context, svm);
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
