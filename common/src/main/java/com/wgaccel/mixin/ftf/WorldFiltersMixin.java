package com.wgaccel.mixin.ftf;

import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times FreeTerraForged's tile filters (erosion, cliffs, beaches, steepness, the terrain ceiling), once per tile. */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.WorldFilters")
public abstract class WorldFiltersMixin {
   @Unique
   private static final ThreadLocal<long[]> wgaccel$start = ThreadLocal.withInitial(() -> new long[1]);

   @Inject(method = "apply", at = @At("HEAD"))
   private void wgaccel$begin(CallbackInfo info) {
      wgaccel$start.get()[0] = System.nanoTime();
   }

   @Inject(method = "apply", at = @At("RETURN"))
   private void wgaccel$end(CallbackInfo info) {
      Stats.record(Stage.FTF_FILTERS, System.nanoTime() - wgaccel$start.get()[0]);
   }
}
