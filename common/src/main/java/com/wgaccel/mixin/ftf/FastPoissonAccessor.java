package com.wgaccel.mixin.ftf;

import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * FreeTerraForged's Poisson-disk tree placement (its forests' trees), for the Voxy LOD trees ({@link com.wgaccel.ftf.FtfTrees}):
 * its spots from the seed and chunk alone, without its getPositions(PlacementContext...), which asks the level for the
 * chunk (a chunk load off the server thread).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.feature.placement.poisson.FastPoissonModifier")
public interface FastPoissonAccessor {
   @Invoker("getPositions")
   Stream<BlockPos> wgaccel$positions(int seed, long levelSeed, int chunkX, int chunkZ, WorldLookup lookup);

   @Accessor("biomeFade")
   float wgaccel$biomeFade();
}
