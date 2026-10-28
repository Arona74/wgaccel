package com.wgaccel.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * What the mod's screens draw with, over the version's own drawing class (GuiGraphics on 1.21.1,
 * GuiGraphicsExtractor on 26.1). Text in a colour without alpha is drawn opaque, as 1.21.1 always did.
 */
public final class Canvas {
   private final GuiGraphics graphics;

   public Canvas(GuiGraphics graphics) {
      this.graphics = graphics;
   }

   public void fill(int x0, int y0, int x1, int y1, int color) {
      this.graphics.fill(x0, y0, x1, y1, color);
   }

   public void text(Font font, String text, int x, int y, int color) {
      this.graphics.drawString(font, text, x, y, color);
   }

   public void text(Font font, Component text, int x, int y, int color) {
      this.graphics.drawString(font, text, x, y, color);
   }

   public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
      this.graphics.drawString(font, text, x, y, color);
   }

   public void centeredText(Font font, String text, int x, int y, int color) {
      this.graphics.drawCenteredString(font, text, x, y, color);
   }

   public void centeredText(Font font, Component text, int x, int y, int color) {
      this.graphics.drawCenteredString(font, text, x, y, color);
   }

   /** Draws only inside this rectangle until {@link #endScissor}. */
   public void scissor(int x0, int y0, int x1, int y1) {
      this.graphics.enableScissor(x0, y0, x1, y1);
   }

   public void endScissor() {
      this.graphics.disableScissor();
   }

   public void outline(int x, int y, int width, int height, int color) {
      this.graphics.renderOutline(x, y, width, height, color);
   }

   /** The whole texture (textureWidth x textureHeight) drawn at x, y at width x height. */
   public void texture(Texture texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
      this.graphics.blit(texture.id, x, y, width, height, 0.0F, 0.0F, textureWidth, textureHeight, textureWidth, textureHeight);
   }

   /** A texture in a resource pack (assets/namespace/path). */
   public static final class Texture {
      private final ResourceLocation id;

      public Texture(String namespace, String path) {
         this.id = ResourceLocation.fromNamespaceAndPath(namespace, path);
      }
   }
}
