package com.wgaccel.mixin.access;

import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The aquifer's fluid status of one grid cell, by vanilla's own method (by the cell's index in its caches on 26.1; called through platform/Mc). */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface AquiferStatusInvoker {
   @Invoker("getAquiferStatus")
   Aquifer.FluidStatus wgaccel$status(int index);

   @org.spongepowered.asm.mixin.gen.Accessor("skipSamplingAboveY")
   int wgaccel$skipSamplingAboveY();
}
