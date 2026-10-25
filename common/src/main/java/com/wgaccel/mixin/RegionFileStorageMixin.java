package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times writing save data into the region files: the data written out, compressed (Deflate unless the server chose
 * otherwise) and stored. Chunks, entities and points of interest all pass through here.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
   @WrapMethod(method = "write")
   private void wgaccel$write(ChunkPos pos, @Nullable CompoundTag tag, Operation<Void> original) throws IOException {
      long start = System.nanoTime();
      try {
         original.call(pos, tag);
      } finally {
         Stats.record(Stage.REGION_WRITE, System.nanoTime() - start);
      }
   }
}
