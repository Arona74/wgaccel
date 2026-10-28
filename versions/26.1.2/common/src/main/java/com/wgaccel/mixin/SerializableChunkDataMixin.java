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
 * steps here, the copy of the chunk (copyOf) and its save data made from the copy (write).
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
   @WrapMethod(method = "copyOf")
   private static SerializableChunkData wgaccel$copyOf(ServerLevel level, ChunkAccess chunk, Operation<SerializableChunkData> original) {
      long start = System.nanoTime();
      try {
         return original.call(level, chunk);
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
      }
   }

   @WrapMethod(method = "write")
   private CompoundTag wgaccel$write(Operation<CompoundTag> original) {
      long start = System.nanoTime();
      try {
         return original.call();
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
      }
   }
}
