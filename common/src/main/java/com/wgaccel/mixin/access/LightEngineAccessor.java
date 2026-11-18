package com.wgaccel.mixin.access;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightEngine.class)
public interface LightEngineAccessor {
   @Accessor("storage")
   LayerLightSectionStorage<?> wgaccel$storage();
}
