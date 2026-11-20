package com.wgaccel.ftf;

import com.wgaccel.mixin.ftf.FastPoissonAccessor;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.feature.placement.poisson.BiomeVariance;
import etcodehome.freeterraforged.world.worldgen.feature.placement.poisson.FastPoissonModifier;
import com.wgaccel.mixin.ftf.FeatureTemplateAccessor;
import etcodehome.freeterraforged.server.FTFMinecraftServer;
import etcodehome.freeterraforged.world.worldgen.feature.chance.ChanceFeature;
import etcodehome.freeterraforged.world.worldgen.feature.template.TemplateFeature;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.BakedTemplate;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.BlockInfo;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.FeatureTemplate;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * FreeTerraForged's trees for the Voxy LOD trees ({@code gen/LodTrees}); only called with FreeTerraForged installed.
 * <ul>
 *   <li>A biome's features as FreeTerraForged decorates it: its plan applies its biome modifiers (its own trees in place
 *   of vanilla's: PresetBiomeModifierData, customBiomeFeatures), which the biome's own settings do not show.
 *   <li>Its Poisson-disk placement (FastPoissonModifier: forests' trees at least a radius apart, denser and sparser by a
 *   noise and towards biome edges), from the seed and chunk (FastPoissonAccessor).
 * </ul>
 */
public final class FtfTrees {
   private FtfTrees() {
   }

   /** The biome's vegetal decoration in FreeTerraForged's plan; null when the generator is not its or has no plan. */
   public static List<Holder<PlacedFeature>> vegetation(ChunkGenerator generator, Holder<Biome> biome) {
      if (!(generator instanceof TerraForgedChunkGenerator ftf)) {
         return null;
      }

      return ftf.plan().map(plan -> plan.placedFeatures().forBiome(biome, GenerationStep.Decoration.VEGETAL_DECORATION.ordinal())).orElse(null);
   }

   public static boolean poisson(PlacementModifier modifier) {
      return modifier instanceof FastPoissonModifier;
   }

   /** FreeTerraForged's LegacyCountExtraModifier (package-private): a count, no context needed. */
   public static boolean countExtra(PlacementModifier modifier) {
      return modifier.getClass().getName().equals("etcodehome.freeterraforged.world.worldgen.feature.placement.LegacyCountExtraModifier");
   }

   /** The Poisson placement's spots in chunk (cx, cz), as its getPositions works them out (seed, chunk, biome fade). */
   public static Stream<BlockPos> poissonPositions(PlacementModifier modifier, ServerLevel level, int cx, int cz) {
      FastPoissonAccessor poisson = (FastPoissonAccessor)modifier;
      long levelSeed = level.getSeed();
      int seed = (int)levelSeed + 234523;
      WorldLookup lookup = null;
      if (poisson.wgaccel$biomeFade() > BiomeVariance.MIN_FADE && (Object)level.getChunkSource().randomState() instanceof FTFRandomState state
         && state.generatorContext() != null) {
         lookup = state.generatorContext().lookup;
      }

      return poisson.wgaccel$positions(seed, levelSeed, cx, cz, lookup);
   }

   /** A template's blocks in one mirror and rotation, relative to its origin (air left out). */
   public record Shape(int[] x, int[] y, int[] z, BlockState[] states) {
   }

   /** FreeTerraForged's template feature (its trees and huge mushrooms are NBT templates). */
   public static boolean template(FeatureConfiguration config) {
      return config instanceof TemplateFeature.Config<?>;
   }

   /** Its weighted pick (ChanceFeature): the features and their base chances (elevation and biome edge modifiers left out). */
   public static boolean chance(FeatureConfiguration config) {
      return config instanceof ChanceFeature.Config;
   }

   public static List<Holder<PlacedFeature>> chanceFeatures(FeatureConfiguration config) {
      List<Holder<PlacedFeature>> features = new ArrayList<>();
      for (ChanceFeature.Entry entry : ((ChanceFeature.Config)config).entries()) {
         features.add(entry.feature());
      }

      return features;
   }

   public static List<Float> chanceWeights(FeatureConfiguration config) {
      List<Float> weights = new ArrayList<>();
      for (ChanceFeature.Entry entry : ((ChanceFeature.Config)config).entries()) {
         weights.add(entry.chance());
      }

      return weights;
   }

   /** The template feature's templates' names, for the log. */
   public static List<String> templateNames(FeatureConfiguration config) {
      List<String> names = new ArrayList<>();
      for (var location : ((TemplateFeature.Config<?>)config).templates()) {
         names.add(location.toString());
      }

      return names;
   }

   /**
    * Each template of the feature (loaded by FreeTerraForged's own manager) in its 12 mirror and rotation forms, index
    * mirror.ordinal() * 4 + rotation.ordinal() (TemplateFeature picks a mirror, a rotation, then a template).
    */
   public static List<Shape[]> templates(MinecraftServer server, FeatureConfiguration config) {
      List<Shape[]> found = new ArrayList<>();
      if (!(server instanceof FTFMinecraftServer ftf)) {
         return found;
      }

      for (var location : ((TemplateFeature.Config<?>)config).templates()) {
         FeatureTemplate template = ftf.getFeatureTemplateManager().load(location);
         if (template == null) {
            continue;
         }

         BakedTemplate baked = ((FeatureTemplateAccessor)(Object)template).wgaccel$template();
         Shape[] shapes = new Shape[Mirror.values().length * Rotation.values().length];
         for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
               BlockInfo[] blocks = baked.get(mirror, rotation);
               int n = 0;
               for (BlockInfo block : blocks) {
                  n += block.state().getBlock() == Blocks.AIR ? 0 : 1;
               }

               int[] x = new int[n];
               int[] y = new int[n];
               int[] z = new int[n];
               BlockState[] states = new BlockState[n];
               int i = 0;
               for (BlockInfo block : blocks) {
                  if (block.state().getBlock() != Blocks.AIR) {
                     x[i] = block.pos().getX();
                     y[i] = block.pos().getY();
                     z[i] = block.pos().getZ();
                     states[i++] = block.state();
                  }
               }

               shapes[mirror.ordinal() * Rotation.values().length + rotation.ordinal()] = new Shape(x, y, z, states);
            }
         }

         found.add(shapes);
      }

      return found;
   }

   /** FreeTerraForged's bush (BushFeature): its trunk and leaves, else null. */
   public static BlockState[] bush(FeatureConfiguration config) {
      if (config instanceof etcodehome.freeterraforged.world.worldgen.feature.BushFeature.Config bush) {
         return new BlockState[]{bush.trunk(), bush.leaves()};
      }

      return null;
   }

   /** The weighted pick's entries (ChanceFeature.Entry), in order. */
   public static List<Object> chanceEntries(FeatureConfiguration config) {
      return new ArrayList<>(((ChanceFeature.Config)config).entries());
   }

   /**
    * An entry's chance at block column (x, z): its base chance times its modifiers' (elevation, biome edge), which read
    * FreeTerraForged's cells there from a context holding only the level and the spot. The base chance when they fail.
    */
   public static float entryChance(Object entry, ServerLevel level, net.minecraft.util.RandomSource random, int x, int z) {
      ChanceFeature.Entry chance = (ChanceFeature.Entry)entry;
      try {
         net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<FeatureConfiguration> context = new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<>(
            java.util.Optional.empty(), level, level.getChunkSource().getGenerator(), random, new BlockPos(x, 0, z), null);
         return chance.getChance(null, context);
      } catch (RuntimeException odd) {
         return chance.chance();
      }
   }
}
