package com.wgaccel.gpu;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * AMD's HIP runtime and hipRTC (ROCm) from Java, for Radeon cards. LWJGL has no bindings for them, so the functions the
 * mod uses are looked up here and called through LWJGL's JNI helpers, the same ones its CUDA bindings use: each HIP
 * function takes the same arguments as its CUDA one (hipModuleLaunchKernel as cuLaunchKernel, hiprtcCompileProgram as
 * nvrtcCompileProgram, ...). The runtime comes with AMD's graphics driver (amdhip64_6.dll in System32); hipRTC, which
 * compiles the kernels at run time, with the AMD HIP SDK. Uses no Minecraft classes, so the standalone checks can use it.
 */
public final class Hip {
   public static final int hipSuccess = 0;
   public static final int HIPRTC_SUCCESS = 0;
   public static final int hipStreamNonBlocking = 1;
   public static final int hipErrorNotReady = 600;
   /**
    * hipDeviceAttribute_t values (HIP 6's hip_runtime_api.h, the ABI of amdhip64_6; counted from the header of the 6.4 SDK
    * and checked on a Radeon 680M: integrated 1, 6 multiprocessors, its clock 2,400,000 kHz under ClockRate = 5).
    */
   public static final int hipDeviceAttributeIntegrated = 16;
   public static final int hipDeviceAttributeMultiprocessorCount = 63;

   private Hip() {
   }

   /** The runtime's functions, looked up the first time one is called. */
   private static final class Runtime {
      static final SharedLibrary LIBRARY = Library.loadNative(Hip.class, "org.lwjgl", System.getProperty("wgaccel.hip", "amdhip64_6"));
      static final long hipInit = function(LIBRARY, "hipInit");
      static final long hipGetDeviceCount = function(LIBRARY, "hipGetDeviceCount");
      static final long hipDeviceGetName = function(LIBRARY, "hipDeviceGetName");
      static final long hipDeviceGetAttribute = function(LIBRARY, "hipDeviceGetAttribute");
      static final long hipGetDevicePropertiesR0600 = function(LIBRARY, "hipGetDevicePropertiesR0600");
      static final long hipDriverGetVersion = function(LIBRARY, "hipDriverGetVersion");
      static final long hipRuntimeGetVersion = function(LIBRARY, "hipRuntimeGetVersion");
      static final long hipSetDevice = function(LIBRARY, "hipSetDevice");
      static final long hipDeviceSynchronize = function(LIBRARY, "hipDeviceSynchronize");
      static final long hipModuleLoadData = function(LIBRARY, "hipModuleLoadData");
      static final long hipModuleGetFunction = function(LIBRARY, "hipModuleGetFunction");
      static final long hipModuleUnload = function(LIBRARY, "hipModuleUnload");
      static final long hipModuleLaunchKernel = function(LIBRARY, "hipModuleLaunchKernel");
      static final long hipMalloc = function(LIBRARY, "hipMalloc");
      static final long hipFree = function(LIBRARY, "hipFree");
      static final long hipMemcpyHtoD = function(LIBRARY, "hipMemcpyHtoD");
      static final long hipMemcpyDtoH = function(LIBRARY, "hipMemcpyDtoH");
      static final long hipMemcpyDtoD = function(LIBRARY, "hipMemcpyDtoD");
      static final long hipMemcpyHtoDAsync = function(LIBRARY, "hipMemcpyHtoDAsync");
      static final long hipMemcpyDtoHAsync = function(LIBRARY, "hipMemcpyDtoHAsync");
      static final long hipMemcpyDtoDAsync = function(LIBRARY, "hipMemcpyDtoDAsync");
      static final long hipStreamCreateWithFlags = function(LIBRARY, "hipStreamCreateWithFlags");
      static final long hipStreamCreateWithPriority = function(LIBRARY, "hipStreamCreateWithPriority");
      static final long hipDeviceGetStreamPriorityRange = function(LIBRARY, "hipDeviceGetStreamPriorityRange");
      static final long hipStreamSynchronize = function(LIBRARY, "hipStreamSynchronize");
      static final long hipHostMalloc = function(LIBRARY, "hipHostMalloc");
      static final long hipHostFree = function(LIBRARY, "hipHostFree");
      static final long hipEventCreateWithFlags = function(LIBRARY, "hipEventCreateWithFlags");
      static final long hipEventRecord = function(LIBRARY, "hipEventRecord");
      static final long hipEventQuery = function(LIBRARY, "hipEventQuery");
      static final long hipEventElapsedTime = function(LIBRARY, "hipEventElapsedTime");
      static final long hipGetErrorString = function(LIBRARY, "hipGetErrorString");
   }

