package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.PredicateReads;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
import org.spongepowered.asm.mixin.Mixin;

/** One rule-based block choice (all its rules' tests) = one read scope ({@link PredicateReads}). */
@Mixin(RuleBasedStateProvider.class)
public abstract class RuleBasedStateProviderMixin {
   @WrapMethod(method = "getState", require = 0)
   private BlockState wgaccel$scope(WorldGenLevel level, RandomSource random, BlockPos pos, Operation<BlockState> original) {
      if (level instanceof PredicateReads.Holder holder) {
         PredicateReads.Scope scope = holder.wgaccel$predicateReads();
         if (scope.begin()) {
            try {
               return original.call(level, random, pos);
            } finally {
               scope.end();
            }
         }
      }

      return original.call(level, random, pos);
   }
}
