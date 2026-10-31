package com.wgaccel.gen;

/** A proto chunk's fill codes kept for its surface step (a mixin on ProtoChunk; {@link SurfaceAhead#takeCodes}), or null. */
public interface ChunkCodes {
   Object wgaccel$codes();

   void wgaccel$codes(Object codes);
}
