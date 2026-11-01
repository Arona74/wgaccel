package com.wgaccel.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MultifaceSpreader.spreadAll (sculk veins spreading over every face, the deep dark's sculk patches) as two plain loops
 * instead of its streams: a stream over the faces, and for each a stream over the six directions, with lambdas, Optionals
 * and pipelines made at every call (1 Nov 2026 Conquest profile: sculk patches 5% of all samples, 40% of it spreadAll's
 * stream machinery). The same calls in the same order: the faces in Direction order that canSpreadFrom passes, then for
 * each every direction through spreadFromFaceTowardDirection itself (its own Optional), the successes counted.
 */
@Mixin(MultifaceSpreader.class)
public abstract class MultifaceSpreaderMixin {
   @Unique
   private static final Direction[] wgaccel$DIRECTIONS = Direction.values();
   @Shadow
   @Final
   private MultifaceSpreader.SpreadConfig config;

   @Shadow
   public abstract java.util.Optional<MultifaceSpreader.SpreadPos> spreadFromFaceTowardDirection(BlockState state, LevelAccessor level, BlockPos pos, Direction fromFace,
      Direction spreadDirection, boolean postProcess);

   @Inject(method = "spreadAll", at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$spreadAll(BlockState state, LevelAccessor level, BlockPos pos, boolean postProcess, CallbackInfoReturnable<Long> info) {
      if (!com.wgaccel.Config.FAST_MULTIFACE_SPREAD.get()) {
         return;
      }

      long spread = 0L;
      for (Direction face : wgaccel$DIRECTIONS) {
         if (this.config.canSpreadFrom(state, face)) {
            for (Direction direction : wgaccel$DIRECTIONS) {
               if (this.spreadFromFaceTowardDirection(state, level, pos, face, direction, postProcess).isPresent()) {
                  spread++;
               }
            }
         }
      }

      info.setReturnValue(spread);
   }
}
