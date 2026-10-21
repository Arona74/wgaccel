package com.wgaccel.mixin.client;

import com.wgaccel.gpu.GpuPrepare;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the world's GPU programs are being made (before its spawn area), the world-loading screen shows them and a bar. */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {
   protected LevelLoadingScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "render", at = @At("TAIL"), require = 0)
   private void wgaccel$gpuProgress(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
      if (!GpuPrepare.active()) {
         return;
      }

      int centre = this.width / 2;
      int y = 24;
      graphics.drawCenteredString(this.font, GpuPrepare.status(), centre, y, 0xFFFFFF);
      int width = Math.min(240, this.width - 32);
      int left = centre - width / 2;
      int top = y + 14;
      graphics.fill(left - 1, top - 1, left + width + 1, top + 6, 0xFF808080);
      graphics.fill(left, top, left + width, top + 5, 0xFF202020);
      // Programs give no progress while they compile: the finished share, plus a sweep so the bar visibly moves.
      int done = (int)(width * GpuPrepare.progress());
      graphics.fill(left, top, left + done, top + 5, 0xFF3FAF5F);
      long sweep = (System.nanoTime() - GpuPrepare.startedNanos()) / 20_000_000L % Math.max(1, width - done);
      graphics.fill(left + done + (int)sweep, top, Math.min(left + width, left + done + (int)sweep + 12), top + 5, 0xFF7FD79F);
   }
}
