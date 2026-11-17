package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.RegionSectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Direct-section dripstone block checks on {@link WorldGenRegion}, avoiding {@code Predicate} lambdas, {@code BlockPos}
 * offset allocations in {@code isCircleMostlyEmbeddedInStone}, and per-call chunk map lookups.
 */
@Mixin(DripstoneUtils.class)
public abstract class DripstoneUtilsMixin {
   @Shadow
   public static boolean isEmptyOrWater(BlockState state) {
      throw new AssertionError();
   }

   @Shadow
   public static boolean isEmptyOrWaterOrLava(BlockState state) {
      throw new AssertionError();
   }

   @WrapMethod(method = "isEmptyOrWater(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Z", require = 0)
   private static boolean wgaccel$isEmptyOrWater(LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
      if (Config.FAST_DRIPSTONE.get() && level instanceof WorldGenRegion region) {
         BlockState state = RegionSectionCache.blockState(region, pos.getX(), pos.getY(), pos.getZ());
         if (state != null) {
            return isEmptyOrWater(state);
         }
      }

      return original.call(level, pos);
   }

   @WrapMethod(method = "isEmptyOrWaterOrLava(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Z", require = 0)
   private static boolean wgaccel$isEmptyOrWaterOrLava(LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
      if (Config.FAST_DRIPSTONE.get() && level instanceof WorldGenRegion region) {
         BlockState state = RegionSectionCache.blockState(region, pos.getX(), pos.getY(), pos.getZ());
         if (state != null) {
            return isEmptyOrWaterOrLava(state);
         }
      }

      return original.call(level, pos);
   }

   @WrapMethod(method = "isCircleMostlyEmbeddedInStone", require = 0)
   private static boolean wgaccel$isCircleEmbedded(WorldGenLevel level, BlockPos pos, int radius, Operation<Boolean> original) {
      if (!Config.FAST_DRIPSTONE.get() || !(level instanceof WorldGenRegion region)) {
         return original.call(level, pos, radius);
      }

      int px = pos.getX();
      int py = pos.getY();
      int pz = pos.getZ();
      BlockState center = RegionSectionCache.blockState(region, px, py, pz);
      if (center == null) {
         return original.call(level, pos, radius);
      }
      if (isEmptyOrWaterOrLava(center)) {
         return false;
      }

      float step = 6.0F / radius;
      BlockPos.MutableBlockPos cursor = RegionSectionCache.mutablePos();
      for (float angle = 0.0F; angle < (float)(Math.PI * 2.0); angle += step) {
         int ox = (int)(Mth.cos(angle) * radius);
         int oz = (int)(Mth.sin(angle) * radius);
         BlockState sample = RegionSectionCache.blockState(region, px + ox, py, pz + oz);
         if (sample == null) {
            sample = region.getBlockState(cursor.set(px + ox, py, pz + oz));
         }
         if (isEmptyOrWaterOrLava(sample)) {
            return false;
         }
      }

      return true;
   }
}
