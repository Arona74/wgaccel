package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * MultifaceSpreader.spreadAll (sculk veins spreading over every face, the deep dark's sculk patches) as two plain loops
 * instead of its streams: pre-filters matching axes and missing/present faces before invoking spreadFromFaceTowardDirection.
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

   @WrapMethod(method = "spreadAll", require = 0)
   private long wgaccel$spreadAll(BlockState state, LevelAccessor level, BlockPos pos, boolean postProcess, Operation<Long> original) {
      if (!com.wgaccel.Config.FAST_MULTIFACE_SPREAD.get()) {
         return original.call(state, level, pos, postProcess);
      }

      boolean otherValid = this.config.isOtherBlockValidAsSource(state);
      long spread = 0L;
      for (Direction face : wgaccel$DIRECTIONS) {
         if (this.config.canSpreadFrom(state, face) && (otherValid || this.config.hasFace(state, face))) {
            Direction.Axis faceAxis = face.getAxis();
            for (Direction direction : wgaccel$DIRECTIONS) {
               if (direction.getAxis() == faceAxis) {
                  continue;
               }
               if (!otherValid && this.config.hasFace(state, direction)) {
                  continue;
               }
               if (this.spreadFromFaceTowardDirection(state, level, pos, face, direction, postProcess).isPresent()) {
                  spread++;
               }
            }
         }
      }

      return spread;
   }
}
