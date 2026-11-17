package com.wgaccel.mixin.c2me;

import com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import com.ishland.c2me.rewrites.chunksystem.common.compat.internal_api.NewStatusHook;
import java.util.ArrayList;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 4 x 4 area step's status, added to C2ME's chunk status list just before BIOMES through C2ME's own hook for this.
 * Always added (C2ME builds the list before configs are read); with gpuAreaStep off it does nothing.
 */
@Mixin(value = NewStatusHook.class, remap = false)
public abstract class NewStatusHookMixin {
   @Inject(method = "beforeVanillaStatusRegister", at = @At("HEAD"))
   private static void wgaccel$addAreaStatus(ArrayList<NewChunkStatus> statuses, ChunkStatus status, CallbackInfo info) {
      if (status == ChunkStatus.BIOMES) {
         statuses.add(com.wgaccel.c2me.AreaStatus.create(statuses.size()));
      }
   }
}
