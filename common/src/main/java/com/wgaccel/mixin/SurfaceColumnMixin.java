package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.platform.Mc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Faster CPU surface (the user's pick; vanilla's surface runs on the CPU): SurfaceSystem's block column reads every
 * block of every column through ProtoChunk.getBlockState, which checks the build height and works the section index out
 * through the level's height accessors each time (0.9.88-0.9.91 vanilla profiles: these reads ~5% of the CPU). Here the
 * column keeps the chunk's height range and sections from its first read and reads the section directly: the same as
 * ProtoChunk.getBlockState (void air outside the height, air in an air-only section, else the section's block). About
 * 1 in 256 reads (plain ProtoChunks only: an ImposterProtoChunk keeps its blocks elsewhere) is compared with the game's own call; a difference turns this off for good.
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
   private int wgaccel$minY;
   @Unique
   private int wgaccel$maxY;
   @Unique
   private int wgaccel$minSection;

   @WrapOperation(method = "getBlock", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
   private BlockState wgaccel$fastRead(ChunkAccess chunk, BlockPos pos, Operation<BlockState> original) {
      if (wgaccel$off || chunk.getClass() != ProtoChunk.class || !com.wgaccel.Config.FAST_SURFACE_READS.get()) {
         return original.call(chunk, pos);
      }

      if (this.wgaccel$chunk != chunk) {
         this.wgaccel$chunk = chunk;
         this.wgaccel$sections = chunk.getSections();
         this.wgaccel$minY = Mc.minY(chunk);
         this.wgaccel$maxY = Mc.maxY(chunk);
         this.wgaccel$minSection = Mc.minSection(chunk);
      }

      int y = pos.getY();
      BlockState state;
      if (y < this.wgaccel$minY || y >= this.wgaccel$maxY) {
         state = Blocks.VOID_AIR.defaultBlockState();
      } else {
         LevelChunkSection section = this.wgaccel$sections[(y >> 4) - this.wgaccel$minSection];
         state = section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
      }

      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(256) == 0) {
         BlockState real = original.call(chunk, pos);
         if (real != state) {
            wgaccel$off = true;
            com.wgaccel.WgaChat.warnOff("WGA fast surface reads off: {} at {} read as {} instead of the game's {}", chunk.getPos(), pos, state, real);
            return real;
         }
      }

      return state;
   }
}
