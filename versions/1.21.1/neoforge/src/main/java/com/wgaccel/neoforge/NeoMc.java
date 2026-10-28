package com.wgaccel.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** NeoForge's calls whose form differs between Minecraft versions (see platform/Mc): 1.21.1's. */
final class NeoMc {
   private NeoMc() {
   }

   /** Client or dedicated server. */
   static Dist dist() {
      return FMLEnvironment.dist;
   }
}
