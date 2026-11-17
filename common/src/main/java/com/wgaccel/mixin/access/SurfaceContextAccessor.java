package com.wgaccel.mixin.access;

import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The surface rule context's values for the GPU surface check (step G3.2), read where each rule call is made. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public interface SurfaceContextAccessor {
   @Accessor("blockX")
   int wgaccel$x();

   @Accessor("blockY")
   int wgaccel$y();

   @Accessor("blockZ")
   int wgaccel$z();

   @Accessor("stoneDepthAbove")
   int wgaccel$stoneAbove();

   @Accessor("stoneDepthBelow")
   int wgaccel$stoneBelow();

   @Accessor("waterHeight")
   int wgaccel$water();

   @Accessor("surfaceDepth")
   int wgaccel$depth();

   @Accessor("lastUpdateXZ")
   long wgaccel$lastUpdateXZ();

   @Accessor("biome")
   Supplier<Holder<Biome>> wgaccel$biome();

   @Invoker("getSurfaceSecondary")
   double wgaccel$secondary();

   @Invoker("getMinSurfaceLevel")
   int wgaccel$minSurface();
}
