package com.wgaccel.mixin;

import com.wgaccel.platform.Mc;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Direct-section getBlock AND setBlock for SurfaceSystem$1 on CPU:
 * 1. Replaces ThreadLocalRandom.current().nextInt(256) per read with a fast (++wgaccel$calls & 255) == 0 counter.
 * 2. Redirects setBlock to write directly into LevelChunkSection and skip Heightmap.update when y <= floor - 2
 *    (matching GpuSurface.write's heightmap floor optimization).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceSystem$1")
public abstract class SurfaceColumnMixin {
   @Unique
   private static volatile boolean wgaccel$off;
   @Unique
   private ChunkAccess wgaccel$chunk;
   @Unique
   private LevelChunkSection[] wgaccel$sections;
   @Unique
   private Heightmap[] wgaccel$maps;
   @Unique
   private int[] wgaccel$firsts;
   @Unique
   private int wgaccel$floor;
   @Unique
   private int wgaccel$lastX = Integer.MIN_VALUE;
   @Unique
   private int wgaccel$lastZ = Integer.MIN_VALUE;
   @Unique
   private int wgaccel$minY;
   @Unique
   private int wgaccel$maxY;
   @Unique
   private int wgaccel$minSection;
   @Unique
   private int wgaccel$calls;

   @Unique
   private void wgaccel$initChunk(ChunkAccess chunk) {
      this.wgaccel$chunk = chunk;
      this.wgaccel$sections = chunk.getSections();
      this.wgaccel$minY = Mc.minY(chunk);
      this.wgaccel$maxY = Mc.maxY(chunk);
      this.wgaccel$minSection = Mc.minSection(chunk);
      this.wgaccel$lastX = Integer.MIN_VALUE;
      this.wgaccel$lastZ = Integer.MIN_VALUE;
      if (this.wgaccel$minY % 16 == 0 && !chunk.getPersistedStatus().isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
         EnumSet<Heightmap.Types> types = chunk.getPersistedStatus().heightmapsAfter();
         Heightmap[] maps = new Heightmap[types.size()];
         int i = 0;
         for (Heightmap.Types t : types) {
            if (!chunk.hasPrimedHeightmap(t)) {
               maps = null;
               break;
            }
            maps[i++] = chunk.getOrCreateHeightmapUnprimed(t);
         }
         this.wgaccel$maps = maps;
         this.wgaccel$firsts = maps != null ? new int[maps.length] : null;
      } else {
         this.wgaccel$maps = null;
         this.wgaccel$firsts = null;
      }
   }

   @Redirect(method = "getBlock", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 0)
   private BlockState wgaccel$fastRead(ChunkAccess chunk, BlockPos pos) {
      if (wgaccel$off || chunk.getClass() != ProtoChunk.class || !com.wgaccel.Config.FAST_SURFACE_READS.get()) {
         return chunk.getBlockState(pos);
      }

      if (this.wgaccel$chunk != chunk) {
         this.wgaccel$initChunk(chunk);
      }

      int y = pos.getY();
      BlockState state;
      if (y < this.wgaccel$minY || y >= this.wgaccel$maxY) {
         state = Blocks.VOID_AIR.defaultBlockState();
      } else {
         LevelChunkSection section = this.wgaccel$sections[(y >> 4) - this.wgaccel$minSection];
         state = section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
      }

      if ((++this.wgaccel$calls & 255) == 0) {
         BlockState real = chunk.getBlockState(pos);
         if (real != state) {
            wgaccel$off = true;
            com.wgaccel.WgaChat.warnOff("WGA fast surface reads off: {} at {} read as {} instead of the game's {}", chunk.getPos(), pos, state, real);
            return real;
         }
      }

      return state;
   }

   @Redirect(method = "setBlock", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"), require = 0)
   private BlockState wgaccel$fastWrite(ChunkAccess chunk, BlockPos pos, BlockState state, boolean isMoving) {
      if (wgaccel$off || chunk.getClass() != ProtoChunk.class || !com.wgaccel.Config.FAST_SURFACE_READS.get()) {
         return chunk.setBlockState(pos, state, isMoving);
      }

      if (this.wgaccel$chunk != chunk) {
         this.wgaccel$initChunk(chunk);
      }

      Heightmap[] maps = this.wgaccel$maps;
      int y = pos.getY();
      if (maps == null || y < this.wgaccel$minY || y >= this.wgaccel$maxY) {
         return chunk.setBlockState(pos, state, isMoving);
      }

      int lx = pos.getX() & 15;
      int lz = pos.getZ() & 15;
      int[] firsts = this.wgaccel$firsts;
      if (lx != this.wgaccel$lastX || lz != this.wgaccel$lastZ) {
         this.wgaccel$lastX = lx;
         this.wgaccel$lastZ = lz;
         int low = Integer.MAX_VALUE;
         for (int m = 0; m < maps.length; m++) {
            int f = maps[m].getFirstAvailable(lx, lz);
            firsts[m] = f;
            if (f < low) {
               low = f;
            }
         }
         this.wgaccel$floor = low;
      }

      LevelChunkSection section = this.wgaccel$sections[(y >> 4) - this.wgaccel$minSection];
      boolean wasOnlyAir = section.hasOnlyAir();
      if (wasOnlyAir && state.isAir()) {
         return state;
      }

      BlockState prev = section.setBlockState(lx, y & 15, lz, state, false);
      if (!state.getFluidState().isEmpty()) {
         chunk.markPosForPostprocessing(pos);
      }

      if (y > this.wgaccel$floor - 2) {
         boolean changed = false;
         for (int m = 0; m < maps.length; m++) {
            if (y > firsts[m] - 2 && maps[m].update(lx, y, lz, state)) {
               firsts[m] = maps[m].getFirstAvailable(lx, lz);
               changed = true;
            }
         }
         if (changed) {
            int low = Integer.MAX_VALUE;
            for (int f : firsts) {
               if (f < low) {
                  low = f;
               }
            }
            this.wgaccel$floor = low;
         }
      }

      return prev;
   }
}