   /** hipRTC's functions, from the library {@link #configureHiprtc} found. */
   private static final class Rtc {
      static final SharedLibrary LIBRARY = loadHiprtc();
      static final long hiprtcVersion = function(LIBRARY, "hiprtcVersion");
      static final long hiprtcCreateProgram = function(LIBRARY, "hiprtcCreateProgram");
      static final long hiprtcCompileProgram = function(LIBRARY, "hiprtcCompileProgram");
      static final long hiprtcGetProgramLogSize = function(LIBRARY, "hiprtcGetProgramLogSize");
      static final long hiprtcGetProgramLog = function(LIBRARY, "hiprtcGetProgramLog");
      static final long hiprtcGetCodeSize = function(LIBRARY, "hiprtcGetCodeSize");
      static final long hiprtcGetCode = function(LIBRARY, "hiprtcGetCode");
      static final long hiprtcDestroyProgram = function(LIBRARY, "hiprtcDestroyProgram");
      static final long hiprtcGetErrorString = function(LIBRARY, "hiprtcGetErrorString");
   }

   private static long function(SharedLibrary library, String name) {
      long address = library.getFunctionAddress(name);
      if (address == 0L) {
         throw new IllegalStateException(name + " not found in " + library.getPath());
      }

      return address;
   }

   /** Whether the driver's HIP runtime loads and sees a card (no hipRTC needed); never throws. */
   public static boolean runtimePresent() {
      try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         return hipInit(0) == hipSuccess && hipGetDeviceCount(count) == hipSuccess && count.get(0) > 0;
      } catch (Throwable missing) {
         return false;
      }
   }

   // The runtime.

   public static int hipInit(int flags) {
      return JNI.callI(flags, Runtime.hipInit);
   }

   public static int hipGetDeviceCount(IntBuffer count) {
      return JNI.callPI(MemoryUtil.memAddress(count), Runtime.hipGetDeviceCount);
   }

   public static int hipDeviceGetName(ByteBuffer name, int device) {
      return JNI.callPI(MemoryUtil.memAddress(name), name.remaining(), device, Runtime.hipDeviceGetName);
   }

   public static int hipDeviceGetAttribute(IntBuffer value, int attribute, int device) {
      return JNI.callPI(MemoryUtil.memAddress(value), attribute, device, Runtime.hipDeviceGetAttribute);
   }

   /** HIP 6's hipDeviceProp_t into a buffer of at least {@link #PROPERTIES_BYTES} (its layout is read by {@link #architecture}). */
   public static int hipGetDeviceProperties(ByteBuffer properties, int device) {
      if (properties.remaining() < PROPERTIES_BYTES) {
         throw new IllegalArgumentException("hipDeviceProp_t needs " + PROPERTIES_BYTES + " bytes");
      }

      return JNI.callPI(MemoryUtil.memAddress(properties), device, Runtime.hipGetDevicePropertiesR0600);
   }

   /** Room for hipDeviceProp_t (about 1.5 KB in HIP 6), with a wide margin. */
   public static final int PROPERTIES_BYTES = 8192;

   public static int hipDriverGetVersion(IntBuffer version) {
      return JNI.callPI(MemoryUtil.memAddress(version), Runtime.hipDriverGetVersion);
   }

   public static int hipRuntimeGetVersion(IntBuffer version) {
      return JNI.callPI(MemoryUtil.memAddress(version), Runtime.hipRuntimeGetVersion);
   }

   public static int hipSetDevice(int device) {
      return JNI.callI(device, Runtime.hipSetDevice);
   }

   public static int hipDeviceSynchronize() {
      return JNI.callI(Runtime.hipDeviceSynchronize);
   }

   public static int hipModuleLoadData(PointerBuffer module, ByteBuffer image) {
      return JNI.callPPI(MemoryUtil.memAddress(module), MemoryUtil.memAddress(image), Runtime.hipModuleLoadData);
   }

   public static int hipModuleGetFunction(PointerBuffer function, long module, ByteBuffer name) {
      return JNI.callPPPI(MemoryUtil.memAddress(function), module, MemoryUtil.memAddress(name), Runtime.hipModuleGetFunction);
   }

   public static int hipModuleUnload(long module) {
      return JNI.callPI(module, Runtime.hipModuleUnload);
   }

   public static int hipModuleLaunchKernel(long function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream,
         PointerBuffer params, long extra) {
      return JNI.callPPPPI(function, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, MemoryUtil.memAddressSafe(params), extra,
         Runtime.hipModuleLaunchKernel);
   }

   public static int hipMalloc(PointerBuffer device, long bytes) {
      return JNI.callPPI(MemoryUtil.memAddress(device), bytes, Runtime.hipMalloc);
   }

   public static int hipFree(long device) {
      return JNI.callPI(device, Runtime.hipFree);
   }

   /** Copies bytes from host memory at an address to the device. */
   public static int hipMemcpyHtoD(long device, long host, long bytes) {
      return JNI.callPPPI(device, host, bytes, Runtime.hipMemcpyHtoD);
   }

   /** Copies bytes from the device to host memory at an address. */
   public static int hipMemcpyDtoH(long host, long device, long bytes) {
      return JNI.callPPPI(host, device, bytes, Runtime.hipMemcpyDtoH);
   }

   public static int hipMemcpyDtoD(long to, long from, long bytes) {
      return JNI.callPPPI(to, from, bytes, Runtime.hipMemcpyDtoD);
   }

   public static int hipMemcpyHtoDAsync(long device, long host, long bytes, long stream) {
      return JNI.callPPPPI(device, host, bytes, stream, Runtime.hipMemcpyHtoDAsync);
   }

   public static int hipMemcpyDtoHAsync(long host, long device, long bytes, long stream) {
      return JNI.callPPPPI(host, device, bytes, stream, Runtime.hipMemcpyDtoHAsync);
   }

   public static int hipMemcpyDtoDAsync(long to, long from, long bytes, long stream) {
      return JNI.callPPPPI(to, from, bytes, stream, Runtime.hipMemcpyDtoDAsync);
   }

   public static int hipStreamCreateWithFlags(PointerBuffer stream, int flags) {
      return JNI.callPI(MemoryUtil.memAddress(stream), flags, Runtime.hipStreamCreateWithFlags);
   }

   public static int hipStreamCreateWithPriority(PointerBuffer stream, int flags, int priority) {
      return JNI.callPI(MemoryUtil.memAddress(stream), flags, priority, Runtime.hipStreamCreateWithPriority);
   }

   public static int hipDeviceGetStreamPriorityRange(IntBuffer least, IntBuffer greatest) {
      return JNI.callPPI(MemoryUtil.memAddress(least), MemoryUtil.memAddress(greatest), Runtime.hipDeviceGetStreamPriorityRange);
   }

   public static int hipStreamSynchronize(long stream) {
      return JNI.callPI(stream, Runtime.hipStreamSynchronize);
   }

   /** Page-locked host memory (hipHostMallocDefault with flags 0), as cuMemAllocHost. */
   public static int hipHostMalloc(PointerBuffer host, long bytes, int flags) {
      return JNI.callPPI(MemoryUtil.memAddress(host), bytes, flags, Runtime.hipHostMalloc);
   }

   public static int hipHostFree(long host) {
      return JNI.callPI(host, Runtime.hipHostFree);
   }

   public static int hipEventCreateWithFlags(PointerBuffer event, int flags) {
      return JNI.callPI(MemoryUtil.memAddress(event), flags, Runtime.hipEventCreateWithFlags);
   }

   public static int hipEventRecord(long event, long stream) {
      return JNI.callPPI(event, stream, Runtime.hipEventRecord);
   }

   /** hipSuccess when the event's work is done, hipErrorNotReady (600, as CUDA's) while it is not. */
   public static int hipEventQuery(long event) {
      return JNI.callPI(event, Runtime.hipEventQuery);
   }

   public static int hipEventElapsedTime(FloatBuffer milliseconds, long start, long stop) {
      return JNI.callPPPI(MemoryUtil.memAddress(milliseconds), start, stop, Runtime.hipEventElapsedTime);
   }

   public static String hipGetErrorString(int error) {
      return MemoryUtil.memASCIISafe(JNI.callP(error, Runtime.hipGetErrorString));
   }

   // hipRTC.

   public static int hiprtcVersion(IntBuffer major, IntBuffer minor) {
      return JNI.callPPI(MemoryUtil.memAddress(major), MemoryUtil.memAddress(minor), Rtc.hiprtcVersion);
   }

   /** A program from NUL-terminated source and name, without headers. */
   public static int hiprtcCreateProgram(PointerBuffer program, ByteBuffer source, ByteBuffer name) {
      return JNI.callPPPPPI(MemoryUtil.memAddress(program), MemoryUtil.memAddress(source), MemoryUtil.memAddress(name), 0, 0L, 0L, Rtc.hiprtcCreateProgram);
   }

   public static int hiprtcCompileProgram(long program, PointerBuffer options) {
      return JNI.callPPI(program, options.remaining(), MemoryUtil.memAddress(options), Rtc.hiprtcCompileProgram);
   }

   public static int hiprtcGetProgramLogSize(long program, PointerBuffer size) {
      return JNI.callPPI(program, MemoryUtil.memAddress(size), Rtc.hiprtcGetProgramLogSize);
   }

   public static int hiprtcGetProgramLog(long program, ByteBuffer log) {
      return JNI.callPPI(program, MemoryUtil.memAddress(log), Rtc.hiprtcGetProgramLog);
   }

   public static int hiprtcGetCodeSize(long program, PointerBuffer size) {
      return JNI.callPPI(program, MemoryUtil.memAddress(size), Rtc.hiprtcGetCodeSize);
   }

   public static int hiprtcGetCode(long program, ByteBuffer code) {
      return JNI.callPPI(program, MemoryUtil.memAddress(code), Rtc.hiprtcGetCode);
   }

   public static int hiprtcDestroyProgram(PointerBuffer program) {
      return JNI.callPI(MemoryUtil.memAddress(program), Rtc.hiprtcDestroyProgram);
   }

   public static String hiprtcGetErrorString(int error) {
      return MemoryUtil.memASCIISafe(JNI.callP(error, Rtc.hiprtcGetErrorString));
   }

   /**
    * The device's architecture as hipRTC takes it ("gfx1035", or with features: "gfx90a:sramecc+:xnack-"), from
    * hipDeviceProp_t's gcnArchName. Found by its text rather than its offset, so a change of the struct's layout between
    * HIP versions does not break it.
    */
   public static String architecture(ByteBuffer properties) {
      int limit = properties.limit();
      for (int i = 0; i + 3 < limit; i++) {
         if (properties.get(i) == 'g' && properties.get(i + 1) == 'f' && properties.get(i + 2) == 'x' && Character.isLetterOrDigit(properties.get(i + 3))) {
            int end = i;
            while (end < limit && properties.get(end) != 0) {
               end++;
            }

            return MemoryUtil.memASCII(properties, end - i, i);
         }
      }

      return null;
   }

   // Finding hipRTC.

   /** Where hipRTC was looked for and what was chosen, for the logs and warnings. */
   public static volatile String hiprtcState = "not looked for yet";
   private static volatile String hiprtcPath;

   /**
    * Finds the HIP SDK's hipRTC (hiprtc*.dll, not its builtins) once. Looked for, in order: the system property
    * wgaccel.hiprtc; the bin folders of HIP_PATH and every other HIP_PATH* variable; the SDK's usual install folder
    * (C:/Program Files/AMD/ROCm/*, newest first); the folders on PATH. Returns the path, or null with hiprtcState saying
    * why.
    */
   public static synchronized String configureHiprtc() {
      if (hiprtcPath != null) {
         return hiprtcPath;
      }

      String explicit = System.getProperty("wgaccel.hiprtc");
      if (explicit != null) {
         hiprtcState = explicit + " (-Dwgaccel.hiprtc)";
         return hiprtcPath = explicit;
      }

      List<String> roots = new ArrayList<>();
      String hip = System.getenv("HIP_PATH");
      if (hip != null) {
         roots.add(hip);
      }

      System.getenv().entrySet().stream().filter(e -> e.getKey().toUpperCase(Locale.ROOT).startsWith("HIP_PATH") && !e.getKey().equalsIgnoreCase("HIP_PATH"))
         .map(java.util.Map.Entry::getValue).sorted(java.util.Comparator.reverseOrder()).forEach(roots::add);
      File installs = new File(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"), "AMD/ROCm");
      File[] versions = installs.listFiles(File::isDirectory);
      if (versions != null) {
         java.util.Arrays.stream(versions).map(File::getPath).sorted(java.util.Comparator.reverseOrder()).forEach(roots::add);
      }

      List<File> folders = new ArrayList<>();
      for (String root : roots) {
         folders.add(new File(root, "bin"));
      }

      String path = System.getenv("PATH");
      if (path != null) {
         for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
               folders.add(new File(entry.trim()));
            }
         }
      }

      for (File folder : folders) {
         File[] candidates = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("hiprtc") && lower.endsWith(".dll") && !lower.contains("builtins");
         });
         if (candidates != null && candidates.length > 0) {
            // The newest when several are side by side.
            java.util.Arrays.sort(candidates, java.util.Comparator.comparing(File::getName).reversed());
            hiprtcState = candidates[0].getAbsolutePath();
            return hiprtcPath = candidates[0].getAbsolutePath();
         }
      }

      hiprtcState = "no hiprtc*.dll found (HIP_PATH " + (hip == null ? "not set" : hip) + "; looked in " + folders.size() + " folders: HIP_PATH*, "
         + installs.getPath() + "/*, PATH); install the AMD HIP SDK or start Java with -Dwgaccel.hiprtc=<path to hiprtc*.dll>";
      return null;
   }

   /**
    * Loads hipRTC. Its own libraries beside it (the code object manager amd_comgr*.dll and hiprtc-builtins*.dll) are
    * loaded first by their full paths, as Windows does not look for a library's dependencies in its folder when that
    * folder is not on PATH (a game started from a launcher).
    */
   private static SharedLibrary loadHiprtc() {
      String path = configureHiprtc();
      if (path == null) {
         throw new IllegalStateException("hipRTC: " + hiprtcState);
      }

      File[] beside = new File(path).getAbsoluteFile().getParentFile().listFiles((dir, name) -> {
         String lower = name.toLowerCase(Locale.ROOT);
         return lower.endsWith(".dll") && (lower.startsWith("amd_comgr") || lower.startsWith("hiprtc-builtins"));
      });
      if (beside != null) {
         for (File library : beside) {
            try {
               Library.loadNative(Hip.class, "org.lwjgl", library.getAbsolutePath());
            } catch (Throwable ignored) {
               // hipRTC names what it misses when it loads.
            }
         }
      }

      return Library.loadNative(Hip.class, "org.lwjgl", path);
   }

   static void check(int result, String what) {
      if (result != hipSuccess) {
         throw new IllegalStateException(what + " failed: HIP error " + result + " (" + hipGetErrorString(result) + ")");
      }
   }

   static void checkRtc(int result, String what) {
      if (result != HIPRTC_SUCCESS) {
         throw new IllegalStateException(what + " failed: " + hiprtcGetErrorString(result));
      }
   }
}
