package com.wgaccel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The button with the mod's logo (logo.png simplified, 20 x 20) in Options that opens the settings screen; a white outline
 * when hovered. Placed left of the field of view's slider (OptionsScreenMixin, again after each re-layout); when next
 * drawn (every mod's buttons made by then) it moves under another mod's small button found there (Distant Horizons') and
 * takes its size.
 */
public final class LogoButton extends Button {
   private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath("wgaccel", "textures/gui/wga_button.png");
   private final Screen parent;
   private int row;
   private int sliderLeft;
   private boolean aligned;

   public LogoButton(Screen parent) {
      super(0, 0, 20, 20, Component.literal("Worldgen Accelerator"), pressed -> net.minecraft.client.Minecraft.getInstance().setScreen(new WgaScreen(parent)),
         DEFAULT_NARRATION);
      this.parent = parent;
      this.setTooltip(Tooltip.create(Component.literal("Worldgen Accelerator: settings, GPU choice and system info")));
   }

   /** Left of the slider at this row; lined up with another mod's button there again when next drawn. */
   public void place(int x, int y, int row, int sliderLeft) {
      this.setX(x);
      this.setY(y);
      this.setWidth(20);
      this.setHeight(20);
      this.row = row;
      this.sliderLeft = sliderLeft;
      this.aligned = false;
   }

   private void align() {
      this.aligned = true;
      for (GuiEventListener child : this.parent.children()) {
         if (child != this && child instanceof AbstractWidget widget && widget.getX() < this.sliderLeft && widget.getWidth() < 40
            && widget.getY() <= this.row + 2 && widget.getY() + widget.getHeight() >= this.row - 2) {
            this.setX(widget.getX());
            this.setY(widget.getY() + widget.getHeight() + 4);
            this.setWidth(widget.getWidth());
            this.setHeight(widget.getHeight());
         }
      }
   }

   @Override
   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      if (!this.aligned) {
         this.align();
      }

      // The 20 x 20 icon drawn at the button's size (the button it lines up with may be smaller).
      graphics.blit(ICON, this.getX(), this.getY(), this.width, this.height, 0.0F, 0.0F, 20, 20, 20, 20);
      if (this.isHoveredOrFocused()) {
         graphics.renderOutline(this.getX(), this.getY(), this.width, this.height, 0xFFFFFFFF);
      }
   }
}
