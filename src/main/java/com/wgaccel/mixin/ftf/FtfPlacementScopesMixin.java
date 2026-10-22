package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.FtfPlacementScopes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** FreeTerraForged's placement scopes end with set(null) instead of remove(): the same to every get(), no miss next time ({@link FtfPlacementScopes}). */
@Pseudo
@Mixin(targets = {"etcodehome.freeterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue",
   "etcodehome.freeterraforged.world.worldgen.feature.placement.ChunkLocalFeaturePlacement"})
public abstract class FtfPlacementScopesMixin {
   @WrapOperation(method = "finish(Z)V", at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;remove()V"))
   private static void wgaccel$keepEntry(ThreadLocal<?> local, Operation<Void> original) {
      if (FtfPlacementScopes.on(local)) {
         local.set(null);
      } else {
         original.call(local);
      }
   }
}
