package com.wgaccel.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.PlacementStreams;
import com.wgaccel.stats.Named;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times every placed feature the decoration step runs (trees, ores, snow and ice, lakes, flowers...), by its id, and
 * evaluates placement modifiers depth-first without nested stream wrappers when {@code fastPlacementStreams} is enabled.
 */
@Mixin(value = PlacedFeature.class, priority = 500)
public abstract class PlacedFeatureMixin {
   @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;
   @Shadow @Final private List<PlacementModifier> placement;
   @Unique private PlacementStreams.Plan wgaccel$plan;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void wgaccel$compilePlacement(CallbackInfo callback) {
      if (this.placement instanceof ImmutableList<?> || this.placement.getClass().getName().startsWith("java.util.ImmutableCollections$")) {
         this.wgaccel$plan = PlacementStreams.compile(this.placement.toArray(PlacementModifier[]::new));
      }
   }

   @WrapMethod(method = "placeWithContext")
   private boolean wgaccel$placeContext(PlacementContext context, RandomSource random, BlockPos pos, Operation<Boolean> original) {
      if (!Config.FAST_PLACEMENT_STREAMS.get()) {
         return original.call(context, random, pos);
      }

      PlacementStreams.Plan compiled = this.wgaccel$plan;
      if (compiled == null) {
         compiled = PlacementStreams.compile(this.placement.toArray(PlacementModifier[]::new));
      }

      return PlacementStreams.place((PlacedFeature)(Object)this, compiled, context, random, this.feature.value(), pos);
   }
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
