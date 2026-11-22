package com.wgaccel.client;

/**
 * A blocky cheetah (Minecraft-style model: rigid textured boxes on pivots), running like a cat: a bound, the hind legs
 * pushing together, the spine stretching then arching. Units: model pixels (ChaseScene.S screen pixels each); y down; a
 * positive angle turns clockwise on screen. The cheetah faces right.
 */
final class BlockCheetah {
   /** o coat, l pale (belly, muzzle), d spot, k black. */
   static final String[] REAR = {
      "ooooooooo",
      "odoooodoo",
      "ooodooood",
      "odooooooo",
      "lllllllll"
   };
   static final String[] FRONT = {
      "ooooooooo",
      "oodoooodo",
      "ooooodooo",
      "odooooooo",
      "oooodoool",
      "llllllll."
   };
   static final String[] HEAD = {
      "oooooo",
      "oooko.",
      "ooodol",
      "oooldl",
      "llllll"
   };
   static final String[] SNOUT = {"lk", "ll"};
   static final String[] EAR = {"o", "d"};
   static final String[] TAIL_A = {"oooo", "oood"};
   static final String[] TAIL_B = {"odoo", "oooo"};
   static final String[] TAIL_C = {"dddk", "dkkk"};
   static final String[] THIGH = {
      "oooo",
      "oood",
      "oooo",
      "odoo",
      "oooo"
   };
   static final String[] SHIN = {"oo", "oo", "oo", "od", "oo"};
   static final String[] UPPER = {"oo", "od", "oo", "oo"};
   static final String[] LOWER = {"oo", "oo", "oo", "ol"};
   static final String[] PAW = {"lll"};

   /** An affine frame: a world point for a local one. */
   record Frame(double x, double y, double angle) {
      Frame child(double px, double py, double turn) {
         double c = Math.cos(this.angle);
         double s = Math.sin(this.angle);
         return new Frame(this.x + px * c - py * s, this.y + px * s + py * c, this.angle + turn);
      }
   }

   private BlockCheetah() {
   }

   /** The cheetah with its feet on the ground line (screen y), its rear at about x, at run phase p (0-1). */
   static void draw(Pixels c, int x, int ground, double p, int scale) {
      // First the pose with the waist at 0, only measured: the lowest point (a paw); then drawn with that paw on the
      // ground, less the flight's lift.
      Painter measure = new Painter(null, 0, 0, 1, false);
      pose(measure, measure, p, 0.0);
      double tau = Math.PI * 2;
      double lift = 0.8 * Math.max(0, Math.cos(2 * tau * (p - 0.3)));
      pose(new Painter(c, x, ground, scale, true), new Painter(c, x, ground, scale, false), p, -(measure.lowest - 0.5) - lift);
   }

   /** The pose at run phase p with the waist at height waistY (model pixels from the ground line). */
   static void pose(Painter far, Painter near, double p, double waistY) {
      double tau = Math.PI * 2;
      double e = Math.cos(tau * (p - 0.3));
      // 1 stretched (p 0.3), 0 bunched (p 0.8).
      double stretch = (1 + e) / 2;
      double arch = 1 - stretch;
      double alpha = -0.08 + 0.42 * arch;
      Frame waist = new Frame(22.0, waistY, 0);
      Frame rear = waist.child(0, 0, -alpha);
      Frame front = waist.child(0, 0, alpha);

      double hindFwd = -0.95 * e;
      double hindBend = 0.35 + 0.75 * arch;
      double frontFwd = 1.0 * e;
      double u = ((p - 0.55) % 1 + 1) % 1 / 0.6;
      double frontBend = u <= 1 ? 0.1 + 1.5 * Math.sin(Math.PI * u) : 0.1;

      // Far legs (darker), a little out of step.
      hind(far, rear, hindFwd - 0.18, hindBend + 0.05);
      foreleg(far, front, frontFwd - 0.2, frontBend * 0.9);
      // The tail: three boxes, back and up, waving.
      // The tail streams out behind (its balance), the tip curling up.
      Frame t0 = rear.child(-9, -1.6, 0.18 + 0.08 * Math.sin(tau * p));
      near.box(t0, -4, -1, TAIL_A);
      Frame t1 = t0.child(-4, 0, -0.05 + 0.08 * Math.sin(tau * p - 1));
      near.box(t1, -4, -1, TAIL_B);
      Frame t2 = t1.child(-4, 0, 0.35 + 0.12 * Math.sin(tau * p - 2));
      near.box(t2, -4, -1, TAIL_C);
      // The torso.
      near.box(rear, -9, -2.5, REAR);
      near.box(front, -0.5, -3, FRONT);
      // The head, kept about level.
      Frame head = front.child(7.8, -2.2, -alpha * 0.9);
      near.box(head.child(1.5, -3.4, 0), 0, 0, EAR);
      near.box(head.child(3.5, -3.4, 0), 0, 0, EAR);
      near.box(head, 0, -2.6, HEAD);
      near.box(head, 6, -0.6, SNOUT);
      // Near legs.
      hind(near, rear, hindFwd, hindBend);
      foreleg(near, front, frontFwd, frontBend);
   }

