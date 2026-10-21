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
 * FTF's check of how far a cell lies under the surface: timed, and (config {@code fasterBiomeChoice}) the answer is
 * reused when the terrain-role step asks again for the same cell, sampler and climate target ({@link BiomeFill}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection")
public abstract class BiomeCoverageMixin {
   @WrapMethod(method = "coverageFactor(Lnet/minecraft/world/level/biome/Climate$Sampler;Lnet/minecraft/world/level/biome/Climate$TargetPoint;III)F")
   private static float wgaccel$coverage(Climate.Sampler sampler, Climate.TargetPoint target, int quartX, int quartY, int quartZ, Operation<Float> original) {
      long start = System.nanoTime();
      try {
         BiomeFill.State memo = BiomeFill.memo();
         if (memo != null && memo.coverageMatches(sampler, target, quartX, quartY, quartZ)) {
            BiomeFill.COVERAGE_HITS.increment();
            float reused = memo.coverage();
            if (memo.checkNow()) {
               float normal = original.call(sampler, target, quartX, quartY, quartZ);
               BiomeFill.checked(Float.compare(normal, reused) == 0, "surface coverage");
               return normal;
            }

            return reused;
         }

         float coverage = original.call(sampler, target, quartX, quartY, quartZ);
         if (memo != null) {
            memo.rememberCoverage(sampler, target, quartX, quartY, quartZ, coverage);
         }

         return coverage;
      } finally {
         Stats.record(Stage.BIOME_COVERAGE, System.nanoTime() - start);
      }
   }
}
