package com.wgaccel.client;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How busy the GPU is, for the Voxy LOD panel: a daemon thread samples once a second while someone asked in the last
 * few seconds, and {@link #percent} gives the last sample (-1: not known).
 * <ul>
 *   <li>Windows: the "GPU Engine" performance counters (Utilization Percentage of every process's engines), summed per
 *   engine across processes; the busiest engine, as Task Manager's GPU column.
 *   <li>Linux: amdgpu's gpu_busy_percent (the busiest card); others not known.
 * </ul>
 */
final class GpuUsage {
   private static final long SAMPLE_MILLIS = 1000L;
   /** The sampler stops when nothing asked for this long. */
   private static final long IDLE_NANOS = 5_000_000_000L;
   private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
   private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
   private static volatile double percent = -1.0;
   private static volatile long askedAt;
   private static volatile boolean running;
   private static volatile boolean unavailable = !WINDOWS && !LINUX;

   private GpuUsage() {
   }

   /** The GPU's busy share, 0-100, or -1 when not known (yet); starts the sampler. */
   static double percent() {
      askedAt = System.nanoTime();
      if (!running && !unavailable) {
         start();
      }

      return unavailable ? -1.0 : percent;
   }

   private static synchronized void start() {
      if (running || unavailable) {
         return;
      }

      running = true;
      Thread thread = new Thread(GpuUsage::sample, "WGA GPU usage");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      thread.start();
   }

   private static void sample() {
      Sampler sampler = null;
      try {
         sampler = WINDOWS ? new WindowsSampler() : new LinuxSampler();
         while (System.nanoTime() - askedAt < IDLE_NANOS) {
            double value = sampler.sample();
            if (value >= 0) {
               percent = Math.min(100.0, value);
            }

            Thread.sleep(SAMPLE_MILLIS);
         }
      } catch (InterruptedException stop) {
         Thread.currentThread().interrupt();
      } catch (RuntimeException | LinkageError failure) {
         unavailable = true;
         com.wgaccel.WorldgenAccelerator.LOG.info("WGA Voxy LOD panel: the GPU usage cannot be read here ({})", failure.toString());
      } finally {
         if (sampler != null) {
            sampler.close();
         }

         percent = -1.0;
         running = false;
      }
   }

   private interface Sampler {
      /** The busy share now, or -1 when this sample has none. */
      double sample();

      void close();
   }

   /** pdh.dll's calls (UTF-16 names), declared here: JNA's own Pdh mapping has no PdhGetFormattedCounterArray. */
   private interface Pdh extends Library {
      int PdhOpenQueryW(Pointer dataSource, Pointer userData, PointerByReference query);

      int PdhAddEnglishCounterW(Pointer query, WString path, Pointer userData, PointerByReference counter);

      int PdhCollectQueryData(Pointer query);

      int PdhGetFormattedCounterArrayW(Pointer counter, int format, IntByReference bufferSize, IntByReference itemCount, Pointer items);

      int PdhCloseQuery(Pointer query);
   }

   private static final class WindowsSampler implements Sampler {
      private static final int PDH_FMT_DOUBLE = 0x00000200;
      private static final int PDH_FMT_NOCAP100 = 0x00008000;
      private static final int PDH_MORE_DATA = 0x800007D2;
      /** PDH_FMT_COUNTERVALUE_ITEM_W on 64 bits: the name's pointer, then CStatus (4 bytes, 4 of padding), then the double. */
      private static final int ITEM = 24;
      private final Pdh pdh;
      private final Pointer query;
      private final Pointer counter;
      private Memory buffer = new Memory(64 * 1024);

      WindowsSampler() {
         if (Native.POINTER_SIZE != 8) {
            throw new IllegalStateException("a 32-bit Java");
         }

         this.pdh = Native.load("pdh", Pdh.class);
         PointerByReference query = new PointerByReference();
         check(this.pdh.PdhOpenQueryW(null, null, query), "PdhOpenQuery");
         this.query = query.getValue();
         PointerByReference counter = new PointerByReference();
         int status = this.pdh.PdhAddEnglishCounterW(this.query, new WString("\\GPU Engine(*)\\Utilization Percentage"), null, counter);
         if (status != 0) {
            this.pdh.PdhCloseQuery(this.query);
            check(status, "PdhAddEnglishCounter (no GPU Engine counters: Windows 10 1709 or later)");
         }

         this.counter = counter.getValue();
         // A rate counter: the first collection only sets its start.
         this.pdh.PdhCollectQueryData(this.query);
      }

      @Override
      public double sample() {
         if (this.pdh.PdhCollectQueryData(this.query) != 0) {
            return -1.0;
         }

         IntByReference size = new IntByReference((int)Math.min(Integer.MAX_VALUE, this.buffer.size()));
         IntByReference count = new IntByReference();
         int status = this.pdh.PdhGetFormattedCounterArrayW(this.counter, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100, size, count, this.buffer);
         if (status == PDH_MORE_DATA) {
            this.buffer = new Memory(Math.max(size.getValue(), (int)this.buffer.size() * 2));
            size.setValue((int)this.buffer.size());
            status = this.pdh.PdhGetFormattedCounterArrayW(this.counter, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100, size, count, this.buffer);
         }

         if (status != 0) {
            return -1.0;
         }

         // Instances: pid_<pid>_luid_<hi>_<lo>_phys_<n>_eng_<n>_engtype_<type>; summed per engine (luid ... eng_<n>).
         Map<String, Double> engines = new HashMap<>();
         for (int i = 0; i < count.getValue(); i++) {
            long at = (long)i * ITEM;
            if (this.buffer.getInt(at + 8) != 0) {
               continue;
            }

            Pointer name = this.buffer.getPointer(at);
            if (name == null) {
               continue;
            }

            String instance = name.getWideString(0);
            int luid = instance.indexOf("luid_");
            int type = instance.indexOf("_engtype_");
            if (luid < 0 || type < luid) {
               continue;
            }

            engines.merge(instance.substring(luid, type), this.buffer.getDouble(at + 16), Double::sum);
         }

         double busiest = 0.0;
         for (double value : engines.values()) {
            busiest = Math.max(busiest, value);
         }

         return busiest;
      }

      @Override
      public void close() {
         this.pdh.PdhCloseQuery(this.query);
      }

      private static void check(int status, String call) {
         if (status != 0) {
            throw new IllegalStateException(call + " failed: 0x" + Integer.toHexString(status));
         }
      }
   }

   private static final class LinuxSampler implements Sampler {
      @Override
      public double sample() {
         double busiest = -1.0;
         try (var cards = Files.newDirectoryStream(Path.of("/sys/class/drm"), "card*")) {
            for (Path card : cards) {
               Path busy = card.resolve("device/gpu_busy_percent");
               if (Files.isReadable(busy)) {
                  busiest = Math.max(busiest, Double.parseDouble(Files.readString(busy).trim()));
               }
            }
         } catch (java.io.IOException | RuntimeException unreadable) {
            return -1.0;
         }

         if (busiest < 0) {
            // No card says (NVIDIA, Intel): not known.
            unavailable = true;
         }

         return busiest;
      }

      @Override
      public void close() {
      }
   }
}
