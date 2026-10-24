package com.wgaccel.mixin.access;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StateTestingPredicate.class)
public interface StateTestingPredicateAccessor {
   @Accessor("offset")
   Vec3i wgaccel$offset();

   @Invoker("test")
   boolean wgaccel$testState(BlockState state);
}
