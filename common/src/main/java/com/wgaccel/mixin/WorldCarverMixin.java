package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.MutablePointContext;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.material.FluidState;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates per-block {@code SinglePointContext} allocations in {@code getCarveState}, reuses one
 * {@link MutableBoolean} across columns in {@code carveEllipsoid}, and skips empty/air blocks via cached
 * {@link LevelChunkSection} lookups without bypassing mod water-carve hooks or {@link CarvingMask} updates.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin<C extends CarverConfiguration> {
   @Shadow
   @Final
   protected static BlockState AIR;
   @Shadow
   @Final
   protected static BlockState CAVE_AIR;
   @Shadow
   @Final
   protected static FluidState LAVA;

   @Unique
   private static final com.wgaccel.gen.FastCheck wgaccel$CARVE_STATE = new com.wgaccel.gen.FastCheck("fast carve states", 64);

   @Unique
   private static final ThreadLocal<MutablePointContext> wgaccel$POINT_CONTEXT =
      ThreadLocal.withInitial(MutablePointContext::new);

   @Shadow
   protected abstract boolean canReplaceBlock(C config, BlockState state);

   @Shadow
   private static boolean isDebugEnabled(CarverConfiguration config) {
      throw new AssertionError();
   }

   @Shadow
   protected abstract boolean carveBlock(
         CarvingContext context,
         C config,
         ChunkAccess chunk,
         Function<BlockPos, Holder<Biome>> biomeAccessor,
         CarvingMask carvingMask,
         BlockPos.MutableBlockPos pos,
         BlockPos.MutableBlockPos checkPos,
         Aquifer aquifer,
         MutableBoolean reachedSurface);

   @WrapMethod(method = "getCarveState", require = 0)
   private BlockState wgaccel$getCarveState(
         CarvingContext context,
         C config,
         BlockPos pos,
         Aquifer aquifer,
         Operation<BlockState> original) {
      if (!Config.FAST_CARVERS.get() || isDebugEnabled(config) || !wgaccel$CARVE_STATE.on()) {
         return original.call(context, config, pos, aquifer);
      }

      BlockState fast = pos.getY() <= config.lavaLevel.resolveY(context)
         ? LAVA.createLegacyBlock()
         : aquifer.computeSubstance(wgaccel$POINT_CONTEXT.get().at(pos.getX(), pos.getY(), pos.getZ()), 0.0);
      // The aquifer gives the same answer (and fluid-update flag) for the same position however often it is asked.
      return wgaccel$CARVE_STATE.sample() ? wgaccel$CARVE_STATE.sameValue(fast, original.call(context, config, pos, aquifer), pos) : fast;
   }

   @WrapMethod(method = "carveEllipsoid", require = 0)
   private boolean wgaccel$carveEllipsoid(
         CarvingContext context,
         C config,
         ChunkAccess chunk,
         Function<BlockPos, Holder<Biome>> biomeAccessor,
         Aquifer aquifer,
         double x,
         double y,
         double z,
         double horizontalRadius,
         double verticalRadius,
         CarvingMask carvingMask,
         WorldCarver.CarveSkipChecker skipChecker,
         Operation<Boolean> original) {
      Class<?> cls = this.getClass();
      if (!Config.FAST_CARVERS.get() || (cls != CaveWorldCarver.class && cls != CanyonWorldCarver.class)) {
         return original.call(context, config, chunk, biomeAccessor, aquifer, x, y, z, horizontalRadius, verticalRadius, carvingMask, skipChecker);
      }

      ChunkPos chunkPos = chunk.getPos();
      double midX = chunkPos.getMiddleBlockX();
      double midZ = chunkPos.getMiddleBlockZ();
      double maxReach = 16.0 + horizontalRadius * 2.0;
      if (Math.abs(x - midX) > maxReach || Math.abs(z - midZ) > maxReach) {
         return false;
      }

      int minBlockX = chunkPos.getMinBlockX();
      int minBlockZ = chunkPos.getMinBlockZ();
      int minLocalX = Math.max(Mth.floor(x - horizontalRadius) - minBlockX - 1, 0);
      int maxLocalX = Math.min(Mth.floor(x + horizontalRadius) - minBlockX, 15);
      int minY = Math.max(Mth.floor(y - verticalRadius) - 1, context.getMinGenY() + 1);
      int topMargin = chunk.isUpgrading() ? 0 : 7;
      int maxY = Math.min(Mth.floor(y + verticalRadius) + 1, context.getMinGenY() + context.getGenDepth() - 1 - topMargin);
      int minLocalZ = Math.max(Mth.floor(z - horizontalRadius) - minBlockZ - 1, 0);
      int maxLocalZ = Math.min(Mth.floor(z + horizontalRadius) - minBlockZ, 15);

      boolean debug = isDebugEnabled(config);
      boolean skipAir = !debug && !this.canReplaceBlock(config, AIR) && !this.canReplaceBlock(config, CAVE_AIR);
      int secCount = chunk.getSectionsCount();

      boolean carved = false;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
      MutableBoolean reachedSurface = new MutableBoolean(false);

      for (int dx = minLocalX; dx <= maxLocalX; dx++) {
         int wx = chunkPos.getBlockX(dx);
         double relX = (wx + 0.5 - x) / horizontalRadius;
         double relXSq = relX * relX;
         if (relXSq >= 1.0) {
            continue;
         }

         double maxRelZ = Math.sqrt(1.0 - relXSq) * horizontalRadius;
         int zStart = Math.max(Mth.floor(z - maxRelZ) - minBlockZ - 1, minLocalZ);
         int zEnd = Math.min(Mth.floor(z + maxRelZ) - minBlockZ + 1, maxLocalZ);
         for (int dz = zStart; dz <= zEnd; dz++) {
            int wz = chunkPos.getBlockZ(dz);
            double relZ = (wz + 0.5 - z) / horizontalRadius;
            if (relXSq + relZ * relZ >= 1.0) {
               continue;
            }

            reachedSurface.setFalse();
            int curSy = Integer.MIN_VALUE;
            LevelChunkSection curSec = null;
            boolean curAirOnly = false;

            for (int cy = maxY; cy > minY; cy--) {
               double relY = (cy - 0.5 - y) / verticalRadius;
               if (skipChecker.shouldSkip(context, relX, relY, relZ, cy)) {
                  continue;
               }

               if (!carvingMask.get(dx, cy, dz) || debug) {
                  carvingMask.set(dx, cy, dz);
                  if (skipAir) {
                     int sy = chunk.getSectionIndex(cy);
                     if (sy != curSy) {
                        curSy = sy;
                        if (sy >= 0 && sy < secCount) {
                           curSec = chunk.getSection(sy);
                           curAirOnly = curSec.hasOnlyAir();
                        } else {
                           curSec = null;
                           curAirOnly = false;
                        }
                     }

                     if (curAirOnly) {
                        continue;
                     }
                     if (curSec != null && curSec.getBlockState(dx, cy & 15, dz).isAir()) {
                        continue;
                     }
                  }

                  pos.set(wx, cy, wz);
                  carved |= this.carveBlock(context, config, chunk, biomeAccessor, carvingMask, pos, checkPos, aquifer, reachedSurface);
               }
            }
         }
      }

      return carved;
   }
}
