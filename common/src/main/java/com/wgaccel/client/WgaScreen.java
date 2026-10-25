package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.Setting;
import com.wgaccel.gpu.GpuDevice;
import com.wgaccel.gpu.GpuInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The mod's settings screen (Options' WGA button, or the mods list's Config), in tabs:
 * <ul>
 * <li>Config: the GPU, Automatic or a manual choice of the API (CUDA, HIP, OpenCL) and the GPU, saved as config
 * gpuBackend and gpuDevice. The GPU is chosen once per game, so a new choice applies after a restart.</li>
 * <li>GPU improvements and CPU improvements: each improvement with a switch and what it does, its section's switch on
 * top. A switch is saved in config overrides (it wins over the preset); Defaults takes the tab's out.</li>
 * <li>System info: the hardware and the software the mod runs with, which Copy puts on the clipboard.</li>
 * <li>Benchmark: performance tests, to come; its button is greyed for now.</li>
 * </ul>
 * A tab's content scrolls between the tabs and the bottom buttons.
 */
public final class WgaScreen extends Screen {
   private static final int WHITE = 0xFFFFFFFF;
   private static final int GREY = 0xFFA0A0A0;
   private static final int DARK = 0xFF707070;
   private static final int GREEN = 0xFF55FF55;
   private static final int YELLOW = 0xFFFFFF55;
   private static final int ROW = 26;
   /** Where the tabs' content starts and how far above the bottom buttons it ends. */
   private static final int TOP = 48;
   private static final int BOTTOM_GAP = 34;
   private static final int INDENT = 14;

   enum Tab {
      CONFIG("Config"),
      GPU("GPU improvements"),
      CPU("CPU improvements"),
      SYSTEM("System info"),
      /** Performance tests run from the screen: shown, not open yet. */
      BENCHMARK("Benchmark", false);

      final String title;
      /** Whether the tab can be opened (false: its button stays greyed, with a tooltip saying it comes later). */
      final boolean ready;

      Tab(String title) {
         this(title, true);
      }

      Tab(String title, boolean ready) {
         this.title = title;
         this.ready = ready;
      }
   }

   /** The tab last open, kept while the game runs. */
   private static Tab lastTab = Tab.CONFIG;
   private final Screen parent;
   private Tab tab = lastTab;
   private int scroll;
   private int contentHeight;
   /** The content's lines as laid out, at their y within the content (before scrolling). */
   private final List<Text> texts = new ArrayList<>();
   /** Whether the widgets were made with the inventory (it is listed in the background at first). */
   private boolean listed;
   private int ticks;

   private record Text(int x, int y, FormattedCharSequence line, int color) {
   }

