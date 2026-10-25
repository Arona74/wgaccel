package com.wgaccel.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The world-loading screen with Worldgen Accelerator's panels: the GPU programs' bar and checklist, and the hardware ({@link com.wgaccel.client.LoadingPanel}). */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
   protected LevelLoadingScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "render", at = @At("TAIL"), require = 0)
   private void wgaccel$gpuProgress(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
      // The whole time the screen is up (0.17.2), not only while GPU programs are being made; nothing when the mod is
      // off (C2ME's OpenCL module).
      if (!com.wgaccel.Conflicts.openCl()) {
         com.wgaccel.client.LoadingPanel.render(graphics, this.font, this.width, this.height);
      }
   }
}
