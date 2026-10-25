package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.PredicateReads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Each region keeps the reads of its current block-predicate evaluation ({@link PredicateReads}); while one is open, its
 * block reads go through it. Here rather than in StateTestingPredicate.test, which C2ME replaces (0.10.10 crashed at
 * start: "cannot inject into ... merged by ... MixinOffsetPredicate"). Optional (require 0): if another mod replaces
 * getBlockState, this simply does not apply.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionReadsMixin implements PredicateReads.Holder {
   @Unique
   private final PredicateReads.Scope wgaccel$reads = new PredicateReads.Scope();

   @Override
   public PredicateReads.Scope wgaccel$predicateReads() {
      return this.wgaccel$reads;
   }

   @WrapMethod(method = "getBlockState", require = 0)
   private BlockState wgaccel$read(BlockPos pos, Operation<BlockState> original) {
      PredicateReads.Scope scope = this.wgaccel$reads;
      if (scope.open()) {
         BlockState kept = scope.kept(pos);
         if (kept != null) {
            return PredicateReads.sample() ? PredicateReads.verify(kept, original.call(pos), pos) : kept;
         }

         BlockState state = original.call(pos);
         scope.keep(pos, state);
         return state;
      }

      return original.call(pos);
   }
}
