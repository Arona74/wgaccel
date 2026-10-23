package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.gpu.GpuBackend;
import com.wgaccel.gpu.GpuDevice;
import com.wgaccel.gpu.GpuPrepare;
import com.wgaccel.gpu.GpuSelfTest;
import com.wgaccel.gpu.HipSelfTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/**
 * The world-loading screen's Worldgen Accelerator panels, for the whole screen (0.17.2): at the top the GPU programs'
 * bar, on the left the hardware and the mods that set the speed, on the right the GPU programs as a checklist (done,
 * still being made with its seconds, or off). Until 0.17.1 one centred line joined every program and ran off narrow
 * screens, and only while a program was still being made (from the disk cache: a flash of 0.4 s). Vanilla's chunk map
 * and percentage keep the middle.
 */
public final class LoadingPanel {
   private static final int WHITE = 0xFFFFFF;
   private static final int GREY = 0xA0A0A0;
   private static final int HEADER = 0xFFD75F;
   private static final int DONE = 0x55FF55;
   private static final int RUNNING = 0xFFFF55;
   private static final int FAILED = 0xFF5555;
   private static final int MARGIN = 8;
   private static final int LINE = 11;
   private static final int ROW_GAP = 3;
   /** Half the middle kept clear for vanilla's chunk map and percentage. */
   private static final int MIDDLE = 64;
   /** The CPU's name, read once in the background (OSHI can take a moment); null until then. */
   private static volatile String cpu;
   private static volatile boolean cpuAsked;

   private LoadingPanel() {
   }

   public static void render(GuiGraphics graphics, Font font, int width, int height) {
      top(graphics, font, width);
      int y = 44;
      left(graphics, font, width, y);
      right(graphics, font, width, y);
   }

   /** The title and the programs' bar, centred at the top. */
   private static void top(GuiGraphics graphics, Font font, int width) {
      List<GpuPrepare.Line> lines = GpuPrepare.lines();
      int done = 0;
      for (GpuPrepare.Line line : lines) {
         done += line.done() || line.failed() ? 1 : 0;
      }

      double total = GpuPrepare.totalSeconds();
      String title = !gpuOn() ? "Worldgen Accelerator: CPU improvements only"
         : lines.isEmpty() ? "Worldgen Accelerator: waiting for the world"
         : total >= 0.0 ? String.format(Locale.ROOT, "Worldgen Accelerator: GPU ready in %.1f s", total)
         : String.format(Locale.ROOT, "Worldgen Accelerator: preparing the GPU  %d / %d", done, lines.size());
      int centre = width / 2;
      int y = 10;
      graphics.drawCenteredString(font, title, centre, y, WHITE);
      if (!gpuOn()) {
         return;
      }

      int barWidth = Math.min(240, width - 32);
      int left = centre - barWidth / 2;
      int top = y + 14;
      graphics.fill(left - 1, top - 1, left + barWidth + 1, top + 6, 0xFF808080);
      graphics.fill(left, top, left + barWidth, top + 5, 0xFF202020);
      // Programs give no progress while they compile: the finished share, plus a sweep so the bar visibly moves.
      float progress = lines.isEmpty() ? 0.0f : GpuPrepare.progress();
      int filled = (int)(barWidth * progress);
      graphics.fill(left, top, left + filled, top + 5, 0xFF3FAF5F);
      if (GpuPrepare.active()) {
         long sweep = (System.nanoTime() - GpuPrepare.startedNanos()) / 20_000_000L % Math.max(1, barWidth - filled);
         graphics.fill(left + filled + (int)sweep, top, Math.min(left + barWidth, left + filled + (int)sweep + 12), top + 5, 0xFF7FD79F);
      }
   }

   /**
    * The hardware and the mods that set the speed, from the left edge. A value wider than the room left of the middle
    * (vanilla's chunk map and percentage) goes on under itself (the CPU's name at GUI scale 3 on a small screen, 0.17.2).
    */
   private static void left(GuiGraphics graphics, Font font, int width, int y) {
      graphics.drawString(font, "Hardware", MARGIN, y, HEADER);
      y += LINE + 2;
      int room = Math.max(60, width / 2 - MIDDLE - MARGIN - 34);
      boolean first = true;
      for (String[] row : hardware()) {
         // A small gap before each labelled group (CPU, GPU, Java...), none inside one: its rows without a label (the
         // CPU's threads, the GPU's backend) and a wrapped row's lines stay together.
         if (!row[0].isEmpty() && !first) {
            y += ROW_GAP;
         }

         first = false;
         graphics.drawString(font, row[0], MARGIN, y, GREY);
         for (net.minecraft.util.FormattedCharSequence part : font.split(net.minecraft.network.chat.Component.literal(row[1]), room)) {
            graphics.drawString(font, part, MARGIN + 34, y, WHITE);
            y += LINE;
         }
      }
   }

