package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FTF's choice among a depth band's cave biomes (a distance and a weight for every candidate, then a pick from the
 * region's hash): timed, and (config {@code fasterBiomeChoice}) remembered per chunk. It depends only on the band's
 * fixed candidates, the climate target, the region key and the climate influence, and a chunk asks it about 270 times
 * with only a few different inputs ({@link BiomeFill}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding$Stage")
public abstract class BiomeStageMixin {
   @WrapMethod(method = "findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;JF)Ljava/lang/Object;")
   private Object wgaccel$choice(Climate.TargetPoint target, long regionKey, float climateInfluence, Operation<Object> original) {
      long start = System.nanoTime();
      try {
         BiomeFill.State memo = BiomeFill.memo();
         if (memo == null) {
            return original.call(target, regionKey, climateInfluence);
         }

         Object known = memo.stageChoice(this, target, regionKey, climateInfluence);
         if (known != null) {
            BiomeFill.STAGE_HITS.increment();
            if (memo.checkNow()) {
               Object normal = original.call(target, regionKey, climateInfluence);
               BiomeFill.checked(normal == known, "cave-biome choice");
               return normal;
            }

            return known;
         }

         Object value = original.call(target, regionKey, climateInfluence);
         memo.rememberStageChoice(this, target, regionKey, climateInfluence, value);
         return value;
      } finally {
         Stats.record(Stage.BIOME_STAGE, System.nanoTime() - start);
      }
   }
}
