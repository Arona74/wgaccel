package com.wgaccel.gen;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Column;

/**
 * Direct chunk-section helpers for hot feature checks (sculk spread, multiface spread, dripstone vertical column
 * scans). Avoids per-block {@link WorldGenRegion} chunk-map lookups and {@code MutableBlockPos} allocations without
 * holding world or chunk references across calls.
 */
public final class RegionSectionCache {
   private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS =
      ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

   private RegionSectionCache() {
   }

   /** Reusable per-thread mutable BlockPos for short-lived single-call boundary checks. */
   public static BlockPos.MutableBlockPos mutablePos() {
      return MUTABLE_POS.get();
   }

   /**
    * Returns the section at {@code pos} when {@code level} is a {@link WorldGenRegion} holding that chunk and {@code pos}
    * is strictly interior ({@code 1..14} on all three axes), guaranteeing all 6 cardinal neighbors live in the same section.
    */
   public static LevelChunkSection interiorSection(LevelAccessor level, BlockPos pos) {
      int lx = pos.getX() & 15;
      int ly = pos.getY() & 15;
      int lz = pos.getZ() & 15;
      if (lx == 0 || lx == 15 || ly == 0 || ly == 15 || lz == 0 || lz == 15) {
         return null;
      }

      if (!(level instanceof WorldGenRegion region)) {
         return null;
      }

      int cx = pos.getX() >> 4;
      int cz = pos.getZ() >> 4;
      if (!region.hasChunk(cx, cz)) {
         return null;
      }

      ChunkAccess chunk = region.getChunk(cx, cz);
      int sy = chunk.getSectionIndex(pos.getY());
      if (sy < 0 || sy >= chunk.getSectionsCount()) {
         return null;
      }

      return chunk.getSection(sy);
   }

   /**
    * Direct section read at {@code (x, y, z)} without allocating a {@link BlockPos}, returning {@code null} when a
    * predicate scope is open or the chunk/section is out of range.
    */
   public static BlockState blockState(WorldGenRegion region, int x, int y, int z) {
      if (region instanceof PredicateReads.Holder holder && holder.wgaccel$predicateReads().open()) {
         return null;
      }

      int cx = x >> 4;
      int cz = z >> 4;
      if (!region.hasChunk(cx, cz)) {
         return null;
      }

      ChunkAccess chunk = region.getChunk(cx, cz);
      int sy = chunk.getSectionIndex(y);
      if (sy < 0 || sy >= chunk.getSectionsCount()) {
         return null;
      }

      return chunk.getSection(sy).getBlockState(x & 15, y & 15, z & 15);
   }

   /**
    * Direct-section equivalent of {@code DripstoneClusterFeature.canPlacePool}, avoiding 6 {@link BlockPos} allocations,
    * the horizontal {@link Direction.Plane} iterator, and repeated chunk lookups when interior to a section.
    * Returns {@code Boolean.TRUE}/{@code Boolean.FALSE} when resolved, or {@code null} to fall back to vanilla.
    */
   public static Boolean canPlaceDripstonePool(WorldGenRegion region, BlockPos pos) {
      if (region instanceof PredicateReads.Holder holder && holder.wgaccel$predicateReads().open()) {
         return null;
      }

      int x = pos.getX();
      int y = pos.getY();
      int z = pos.getZ();
      int cx = x >> 4;
      int cz = z >> 4;
      if (!region.hasChunk(cx, cz)) {
         return null;
      }

      ChunkAccess chunk = region.getChunk(cx, cz);
      int sy = chunk.getSectionIndex(y);
      if (sy < 0 || sy >= chunk.getSectionsCount()) {
         return null;
      }

      int lx = x & 15;
      int ly = y & 15;
      int lz = z & 15;
      LevelChunkSection sec = chunk.getSection(sy);
      BlockState center = sec.getBlockState(lx, ly, lz);
      if (center.is(Blocks.WATER) || center.is(Blocks.DRIPSTONE_BLOCK) || center.is(Blocks.POINTED_DRIPSTONE)) {
         return Boolean.FALSE;
      }

      if (lx >= 1 && lx <= 14 && ly >= 1 && ly <= 14 && lz >= 1 && lz <= 14) {
         if (sec.getBlockState(lx, ly + 1, lz).getFluidState().is(FluidTags.WATER)) {
            return Boolean.FALSE;
         }

         return isAdjacentPoolWall(sec.getBlockState(lx, ly, lz - 1))
            && isAdjacentPoolWall(sec.getBlockState(lx, ly, lz + 1))
            && isAdjacentPoolWall(sec.getBlockState(lx - 1, ly, lz))
            && isAdjacentPoolWall(sec.getBlockState(lx + 1, ly, lz))
            && isAdjacentPoolWall(sec.getBlockState(lx, ly - 1, lz));
      }

      BlockState above = blockState(region, x, y + 1, z);
      if (above == null) {
         return null;
      }
      if (above.getFluidState().is(FluidTags.WATER)) {
         return Boolean.FALSE;
      }

      for (Direction dir : Direction.Plane.HORIZONTAL) {
         BlockState neighbor = blockState(region, x + dir.getStepX(), y, z + dir.getStepZ());
         if (neighbor == null) {
            return null;
         }
         if (!isAdjacentPoolWall(neighbor)) {
            return Boolean.FALSE;
         }
      }

      BlockState below = blockState(region, x, y - 1, z);
      if (below == null) {
         return null;
      }
      return isAdjacentPoolWall(below);
   }

