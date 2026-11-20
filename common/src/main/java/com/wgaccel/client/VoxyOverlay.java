package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.gen.VoxyProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;

/**
 * The Voxy LOD generator's progress in the game (config voxyProgress): a panel in a corner of the screen and lines in
 * the F3 screen. It reads what the generator last published ({@link VoxyProgress}; the integrated server runs in the
 * same game, so nothing shows when playing on a dedicated server).
 *
 * <p>The panel: a dark card with a stripe in the state's colour, the title and the rate, then one bar per ring (red
 * while being made, green when done, grey until its turn) with its radius, detail and percentage, and a last line with
 * the time left, or why the generator waits.
 */
public final class VoxyOverlay {
   /** The panel's narrowest and widest (it is as wide as its longest line). */
   private static final int MIN_WIDTH = 170;
   private static final int MAX_WIDTH = 320;
   private static final int MARGIN = 6;
   private static final int PAD = 6;
   private static final int LINE = 10;
   private static final int BAR = 3;
   private static final int CARD = 0xC0101216;
   private static final int EDGE = 0x30FFFFFF;
   private static final int TRACK = 0x30FFFFFF;
   private static final int TITLE = 0xFFFFFF;
   private static final int TEXT = 0xD0D4DA;
   private static final int DIM = 0x8A9099;
   private static final int ACTIVE = 0xFFE5484D;
   private static final int DONE = 0xFF46A758;
   private static final int PAUSED = 0xFFF5A524;
   private static final int WAITING = 0xFF8A9099;
   /** How long the panel stays after the rings are done (config voxyOverlayHideWhenDone). */
   private static final long DONE_SHOWN_NANOS = 10_000_000_000L;
   private static volatile long doneSince;

   private VoxyOverlay() {
   }

   private static boolean overlayOn() {
      Config.VoxyProgressDisplay display = Config.VOXY_PROGRESS.get();
      return display == Config.VoxyProgressDisplay.OVERLAY || display == Config.VoxyProgressDisplay.BOTH;
   }

   private static boolean f3On() {
      Config.VoxyProgressDisplay display = Config.VOXY_PROGRESS.get();
      return display == Config.VoxyProgressDisplay.F3 || display == Config.VoxyProgressDisplay.BOTH;
   }

   /** The progress to show, or null (none, or done long enough ago). */
   private static VoxyProgress shown() {
      VoxyProgress progress = VoxyProgress.current();
      if (progress == null) {
         doneSince = 0;
         return null;
      }

      if (progress.state() != VoxyProgress.State.DONE) {
         doneSince = 0;
         return progress;
      }

      long now = System.nanoTime();
      if (doneSince == 0) {
         doneSince = now;
      }

      return Config.VOXY_OVERLAY_HIDE_WHEN_DONE.get() && now - doneSince > DONE_SHOWN_NANOS ? null : progress;
   }

   /** The panel, from the game's HUD (not while F1 hides the HUD; not in the corner F3 uses while it is open). */
   public static void render(Canvas canvas, Font font, int screenWidth, int screenHeight, boolean debugShown) {
      try {
         if (!overlayOn()) {
            return;
         }

         VoxyProgress progress = shown();
         if (progress == null) {
            return;
         }

         Config.VoxyOverlayCorner corner = Config.VOXY_OVERLAY_CORNER.get();
         boolean left = corner == Config.VoxyOverlayCorner.TOP_LEFT || corner == Config.VoxyOverlayCorner.BOTTOM_LEFT;
         boolean top = corner == Config.VoxyOverlayCorner.TOP_LEFT || corner == Config.VoxyOverlayCorner.TOP_RIGHT;
         if (debugShown && top) {
            // F3's text covers the top corners; its lines carry the progress then.
            return;
         }

         int rings = progress.rings().size();
         int height = PAD + LINE + 2 + rings * (LINE + BAR + 4) + LINE + PAD - 2 + (progress.extra().isEmpty() ? 0 : LINE);
         int width = width(font, progress);
         int x = left ? MARGIN : screenWidth - width - MARGIN;
         int y = top ? MARGIN : screenHeight - height - MARGIN - 40;
         draw(canvas, font, progress, x, y, width, height);
      } catch (RuntimeException failure) {
         // Never break the HUD.
      }
   }

   private static String rate(VoxyProgress progress) {
      return switch (progress.state()) {
         case RUNNING -> String.format(Locale.ROOT, "%,.0f chunks/s", progress.chunksPerSecond());
         case PAUSED -> "paused";
         case WAITING -> "waiting";
         case DONE -> "done";
         case STOPPED -> "stopped";
      };
   }

   private static String label(int inner, VoxyProgress.Ring ring) {
      return String.format(Locale.ROOT, "%d-%d chunks  %s", inner, ring.radius(), ring.detail());
   }

   private static String percent(VoxyProgress.Ring ring) {
      return String.format(Locale.ROOT, "%.0f%%", 100.0 * ring.fraction());
   }

   private static String footer(VoxyProgress progress) {
      return switch (progress.state()) {
         case RUNNING -> String.format(Locale.ROOT, "%,d / %,d chunks   ETA %s", progress.done(), progress.total(), VoxyProgress.duration(progress.etaSeconds()));
         case PAUSED, WAITING, STOPPED -> progress.why();
         case DONE -> String.format(Locale.ROOT, "%,d chunks", progress.total());
      };
   }

