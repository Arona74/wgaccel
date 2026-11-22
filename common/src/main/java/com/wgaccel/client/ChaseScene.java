package com.wgaccel.client;

/**
 * The Voxy LOD panel's scene (config voxyOverlayAnimation), also on the world-loading screen: a blocky cheetah
 * ({@link BlockCheetah}) chasing a green hare over sliding ground, the background passing through biomes (savanna,
 * forest, desert, snowy taiga, badlands) with Minecraft-like hard borders, a slower far layer and clouds. Drawn with
 * filled rectangles only. The caller keeps the scene's time ({@link Clock}): it runs faster or slower with the
 * generator's speed.
 */
final class ChaseScene {
   static final int S = 2;
   static final int GROUND_FROM_BOTTOM = 8;
   /** Pixels of ground per biome. */
   static final int SEGMENT = 360;
   static final double SPEED = 40.0;

   static final String[][] HARE = {
      {
         ".....GGggg......",
         "........gggg....",
         "..gggggggggegg..",
         "wgggggggggggggg.",
         ".ggggggggggg....",
         "ggg.......ggg...",
         "gg..........gg.."
      },
      {
         "......GGggg.....",
         ".........gggg...",
         "....gggggggegg..",
         "..wgggggggggggg.",
         "...gggggggg.....",
         "....gg..gg......",
         "...gg....gg....."
      }
   };

   private record Biome(String name, int skyTop, int skyLow, int edge, int top, int soil, int pebble) {
   }

   static final Biome[] BIOMES = {
      new Biome("savanna", 0xFF52708F, 0xFF7E9AB0, 0xFFC9B060, 0xFFB59A4E, 0xFF8A6D3B, 0xFF6B5636),
      new Biome("forest", 0xFF4A6684, 0xFF6F8CA6, 0xFF6BAA4A, 0xFF4F8A3A, 0xFF5A3E26, 0xFF46301E),
      new Biome("desert", 0xFF6F95BA, 0xFFD9B98A, 0xFFF0D896, 0xFFE6C47A, 0xFFCFA85E, 0xFFB8904A),
      new Biome("snowy taiga", 0xFF71879C, 0xFF9AAABB, 0xFFFFFFFF, 0xFFEEF2F6, 0xFFC9D3DC, 0xFFAEBAC6),
      new Biome("badlands", 0xFF6E8FAE, 0xFFC9A27E, 0xFFD27A48, 0xFFB8613A, 0xFF8F4A2C, 0xFF7A3E24)
   };

   private ChaseScene() {
   }

   static long hash(long k) {
      k ^= k >>> 33;
      k *= 0xff51afd7ed558ccdL;
      k ^= k >>> 33;
      k *= 0xc4ceb9fe1a85ec53L;
      return k ^ k >>> 33;
   }

   /** The scene at (x, y), w x h GUI pixels, at its time t (seconds of normal-speed running). */
   static void draw(Pixels c, int x, int y, int w, int h, double t) {
      double scroll = t * SPEED;
      int ground = y + h - GROUND_FROM_BOTTOM;
      int first = (int)Math.floor(scroll / SEGMENT);
      for (int seg = first; ; seg++) {
         int from = x + (int)Math.round(seg * (double)SEGMENT - scroll);
         if (from >= x + w) {
            break;
         }

         int x0 = Math.max(x, from);
         int x1 = Math.min(x + w, from + SEGMENT);
         Biome b = BIOMES[Math.floorMod(seg, BIOMES.length)];
         c.scissor(x0, y, x1, y + h);
         backdrop(c, b, Math.floorMod(seg, BIOMES.length), x, y, w, ground, h, scroll, t);
         c.endScissor();
      }

      c.scissor(x, y, x + w, y + h);
      // The chase.
      double cycle = t / 0.36;
      int cheetahX = x + 10 + (int)Math.round(5 * Math.sin(t * 0.7));
      int gap = 16 + (int)Math.round(9 * Math.sin(t * 1.1 + 1.0));
      dust(c, cheetahX + 6, ground, t);
      BlockCheetah.draw(c, cheetahX, ground, cycle % 1.0, S);
      int hareX = cheetahX + 40 * S + gap;
      int hop = (int)Math.round(Math.abs(Math.sin(t * 7)) * 5);
      String[] hare = HARE[(int)(t * 7) & 1];
      sprite(c, hare, hareX, ground - hare.length * S - hop);
      c.endScissor();
   }

