package com.wgaccel.gpu;

import com.wgaccel.gpu.cuda.CU;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Which GPU the mod uses when the PC has more than one (a desktop Ryzen's own small Radeon beside the graphics card, two
 * cards): config gpuDevice, or -Dwgaccel.gpuDevice=N for the standalone checks; -1 (the default) chooses: the card with
 * the most multiprocessors (CUDA's SMs, HIP's compute or work-group units), a separate card before an integrated one.
 * Until 0.11.5 always the first device, which on a desktop with an integrated GPU can be the small one. Decided once,
 * the first time the GPU is used: a change takes a restart of the game.
 */
public final class GpuDevice {
   /** Set from config gpuDevice before the GPU is first used; -1: automatic. */
   public static volatile int configured = Integer.getInteger("wgaccel.gpuDevice", -1);
   /**
    * Config gpuBackend (AUTO, CUDA, HIP or OPENCL), set before the GPU is first used. Here rather than in {@link GpuBackend},
    * whose class makes its choice as it loads.
    */
   public static volatile String configuredBackend = "AUTO";
   /** How the device was chosen and the devices seen, for the logs. */
   public static volatile String reason = "not chosen yet";
   private static volatile int chosen = -1;
   /** The chosen device's name once chosen (the world-loading screen's hardware list), else null. */
   public static volatile String chosenName;
   /** Whether a GPU context has started on the chosen device (the GPU parts can run; the join message names it). */
   public static volatile boolean running;

   private GpuDevice() {
   }

   /** The device ordinal the GPU parts use (CUDA's cuDeviceGet, HIP's hipSetDevice, OpenCL's {@link OpenCl#devices}). */
   public static int index() {
      int index = chosen;
      return index >= 0 ? index : choose();
   }

   private static synchronized int choose() {
      if (chosen >= 0) {
         return chosen;
      }

      int asked = configured;
      List<String> seen = new ArrayList<>();
      int best = 0;
      long bestScore = -1L;
      int count = 0;
      List<String> names = new ArrayList<>();
      try {
         count = count();
         for (int d = 0; d < count; d++) {
            int units = attribute(d, false);
            boolean integrated = attribute(d, true) != 0;
            names.add(name(d));
            // OpenCL lists GPUs the mod cannot run on too (no double precision or shared virtual memory): never chosen automatically.
            String missing = GpuBackend.OPENCL ? OpenCl.devices().get(d).missing() : "";
            seen.add(d + " " + names.get(d) + " (" + units + " units" + (integrated ? ", integrated" : "") + (missing.isEmpty() ? "" : ", no " + missing) + ")");
            // Intel integrated graphics never automatically (OpenCl.Device#automatic): slower than the CPU on the one measured.
            boolean skipped = GpuBackend.OPENCL && !OpenCl.devices().get(d).automatic();
            long score = missing.isEmpty() && !skipped ? (integrated ? 0L : 1L << 32) + units : -1L;
            if (score > bestScore) {
               bestScore = score;
               best = d;
            }
         }
      } catch (Throwable failure) {
         // Choosing must not stop the GPU from starting: the first device, as before, and the self-test says the rest.
         seen.add("listing failed: " + failure);
      }

      String devices = "; devices: " + (seen.isEmpty() ? "none" : String.join(", ", seen));
      if (asked >= 0 && asked < count) {
         chosen = asked;
         reason = "config gpuDevice = " + asked + devices;
      } else {
         chosen = best;
         reason = (asked >= 0 ? "config gpuDevice = " + asked + " is not a device here, so " : "") + "automatic: the most units, a separate card first"
            + devices;
      }

      chosenName = chosen < names.size() ? names.get(chosen) : null;
      return chosen;
   }

   private static int count() {
      if (GpuBackend.OPENCL) {
         return OpenCl.devices().size();
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer value = stack.mallocInt(1);
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipInit(0), "hipInit");
            Hip.check(Hip.hipGetDeviceCount(value), "hipGetDeviceCount");
         } else {
            GpuSelfTest.check(CU.cuInit(0), "cuInit");
            GpuSelfTest.check(CU.cuDeviceGetCount(value), "cuDeviceGetCount");
         }

         return value.get(0);
      }
   }

   /** The device's multiprocessor count, or whether it is integrated (1) or not (0). */
   private static int attribute(int ordinal, boolean integrated) {
      if (GpuBackend.OPENCL) {
         OpenCl.Device device = OpenCl.devices().get(ordinal);
         return integrated ? (device.integrated() ? 1 : 0) : device.units();
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer value = stack.mallocInt(1);
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipDeviceGetAttribute(value, integrated ? Hip.hipDeviceAttributeIntegrated : Hip.hipDeviceAttributeMultiprocessorCount, ordinal),
               "hipDeviceGetAttribute");
         } else {
            IntBuffer device = stack.mallocInt(1);
            GpuSelfTest.check(CU.cuDeviceGet(device, ordinal), "cuDeviceGet");
            GpuSelfTest.check(CU.cuDeviceGetAttribute(value, integrated ? CU.CU_DEVICE_ATTRIBUTE_INTEGRATED : CU.CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT,
               device.get(0)), "cuDeviceGetAttribute");
         }

         return value.get(0);
      }
   }

   private static String name(int ordinal) {
      if (GpuBackend.OPENCL) {
         return OpenCl.devices().get(ordinal).name();
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         ByteBuffer name = stack.malloc(256);
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipDeviceGetName(name, ordinal), "hipDeviceGetName");
         } else {
            IntBuffer device = stack.mallocInt(1);
            GpuSelfTest.check(CU.cuDeviceGet(device, ordinal), "cuDeviceGet");
            GpuSelfTest.check(CU.cuDeviceGetName(name, device.get(0)), "cuDeviceGetName");
         }

         return MemoryUtil.memASCII(name, MemoryUtil.memLengthNT1(name));
      }
   }
}
