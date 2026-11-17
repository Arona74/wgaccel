package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.LayerGrounds;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** count_on_every_layer's grounds from one scan per column and call ({@link LayerGrounds}). */
@Mixin(CountOnEveryLayerPlacement.class)
public abstract class CountOnEveryLayerMixin {
   @WrapMethod(method = "getPositions")
   private Stream<BlockPos> wgaccel$positions(PlacementContext context, RandomSource random, BlockPos origin, Operation<Stream<BlockPos>> original) {
      Object token = LayerGrounds.begin();
      try {
         return original.call(context, random, origin);
      } finally {
         LayerGrounds.end(token);
      }
   }

   @WrapOperation(method = "getPositions", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/placement/CountOnEveryLayerPlacement;findOnGroundYPosition(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;IIII)I"),
      require = 0)
   private static int wgaccel$ground(PlacementContext context, int x, int y, int z, int layer, Operation<Integer> original) {
      return LayerGrounds.ground(context, x, y, z, layer, () -> original.call(context, x, y, z, layer));
   }
}
