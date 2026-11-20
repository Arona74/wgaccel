package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times turning a chunk into save data (before it is compressed and written): 1.21.1's ChunkSerializer.write is two
 * steps here, the copy of the chunk (copyOf) and its save data made from the copy (write). Counts the chunks saved and
 * read back by status (ChunkIo).
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
   @WrapMethod(method = "copyOf")
   private static SerializableChunkData wgaccel$copyOf(ServerLevel level, ChunkAccess chunk, Operation<SerializableChunkData> original) {
      long start = System.nanoTime();
      try {
         SerializableChunkData data = original.call(level, chunk);
         com.wgaccel.gen.VoxyAuto.saved(level, chunk);
         return data;
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
      }
   }

   @WrapMethod(method = "write")
   private CompoundTag wgaccel$write(Operation<CompoundTag> original) {
      long start = System.nanoTime();
      CompoundTag tag = null;
      try {
         tag = original.call();
         return tag;
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
         SerializableChunkData self = (SerializableChunkData)(Object)this;
         com.wgaccel.stats.ChunkIo.written(com.wgaccel.platform.Mc.key(self.chunkPos()), self.chunkStatus(), tag == null ? 0L : tag.sizeInBytes());
      }
   }

   /** Counts the chunks read back, by status (ChunkIo). */
   @WrapMethod(method = "read")
   private net.minecraft.world.level.chunk.ProtoChunk wgaccel$read(ServerLevel level, net.minecraft.world.entity.ai.village.poi.PoiManager poi,
         net.minecraft.world.level.chunk.storage.RegionStorageInfo info, net.minecraft.world.level.ChunkPos pos,
         Operation<net.minecraft.world.level.chunk.ProtoChunk> original) {
      net.minecraft.world.level.chunk.ProtoChunk chunk = original.call(level, poi, info, pos);
      if (chunk != null) {
         com.wgaccel.stats.ChunkIo.read(com.wgaccel.platform.Mc.key(pos), chunk.getPersistedStatus());
      }

      return chunk;
   }
}
