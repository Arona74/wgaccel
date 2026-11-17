package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.Config;
import com.wgaccel.gen.BiomeZoomCache;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BiomeManager.class)
public abstract class BiomeZoomMixin {
   @WrapOperation(method = "getBiome", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/biome/BiomeManager;getFiddledDistance(JIIIDDD)D"))
   private double wgaccel$corner(long seed, int x, int y, int z, double dx, double dy, double dz, Operation<Double> original) {
      return Config.FAST_BIOME_ZOOM.get() ? BiomeZoomCache.checked(seed,x,y,z,dx,dy,dz,original) : original.call(seed,x,y,z,dx,dy,dz);
   }
}
