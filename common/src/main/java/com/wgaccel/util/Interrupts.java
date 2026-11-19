package com.wgaccel.util;

/**
 * Whether a failure only means the thread was interrupted (a mod stopping its own threads: Distant Horizons does when a
 * world closes). Such a failure is no reason to turn a GPU part off: that call goes to the CPU and the part stays on.
 */
public final class Interrupts {
   private Interrupts() {
   }

   public static boolean only(Throwable failure) {
      if (Thread.currentThread().isInterrupted()) {
         return true;
      }

      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
         if (cause instanceof InterruptedException || cause instanceof java.nio.channels.ClosedByInterruptException) {
            return true;
         }

         if (cause.getCause() == cause) {
            break;
         }
      }

      return false;
   }
}
