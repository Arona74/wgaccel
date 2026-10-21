package com.wgaccel.gpu;

import static org.lwjgl.cuda.CU.*;
import static org.lwjgl.cuda.NVRTC.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G1.2: proves the GPU route on the user's machine, in game or alone (`gradlew.bat cudaCheck`): the CUDA
 * driver and NVRTC through LWJGL (no nvcc, no C++), a kernel compiled at run time, and float maths bit-identical to
 * Java's (the kind FreeTerraForged's noise uses: multiply, add, divide, square root, floor, min, max), with fused
 * multiply-add off. Uses no Minecraft classes, so the standalone check can run it too.
 */
public final class GpuSelfTest {
   /** NVRTC's options for Java-identical floats (checked on compute capability 8.6). */
   public static final String[] EXACT_OPTIONS = {"--gpu-architecture=compute_86", "--fmad=false", "--prec-div=true", "--prec-sqrt=true", "--ftz=false"};

   private static final String SOURCE = """
      extern "C" __global__ void terrainLike(const float* a, const float* b, float* out, int n) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= n) return;
          float x = a[i];
          float y = b[i];
          float r = x * y + y / x;
          r = r - sqrtf(x + y);
          r = r + fminf(x, y) * 0.37f - fmaxf(x, y) * 0.11f;
          r = r * 0.5f + floorf(x * 3.7f) / 7.0f;
          out[i] = r;
      }
      """;
   private static boolean nvrtcConfigured;

   private GpuSelfTest() {
   }

   /** Points LWJGL at the CUDA Toolkit's NVRTC (from CUDA_PATH) once, before NVRTC is first used. */
   public static synchronized void configureNvrtc() {
      if (nvrtcConfigured) {
         return;
      }

      nvrtcConfigured = true;
      String explicit = System.getProperty("wgaccel.nvrtc");
      if (explicit != null) {
         Configuration.CUDA_NVRTC_LIBRARY_NAME.set(explicit);
         return;
      }

      String cuda = System.getenv("CUDA_PATH");
      if (cuda != null) {
         File[] candidates = new File(cuda, "bin").listFiles((dir, name) -> name.startsWith("nvrtc64_") && name.endsWith(".dll") && !name.contains(".alt."));
         if (candidates != null && candidates.length > 0) {
            Configuration.CUDA_NVRTC_LIBRARY_NAME.set(candidates[0].getAbsolutePath());
         }
      }
   }

   /** The same expression in Java (strict IEEE floats; square root as FreeTerraForged does it, through double). */
   private static float terrainLike(float x, float y) {
      float r = x * y + y / x;
      r = r - (float)Math.sqrt((double)(x + y));
      r = r + Math.min(x, y) * 0.37F - Math.max(x, y) * 0.11F;
      r = r * 0.5F + (float)Math.floor((double)(x * 3.7F)) / 7.0F;
      return r;
   }

