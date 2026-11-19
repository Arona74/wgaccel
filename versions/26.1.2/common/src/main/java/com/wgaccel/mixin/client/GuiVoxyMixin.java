package com.wgaccel.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Voxy LOD generator's progress panel over the HUD ({@link com.wgaccel.client.VoxyOverlay}). */
@Mixin(Gui.class)
public abstract class GuiVoxyMixin {
   @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"), require = 0)
   private void wgaccel$voxyProgress(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo info) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.options.hideGui) {
         return;
      }

      com.wgaccel.client.VoxyOverlay.render(new com.wgaccel.client.Canvas(graphics), minecraft.font, graphics.guiWidth(), graphics.guiHeight(),
         minecraft.getDebugOverlay().showDebugScreen());
   }
}
