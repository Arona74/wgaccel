package com.wgaccel.mixin.compat;

import com.wgaccel.gen.LithosRocks;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithos 1.0.0's rock type of a block handed out from {@link LithosRocks}: a kept answer at HEAD; Lithos's own (a miss,
 * or a compared one) kept or compared at RETURN. Obfuscated names: optional, and the descriptor names Lithos's rock
 * class, so another form of the function does not match (LithosRocks logs when the first answer is kept).
 */
@Pseudo
@Mixin(targets = "com.conquest.lithos.worldgen.e")
public abstract class LithosRocksMixin {
   @Inject(method = "a(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/block/Block;)Lcom/conquest/lithos/worldgen/d;",
      at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$kept(WorldGenLevel level, Block block, CallbackInfoReturnable<Object> info) {
      Object kept = LithosRocks.kept(block);
      if (kept != null && !LithosRocks.compared()) {
         info.setReturnValue(LithosRocks.answer(kept));
      }
   }

   @Inject(method = "a(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/block/Block;)Lcom/conquest/lithos/worldgen/d;",
      at = @At("RETURN"), require = 0)
   private static void wgaccel$made(WorldGenLevel level, Block block, CallbackInfoReturnable<Object> info) {
      LithosRocks.made(block, info.getReturnValue());
   }
}
