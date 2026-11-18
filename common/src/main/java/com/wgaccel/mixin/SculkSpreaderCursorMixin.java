package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.PredicateReads;
import com.wgaccel.gen.RegionSectionCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SculkBehaviour;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Reuses ThreadLocal buffers and direct chunk-section lookups for {@code SculkSpreader.ChargeCursor} neighbor
 * scans and unobstructed checks during worldgen, avoiding per-tick {@code ObjectArrayList}, {@code BlockPos},
 * and {@code WorldGenRegion} chunk-map lookups.
 */
@Mixin(SculkSpreader.ChargeCursor.class)
public abstract class SculkSpreaderCursorMixin {
   @Shadow
   @Final
   private static ObjectArrayList<Vec3i> NON_CORNER_NEIGHBOURS;

   @Shadow
   private static List<Vec3i> getRandomizedNonCornerNeighbourOffsets(RandomSource random) {
      throw new AssertionError();
   }

   @Shadow
   private static boolean isUnobstructed(LevelAccessor level, BlockPos pos, Direction direction) {
      throw new AssertionError();
   }

   @Unique
   private static final ThreadLocal<ObjectArrayList<Vec3i>> wgaccel$NEIGHBOURS =
      ThreadLocal.withInitial(() -> new ObjectArrayList<>(NON_CORNER_NEIGHBOURS));

   @Unique
   private static final ThreadLocal<BlockPos.MutableBlockPos> wgaccel$POS =
      ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

   @WrapMethod(method = "getRandomizedNonCornerNeighbourOffsets", require = 0)
   private static List<Vec3i> wgaccel$neighbours(RandomSource random, Operation<List<Vec3i>> original) {
      if (!Config.FAST_MULTIFACE_SPREAD.get()) {
         return original.call(random);
      }

      ObjectArrayList<Vec3i> list = wgaccel$NEIGHBOURS.get();
      int size = NON_CORNER_NEIGHBOURS.size();
      for (int i = 0; i < size; i++) {
         list.set(i, NON_CORNER_NEIGHBOURS.get(i));
      }

      for (int i = size; i > 1; i--) {
         int j = random.nextInt(i);
         Vec3i tmp = list.get(i - 1);
         list.set(i - 1, list.get(j));
         list.set(j, tmp);
      }

      return list;
   }

   @WrapMethod(method = "getValidMovementPos", require = 0)
   private static BlockPos wgaccel$getValidMovementPos(LevelAccessor level, BlockPos pos, RandomSource random, Operation<BlockPos> original) {
      if (!Config.FAST_MULTIFACE_SPREAD.get() || !(level instanceof WorldGenRegion region)
            || (region instanceof PredicateReads.Holder holder && holder.wgaccel$predicateReads().open())) {
         return original.call(level, pos, random);
      }

      int px = pos.getX();
      int py = pos.getY();
      int pz = pos.getZ();
      LevelChunkSection interior = RegionSectionCache.interiorSection(level, pos);
      BlockPos.MutableBlockPos candidate = wgaccel$POS.get();
      int foundX = px;
      int foundY = py;
      int foundZ = pz;
      boolean hasFound = false;

      if (interior != null) {
         if (interior.hasOnlyAir()) {
            return null;
         }

         int lx = px & 15;
         int ly = py & 15;
         int lz = pz & 15;
         for (Vec3i offset : getRandomizedNonCornerNeighbourOffsets(random)) {
            int ox = offset.getX();
            int oy = offset.getY();
            int oz = offset.getZ();
            BlockState state = interior.getBlockState(lx + ox, ly + oy, lz + oz);
            if (state.getBlock() instanceof SculkBehaviour && wgaccel$isInteriorMovementUnobstructed(level, interior, px, py, pz, lx, ly, lz, ox, oy, oz)) {
               foundX = px + ox;
               foundY = py + oy;
               foundZ = pz + oz;
               hasFound = true;
               candidate.set(foundX, foundY, foundZ);
               if (SculkVeinBlock.hasSubstrateAccess(level, state, candidate)) {
                  break;
               }
            }
         }
      } else {
         for (Vec3i offset : getRandomizedNonCornerNeighbourOffsets(random)) {
            int ox = offset.getX();
            int oy = offset.getY();
            int oz = offset.getZ();
            int tx = px + ox;
            int ty = py + oy;
            int tz = pz + oz;
            BlockState state = RegionSectionCache.blockState(region, tx, ty, tz);
            if (state == null) {
               state = level.getBlockState(candidate.set(tx, ty, tz));
            }
            if (state.getBlock() instanceof SculkBehaviour) {
               candidate.set(tx, ty, tz);
               if (wgaccel$isRegionMovementUnobstructed(level, pos, px, py, pz, ox, oy, oz)) {
                  foundX = tx;
                  foundY = ty;
                  foundZ = tz;
                  hasFound = true;
                  if (SculkVeinBlock.hasSubstrateAccess(level, state, candidate)) {
                     break;
                  }
               }
            }
         }
      }

      return hasFound ? new BlockPos(foundX, foundY, foundZ) : null;
   }

