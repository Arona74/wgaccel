package com.wgaccel.gpu;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mod's own GPU memory, live and at its peak: buffers on the card (cuMemAlloc / hipMalloc) and page-locked host
 * buffers (cuMemAllocHost / hipHostMalloc, which stay in RAM and count in the game's private bytes). A user found the
 * game's RAM too high (0.18.1); the 23 Oct 2026 r1000 had 3.4 GB more native memory with the mod than without, and these
 * numbers tell ours from the driver's.
 */
public final class GpuMemory {
   private static final ConcurrentHashMap<Long, Long> DEVICE = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<Long, Long> HOST = new ConcurrentHashMap<>();
   private static final AtomicLong DEVICE_BYTES = new AtomicLong();
   private static final AtomicLong HOST_BYTES = new AtomicLong();
   private static final AtomicLong DEVICE_PEAK = new AtomicLong();
   private static final AtomicLong HOST_PEAK = new AtomicLong();

   private GpuMemory() {
   }

   static void device(long address, long bytes) {
      add(DEVICE, DEVICE_BYTES, DEVICE_PEAK, address, bytes);
   }

   static void deviceFreed(long address) {
      remove(DEVICE, DEVICE_BYTES, address);
   }

   static void host(long address, long bytes) {
      add(HOST, HOST_BYTES, HOST_PEAK, address, bytes);
   }

   static void hostFreed(long address) {
      remove(HOST, HOST_BYTES, address);
   }

   private static void add(ConcurrentHashMap<Long, Long> live, AtomicLong total, AtomicLong peak, long address, long bytes) {
      if (address != 0L && live.put(address, bytes) == null) {
         peak.accumulateAndGet(total.addAndGet(bytes), Math::max);
      }
   }

   private static void remove(ConcurrentHashMap<Long, Long> live, AtomicLong total, long address) {
      Long bytes = live.remove(address);
      if (bytes != null) {
         total.addAndGet(-bytes);
      }
   }

   // What the GPU runtime takes in the process for itself: programs loaded (and compiled), streams made, and how much the
   // process's committed memory grew across those calls (other threads' allocations meanwhile included, so a rough share).
   private static final AtomicLong MODULES = new AtomicLong();
   private static final AtomicLong MODULE_GROWTH = new AtomicLong();
   private static final AtomicLong STREAMS = new AtomicLong();
   private static final AtomicLong STREAM_GROWTH = new AtomicLong();

   /** The process's committed memory (Windows: private bytes), or -1. */
   public static long committed() {
      return java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
         ? os.getCommittedVirtualMemorySize() : -1L;
   }

   static void moduleLoaded(long committedBefore) {
      MODULES.incrementAndGet();
      grew(MODULE_GROWTH, committedBefore);
   }

   static void streamMade(long committedBefore) {
      STREAMS.incrementAndGet();
      grew(STREAM_GROWTH, committedBefore);
   }

   private static void grew(AtomicLong growth, long before) {
      long after = committed();
      if (before >= 0L && after > before) {
         growth.addAndGet(after - before);
      }
   }

   /** For the log: "GPU memory: on the card 512 MB (peak 600), page-locked in RAM 40 MB (peak 48), ...". */
   public static String summary() {
      return String.format(Locale.ROOT, "GPU memory: on the card %,d MB in %,d buffers (peak %,d MB), page-locked in RAM %,d MB in %,d buffers (peak %,d MB); "
         + "GPU runtime: %,d programs loaded (process grew %,d MB across them), %,d streams (grew %,d MB across them)",
         DEVICE_BYTES.get() >> 20, DEVICE.size(), DEVICE_PEAK.get() >> 20, HOST_BYTES.get() >> 20, HOST.size(), HOST_PEAK.get() >> 20,
         MODULES.get(), MODULE_GROWTH.get() >> 20, STREAMS.get(), STREAM_GROWTH.get() >> 20);
   }
}
