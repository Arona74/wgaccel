package com.wgaccel.client;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup, called only on the client (its classes do not exist on a dedicated server). */
public final class ClientSetup {
   private ClientSetup() {
   }

   /** The mods list's Config button opens the GPU screen. */
   public static void register(ModContainer container) {
      container.registerExtensionPoint(IConfigScreenFactory.class, new IConfigScreenFactory() {
         @Override
         public Screen createScreen(ModContainer mod, Screen parent) {
            return new GpuScreen(parent);
         }
      });
   }
}
