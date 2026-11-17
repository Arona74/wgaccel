package com.wgaccel.gpu;

import static com.wgaccel.gpu.Hip.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link GpuSelfTest} for AMD cards: the HIP runtime and hipRTC through {@link Hip}, the same kernel compiled at run
 * time, and its floats compared with Java's bit for bit. Uses no Minecraft classes (`gradlew.bat hipCheck`).
 */
public final class HipSelfTest {
   /**
    * hipRTC's options for Java-identical floats, after the architecture: no fused multiply-add (HIP's default fuses
    * a * b + c), subnormals kept, float division and square root correctly rounded (HIP's default, said anyway), no
    * fast maths. NVRTC's {@link GpuSelfTest#EXACT_FLAGS} in clang's words.
    */
   public static final String[] EXACT_FLAGS = {"-ffp-contract=off", "-fno-gpu-flush-denormals-to-zero", "-fhip-fp32-correctly-rounded-divide-sqrt",
      "-fno-fast-math"};
   private static volatile String[] exactOptions;
   /** The card and its architecture, for the logs. */
   public static volatile String deviceName = "unknown";
   public static volatile String architecture;

   private HipSelfTest() {
   }

   /**
    * hipRTC's options: the card's own architecture (-Dwgaccel.hipArch=gfx... compiles for another, for tests), then
    * {@link #EXACT_FLAGS}.
    */
   public static synchronized String[] exactOptions() {
      String[] options = exactOptions;
      if (options != null) {
         return options;
      }

      String arch = System.getProperty("wgaccel.hipArch");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         check(hipInit(0), "hipInit");
         ByteBuffer name = stack.malloc(256);
         check(hipDeviceGetName(name, GpuDevice.index()), "hipDeviceGetName");
         deviceName = MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name));
         if (arch == null) {
            ByteBuffer properties = MemoryUtil.memCalloc(PROPERTIES_BYTES);
            try {
               check(hipGetDeviceProperties(properties, GpuDevice.index()), "hipGetDeviceProperties");
               arch = Hip.architecture(properties);
            } finally {
               MemoryUtil.memFree(properties);
            }
         }
      }

      if (arch == null) {
         throw new IllegalStateException("the architecture of " + deviceName + " is not in its hipDeviceProp_t; start Java with -Dwgaccel.hipArch=gfx...");
      }

      architecture = arch;
      options = new String[EXACT_FLAGS.length + 1];
      options[0] = "--offload-arch=" + arch;
      System.arraycopy(EXACT_FLAGS, 0, options, 1, EXACT_FLAGS.length);
      exactOptions = options;
      return options;
   }

   /**
    * Where a source's code object is kept (beside CUDA's, in {@link GpuSelfTest#cacheDirectory}, config gpuCubinCache):
    * the SHA-256 of the source, the options (the architecture with them), hipRTC's and the runtime's versions; or null
    * without a cache.
    */
   public static java.nio.file.Path codeObjectPath(String source) {
      java.nio.file.Path directory = GpuSelfTest.cacheDirectory;
      if (directory == null || !GpuSelfTest.cubinCache) {
         return null;
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer runtime = stack.mallocInt(1);
         check(hipRuntimeGetVersion(runtime), "hipRuntimeGetVersion");
         java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
         digest.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
         digest.update((PRELUDE + String.join(" ", exactOptions()) + " hiprtc " + hiprtcVersionText() + " runtime " + runtime.get(0))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
         return directory.resolve(java.util.HexFormat.of().formatHex(digest.digest()) + "-" + architecture.replace(':', '_') + ".hsaco");
      } catch (Exception failure) {
         return null;
      }
   }

   private static String hiprtcVersionText() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer major = stack.mallocInt(1);
         IntBuffer minor = stack.mallocInt(1);
         checkRtc(hiprtcVersion(major, minor), "hiprtcVersion");
         return major.get(0) + "." + minor.get(0);
      }
   }

   /**
    * Put before every source: CUDA's warp vote and shuffle with a 32-bit mask, as the surface prepass uses them
    * ({@link SurfaceCodegen#PREPASS_WARP}: 32 threads per column, all of them taking part), in HIP's own calls. HIP's
    * __ballot gives the whole wavefront's 64 bits; the half of the thread's group of 32 is taken (a wavefront of 64 holds
    * two groups, one of 32 the low half only). __shfl with width 32 reads within the same group of 32. Right for
    * wavefronts of 32 (RDNA) and 64 (GCN, CDNA).
    */
   public static final String PRELUDE = """
      #define __ballot_sync(mask, predicate) ((unsigned int)(__ballot(predicate) >> (__lane_id() & 32)))
      #define __shfl_sync(mask, value, source) __shfl((value), (source), 32)
      """;

   /** Compiles HIP (CUDA-style) C to a code object for this card with {@link #exactOptions}; the caller frees the buffer. */
   public static ByteBuffer compile(String source, String name) {
      String[] exact = exactOptions();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer program = stack.mallocPointer(1);
         // Off the small LWJGL stack: generated sources run to megabytes. Kept until the program is destroyed.
         ByteBuffer text = MemoryUtil.memUTF8(PRELUDE + source);
         try {
            checkRtc(hiprtcCreateProgram(program, text, stack.UTF8(name)), "hiprtcCreateProgram");
         } catch (RuntimeException failure) {
            MemoryUtil.memFree(text);
            throw failure;
         }

         try {
            PointerBuffer options = stack.mallocPointer(exact.length);
            for (String option : exact) {
               options.put(stack.UTF8(option));
            }

            options.flip();
            int compiled = hiprtcCompileProgram(program.get(0), options);
            if (compiled != HIPRTC_SUCCESS) {
               throw new IllegalStateException("hipRTC could not compile " + name + ": " + hiprtcGetErrorString(compiled) + "\n" + log(program.get(0)));
            }

            PointerBuffer size = stack.mallocPointer(1);
            checkRtc(hiprtcGetCodeSize(program.get(0), size), "hiprtcGetCodeSize");
            ByteBuffer code = MemoryUtil.memAlloc((int)size.get(0));
            checkRtc(hiprtcGetCode(program.get(0), code), "hiprtcGetCode");
            return code;
         } finally {
            hiprtcDestroyProgram(program);
            MemoryUtil.memFree(text);
         }
      }
   }

   private static String log(long program) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (hiprtcGetProgramLogSize(program, size) != HIPRTC_SUCCESS || size.get(0) <= 1L) {
            return "";
         }

         ByteBuffer log = MemoryUtil.memAlloc((int)size.get(0));
         try {
            hiprtcGetProgramLog(program, log);
            return MemoryUtil.memASCII(log, (int)size.get(0) - 1);
         } finally {
            MemoryUtil.memFree(log);
         }
      }
   }

   /** Runs {@link GpuSelfTest}'s kernel on the AMD card; returns the lines to log. Throws when the route fails. */
   public static List<String> run() {
      List<String> lines = new ArrayList<>();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         exactOptions();
         IntBuffer runtime = stack.mallocInt(1);
         check(hipRuntimeGetVersion(runtime), "hipRuntimeGetVersion");
         lines.add("device " + GpuDevice.index() + " (" + GpuDevice.reason + ")");
         lines.add("GPU: " + deviceName + " (" + architecture + "); HIP runtime " + runtime.get(0) + "; hipRTC " + hiprtcVersionText() + " (" + hiprtcState + ")");
         check(hipSetDevice(GpuDevice.index()), "hipSetDevice");
         long started = System.nanoTime();
         ByteBuffer code = compile(GpuSelfTest.SOURCE, "terrain_like.hip");
         double compileMs = (System.nanoTime() - started) / 1.0e6;
         PointerBuffer module = stack.mallocPointer(1);
         try {
            check(hipModuleLoadData(module, code), "hipModuleLoadData");
         } finally {
            MemoryUtil.memFree(code);
         }

         PointerBuffer function = stack.mallocPointer(1);
         check(hipModuleGetFunction(function, module.get(0), stack.ASCII("terrainLike")), "hipModuleGetFunction");
         int n = 1 << 22;
         FloatBuffer a = MemoryUtil.memAllocFloat(n);
         FloatBuffer b = MemoryUtil.memAllocFloat(n);
         FloatBuffer out = MemoryUtil.memAllocFloat(n);
         java.util.Random random = new java.util.Random(12345L);
         for (int i = 0; i < n; i++) {
            a.put(i, 0.001F + random.nextFloat() * 1000.0F);
            b.put(i, (random.nextFloat() - 0.5F) * 2000.0F);
         }

         PointerBuffer da = stack.mallocPointer(1);
         PointerBuffer db = stack.mallocPointer(1);
         PointerBuffer dout = stack.mallocPointer(1);
         check(hipMalloc(da, 4L * n), "hipMalloc");
         check(hipMalloc(db, 4L * n), "hipMalloc");
         check(hipMalloc(dout, 4L * n), "hipMalloc");
         check(hipMemcpyHtoD(da.get(0), MemoryUtil.memAddress(a), 4L * n), "hipMemcpyHtoD");
         check(hipMemcpyHtoD(db.get(0), MemoryUtil.memAddress(b), 4L * n), "hipMemcpyHtoD");
         PointerBuffer params = stack.mallocPointer(4);
         params.put(0, stack.pointers(da.get(0)).address());
         params.put(1, stack.pointers(db.get(0)).address());
         params.put(2, stack.pointers(dout.get(0)).address());
         params.put(3, MemoryUtil.memAddress(stack.ints(n)));
         long launch = System.nanoTime();
         check(hipModuleLaunchKernel(function.get(0), (n + 255) / 256, 1, 1, 256, 1, 1, 0, 0L, params, 0L), "hipModuleLaunchKernel");
         check(hipDeviceSynchronize(), "hipDeviceSynchronize");
         double gpuMs = (System.nanoTime() - launch) / 1.0e6;
         check(hipMemcpyDtoH(MemoryUtil.memAddress(out), dout.get(0), 4L * n), "hipMemcpyDtoH");

         int different = 0;
         int notANumber = 0;
         for (int i = 0; i < n; i++) {
            float expected = GpuSelfTest.terrainLike(a.get(i), b.get(i));
            float actual = out.get(i);
            if (Float.isNaN(expected) && Float.isNaN(actual)) {
               notANumber++;
            } else if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
               if (different++ < 4) {
                  lines.add(String.format(Locale.ROOT, "  differs: a=%s b=%s java=%s gpu=%s", a.get(i), b.get(i), expected, actual));
               }
            }
         }

         lines.add(String.format(Locale.ROOT, "kernel compiled in %.0f ms; %,d values in %.2f ms on the GPU", compileMs, n, gpuMs));
         lines.add(different == 0
            ? String.format(Locale.ROOT, "BIT-IDENTICAL: all %,d numbers equal Java's bit for bit (%,d NaN on both sides)", n - notANumber, notANumber)
            : String.format(Locale.ROOT, "DIFFERENT: %,d of %,d numbers differ from Java's", different, n));
         hipFree(da.get(0));
         hipFree(db.get(0));
         hipFree(dout.get(0));
         hipModuleUnload(module.get(0));
         MemoryUtil.memFree(a);
         MemoryUtil.memFree(b);
         MemoryUtil.memFree(out);
      }

      return lines;
   }

   /** The fixed kernel sources that need no Minecraft classes, by name, for {@link #compileModules}. */
   public static Map<String, String> fixedSources() {
      Map<String, String> sources = new LinkedHashMap<>();
      sources.put("erosion filter (ErosionKernels)", ErosionKernels.SOURCE);
      sources.put("FreeTerraForged noise (NoiseKernels)", NoiseKernels.NOISE_UTIL + NoiseKernels.PERLIN + NoiseKernels.RIDGE + NoiseKernels.SIMPLEX
         + NoiseKernels.PERLIN2 + NoiseKernels.WORLEY + NoiseKernels.PERLIN_CHECK);
      sources.put("vanilla noise (VanillaKernels.NOISE)", VanillaKernels.NOISE);
      return sources;
   }

   /**
    * Kernel sources (the generated ones need a world) through hipRTC, each compiled and loaded: what compiles as it is,
    * and hipRTC's errors for what does not.
    */
   public static List<String> compileModules(Map<String, String> sources) {
      List<String> lines = new ArrayList<>();
      check(hipSetDevice(GpuDevice.index()), "hipSetDevice");
      for (Map.Entry<String, String> source : sources.entrySet()) {
         long started = System.nanoTime();
         try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer code = compile(source.getValue(), "module.hip");
            int bytes = code.remaining();
            PointerBuffer module = stack.mallocPointer(1);
            try {
               check(hipModuleLoadData(module, code), "hipModuleLoadData");
            } finally {
               MemoryUtil.memFree(code);
            }

            hipModuleUnload(module.get(0));
            lines.add(String.format(Locale.ROOT, "OK      %s: %,d bytes in %.0f ms", source.getKey(), bytes, (System.nanoTime() - started) / 1.0e6));
         } catch (RuntimeException failure) {
            lines.add("FAILED  " + source.getKey() + ": " + failure.getMessage());
         }
      }

      return lines;
   }
}
