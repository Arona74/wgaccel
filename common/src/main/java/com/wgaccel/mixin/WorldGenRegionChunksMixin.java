package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.RegionChunks;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** The features step's region chunks kept once found ({@link RegionChunks}). Optional (require 0), like the region's other mixin. */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionChunksMixin {
   @Shadow
   @Final
   private ChunkStep generatingStep;
   @Unique
   private RegionChunks.Entry[] wgaccel$chunks;

   @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", require = 0)
   private ChunkAccess wgaccel$chunk(int x, int z, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
      if (this.generatingStep.targetStatus() != ChunkStatus.FEATURES) {
         return original.call(x, z, status, load);
      }

      RegionChunks.Entry[] table = this.wgaccel$chunks;
      if (table == null) {
         // Made on first use (only features regions need one); a race makes two tables, each correct.
         table = this.wgaccel$chunks = RegionChunks.table();
      }

      ChunkAccess kept = RegionChunks.kept(table, x, z, status);
      if (kept != null) {
         return RegionChunks.sample() ? RegionChunks.verify(kept, original.call(x, z, status, load), x, z, this) : kept;
      }

      return RegionChunks.keep(table, x, z, status, original.call(x, z, status, load));
   }
}
