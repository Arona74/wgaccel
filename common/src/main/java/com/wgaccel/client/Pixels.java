package com.wgaccel.client;

/**
 * A small picture drawn in memory (ARGB, one pixel per GUI pixel), then drawn on screen as one texture
 * ({@link Canvas#pixels}): the chase scene's hundreds of rectangles cost one draw a frame instead of hundreds. Fills
 * with alpha blend over what is there; one clip rectangle at a time ({@link #scissor}).
 */
public final class Pixels {
   int width;
   int height;
   int[] argb;
   /** Bumped on each new picture: the texture is uploaded again when it differs. */
   int version;
   /** The version's texture for it (Canvas's own), or null. */
   Object handle;
   private int clipX0;
   private int clipY0;
   private int clipX1;
   private int clipY1;

   public Pixels(int width, int height) {
      this.resize(width, height);
   }

   /** A new size (the picture is cleared). */
   void resize(int width, int height) {
      this.width = Math.max(1, width);
      this.height = Math.max(1, height);
      this.argb = new int[this.width * this.height];
      this.endScissor();
      this.version++;
   }

   public int width() {
      return this.width;
   }

   public int height() {
      return this.height;
   }

   public void fill(int x0, int y0, int x1, int y1, int color) {
      int ax = Math.max(Math.min(x0, x1), this.clipX0);
      int bx = Math.min(Math.max(x0, x1), this.clipX1);
      int ay = Math.max(Math.min(y0, y1), this.clipY0);
      int by = Math.min(Math.max(y0, y1), this.clipY1);
      int alpha = color >>> 24;
      if (ax >= bx || ay >= by || alpha == 0) {
         return;
      }

      int[] a = this.argb;
      for (int y = ay; y < by; y++) {
         int row = y * this.width;
         if (alpha == 255) {
            java.util.Arrays.fill(a, row + ax, row + bx, color);
            continue;
         }

         for (int x = ax; x < bx; x++) {
            a[row + x] = blend(a[row + x], color, alpha);
         }
      }
   }

   /** Draws only inside this rectangle until {@link #endScissor}. */
   public void scissor(int x0, int y0, int x1, int y1) {
      this.clipX0 = Math.max(0, x0);
      this.clipY0 = Math.max(0, y0);
      this.clipX1 = Math.min(this.width, x1);
      this.clipY1 = Math.min(this.height, y1);
   }

   public void endScissor() {
      this.clipX0 = 0;
      this.clipY0 = 0;
      this.clipX1 = this.width;
      this.clipY1 = this.height;
   }

   private static int blend(int under, int over, int alpha) {
      int keep = 255 - alpha;
      int r = ((over >> 16 & 255) * alpha + (under >> 16 & 255) * keep) / 255;
      int g = ((over >> 8 & 255) * alpha + (under >> 8 & 255) * keep) / 255;
      int b = ((over & 255) * alpha + (under & 255) * keep) / 255;
      int a = Math.min(255, alpha + (under >>> 24) * keep / 255);
      return a << 24 | r << 16 | g << 8 | b;
   }
}
