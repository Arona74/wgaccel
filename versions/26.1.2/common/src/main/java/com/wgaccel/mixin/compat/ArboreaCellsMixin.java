package com.wgaccel.mixin.compat;

import com.wgaccel.gen.ForestCells;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conquest of Arborea 1.0.0's forest cell function handed out from {@link ForestCells}: a kept cell at HEAD; Arborea's
 * own answer (a miss, or a compared one) kept or compared at RETURN. Its class names are obfuscated: optional, and the
 * descriptor names the record class, so another form of the function does not match. Arborea 1.0.0 was rebuilt under the
 * same version (1 Nov 2026 jar): the function moved from trees.d.h to trees.d.i (h is now a feature), and this mixin
 * matched nothing without a word (25 Nov 2026 profile: the cell function 7.3% of the CPU, its Random, AtomicLong and
 * record 27% of all allocation). Both names are listed; ForestCells logs when the first cell is kept.
 */
@Pseudo
@Mixin(targets = {"com.conquest.trees.d.h", "com.conquest.trees.d.i"})
public abstract class ArboreaCellsMixin {
   @Inject(method = {"a(JII)Lcom/conquest/trees/d/h$a;", "a(JII)Lcom/conquest/trees/d/i$a;"}, at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$kept(long seed, int x, int z, CallbackInfoReturnable<Object> info) {
      Object kept = ForestCells.kept(seed, x, z);
      if (kept != null && !ForestCells.compared()) {
         info.setReturnValue(kept);
      }
   }

   @Inject(method = {"a(JII)Lcom/conquest/trees/d/h$a;", "a(JII)Lcom/conquest/trees/d/i$a;"}, at = @At("RETURN"), require = 0)
   private static void wgaccel$made(long seed, int x, int z, CallbackInfoReturnable<Object> info) {
      ForestCells.made(seed, x, z, info.getReturnValue());
   }
}
