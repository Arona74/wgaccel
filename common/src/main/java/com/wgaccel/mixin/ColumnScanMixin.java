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
         Optional<Column> fast = com.wgaccel.gen.RegionSectionCache.COLUMN_CHECK.on()
            ? RegionSectionCache.scanColumn(region, pos, range, insideTest, stopTest) : null;
         if (fast != null) {
            if (com.wgaccel.gen.RegionSectionCache.COLUMN_CHECK.sample()) {
               // A read-only scan (its tests are block tests): asking the game's own as well changes nothing.
               Optional<Column> real = original.call(level, pos, range, insideTest, stopTest);
               com.wgaccel.gen.RegionSectionCache.COLUMN_CHECK.sameValue(com.wgaccel.gen.RegionSectionCache.columnKey(fast),
                  com.wgaccel.gen.RegionSectionCache.columnKey(real), pos);
               return real;
            }
            return fast;
         }
      }

      return original.call(level, pos, range, insideTest, stopTest);
   }
}
