package com.wgaccel.mixin.compat;

import com.wgaccel.gen.LithosNoise;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithos 1.0.0's five noise functions answered from the region's GPU table ({@link LithosNoise}), each with Lithos's own
 * scaling of the samples (a: times 0.22; b: times 0.07; c: above 0.6; d: the first plus 0.35 times the second; e: above
 * 0.55). Its class names are obfuscated: optional, and LithosNoise checks the class's form before any table is made
 * (no table, no answer from here); the compared answers catch any other change.
 */
@Pseudo
@Mixin(targets = "com.conquest.lithos.worldgen.t")
public abstract class LithosNoiseMixin {
   @Inject(method = "a(Lnet/minecraft/world/level/WorldGenLevel;III)D", at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$a(WorldGenLevel level, int x, int y, int z, CallbackInfoReturnable<Double> info) {
      LithosNoise.Table table = LithosNoise.table(level);
      int slot = table != null ? LithosNoise.slot(table, x, y, z) : -1;
      if (slot >= 0) {
         double value = LithosNoise.sample(table, slot, 0) * 0.22;
         info.setReturnValue(LithosNoise.compared() ? LithosNoise.verify(table, 0, value, level, x, y, z) : value);
      }
   }

   @Inject(method = "b(Lnet/minecraft/world/level/WorldGenLevel;III)D", at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$b(WorldGenLevel level, int x, int y, int z, CallbackInfoReturnable<Double> info) {
      LithosNoise.Table table = LithosNoise.table(level);
      int slot = table != null ? LithosNoise.slot(table, x, y, z) : -1;
      if (slot >= 0) {
         double value = LithosNoise.sample(table, slot, 1) * 0.07;
         info.setReturnValue(LithosNoise.compared() ? LithosNoise.verify(table, 1, value, level, x, y, z) : value);
      }
   }

   @Inject(method = "c(Lnet/minecraft/world/level/WorldGenLevel;III)Z", at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$c(WorldGenLevel level, int x, int y, int z, CallbackInfoReturnable<Boolean> info) {
      LithosNoise.Table table = LithosNoise.table(level);
      int slot = table != null ? LithosNoise.slot(table, x, y, z) : -1;
      if (slot >= 0) {
         boolean value = LithosNoise.sample(table, slot, 2) > 0.6;
         info.setReturnValue(LithosNoise.compared() ? LithosNoise.verify(table, 2, value, level, x, y, z) : value);
      }
   }

   @Inject(method = "d(Lnet/minecraft/world/level/WorldGenLevel;III)D", at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$d(WorldGenLevel level, int x, int y, int z, CallbackInfoReturnable<Double> info) {
      LithosNoise.Table table = LithosNoise.table(level);
      int slot = table != null ? LithosNoise.slot(table, x, y, z) : -1;
      if (slot >= 0) {
         double value = LithosNoise.sample(table, slot, 3) + LithosNoise.sample(table, slot, 4) * 0.35;
         info.setReturnValue(LithosNoise.compared() ? LithosNoise.verify(table, 3, value, level, x, y, z) : value);
      }
   }

   @Inject(method = "e(Lnet/minecraft/world/level/WorldGenLevel;III)Z", at = @At("HEAD"), cancellable = true, require = 0)
   private static void wgaccel$e(WorldGenLevel level, int x, int y, int z, CallbackInfoReturnable<Boolean> info) {
      LithosNoise.Table table = LithosNoise.table(level);
      int slot = table != null ? LithosNoise.slot(table, x, y, z) : -1;
      if (slot >= 0) {
         boolean value = LithosNoise.sample(table, slot, 5) > 0.55;
         info.setReturnValue(LithosNoise.compared() ? LithosNoise.verify(table, 4, value, level, x, y, z) : value);
      }
   }
}
