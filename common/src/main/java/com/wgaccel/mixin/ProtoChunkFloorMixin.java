package com.wgaccel.mixin;

import com.wgaccel.gen.ChunkCodes;
import com.wgaccel.gen.FloorMax;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A proto chunk's slots: its highest ground height ({@link FloorMax}, kept by {@link com.wgaccel.gen.FastOres}) and its fill codes for the surface ({@link ChunkCodes}). */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkFloorMixin implements FloorMax, ChunkCodes {
   @Unique
   private int wgaccel$floorMax = Integer.MIN_VALUE;
   @Unique
   private volatile Object wgaccel$codes;

   @Override
   public Object wgaccel$codes() {
      return this.wgaccel$codes;
   }

   @Override
   public void wgaccel$codes(Object codes) {
      this.wgaccel$codes = codes;
   }

   @Override
   public int wgaccel$floorMax() {
      return this.wgaccel$floorMax;
   }

   @Override
   public void wgaccel$floorMax(int max) {
      this.wgaccel$floorMax = max;
   }
}
