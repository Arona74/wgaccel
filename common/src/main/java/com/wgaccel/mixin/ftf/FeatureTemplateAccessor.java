package com.wgaccel.mixin.ftf;

import etcodehome.freeterraforged.world.worldgen.feature.template.template.BakedTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** FreeTerraForged's tree templates' blocks (each mirror and rotation), for the Voxy LOD trees ({@link com.wgaccel.ftf.FtfTrees}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.feature.template.template.FeatureTemplate")
public interface FeatureTemplateAccessor {
   @Accessor("template")
   BakedTemplate wgaccel$template();
}
