package com.wgaccel.gen;

import com.wgaccel.WgaChat;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The sampled self-check of a rewrite whose answer is a pure function of the world (a block test, a column scan, a
 * carve state): about 1 call in {@code period} also asks the game's own method and compares, the game's answer is the
 * one used, and a difference turns the rewrite off for the rest of the session (in chat and the log). 0.64.1's feature
 * rewrites had no checks; these were added in 0.64.2.
 */
public final class FastCheck {
   /** Every check made, for the run report. */
   private static final java.util.List<FastCheck> ALL = new java.util.concurrent.CopyOnWriteArrayList<>();
   private final String name;
   private final int mask;
   private volatile boolean off;
   /** Racy on purpose: a lost increment only moves which call gets compared. */
   private int calls;
   private final LongAdder checked = new LongAdder();
   private final LongAdder different = new LongAdder();
   private final AtomicLong logged = new AtomicLong();

   /** period: a power of two. */
   public FastCheck(String name, int period) {
      if (Integer.bitCount(period) != 1) {
         throw new IllegalArgumentException("period " + period);
      }

      this.name = name;
      this.mask = period - 1;
      ALL.add(this);
   }

   /** The run report's line: each check that compared anything, or null when none did. */
   public static String report() {
      StringBuilder line = new StringBuilder();
      for (FastCheck check : ALL) {
         long checked = check.checked.sum();
         if (checked == 0) {
            continue;
         }

         long differ = check.different.sum();
         line.append(line.length() == 0 ? "" : ", ").append(check.name).append(' ')
            .append(String.format(Locale.ROOT, "%,d", checked)).append(differ == 0 ? " IDENTICAL" : " checked, " + differ + " DIFFER (off)");
      }

      return line.length() == 0 ? null : "WGA   rewrite checks: " + line;
   }

   /** False once a difference was found: callers then take the game's own path. */
   public boolean on() {
      return !this.off;
   }

   /** Whether this call is one of the compared ones. */
   public boolean sample() {
      return (++this.calls & this.mask) == 0;
   }

   /** A compared boolean answer; returns the game's. */
   public boolean same(boolean fast, boolean real, Object where) {
      this.record(fast == real, where, fast, real);
      return real;
   }

   /** A compared answer (equals); returns the game's. */
   public <T> T sameValue(T fast, T real, Object where) {
      this.record(Objects.equals(fast, real), where, fast, real);
      return real;
   }

   private void record(boolean equal, Object where, Object fast, Object real) {
      this.checked.increment();
      if (!equal) {
         this.different.increment();
         if (!this.off) {
            this.off = true;
            WgaChat.warnOff("WGA {} off: at {} it gave {} instead of the game's {}", this.name, String.valueOf(where), fast, real);
         }
      }

      if (this.logged.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(this.summary());
      }
   }

   public String summary() {
      long differ = this.different.sum();
      return String.format(Locale.ROOT, "WGA %s: checked %,d: %s", this.name, this.checked.sum(),
         differ == 0 ? "IDENTICAL" : differ + " DIFFER (off)");
   }
}
