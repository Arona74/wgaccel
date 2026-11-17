package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;

/** A new chunk's entities (mobs, Create trains placed by worldgen) joining the world, on the main thread. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkEntitiesMixin {
   @WrapMethod(method = "postLoadProtoChunk")
   private static void wgaccel$entities(ServerLevel level, ValueInput.ValueInputList entities, Operation<Void> original) {
      long start = System.nanoTime();
      try {
         original.call(level, entities);
      } finally {
         Stats.record(Stage.ENTITIES, System.nanoTime() - start);
         Stats.entitiesLoaded((int)entities.stream().count());
      }
   }
}
