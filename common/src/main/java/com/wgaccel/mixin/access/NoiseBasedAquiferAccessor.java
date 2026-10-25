package com.wgaccel.mixin.access;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The aquifer's grid and fluid statuses for the GPU noise fill (step G2.5), read by vanilla's own fields and method. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface NoiseBasedAquiferAccessor {
   @Accessor("minGridX")
   int wgaccel$minGridX();

   @Accessor("minGridY")
   int wgaccel$minGridY();

   @Accessor("minGridZ")
   int wgaccel$minGridZ();

   @Accessor("gridSizeX")
   int wgaccel$gridSizeX();

   @Accessor("gridSizeZ")
   int wgaccel$gridSizeZ();

   @Accessor("aquiferLocationCache")
   long[] wgaccel$locationCache();

   @Accessor("positionalRandomFactory")
   PositionalRandomFactory wgaccel$random();

   @Accessor("globalFluidPicker")
   Aquifer.FluidPicker wgaccel$picker();

   @Invoker("getAquiferStatus")
   Aquifer.FluidStatus wgaccel$status(long packedCentre);
}
