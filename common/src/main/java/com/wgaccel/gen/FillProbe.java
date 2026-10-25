package com.wgaccel.gen;

import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;

/**
 * GPU step G2.1 (measuring for the 3D fill on the GPU): the noise fill's cell corners (the noise the fill interpolates
 * between, worked out a slice of cells at a time), timed in every chunk. Run K also timed each block's state and the
 * aquifer in one chunk of eight; those timers cost about 7 ms per chunk and were taken out once their numbers were in
 * (HANDOFF, run K).
 */
public final class FillProbe {
   private FillProbe() {
   }

   public static void corners(long nanos) {
      Stats.record(Stage.FILL_CORNERS, nanos);
   }
}