   /** As wide as the longest line (a gap of 12 between a line's left and right parts), within MIN_WIDTH-MAX_WIDTH. */
   private static int width(Font font, VoxyProgress progress) {
      int widest = font.width("Voxy LODs") + 12 + font.width(rate(progress));
      int inner = 0;
      for (VoxyProgress.Ring ring : progress.rings()) {
         widest = Math.max(widest, font.width(label(inner, ring)) + 12 + font.width(percent(ring)));
         inner = ring.radius();
      }

      widest = Math.max(widest, font.width(footer(progress)));
      if (!progress.extra().isEmpty()) {
         widest = Math.max(widest, font.width(progress.extra()));
      }

      return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest + 2 * PAD + 2));
   }

   private static void draw(Canvas canvas, Font font, VoxyProgress progress, int x, int y, int width, int height) {
      int accent = switch (progress.state()) {
         case RUNNING -> ACTIVE;
         case PAUSED -> PAUSED;
         case WAITING -> WAITING;
         case DONE -> DONE;
         case STOPPED -> PAUSED;
      };
      // The card, its edge and the state's stripe.
      canvas.fill(x, y, x + width, y + height, CARD);
      canvas.fill(x, y, x + width, y + 1, EDGE);
      canvas.fill(x, y + height - 1, x + width, y + height, EDGE);
      canvas.fill(x + width - 1, y, x + width, y + height, EDGE);
      canvas.fill(x, y, x + 2, y + height, accent);
      int left = x + PAD + 2;
      int right = x + width - PAD;
      int line = y + PAD;
      canvas.text(font, "Voxy LODs", left, line, TITLE);
      String rate = rate(progress);
      canvas.text(font, rate, right - font.width(rate), line, accent & 0xFFFFFF);
      line += LINE + 2;
      int current = progress.currentRing();
      int inner = 0;
      for (int i = 0; i < progress.rings().size(); i++) {
         VoxyProgress.Ring ring = progress.rings().get(i);
         boolean ringDone = ring.done() >= ring.total();
         int color = ringDone ? DONE : i == current && progress.state() != VoxyProgress.State.WAITING ? ACTIVE : WAITING;
         String label = label(inner, ring);
         String percent = percent(ring);
         canvas.text(font, label, left, line, i == current ? TEXT : DIM);
         canvas.text(font, percent, right - font.width(percent), line, i == current ? TEXT : DIM);
         line += LINE;
         int barWidth = right - left;
         canvas.fill(left, line, right, line + BAR, TRACK);
         int filled = (int)Math.round(barWidth * ring.fraction());
         if (filled > 0) {
            canvas.fill(left, line, left + filled, line + BAR, color);
         }

         line += BAR + 4;
         inner = ring.radius();
      }

      canvas.text(font, trim(font, footer(progress), right - left), left, line, progress.state() == VoxyProgress.State.STOPPED ? TEXT : DIM);
      if (!progress.extra().isEmpty()) {
         line += LINE;
         canvas.text(font, trim(font, progress.extra(), right - left), left, line, DIM);
      }
   }

   private static String trim(Font font, String text, int width) {
      if (font.width(text) <= width) {
         return text;
      }

      String cut = text;
      while (!cut.isEmpty() && font.width(cut + "...") > width) {
         cut = cut.substring(0, cut.length() - 1);
      }

      return cut + "...";
   }

   /**
    * The F3 lines (config voxyProgress F3 or BOTH; the right column, with Minecraft's colour codes), or none: the title
    * and state, then a line per ring with its percentage.
    */
   public static List<String> debugLines() {
      List<String> lines = new ArrayList<>();
      try {
         if (!f3On()) {
            return lines;
         }

         VoxyProgress progress = VoxyProgress.current();
         if (progress == null) {
            return lines;
         }

         // White text; the rate white, a paused state gold, waiting grey, done green; a ring's percentage yellow until done
         // (green), the time left aqua.
         String state = switch (progress.state()) {
            case RUNNING -> "\u00a7f" + String.format(Locale.ROOT, "%,.0f chunks/s", progress.chunksPerSecond());
            case PAUSED -> "\u00a76paused" + (progress.why().isEmpty() ? "" : " \u00a7f(" + progress.why() + ")");
            case WAITING -> "\u00a77waiting" + (progress.why().isEmpty() ? "" : " (" + progress.why() + ")");
            case DONE -> "\u00a7adone";
            case STOPPED -> "\u00a76stopped" + (progress.why().isEmpty() ? "" : " \u00a7f(" + progress.why() + ")");
         };
         lines.add("\u00a76[WGA] Voxy LODs\u00a7r " + state);
         int inner = 0;
         for (VoxyProgress.Ring ring : progress.rings()) {
            String color = ring.done() >= ring.total() ? "\u00a7a" : "\u00a7e";
            lines.add(String.format(Locale.ROOT, "\u00a7f%d-%d chunks, %s: %s%.0f%%", inner, ring.radius(), ring.detail(), color, 100.0 * ring.fraction()));
            inner = ring.radius();
         }

         if (progress.state() == VoxyProgress.State.RUNNING) {
            lines.add(String.format(Locale.ROOT, "\u00a7f%,d / %,d chunks, \u00a7bETA %s\u00a7f, %d threads", progress.done(), progress.total(),
               VoxyProgress.duration(progress.etaSeconds()), progress.threads()));
         }

         if (!progress.extra().isEmpty()) {
            lines.add("\u00a7f" + progress.extra());
         }
      } catch (RuntimeException failure) {
         // Never break F3.
      }

      return lines;
   }
}
