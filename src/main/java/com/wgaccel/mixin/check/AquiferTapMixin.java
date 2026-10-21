package com.wgaccel.mixin.check;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.DensityTap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GPU step G2.3's reference: every block of the noise fill hands its final density (as the game worked it out, C2ME's
 * compiled code included) to the aquifer; a tapped chunk's are copied here ({@link DensityTap}). Check runs only.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class AquiferTapMixin {
   @WrapMethod(method = "computeSubstance", require = 0)
   private BlockState wgaccel$tap(DensityFunction.FunctionContext context, double density, Operation<BlockState> original) {
      DensityTap.block(context, density);
      return original.call(context, density);
   }
}
