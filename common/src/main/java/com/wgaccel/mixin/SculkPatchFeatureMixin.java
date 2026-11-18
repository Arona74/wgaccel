package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.RegionSectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.SculkPatchFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Replaces SculkPatchFeature.canSpreadFrom's stream allocation and per-neighbor chunk lookups with direct interior
 * section reads (when {@code x, y, z mod 16} are in {@code 1..14}) and a reusable MutableBlockPos fallback.
 */
@Mixin(SculkPatchFeature.class)
public abstract class SculkPatchFeatureMixin {
   @Unique
   private static final Direction[] wgaccel$DIRECTIONS = Direction.values();

   @WrapMethod(method = "canSpreadFrom", require = 0)
   private boolean wgaccel$canSpreadFrom(LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
      if (!Config.FAST_MULTIFACE_SPREAD.get() || !wgaccel$CHECK.on()) {
         return original.call(level, pos);
      }

      boolean fast = wgaccel$fastCanSpreadFrom(level, pos);
      // A read-only test: asking the game's own as well changes nothing.
      return wgaccel$CHECK.sample() ? wgaccel$CHECK.same(fast, original.call(level, pos), pos) : fast;
   }

   @Unique
   private static final com.wgaccel.gen.FastCheck wgaccel$CHECK = new com.wgaccel.gen.FastCheck("fast sculk spread test", 64);

   @Unique
   private static boolean wgaccel$fastCanSpreadFrom(LevelAccessor level, BlockPos pos) {
      LevelChunkSection section = RegionSectionCache.interiorSection(level, pos);
      if (section != null) {
         if (section.hasOnlyAir()) {
            return false;
         }

         int lx = pos.getX() & 15;
         int ly = pos.getY() & 15;
         int lz = pos.getZ() & 15;
         BlockState state = section.getBlockState(lx, ly, lz);
         if (state.getBlock() instanceof SculkBehaviour) {
            return true;
         }

         if (!state.isAir() && (!state.is(Blocks.WATER) || !state.getFluidState().isSource())) {
            return false;
         }

         int px = pos.getX();
         int py = pos.getY();
         int pz = pos.getZ();
         BlockPos.MutableBlockPos neighbor = RegionSectionCache.mutablePos();
         for (Direction dir : wgaccel$DIRECTIONS) {
            int dx = dir.getStepX();
            int dy = dir.getStepY();
            int dz = dir.getStepZ();
            BlockState nState = section.getBlockState(lx + dx, ly + dy, lz + dz);
            if (!nState.isAir() && nState.isCollisionShapeFullBlock(level, neighbor.set(px + dx, py + dy, pz + dz))) {
               return true;
            }
         }

         return false;
      }

      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof SculkBehaviour) {
         return true;
      }

      if (!state.isAir() && (!state.is(Blocks.WATER) || !state.getFluidState().isSource())) {
         return false;
      }

      BlockPos.MutableBlockPos neighbor = RegionSectionCache.mutablePos();
      for (Direction dir : wgaccel$DIRECTIONS) {
         neighbor.setWithOffset(pos, dir);
         if (level.getBlockState(neighbor).isCollisionShapeFullBlock(level, neighbor)) {
            return true;
         }
      }

      return false;
   }
}
