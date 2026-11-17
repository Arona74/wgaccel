package com.wgaccel.mixin.access;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
   @Invoker("storingLightForSection")
   boolean wgaccel$storingLightForSection(long sectionPos);

   @Invoker("getDataLayerData")
   DataLayer wgaccel$getDataLayerData(long sectionPos);
}
