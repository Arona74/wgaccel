package com.wgaccel.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.wgaccel.client.WgaScreen;

/** With ModMenu installed, its Config button opens the settings screen (as NeoForge's mods list does). */
public final class WgaModMenu implements ModMenuApi {
   @Override
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return WgaScreen::new;
   }
}
