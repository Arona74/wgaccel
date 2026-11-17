package com.wgaccel.mixin.access;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Aquifer.FluidStatus.class)
public interface FluidStatusAccessor {
   @Accessor("fluidLevel")
   int wgaccel$level();

   @Accessor("fluidType")
   BlockState wgaccel$type();
}
