package com.wgaccel.mixin.ftf;

import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measuring only: FTF's 3D cave-biome regions (nearest of 27 cell sites) inside the underground layout. */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding")
public abstract class BiomeRegionMixin {
   @Unique
   private static final ThreadLocal<long[]> wgaccel$start = ThreadLocal.withInitial(() -> new long[1]);

   @Inject(method = "sample3d(JIIIII)Letcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$RegionSample;", at = @At("HEAD"))
   private static void wgaccel$begin(CallbackInfoReturnable<?> info) {
      wgaccel$start.get()[0] = System.nanoTime();
   }

   @Inject(method = "sample3d(JIIIII)Letcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$RegionSample;", at = @At("RETURN"))
   private static void wgaccel$end(CallbackInfoReturnable<?> info) {
      Stats.record(Stage.BIOME_SAMPLE3D, System.nanoTime() - wgaccel$start.get()[0]);
   }
}
