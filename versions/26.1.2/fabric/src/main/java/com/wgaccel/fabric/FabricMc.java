package com.wgaccel.fabric;

import com.wgaccel.WorldgenAccelerator;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** Fabric API's calls whose form differs between Minecraft versions (see platform/Mc): 26.1's. */
final class FabricMc {
   /** Chunk-load listener phases around every other mod's (NeoForge's priorities HIGHEST and LOWEST). */
   private static final Identifier FIRST = Identifier.fromNamespaceAndPath(WorldgenAccelerator.MOD_ID, "first");
   private static final Identifier LAST = Identifier.fromNamespaceAndPath(WorldgenAccelerator.MOD_ID, "last");

   private FabricMc() {
   }

   static void onLevelLoad(Consumer<ServerLevel> listener) {
      ServerLevelEvents.LOAD.register((server, level) -> listener.accept(level));
   }

   /** first before every other mod's chunk-load listener, last after them. */
   static void onChunkLoad(Consumer<ServerLevel> first, Consumer<ServerLevel> last) {
      ServerChunkEvents.CHUNK_LOAD.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
      ServerChunkEvents.CHUNK_LOAD.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
      ServerChunkEvents.CHUNK_LOAD.register(FIRST, (level, chunk, generated) -> first.accept(level));
      ServerChunkEvents.CHUNK_LOAD.register(LAST, (level, chunk, generated) -> last.accept(level));
   }
}
