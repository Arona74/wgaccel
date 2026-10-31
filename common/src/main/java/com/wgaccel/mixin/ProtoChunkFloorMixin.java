package com.wgaccel.mixin;

import com.wgaccel.gen.FloorMax;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The slot for a proto chunk's highest ground height ({@link FloorMax}, kept by {@link com.wgaccel.gen.FastOres}). */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkFloorMixin implements FloorMax {
   @Unique
   private int wgaccel$floorMax = Integer.MIN_VALUE;

   @Override
   public int wgaccel$floorMax() {
      return this.wgaccel$floorMax;
   }

   @Override
   public void wgaccel$floorMax(int max) {
      this.wgaccel$floorMax = max;
   }
}
