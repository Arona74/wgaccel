package com.wgaccel.mixin.access;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The beardifier's structure pieces and junctions (lists on 26.1; read through platform/Mc). */
@Mixin(Beardifier.class)
public interface BeardifierAccessor {
   @Accessor("pieces")
   List<Beardifier.Rigid> wgaccel$pieces();

   @Accessor("junctions")
   List<JigsawJunction> wgaccel$junctions();
}