   public WgaScreen(Screen parent) {
      super(Component.literal("Worldgen Accelerator"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      this.texts.clear();
      this.listed = GpuInventory.get() != null;
      int tabs = Tab.values().length;
      int tabWidth = Math.min(120, (this.width - 16 - 4 * (tabs - 1)) / tabs);
      int tabLeft = this.width / 2 - (tabWidth * tabs + 4 * (tabs - 1)) / 2;
      for (Tab each : Tab.values()) {
         Button button = Button.builder(Component.literal(each.title), pressed -> this.open(each))
            .bounds(tabLeft + each.ordinal() * (tabWidth + 4), 22, tabWidth, 20).build();
         button.active = each.ready && each != this.tab;
         if (!each.ready) {
            button.setTooltip(Tooltip.create(Component.literal("Coming in a later version: performance tests run from here.")));
         }

         this.addRenderableWidget(button);
      }

      List<Placed> widgets = new ArrayList<>();
      this.contentHeight = switch (this.tab) {
         case CONFIG -> this.config(widgets);
         case GPU -> this.improvements(widgets, Config.GPU_IMPROVEMENTS, "All GPU improvements",
            "Off: the GPU is never used; the CPU improvements still apply.", Improvements.GPU);
         case CPU -> this.improvements(widgets, Config.CPU_IMPROVEMENTS, "All CPU improvements",
            "Off: none of the improvements below; the GPU ones still apply.", Improvements.CPU);
         case SYSTEM -> this.system();
         case BENCHMARK -> this.text(Component.literal("Coming in a later version."), this.left(), 0, this.width - this.left() - 8, GREY);
      };
      this.scroll = Math.max(0, Math.min(this.scroll, this.contentHeight - this.visible()));
      for (Placed placed : widgets) {
         int y = TOP + placed.y() - this.scroll;
         if (y >= TOP && y + placed.widget().getHeight() <= this.bottom()) {
            placed.widget().setY(y);
            this.addRenderableWidget(placed.widget());
         }
      }

      int buttons = this.height - 28;
      if (this.tab == Tab.GPU || this.tab == Tab.CPU) {
         List<Improvements.Improvement> list = this.tab == Tab.GPU ? Improvements.GPU : Improvements.CPU;
         Setting<Boolean> master = this.tab == Tab.GPU ? Config.GPU_IMPROVEMENTS : Config.CPU_IMPROVEMENTS;
         Button defaults = Button.builder(Component.literal("Defaults"), pressed -> {
            for (Improvements.Improvement improvement : list) {
               improvement.setting().unchoose();
            }

            master.set(true);
            this.rebuildWidgets();
         }).bounds(this.width / 2 - 154, buttons, 150, 20).build();
         defaults.setTooltip(Tooltip.create(Component.literal("Every improvement of this tab back to the preset's value (Config: preset).")));
         this.addRenderableWidget(defaults);
         this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> this.onClose()).bounds(this.width / 2 + 4, buttons, 150, 20).build());
      } else if (this.tab == Tab.SYSTEM) {
         Button copy = Button.builder(Component.literal("Copy"), pressed -> this.minecraft.keyboardHandler.setClipboard(SystemReport.text()))
            .bounds(this.width / 2 - 154, buttons, 150, 20).build();
         copy.setTooltip(Tooltip.create(Component.literal("Puts this information on the clipboard, for a bug report.")));
         this.addRenderableWidget(copy);
         this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> this.onClose()).bounds(this.width / 2 + 4, buttons, 150, 20).build());
      } else {
         this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> this.onClose()).bounds(this.width / 2 - 100, buttons, 200, 20).build());
      }
   }

   private record Placed(AbstractWidget widget, int y) {
   }

   private void open(Tab tab) {
      this.tab = tab;
      lastTab = tab;
      this.scroll = 0;
      this.rebuildWidgets();
   }

   // The Config tab.

   private int config(List<Placed> widgets) {
      int left = this.left();
      int x = this.textLeft();
      int y = 0;
      y += this.text(Component.literal(inUse()), left, y, this.width - left - 8, WHITE);
      GpuInventory.Inventory inventory = GpuInventory.get();
      String pending = pending(inventory);
      String note = pending != null ? pending
         : GpuDevice.chosenName == null ? "A choice is used from the first world you open (no restart needed yet)."
         : "The GPU is chosen once per game: a new choice applies after restarting the game.";
      y += this.text(Component.literal(note), left, y, this.width - left - 8, pending != null ? YELLOW : GREY) + 8;
      if (inventory == null) {
         y += this.text(Component.literal("Looking for GPUs..."), left, y, this.width - left - 8, GREY);
         return this.debugInfoLevel(widgets, y + 12);
      }

      boolean automatic = automaticChosen();
      GpuInventory.Device first = firstUsable(inventory);
      Button auto = this.pick("Automatic", automatic, true, () -> this.choose(Config.GpuBackendChoice.AUTO, -1), left, 100);
      auto.setTooltip(Tooltip.create(Component.literal("Let the mod choose: OpenCL, through the GPU driver's own compiler, on a separate card first, then the"
         + " one with the most compute units (Intel integrated graphics only when chosen by hand). CUDA and HIP are used only when chosen under Manual.")));
      Button manual = this.pick("Manual", !automatic, first != null, () -> this.chooseManually(inventory, first), left + 104, 100);
      manual.setTooltip(Tooltip.create(Component.literal(first == null ? "No GPU on this PC can run the mod's GPU parts." : "Choose the API and the GPU below.")));
      widgets.add(new Placed(auto, y));
      widgets.add(new Placed(manual, y));
      y += 24;
      y += this.text(Component.literal(automatic ? "Automatic: " + automaticText(inventory) : "Manual: the API and the GPU chosen below."), left, y,
         this.width - left - 8, automatic ? GREEN : GREY) + 10;

      // The manual choice: grey and locked while Automatic is chosen.
      String api = automatic ? null : chosenBackend();
      y += this.text(Component.literal("API (the compiler the GPU code goes through)"), left, y, this.width - left - 8, automatic ? DARK : WHITE) + 2;
      int apiX = left;
      for (String backend : List.of("CUDA", "HIP", "OpenCL")) {
         GpuInventory.Device best = best(inventory, backend);
         Button button = this.pick(backend, backend.equalsIgnoreCase(api), !automatic && best != null, () -> this.choose(choice(backend), best.index()), apiX, 76);
         button.setTooltip(Tooltip.create(Component.literal(apiText(backend) + (best == null ? " No GPU of this PC through it." : ""))));
         widgets.add(new Placed(button, y));
         apiX += 80;
      }

      y += 28;
      y += this.text(Component.literal("GPU"), left, y, this.width - left - 8, automatic ? DARK : WHITE) + 2;
      for (GpuInventory.Adapter adapter : inventory.adapters()) {
         GpuInventory.Device device = api == null ? adapter.device() : adapter.devices().stream().filter(each -> each.backend().equalsIgnoreCase(api)).findFirst()
            .orElse(null);
         boolean selected = !automatic && device != null && chosen(device);
         Button button = this.pick(selected ? "Selected" : device == null ? "-" : "Use", selected, !automatic && device != null,
            () -> this.choose(choice(device.backend()), device.index()), left, 60);
         button.setTooltip(Tooltip.create(Component.literal(device != null ? "Use this GPU (after a restart of the game)."
            : adapter.devices().isEmpty() ? unusable(adapter) : "Not through " + api + ": choose " + adapter.device().backend() + " above.")));
         widgets.add(new Placed(button, y));
         int color = automatic ? DARK : selected ? GREEN : device == null ? GREY : WHITE;
         this.texts.add(new Text(x, y + 1, Component.literal(this.fit(adapter.name() + (inUse(device) ? "  (in use)" : ""))).getVisualOrderText(), color));
         y += Math.max(ROW, 11 + this.text(Component.literal(details(adapter, device)), x, y + 11, this.room(), automatic ? DARK : device == null ? YELLOW : GREY) + 6);
      }

      if (!inventory.problems().isEmpty()) {
         y += 4 + this.text(Component.literal("Listing problems: " + String.join("; ", inventory.problems())), left, y + 4, this.width - left - 8, YELLOW);
      }

      return this.debugInfoLevel(widgets, y + 12);
   }

   /** The Config tab's last part: how much the mod writes to the log (applies at once). */
   private int debugInfoLevel(List<Placed> widgets, int y) {
      int left = this.left();
      y += this.text(Component.literal("Debug info level"), left, y, this.width - left - 8, WHITE) + 2;
      Config.DebugInfoLevel current = Config.DEBUG_INFO_LEVEL.get();
      int buttonX = left;
      for (Config.DebugInfoLevel level : Config.DebugInfoLevel.values()) {
         String label = level.name().charAt(0) + level.name().substring(1).toLowerCase(Locale.ROOT);
         Button button = this.pick(label, level == current, true, () -> {
            Config.DEBUG_INFO_LEVEL.set(level);
            this.rebuildWidgets();
         }, buttonX, 76);
         button.setTooltip(Tooltip.create(Component.literal(switch (level) {
            case VERBOSE -> "Every line the mod logs: for a bug report.";
            case NORMAL -> "Each kind of line the first time and every 10th time after: about ten times fewer lines.";
            case SILENT -> "Warnings and errors only.";
         })));
         widgets.add(new Placed(button, y));
         buttonX += 80;
      }

      y += 24;
      return y + this.text(Component.literal("How much the mod writes to the game's log. Applies at once."), left, y, this.width - left - 8, GREY);
   }

   /**
    * A choice's button: the chosen one stays bright with its label in green (pressing it again does nothing), one that
    * can be chosen is a normal button, and one that cannot is greyed out.
    */
   private Button pick(String label, boolean selected, boolean available, Runnable choose, int x, int width) {
      Component text = selected ? Component.literal(label).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD) : Component.literal(label);
      Button button = Button.builder(text, pressed -> {
         if (!selected) {
            choose.run();
         }
      }).bounds(x, 0, width, 20).build();
      button.active = selected || available;
      return button;
   }

   private void choose(Config.GpuBackendChoice backend, int device) {
      Config.GPU_BACKEND.set(backend);
      Config.GPU_DEVICE.set(device);
      this.rebuildWidgets();
   }

   /** Manual from Automatic: starts at the GPU Automatic takes (or the first usable one), its API written out. */
   private void chooseManually(GpuInventory.Inventory inventory, GpuInventory.Device first) {
      GpuInventory.Device device = inventory.automatic() != null ? inventory.automatic() : first;
      if (device != null) {
         this.choose(choice(device.backend()), device.index());
      }
   }

   private static Config.GpuBackendChoice choice(String backend) {
      return Config.GpuBackendChoice.valueOf(backend.toUpperCase(Locale.ROOT));
   }

   private static GpuInventory.Device firstUsable(GpuInventory.Inventory inventory) {
      return inventory.adapters().stream().map(GpuInventory.Adapter::device).filter(java.util.Objects::nonNull).findFirst().orElse(null);
   }

   /** The device the mod would take through this API: the most compute units, a separate card first; null when none. */
   private static GpuInventory.Device best(GpuInventory.Inventory inventory, String backend) {
      GpuInventory.Device best = null;
      long bestScore = -1L;
      for (GpuInventory.Adapter adapter : inventory.adapters()) {
         for (GpuInventory.Device device : adapter.devices()) {
            long score = (device.integrated() ? 0L : 1L << 32) + device.units();
            if (device.backend().equalsIgnoreCase(backend) && score > bestScore) {
               bestScore = score;
               best = device;
            }
         }
      }

      return best;
   }

   private static String apiText(String backend) {
      return switch (backend) {
         case "CUDA" -> "NVIDIA's CUDA, with NVRTC from the CUDA Toolkit: NVIDIA cards (GTX 10xx and newer).";
         case "HIP" -> "AMD's HIP, with hipRTC from the AMD HIP SDK 6.4 or newer: AMD cards on Windows.";
         default -> "OpenCL with shared virtual memory, through the driver's own compiler (no SDK or toolkit to install): Intel graphics, AMD and NVIDIA cards.";
      };
   }

   private static boolean automaticChosen() {
      return Config.GPU_BACKEND.written() == Config.GpuBackendChoice.AUTO && Config.GPU_DEVICE.written() < 0;
   }

   /** The API the manual choice names (with AUTO written, the one Automatic takes). */
   private static String chosenBackend() {
      Config.GpuBackendChoice backend = Config.GPU_BACKEND.written();
      String name = backend == Config.GpuBackendChoice.AUTO ? automaticBackend() : backend.name();
      return name.equals("OPENCL") ? "OpenCL" : name;
   }

   /** Whether the config names this device (with AUTO as the backend, the one AUTO would take for it). */
   private static boolean chosen(GpuInventory.Device device) {
      return device.backend().equalsIgnoreCase(chosenBackend()) && device.index() == Config.GPU_DEVICE.written();
   }

   private static String automaticBackend() {
      GpuInventory.Inventory inventory = GpuInventory.get();
      return inventory == null || inventory.automatic() == null ? "" : inventory.automatic().backend();
   }

   private static String unusable(GpuInventory.Adapter adapter) {
      String vendor = adapter.vendor() == null ? "" : adapter.vendor().toLowerCase(Locale.ROOT);
      if (vendor.contains("intel")) {
         return "OpenCL does not offer this GPU with what the mod needs (double precision, shared virtual memory, OpenCL 2.0 or 3.0): update Intel's graphics driver.";
      }

      return "Neither NVIDIA's CUDA, AMD's HIP nor OpenCL sees this GPU with what the mod needs (driver missing, or AMD's HIP SDK not installed).";
   }

   private static String automaticText(GpuInventory.Inventory inventory) {
      GpuInventory.Device automatic = inventory.automatic();
      if (automatic == null && inventory.adapters().stream().anyMatch(adapter -> adapter.device() != null && adapter.device().integrated())) {
         return "the CPU (Intel integrated graphics are used only when chosen by hand: slower than the CPU on the one tested)";
      }

      return automatic == null ? "no usable GPU found, the CPU improvements only" : automatic.name() + " through " + automatic.backend();
   }

   /** The adapter's vendor and memory, and this device's API, number and units (or why the mod cannot use the adapter). */
   private static String details(GpuInventory.Adapter adapter, GpuInventory.Device device) {
      StringBuilder text = new StringBuilder(adapter.vendor() == null ? "" : adapter.vendor());
      long memory = adapter.memory() > 0 ? adapter.memory() : device == null ? 0 : device.memory();
      if (memory > 0) {
         text.append(String.format(Locale.ROOT, ", %.1f GB", memory / 1.073741824e9));
      }

      if (device != null) {
         text.append(String.format(Locale.ROOT, " - %s device %d, %d compute units%s", device.backend(), device.index(), device.units(), device.integrated() ? ", integrated" : ""));
      } else if (adapter.devices().isEmpty()) {
         text.append(" - ").append(unusable(adapter));
      } else {
         text.append(" - through ").append(String.join(", ", adapter.devices().stream().map(GpuInventory.Device::backend).toList())).append(" only");
      }

      return text.toString();
   }

   /**
    * The warning when the saved choice is another GPU than the one this game already runs on (it is chosen once per
    * game), else null. Before the GPU has started (the main menu, no world opened yet) the choice still applies.
    */
   private static String pending(GpuInventory.Inventory inventory) {
      if (inventory == null || GpuDevice.chosenName == null) {
         return null;
      }

      GpuInventory.Device wanted = null;
      if (automaticChosen()) {
         wanted = inventory.automatic();
      } else {
         for (GpuInventory.Adapter adapter : inventory.adapters()) {
            for (GpuInventory.Device device : adapter.devices()) {
               if (chosen(device)) {
                  wanted = device;
               }
            }
         }
      }

      if (wanted == null || inUse(wanted)) {
         return null;
      }

      return "Restart the game to switch to " + wanted.name() + " through " + wanted.backend() + ": this game keeps using " + GpuDevice.chosenName + ".";
   }

   /** The GPU the mod uses in this game, if it has started one. */
   private static String inUse() {
      String name = GpuDevice.chosenName;
      if (name == null) {
         return Config.GPU_IMPROVEMENTS.get() ? "GPU not started yet: it starts with the first world." : "The GPU improvements are off (GPU improvements tab).";
      }

      return "In use in this game: " + name + " (" + com.wgaccel.gpu.GpuBackend.name() + ", " + GpuDevice.reason.split(";")[0] + ")";
   }

   private static boolean inUse(GpuInventory.Device device) {
      return device != null && GpuDevice.chosenName != null && device.name().equals(GpuDevice.chosenName)
         && device.backend().equalsIgnoreCase(com.wgaccel.gpu.GpuBackend.name()) && device.index() == GpuDevice.index();
   }

   // The improvements tabs.

   private int improvements(List<Placed> widgets, Setting<Boolean> master, String masterName, String masterText, List<Improvements.Improvement> list) {
      int left = this.left();
      int y = 0;
      y += this.text(Component.literal("Each one gives the same world block for block, checks itself now and then and turns itself off if a check finds a"
         + " difference. A change applies from the next world you open."), left, y, this.width - left - 8, GREY) + 8;
      if (master == Config.GPU_IMPROVEMENTS && GpuInventory.get() != null && firstUsable(GpuInventory.get()) == null) {
         y += this.text(Component.literal("No GPU of this PC can run them (see the Config tab): they do nothing here."), left, y, this.width - left - 8, YELLOW) + 8;
      }

      boolean on = master.get();
      Button all = this.toggle(on ? "ON" : "OFF", on, pressed -> {
         master.set(!on);
         this.rebuildWidgets();
      });
      all.setTooltip(Tooltip.create(Component.literal("Config " + master.key())));
      widgets.add(new Placed(all, y));
      y += this.row(masterName, masterText, this.textLeft(), y, WHITE, GREY);
      y += 6;
      for (Improvements.Improvement improvement : list) {
         Setting<?> setting = improvement.setting();
         Object value = setting.chosen();
         boolean needed = improvement.needs() == null || Improvements.on(improvement.needs().chosen());
         int indent = improvement.needs() == null ? 0 : INDENT;
         Button toggle = this.toggle(Improvements.label(value), Improvements.on(value), pressed -> {
            Improvements.choose(setting, Improvements.flipped(setting));
            this.rebuildWidgets();
         });
         toggle.setX(left + indent);
         toggle.active = on;
         toggle.setTooltip(Tooltip.create(Component.literal("Config " + setting.key() + (setting.overriddenNow() ? ", switched here (config overrides)" : ""))));
         widgets.add(new Placed(toggle, y));
         String name = improvement.name() + (setting.overriddenNow() ? "  (changed)" : "")
            + (needed ? "" : "  - needs " + list.stream().filter(each -> each.setting() == improvement.needs()).map(Improvements.Improvement::name).findFirst().orElse("?"));
         boolean bright = on && needed;
         y += this.row(name, improvement.description(), this.textLeft() + indent, y, bright ? (setting.overriddenNow() ? YELLOW : WHITE) : DARK, bright ? GREY : DARK);
      }

      return y;
   }

   private Button toggle(String label, boolean on, Button.OnPress press) {
      Component text = Component.literal(label).withStyle(label.equals("CHECK") ? ChatFormatting.YELLOW : on ? ChatFormatting.GREEN : ChatFormatting.RED);
      return Button.builder(text, press).bounds(this.left(), 0, 60, 20).build();
   }

   /** A row beside its button: the name, then the text wrapped under it; returns the row's height. */
   private int row(String name, String text, int x, int y, int nameColor, int textColor) {
      int room = Math.max(80, this.width - x - 8);
      String fitted = this.font.width(name) <= room ? name : this.font.plainSubstrByWidth(name, room - this.font.width("...")) + "...";
      this.texts.add(new Text(x, y + 1, Component.literal(fitted).getVisualOrderText(), nameColor));
      return Math.max(ROW, 11 + this.text(Component.literal(text), x, y + 11, room, textColor) + 6);
   }

   // The System info tab.

   private int system() {
      int left = Math.max(8, this.width / 2 - 200);
      int labels = left + 130;
      int room = Math.max(80, this.width - labels - 8);
      int y = 0;
      for (String[] row : SystemReport.rows()) {
         if (row[1] == null) {
            y += (y == 0 ? 0 : 8) + this.text(Component.literal(row[0]).withStyle(ChatFormatting.BOLD), left, y + (y == 0 ? 0 : 8), this.width - left - 8, WHITE) + 2;
         } else {
            this.texts.add(new Text(left, y, Component.literal(row[0]).getVisualOrderText(), GREY));
            y += this.text(Component.literal(row[1]), labels, y, room, WHITE) + 2;
         }
      }

      return y;
   }

   // Laying out and drawing.

   /** Adds the text wrapped to this width at y in the content; returns its height. */
   private int text(Component text, int x, int y, int width, int color) {
      List<FormattedCharSequence> lines = this.font.split(text, Math.max(40, width));
      for (int i = 0; i < lines.size(); i++) {
         this.texts.add(new Text(x, y + 10 * i, lines.get(i), color));
      }

      return 10 * lines.size();
   }

   private int left() {
      return Math.max(8, this.width / 2 - 190);
   }

   private int textLeft() {
      return this.left() + 68;
   }

   /** The width left for a row's text, from its left to the screen's right edge less a margin. */
   private int room() {
      return Math.max(80, this.width - this.textLeft() - 8);
   }

   /** A one-line text cut to the room left, ending in "..." when cut. */
   private String fit(String text) {
      int room = this.room();
      if (this.font.width(text) <= room) {
         return text;
      }

      return this.font.plainSubstrByWidth(text, room - this.font.width("...")) + "...";
   }

   private int bottom() {
      return this.height - BOTTOM_GAP;
   }

   private int visible() {
      return Math.max(0, this.bottom() - TOP);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int most = Math.max(0, this.contentHeight - this.visible());
      int next = Math.max(0, Math.min(most, this.scroll - (int)Math.round(scrollY * 20)));
      if (next != this.scroll) {
         this.scroll = next;
         this.rebuildWidgets();
         return true;
      }

      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public void tick() {
      this.ticks++;
      if (!this.listed && GpuInventory.get() != null) {
         this.rebuildWidgets();
      } else if (this.tab == Tab.SYSTEM && this.ticks % 20 == 0) {
         // Once a second: the hardware is asked in the background, and the GPU in use may start meanwhile.
         this.rebuildWidgets();
      }
   }

   /** The usual background, darkened further so the text reads well over the world or the panorama. */
   @Override
   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.renderBackground(graphics, mouseX, mouseY, partialTick);
      graphics.fill(0, 0, this.width, this.height, 0xB0000000);
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, WHITE);
      graphics.enableScissor(0, TOP, this.width, this.bottom());
      for (Text text : this.texts) {
         int y = TOP + text.y() - this.scroll;
         if (y + 10 > TOP && y < this.bottom()) {
            graphics.drawString(this.font, text.line(), text.x(), y, text.color());
         }
      }

      graphics.disableScissor();
      int visible = this.visible();
      if (this.contentHeight > visible && visible > 0) {
         // A scroll bar at the right edge.
         int bar = Math.max(16, visible * visible / this.contentHeight);
         int at = TOP + (visible - bar) * this.scroll / Math.max(1, this.contentHeight - visible);
         graphics.fill(this.width - 6, TOP, this.width - 2, this.bottom(), 0x40FFFFFF);
         graphics.fill(this.width - 6, at, this.width - 2, at + bar, 0xC0FFFFFF);
      }
   }

   @Override
   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }
}
