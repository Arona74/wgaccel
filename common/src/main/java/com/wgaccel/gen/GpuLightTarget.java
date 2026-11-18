package com.wgaccel.gen;

import com.wgaccel.gpu.LightGpu;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Bridge implemented by {@link com.wgaccel.mixin.ThreadedLevelLightEngineGpuLightMixin} for installing precomputed GPU light. */
public interface GpuLightTarget {
   CompletableFuture<ChunkAccess> wgaccel$applyGpuLight(ChunkAccess chunk, int activeMinSec, LightGpu.Result result);
}
