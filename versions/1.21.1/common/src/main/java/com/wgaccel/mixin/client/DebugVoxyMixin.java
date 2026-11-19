package com.wgaccel.mixin.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Voxy LOD generator's progress as F3 lines, in the right column ({@link com.wgaccel.client.VoxyOverlay#debugLines}). */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugVoxyMixin {
   @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true, require = 0)
   private void wgaccel$voxyLines(CallbackInfoReturnable<List<String>> info) {
      List<String> lines = com.wgaccel.client.VoxyOverlay.debugLines();
      if (lines.isEmpty()) {
         return;
      }

      List<String> all = new ArrayList<>(info.getReturnValue());
      all.add("");
      all.addAll(lines);
      info.setReturnValue(all);
   }
}