   static void backdrop(Pixels c, Biome b, int kind, int x, int y, int w, int ground, int h, double scroll, double t) {
      int half = (ground - y) / 2;
      c.fill(x, y, x + w, y + half, b.skyTop);
      c.fill(x, y + half, x + w, ground, b.skyLow);
      // Far layer (slower).
      double far = scroll * 0.35;
      int spacing = 46;
      for (int k = (int)Math.floor(far / spacing) - 1; k * spacing - far < w + spacing; k++) {
         long r = hash(k * 31L + kind);
         int fx = x + (int)Math.round(k * spacing - far) + (int)(r & 15);
         far(c, kind, fx, ground, r);
      }

      // Clouds, except in the desert.
      if (kind != 2) {
         double cl = scroll * 0.15;
         for (int k = (int)Math.floor(cl / 90) - 1; k * 90 - cl < w + 90; k++) {
            long r = hash(k * 7L + 99);
            int cx = x + (int)Math.round(k * 90 - cl) + (int)(r & 31);
            int cy = y + 3 + (int)((r >> 8) & 7);
            int alpha = kind == 3 ? 0x60 : 0x40;
            c.fill(cx, cy, cx + 18, cy + 3, alpha << 24 | 0xFFFFFF);
            c.fill(cx + 4, cy - 2, cx + 12, cy, alpha << 24 | 0xFFFFFF);
         }
      }

      // Ground.
      c.fill(x, ground, x + w, ground + 1, b.edge);
      c.fill(x, ground + 1, x + w, ground + 3, b.top);
      c.fill(x, ground + 3, x + w, y + h, b.soil);
      for (int k = (int)Math.floor(scroll / 11) - 1; k * 11 - scroll < w + 11; k++) {
         long r = hash(k * 13L + 5);
         int px = x + (int)Math.round(k * 11 - scroll) + (int)(r & 7);
         int py = ground + 4 + (int)((r >> 4) & 3);
         c.fill(px, py, px + 2, py + 1, b.pebble);
      }

      // Near decorations (ground speed).
      for (int k = (int)Math.floor(scroll / 23) - 1; k * 23 - scroll < w + 23; k++) {
         long r = hash(k * 17L + 3 + kind * 1000L);
         int nx = x + (int)Math.round(k * 23 - scroll) + (int)(r & 7);
         near(c, kind, nx, ground, r);
      }

      if (kind == 3) {
         // Falling snow.
         for (int i = 0; i < 26; i++) {
            long r = hash(i * 101L);
            int sx = x + (int)Math.floorMod((long)((r & 1023) - scroll * 0.6 - t * 6), (long)w);
            int sy = y + (int)Math.floorMod((long)(((r >> 10) & 63) + t * 14), (long)(ground - y));
            c.fill(sx, sy, sx + 1, sy + 1, 0xE0FFFFFF);
         }
      }
   }

   /** The far layer's object: acacia, pine, dune, snowy spruce, mesa. */
   static void far(Pixels c, int kind, int fx, int ground, long r) {
      switch (kind) {
         case 0 -> {
            if ((r & 0x300) != 0) {
               int th = 9 + (int)((r >> 12) & 3);
               c.fill(fx + 7, ground - th, fx + 8, ground, 0xFF5A4630);
               c.fill(fx, ground - th - 3, fx + 15, ground - th, 0xFF4A6A30);
               c.fill(fx + 2, ground - th - 4, fx + 13, ground - th - 3, 0xFF4A6A30);
            }
         }
         case 1 -> {
            for (int j = 0; j < 2; j++) {
               int px = fx + j * 20;
               int ph = 14 + (int)((r >> (12 + j * 3)) & 7);
               for (int row = 0; row < ph; row += 2) {
                  int half = 1 + (row * 5) / ph;
                  c.fill(px + 6 - half, ground - ph + row, px + 7 + half, ground - ph + row + 2, 0xFF2C5A38);
               }
            }
         }
         case 2 -> {
            int dh = 5 + (int)((r >> 12) & 3);
            c.fill(fx - 6, ground - dh + 2, fx + 40, ground, 0xFFD3AE6A);
            c.fill(fx + 2, ground - dh, fx + 28, ground - dh + 2, 0xFFD3AE6A);
            if ((r & 0x700) == 0) {
               c.fill(fx + 30, ground - 30, fx + 38, ground - 22, 0xFFFFE08A);
            }
         }
         case 3 -> {
            for (int j = 0; j < 2; j++) {
               int px = fx + j * 18;
               int ph = 13 + (int)((r >> (12 + j * 3)) & 7);
               for (int row = 0; row < ph; row += 2) {
                  int half = 1 + (row * 5) / ph;
                  c.fill(px + 6 - half, ground - ph + row, px + 7 + half, ground - ph + row + 2, 0xFF294A3C);
                  if (row % 4 == 0) {
                     c.fill(px + 6 - half, ground - ph + row, px + 7 + half, ground - ph + row + 1, 0xFFF4F7FA);
                  }
               }
            }
         }
         default -> {
            int mh = 12 + (int)((r >> 12) & 7);
            int[] bands = {0xFFD9A066, 0xFFB0603A, 0xFFC8834E, 0xFF9A4E30, 0xFFE0B080};
            for (int row = 0; row < mh; row++) {
               int widen = row < 2 ? 0 : 3;
               c.fill(fx - widen, ground - mh + row, fx + 30 + widen, ground - mh + row + 1, bands[(row / 3) % bands.length]);
            }
         }
      }
   }

