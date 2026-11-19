package com.wgaccel.gen;

import java.util.List;
import java.util.Locale;

/**
 * The Voxy LOD generator's progress as last published (by {@link VoxyAuto} or the {@code /wgaccel lod voxy} command),
 * for the client's panel and F3 lines (the integrated server runs in the same game) and the log. Immutable; null when
 * nothing runs.
 *
 * @param source "auto" or "command"
 * @param state what the generator is doing (RUNNING, PAUSED with why, WAITING for the GPU or Voxy, DONE)
 * @param why the pause's or wait's reason, or ""
 * @param rings the rings, nearest first
 * @param chunksPerSecond the last seconds' rate (chunks covered: written or found already generated)
 * @param threads the threads working
 * @param at when it was published (System.nanoTime)
 */
public record VoxyProgress(String source, State state, String why, List<Ring> rings, double chunksPerSecond, int threads, long at) {
   public enum State {
      RUNNING,
      PAUSED,
      WAITING,
      DONE
   }

   /**
    * @param radius the ring's outer radius in chunks
    * @param stride blocks between the columns worked out (1-16 every chunk; 32-256 interpolated)
    * @param done chunks covered
    * @param total chunks in the ring's tiles
    * @param rate chunks/s at this ring's detail (measured, or estimated from another ring's), 0 when unknown
    */
   public record Ring(int radius, int stride, long done, long total, double rate) {
      public double fraction() {
         return this.total <= 0 ? 1.0 : Math.min(1.0, (double)this.done / this.total);
      }

      /** "full detail", "1 column / 4 blocks", "1 column / 4x4 chunks". */
      public String detail() {
         if (this.stride <= 1) {
            return "full detail";
         }

         if (this.stride <= 16) {
            return "1 column / " + this.stride + " blocks";
         }

         int step = this.stride / 16;
         return "1 column / " + step + "x" + step + " chunks";
      }
   }

   private static volatile VoxyProgress current;

   /** The progress last published, or null. */
   public static VoxyProgress current() {
      return current;
   }

   public static void publish(VoxyProgress progress) {
      current = progress;
   }

   public long done() {
      long done = 0;
      for (Ring ring : this.rings) {
         done += ring.done;
      }

      return done;
   }

   public long total() {
      long total = 0;
      for (Ring ring : this.rings) {
         total += ring.total;
      }

      return total;
   }

   /** The ring being worked on (the first not done), or the last. */
   public int currentRing() {
      for (int i = 0; i < this.rings.size(); i++) {
         if (this.rings.get(i).done < this.rings.get(i).total) {
            return i;
         }
      }

      return this.rings.size() - 1;
   }

   /**
    * Seconds left: each ring's chunks left at its own rate (full detail is about 10x slower than one column per 4 x 4
    * chunks, so one rate for all was far off), the current rate where a ring has none; -1 when unknown.
    */
   public long etaSeconds() {
      double seconds = 0.0;
      for (Ring ring : this.rings) {
         long left = ring.total - ring.done;
         if (left <= 0) {
            continue;
         }

         double rate = ring.rate > 1.0 ? ring.rate : this.chunksPerSecond;
         if (rate < 1.0) {
            return -1;
         }

         seconds += left / rate;
      }

      return (long)Math.ceil(seconds);
   }

   /** "1:02:03", "12:34", "45 s", "--". */
   public static String duration(long seconds) {
      if (seconds < 0) {
         return "--";
      }

      if (seconds < 60) {
         return seconds + " s";
      }

      long h = seconds / 3600;
      long m = seconds / 60 % 60;
      long s = seconds % 60;
      return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
   }

   /** The progress as one log line. */
   public String line() {
      StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "WGA Voxy LOD generator (%s): %s%s, %,.0f chunks/s, %d threads", this.source,
         this.state.name().toLowerCase(Locale.ROOT), this.why.isEmpty() ? "" : " (" + this.why + ")", this.chunksPerSecond, this.threads));
      for (Ring ring : this.rings) {
         text.append(String.format(Locale.ROOT, "; ring %d chunks, %s: %,d of %,d (%.0f%%)", ring.radius, ring.detail(), ring.done, ring.total,
            100.0 * ring.fraction()));
      }

      return text.append("; time left ").append(duration(this.etaSeconds())).toString();
   }
}
