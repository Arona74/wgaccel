package com.wgaccel.mixin.client;

import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Voxy LOD generator's progress as F3 lines ({@link com.wgaccel.client.VoxyOverlay#debugLines}), after the system specs entry (the right column)
 * (26.1's F3 is made of entries).
 */
@Mixin(DebugEntrySystemSpecs.class)
public abstract class DebugVoxyMixin {
   @Inject(method = "display", at = @At("TAIL"), require = 0)
   private void wgaccel$voxyLines(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk, CallbackInfo info) {
      for (String line : com.wgaccel.client.VoxyOverlay.debugLines()) {
         displayer.addLine(line);
      }
   }
}
