package com.wgaccel.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * What the mod's screens draw with, over the version's own drawing class (GuiGraphics on 1.21.1,
 * GuiGraphicsExtractor on 26.1). Text in a colour without alpha is drawn opaque, as 1.21.1 always did (26.1 would
 * leave it invisible).
 */
public final class Canvas {
   private final GuiGraphicsExtractor graphics;

   public Canvas(GuiGraphicsExtractor graphics) {
      this.graphics = graphics;
   }

   public void fill(int x0, int y0, int x1, int y1, int color) {
      this.graphics.fill(x0, y0, x1, y1, color);
   }

   public void text(Font font, String text, int x, int y, int color) {
      this.graphics.text(font, text, x, y, opaque(color));
   }

   public void text(Font font, Component text, int x, int y, int color) {
      this.graphics.text(font, text, x, y, opaque(color));
   }

   public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
      this.graphics.text(font, text, x, y, opaque(color));
   }

   public void centeredText(Font font, String text, int x, int y, int color) {
      this.graphics.centeredText(font, text, x, y, opaque(color));
   }

   public void centeredText(Font font, Component text, int x, int y, int color) {
      this.graphics.centeredText(font, text, x, y, opaque(color));
   }

   /** Draws only inside this rectangle until {@link #endScissor}. */
   public void scissor(int x0, int y0, int x1, int y1) {
      this.graphics.enableScissor(x0, y0, x1, y1);
   }

   public void endScissor() {
      this.graphics.disableScissor();
   }

   public void outline(int x, int y, int width, int height, int color) {
      this.graphics.outline(x, y, width, height, color);
   }

   /** The whole texture (textureWidth x textureHeight) drawn at x, y at width x height. */
   public void texture(Texture texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
      this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id, x, y, 0.0F, 0.0F, width, height, textureWidth, textureHeight, textureWidth,
         textureHeight);
   }

   /** As 1.21.1's Font: a colour with (almost) no alpha is opaque. */
   private static int opaque(int color) {
      return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
   }

   /** A texture in a resource pack (assets/namespace/path). */
   public static final class Texture {
      private final Identifier id;

      public Texture(String namespace, String path) {
         this.id = Identifier.fromNamespaceAndPath(namespace, path);
      }
   }
}
