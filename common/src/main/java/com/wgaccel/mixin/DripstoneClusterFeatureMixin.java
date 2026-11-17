package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.RegionSectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.DripstoneClusterFeature;
import org.spongepowered.asm.mixin.Mixin;

/** Direct-section pool wall check in {@link DripstoneClusterFeature#canPlacePool} without {@code BlockPos} or iterator allocations. */
@Mixin(DripstoneClusterFeature.class)
public abstract class DripstoneClusterFeatureMixin {
   @WrapMethod(method = "canPlacePool", require = 0)
   private boolean wgaccel$canPlacePool(WorldGenLevel level, BlockPos pos, Operation<Boolean> original) {
      if (Config.FAST_DRIPSTONE.get() && level instanceof WorldGenRegion region) {
         Boolean result = RegionSectionCache.canPlaceDripstonePool(region, pos);
         if (result != null) {
            return result;
         }
      }

      return original.call(level, pos);
   }
}
