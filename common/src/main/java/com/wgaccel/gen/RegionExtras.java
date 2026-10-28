package com.wgaccel.gen;

/**
 * One object of a version layer's per world-generation region (a mixin on the region; the 26.1.2 layer's Lithos noise
 * table, made when the features region is: Mc.featuresStart).
 */
public interface RegionExtras {
   Object wgaccel$extra();

   void wgaccel$extra(Object extra);
}
