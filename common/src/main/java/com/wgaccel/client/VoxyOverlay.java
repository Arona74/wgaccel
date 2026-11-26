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
 * <p>The panel: a dark card with a stripe in the state's colour, the title and the rate, the game's CPU use, the GPU's
 * use and the GPU API the mod runs on, then one bar per ring (yellow while being made, green when done, grey until its
 * turn) with its radius, detail and percentage, and a last line with the time left, or why the generator waits.
 */
public final class VoxyOverlay {
   /** The panel's narrowest and widest (it is as wide as its longest line). */
   private static final int MIN_WIDTH = 200;
   private static final int MAX_WIDTH = 360;
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
   private static final int ACTIVE = 0xFFFFE629;
   private static final int DONE = 0xFF46A758;
   private static final int PAUSED = 0xFFF5A524;
   private static final int WAITING = 0xFF8A9099;
   /** How long the panel stays after the rings are done (config voxyOverlayHideWhenDone). */
   private static final long DONE_SHOWN_NANOS = 10_000_000_000L;
   private static final String TITLE_TEXT = "WGA Voxy LODs Generator";
   /** The chase scene's height (config voxyOverlayAnimation), under the title row. */
   private static final int SCENE = 40;
   /** The scene's own time: it runs at the generator's speed against the normal one for the ring's detail. */
   private static final ChaseScene.Clock CLOCK = new ChaseScene.Clock();
   private static final ChaseScene.View VIEW = new ChaseScene.View();
   /** The usage colours: green (low) to yellow (80%), orange from 80%, red from 95%. */
   private static final int LOW = 0x46A758;
   private static final int HIGH = 0xFFE629;
   private static final int VERY_HIGH = 0xF76B15;
   private static final int FULL = 0xE5484D;
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

         Config.VoxyOverlayCorner corner = Config.VOXY_OVERLAY_CORNER.get();
         boolean left = corner == Config.VoxyOverlayCorner.TOP_LEFT || corner == Config.VoxyOverlayCorner.BOTTOM_LEFT;
         boolean top = corner == Config.VoxyOverlayCorner.TOP_LEFT || corner == Config.VoxyOverlayCorner.TOP_RIGHT;
         if (debugShown && top) {
            // F3's text covers the top corners; its lines carry the progress then.
            return;
         }

         VoxyProgress progress = shown();
         if (progress == null) {
            // On a server our generator does not run: the LODs come from the server's Voxy Server Side, if it has it.
            VssStatus.View vss = vssShown();
            if (vss != null) {
               List<Row> rows = vssRows(vss);
               int width = vssWidth(font, rows);
               int height = PAD + vssHeight(rows) + PAD - 2;
               int x = left ? MARGIN : screenWidth - width - MARGIN;
               int y = top ? MARGIN : screenHeight - height - MARGIN - 40;
               drawVss(canvas, font, vss, rows, x, y, width, height);
            }

            return;
         }

         int rings = progress.rings().size();
         int height = PAD + LINE + 2 + sceneRow() + LINE + 2 + rings * (LINE + BAR + 4) + LINE + PAD - 2 + (progress.extra().isEmpty() ? 0 : LINE);
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

   /** The scene's row (and the gap under it), or none when the scene is off. */
   private static int sceneRow() {
      return Config.VOXY_OVERLAY_ANIMATION.get() ? SCENE + 2 : 0;
   }

   /**
    * How fast the scene runs: chunks/s against the normal speed for the current ring's detail (300 chunks/s at full
    * detail and strides up to 16, which cost the same per chunk; 1,500 at stride 32, 3,000 at 64, twice for each step
    * after), within 0.25 - 4; standing still unless the generator runs.
    */
   private static double speed(VoxyProgress progress) {
      if (progress.state() != VoxyProgress.State.RUNNING) {
         return 0.0;
      }

      int current = progress.currentRing();
      int stride = current >= 0 && current < progress.rings().size() ? progress.rings().get(current).stride() : 1;
      double normal = stride <= 16 ? 300.0 : 1500.0 * stride / 32.0;
      return Math.max(0.25, Math.min(4.0, progress.chunksPerSecond() / normal));
   }

   private static volatile long cpuSampledAt;
   private static volatile double cpuSample = -1.0;

