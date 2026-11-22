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

   /**
    * A picture drawn in memory, at x, y, one texel per GUI pixel: one texture draw (uploaded again only when the picture
    * changed).
    */
   public void pixels(Pixels pixels, int x, int y) {
      PixelTexture texture = pixels.handle instanceof PixelTexture own ? own : null;
      if (texture == null || texture.width != pixels.width || texture.height != pixels.height) {
         if (texture != null) {
            texture.release();
         }

         texture = new PixelTexture(pixels.width, pixels.height);
         pixels.handle = texture;
      }

      if (texture.version != pixels.version) {
         com.mojang.blaze3d.platform.NativeImage image = texture.texture.getPixels();
         if (image != null) {
            for (int py = 0; py < pixels.height; py++) {
               for (int px = 0; px < pixels.width; px++) {
                  image.setPixel(px, py, pixels.argb[py * pixels.width + px]);
               }
            }

            texture.texture.upload();
         }

         texture.version = pixels.version;
      }

      this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id, x, y, 0.0F, 0.0F, pixels.width, pixels.height, pixels.width, pixels.height,
         pixels.width, pixels.height);
   }

   /** A Pixels' texture (registered under its own id). */
   private static final class PixelTexture {
      private static final java.util.concurrent.atomic.AtomicInteger COUNT = new java.util.concurrent.atomic.AtomicInteger();
      final int width;
      final int height;
      final net.minecraft.client.renderer.texture.DynamicTexture texture;
      final Identifier id;
      int version = -1;

      PixelTexture(int width, int height) {
         this.width = width;
         this.height = height;
         this.texture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "wgaccel panel picture", width, height, false);
         this.id = Identifier.fromNamespaceAndPath("wgaccel", "dynamic/picture_" + COUNT.incrementAndGet());
         net.minecraft.client.Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
      }

      void release() {
         net.minecraft.client.Minecraft.getInstance().getTextureManager().release(this.id);
      }
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
