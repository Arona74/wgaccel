package com.wgaccel.mixin;

import com.wgaccel.gen.DiskColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A disk's columns that cannot pass its target are not tested ({@link DiskColumns}). A redirect of place's calls to
 * placeColumn, not a wrapper: no object per column.
 */
@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
   @Shadow
   protected abstract boolean placeColumn(DiskConfiguration config, WorldGenLevel level, RandomSource random, int top, int bottom, BlockPos.MutableBlockPos pos);

   @Redirect(method = "place", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/feature/DiskFeature;placeColumn(Lnet/minecraft/world/level/levelgen/feature/configurations/DiskConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;IILnet/minecraft/core/BlockPos$MutableBlockPos;)Z"),
      require = 0)
   private boolean wgaccel$column(DiskFeature self, DiskConfiguration config, WorldGenLevel level, RandomSource random, int top, int bottom, BlockPos.MutableBlockPos pos) {
      if (DiskColumns.on() && DiskColumns.skip(config.target(), level, top, bottom, pos)) {
         return false;
      }

      try {
         return this.placeColumn(config, level, random, top, bottom, pos);
      } finally {
         DiskColumns.done();
      }
   }

   /** The column's tests: the heights whose own block the target cannot take answered at once ({@link DiskColumns#test}). */
   @Redirect(method = "placeColumn", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"), require = 0)
   private boolean wgaccel$test(net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate target, Object level, Object pos) {
      return DiskColumns.test(target, (WorldGenLevel)level, (BlockPos)pos);
   }
}
