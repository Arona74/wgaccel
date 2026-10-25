package com.wgaccel;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * A GPU step or check that turns itself off says so in the chat too, not only in the log: twice a whole benchmark ran
 * with the GPU surface off unseen (0.9.48, 0.9.51). Logged at once; posted to the players at the next report (every
 * 10 s), on the server thread.
 */
public final class WgaChat {
   private static final ConcurrentLinkedQueue<String> WAITING = new ConcurrentLinkedQueue<>();

   private WgaChat() {
   }

   /** As LOG.warn(pattern, arguments), and the message to the chat. */
   public static void warnOff(String pattern, Object... arguments) {
      WorldgenAccelerator.LOG.warn(pattern, arguments);
      if (WAITING.size() < 20) {
         WAITING.add(org.slf4j.helpers.MessageFormatter.arrayFormat(pattern, arguments).getMessage());
      }
   }

   /** From the reporter: the waiting messages to every player. */
   public static void flush() {
      if (WAITING.isEmpty()) {
         return;
      }

      MinecraftServer server = WorldgenAccelerator.server();
      if (server == null || server.getPlayerList().getPlayerCount() == 0) {
         return;
      }

      server.execute(() -> {
         String message;
         while ((message = WAITING.poll()) != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("[Worldgen Accelerator] " + message), false);
         }
      });
   }
}
