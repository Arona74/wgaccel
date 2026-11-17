package com.wgaccel.mixin.ftf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FreeTerraForged's region filter grids: their raw chunks made in parallel before the region's own walk ({@link com.wgaccel.ftf.FtfRegionPrefetch}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.filter.RegionFilterGrid")
public abstract class RegionPrefetchMixin {
   @Inject(method = "init(IILetcodehome/freeterraforged/world/worldgen/GeneratorContext;)V", at = @At("HEAD"))
   private void wgaccel$prefetch(int regionChunkX, int regionChunkZ, @Coerce Object context, CallbackInfo info) {
      com.wgaccel.ftf.FtfRegionPrefetch.before(regionChunkX, regionChunkZ, context);
   }
}
