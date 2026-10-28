package com.wgaccel.mixin.compat;

import com.wgaccel.gen.LandformMemo;
import java.util.function.IntBinaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conquest ReTerraForged's column functions handed out remembered per column ({@link LandformMemo}): landforms, surface
 * heights, scree and rivers, each api class's own kind. Its api classes keep their names (the rest is obfuscated);
 * optional, so nothing happens without Conquest.
 */
@Pseudo
@Mixin(targets = {"conquest.reterraforged.api.ConquestLandforms", "conquest.reterraforged.api.ConquestSurface", "conquest.reterraforged.api.ConquestScree",
   "conquest.reterraforged.api.ConquestRivers"})
public abstract class ConquestLandformsMixin {
   @Inject(method = "apply(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/function/IntBinaryOperator;", at = @At("RETURN"), cancellable = true, require = 0)
   private void wgaccel$remembered(CallbackInfoReturnable<IntBinaryOperator> info) {
      info.setReturnValue(LandformMemo.wrap(LandformMemo.Kind.of(this.getClass().getSimpleName()), info.getReturnValue()));
   }
}
