package com.wgaccel.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A screen that draws on a {@link Canvas}, after the usual background and widgets. */
public abstract class CanvasScreen extends Screen {
   protected CanvasScreen(Component title) {
      super(title);
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractBackground(graphics, mouseX, mouseY, partialTick);
      this.drawBackground(new Canvas(graphics));
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      this.draw(new Canvas(graphics), mouseX, mouseY, partialTick);
   }

   /** Over the usual background, under the widgets. */
   protected void drawBackground(Canvas canvas) {
   }

   /** Over the widgets. */
   protected void draw(Canvas canvas, int mouseX, int mouseY, float partialTick) {
   }
}
