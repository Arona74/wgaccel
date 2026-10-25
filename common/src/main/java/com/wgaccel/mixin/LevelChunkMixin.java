package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/** Times a full chunk's block entities joining the world (on the main thread). */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
   @WrapMethod(method = "registerAllBlockEntitiesAfterLevelLoad")
   private void wgaccel$blockEntities(Operation<Void> original) {
      long start = System.nanoTime();
      try {
         original.call();
      } finally {
         Stats.record(Stage.BLOCK_ENTITIES, System.nanoTime() - start);
      }
   }
}
