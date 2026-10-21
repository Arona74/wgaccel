package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FTF's terrain-role step at the end of its biome decoration (river biomes where the terrain is a river): timed, and
 * (config {@code fasterBiomeChoice}) the column's terrain is read from the chunk's tile once per column instead of once
 * per 4-block cell ({@link BiomeFill}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.runtime.TerrainBiomeRoleSelection")
public abstract class BiomeRolesMixin {
   @WrapMethod(method = "resolve")
   private Holder<Biome> wgaccel$roles(WorldgenPlans.ProviderResult result, Climate.TargetPoint target, Holder<Biome> resolved, int quartX, int quartY, int quartZ,
         Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      long start = System.nanoTime();
      try {
         return original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
      } finally {
         Stats.record(Stage.BIOME_ROLES, System.nanoTime() - start);
      }
   }

   @WrapOperation(method = "resolve", at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/cell/heightmap/WorldLookup;applyCell(Letcodehome/freeterraforged/world/worldgen/cell/Cell;IIZ)Z"))
   private boolean wgaccel$columnTerrain(WorldLookup lookup, Cell cell, int blockX, int blockZ, boolean climate, Operation<Boolean> original) {
      BiomeFill.State memo = BiomeFill.memo();
      if (memo == null || climate) {
         return original.call(lookup, cell, blockX, blockZ, climate);
      }

      Object known = memo.terrain(lookup, blockX, blockZ);
      if (known != null) {
         BiomeFill.ROLE_HITS.increment();
         if (memo.checkNow()) {
            boolean fromTile = original.call(lookup, cell, blockX, blockZ, climate);
            BiomeFill.checked(fromTile && cell.terrain == known, "column terrain");
            return fromTile;
         }

         cell.terrain = (Terrain)known;
         return true;
      }

      BiomeFill.ROLE_MISSES.increment();
      boolean fromTile = original.call(lookup, cell, blockX, blockZ, climate);
      // Only answers copied from the chunk's tile are kept: FTF holds that tile for the whole fill, so they cannot change.
      if (fromTile && cell.terrain != null) {
         memo.rememberTerrain(lookup, blockX, blockZ, cell.terrain);
      }

      return fromTile;
   }
}
