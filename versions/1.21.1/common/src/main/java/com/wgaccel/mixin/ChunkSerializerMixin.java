package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;

/** Times turning a chunk into save data (before it is compressed and written); counts chunks saved and read by status. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
   @WrapMethod(method = "write")
   private static CompoundTag wgaccel$write(ServerLevel level, ChunkAccess chunk, Operation<CompoundTag> original) {
      long start = System.nanoTime();
      CompoundTag tag = null;
      try {
         tag = original.call(level, chunk);
         return tag;
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
         com.wgaccel.stats.ChunkIo.written(chunk.getPos().toLong(), chunk.getPersistedStatus().getName(), tag == null ? 0L : tag.sizeInBytes());
      }
   }

   /** Counts the chunks read back, by status (ChunkIo). */
   @WrapMethod(method = "read")
   private static net.minecraft.world.level.chunk.ProtoChunk wgaccel$read(ServerLevel level, net.minecraft.world.entity.ai.village.poi.PoiManager poi,
         net.minecraft.world.level.chunk.storage.RegionStorageInfo info, net.minecraft.world.level.ChunkPos pos, CompoundTag tag,
         Operation<net.minecraft.world.level.chunk.ProtoChunk> original) {
      net.minecraft.world.level.chunk.ProtoChunk chunk = original.call(level, poi, info, pos, tag);
      if (chunk != null) {
         com.wgaccel.stats.ChunkIo.read(pos.toLong(), chunk.getPersistedStatus().getName());
      }

      return chunk;
   }
}
