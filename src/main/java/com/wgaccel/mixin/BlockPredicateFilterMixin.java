package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.PredicateReads;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;

/** One placement filter's predicate evaluation = one read scope ({@link PredicateReads}). */
@Mixin(BlockPredicateFilter.class)
public abstract class BlockPredicateFilterMixin {
   @WrapMethod(method = "shouldPlace", require = 0)
   private boolean wgaccel$scope(PlacementContext context, RandomSource random, BlockPos pos, Operation<Boolean> original) {
      if (context.getLevel() instanceof PredicateReads.Holder holder) {
         PredicateReads.Scope scope = holder.wgaccel$predicateReads();
         if (scope.begin()) {
            try {
               return original.call(context, random, pos);
            } finally {
               scope.end();
            }
         }
      }

      return original.call(context, random, pos);
   }
}
