package com.wgaccel.mixin;

import com.wgaccel.gen.PredicateReads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each region keeps the reads of its current block-predicate evaluation ({@link PredicateReads}); while one is open, its
 * block reads go through it. Here rather than in StateTestingPredicate.test, which C2ME replaces (0.10.10 crashed at
 * start: "cannot inject into ... merged by ... MixinOffsetPredicate"). Optional (require 0): if another mod replaces
 * getBlockState, this simply does not apply. A HEAD inject, not a @WrapMethod (which allocated on every block read of
 * every region, scope open or not); the world's own read is getBlockState again with the scope paused.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionReadsMixin implements PredicateReads.Holder {
   @Unique
   private final PredicateReads.Scope wgaccel$reads = new PredicateReads.Scope();

   @Shadow
   public abstract BlockState getBlockState(BlockPos pos);

   @Override
   public PredicateReads.Scope wgaccel$predicateReads() {
      return this.wgaccel$reads;
   }

   @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$read(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
      PredicateReads.Scope scope = this.wgaccel$reads;
      if (!scope.open()) {
         return;
      }

      BlockState kept = scope.kept(pos);
      if (kept != null && !PredicateReads.sample()) {
         cir.setReturnValue(kept);
         return;
      }

      int depth = scope.pause();
      BlockState state;
      try {
         state = this.getBlockState(pos);
      } finally {
         scope.resume(depth);
      }

      if (kept != null) {
         state = PredicateReads.verify(kept, state, pos);
      } else {
         scope.keep(pos, state);
      }

      cir.setReturnValue(state);
   }
}
