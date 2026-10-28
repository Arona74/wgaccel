package com.wgaccel.fabric;

import com.wgaccel.WgaCommands;
import com.wgaccel.WorldgenAccelerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Fabric's entry point: Fabric API's events passed on to {@link WorldgenAccelerator}. */
public final class WorldgenAcceleratorFabric implements ModInitializer {
   @Override
   public void onInitialize() {
      if (!WorldgenAccelerator.start()) {
         ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WorldgenAccelerator.offMessage(handler.player));
         return;
      }

      FabricMc.onLevelLoad(WorldgenAccelerator::onLevelLoad);
      ServerLifecycleEvents.SERVER_STARTED.register(WorldgenAccelerator::onServerStarted);
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> WorldgenAccelerator.onServerStopping());
      ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WorldgenAccelerator.onPlayerLoggedIn(handler.player));
      CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> WgaCommands.register(dispatcher));
      // Around every other mod's chunk-load listener (NeoForge's priorities HIGHEST and LOWEST).
      FabricMc.onChunkLoad(WorldgenAccelerator::onChunkLoadFirst, WorldgenAccelerator::onChunkLoadLast);
   }
}
