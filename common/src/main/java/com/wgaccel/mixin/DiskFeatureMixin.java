package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.DiskColumns;
import com.wgaccel.gen.FastPredicates;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips whole disks or disk columns that cannot pass the disk's target ({@link DiskColumns}) without allocating
 * {@link BlockPos#betweenClosed} iterators, and shares one {@link FastPredicates.EvalContext} across the disk.
 */
@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
   @Shadow
   protected abstract boolean placeColumn(DiskConfiguration config, WorldGenLevel level, RandomSource random, int top, int bottom, BlockPos.MutableBlockPos pos);

   @WrapMethod(method = "place", require = 0)
   private boolean wgaccel$place(FeaturePlaceContext<DiskConfiguration> context, Operation<Boolean> original) {
      if (!DiskColumns.on()) {
         return original.call(context);
      }

      DiskConfiguration config = context.config();
      BlockPos origin = context.origin();
      WorldGenLevel level = context.level();
      RandomSource random = context.random();
      int ox = origin.getX();
      int oy = origin.getY();
      int oz = origin.getZ();
      int top = oy + config.halfHeight();
      int bottom = oy - config.halfHeight() - 1;
      int radius = config.radius().sample(random);

      int mode = DiskColumns.beginDisk(config.target(), level, ox, oz, radius, top, bottom);
      if (mode == DiskColumns.DISK_EMPTY) {
         return false;
      }

      FastPredicates.EvalContext fpCtx = FastPredicates.on() ? FastPredicates.begin(level) : null;
      boolean placed = false;
      try {
         BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
         int r2 = radius * radius;
         // Same z-outer, x-inner order as BlockPos.betweenClosed so neighbor-reading rules stay identical.
         for (int dz = -radius; dz <= radius; dz++) {
            int dz2 = dz * dz;
            int pz = oz + dz;
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dz2 <= r2) {
                  mpos.set(ox + dx, oy, pz);
                  if (!DiskColumns.skipDiskColumn(level, top, bottom, mpos)) {
                     try {
                        placed |= this.placeColumn(config, level, random, top, bottom, mpos);
                     } finally {
                        DiskColumns.done();
                     }
                  }
               }
            }
         }
         return placed;
      } finally {
         if (fpCtx != null) {
            fpCtx.end();
         }
         DiskColumns.endDisk();
      }
   }

   @Redirect(method = "place", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/feature/DiskFeature;placeColumn(Lnet/minecraft/world/level/levelgen/feature/configurations/DiskConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;IILnet/minecraft/core/BlockPos$MutableBlockPos;)Z"),
      require = 0)
   private boolean wgaccel$column(DiskFeature self, DiskConfiguration config, WorldGenLevel level, RandomSource random, int top, int bottom, BlockPos.MutableBlockPos pos) {
      if (DiskColumns.on() && DiskColumns.skip(config.target(), level, top, bottom, pos)) {
         return false;
      }

      FastPredicates.EvalContext ctx = FastPredicates.on() ? FastPredicates.begin(level) : null;
      try {
         return this.placeColumn(config, level, random, top, bottom, pos);
      } finally {
         if (ctx != null) {
            ctx.end();
         }
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
