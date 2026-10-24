package com.wgaccel.mixin.client;

import com.wgaccel.client.WgaScreen;
import com.wgaccel.client.LogoButton;
import java.util.TreeSet;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A 20 x 20 button with the mod's logo in Options, left of the field of view's slider, opening the settings screen
 * ({@link WgaScreen}). Distant Horizons puts its own icon there; with it, ours goes right under it.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
   protected OptionsScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"))
   private void wgaccel$gpuButton(CallbackInfo info) {
      // The rows of widgets below the title, top down: the field of view's row first, then the buttons' grid.
      TreeSet<Integer> rows = new TreeSet<>();
      for (GuiEventListener child : this.children()) {
         if (child instanceof AbstractWidget widget && widget.getY() > 20) {
            rows.add(widget.getY());
         }
      }

      // Left of the field of view's slider, where Distant Horizons puts its icon; with that mod, under its icon instead
      // (LogoButton moves there when first drawn).
      int fovRow = rows.isEmpty() ? 6 : rows.first();
      int y = fovRow;
      int left = this.width / 2 - 155;
      for (GuiEventListener child : this.children()) {
         if (child instanceof AbstractWidget widget && widget.getY() == fovRow && widget.getWidth() >= 100) {
            left = Math.min(left, widget.getX());
         }
      }

      int x = left - 24 >= 2 ? left - 24 : 2;
      // Lines itself up under another mod's small button there (Distant Horizons') when first drawn, all buttons made.
      this.addRenderableWidget(new LogoButton(x, y, (Screen)(Object)this, fovRow, left));
   }
}
