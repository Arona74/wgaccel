package com.wgaccel.ftf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * When a summary is due: at counts that grow by a factor (the next threshold in next). The threshold is read once, so
 * threads arriving together move it one step only; it stops at Integer.MAX_VALUE. Until 0.17.3 it was read twice: many
 * threads at once pushed it past the int range to 0, after which every call logged (thousands of lines a second).
 */
final class Due {
   private Due() {
   }

   static boolean due(AtomicInteger next, long count, int factor) {
      int at = next.get();
      return at > 0 && count >= at && next.compareAndSet(at, (int)Math.min(Integer.MAX_VALUE, (long)at * factor));
   }
}
