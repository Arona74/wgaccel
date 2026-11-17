package com.wgaccel.mixin.access;

import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A heightmap's block test and its raw height setter (the surface writer works heights out after its writes). */
@Mixin(Heightmap.class)
public interface HeightmapAccessor {
   @Accessor("isOpaque")
   Predicate<BlockState> wgaccel$opaque();

   @Invoker("setHeight")
   void wgaccel$setHeight(int x, int z, int height);
}