   private static List<String[]> hardware() {
      List<String[]> rows = new ArrayList<>();
      Runtime runtime = Runtime.getRuntime();
      String cpuName = cpu();
      rows.add(new String[] {"CPU", cpuName == null ? "..." : cpuName});
      rows.add(new String[] {"", runtime.availableProcessors() + " threads"});
      String gpuName = GpuDevice.chosenName;
      if (gpuName == null && !"unknown".equals(HipSelfTest.deviceName)) {
         gpuName = HipSelfTest.deviceName;
      }

      if (gpuName == null && !"unknown".equals(GpuSelfTest.deviceName)) {
         gpuName = GpuSelfTest.deviceName;
      }

      if (!gpuOn()) {
         rows.add(new String[] {"GPU", "GPU parts off"});
      } else if (gpuName == null) {
         rows.add(new String[] {"GPU", "..."});
      } else {
         // The backend is chosen before the device, so it is known (and touching it starts nothing) once a name is.
         rows.add(new String[] {"GPU", gpuName});
         String detail = GpuBackend.name();
         if (GpuBackend.HIP && HipSelfTest.architecture != null) {
            detail += ", " + HipSelfTest.architecture;
         } else if (!GpuBackend.HIP && GpuSelfTest.deviceCapability != 0) {
            detail += String.format(Locale.ROOT, ", compute %d.%d", GpuSelfTest.deviceCapability / 10, GpuSelfTest.deviceCapability % 10);
         }

         rows.add(new String[] {"", detail});
      }

      rows.add(new String[] {"Java", System.getProperty("java.version") + String.format(Locale.ROOT, ", heap %.1f GB", runtime.maxMemory() / 1.073741824e9)});
      String c2me = version("c2me");
      if (c2me != null) {
         String workers = com.wgaccel.stats.Environment.c2meParallelism();
         String shown = workers == null ? "" : workers.replace("\"", "");
         rows.add(new String[] {"C2ME", c2me + (shown.isEmpty() ? "" : shown.equalsIgnoreCase("default") ? ", default workers" : ", " + shown + " workers")});
      }

      String ftf = version("freeterraforged");
      if (ftf != null) {
         rows.add(new String[] {"FTF", ftf});
      }

      String own = version(com.wgaccel.WorldgenAccelerator.MOD_ID);
      if (own != null) {
         rows.add(new String[] {"WGA", own});
      }

      return rows;
   }

   /** The GPU programs as a checklist, its longest line ending at the right edge. */
   private static void right(GuiGraphics graphics, Font font, int width, int y) {
      List<String> texts = new ArrayList<>();
      List<Integer> colours = new ArrayList<>();
      if (!gpuOn()) {
         texts.add("GPU parts off");
         colours.add(GREY);
      } else if (GpuPrepare.lines().isEmpty()) {
         texts.add("waiting for the world");
         colours.add(GREY);
      } else {
         for (GpuPrepare.Line line : GpuPrepare.lines()) {
            if (line.failed()) {
               texts.add("✖ " + line.name() + "  off");
               colours.add(FAILED);
            } else {
               texts.add((line.done() ? "✔ " : "… ") + line.name() + String.format(Locale.ROOT, "  %.1f s", line.seconds()));
               colours.add(line.done() ? DONE : RUNNING);
            }
         }
      }

      String header = "GPU programs";
      int columnWidth = font.width(header);
      for (String text : texts) {
         columnWidth = Math.max(columnWidth, font.width(text));
      }

      // The settings that set the speed, under the programs in the same column (0.17.2): the preset, the two sections,
      // Chunky's chunks in flight, and only the settings not at the preset's value.
      List<Component> settings = settings();
      String settingsHeader = "Settings";
      columnWidth = Math.max(columnWidth, font.width(settingsHeader));
      for (Component text : settings) {
         columnWidth = Math.max(columnWidth, font.width(text));
      }

      int x = width - MARGIN - columnWidth;
      graphics.drawString(font, header, x, y, HEADER);
      y += LINE + 2;
      for (int i = 0; i < texts.size(); i++) {
         graphics.drawString(font, texts.get(i), x, y, colours.get(i));
         y += LINE;
      }

      y += LINE;
      graphics.drawString(font, settingsHeader, x, y, HEADER);
      y += LINE + 2;
      for (Component text : settings) {
         graphics.drawString(font, text, x, y, WHITE);
         y += LINE;
      }
   }

   /** At most this many settings away from the preset are listed; the rest as "+N more". */
   private static final int SETTINGS_SHOWN = 6;
   private static volatile List<Component> settingsLines = List.of();
   private static volatile long settingsRead;

