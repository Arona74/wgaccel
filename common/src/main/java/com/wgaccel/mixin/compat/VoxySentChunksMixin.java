package com.wgaccel.mixin.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chunks the server sends to a player, for the Voxy LOD generator: Voxy takes its LODs from the chunks a client
 * receives, so those (and only those) need no LOD of ours. A chunk generated in full but never sent (the ring the server
 * finishes past the view distance, the spawn area made before anyone joined) is left to the generator. Here rather than
 * in the main mixins: those are all off when another mod's OpenCL conflicts, and this one must hold whenever the
 * generator runs.
 */
@Mixin(PlayerChunkSender.class)
public abstract class VoxySentChunksMixin {
   @Inject(method = "sendChunk", at = @At("HEAD"), require = 0)
   private static void wgaccel$sent(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, CallbackInfo info) {
      com.wgaccel.gen.VoxyAuto.sent(level, chunk.getPos());
   }
}
