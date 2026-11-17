package com.wgaccel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A button that draws itself on a {@link Canvas}. */
public abstract class CanvasButton extends Button {
   protected CanvasButton(int x, int y, int width, int height, Component message, OnPress onPress, CreateNarration narration) {
      super(x, y, width, height, message, onPress, narration);
   }

   @Override
   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.draw(new Canvas(graphics), mouseX, mouseY, partialTick);
   }

   protected abstract void draw(Canvas canvas, int mouseX, int mouseY, float partialTick);
}