   /**
    * The game's CPU use: the process's share of all the CPU's threads, 0-100, or -1 when not known. Asked of the system
    * once a second (a slow call on Windows; the panel and F3 read it every frame).
    */
   private static double cpuPercent() {
      long now = System.nanoTime();
      if (cpuSampledAt == 0L || now - cpuSampledAt >= 1_000_000_000L) {
         cpuSampledAt = now;
         cpuSample = sampleCpu();
      }

      return cpuSample;
   }

   private static double sampleCpu() {
      try {
         if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            return load < 0 ? -1.0 : 100.0 * load;
         }
      } catch (RuntimeException | LinkageError unknown) {
         // Not known.
      }

      return -1.0;
   }

   private static String usage(double percent) {
      return percent < 0 ? "n/a" : String.format(Locale.ROOT, "%.0f%%", percent);
   }

   /** The usage line's left part: "CPU 42%   GPU 63%". */
   private static String usageLine() {
      return "CPU " + usage(cpuPercent()) + "   GPU " + usage(GpuUsage.percent());
   }

   /** A usage's colour (RGB): green to yellow up to 80%, orange from 80%, red from 95%; grey when not known. */
   private static int usageColor(double percent) {
      if (percent < 0) {
         return DIM;
      } else if (percent >= 95) {
         return FULL;
      } else if (percent >= 80) {
         return VERY_HIGH;
      }

      double f = percent / 80.0;
      int r = (int)Math.round((LOW >> 16 & 255) + f * ((HIGH >> 16 & 255) - (LOW >> 16 & 255)));
      int g = (int)Math.round((LOW >> 8 & 255) + f * ((HIGH >> 8 & 255) - (LOW >> 8 & 255)));
      int b = (int)Math.round((LOW & 255) + f * ((HIGH & 255) - (LOW & 255)));
      return r << 16 | g << 8 | b;
   }

   /** The same colours as Minecraft's colour codes for F3: green, yellow, gold, red; grey when not known. */
   private static String usageCode(double percent) {
      return percent < 0 ? "§7" : percent >= 95 ? "§c" : percent >= 80 ? "§6" : percent >= 50 ? "§e" : "§a";
   }

   /** The GPU API the mod's GPU parts run on (OpenCL, CUDA, HIP), or "GPU off" (GpuBackend probes when loaded: only once running). */
   private static String api() {
      return com.wgaccel.gpu.GpuDevice.running ? com.wgaccel.gpu.GpuBackend.name() : "GPU off";
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
   private static VoxyProgress widthFor;
   private static long widthCpuAt;
   private static int widthCached;

   private static int width(Font font, VoxyProgress progress) {
      // Worked out again only for a new snapshot or CPU sample (its text changes the width), not every frame.
      if (progress == widthFor && cpuSampledAt == widthCpuAt) {
         return widthCached;
      }

      widthCached = measure(font, progress);
      widthFor = progress;
      widthCpuAt = cpuSampledAt;
      return widthCached;
   }

   private static int measure(Font font, VoxyProgress progress) {
      int widest = font.width(TITLE_TEXT) + 12 + font.width(rate(progress));
      widest = Math.max(widest, font.width(usageLine()) + 12 + font.width(api()));
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
      canvas.text(font, TITLE_TEXT, left, line, TITLE);
      String rate = rate(progress);
      canvas.text(font, rate, right - font.width(rate), line, accent & 0xFFFFFF);
      line += LINE + 2;
      if (Config.VOXY_OVERLAY_ANIMATION.get()) {
         // The chase, as wide as the panel's lines.
         VIEW.draw(canvas, left, line, right - left, SCENE, CLOCK.advance(speed(progress)));
         line += SCENE + 2;
      }

      // CPU and GPU use, each value in its colour; the GPU API on the right.
      double cpu = cpuPercent();
      double gpu = GpuUsage.percent();
      int at = left;
      canvas.text(font, "CPU ", at, line, TEXT);
      at += font.width("CPU ");
      canvas.text(font, usage(cpu), at, line, usageColor(cpu));
      at += font.width(usage(cpu));
      canvas.text(font, "   GPU ", at, line, TEXT);
      at += font.width("   GPU ");
      canvas.text(font, usage(gpu), at, line, usageColor(gpu));
      String api = api();
      canvas.text(font, api, right - font.width(api), line, DIM);
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

   // ---- Voxy Server Side: the LODs a server sends (VssStatus) ----

   private static final String VSS_TITLE = "WGA Voxy LODs from the server";
   private static final int WARN = 0xE5484D;
   private static volatile long vssDoneSince;

   /** One line of the VSS card: text left and right (each in its colour); a bar under it when fraction is 0-1. */
   private record Row(String left, int leftColor, String right, int rightColor, double fraction, int barColor) {
      static Row text(String left, int color) {
         return new Row(left, color, "", DIM, -1, 0);
      }
   }

   /** The VSS state to show, or none (no VSS, a server without it, up to date long enough with voxyOverlayHideWhenDone). */
   private static VssStatus.View vssShown() {
      VssStatus.View view = VssStatus.current();
      if (view == null || VssStatus.NO_VSS.equals(view.why())) {
         vssDoneSince = 0;
         return null;
      }

      if (view.state() != VssStatus.State.UP_TO_DATE) {
         vssDoneSince = 0;
         return view;
      }

      long now = System.nanoTime();
      if (vssDoneSince == 0) {
         vssDoneSince = now;
      }

      return Config.VOXY_OVERLAY_HIDE_WHEN_DONE.get() && now - vssDoneSince > DONE_SHOWN_NANOS ? null : view;
   }

   private static int vssAccent(VssStatus.State state) {
      return switch (state) {
         case RECEIVING -> ACTIVE;
         case IDLE -> PAUSED;
         case UP_TO_DATE -> DONE;
         case WAITING -> WAITING;
         case NO_LODS -> 0xFF000000 | WARN;
      };
   }

   private static String vssRate(VssStatus.View view) {
      return switch (view.state()) {
         case RECEIVING -> String.format(Locale.ROOT, "%,.0f columns/s", Math.max(0, view.receiveRate()));
         case IDLE -> "idle";
         case UP_TO_DATE -> "up to date";
         case WAITING -> "joining";
         case NO_LODS -> "no LODs";
      };
   }

   private static String vssHeadline(VssStatus.State state) {
      return switch (state) {
         case RECEIVING -> "Getting Voxy LODs from the server";
         case IDLE -> "VSS connected, no requests";
         case UP_TO_DATE -> "VSS connected, LODs up to date";
         case WAITING -> "Joining the server's Voxy Server Side";
         case NO_LODS -> "No LODs from this server";
      };
   }

   /** The card's lines under the title, in order. */
   private static List<Row> vssRows(VssStatus.View view) {
      List<Row> rows = new ArrayList<>();
      rows.add(Row.text(vssHeadline(view.state()), TEXT));
      if (view.distance() > 0 && view.ring() >= 0) {
         int ring = Math.min(view.ring(), view.distance());
         int color = view.state() == VssStatus.State.UP_TO_DATE ? DONE : view.state() == VssStatus.State.RECEIVING ? ACTIVE : WAITING;
         rows.add(new Row(String.format(Locale.ROOT, "ring %,d of %,d chunks", ring, view.distance()), TEXT,
            String.format(Locale.ROOT, "%.0f%%", 100.0 * view.fraction()), TEXT, view.fraction(), color));
      }

      if (view.held() >= 0) {
         // Waiting: asked, not answered yet (goes up and down as answers come); asks/s: how fast VSS asks (answers
         // "already up to date" included, so above the columns/s).
         String left = view.pending() >= 0 ? String.format(Locale.ROOT, "%,d waiting", view.pending()) : "";
         if (view.requestRate() >= 0) {
            left += String.format(Locale.ROOT, "%s%,.0f asks/s", left.isEmpty() ? "" : ", ", view.requestRate());
         }

         if (view.answerMs() >= 0) {
            left += String.format(Locale.ROOT, "%sanswer %.0f ms", left.isEmpty() ? "" : ", ", view.answerMs());
         }

         String right = view.pingMs() >= 0 ? String.format(Locale.ROOT, "ping %.0f ms", view.pingMs()) : "";
         rows.add(new Row(left, DIM, right, view.pingMs() >= 150 ? VERY_HIGH : DIM, -1, 0));
         rows.add(Row.text(String.format(Locale.ROOT, "%,d columns held, %,d this session", view.held(), Math.max(0, view.session())), DIM));
      }

      if (!view.why().isEmpty()) {
         rows.add(Row.text(view.why(), view.state() == VssStatus.State.NO_LODS ? TEXT : DIM));
      }

      // VSS's cache only says which columns the client has; when Voxy lost them (another storage, deleted), they are
      // never asked again and the LODs stay missing where the player already was.
      if (view.heldAtJoin() > 0 && (view.state() == VssStatus.State.IDLE || view.state() == VssStatus.State.UP_TO_DATE)) {
         rows.add(Row.text("No LODs showing? " + view.command() + " clearcache gets them again", DIM));
      }

      if (!view.warning().isEmpty()) {
         rows.add(Row.text(view.warning(), WARN));
      }

      return rows;
   }

   private static int vssHeight(List<Row> rows) {
      int height = LINE + 2;
      for (Row row : rows) {
         height += row.fraction() >= 0 ? LINE + BAR + 4 : LINE;
      }

      return height;
   }

   private static int vssWidth(Font font, List<Row> rows) {
      int widest = font.width(VSS_TITLE) + 12 + font.width("99,999 columns/s");
      for (Row row : rows) {
         widest = Math.max(widest, font.width(row.left()) + (row.right().isEmpty() ? 0 : 12 + font.width(row.right())));
      }

      return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest + 2 * PAD + 2));
   }

   private static void drawVss(Canvas canvas, Font font, VssStatus.View view, List<Row> rows, int x, int y, int width, int height) {
      int accent = vssAccent(view.state());
      canvas.fill(x, y, x + width, y + height, CARD);
      canvas.fill(x, y, x + width, y + 1, EDGE);
      canvas.fill(x, y + height - 1, x + width, y + height, EDGE);
      canvas.fill(x + width - 1, y, x + width, y + height, EDGE);
      canvas.fill(x, y, x + 2, y + height, accent);
      int left = x + PAD + 2;
      int right = x + width - PAD;
      int line = y + PAD;
      canvas.text(font, VSS_TITLE, left, line, TITLE);
      String rate = vssRate(view);
      canvas.text(font, rate, right - font.width(rate), line, accent & 0xFFFFFF);
      line += LINE + 2;
      for (Row row : rows) {
         int room = right - left - (row.right().isEmpty() ? 0 : font.width(row.right()) + 12);
         canvas.text(font, trim(font, row.left(), room), left, line, row.leftColor());
         if (!row.right().isEmpty()) {
            canvas.text(font, row.right(), right - font.width(row.right()), line, row.rightColor());
         }

         line += LINE;
         if (row.fraction() >= 0) {
            canvas.fill(left, line, right, line + BAR, TRACK);
            int filled = (int)Math.round((right - left) * Math.min(1.0, row.fraction()));
            if (filled > 0) {
               canvas.fill(left, line, left + filled, line + BAR, row.barColor());
            }

            line += BAR + 4;
         }
      }
   }

   /** F3's VSS lines (on a server, when our generator does not run). */
   private static void vssDebugLines(List<String> lines) {
      VssStatus.View view = VssStatus.current();
      if (view == null) {
         return;
      }

      String color = switch (view.state()) {
         case RECEIVING -> "§e";
         case IDLE -> "§6";
         case UP_TO_DATE -> "§a";
         case WAITING -> "§7";
         case NO_LODS -> "§c";
      };
      lines.add("§6WGA Voxy LODs from the server§r " + color + vssRate(view));
      lines.add("§f" + vssHeadline(view.state()));
      if (view.distance() > 0 && view.ring() >= 0) {
         lines.add(String.format(Locale.ROOT, "§fring %,d of %,d chunks: %s%.0f%%", Math.min(view.ring(), view.distance()), view.distance(), color,
            100.0 * view.fraction()));
      }

      if (view.held() >= 0) {
         lines.add(String.format(Locale.ROOT, "§f%,d waiting, %,.0f asks/s, answer %.0f ms, ping %.0f ms", Math.max(0, view.pending()),
            Math.max(0, view.requestRate()), Math.max(0, view.answerMs()), Math.max(0, view.pingMs())));
         lines.add(String.format(Locale.ROOT, "§f%,d columns held, %,d this session", view.held(), Math.max(0, view.session())));
      }

      if (!view.why().isEmpty()) {
         lines.add("§7" + view.why());
      }

      if (!view.warning().isEmpty()) {
         lines.add("§c" + view.warning());
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
            vssDebugLines(lines);
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
         lines.add("\u00a76WGA Voxy LODs Generator\u00a7r " + state);
         double cpu = cpuPercent();
         double gpu = GpuUsage.percent();
         lines.add("\u00a7fCPU " + usageCode(cpu) + usage(cpu) + "\u00a7f, GPU " + usageCode(gpu) + usage(gpu) + "\u00a7f, " + api());
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
