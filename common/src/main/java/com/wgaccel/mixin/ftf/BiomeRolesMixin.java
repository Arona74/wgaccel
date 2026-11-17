package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceProtection;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FTF's terrain-role step at the end of its biome decoration (river biomes where the terrain is a river): timed, and
 * (config {@code fasterBiomeChoice}) the column's terrain is read once per column instead of once per 4-block cell
 * ({@link BiomeFill}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.runtime.TerrainBiomeRoleSelection")
public abstract class BiomeRolesMixin {
   @WrapMethod(method = "resolve")
   private Holder<Biome> wgaccel$roles(WorldgenPlans.ProviderResult result, Climate.TargetPoint target, Holder<Biome> resolved, int quartX, int quartY, int quartZ,
         Climate.Sampler sampler, GeneratorContext context, Operation<Holder<Biome>> original) {
      boolean timed = BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      try {
         BiomeFill.State memo = BiomeFill.memo();
         if (memo != null && context != null && context.lookup != null && context.levels != null) {
            int blockX = QuartPos.toBlock(quartX);
            int blockZ = QuartPos.toBlock(quartZ);
            int slot = (quartX & 3) << 2 | quartZ & 3;
               if (memo.roleSubmergedCached(context.lookup, context.levels.water, slot, blockX, blockZ)) {
                  if (!memo.roleSubmerged[slot] && memo.isNonRiver(resolved)) {
                     BiomeFill.ROLE_HITS.increment();
                     if (memo.checkNow()) {
                        Holder<Biome> real = original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
                        BiomeFill.checked(real == resolved, "dry role biome");
                        return real;
                     }
                     return resolved;
                  }
                  if (result != null && (!result.biome().equals(result.baseBiome())
                        || (target.depth() > 0L && UndergroundBiomeSurfaceProtection.coverageFactor(sampler, target, quartX, quartY, quartZ) > 0.0F))) {
                     BiomeFill.ROLE_HITS.increment();
                     if (memo.checkNow()) {
                        Holder<Biome> real = original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
                        BiomeFill.checked(real == resolved, "protected role biome");
                        return real;
                     }
                     return resolved;
                  }
                  if (memo.roleLastIn[slot] == resolved && memo.roleLastOut[slot] != null && result != null
                        && memo.roleLastCandidates[slot] == result.candidates()
                        && memo.roleLastDepth[slot] == result.target().depth()) {
                     BiomeFill.ROLE_HITS.increment();
                     @SuppressWarnings("unchecked")
                     Holder<Biome> cachedOut = (Holder<Biome>) memo.roleLastOut[slot];
                     if (memo.checkNow()) {
                        Holder<Biome> real = original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
                        BiomeFill.checked(real == cachedOut, "column role biome");
                        return real;
                     }
                     return cachedOut;
                  }
               }

            Holder<Biome> out = original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
            if (!memo.roleSubmergedCached(context.lookup, context.levels.water, slot, blockX, blockZ)) {
               Terrain terrain;
               float water;
               float height;
               if (memo.roleCached(context.lookup, slot, blockX, blockZ)) {
                  terrain = (Terrain)memo.roleTerrain[slot];
                  water = memo.roleWater[slot];
                  height = memo.roleHeight[slot];
               } else {
                  Cell cell = (Cell)memo.roleCell;
                  if (cell == null) {
                     cell = new Cell();
                     memo.roleCell = cell;
                  }
                  if (!context.lookup.applyCell(cell, blockX, blockZ, false) || cell.terrain == null) {
                     return out;
                  }

                  terrain = cell.terrain;
                  water = cell.riverWaterLevel;
                  height = cell.height;
                  memo.rememberRoleCell(context.lookup, slot, blockX, blockZ, terrain, water, height);
               }

               boolean submerged = (terrain.isRiver() || terrain.isWetland() || terrain.isLake())
                  && height <= (water > 0.0f ? water : context.levels.water);
               memo.rememberRoleSubmerged(context.lookup, context.levels.water, slot, blockX, blockZ, submerged);
            }
            if (result != null && result.biome().equals(result.baseBiome())
                  && (target.depth() <= 0L || UndergroundBiomeSurfaceProtection.coverageFactor(sampler, target, quartX, quartY, quartZ) <= 0.0F)) {
               memo.roleLastIn[slot] = resolved;
               memo.roleLastOut[slot] = out;
               memo.roleLastCandidates[slot] = result.candidates();
               memo.roleLastDepth[slot] = result.target().depth();
            }

            return out;
         }

         return original.call(result, target, resolved, quartX, quartY, quartZ, sampler, context);
      } finally {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_ROLES, System.nanoTime() - start, 16);
         }
      }
   }

   @WrapOperation(method = "resolve", require = 0, at = @At(value = "INVOKE",
      target = "Letcodehome/freeterraforged/world/worldgen/densityfunction/CellSampler;getCachedOrLookup(Letcodehome/freeterraforged/world/worldgen/cell/heightmap/WorldLookup;IIZ)Letcodehome/freeterraforged/world/worldgen/cell/Cell;"))
   private Cell wgaccel$columnRoleCell(WorldLookup lookup, int blockX, int blockZ, boolean climate, Operation<Cell> original) {
      BiomeFill.State memo = BiomeFill.memo();
      if (memo == null || climate) {
         return original.call(lookup, blockX, blockZ, climate);
      }

      int slot = (blockX >> 2 & 3) << 2 | blockZ >> 2 & 3;
      if (memo.roleCached(lookup, slot, blockX, blockZ)) {
         BiomeFill.ROLE_HITS.increment();
         if (memo.checkNow()) {
            Cell real = original.call(lookup, blockX, blockZ, climate);
            boolean same = real != null && real.terrain == memo.roleTerrain[slot]
               && Float.compare(real.riverWaterLevel, memo.roleWater[slot]) == 0
               && Float.compare(real.height, memo.roleHeight[slot]) == 0;
            BiomeFill.checked(same, "column terrain");
            return real;
         }

         Cell cached = (Cell)memo.roleCell;
         if (cached == null) {
            cached = new Cell();
            memo.roleCell = cached;
         }

         cached.terrain = (Terrain)memo.roleTerrain[slot];
         cached.riverWaterLevel = memo.roleWater[slot];
         cached.height = memo.roleHeight[slot];
         return cached;
      }

      BiomeFill.ROLE_MISSES.increment();
      Cell real = original.call(lookup, blockX, blockZ, climate);
      if (real != null && real.terrain != null) {
         memo.rememberRoleCell(lookup, slot, blockX, blockZ, real.terrain, real.riverWaterLevel, real.height);
      }

      return real;
   }

   @WrapOperation(method = "resolve", require = 0, at = @At(value = "INVOKE",
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
