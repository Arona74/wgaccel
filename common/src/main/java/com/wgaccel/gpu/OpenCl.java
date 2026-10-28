package com.wgaccel.gpu;

import static org.lwjgl.opencl.CL10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL12;
import org.lwjgl.opencl.CL20;
import org.lwjgl.opencl.CL30;
import org.lwjgl.opencl.KHRPriorityHints;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The OpenCL backend ({@link GpuBackend#OPENCL}), for GPUs with neither CUDA nor HIP (Intel's): the calls {@link GpuApi},
 * {@link Gpu} and {@link GpuStreams} make, in CUDA's terms. Device memory is OpenCL 2.0's shared virtual memory, whose
 * pointers are plain addresses as CUDA's are (offsets added to them, passed to kernels as they are), so the GPU parts
 * keep their device addresses as longs. A stream is an in-order command queue, 0 the context's own; a module is a
 * program built from {@link OpenClSource}'s translation, kept on disk as the driver's binary. Results are CUDA's numbers
 * where the callers compare them (0 success, 600 not ready), else OpenCL's error codes (negative).
 * <p>
 * A device needs double precision and coarse-grained shared virtual memory (OpenCL 2.0, or 3.0 with it): Intel's Arc and
 * Xe graphics, AMD's cards and NVIDIA's have them. NVIDIA's OpenCL has no generic address space (OpenCL C 1.2): there the
 * translation writes every pointer's address space out ({@link OpenClSource#addressSpaces}) and builds as OpenCL C 1.2.
 */
public final class OpenCl {
   static final int NOT_READY = 600;
   private static final int UNSUPPORTED = CL_INVALID_OPERATION;

   /** An OpenCL GPU and what the mod needs of it. */
   public record Device(long platform, long id, String name, String vendor, String version, String driver, int units, boolean integrated, long memory,
         boolean doubles, boolean svm, boolean generic, boolean priorities, boolean mesa, String chip) {
      /**
       * Fewer compute units than this (AMD's CUs, NVIDIA's SMs) and the automatic choice passes the GPU over: a Radeon 610M
       * (RDNA 2, 2 units) was slower than its laptop's CPU, a Radeon 680M (12 units) 1.6x faster (27 Oct 2026).
       */
      public static final int AUTOMATIC_UNITS = 8;

      /** Double precision, and shared virtual memory but on NVIDIA's (plain buffers there: {@link #buffers}). */
      public boolean usable() {
         return this.doubles && (this.svm || this.nvidia());
      }

      /**
       * Whether the automatic choice may take it: usable, and not Intel integrated graphics. An Intel Arc iGPU (Ultra 9 275HX
       * laptop, 23 Oct 2026) took r1000 in 1:49 where the same laptop's CPU alone took 0:42 (its tile noise 210 ms per batch
       * against 10 ms on the RTX 5060 beside it); an AMD Radeon 680M almost doubled its laptop's CPU (0.16.0). Chosen by hand
       * Intel's still run.
       */
      public boolean automatic() {
         return this.usable() && !this.mesa && !(this.integrated && this.vendor.toUpperCase(Locale.ROOT).contains("INTEL")) && this.units >= AUTOMATIC_UNITS;
      }

      /**
       * Why the automatic choice passes it over ("" when it does not). Mesa's OpenCL (rusticl): a Radeon 680M on Mesa 25.2
       * (26 Oct 2026, RUSTICL_FEATURES=fp64) took 6 s for the biome kernels and over 180 s without finishing the noise fill,
       * surface and tile noise (AMD's own compiler: under 1 s each), then the game stopped soon after Chunky started.
       */
      public String passedOver() {
         return !this.usable() ? "no " + this.missing() : this.mesa ? "Mesa's OpenCL (rusticl): compiles the kernels for minutes and stalled; used only when chosen"
            : this.integrated && this.vendor.toUpperCase(Locale.ROOT).contains("INTEL") ? "Intel integrated graphics: slower than the CPU on the one tested; used only when chosen"
            : this.units < AUTOMATIC_UNITS ? this.units + " compute units, under " + AUTOMATIC_UNITS + ": a GPU this small was slower than the CPU (Radeon 610M); used only when chosen"
            : "";
      }

      /**
       * AMD's RDNA 1 (gfx101x: RX 5000): an RX 5700 XT was faster through OpenCL than through HIP (Windows), and ROCm does
       * not list it, so Linux's automatic choice keeps OpenCL there.
       */
      public boolean rdna1() {
         return this.chip.toLowerCase(Locale.ROOT).startsWith("gfx101");
      }

      boolean nvidia() {
         return this.vendor.toUpperCase(Locale.ROOT).contains("NVIDIA");
      }

      /** What the mod misses on it, for the logs ("" when usable). */
      public String missing() {
         List<String> missing = new ArrayList<>();
         if (!this.doubles) {
            missing.add("double precision");
         }

         if (!this.svm && !this.nvidia()) {
            missing.add("shared virtual memory");
         }

         return String.join(", ", missing);
      }
   }

   private static volatile List<Device> devices;
   private static volatile boolean started;
   private static Device chosen;
   private static long context;
   private static long defaultQueue;
   private static String options;
   private static final List<Long> QUEUES = new CopyOnWriteArrayList<>();
   /** Each module's kernels and their argument sizes ({@link OpenClSource.Translated#kernels}). */
   private static final Map<Long, Map<String, int[]>> PROGRAMS = new ConcurrentHashMap<>();
   /** The kernel handles by program and name, what each names, and each thread's own kernel objects by handle. */
   private static final Map<String, Long> KERNELS = new ConcurrentHashMap<>();
   private static final Map<Long, Kernel> KERNEL_INFO = new ConcurrentHashMap<>();
   private static final AtomicLong NEXT_KERNEL = new AtomicLong();
   private static final ThreadLocal<Map<Long, List<Own>>> OWN = ThreadLocal.withInitial(java.util.HashMap::new);
   /**
    * Every live allocation, and a count of their changes. A kernel may reach an allocation only through its arguments or
    * when told of it (clSetKernelExecInfo's SVM_PTRS): the batches read their buffers' addresses from a table on the GPU,
    * and on a Radeon those were not resident (the batched fill's codes all wrong, then the driver hung).
    */
   private static final java.util.Set<Long> ALLOCATIONS = ConcurrentHashMap.newKeySet();
   private static final AtomicLong ALLOCATIONS_CHANGED = new AtomicLong();

   /** A kernel: its program, name and argument sizes, and every thread's object of it (released with the program). */
   private record Kernel(long program, String name, int[] sizes, List<Long> made) {
      Kernel(long program, String name, int[] sizes) {
         this(program, name, sizes, new CopyOnWriteArrayList<>());
      }
   }

   /** A thread's own object of a kernel, and the allocations list it was last given. */
   private static final class Own {
      final long kernel;
      long allocations = -1L;
      /** Its last launch (0: none waiting): it takes new arguments only once that launch is done (see {@link #launch}). */
      long event;

      Own(long kernel) {
         this.kernel = kernel;
      }

      /** Whether its last launch is done (its event then released), so its arguments can change. */
      boolean free() {
         if (this.event == 0L) {
            return true;
         }

         try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer status = stack.mallocInt(1);
            int result = clGetEventInfo(this.event, CL_EVENT_COMMAND_EXECUTION_STATUS, status, null);
            if (result == CL_SUCCESS && status.get(0) > CL_COMPLETE) {
               return false;
            }
         }

         clReleaseEvent(this.event);
         this.event = 0L;
         return true;
      }
   }
   private static final Map<Long, long[]> EVENTS = new ConcurrentHashMap<>();
   private static final AtomicLong NEXT_EVENT = new AtomicLong();
   /**
    * The first OpenCL call of this game that failed, and what it was, added to every later error's text: under NVIDIA's
    * OpenCL (built on CUDA) one kernel's fault fails every later call of every thread, with unrelated codes (an RTX 5060
    * laptop, 24 Oct 2026: every copy -36, every launch -6, from one first failure the log did not show).
    */
   private static volatile String firstFailure;
   /**
    * Without the generic address space (the explicit translation, NVIDIA's): each kernel's first launches are waited for,
    * so a kernel that faults is the one named (the translation's spaces are checked by the compiler, not its pointers).
    * -Dwgaccel.openclCheckLaunches=true does it on any device.
    */
   private static boolean checkLaunches;
   private static final int CHECKED_LAUNCHES = 4;
   private static final Map<Long, java.util.concurrent.atomic.AtomicInteger> CHECKED = new ConcurrentHashMap<>();
   /** Where the last failed build's translated source was written (for the log), or null. */
   public static volatile String failedSource;

   private OpenCl() {
   }

   /** Every OpenCL GPU of the PC, all platforms in order (config gpuDevice counts in this list); empty without OpenCL. */
   public static synchronized List<Device> devices() {
      List<Device> known = devices;
      if (known != null) {
         return known;
      }

      List<Device> list = new ArrayList<>();
      List<String> seen = new ArrayList<>();
      String library = linuxLibrary();
      if (library != null) {
         seen.add("library " + library);
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         int result = clGetPlatformIDs(null, count);
         if (result != CL_SUCCESS || count.get(0) == 0) {
            seen.add("no OpenCL platform (" + (result == CL_SUCCESS ? "none listed" : code(result)) + ")");
         } else {
            PointerBuffer platforms = stack.mallocPointer(count.get(0));
            clGetPlatformIDs(platforms, (IntBuffer)null);
            for (int p = 0; p < platforms.capacity(); p++) {
               long platform = platforms.get(p);
               String name = platformText(platform, CL_PLATFORM_NAME) + " " + platformText(platform, CL_PLATFORM_VERSION);
               IntBuffer found = stack.mallocInt(1);
               result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, found);
               if (result != CL_SUCCESS || found.get(0) == 0) {
                  seen.add("platform " + name.trim() + ": no GPU (" + (result == CL_SUCCESS ? "none listed" : code(result)) + ")"
                     + (name.toLowerCase(Locale.ROOT).contains("rusticl") && System.getenv("RUSTICL_ENABLE") == null
                        ? "; Mesa's rusticl lists GPUs only with RUSTICL_ENABLE (e.g. radeonsi) set in the game's own environment" : "")
                     + (name.contains("AMD-APP") && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") ? rocmHint() : ""));
                  continue;
               }

               PointerBuffer ids = stack.mallocPointer(found.get(0));
               clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, ids, (IntBuffer)null);
               for (int d = 0; d < ids.capacity(); d++) {
                  Device device = describe(platform, ids.get(d), name.toLowerCase(Locale.ROOT).matches(".*(rusticl|clover|mesa).*"));
                  list.add(device);
                  seen.add("platform " + name.trim() + ": " + device.name() + " (" + device.version() + ", driver " + device.driver() + ")"
                     + (device.passedOver().isEmpty() ? "" : ", not taken automatically: " + device.passedOver())
                     // Mesa's rusticl keeps double precision off unless asked for (a Radeon 680M, Mesa 25.2, 26 Oct 2026).
                     + (!device.doubles() && name.toLowerCase(Locale.ROOT).contains("rusticl")
                        && !String.valueOf(System.getenv("RUSTICL_FEATURES")).contains("fp64") ? " (rusticl turns it on with RUSTICL_FEATURES=fp64 in the game's environment)" : ""));
               }
            }
         }
      } catch (Throwable missing) {
         // No OpenCL library (OpenCL.dll comes with every GPU driver; a PC without one has no GPU to use; on Linux the ICD
         // loader, libOpenCL.so.1, is a package of its own).
         seen.add("the OpenCL library did not load: " + missing);
      }

      survey = String.join("; ", seen);
      devices = List.copyOf(list);
      return devices;
   }

   /**
    * On Linux, the OpenCL ICD loader by its runtime name, before LWJGL's first OpenCL call loads it: LWJGL looks for
    * libOpenCL.so, which only the loader's development package installs (Ubuntu's ocl-icd-opencl-dev), so a Radeon 680M
    * laptop with Mesa's rusticl (26 Oct 2026) had no OpenCL at all while libOpenCL.so.1 was there. Left alone when the
    * library was named with -Dorg.lwjgl.opencl.libname. Returns what was chosen, or null.
    */
   private static String linuxLibrary() {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") || org.lwjgl.system.Configuration.OPENCL_LIBRARY_NAME.get() != null) {
         return null;
      }

      for (String dir : new String[] {"/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib", "/usr/lib/aarch64-linux-gnu"}) {
         for (String name : new String[] {"libOpenCL.so.1", "libOpenCL.so"}) {
            java.nio.file.Path path = java.nio.file.Path.of(dir, name);
            if (java.nio.file.Files.exists(path)) {
               org.lwjgl.system.Configuration.OPENCL_LIBRARY_NAME.set(path.toString());
               return path.toString();
            }
         }
      }

      // Not in the usual places: the dynamic loader's own search by the runtime name.
      org.lwjgl.system.Configuration.OPENCL_LIBRARY_NAME.set("libOpenCL.so.1");
      return "libOpenCL.so.1 (the system's search)";
   }

   /**
    * ROCm's OpenCL listing no GPU on Linux: what the game's process has of what it needs. ROCm reaches the GPU through
    * /dev/kfd (the user in the render group), and passes over GPUs it does not list (a Radeon 680M, gfx1035) unless
    * HSA_OVERRIDE_GFX_VERSION names one it does (10.3.0); both checked from inside the game, where a launcher's
    * environment applies (26 Oct 2026: ROCm 6.4 installed, its platform there, no GPU).
    */
   private static String rocmHint() {
      String override = System.getenv("HSA_OVERRIDE_GFX_VERSION");
      java.nio.file.Path kfd = java.nio.file.Path.of("/dev/kfd");
      String access = !java.nio.file.Files.exists(kfd) ? "missing (the kernel's amdgpu driver without its compute part)"
         : java.nio.file.Files.isReadable(kfd) && java.nio.file.Files.isWritable(kfd) ? "usable" : "not usable by this user (add it to the render group, then log in again)";
      return "; ROCm: HSA_OVERRIDE_GFX_VERSION " + (override == null ? "not set in the game's environment (10.3.0 for a Radeon 680M / 660M)" : "= " + override)
         + ", /dev/kfd " + access;
   }

   /** What {@link #devices} found, platform by platform, and why a platform gave no GPU (for the logs). */
   public static String survey() {
      devices();
      return survey;
   }

   private static volatile String survey = "";

   private static String platformText(long platform, int parameter) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (clGetPlatformInfo(platform, parameter, (ByteBuffer)null, size) != CL_SUCCESS || size.get(0) <= 1L) {
            return "";
         }

         ByteBuffer value = MemoryUtil.memAlloc((int)size.get(0));
         try {
            clGetPlatformInfo(platform, parameter, value, null);
            return MemoryUtil.memUTF8(value, (int)size.get(0) - 1).trim();
         } finally {
            MemoryUtil.memFree(value);
         }
      }
   }

   /** An OpenCL error code with its name, for the ones listing platforms and devices gives. */
   private static String code(int result) {
      String name = switch (result) {
         case -1 -> "CL_DEVICE_NOT_FOUND";
         case -30 -> "CL_INVALID_VALUE";
         case -31 -> "CL_INVALID_DEVICE_TYPE";
         case -32 -> "CL_INVALID_PLATFORM";
         case -1001 -> "CL_PLATFORM_NOT_FOUND_KHR: the ICD loader found no driver";
         default -> "";
      };
      return name.isEmpty() ? "error " + result : "error " + result + " " + name;
   }

   private static Device describe(long platform, long device, boolean mesa) {
      String version = text(device, CL_DEVICE_VERSION);
      boolean two = version.startsWith("OpenCL 2");
      boolean three = version.startsWith("OpenCL 3");
      long svm = two || three ? number(device, CL20.CL_DEVICE_SVM_CAPABILITIES) : 0L;
      boolean generic = two || three && number(device, CL30.CL_DEVICE_GENERIC_ADDRESS_SPACE_SUPPORT) != 0L;
      String extensions = text(device, CL_DEVICE_EXTENSIONS);
      // AMD's device name is the chip's (gfx1201); its board name is the card's (CL_DEVICE_BOARD_NAME_AMD).
      String board = extensions.contains("cl_amd_device_attribute_query") ? text(device, 0x4038).trim() : "";
      return new Device(platform, device, board.isEmpty() ? text(device, CL_DEVICE_NAME).trim() : board, text(device, CL_DEVICE_VENDOR).trim(), version,
         text(device, CL_DRIVER_VERSION),
         (int)number(device, CL_DEVICE_MAX_COMPUTE_UNITS), number(device, org.lwjgl.opencl.CL11.CL_DEVICE_HOST_UNIFIED_MEMORY) != 0L,
         number(device, CL_DEVICE_GLOBAL_MEM_SIZE), number(device, CL12.CL_DEVICE_DOUBLE_FP_CONFIG) != 0L, (svm & CL20.CL_DEVICE_SVM_COARSE_GRAIN_BUFFER) != 0L, generic,
         extensions.contains("cl_khr_priority_hints"), mesa, text(device, CL_DEVICE_NAME).trim());
   }

   private static String text(long device, int parameter) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (clGetDeviceInfo(device, parameter, (ByteBuffer)null, size) != CL_SUCCESS || size.get(0) <= 1L) {
            return "";
         }

         ByteBuffer value = MemoryUtil.memAlloc((int)size.get(0));
         try {
            clGetDeviceInfo(device, parameter, value, null);
            return MemoryUtil.memUTF8(value, (int)size.get(0) - 1);
         } finally {
            MemoryUtil.memFree(value);
         }
      }
   }

   /** A numeric device property (cl_uint, cl_bool, cl_ulong or a bit field), read into 8 zeroed bytes. */
   private static long number(long device, int parameter) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         LongBuffer value = stack.callocLong(1);
         return clGetDeviceInfo(device, parameter, value, null) == CL_SUCCESS ? value.get(0) : 0L;
      }
   }

   /** Whether a GPU the automatic choice may take is there ({@link Device#automatic}). */
   static boolean automaticDevicePresent() {
      return devices().stream().anyMatch(Device::automatic);
   }

   /**
    * Whether the GPU parts run through OpenCL on Intel graphics (known before the first use). Intel's compiler (Arc iGPU,
    * driver 32.0.101.8332) had not built the surface code after more than 5 minutes, holding a core and the world's load
    * (23 Oct 2026), so the surface stays the CPU's there.
    */
   public static boolean intel() {
      if (!GpuBackend.OPENCL) {
         return false;
      }

      try {
         List<Device> list = devices();
         int index = GpuDevice.index();
         return index >= 0 && index < list.size() && list.get(index).vendor().toLowerCase(java.util.Locale.ROOT).contains("intel");
      } catch (Throwable failure) {
         return false;
      }
   }

   /** The device in use (null before the first use). */
   public static Device chosen() {
      return chosen;
   }

   /** Creates the context and its own queue on the chosen device ({@link GpuDevice}), once. */
   public static void init() {
      if (started) {
         return;
      }

      // The device chosen before this class's lock is taken: GpuDevice.choose holds its own lock while it lists the
      // devices here (devices() takes this one), so the other order deadlocked the world's loading (GpuSurface.start
      // choosing while the GPU thread started OpenCL, 26.1.2 server, 27 Oct 2026).
      GpuDevice.index();
      start();
   }

   private static synchronized void start() {
      if (started) {
         return;
      }

      List<Device> list = devices();
      int index = GpuDevice.index();
      if (index < 0 || index >= list.size()) {
         throw new IllegalStateException("OpenCL device " + index + " is not there (" + list.size() + " OpenCL GPUs)");
      }

      Device device = list.get(index);
      if (!device.usable()) {
         throw new IllegalStateException(device.name() + " (" + device.version() + ") cannot run the mod's OpenCL kernels: no " + device.missing());
      }

      if (!device.automatic() && GpuDevice.configured < 0) {
         // Automatic never takes Intel integrated graphics or GPUs under AUTOMATIC_UNITS (Device#automatic): chosen in the settings screen they run.
         throw new IllegalStateException("no GPU OpenCL can use automatically; " + device.name() + ": " + device.passedOver() + " (in the settings screen)");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         context = clCreateContext(stack.pointers(CL_CONTEXT_PLATFORM, device.platform(), 0L), device.id(), null, 0L, error);
         check(error.get(0), "clCreateContext");
      }

      chosen = device;
      // Without the generic address space (NVIDIA): OpenCL C 1.2, the compiler's default, with the spaces written out.
      String std = !device.generic() ? "" : device.version().startsWith("OpenCL 3") ? "-cl-std=CL3.0 " : "-cl-std=CL2.0 ";
      // Float division and square root correctly rounded, as Java's; no fast-maths option (OpenCL's default is strict).
      options = std + "-cl-fp32-correctly-rounded-divide-sqrt";
      // For diagnosis only (-Dwgaccel.openclOptions=-cl-opt-disable): Intel's compiler got 64-bit integers wrong in the fill.
      String extra = System.getProperty("wgaccel.openclOptions");
      if (extra != null && !extra.isBlank()) {
         options += " " + extra.trim();
      }
      checkLaunches = !device.generic() || Boolean.getBoolean("wgaccel.openclCheckLaunches");
      buffers = device.nvidia() || Boolean.getBoolean("wgaccel.openclBuffers");
      started = true;
      defaultQueue = createQueue(-1);
      if (buffers) {
         initBuffers();
      }
   }

   /** The build options, for the logs. */
   public static String options() {
      return options;
   }

   // ---- Streams: in-order command queues ----

   /** A new in-order queue; tier 0 (waited for) to 2 (background) as {@link GpuStreams}, -1 none. */
   static long createQueue(int tier) {
      init();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long queue = 0L;
         if (tier >= 0 && chosen.priorities() && GpuStreams.priorities) {
            int priority = tier == 0 ? KHRPriorityHints.CL_QUEUE_PRIORITY_HIGH_KHR : tier == 2 ? KHRPriorityHints.CL_QUEUE_PRIORITY_LOW_KHR
               : KHRPriorityHints.CL_QUEUE_PRIORITY_MED_KHR;
            queue = CL20.clCreateCommandQueueWithProperties(context, chosen.id(), stack.longs(KHRPriorityHints.CL_QUEUE_PRIORITY_KHR, priority, 0L), error);
         }

         if (queue == 0L) {
            queue = CL20.clCreateCommandQueueWithProperties(context, chosen.id(), (LongBuffer)null, error);
            check(error.get(0), "clCreateCommandQueueWithProperties");
         }

         QUEUES.add(queue);
         return queue;
      }
   }

   private static long queue(long stream) {
      return stream == 0L ? defaultQueue : stream;
   }

   static int synchronize(long stream) {
      return failed(clFinish(queue(stream)), "a wait for a queue's work");
   }

   /** Waits for every queue (before memory is freed: CUDA's cuMemFree waits for the work that may use it, clSVMFree does not). */
   private static void finishAll() {
      for (long queue : QUEUES) {
         clFinish(queue);
      }
   }

   // ---- Memory ----

   /**
    * NVIDIA's OpenCL: plain buffers used by their GPU address instead of SVM. NVIDIA's SVM passed every standalone test
    * (the probe, under load; the noise fill bench with 14 threads, with ZGC), yet in the game an SVM allocation failed
    * and the game died (RTX 5060 laptop, 24 Oct 2026). A buffer's GPU address is read once by a kernel; the kernels take
    * their buffers as these numbers ({@link OpenClSource#kernelParameters}) and the batches read them from tables, as
    * with SVM; copies find the buffer an address is in. -Dwgaccel.openclBuffers=true does it on any device.
    */
   private static boolean buffers;
   /** The plain buffers by GPU address: {cl_mem, bytes}. */
   private static final java.util.concurrent.ConcurrentSkipListMap<Long, long[]> BUFFERS = new java.util.concurrent.ConcurrentSkipListMap<>();
   private static long addressKernel;
   private static long addressOut;
   private static final Object ADDRESS = new Object();

   /** The buffers mode's address kernel and its output, once at init. */
   private static void initBuffers() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long program = clCreateProgramWithSource(context, "__kernel void wga_address(__global char* b, __global ulong* out) { out[0] = (ulong)b; }", error);
         check(error.get(0), "clCreateProgramWithSource wga_address");
         check(clBuildProgram(program, chosen.id(), "", null, 0L), "clBuildProgram wga_address");
         addressKernel = clCreateKernel(program, "wga_address", error);
         check(error.get(0), "clCreateKernel wga_address");
         addressOut = clCreateBuffer(context, CL_MEM_READ_WRITE, 8L, error);
         check(error.get(0), "clCreateBuffer");
      }
   }

   /** A plain buffer and its GPU address (the address kernel run on the context's own queue). */
   private static int allocateBuffer(PointerBuffer pointer, long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long size = Math.max(4L, bytes);
         long memory = clCreateBuffer(context, CL_MEM_READ_WRITE, size, error);
         if (error.get(0) != CL_SUCCESS) {
            return failed(error.get(0), "clCreateBuffer of " + bytes + " bytes (" + BUFFERS.size() + " buffers live)");
         }

         ByteBuffer value = stack.malloc(8);
         int result;
         synchronized (ADDRESS) {
            result = clSetKernelArg1p(addressKernel, 0, memory);
            result = result != CL_SUCCESS ? result : clSetKernelArg1p(addressKernel, 1, addressOut);
            result = result != CL_SUCCESS ? result : clEnqueueNDRangeKernel(defaultQueue, addressKernel, 1, null, stack.pointers(1L), null, null, null);
            result = result != CL_SUCCESS ? result : clEnqueueReadBuffer(defaultQueue, addressOut, true, 0L, value, null, null);
         }

         if (result != CL_SUCCESS) {
            clReleaseMemObject(memory);
            return failed(result, "reading the GPU address of a new buffer");
         }

         long address = value.getLong(0);
         BUFFERS.put(address, new long[] {memory, size});
         pointer.put(pointer.position(), address);
         return CL_SUCCESS;
      }
   }

   /** The plain buffer an address is in and the offset there ({cl_mem, offset}), or null for host memory. */
   private static long[] buffer(long address) {
      java.util.Map.Entry<Long, long[]> entry = BUFFERS.floorEntry(address);
      return entry != null && address < entry.getKey() + entry.getValue()[1] ? new long[] {entry.getValue()[0], address - entry.getKey()} : null;
   }

   static int allocate(PointerBuffer pointer, long bytes) {
      init();
      if (buffers) {
         return allocateBuffer(pointer, bytes);
      }

      long address = CL20.nclSVMAlloc(context, CL_MEM_READ_WRITE, Math.max(4L, bytes), 0);
      if (address == 0L) {
         return failed(CL_MEM_OBJECT_ALLOCATION_FAILURE, "clSVMAlloc of " + bytes + " bytes on thread " + Thread.currentThread().getName() + " (" + ALLOCATIONS.size()
            + " allocations live)");
      }

      ALLOCATIONS.add(address);
      ALLOCATIONS_CHANGED.incrementAndGet();
      pointer.put(pointer.position(), address);
      return CL_SUCCESS;
   }

   /**
    * Launches (from giving their kernel the allocations list to enqueueing) against frees. A launch that read the list
    * just before a free went out with the freed buffer in it, and the free took the memory from under it: with 6 threads
    * filling tile noise tables on one Gpu (each job allocating and freeing its buffers), wrong values in most jobs; with
    * no frees, or launches and frees apart, none.
    */
   private static final java.util.concurrent.locks.ReentrantReadWriteLock FREEING = new java.util.concurrent.locks.ReentrantReadWriteLock();

   static int free(long address) {
      if (address != 0L) {
         FREEING.writeLock().lock();
         try {
            finishAll();
            if (buffers) {
               long[] buffer = BUFFERS.remove(address);
               return buffer == null ? CL_INVALID_VALUE : clReleaseMemObject(buffer[0]);
            }

            ALLOCATIONS.remove(address);
            ALLOCATIONS_CHANGED.incrementAndGet();
            CL20.nclSVMFree(context, address);
         } finally {
            FREEING.writeLock().unlock();
         }
      }

      return CL_SUCCESS;
   }

   /** Host memory for copies (OpenCL has no page-locked allocation of its own; an aligned block copies as well). */
   static int allocateHost(PointerBuffer pointer, long bytes) {
      long address = MemoryUtil.nmemAlignedAlloc(4096L, Math.max(4096L, bytes));
      if (address == 0L) {
         return CL_OUT_OF_HOST_MEMORY;
      }

      pointer.put(pointer.position(), address);
      return CL_SUCCESS;
   }

   static int freeHost(long address) {
      if (address != 0L) {
         finishAll();
         MemoryUtil.nmemAlignedFree(address);
      }

      return CL_SUCCESS;
   }

   /** A copy between any two of host memory and device memory; waited for when blocking, else in order on the stream. */
   static int copy(long to, long from, long bytes, long stream, boolean blocking) {
      if (bytes <= 0L) {
         return CL_SUCCESS;
      }

      long queue = queue(stream);
      int result;
      if (buffers) {
         long[] into = buffer(to);
         long[] out = buffer(from);
         if (into == null && out == null) {
            MemoryUtil.memCopy(from, to, bytes);
            return CL_SUCCESS;
         }

         result = into == null ? nclEnqueueReadBuffer(queue, out[0], blocking ? 1 : 0, out[1], bytes, to, 0, 0L, 0L)
            : out == null ? nclEnqueueWriteBuffer(queue, into[0], blocking ? 1 : 0, into[1], bytes, from, 0, 0L, 0L)
            : nclEnqueueCopyBuffer(queue, out[0], into[0], out[1], into[1], bytes, 0, 0L, 0L);
         if (result == CL_SUCCESS && blocking && into != null && out != null) {
            result = clFinish(queue);
         }
      } else {
         result = CL20.nclEnqueueSVMMemcpy(queue, blocking ? 1 : 0, to, from, bytes, 0, 0L, 0L);
      }

      failed(result, "a copy of " + bytes + " bytes");
      if (result == CL_SUCCESS && !blocking) {
         clFlush(queue);
      }

      return result;
   }

   // ---- Modules and kernels ----

   /** Translates, builds (or loads the driver's binary kept on disk) and returns the program. */
   public static long loadModule(String source, String name) {
      init();
      OpenClSource.Translated translated = OpenClSource.translate(source, !chosen.generic(), buffers);
      java.nio.file.Path cached = binaryPath(translated.text());
      long program = cached != null && java.nio.file.Files.exists(cached) ? fromBinary(cached) : 0L;
      if (program == 0L) {
         program = fromSource(translated.text(), name);
         if (cached != null) {
            keepBinary(program, cached);
         }
      }

      PROGRAMS.put(program, translated.kernels());
      return program;
   }

   private static long fromSource(String text, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer error = stack.mallocInt(1);
         long program = clCreateProgramWithSource(context, text, error);
         check(error.get(0), "clCreateProgramWithSource " + name);
         int built = clBuildProgram(program, chosen.id(), options, null, 0L);
         if (built != CL_SUCCESS) {
            String log = buildLog(program);
            clReleaseProgram(program);
            failedSource = keepFailed(text, name);
            throw new IllegalStateException("OpenCL could not build " + name + ": " + errorName(built) + (failedSource == null ? "" : " (translated source: " + failedSource + ")")
               + "\n" + (log.length() > 6000 ? log.substring(0, 6000) + "\n..." : log));
         }

         return program;
      }
   }

   private static String buildLog(long program) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (clGetProgramBuildInfo(program, chosen.id(), CL_PROGRAM_BUILD_LOG, (ByteBuffer)null, size) != CL_SUCCESS || size.get(0) <= 1L) {
            return "";
         }

         ByteBuffer log = MemoryUtil.memAlloc((int)size.get(0));
         try {
            clGetProgramBuildInfo(program, chosen.id(), CL_PROGRAM_BUILD_LOG, log, null);
            return MemoryUtil.memUTF8(log, (int)size.get(0) - 1).trim();
         } finally {
            MemoryUtil.memFree(log);
         }
      }
   }

   /** The translated source of a build that failed, beside the cache (or in the temporary folder), for the report. */
   private static String keepFailed(String text, String name) {
      try {
         java.nio.file.Path directory = GpuSelfTest.cacheDirectory != null ? GpuSelfTest.cacheDirectory : java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
         java.nio.file.Files.createDirectories(directory);
         java.nio.file.Path file = directory.resolve("opencl-failed-" + name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".cl");
         java.nio.file.Files.writeString(file, text);
         return file.toString();
      } catch (Exception failure) {
         return null;
      }
   }

   /** Where a program's binary is kept: the SHA-256 of its text, the options and the device and driver; or null without a cache. */
   private static java.nio.file.Path binaryPath(String text) {
      java.nio.file.Path directory = GpuSelfTest.cacheDirectory;
      if (directory == null || !GpuSelfTest.cubinCache) {
         return null;
      }

      try {
         java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
         digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
         digest.update((options + "|" + chosen.name() + "|" + chosen.version() + "|" + chosen.driver()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
         return directory.resolve(java.util.HexFormat.of().formatHex(digest.digest()) + ".clbin");
      } catch (Exception failure) {
         return null;
      }
   }

   private static long fromBinary(java.nio.file.Path cached) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         byte[] bytes = java.nio.file.Files.readAllBytes(cached);
         ByteBuffer binary = MemoryUtil.memAlloc(bytes.length);
         try {
            binary.put(0, bytes);
            IntBuffer status = stack.mallocInt(1);
            IntBuffer error = stack.mallocInt(1);
            long program = clCreateProgramWithBinary(context, stack.pointers(chosen.id()), binary, status, error);
            if (error.get(0) == CL_SUCCESS && status.get(0) == CL_SUCCESS && clBuildProgram(program, chosen.id(), options, null, 0L) == CL_SUCCESS) {
               return program;
            }

            if (program != 0L) {
               clReleaseProgram(program);
            }
         } finally {
            MemoryUtil.memFree(binary);
         }
      } catch (Exception ignored) {
      }

      try {
         java.nio.file.Files.deleteIfExists(cached);
      } catch (Exception ignored) {
      }

      return 0L;
   }

   private static void keepBinary(long program, java.nio.file.Path cached) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         if (clGetProgramInfo(program, CL_PROGRAM_BINARY_SIZES, size, null) != CL_SUCCESS || size.get(0) <= 0L) {
            return;
         }

         ByteBuffer binary = MemoryUtil.memAlloc((int)size.get(0));
         try {
            if (clGetProgramInfo(program, CL_PROGRAM_BINARIES, stack.pointers(binary), null) != CL_SUCCESS) {
               return;
            }

            byte[] bytes = new byte[binary.remaining()];
            binary.get(0, bytes);
            java.nio.file.Files.createDirectories(cached.getParent());
            java.nio.file.Path temporary = cached.resolveSibling(cached.getFileName() + ".tmp" + Thread.currentThread().getId());
            java.nio.file.Files.write(temporary, bytes);
            java.nio.file.Files.move(temporary, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
         } finally {
            MemoryUtil.memFree(binary);
         }
      } catch (Exception ignored) {
      }
   }

   static int unloadModule(long program) {
      PROGRAMS.remove(program);
      KERNEL_INFO.values().removeIf(kernel -> {
         if (kernel.program != program) {
            return false;
         }

         for (long made : kernel.made) {
            clReleaseKernel(made);
         }

         return true;
      });
      KERNELS.entrySet().removeIf(entry -> entry.getKey().startsWith(program + ":"));
      return clReleaseProgram(program);
   }

   /**
    * A kernel of a module by name, as CUDA's function handle: one per name and module, used from any thread. The handle
    * is a number of the mod's; each thread launches through a kernel object of its own ({@link #launch}).
    */
   static int getFunction(PointerBuffer function, long program, ByteBuffer name) {
      String kernel = MemoryUtil.memASCII(MemoryUtil.memAddress(name), MemoryUtil.memLengthNT1(name));
      Map<String, int[]> kernels = PROGRAMS.get(program);
      int[] sizes = kernels == null ? null : kernels.get(kernel);
      if (sizes == null) {
         return CL_INVALID_KERNEL_NAME;
      }

      long handle = KERNELS.computeIfAbsent(program + ":" + kernel, key -> {
         long made = NEXT_KERNEL.incrementAndGet();
         KERNEL_INFO.put(made, new Kernel(program, kernel, sizes));
         return made;
      });
      function.put(function.position(), handle);
      return CL_SUCCESS;
   }

   /**
    * cuLaunchKernel: a grid of blocks, the arguments as the addresses of their values (CUDA's list). OpenCL sets a
    * kernel's arguments on the kernel object itself, so each thread has its own objects for each kernel: no thread sees
    * another's arguments, whenever the driver reads them (shared objects under a lock gave 3 wrong chunks of 2,520 on a
    * Radeon with 14 threads filling at once; HIP none). And a thread's object takes new arguments only once its last
    * launch is done, else another object of the kernel is used: AMD's driver read them when the launch ran, not when it
    * was queued (25 Oct 2026, RX 9070 XT: the erosion batch's envelope window launch, queued just before the gate's with
    * the same kernel, sometimes ran with the gate's arguments, a busy GPU during RoadWeaver's road planning).
    */
   static int launch(long handle, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream, PointerBuffer params) {
      FREEING.readLock().lock();
      try {
         return launchNow(handle, gridX, gridY, gridZ, blockX, blockY, blockZ, sharedBytes, stream, params);
      } finally {
         FREEING.readLock().unlock();
      }
   }

   private static int launchNow(long handle, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ, int sharedBytes, long stream, PointerBuffer params) {
      Kernel info = KERNEL_INFO.get(handle);
      if (info == null || sharedBytes != 0) {
         return info == null ? CL_INVALID_KERNEL : CL_INVALID_VALUE;
      }

      List<Own> mine = OWN.get().computeIfAbsent(handle, key -> new ArrayList<>());
      Own own = null;
      for (Own each : mine) {
         if (each.free()) {
            own = each;
            break;
         }
      }

      if (own == null) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            long made = clCreateKernel(info.program, info.name, error);
            if (error.get(0) != CL_SUCCESS) {
               return error.get(0);
            }

            info.made.add(made);
            own = new Own(made);
            mine.add(own);
         }
      }

      long kernel = own.kernel;
      long changed = ALLOCATIONS_CHANGED.get();
      if (!buffers && own.allocations != changed) {
         Long[] live = ALLOCATIONS.toArray(new Long[0]);
         PointerBuffer list = MemoryUtil.memAllocPointer(Math.max(1, live.length));
         try {
            for (int i = 0; i < live.length; i++) {
               list.put(i, live[i]);
            }

            int told = CL20.nclSetKernelExecInfo(kernel, CL20.CL_KERNEL_EXEC_INFO_SVM_PTRS, (long)live.length * org.lwjgl.system.Pointer.POINTER_SIZE,
               MemoryUtil.memAddress(list));
            if (told != CL_SUCCESS) {
               return failed(told, "the allocations list (" + live.length + ") of " + info.name);
            }
         } finally {
            MemoryUtil.memFree(list);
         }

         own.allocations = changed;
      }

      int[] sizes = info.sizes;
      long queue = queue(stream);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         for (int i = 0; i < sizes.length; i++) {
            long value = params.get(i);
            int result;
            if (sizes[i] == 0) {
               long address = MemoryUtil.memGetAddress(value);
               // Buffers: the address as a number (the kernel takes ulong name__a).
               result = buffers ? nclSetKernelArg(kernel, i, 8L, value) : address == 0L ? nclSetKernelArg(kernel, i, org.lwjgl.system.Pointer.POINTER_SIZE, 0L)
                  : CL20.nclSetKernelArgSVMPointer(kernel, i, address);
            } else {
               result = nclSetKernelArg(kernel, i, sizes[i], value);
            }

            if (result != CL_SUCCESS) {
               return failed(result, "argument " + i + " of " + info.name);
            }
         }

         PointerBuffer global = stack.pointers((long)gridX * blockX, (long)gridY * blockY, (long)gridZ * blockZ);
         PointerBuffer local = stack.pointers(blockX, blockY, blockZ);
         PointerBuffer launched = stack.mallocPointer(1);
         int result = clEnqueueNDRangeKernel(queue, kernel, 3, null, global, local, null, launched);
         if (result != CL_SUCCESS) {
            return failed(result, "the launch of " + info.name + " (" + gridX * blockX + " x " + gridY * blockY + " threads, blocks of " + blockX + ")");
         }

         own.event = launched.get(0);
      }

      if (checkLaunches && CHECKED.computeIfAbsent(handle, key -> new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement() < CHECKED_LAUNCHES) {
         int done = clFinish(queue);
         if (done != CL_SUCCESS) {
            return failed(done, "kernel " + info.name + ", waited for after its launch");
         }
      }

      // Started at once, as CUDA's launches are (OpenCL may hold queued work until a flush or a wait).
      return clFlush(queue);
   }

   // ---- Events (for the timings only, which are off under OpenCL; recorded and queried as CUDA's) ----

   static int eventCreate(PointerBuffer event) {
      long id = NEXT_EVENT.incrementAndGet();
      EVENTS.put(id, new long[1]);
      event.put(event.position(), id);
      return CL_SUCCESS;
   }

   static int eventRecord(long id, long stream) {
      long[] slot = EVENTS.get(id);
      if (slot == null) {
         return CL_INVALID_EVENT;
      }

      long queue = queue(stream);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer made = stack.mallocPointer(1);
         int result = CL12.clEnqueueMarkerWithWaitList(queue, null, made);
         if (result != CL_SUCCESS) {
            return result;
         }

         synchronized (slot) {
            if (slot[0] != 0L) {
               clReleaseEvent(slot[0]);
            }

            slot[0] = made.get(0);
         }
      }

      return clFlush(queue);
   }

   static int eventQuery(long id) {
      long[] slot = EVENTS.get(id);
      if (slot == null) {
         return CL_INVALID_EVENT;
      }

      synchronized (slot) {
         if (slot[0] == 0L) {
            return CL_SUCCESS;
         }

         try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer status = stack.mallocInt(1);
            int result = clGetEventInfo(slot[0], CL_EVENT_COMMAND_EXECUTION_STATUS, status, null);
            if (result != CL_SUCCESS) {
               return result;
            }

            return status.get(0) == CL_COMPLETE ? CL_SUCCESS : status.get(0) < 0 ? status.get(0) : NOT_READY;
         }
      }
   }

   static int eventElapsedTime() {
      return UNSUPPORTED;
   }

   // ---- Errors ----

   /** The result, kept as the first failure of the game when it is one and none came before. */
   private static int failed(int result, String what) {
      if (result != CL_SUCCESS && result != NOT_READY && firstFailure == null) {
         firstFailure = what + " (" + plainName(result) + ")";
      }

      return result;
   }

   static void check(int result, String what) {
      if (result != CL_SUCCESS) {
         throw new IllegalStateException(what + " failed: OpenCL error " + result + " (" + errorName(result) + ")");
      }
   }

   public static String errorName(int result) {
      String first = firstFailure;
      return plainName(result) + (result != CL_SUCCESS && result != NOT_READY && first != null ? "; the first OpenCL failure in this game: " + first : "");
   }

   private static String plainName(int result) {
      return switch (result) {
         case CL_SUCCESS -> "success";
         case CL_DEVICE_NOT_FOUND -> "CL_DEVICE_NOT_FOUND";
         case CL_MEM_OBJECT_ALLOCATION_FAILURE -> "CL_MEM_OBJECT_ALLOCATION_FAILURE";
         case CL_OUT_OF_RESOURCES -> "CL_OUT_OF_RESOURCES";
         case CL_OUT_OF_HOST_MEMORY -> "CL_OUT_OF_HOST_MEMORY";
         case CL_BUILD_PROGRAM_FAILURE -> "CL_BUILD_PROGRAM_FAILURE";
         case CL_INVALID_VALUE -> "CL_INVALID_VALUE";
         case CL_INVALID_BUILD_OPTIONS -> "CL_INVALID_BUILD_OPTIONS";
         case CL_INVALID_KERNEL_NAME -> "CL_INVALID_KERNEL_NAME";
         case CL_INVALID_KERNEL -> "CL_INVALID_KERNEL";
         case CL_INVALID_ARG_INDEX -> "CL_INVALID_ARG_INDEX";
         case CL_INVALID_ARG_VALUE -> "CL_INVALID_ARG_VALUE";
         case CL_INVALID_ARG_SIZE -> "CL_INVALID_ARG_SIZE";
         case CL_INVALID_WORK_GROUP_SIZE -> "CL_INVALID_WORK_GROUP_SIZE";
         case CL_INVALID_EVENT -> "CL_INVALID_EVENT";
         case CL_INVALID_OPERATION -> "CL_INVALID_OPERATION";
         case NOT_READY -> "not ready";
         default -> String.format(Locale.ROOT, "%d", result);
      };
   }
}
