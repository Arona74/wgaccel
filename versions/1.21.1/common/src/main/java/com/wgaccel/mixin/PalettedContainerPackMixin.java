package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.Config;
import com.wgaccel.gen.FastPack;
import java.util.function.IntUnaryOperator;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1. Accelerates PalettedContainer.pack via FastPack.swap.
 * 2. Eliminates the 4,096-element virtual copy loop in PalettedContainer.onResize when resizing a 0-bit
 *    SingleValuePalette / ZeroBitStorage section (such as the 150,249 uniform stone sections created by
 *    GpuNoiseFill / SectionBuilder.uniform) into a 4-bit LinearPalette / SimpleBitStorage during FEATURES:
 *    since the single old value receives palette ID 0 in the new empty palette and the newly allocated
 *    SimpleBitStorage long[] is already zero-initialized, all 4,096 entries are already 0 without looping.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerPackMixin {
   @WrapOperation(method = "pack", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/PalettedContainer;swapPalette([ILjava/util/function/IntUnaryOperator;)V"))
   private static void wgaccel$localIds(int[] values, IntUnaryOperator convert, Operation<Void> original) {
      if (!FastPack.swap(values, convert)) original.call(values, convert);
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   @WrapOperation(method = "onResize", require = 0, at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/chunk/PalettedContainer$Data;copyFrom(Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/util/BitStorage;)V"))
   private void wgaccel$fastZeroBitCopyFrom(Object newData, Palette<?> oldPalette, BitStorage oldStorage, Operation<Void> original) {
      if (Config.FAST_CHUNK_PACK.get() && oldStorage instanceof ZeroBitStorage && oldStorage.getSize() > 0) {
         // Register the single uniform state at index 0 in the new palette; if it receives ID 0,
         // the freshly allocated SimpleBitStorage long[] is already all 0L across all 4,096 cells!
         int newId = ((PalettedContainer.Data) newData).palette().idFor(oldPalette.valueFor(0));
         if (newId == 0) {
            return;
         }
      }
      original.call(newData, oldPalette, oldStorage);
   }
}