   private static boolean isAdjacentPoolWall(BlockState state) {
      return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.getFluidState().is(FluidTags.WATER);
   }

   /**
    * Direct-section implementation of {@link Column#scan} for {@link WorldGenRegion}, reading directly from cached
    * {@link LevelChunkSection}s along the fixed {@code (x, z)} column. Returns {@code null} when a predicate scope is
    * active or the origin chunk/section is unavailable so callers fall back to vanilla.
    */
   public static Optional<Column> scanColumn(
         WorldGenRegion region,
         BlockPos origin,
         int range,
         Predicate<BlockState> insideTest,
         Predicate<BlockState> stopTest) {
      if (region instanceof PredicateReads.Holder holder && holder.wgaccel$predicateReads().open()) {
         return null;
      }

      int px = origin.getX();
      int py = origin.getY();
      int pz = origin.getZ();
      int cx = px >> 4;
      int cz = pz >> 4;
      if (!region.hasChunk(cx, cz)) {
         return null;
      }

      ChunkAccess chunk = region.getChunk(cx, cz);
      int secCount = chunk.getSectionsCount();
      int sy0 = chunk.getSectionIndex(py);
      if (sy0 < 0 || sy0 >= secCount) {
         return null;
      }

      int lx = px & 15;
      int lz = pz & 15;
      LevelChunkSection sec0 = chunk.getSection(sy0);
      BlockState originState = sec0.getBlockState(lx, py & 15, lz);
      if (!insideTest.test(originState)) {
         return Optional.empty();
      }

      OptionalInt ceiling = scanDirection(region, chunk, secCount, originState, px, py, pz, lx, lz, sy0, sec0, range, 1, insideTest, stopTest);
      OptionalInt floor = scanDirection(region, chunk, secCount, originState, px, py, pz, lx, lz, sy0, sec0, range, -1, insideTest, stopTest);
      return Optional.of(Column.create(floor, ceiling));
   }

   private static OptionalInt scanDirection(
         WorldGenRegion region,
         ChunkAccess chunk,
         int secCount,
         BlockState originState,
         int px,
         int startY,
         int pz,
         int lx,
         int lz,
         int curSy,
         LevelChunkSection curSec,
         int range,
         int step,
         Predicate<BlockState> insideTest,
         Predicate<BlockState> stopTest) {
      int y = startY;
      boolean valid = true;
      BlockState state = originState;
      for (int i = 1; i < range; i++) {
         boolean inside;
         if (valid) {
            inside = insideTest.test(state);
         } else {
            inside = region.isStateAtPosition(MUTABLE_POS.get().set(px, y, pz), insideTest);
         }

         if (!inside) {
            break;
         }

         y += step;
         int sy = chunk.getSectionIndex(y);
         if (sy != curSy) {
            curSy = sy;
            if (sy >= 0 && sy < secCount) {
               curSec = chunk.getSection(sy);
               valid = true;
            } else {
               curSec = null;
               valid = false;
            }
         }

         if (valid) {
            state = curSec.getBlockState(lx, y & 15, lz);
         }
      }

      boolean stop;
      if (valid) {
         stop = stopTest.test(state);
      } else {
         stop = region.isStateAtPosition(MUTABLE_POS.get().set(px, y, pz), stopTest);
      }

      return stop ? OptionalInt.of(y) : OptionalInt.empty();
   }
}
