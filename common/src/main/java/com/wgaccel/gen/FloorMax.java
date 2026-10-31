package com.wgaccel.gen;

/**
 * A proto chunk's highest OCEAN_FLOOR_WG height, worked out once (a mixin on ProtoChunk; {@link FastOres#floorMax}).
 * Integer.MIN_VALUE until then.
 */
public interface FloorMax {
   int wgaccel$floorMax();

   void wgaccel$floorMax(int max);
}