   /** Runs the test; returns the lines to log. Throws when the GPU route fails. */
   public static List<String> run() {
      configureNvrtc();
      List<String> lines = new ArrayList<>();
      check(cuInit(0), "cuInit");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer device = stack.mallocInt(1);
         check(cuDeviceGet(device, 0), "cuDeviceGet");
         ByteBuffer name = stack.malloc(256);
         check(cuDeviceGetName(name, device.get(0)), "cuDeviceGetName");
         IntBuffer major = stack.mallocInt(1);
         IntBuffer minor = stack.mallocInt(1);
         checkNvrtc(nvrtcVersion(major, minor), "nvrtcVersion");
         lines.add("GPU: " + MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name)) + "; NVRTC " + major.get(0) + "." + minor.get(0));

         PointerBuffer context = stack.mallocPointer(1);
         check(cuCtxCreate(context, 0, device.get(0)), "cuCtxCreate");
         try {
            long started = System.nanoTime();
            ByteBuffer ptx = compile(SOURCE, "terrain_like.cu");
            double compileMs = (System.nanoTime() - started) / 1.0e6;
            PointerBuffer module = stack.mallocPointer(1);
            check(cuModuleLoadData(module, ptx), "cuModuleLoadData");
            MemoryUtil.memFree(ptx);
            PointerBuffer function = stack.mallocPointer(1);
            check(cuModuleGetFunction(function, module.get(0), stack.ASCII("terrainLike")), "cuModuleGetFunction");

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
            check(cuMemAlloc(da, 4L * n), "cuMemAlloc");
            check(cuMemAlloc(db, 4L * n), "cuMemAlloc");
            check(cuMemAlloc(dout, 4L * n), "cuMemAlloc");
            check(cuMemcpyHtoD(da.get(0), a), "cuMemcpyHtoD");
            check(cuMemcpyHtoD(db.get(0), b), "cuMemcpyHtoD");
            PointerBuffer params = stack.mallocPointer(4);
            params.put(0, stack.pointers(da.get(0)).address());
            params.put(1, stack.pointers(db.get(0)).address());
            params.put(2, stack.pointers(dout.get(0)).address());
            params.put(3, MemoryUtil.memAddress(stack.ints(n)));
            long launch = System.nanoTime();
            check(cuLaunchKernel(function.get(0), (n + 255) / 256, 1, 1, 256, 1, 1, 0, 0L, params, null), "cuLaunchKernel");
            check(cuCtxSynchronize(), "cuCtxSynchronize");
            double gpuMs = (System.nanoTime() - launch) / 1.0e6;
            check(cuMemcpyDtoH(out, dout.get(0)), "cuMemcpyDtoH");

            int different = 0;
            int notANumber = 0;
            for (int i = 0; i < n; i++) {
               float expected = terrainLike(a.get(i), b.get(i));
               float actual = out.get(i);
               if (Float.isNaN(expected) && Float.isNaN(actual)) {
                  notANumber++;
               } else if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
                  different++;
               }
            }

            lines.add(String.format(Locale.ROOT, "kernel compiled in %.0f ms; %,d values in %.2f ms on the GPU", compileMs, n, gpuMs));
            lines.add(different == 0
               ? String.format(Locale.ROOT, "BIT-IDENTICAL: all %,d numbers equal Java's bit for bit (%,d NaN on both sides)", n - notANumber, notANumber)
               : String.format(Locale.ROOT, "DIFFERENT: %,d of %,d numbers differ from Java's", different, n));
            cuMemFree(da.get(0));
            cuMemFree(db.get(0));
            cuMemFree(dout.get(0));
            cuModuleUnload(module.get(0));
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
            MemoryUtil.memFree(out);
         } finally {
            cuCtxDestroy(context.get(0));
         }
      }

      return lines;
   }

   /** Where compiled programs are kept (set by the mod: the game folder's wgaccel-gpu-cache), or null for none. */
   public static volatile java.nio.file.Path cacheDirectory;
   /** S2: whether the driver's machine code is kept too (config gpuCubinCache). */
   public static volatile boolean cubinCache = true;
   private static volatile String deviceTag;

   /**
    * S2: where a source's machine code is kept: the PTX cache's key plus the driver version and the GPU's compute
    * capability (a new driver or card translates again), or null without a cache.
    */
   public static java.nio.file.Path cubinPath(String source) {
      java.nio.file.Path directory = cacheDirectory;
      if (directory == null || !cubinCache) {
         return null;
      }

      try {
         // NVRTC's path must be set before its version is asked (else LWJGL loads a default library).
         configureNvrtc();
         if (deviceTag == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
               IntBuffer value = stack.mallocInt(1);
               check(cuDriverGetVersion(value), "cuDriverGetVersion");
               int driver = value.get(0);
               IntBuffer device = stack.mallocInt(1);
               check(cuDeviceGet(device, 0), "cuDeviceGet");
               check(cuDeviceGetAttribute(value, CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device.get(0)), "cuDeviceGetAttribute");
               int major = value.get(0);
               check(cuDeviceGetAttribute(value, CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device.get(0)), "cuDeviceGetAttribute");
               deviceTag = "-sm" + major + value.get(0) + "-driver" + driver;
            }
         }

         java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
         digest.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
         digest.update(String.join(" ", EXACT_OPTIONS).getBytes(java.nio.charset.StandardCharsets.UTF_8));
         digest.update(nvrtcVersionText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
         return directory.resolve(java.util.HexFormat.of().formatHex(digest.digest()) + deviceTag + ".cubin");
      } catch (Exception failure) {
         return null;
      }
   }

   /**
    * Compiles CUDA C to PTX with NVRTC and {@link #EXACT_OPTIONS}; the caller frees the buffer. With a cache directory,
    * the PTX is kept by the SHA-256 of the source, the options and NVRTC's version, and the same source is loaded from
    * there next time (the same bytes NVRTC would make).
    */
   public static ByteBuffer compile(String source, String name) {
      configureNvrtc();
      java.nio.file.Path cached = null;
      java.nio.file.Path directory = cacheDirectory;
      if (directory != null) {
         try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(String.join(" ", EXACT_OPTIONS).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(nvrtcVersionText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cached = directory.resolve(java.util.HexFormat.of().formatHex(digest.digest()) + ".ptx");
            if (java.nio.file.Files.exists(cached)) {
               byte[] bytes = java.nio.file.Files.readAllBytes(cached);
               ByteBuffer ptx = MemoryUtil.memAlloc(bytes.length);
               ptx.put(0, bytes);
               return ptx;
            }
         } catch (Exception failure) {
            cached = null;
         }
      }

      ByteBuffer ptx = compileFresh(source, name);
      if (cached != null) {
         try {
            byte[] bytes = new byte[ptx.remaining()];
            ptx.get(0, bytes);
            java.nio.file.Files.createDirectories(cached.getParent());
            java.nio.file.Path temporary = cached.resolveSibling(cached.getFileName() + ".tmp" + Thread.currentThread().getId());
            java.nio.file.Files.write(temporary, bytes);
            java.nio.file.Files.move(temporary, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
         } catch (Exception ignored) {
         }
      }

      return ptx;
   }

   private static String nvrtcVersionText() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer major = stack.mallocInt(1);
         IntBuffer minor = stack.mallocInt(1);
         org.lwjgl.cuda.NVRTC.nvrtcVersion(major, minor);
         return major.get(0) + "." + minor.get(0);
      }
   }

   private static ByteBuffer compileFresh(String source, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer program = stack.mallocPointer(1);
         // Off the small LWJGL stack: generated sources run to megabytes. Kept until the program is destroyed.
         ByteBuffer text = MemoryUtil.memUTF8(source);
         try {
            checkNvrtc(nvrtcCreateProgram(program, text, stack.UTF8(name), null, null), "nvrtcCreateProgram");
         } catch (RuntimeException failure) {
            MemoryUtil.memFree(text);
            throw failure;
         }

         try {
            PointerBuffer options = stack.mallocPointer(EXACT_OPTIONS.length);
            for (String option : EXACT_OPTIONS) {
               options.put(stack.UTF8(option));
            }

            options.flip();
            int compiled = nvrtcCompileProgram(program.get(0), options);
            if (compiled != NVRTC_SUCCESS) {
               PointerBuffer logSize = stack.mallocPointer(1);
               nvrtcGetProgramLogSize(program.get(0), logSize);
               ByteBuffer log = MemoryUtil.memAlloc((int)Math.max(1L, logSize.get(0)));
               nvrtcGetProgramLog(program.get(0), log);
               String message = MemoryUtil.memASCII(log, (int)Math.max(0L, logSize.get(0) - 1));
               MemoryUtil.memFree(log);
               throw new IllegalStateException("NVRTC could not compile " + name + ": " + nvrtcGetErrorString(compiled) + "\n" + message);
            }

            PointerBuffer size = stack.mallocPointer(1);
            checkNvrtc(nvrtcGetPTXSize(program.get(0), size), "nvrtcGetPTXSize");
            ByteBuffer ptx = MemoryUtil.memAlloc((int)size.get(0));
            checkNvrtc(nvrtcGetPTX(program.get(0), ptx), "nvrtcGetPTX");
            return ptx;
         } finally {
            nvrtcDestroyProgram(program);
            MemoryUtil.memFree(text);
         }
      }
   }

   static void check(int result, String what) {
      if (result != CUDA_SUCCESS) {
         throw new IllegalStateException(what + " failed: CUDA error " + result);
      }
   }

   static void checkNvrtc(int result, String what) {
      if (result != NVRTC_SUCCESS) {
         throw new IllegalStateException(what + " failed: " + nvrtcGetErrorString(result));
      }
   }
}
