package com.wgaccel.gpu;

import com.wgaccel.gpu.cuda.CU;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

/**
 * Every graphics adapter of the PC (the OS's list, through OSHI) and which of them the mod can use: the devices NVIDIA's
 * CUDA, AMD's HIP and OpenCL see, each matched to its adapter by name (CUDA's and HIP's first, so a card both see is
 * offered with its own API; OpenCL's for the rest, Intel's GPUs). For the settings screen, on PCs with more than one GPU (a
 * laptop's Intel Arc beside an NVIDIA card, a Ryzen's own Radeon beside a graphics card). Listing starts the drivers but
 * chooses nothing: {@link GpuBackend} and {@link GpuDevice} still decide once, from the config, when the GPU is first used.
 */
public final class GpuInventory {
   /** A device one of the mod's backends can run on: its backend ("CUDA", "HIP" or "OpenCL") and its number there (config gpuDevice). */
   public record Device(String backend, int index, String name, int units, boolean integrated, long memory) {
   }

   /**
    * One adapter of the PC: every backend's device for it (CUDA's or HIP's first, then OpenCL's; empty when none can use
    * it) and the OS's driver version ("" when unknown).
    */
   public record Adapter(String name, String vendor, long memory, String driver, List<Device> devices) {
      /** The device the mod offers it with first (its own API before OpenCL), or null when none can use it. */
      public Device device() {
         return this.devices.isEmpty() ? null : this.devices.get(0);
      }
   }

   /** The adapters, the device AUTO would take (null: none usable), and what went wrong while listing (empty when nothing). */
   public record Inventory(List<Adapter> adapters, Device automatic, List<String> problems) {
   }

   private static volatile Inventory inventory;
   private static volatile boolean started;

   private GpuInventory() {
   }

   /** The list, or null while it is still being made (the first ask starts it on a thread of its own). */
   public static Inventory get() {
      Inventory known = inventory;
      if (known == null && !started) {
         start();
      }

      return known;
   }

   private static synchronized void start() {
      if (started) {
         return;
      }

      started = true;
      Thread thread = new Thread(() -> inventory = make(), "WGA GPU inventory");
      thread.setDaemon(true);
      thread.start();
   }

   private static Inventory make() {
      List<String> problems = new ArrayList<>();
      List<Device> cuda = new ArrayList<>();
      List<Device> hip = new ArrayList<>();
      // Before the first cuInit (GpuEnv: the variable must be set before the driver starts).
      GpuEnv.deviceConnections(com.wgaccel.Config.GPU_DEVICE_CONNECTIONS.get());
      if (nvidiaDriver()) {
         try {
            cuda.addAll(cudaDevices());
         } catch (Throwable failure) {
            problems.add("CUDA: " + failure);
         }
      }

      try {
         hip.addAll(hipDevices());
      } catch (Throwable failure) {
         // No AMD driver with a HIP runtime: normal on NVIDIA and Intel PCs.
      }

      List<Device> openCl = new ArrayList<>();
      try {
         List<OpenCl.Device> found = OpenCl.devices();
         for (int d = 0; d < found.size(); d++) {
            OpenCl.Device device = found.get(d);
            if (device.usable()) {
               openCl.add(new Device("OpenCL", d, device.name(), device.units(), device.integrated(), device.memory()));
            }
         }
      } catch (Throwable failure) {
         problems.add("OpenCL: " + failure);
      }

      List<List<Device>> backends = List.of(cuda, hip, openCl);
      List<Adapter> adapters = new ArrayList<>();
      Set<Device> matched = new HashSet<>();
      try {
         for (oshi.hardware.GraphicsCard card : new oshi.SystemInfo().getHardware().getGraphicsCards()) {
            adapters.add(new Adapter(card.getName(), card.getVendor(), card.getVRam(), driver(card.getVersionInfo()), match(card.getName(), backends, matched)));
         }
      } catch (Throwable failure) {
         problems.add("the OS's adapter list: " + failure);
      }

      for (List<Device> list : List.of(cuda, hip)) {
         for (Device device : list) {
            if (!matched.contains(device)) {
               matched.add(device);
               List<Device> devices = new ArrayList<>(List.of(device));
               devices.addAll(match(device.name(), List.of(openCl), matched));
               adapters.add(new Adapter(device.name(), device.backend().equals("CUDA") ? "NVIDIA" : "AMD", device.memory(), "", List.copyOf(devices)));
            }
         }
      }

      // OpenCL also lists the cards CUDA and HIP see: only a device no adapter has a name like is added.
      for (Device device : openCl) {
         if (!matched.contains(device) && adapters.stream().noneMatch(adapter -> alike(adapter.name(), device.name()))) {
            OpenCl.Device found = OpenCl.devices().get(device.index());
            adapters.add(new Adapter(device.name(), found.vendor(), device.memory(), found.driver(), List.of(device)));
         }
      }

      return new Inventory(List.copyOf(adapters), automatic(hip, openCl), List.copyOf(problems));
   }

