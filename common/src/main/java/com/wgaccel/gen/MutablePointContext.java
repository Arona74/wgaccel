package com.wgaccel.gen;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Reusable {@link DensityFunction.FunctionContext} for synchronous grid sampling loops. */
public final class MutablePointContext implements DensityFunction.FunctionContext {
   private int x;
   private int y;
   private int z;

   public MutablePointContext at(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
   }

   @Override
   public int blockX() {
      return this.x;
   }

   @Override
   public int blockY() {
      return this.y;
   }

   @Override
   public int blockZ() {
      return this.z;
   }
}
