package com.wgaccel.mixin.compat;

import com.wgaccel.gen.LandformMemo;
import java.util.function.IntBinaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conquest ReTerraForged's landform function handed out remembered per column ({@link LandformMemo}). Its api classes
 * keep their names (the rest is obfuscated); optional, so nothing happens without Conquest.
 */
@Pseudo
@Mixin(targets = "conquest.reterraforged.api.ConquestLandforms")
public abstract class ConquestLandformsMixin {
   @Inject(method = "apply(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/function/IntBinaryOperator;", at = @At("RETURN"), cancellable = true, require = 0)
   private void wgaccel$remembered(CallbackInfoReturnable<IntBinaryOperator> info) {
      info.setReturnValue(LandformMemo.wrap(info.getReturnValue()));
   }
}
