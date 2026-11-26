package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.Setting;
import com.wgaccel.gpu.GpuDevice;
import com.wgaccel.gpu.GpuInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
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
 * gpuBackend and gpuDevice. The GPU is chosen once per game, so a new choice applies after a restart. Then the general
 * settings (preset, Chunky, memory report, the GPU chat line) and the debug info level.</li>
 * <li>GPU improvements and CPU improvements: each improvement with a switch and what it does, its section's switch on
 * top. A switch is saved in config overrides (it wins over the preset); Defaults takes the tab's out.</li>
 * <li>Advanced: the GPU's checks and tuning in groups ({@link Advanced}), each with the control its value takes.</li>
 * <li>System info: the hardware and the software the mod runs with, which Copy puts on the clipboard.</li>
 * <li>Benchmark: performance tests, to come; its button is greyed for now.</li>
 * </ul>
 * A tab's content scrolls between the tabs and the bottom buttons.
 */
public final class WgaScreen extends CanvasScreen {
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
      LODS("Voxy LODs"),
      ADVANCED("Advanced"),
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
         case LODS -> this.lods(widgets);
         case ADVANCED -> this.advanced(widgets);
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
      } else if (this.tab == Tab.ADVANCED || this.tab == Tab.LODS) {
         boolean lods = this.tab == Tab.LODS;
         Button defaults = Button.builder(Component.literal("Defaults"), pressed -> {
            if (lods) {
               for (Setting<?> setting : LOD_SETTINGS) {
                  reset(setting);
               }
            } else {
               for (Advanced.Group group : Advanced.GROUPS) {
                  for (Advanced.Entry entry : group.entries()) {
                     reset(entry.setting());
                  }
               }
            }

            this.rebuildWidgets();
         }).bounds(this.width / 2 - 154, buttons, 150, 20).build();
         defaults.setTooltip(Tooltip.create(Component.literal("Every value of this tab back to its default (the preset's, or the config file's).")));
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
      // GPU off: the choice below is still saved, but nothing uses it (a yellow line, so it is not taken for in use).
      boolean off = Config.GPU_MODE.get() == Config.GpuMode.OFF && GpuDevice.chosenName == null;
      y += this.text(Component.literal(inUse()), left, y, this.width - left - 8, off ? YELLOW : WHITE);
      GpuInventory.Inventory inventory = GpuInventory.get();
      String pending = pending(inventory);
      String note = pending != null ? pending
         : GpuDevice.chosenName == null ? "A choice is used from the first world you open (no restart needed yet)."
         : "The GPU is chosen once per game: a new choice applies after restarting the game.";
      y += this.text(Component.literal(note), left, y, this.width - left - 8, pending != null ? YELLOW : GREY) + 8;
      if (inventory == null) {
         y += this.text(Component.literal("Looking for GPUs..."), left, y, this.width - left - 8, GREY);
         return this.general(widgets, y + 12);
      }

      boolean automatic = automaticChosen();
      GpuInventory.Device first = firstUsable(inventory);
      Button auto = this.pick("Automatic", automatic, true, () -> this.choose(Config.GpuBackendChoice.AUTO, -1), left, 100);
      auto.setTooltip(Tooltip.create(Component.literal("Let the mod choose: OpenCL, through the GPU driver's own compiler, on a separate card first, then the"
         + " one with the most compute units (Intel integrated graphics and GPUs under 8 compute units only when chosen by hand). On Linux with ROCm installed: HIP, on an AMD GPU but RDNA 1."
         + " Otherwise CUDA and HIP are used only when chosen under Manual.")));
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

      return this.general(widgets, y + 12);
   }

   /** The Config tab's general settings (preset, Chunky, memory report, the GPU chat line), then the debug info level. */
   private int general(List<Placed> widgets, int y) {
      int left = this.left();
      y += this.text(Component.literal("General").withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
      y += this.text(Component.literal("Preset: which values the improvements take. AUTO is CLIENT in the game and SERVER on a dedicated server (the"
         + " measured best for each); CUSTOM takes every value as written in the config file. Switches made in these tabs win over it."), left, y,
         this.width - left - 8, GREY) + 2;
      Config.Preset preset = Config.PRESET.get();
      int buttonX = left;
      for (Config.Preset each : Config.Preset.values()) {
         Button button = this.pick(each.name(), each == preset, true, () -> {
            Config.PRESET.set(each);
            Config.PRESET.save();
            Setting.invalidate();
            this.rebuildWidgets();
         }, buttonX, 76);
         button.setTooltip(Tooltip.create(Component.literal("Config preset")));
         widgets.add(new Placed(button, y));
         buttonX += 80;
      }

      y += 26;
      y += this.setting(widgets, Config.CHUNKY_MAX_WORKING_COUNT, "Chunky chunks in flight",
         "How many chunks Chunky keeps in flight while pregenerating. 0: automatic, from the memory and the world's height; -1: Chunky's own setting."
            + " Applies to the next pregeneration.", y);
      y += this.setting(widgets, Config.MEMORY_REPORT, "Memory report",
         "For a bug report about memory: while the memory is above 80% of its maximum, once a minute (8 times at most per world), the log gets"
            + " what still fills it (a pause of about a second each time).", y);
      y += this.setting(widgets, Config.GPU_TESTED_WARNING, "GPU chat line", "A chat line naming the GPU and the API in use when you join a world.", y);
      return this.debugInfoLevel(widgets, y + 8);
   }

   // The Voxy LODs tab.

   /** The tab's settings (its Defaults button resets them). */
   private static final List<Setting<?>> LOD_SETTINGS = List.of(Config.VOXY_GENERATOR, Config.VOXY_RINGS, Config.VOXY_PERFORMANCE, Config.VOXY_THREADS,
      Config.VOXY_PAUSE_ABOVE_MSPT, Config.VOXY_HEIGHT_DITHER, Config.VOXY_STEP_SIDES, Config.VOXY_TREES, Config.VOXY_LITHOS, Config.VOXY_LITHOS_STRATA, Config.VOXY_LITHOS_LAYERED_STONE, Config.VOXY_LITHOS_CLIFF_STONE, Config.VOXY_LITHOS_GROUND_COVER, Config.VOXY_LITHOS_VEGETATION_FILL, Config.VOXY_ARBOREA_FORESTS, Config.VOXY_PLANTS, Config.VOXY_ARONA_LAYERS, Config.VOXY_STRUCTURES, Config.VOXY_TERRAIN_FEATURES, Config.VOXY_TERRAIN_COST_LIMIT, Config.VOXY_WITH_VWG, Config.VOXY_VWG_TASKS, Config.VOXY_VWG_AFTER_RADIUS, Config.VOXY_REAL_CHUNKS, Config.VOXY_REAL_CHUNKS_RADIUS, Config.VOXY_PROGRESS, Config.VOXY_OVERLAY_CORNER,
      Config.VOXY_OVERLAY_HIDE_WHEN_DONE, Config.VOXY_OVERLAY_ANIMATION);

   private int lods(List<Placed> widgets) {
      int left = this.left();
      int y = 0;
      y += this.text(Component.literal("Voxy's distant terrain made on the GPU instead of generated chunks, in rings around you, nearest first: the ground,"
         + " its surface blocks, water, snow and ice, plus the trees, structures, terrain features and plants set in the config's [voxy.features]"
         + " (the chunks you really generate replace it in Voxy)."
         + " Chunks already generated are never written over. Overworld only."), left, y, this.width - left - 8, GREY) + 6;
      if (!com.wgaccel.platform.Platform.get().isModLoaded("voxy")) {
         y += this.text(Component.literal("Voxy is not installed: these settings do nothing until it is."), left, y, this.width - left - 8, YELLOW) + 6;
      }

      y += this.text(Component.literal("Generator").withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
      y += this.setting(widgets, Config.VOXY_GENERATOR, "Voxy LOD generator", "Makes Voxy's distant terrain around you by itself, as you play.", y);
      y += this.setting(widgets, Config.VOXY_RINGS, "Rings",
         "Nearest first, as radius:stride (radius in chunks; stride the blocks between the columns worked out). 1 is full detail (about 1,000"
            + " chunks/s on an RX 9070 XT); 32-256 is one column every stride/16 chunks, the heights between interpolated (32: about"
            + " 3,000 chunks/s; 64: about 9,000, blotchier blocks). Default: 64:1; 192:32; 512:64.", y);
      y += this.setting(widgets, Config.VOXY_PERFORMANCE, "Performance",
         "How much of the CPU it takes. LOW: an eighth of the threads, low priority; MEDIUM: a quarter; HIGH: half; EXTREME: all but two;"
            + " DYNAMIC: HIGH for the first ring, MEDIUM for the second, LOW after (Voxy World Gen V2 gets more tasks as ours slows). Applies at once.", y);
      y += this.setting(widgets, Config.VOXY_THREADS, "Threads", "How many threads it works on. 0: from the performance setting.", y);
      y += this.setting(widgets, Config.VOXY_PAUSE_ABOVE_MSPT, "Pause above (ms a tick)",
         "It waits while the server's average tick takes longer than this, so the game stays smooth. 0: never waits.", y);
      y += this.setting(widgets, Config.VOXY_WITH_VWG, "With Voxy World Gen V2",
         "AFTER: our LODs first, then Voxy World Gen V2 runs and replaces them with real chunks. AVOID: its circle is left to it. IGNORE: no link.", y);
      y += this.setting(widgets, Config.VOXY_VWG_TASKS, "Voxy World Gen V2 tasks", "AFTER: its chunks at once once it runs. 0: its own setting.", y);
      y += this.setting(widgets, Config.VOXY_VWG_AFTER_RADIUS, "Voxy World Gen V2 starts after",
         "AFTER: it starts once our LODs within this many chunks are done (e.g. 128). 0: its own radius.", y);
      y += this.setting(widgets, Config.VOXY_REAL_CHUNKS, "Real chunks after LODs",
         "When the rings around you are done, generate the real chunks near you with Chunky (needs Chunky); they replace the LODs in Voxy.", y);
      y += this.setting(widgets, Config.VOXY_REAL_CHUNKS_RADIUS, "Real chunks radius", "In chunks around you. 0: the first ring's radius.", y);
      y += 6;
      y += this.text(Component.literal("Progress").withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
      y += this.setting(widgets, Config.VOXY_PROGRESS, "Show progress", "OVERLAY: a panel in a corner of the screen; F3: lines in the debug screen; BOTH; OFF.", y);
      y += this.setting(widgets, Config.VOXY_OVERLAY_CORNER, "Panel corner", "Which corner of the screen the panel sits in.", y);
      y += this.setting(widgets, Config.VOXY_OVERLAY_HIDE_WHEN_DONE, "Hide when done", "The panel goes 10 seconds after the rings are done; it comes back when you move on.", y);
      y += this.setting(widgets, Config.VOXY_OVERLAY_ANIMATION, "Chase scene", "A cheetah chasing a hare through the biomes, under the panel's title (and on the world-loading screen); it runs at the generator's speed.", y);
      y += 6;
      y += this.text(Component.literal("Visuals").withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
      y += this.setting(widgets, Config.VOXY_HEIGHT_DITHER, "Height dither",
         "Against moire far away: a random +-1 block on each column's height breaks regular steps. FAR: the interpolated rings; ALL: every ring."
            + " New LODs only (/wgaccel lod voxy reset).", y);
      y += this.setting(widgets, Config.VOXY_STEP_SIDES, "Step sides like the surface",
         "Against moire far away: the side of each step shows the surface block (grass, snow) instead of dirt. New LODs only.", y);
      y += 6;
      y += this.text(Component.literal("Features").withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
      y += this.setting(widgets, Config.VOXY_TREES, "Trees",
         "Each biome's own trees and huge mushrooms, shaped as the game grows them, about as many as it makes. ALL: every ring; FAR: all but the outermost ring; NEAR: the full-detail rings; OFF."
            + " New LODs only (/wgaccel lod voxy reset).", y);
      y += this.setting(widgets, Config.VOXY_PLANTS, "Plants",
         "Each biome's own plants (flowers, grass, bushes, Conquest's ground cover), run as the game places them, into the empty cells over the"
            + " ground. FAR: all rings but the outermost; ALL: every ring (far rings once per sample). New LODs only (/wgaccel lod voxy reset).", y);
      y += this.setting(widgets, Config.VOXY_TERRAIN_FEATURES, "Terrain features",
         "Ice spikes, icebergs, mossy boulders, desert wells, mods' snow cover such as Still Life's (each biome's own, run as the game places them). FAR: all rings but the"
            + " outermost; ALL: every ring. New LODs only (/wgaccel lod voxy reset).", y);
      y += this.setting(widgets, Config.VOXY_TERRAIN_COST_LIMIT, "Terrain feature cost limit (ms)",
         "A terrain feature costing more than this a chunk is left out (Still Life's cliff rock costs about 20 ms). 0: no limit."
            + " New LODs only (/wgaccel lod voxy reset).", y);
      y += this.setting(widgets, Config.VOXY_STRUCTURES, "Structures (in progress)",
         "Villages, temples, outposts and ruins in the LODs: found from the seed and placed by their own code (underground ones left out)."
            + " Full-detail rings for now. New LODs only (/wgaccel lod voxy reset).", y);
      if (com.wgaccel.platform.Platform.get().isModLoaded("aronalayers")) {
         y += this.setting(widgets, Config.VOXY_ARONA_LAYERS, "AronaLayers layers",
            "AronaLayers' layers on the LODs' ground, placed by AronaLayers itself (needs its LayersApi version 2). With Layer Supported LOD they"
               + " show at their real height. FAR: all rings but the outermost; ALL: every ring. New LODs only (/wgaccel lod voxy reset).", y);
      }

      if (com.wgaccel.platform.Platform.get().isModLoaded("lithos")) {
         y += this.setting(widgets, Config.VOXY_LITHOS, "Lithos rock",
            "Lithos's own rock strata, cliff stone and ground cover on the LODs' ground (its features run as they are; slow: about 3 times"
               + " slower near rings). FAR: all rings but the outermost; ALL: every ring (far rings one run per sample). New LODs only (/wgaccel lod voxy reset).", y);
         y += this.setting(widgets, Config.VOXY_LITHOS_STRATA, "  Strata",
            "Lithos's rock layers. No top block changes, only the sides of steps and slopes; its costliest feature (about 20 ms a chunk)."
               + " New LODs only.", y);
         y += this.setting(widgets, Config.VOXY_LITHOS_LAYERED_STONE, "  Layered stone",
            "Lithos's stone bands, mostly under the surface the LODs show (about 5 ms a chunk). New LODs only.", y);
         y += this.setting(widgets, Config.VOXY_LITHOS_CLIFF_STONE, "  Cliff stone", "Lithos's stone on steep ground. New LODs only.", y);
         y += this.setting(widgets, Config.VOXY_LITHOS_GROUND_COVER, "  Ground cover",
            "Lithos's surface blocks and what lies on them. New LODs only.", y);
         y += this.setting(widgets, Config.VOXY_LITHOS_VEGETATION_FILL, "  Vegetation fill", "The plants Lithos adds. New LODs only.", y);
      }

      if (com.wgaccel.platform.Platform.get().isModLoaded("conquest-of-arborea")) {
         y += this.setting(widgets, Config.VOXY_ARBOREA_FORESTS, "Arborea forests",
            "Conquest of Arborea's own forests (its tree feature run as it is), with Trees on. New LODs only (/wgaccel lod voxy reset).", y);
      }

      return y;
   }

   // The Advanced tab.

   private int advanced(List<Placed> widgets) {
      int left = this.left();
      int y = 0;
      y += this.text(Component.literal("The GPU's checks and tuning, for tests and benchmarks. The defaults are the measured best and every value gives the"
         + " same world. Most apply from the next world you open; the ones that say so, when the GPU or the game starts."), left, y,
         this.width - left - 8, GREY) + 8;
      for (Advanced.Group group : Advanced.GROUPS) {
         y += this.text(Component.literal(group.title()).withStyle(ChatFormatting.BOLD), left, y, this.width - left - 8, WHITE) + 4;
         for (Advanced.Entry entry : group.entries()) {
            y += this.setting(widgets, entry.setting(), entry.name(), entry.description(), y);
         }

         y += 6;
      }

      return y;
   }

   /**
    * A setting's row with the control its value takes: a switch (true or false), a mode button (cycles its modes), a
    * number box (red while not a number in its range) or a list box under the text (entries separated by ";"). Returns
    * the row's height.
    */
   @SuppressWarnings("unchecked")
   private int setting(List<Placed> widgets, Setting<?> setting, String name, String description, int y) {
      int left = this.left();
      Object value = setting.chosen();
      boolean changed = setting.normal() == null ? !java.util.Objects.equals(value, setting.fileDefault()) : setting.overriddenNow();
      Tooltip tooltip = Tooltip.create(Component.literal("Config " + setting.key() + (changed ? " (changed from its default)" : "")));
      String label = name + (changed ? "  (changed)" : "");
      int nameColor = changed ? YELLOW : WHITE;
      if (value instanceof Boolean on) {
         Button toggle = this.toggle(on ? "ON" : "OFF", on, pressed -> {
            apply(setting, !on);
            this.rebuildWidgets();
         });
         toggle.setTooltip(tooltip);
         widgets.add(new Placed(toggle, y));
      } else if (value instanceof Enum<?> mode) {
         Object[] modes = mode.getDeclaringClass().getEnumConstants();
         Button cycle = this.toggle(mode.name(), Improvements.on(mode), pressed -> {
            apply(setting, modes[(mode.ordinal() + 1) % modes.length]);
            this.rebuildWidgets();
         });
         cycle.setTooltip(tooltip);
         widgets.add(new Placed(cycle, y));
      } else if (value instanceof Number) {
         net.minecraft.client.gui.components.EditBox box = new net.minecraft.client.gui.components.EditBox(this.font, left, 0, 60, 20, Component.literal(name));
         box.setValue(String.valueOf(value));
         box.setTooltip(tooltip);
         box.setResponder(text -> {
            Object parsed = number(setting, text);
            box.setTextColor(parsed == null ? 0xFFFF5555 : 0xFFE0E0E0);
            if (parsed != null && !parsed.equals(setting.chosen())) {
               apply(setting, parsed);
            }
         });
         widgets.add(new Placed(box, y));
      } else if (value instanceof List<?> list) {
         int height = this.row(label, description, this.textLeft(), y, nameColor, GREY);
         net.minecraft.client.gui.components.EditBox box = new net.minecraft.client.gui.components.EditBox(this.font, this.textLeft(), 0, this.room(), 18,
            Component.literal(name));
         box.setMaxLength(1024);
         box.setValue(String.join("; ", list.stream().map(String::valueOf).toList()));
         box.setTooltip(tooltip);
         box.setResponder(text -> apply(setting, java.util.Arrays.stream(text.split(";")).map(String::trim).filter(each -> !each.isEmpty()).toList()));
         widgets.add(new Placed(box, y + height - 2));
         return height + 22;
      }

      return this.row(label, description, this.textLeft(), y, nameColor, GREY);
   }

   /** The text as a number of the setting's kind within its range, or null. */
   @SuppressWarnings("unchecked")
   private static Object number(Setting<?> setting, String text) {
      try {
         Object like = setting.fileDefault();
         Comparable<Object> parsed = like instanceof Double ? (Comparable<Object>)(Object)Double.valueOf(text.trim())
            : (Comparable<Object>)(Object)Integer.valueOf(text.trim());
         Object min = setting.raw().min();
         Object max = setting.raw().max();
         if (min != null && parsed.compareTo(min) < 0 || max != null && parsed.compareTo(max) > 0) {
            return null;
         }

         return parsed;
      } catch (NumberFormatException notNumber) {
         return null;
      }
   }

   /** A value from the screen: a setting the presets give is chosen (config overrides), one they leave alone is written. */
   @SuppressWarnings("unchecked")
   private static void apply(Setting<?> setting, Object value) {
      if (setting.normal() == null) {
         ((Setting<Object>)setting).set(value);
      } else {
         ((Setting<Object>)setting).choose(value);
      }
   }

   /** Back to its default: the preset's (the screen's choice taken out), or the file's default. */
   @SuppressWarnings("unchecked")
   private static void reset(Setting<?> setting) {
      if (setting.normal() == null) {
         ((Setting<Object>)setting).set(setting.fileDefault());
      } else {
         setting.unchoose();
      }
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
         return !Config.GPU_IMPROVEMENTS.get() ? "GPU OFF: the GPU improvements are switched off, so no GPU is used, whatever is chosen below."
            + " Turn them on in the GPU improvements tab."
            : Config.GPU_MODE.get() == Config.GpuMode.OFF ? "GPU OFF: gpuMode is OFF, so no GPU is used, whatever is chosen below."
            : "GPU not started yet: it starts with the first world.";
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
   protected void drawBackground(Canvas canvas) {
      canvas.fill(0, 0, this.width, this.height, 0xB0000000);
   }

   @Override
   protected void draw(Canvas canvas, int mouseX, int mouseY, float partialTick) {
      canvas.centeredText(this.font, this.title, this.width / 2, 8, WHITE);
      canvas.scissor(0, TOP, this.width, this.bottom());
      for (Text text : this.texts) {
         int y = TOP + text.y() - this.scroll;
         if (y + 10 > TOP && y < this.bottom()) {
            canvas.text(this.font, text.line(), text.x(), y, text.color());
         }
      }

      canvas.endScissor();
      int visible = this.visible();
      if (this.contentHeight > visible && visible > 0) {
         // A scroll bar at the right edge.
         int bar = Math.max(16, visible * visible / this.contentHeight);
         int at = TOP + (visible - bar) * this.scroll / Math.max(1, this.contentHeight - visible);
         canvas.fill(this.width - 6, TOP, this.width - 2, this.bottom(), 0x40FFFFFF);
         canvas.fill(this.width - 6, at, this.width - 2, at + bar, 0xC0FFFFFF);
      }
   }

   @Override
   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }
}