   @Unique
   private static boolean wgaccel$isInteriorMovementUnobstructed(LevelAccessor level, LevelChunkSection interior,
         int px, int py, int pz, int lx, int ly, int lz, int dx, int dy, int dz) {
      if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
         return true;
      }

      Direction dirX = dx < 0 ? Direction.WEST : Direction.EAST;
      Direction dirY = dy < 0 ? Direction.DOWN : Direction.UP;
      Direction dirZ = dz < 0 ? Direction.NORTH : Direction.SOUTH;
      if (dx == 0) {
         return wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirY)
            || wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirZ);
      }
      if (dy == 0) {
         return wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirX)
            || wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirZ);
      }
      return wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirX)
         || wgaccel$isDirUnobstructedInterior(level, interior, px, py, pz, lx, ly, lz, dirY);
   }

   @Unique
   private static boolean wgaccel$isDirUnobstructedInterior(LevelAccessor level, LevelChunkSection interior,
         int px, int py, int pz, int lx, int ly, int lz, Direction dir) {
      int sx = dir.getStepX();
      int sy = dir.getStepY();
      int sz = dir.getStepZ();
      BlockState state = interior.getBlockState(lx + sx, ly + sy, lz + sz);
      return !state.isFaceSturdy(level, RegionSectionCache.mutablePos().set(px + sx, py + sy, pz + sz), dir.getOpposite());
   }

   @Unique
   private static boolean wgaccel$isRegionMovementUnobstructed(LevelAccessor level, BlockPos fromPos, int px, int py, int pz, int dx, int dy, int dz) {
      if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
         return true;
      }

      Direction dirX = dx < 0 ? Direction.WEST : Direction.EAST;
      Direction dirY = dy < 0 ? Direction.DOWN : Direction.UP;
      Direction dirZ = dz < 0 ? Direction.NORTH : Direction.SOUTH;
      if (dx == 0) {
         return isUnobstructed(level, fromPos, dirY) || isUnobstructed(level, fromPos, dirZ);
      }
      if (dy == 0) {
         return isUnobstructed(level, fromPos, dirX) || isUnobstructed(level, fromPos, dirZ);
      }
      return isUnobstructed(level, fromPos, dirX) || isUnobstructed(level, fromPos, dirY);
   }

   @WrapMethod(method = "isUnobstructed", require = 0)
   private static boolean wgaccel$isUnobstructed(LevelAccessor level, BlockPos pos, Direction dir, Operation<Boolean> original) {
      if (Config.FAST_MULTIFACE_SPREAD.get() && level instanceof WorldGenRegion region) {
         int rx = pos.getX() + dir.getStepX();
         int ry = pos.getY() + dir.getStepY();
         int rz = pos.getZ() + dir.getStepZ();
         BlockState state = RegionSectionCache.blockState(region, rx, ry, rz);
         if (state != null) {
            return !state.isFaceSturdy(level, RegionSectionCache.mutablePos().set(rx, ry, rz), dir.getOpposite());
         }
      }

      return original.call(level, pos, dir);
   }
}
