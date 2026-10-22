package com.wgaccel.mixin;

import com.wgaccel.gen.GpuSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fluid-mark tracing on checked chunks ({@link GpuSurface#traceMark}); changes nothing. */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMarkTraceMixin {
   @Inject(method = "markPosForPostprocessing", at = @At("HEAD"), require = 0)
   private void wgaccel$trace(BlockPos pos, CallbackInfo info) {
      GpuSurface.traceMark((ProtoChunk)(Object)this, pos);
   }
}
