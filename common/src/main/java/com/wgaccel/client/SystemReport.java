package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.gpu.GpuDevice;
import com.wgaccel.gpu.GpuInventory;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings screen's System info: the PC (CPU, memory, OS, GPUs and their drivers) and the software the mod runs with
 * (Java, Minecraft, the mod loader, the worldgen mods, the GPU compilers), as rows of a label and a value; a row with a null
 * value is a heading. The hardware is asked once, on a thread of its own (OSHI can take a second).
 */
final class SystemReport {
   private static volatile List<String[]> hardware;
   private static volatile boolean asked;

   private SystemReport() {
   }

   static List<String[]> rows() {
      List<String[]> rows = new ArrayList<>();
      rows.add(new String[] {"Hardware", null});
      List<String[]> known = hardware();
      if (known == null) {
         rows.add(new String[] {"CPU", "looking..."});
      } else {
         rows.addAll(known);
      }

      Runtime runtime = Runtime.getRuntime();
      rows.add(new String[] {"Java heap", String.format(Locale.ROOT, "%.1f GB max, %d threads for Java", runtime.maxMemory() / 1.073741824e9,
         runtime.availableProcessors())});
      GpuInventory.Inventory inventory = GpuInventory.get();
      if (inventory == null) {
         rows.add(new String[] {"GPUs", "looking..."});
      } else {
         for (GpuInventory.Adapter adapter : inventory.adapters()) {
            StringBuilder text = new StringBuilder(adapter.name());
            long memory = adapter.memory() > 0 ? adapter.memory() : adapter.device() == null ? 0 : adapter.device().memory();
            if (memory > 0) {
               text.append(String.format(Locale.ROOT, ", %.1f GB", memory / 1.073741824e9));
            }

            if (!adapter.driver().isEmpty()) {
               text.append(", driver ").append(adapter.driver());
            }

            text.append(adapter.devices().isEmpty() ? "; the mod cannot use it"
               : "; through " + String.join(", ", adapter.devices().stream().map(GpuInventory.Device::backend).toList()));
            rows.add(new String[] {"GPU", text.toString()});
         }
      }

      rows.add(new String[] {"Software", null});
      rows.add(new String[] {"OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"});
      rows.add(new String[] {"Java", System.getProperty("java.version") + ", " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vendor") + ")"});
      rows.add(new String[] {"Minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName()});
      rows.add(new String[] {"Mod Loader", Platform.get().describeLoader()});
      rows.add(new String[] {"Worldgen Accelerator", or(version(com.wgaccel.WorldgenAccelerator.MOD_ID), "?")});
      rows.add(new String[] {"FreeTerraForged", or(version("freeterraforged"), "not installed")});
      rows.add(new String[] {"C2ME", or(version("c2me"), "not installed")});
      rows.add(new String[] {"Chunky", or(version("chunky"), "not installed")});
      rows.add(new String[] {"Distant Horizons", or(version("distanthorizons"), "not installed")});
      rows.add(new String[] {"Settings preset", Config.PRESET.get() == Config.preset() ? Config.preset().name() : Config.PRESET.get().name() + " (" + Config.preset().name() + ")"});
      rows.add(new String[] {"GPU stack", null});
      String name = GpuDevice.chosenName;
      rows.add(new String[] {"In use", name == null ? "not started yet (it starts with the first world)" : name + " through " + com.wgaccel.gpu.GpuBackend.name()});
      if (!com.wgaccel.gpu.Lwjgl.PRESENT) {
         rows.add(new String[] {"LWJGL", "not loaded"});
      } else {
         rows.add(new String[] {"NVIDIA NVRTC", state(() -> com.wgaccel.gpu.GpuSelfTest.nvrtcState)});
         rows.add(new String[] {"AMD hipRTC", state(() -> com.wgaccel.gpu.Hip.hiprtcState)});
      }

      rows.add(new String[] {"CUDA_PATH", or(System.getenv("CUDA_PATH"), "not set")});
      rows.add(new String[] {"HIP_PATH", or(System.getenv("HIP_PATH"), "not set")});
      if (inventory != null && !inventory.problems().isEmpty()) {
         rows.add(new String[] {"Listing problems", String.join("; ", inventory.problems())});
      }

      return rows;
   }

   /** The rows as plain text, for the clipboard (a bug report). */
   static String text() {
      StringBuilder text = new StringBuilder();
      for (String[] row : rows()) {
         text.append(row[1] == null ? (text.isEmpty() ? "" : "\n") + "== " + row[0] + " ==" : row[0] + ": " + row[1]).append('\n');
      }

      return text.toString();
   }

   private static List<String[]> hardware() {
      if (!asked) {
         asked = true;
         Thread thread = new Thread(() -> {
            List<String[]> rows = new ArrayList<>();
            try {
               oshi.hardware.HardwareAbstractionLayer layer = new oshi.SystemInfo().getHardware();
               oshi.hardware.CentralProcessor processor = layer.getProcessor();
               rows.add(new String[] {"CPU", LoadingPanel.tidy(processor.getProcessorIdentifier().getName()) + String.format(Locale.ROOT,
                  ", %d cores, %d threads", processor.getPhysicalProcessorCount(), processor.getLogicalProcessorCount())});
               rows.add(new String[] {"Memory", String.format(Locale.ROOT, "%.1f GB", layer.getMemory().getTotal() / 1.073741824e9)});
            } catch (Throwable failure) {
               // No OSHI (or no answer): what Java knows.
               rows.add(new String[] {"CPU", System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " threads"});
            }

            hardware = List.copyOf(rows);
         }, "WGA system info");
         thread.setDaemon(true);
         thread.start();
      }

      return hardware;
   }

   private static String state(java.util.function.Supplier<String> state) {
      try {
         String text = state.get();
         return text.equals("not looked for yet") ? "looked for when the GPU starts with this API" : text;
      } catch (Throwable failure) {
         return "unknown (" + Names.simpleName(failure.getClass()) + ")";
      }
   }

   private static String version(String mod) {
      return Platform.get().modVersion(mod);
   }

   private static String or(String value, String otherwise) {
      return value == null || value.isBlank() ? otherwise : value;
   }
}
