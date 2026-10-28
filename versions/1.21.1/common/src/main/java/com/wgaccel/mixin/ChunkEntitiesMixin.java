package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;

/** A new chunk's entities (mobs, Create trains placed by worldgen) joining the world, on the main thread. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkEntitiesMixin {
   @WrapMethod(method = "postLoadProtoChunk")
   private static void wgaccel$entities(ServerLevel level, List<CompoundTag> entities, Operation<Void> original) {
      long start = System.nanoTime();
      try {
         original.call(level, entities);
      } finally {
         Stats.record(Stage.ENTITIES, System.nanoTime() - start);
         Stats.entitiesLoaded(entities.size());
      }
   }
}
