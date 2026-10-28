package com.wgaccel.neoforge;

import com.wgaccel.WgaCommands;
import com.wgaccel.WorldgenAccelerator;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge's entry point: its events passed on to {@link WorldgenAccelerator}. */
@Mod(WorldgenAccelerator.MOD_ID)
public final class WorldgenAcceleratorNeoForge {
   public WorldgenAcceleratorNeoForge(ModContainer container) {
      if (!WorldgenAccelerator.start()) {
         NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> WorldgenAccelerator.offMessage(event.getEntity()));
         return;
      }

      if (NeoMc.dist().isClient()) {
         ClientSetup.register(container);
      }

      NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
         if (event.getLevel() instanceof ServerLevel level) {
            WorldgenAccelerator.onLevelLoad(level);
         }
      });
      NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> WorldgenAccelerator.onServerStarted(event.getServer()));
      NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WorldgenAccelerator.onServerStopping());
      NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> WorldgenAccelerator.onPlayerLoggedIn(event.getEntity()));
      NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> WgaCommands.register(event.getDispatcher()));
      NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ChunkEvent.Load event) -> WorldgenAccelerator.onChunkLoadFirst(event.getLevel()));
      NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ChunkEvent.Load event) -> WorldgenAccelerator.onChunkLoadLast(event.getLevel()));
   }
}
