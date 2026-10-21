package com.wgaccel.mixin.check;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.DensityTap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

/** GPU step G2.4's reference: each block's state as the noise fill works it out (aquifer, then ore veins), for a tapped chunk. Check runs only. */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkStateTapMixin {
   @WrapMethod(method = "getInterpolatedState", require = 0)
   private BlockState wgaccel$tap(Operation<BlockState> original) {
      BlockState state = original.call();
      DensityTap.state((NoiseChunk)(Object)this, state);
      return state;
   }
}
