package com.wgaccel.mixin;

import com.mojang.serialization.Codec;
import com.wgaccel.Config;
import com.wgaccel.gen.FastGeodePlace;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GeodeFeature.class)
public abstract class GeodeFeatureMixin extends Feature<GeodeConfiguration> {
   @Shadow
   @Final
   private static Direction[] DIRECTIONS;

   private GeodeFeatureMixin(Codec<GeodeConfiguration> codec) {
      super(codec);
   }

   @Inject(method = "place", at = @At("HEAD"), cancellable = true)
   private void wgaccel$fastPlace(FeaturePlaceContext<GeodeConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
      if (!Config.FAST_GEODES.getAsBoolean()) {
         return;
      }
      WorldGenLevel level = context.level();
      Predicate<BlockState> replaceable = isReplaceable(context.config().geodeBlockSettings.cannotReplace);
      cir.setReturnValue(FastGeodePlace.place(context, DIRECTIONS, (pos, state) -> this.safeSetBlock(level, pos, state, replaceable)));
   }
}