   /** A hind leg from the hip: a big thigh, a thin shin bent back at the hock, the foot. */
   static void hind(Painter painter, Frame rear, double fwd, double bend) {
      Frame thigh = rear.child(-6.5, 1.0, -fwd);
      painter.box(thigh, -2, -1, THIGH);
      Frame shin = thigh.child(0, 3.6, bend);
      painter.box(shin, -1, 0, SHIN);
      Frame paw = shin.child(0, 4.6, -bend - (-fwd) * 0.6);
      painter.box(paw, -1, 0, PAW);
   }

   /** A foreleg from the shoulder: upper and lower boxes, the lower folding back as it lifts, the paw. */
   static void foreleg(Painter painter, Frame front, double fwd, double bend) {
      Frame upper = front.child(6.5, 1.8, -fwd);
      painter.box(upper, -1, -1, UPPER);
      Frame lower = upper.child(0, 3.0, bend);
      painter.box(lower, -1, 0, LOWER);
      Frame paw = lower.child(0, 3.8, -bend * 0.5);
      painter.box(paw, -1, 0, PAW);
   }

   /** Draws textured boxes: each screen cell in the box's bounds takes the texel its centre falls in. */
   static final class Painter {
      final Pixels c;
      final int x;
      final int ground;
      final int scale;
      final boolean dark;
      /** Measuring (no canvas): the lowest model y any box reaches. */
      double lowest = -Double.MAX_VALUE;

      Painter(Pixels c, int x, int ground, int scale, boolean dark) {
         this.c = c;
         this.x = x;
         this.ground = ground;
         this.scale = scale;
         this.dark = dark;
      }

      /** A box whose texture's top left texel sits at (u0, v0) in the frame, one texel per model pixel. */
      void box(Frame f, double u0, double v0, String[] texture) {
         int w = texture[0].length();
         int h = texture.length;
         double cos = Math.cos(f.angle());
         double sin = Math.sin(f.angle());
         double minX = Double.MAX_VALUE;
         double minY = Double.MAX_VALUE;
         double maxX = -Double.MAX_VALUE;
         double maxY = -Double.MAX_VALUE;
         for (int i = 0; i < 4; i++) {
            double lu = u0 + (i & 1) * w;
            double lv = v0 + (i >> 1) * h;
            double wx = f.x() + lu * cos - lv * sin;
            double wy = f.y() + lu * sin + lv * cos;
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minY = Math.min(minY, wy);
            maxY = Math.max(maxY, wy);
         }

         if (this.c == null) {
            this.lowest = Math.max(this.lowest, maxY);
            return;
         }

         for (int gy = (int)Math.floor(minY); gy <= (int)Math.ceil(maxY); gy++) {
            for (int gx = (int)Math.floor(minX); gx <= (int)Math.ceil(maxX); gx++) {
               double dx = gx + 0.5 - f.x();
               double dy = gy + 0.5 - f.y();
               double lu = dx * cos + dy * sin - u0;
               double lv = -dx * sin + dy * cos - v0;
               if (lu < 0 || lv < 0 || lu >= w || lv >= h) {
                  continue;
               }

               int color = color(texture[(int)lv].charAt((int)lu));
               if (color == 0) {
                  continue;
               }

               if (this.dark) {
                  color = darker(color);
               }

               int sx = this.x + gx * this.scale;
               int sy = this.ground + gy * this.scale;
               this.c.fill(sx, sy, sx + this.scale, sy + this.scale, color);
            }
         }
      }
   }

   static int darker(int argb) {
      int r = (argb >> 16 & 255) * 3 / 4;
      int g = (argb >> 8 & 255) * 3 / 4;
      int b = (argb & 255) * 3 / 4;
      return argb & 0xFF000000 | r << 16 | g << 8 | b;
   }

   static int color(char ch) {
      return switch (ch) {
         case 'o' -> 0xFFE8962E;
         case 'l' -> 0xFFF6D9A6;
         case 'd' -> 0xFF5A3410;
         case 'k' -> 0xFF15110E;
         default -> 0;
      };
   }
}
