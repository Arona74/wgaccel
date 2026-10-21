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

/** Times turning a chunk into save data (before it is compressed and written). */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
   @WrapMethod(method = "write")
   private static CompoundTag wgaccel$write(ServerLevel level, ChunkAccess chunk, Operation<CompoundTag> original) {
      long start = System.nanoTime();
      try {
         return original.call(level, chunk);
      } finally {
         Stats.record(Stage.SERIALIZE, System.nanoTime() - start);
      }
   }
}
