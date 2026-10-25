package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Named;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times every placed feature the decoration step runs (trees, ores, snow and ice, lakes, flowers...), by its id. The
 * features step calls this once per feature per chunk; features placed inside other features count in their parent.
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {
   @WrapMethod(method = "placeWithBiomeCheck")
   private boolean wgaccel$place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos, Operation<Boolean> original) {
      if (!Named.enterFeature()) {
         try {
            return original.call(level, generator, random, pos);
         } finally {
            Named.exitFeature();
         }
      }

      long start = System.nanoTime();
      try {
         return original.call(level, generator, random, pos);
      } finally {
         long nanos = System.nanoTime() - start;
         Named.exitFeature();
         Named.record(Named.Category.FEATURE, Named.feature(level, (PlacedFeature)(Object)this), nanos, false);
      }
   }
}
