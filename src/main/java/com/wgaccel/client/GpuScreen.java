package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.gpu.GpuDevice;
import com.wgaccel.gpu.GpuInventory;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The GPU screen (Options' WGA button, or the mods list's Config): every graphics adapter of the PC, which ones the mod
 * can use (CUDA, HIP; Intel's not yet), which one it uses now, and a choice of Automatic or one of them, saved as config
 * gpuBackend and gpuDevice. The GPU is chosen once per game, so a new choice applies after a restart. For PCs with more
 * than one GPU (a laptop's Intel Arc beside an NVIDIA card, a Ryzen's own Radeon beside a graphics card).
 */
public final class GpuScreen extends Screen {
   private static final int WHITE = 0xFFFFFFFF;
   private static final int GREY = 0xFFA0A0A0;
   private static final int GREEN = 0xFF55FF55;
   private static final int YELLOW = 0xFFFFFF55;
   private static final int ROW = 26;
   private static final int TOP = 62;
   private final Screen parent;
   /** Whether the widgets were made with the inventory (it is listed in the background at first). */
   private boolean listed;

   public GpuScreen(Screen parent) {
      super(Component.literal("Worldgen Accelerator: GPU"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      GpuInventory.Inventory inventory = GpuInventory.get();
      this.listed = inventory != null;
      int left = this.left();
      if (inventory != null) {
         boolean automatic = automaticChosen();
         this.addRenderableWidget(this.choice(automatic, true, left, TOP, () -> this.choose(Config.GpuBackendChoice.AUTO, -1)));
         int y = TOP + this.rowHeight(automaticText(inventory));
         for (GpuInventory.Adapter adapter : inventory.adapters()) {
            GpuInventory.Device device = adapter.device();
            Button button;
            if (device == null) {
               button = Button.builder(Component.literal("-"), pressed -> {
               }).bounds(left, y, 60, 20).build();
               button.active = false;
               button.setTooltip(Tooltip.create(Component.literal(unusable(adapter))));
            } else {
               boolean selected = !automatic && chosen(device);
               button = this.choice(selected, false, left, y, () -> this.choose(Config.GpuBackendChoice.valueOf(device.backend()), device.index()));
            }

            this.addRenderableWidget(button);
            y += this.rowHeight(details(adapter));
         }
      }

      this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> this.onClose()).bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
   }

   private Button choice(boolean selected, boolean automatic, int x, int y, Runnable choose) {
      Button button = Button.builder(Component.literal(selected ? "Selected" : "Use"), pressed -> choose.run()).bounds(x, y, 60, 20).build();
      button.active = !selected;
      button.setTooltip(Tooltip.create(Component.literal(automatic ? "Let the mod choose: CUDA when NVIDIA's driver sees a card, else AMD's HIP; then the card with the most compute units, a separate card before an integrated one."
         : "Use this GPU (after a restart of the game).")));
      return button;
   }

   private void choose(Config.GpuBackendChoice backend, int device) {
      Config.GPU_BACKEND.set(backend);
      Config.GPU_DEVICE.set(device);
      this.rebuildWidgets();
   }

   private static boolean automaticChosen() {
      return Config.GPU_BACKEND.written() == Config.GpuBackendChoice.AUTO && Config.GPU_DEVICE.written() < 0;
   }

   /** Whether the config names this device (with AUTO as the backend, the one AUTO would take for it). */
   private static boolean chosen(GpuInventory.Device device) {
      Config.GpuBackendChoice backend = Config.GPU_BACKEND.written();
      String wanted = backend == Config.GpuBackendChoice.AUTO ? automaticBackend() : backend.name();
      return device.backend().equals(wanted) && device.index() == Config.GPU_DEVICE.written();
   }

   private static String automaticBackend() {
      GpuInventory.Inventory inventory = GpuInventory.get();
      return inventory == null || inventory.automatic() == null ? "" : inventory.automatic().backend();
   }

   private static String unusable(GpuInventory.Adapter adapter) {
      String vendor = adapter.vendor() == null ? "" : adapter.vendor().toLowerCase(Locale.ROOT);
      if (vendor.contains("intel")) {
         return "Intel GPUs are not supported yet (no oneAPI backend).";
      }

      return "Neither NVIDIA's CUDA nor AMD's HIP sees this GPU (driver missing, or AMD's HIP SDK not installed).";
   }

   private int left() {
      return Math.max(8, this.width / 2 - 190);
   }

   @Override
   public void tick() {
      if (!this.listed && GpuInventory.get() != null) {
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
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, WHITE);
      graphics.drawCenteredString(this.font, Component.literal(inUse()), this.width / 2, 28, WHITE);
      GpuInventory.Inventory inventory = GpuInventory.get();
      String pending = pending(inventory);
      String note = pending != null ? pending
         : GpuDevice.chosenName == null ? "A choice is used from the first world you open (no restart needed yet)."
         : "The GPU is chosen once per game: a new choice applies after restarting the game.";
      // At most two lines between the title block and the list.
      List<FormattedCharSequence> noteLines = this.font.split(Component.literal(note), Math.max(100, this.width - 16));
      for (int i = 0; i < Math.min(2, noteLines.size()); i++) {
         graphics.drawCenteredString(this.font, noteLines.get(i), this.width / 2, 40 + 10 * i, pending != null ? YELLOW : GREY);
      }

      if (inventory == null) {
         graphics.drawCenteredString(this.font, Component.literal("Looking for GPUs..."), this.width / 2, TOP + 6, GREY);
         return;
      }

      int x = this.textLeft();
      graphics.drawString(this.font, this.fit("Automatic"), x, TOP + 1, automaticChosen() ? GREEN : WHITE);
      int y = TOP + this.drawWrapped(graphics, automaticText(inventory), x, TOP + 11, GREY);
      for (GpuInventory.Adapter adapter : inventory.adapters()) {
         GpuInventory.Device device = adapter.device();
         boolean selected = device != null && !automaticChosen() && chosen(device);
         graphics.drawString(this.font, this.fit(adapter.name() + (inUse(device) ? "  (in use)" : "")), x, y + 1, selected ? GREEN : device == null ? GREY : WHITE);
         y += this.drawWrapped(graphics, details(adapter), x, y + 11, device == null ? YELLOW : GREY);
      }

      if (!inventory.problems().isEmpty()) {
         this.drawWrapped(graphics, "Listing problems: " + String.join("; ", inventory.problems()), this.left(), y + 4, YELLOW);
      }
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

   /** Draws the text wrapped to the room left; returns the row's height (its name line, these lines and a gap, at least ROW). */
   private int drawWrapped(GuiGraphics graphics, String text, int x, int y, int color) {
      List<FormattedCharSequence> lines = this.font.split(Component.literal(text), this.room());
      for (int i = 0; i < lines.size(); i++) {
         graphics.drawString(this.font, lines.get(i), x, y + 10 * i, color);
      }

      return this.rowHeight(lines.size());
   }

   /** A row's height for this second-line text: as {@link #drawWrapped} lays it out. */
   private int rowHeight(String text) {
      return this.rowHeight(this.font.split(Component.literal(text), this.room()).size());
   }

   private int rowHeight(int lines) {
      return Math.max(ROW, 11 + 10 * lines + 6);
   }

   private static String automaticText(GpuInventory.Inventory inventory) {
      GpuInventory.Device automatic = inventory.automatic();
      return automatic == null ? "no usable GPU found" : "now: " + automatic.name() + " (" + automatic.backend() + ")";
   }

   private static String details(GpuInventory.Adapter adapter) {
      StringBuilder text = new StringBuilder(adapter.vendor() == null ? "" : adapter.vendor());
      long memory = adapter.memory() > 0 ? adapter.memory() : adapter.device() == null ? 0 : adapter.device().memory();
      if (memory > 0) {
         text.append(String.format(Locale.ROOT, ", %.1f GB", memory / 1.073741824e9));
      }

      GpuInventory.Device device = adapter.device();
      if (device == null) {
         text.append(" - ").append(unusable(adapter));
      } else {
         text.append(String.format(Locale.ROOT, " - %s device %d, %d compute units%s", device.backend(), device.index(), device.units(), device.integrated() ? ", integrated" : ""));
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
            if (adapter.device() != null && chosen(adapter.device())) {
               wanted = adapter.device();
            }
         }
      }

      if (wanted == null || inUse(wanted)) {
         return null;
      }

      return "Restart the game to switch to " + wanted.name() + ": this game keeps using " + GpuDevice.chosenName + ".";
   }

   /** The GPU the mod uses in this game, if it has started one. */
   private static String inUse() {
      String name = GpuDevice.chosenName;
      if (name == null) {
         return Config.GPU_IMPROVEMENTS.get() ? "GPU not started yet: it starts with the first world." : "The GPU parts are off (gpuImprovements = false).";
      }

      return "In use in this game: " + name + " (" + com.wgaccel.gpu.GpuBackend.name() + ", " + GpuDevice.reason.split(";")[0] + ")";
   }

   private static boolean inUse(GpuInventory.Device device) {
      return device != null && GpuDevice.chosenName != null && device.name().equals(GpuDevice.chosenName)
         && device.backend().equals(com.wgaccel.gpu.GpuBackend.name()) && device.index() == GpuDevice.index();
   }

   @Override
   public void onClose() {
      this.minecraft.setScreen(this.parent);
   }
}
