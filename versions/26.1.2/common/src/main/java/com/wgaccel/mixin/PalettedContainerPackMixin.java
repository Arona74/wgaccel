package com.wgaccel.mixin;

import com.wgaccel.gen.FastPack;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Chunk saving's pack: a section's values numbered anew with one palette question per old id ({@link FastPack}). */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerPackMixin {
   @Redirect(method = "pack", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/PalettedContainer;reencodeContents(Lnet/minecraft/util/BitStorage;Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/world/level/chunk/Palette;)[I"))
   private <T> int[] wgaccel$reencode(BitStorage storage, Palette<T> oldPalette, Palette<T> newPalette) {
      return FastPack.reencode(storage, oldPalette, newPalette);
   }
}
