package com.wgaccel.fabric.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The biome's own climate, which Fabric API's biome modifications rewrite (FabricHooks). */
@Mixin(Biome.class)
public interface BiomeAccessor {
   @Accessor("climateSettings")
   Biome.ClimateSettings wgaccel$climateSettings();
}