      /** Near decorations: grass tufts, bushes, cacti, snow lumps, dead bushes. */
   static void near(Pixels c, int kind, int nx, int ground, long r) {
      if ((r & 0x30) == 0) {
         return;
      }

      switch (kind) {
         case 0 -> {
            c.fill(nx, ground - 2, nx + 1, ground, 0xFFC2B04A);
            c.fill(nx + 2, ground - 3, nx + 3, ground, 0xFFC2B04A);
         }
         case 1 -> {
            c.fill(nx, ground - 2, nx + 1, ground, 0xFF3E7A35);
            c.fill(nx + 2, ground - 3, nx + 3, ground, 0xFF3E7A35);
            if ((r & 0x400) != 0) {
               c.fill(nx + 5, ground - 2, nx + 6, ground - 1, 0xFFE5484D);
            }
         }
         case 2 -> {
            if ((r & 0xC00) == 0) {
               c.fill(nx, ground - 9, nx + 2, ground, 0xFF2F6B3A);
               c.fill(nx - 2, ground - 6, nx, ground - 5, 0xFF2F6B3A);
               c.fill(nx - 2, ground - 8, nx - 1, ground - 5, 0xFF2F6B3A);
               c.fill(nx + 2, ground - 5, nx + 4, ground - 4, 0xFF2F6B3A);
               c.fill(nx + 3, ground - 7, nx + 4, ground - 4, 0xFF2F6B3A);
            }
         }
         case 3 -> c.fill(nx, ground - 1, nx + 4, ground, 0xFFFFFFFF);
         default -> {
            if ((r & 0x400) != 0) {
               c.fill(nx, ground - 3, nx + 1, ground, 0xFF6B4A2A);
               c.fill(nx - 1, ground - 4, nx, ground - 3, 0xFF6B4A2A);
               c.fill(nx + 1, ground - 4, nx + 2, ground - 3, 0xFF6B4A2A);
            }
         }
      }
   }

   static void dust(Pixels c, int x, int ground, double t) {
      for (int k = 0; k < 5; k++) {
         double age = (t * 2.5 + k / 5.0) % 1.0;
         int px = x - (int)(age * 18);
         int py = ground - 2 - (int)(age * 5);
         int alpha = (int)((1.0 - age) * 0x90);
         int size = age < 0.5 ? 2 : 1;
         c.fill(px, py, px + size, py + size, alpha << 24 | 0xD8C8A0);
      }
   }

   static void sprite(Pixels c, String[] rows, int x, int y) {
      for (int r = 0; r < rows.length; r++) {
         String row = rows[r];
         for (int col = 0; col < row.length(); col++) {
            int color = color(row.charAt(col));
            if (color != 0) {
               c.fill(x + col * S, y + r * S, x + col * S + S, y + r * S + S, color);
            }
         }
      }
   }

   static int color(char pixel) {
      return switch (pixel) {
         case 'o' -> 0xFFF08A24;
         case 'd' -> 0xFF7A3E10;
         case 'k' -> 0xFF15110E;
         case 'w' -> 0xFFF6D7A8;
         case 'g' -> 0xFF5BBF4A;
         case 'G' -> 0xFF2F7A2A;
         case 'e' -> 0xFF101010;
         default -> 0;
      };
   }

   /** A scene's own time: real time times a speed factor (0 stands still), so a change of speed does not jump. */
   /**
    * The scene as one texture on a panel ({@link Pixels}): drawn again at most 30 times a second, and not while it stands
    * still; each frame only draws the texture.
    */
   static final class View {
      private static final long FRAME_NANOS = 1_000_000_000L / 30;
      private Pixels pixels;
      private double drawnTime = Double.NaN;
      private long drawnAt;

      void draw(Canvas canvas, int x, int y, int w, int h, double t) {
         if (this.pixels == null) {
            this.pixels = new Pixels(w, h);
         } else if (this.pixels.width() != w || this.pixels.height() != h) {
            this.pixels.resize(w, h);
            this.drawnTime = Double.NaN;
         }

         long now = System.nanoTime();
         if (t != this.drawnTime && (Double.isNaN(this.drawnTime) || now - this.drawnAt >= FRAME_NANOS)) {
            ChaseScene.draw(this.pixels, 0, 0, w, h, t);
            this.pixels.version++;
            this.drawnTime = t;
            this.drawnAt = now;
         }

         canvas.pixels(this.pixels, x, y);
      }
   }

   static final class Clock {
      private double time = 1.0;
      private long last;

      /** Advances by the time since the last call times the factor; returns the scene time. */
      double advance(double factor) {
         long now = System.nanoTime();
         if (this.last != 0L) {
            // A long gap (the screen not drawn) counts as a short one.
            this.time += Math.min(0.1, (now - this.last) / 1.0e9) * Math.max(0.0, factor);
         }

         this.last = now;
         return this.time;
      }
   }
}