   /**
    * As GpuBackend and GpuDevice choose with AUTO and -1: HIP's GPUs on Linux with ROCm ({@link Hip#linuxAutomatic}),
    * otherwise OpenCL's but Intel integrated graphics ({@link OpenCl.Device#automatic}); a separate card first, then the
    * most units. CUDA only when chosen.
    */
   private static Device automatic(List<Device> hip, List<Device> openCl) {
      List<Device> list = !hip.isEmpty() && Hip.linuxAutomatic() ? hip
         : openCl.stream().filter(device -> OpenCl.devices().get(device.index()).automatic()).toList();
      Device best = null;
      long bestScore = -1L;
      for (Device device : list) {
         long score = (device.integrated() ? 0L : 1L << 32) + device.units();
         if (score > bestScore) {
            bestScore = score;
            best = device;
         }
      }

      return best;
   }

   /**
    * Each backend's first device not taken yet whose name is the adapter's (letters and digits compared, one inside the
    * other), in the backends' order.
    */
   private static List<Device> match(String adapter, List<List<Device>> backends, Set<Device> matched) {
      List<Device> devices = new ArrayList<>();
      for (List<Device> list : backends) {
         for (Device device : list) {
            if (!matched.contains(device) && alike(adapter, device.name())) {
               matched.add(device);
               devices.add(device);
               break;
            }
         }
      }

      return List.copyOf(devices);
   }

   /** The driver's version from OSHI's "DriverVersion=32.0.15.6094" ("" when it gives none). */
   private static String driver(String info) {
      if (info == null) {
         return "";
      }

      int at = info.indexOf("DriverVersion=");
      return at < 0 ? info.trim() : info.substring(at + "DriverVersion=".length()).split("[,;]")[0].trim();
   }

   private static boolean alike(String one, String other) {
      String a = plain(one);
      String b = plain(other);
      return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
   }

   private static String plain(String name) {
      return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
   }

   private static List<Device> cudaDevices() {
      List<Device> devices = new ArrayList<>();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         if (CU.cuInit(0) != CU.CUDA_SUCCESS || CU.cuDeviceGetCount(count) != CU.CUDA_SUCCESS) {
            return devices;
         }

         for (int d = 0; d < count.get(0); d++) {
            IntBuffer device = stack.mallocInt(1);
            IntBuffer value = stack.mallocInt(1);
            ByteBuffer name = stack.malloc(256);
            PointerBuffer bytes = stack.mallocPointer(1);
            CU.cuDeviceGet(device, d);
            CU.cuDeviceGetName(name, device.get(0));
            CU.cuDeviceGetAttribute(value, CU.CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT, device.get(0));
            int units = value.get(0);
            CU.cuDeviceGetAttribute(value, CU.CU_DEVICE_ATTRIBUTE_INTEGRATED, device.get(0));
            boolean integrated = value.get(0) != 0;
            long memory = CU.cuDeviceTotalMem(bytes, device.get(0)) == CU.CUDA_SUCCESS ? bytes.get(0) : 0L;
            devices.add(new Device("CUDA", d, MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name)), units, integrated, memory));
         }
      }

      return devices;
   }

   private static List<Device> hipDevices() {
      List<Device> devices = new ArrayList<>();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         if (Hip.hipInit(0) != Hip.hipSuccess || Hip.hipGetDeviceCount(count) != Hip.hipSuccess) {
            return devices;
         }

         for (int d = 0; d < count.get(0); d++) {
            IntBuffer value = stack.mallocInt(1);
            ByteBuffer name = stack.malloc(256);
            Hip.hipDeviceGetName(name, d);
            Hip.hipDeviceGetAttribute(value, Hip.hipDeviceAttributeMultiprocessorCount, d);
            int units = value.get(0);
            Hip.hipDeviceGetAttribute(value, Hip.hipDeviceAttributeIntegrated, d);
            boolean integrated = value.get(0) != 0;
            devices.add(new Device("HIP", d, MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name)), units, integrated, 0L));
         }
      }

      return devices;
   }

   /**
    * Whether NVIDIA's driver library is installed, by looking for its file (loading it to find out would make LWJGL
    * print a warning in the log of every PC without it): nvcuda.dll in System32, or libcuda.so.1 in the usual Linux
    * folders.
    */
   static boolean nvidiaDriver() {
      if (Platform.get() == Platform.WINDOWS) {
         return new File(System.getenv().getOrDefault("SystemRoot", "C:/Windows"), "System32/nvcuda.dll").isFile();
      }

      for (String folder : new String[] {"/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/lib/wsl/lib", "/usr/local/cuda/lib64"}) {
         if (new File(folder, "libcuda.so.1").isFile()) {
            return true;
         }
      }

      return false;
   }
}
