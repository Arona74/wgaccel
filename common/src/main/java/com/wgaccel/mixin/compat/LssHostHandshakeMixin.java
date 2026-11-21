package com.wgaccel.mixin.compat;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LOD Server Support (LSS, MIT) on a LAN host: once the world is opened to LAN, LSS's client also asks its own integrated
 * server for LODs, and that server makes every chunk of the LOD distance around the host in full, while wgaccel's
 * Voxy LOD generator already draws the same area into the same Voxy world, far faster. With the generator on (config
 * voxyGenerator, Voxy installed), the host skips that self-handshake (LSS's own test of whether the host has a LOD
 * consumer answers no); the LAN guests are served by LSS as before. In plain single player LSS starts no service at
 * all, so this never comes into play there.
 */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.client.ClientNetGlue")
public abstract class LssHostHandshakeMixin {
   @Unique
   private static volatile boolean wgaccel$said;

   @Inject(method = "hostHandshakeHasConsumer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private static void wgaccel$leaveHostToVoxyGenerator(CallbackInfoReturnable<Boolean> cir) {
      if (Config.VOXY_GENERATOR.get() && com.wgaccel.platform.Platform.get().isModLoaded("voxy")) {
         if (!wgaccel$said) {
            wgaccel$said = true;
            WorldgenAccelerator.LOG.info("WGA Voxy LOD generator: the LAN host's own LOD Server Support session skipped (wgaccel draws"
               + " the LODs around you; LAN players still get theirs from LOD Server Support)");
         }

         cir.setReturnValue(false);
      }
   }
}
