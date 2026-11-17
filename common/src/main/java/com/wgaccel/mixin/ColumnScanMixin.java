package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.RegionSectionCache;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Column;
import org.spongepowered.asm.mixin.Mixin;

/** Accelerates vertical column scans (dripstone clusters, basalt columns, root systems) via direct section reads. */
@Mixin(Column.class)
public abstract class ColumnScanMixin {
   @WrapMethod(method = "scan", require = 0)
   private static Optional<Column> wgaccel$scan(
         LevelSimulatedReader level,
         BlockPos pos,
         int range,
         Predicate<BlockState> insideTest,
         Predicate<BlockState> stopTest,
         Operation<Optional<Column>> original) {
      if (level instanceof WorldGenRegion region && Config.FAST_REGION_CHUNKS.get()) {
         Optional<Column> fast = RegionSectionCache.scanColumn(region, pos, range, insideTest, stopTest);
         if (fast != null) {
            return fast;
         }
      }

      return original.call(level, pos, range, insideTest, stopTest);
   }
}
