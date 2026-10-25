package com.wgaccel.fabric;

import com.wgaccel.WgaCommands;
import com.wgaccel.WorldgenAccelerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;

/** Fabric's entry point: Fabric API's events passed on to {@link WorldgenAccelerator}. */
public final class WorldgenAcceleratorFabric implements ModInitializer {
   /** Chunk-load listener phases around every other mod's (NeoForge's priorities HIGHEST and LOWEST). */
   private static final ResourceLocation FIRST = ResourceLocation.fromNamespaceAndPath(WorldgenAccelerator.MOD_ID, "first");
   private static final ResourceLocation LAST = ResourceLocation.fromNamespaceAndPath(WorldgenAccelerator.MOD_ID, "last");

   @Override
   public void onInitialize() {
      if (!WorldgenAccelerator.start()) {
         ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WorldgenAccelerator.offMessage(handler.player));
         return;
      }

      ServerWorldEvents.LOAD.register((server, level) -> WorldgenAccelerator.onLevelLoad(level));
      ServerLifecycleEvents.SERVER_STARTED.register(WorldgenAccelerator::onServerStarted);
      ServerLifecycleEvents.SERVER_STOPPING.register(server -> WorldgenAccelerator.onServerStopping());
      ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WorldgenAccelerator.onPlayerLoggedIn(handler.player));
      CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> WgaCommands.register(dispatcher));
      ServerChunkEvents.CHUNK_LOAD.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
      ServerChunkEvents.CHUNK_LOAD.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
      ServerChunkEvents.CHUNK_LOAD.register(FIRST, (level, chunk) -> WorldgenAccelerator.onChunkLoadFirst(level));
      ServerChunkEvents.CHUNK_LOAD.register(LAST, (level, chunk) -> WorldgenAccelerator.onChunkLoadLast(level));
   }
}
