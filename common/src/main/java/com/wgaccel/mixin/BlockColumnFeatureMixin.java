package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.FastPredicates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Evaluates {@link BlockColumnFeature}'s column {@code allowedPlacement} checks via {@link FastPredicates} within one
 * shared per-column {@link FastPredicates.EvalContext}, avoiding repeated chunk lookups across layer height checks.
 */
@Mixin(BlockColumnFeature.class)
public abstract class BlockColumnFeatureMixin {
   @WrapMethod(method = "place", require = 0)
   private boolean wgaccel$placeScope(FeaturePlaceContext<BlockColumnConfiguration> context, Operation<Boolean> original) {
      if (!FastPredicates.on()) {
         return original.call(context);
      }

      FastPredicates.EvalContext ctx = FastPredicates.begin(context.level());
      try {
         return original.call(context);
      } finally {
         ctx.end();
      }
   }

   @Redirect(method = "place", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"), require = 0)
   private boolean wgaccel$allowedPlacement(BlockPredicate predicate, Object level, Object pos) {
      return FastPredicates.testOnce(predicate, (WorldGenLevel)level, (BlockPos)pos);
   }
}