   /**
    * Read again once a second (the config can change while the screen is up; reading every field each frame is not
    * needed). Values coloured: on / ON green, off / OFF red, CHECK yellow.
    */
   private static List<Component> settings() {
      long now = System.nanoTime();
      if (now - settingsRead < 1_000_000_000L && !settingsLines.isEmpty()) {
         return settingsLines;
      }

      List<Component> lines = new ArrayList<>();
      Config.Preset chosen = Config.PRESET.get();
      Config.Preset preset = Config.preset();
      lines.add(Component.literal("preset " + (chosen == preset ? chosen.name() : chosen.name() + " → " + preset.name())));
      lines.add(Component.literal("CPU ").append(value(Config.CPU_IMPROVEMENTS.getAsBoolean())).append(", GPU ")
         .append(value(Config.GPU_IMPROVEMENTS.getAsBoolean())));
      // Chunky's maxWorkingCount: how many chunks it asks for at a time, as the mod set it at the world's load (from the
      // heap and the world's height, config chunkyMaxWorkingCount; Chunky's own default is 50).
      String chunky = System.getProperty("chunky.maxWorkingCount");
      if (chunky != null && ModList.get().isLoaded("chunky")) {
         lines.add(Component.literal("Chunky: " + chunky + " at a time"));
      }

      // Every setting the presets give a value, not at it (CUSTOM, or one switched off), its section switch on.
      List<Component> changed = new ArrayList<>();
      for (java.lang.reflect.Field field : Config.class.getDeclaredFields()) {
         if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != com.wgaccel.Setting.class) {
            continue;
         }

         try {
            com.wgaccel.Setting<?> setting = (com.wgaccel.Setting<?>)field.get(null);
            Object normal = setting == null ? null : setting.normal();
            if (normal != null && !setting.sectionOff() && !normal.equals(setting.get())) {
               changed.add(Component.literal("  " + setting.key() + ": ").withStyle(style -> style.withColor(GREY)).append(value(setting.get())));
            }
         } catch (IllegalAccessException | RuntimeException ignored) {
            // A setting not loaded yet (before the config is): left out.
         }
      }

      if (changed.isEmpty()) {
         lines.add(Component.literal("all at the preset's values").withStyle(style -> style.withColor(GREY)));
      } else {
         lines.addAll(changed.subList(0, Math.min(SETTINGS_SHOWN, changed.size())));
         if (changed.size() > SETTINGS_SHOWN) {
            lines.add(Component.literal("  +" + (changed.size() - SETTINGS_SHOWN) + " more").withStyle(style -> style.withColor(GREY)));
         }
      }

      settingsLines = lines;
      settingsRead = now;
      return lines;
   }

   /** A setting's value: on / ON green, off / OFF red, CHECK yellow, anything else white (long ones cut). */
   private static Component value(Object value) {
      String text = value instanceof Boolean on ? (on ? "on" : "off") : String.valueOf(value);
      int colour = text.equalsIgnoreCase("on") ? DONE : text.equalsIgnoreCase("off") ? FAILED : text.equals("CHECK") ? RUNNING : WHITE;
      String cut = text.length() > 24 ? text.substring(0, 23) + "…" : text;
      return Component.literal(cut).withStyle(style -> style.withColor(colour));
   }

   /** Whether the GPU parts are on (config) and LWJGL is there; otherwise only the CPU improvements run. */
   private static boolean gpuOn() {
      return Config.GPU_MODE.get() != Config.GpuMode.OFF && com.wgaccel.gpu.Lwjgl.PRESENT;
   }

   private static String version(String mod) {
      return ModList.get().getModContainerById(mod).map(container -> container.getModInfo().getVersion().toString()).orElse(null);
   }

   /**
    * "AMD Ryzen 7 7800X3D 8-Core Processor" -> "AMD Ryzen 7 7800X3D", "Intel(R) Core(TM) i7-9700K CPU @ 3.60GHz" ->
    * "Intel Core i7-9700K", "AMD Ryzen 9 6900HX with Radeon Graphics" -> "AMD Ryzen 9 6900HX".
    */
   static String tidy(String name) {
      String tidy = name.replaceAll("\\((R|r|TM|tm)\\)|®|™", "")
         .replaceAll("@\\s*[0-9.]+\\s*[GM]Hz", "")
         .replaceAll("\\b(\\d+|Six|Eight|Twelve|Sixteen)-Core\\b", "")
         .replaceAll("\\b(Processor|CPU)\\b", "")
         .replaceAll("\\s+with Radeon.*$", "")
         .replaceAll("\\s+", " ").trim();
      return tidy.isEmpty() ? name.trim() : tidy;
   }

   private static String cpu() {
      if (!cpuAsked) {
         cpuAsked = true;
         Thread thread = new Thread(() -> {
            try {
               cpu = tidy(new oshi.SystemInfo().getHardware().getProcessor().getProcessorIdentifier().getName());
            } catch (Throwable failure) {
               // No OSHI (or no answer): the thread count alone.
               cpu = System.getProperty("os.arch");
            }
         }, "WGA CPU name");
         thread.setDaemon(true);
         thread.start();
      }

      return cpu;
   }
}
