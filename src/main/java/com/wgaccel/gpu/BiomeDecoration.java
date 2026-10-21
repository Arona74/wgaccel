package com.wgaccel.gpu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * GPU step G5.4: FreeTerraForged's biome decoration (the plan's one selection-decoration stage,
 * {@code ftf_selection_policy}) as the GPU's biomeDecorate kernel needs it, read by reflection from the stage's
 * decorator (a lambda holding the provider's underground Layout, the preset and the TerrainBiomeRoleSelection):
 * the Layout's depth range and density settings, its original, background and river tables flattened after the main
 * table's tree (one set of node arrays; {@link #bounds} etc. replace the main table's on the GPU), and per biome number
 * whether it is a cave candidate (1) or a river (2). See docs/G54-decoration-inventory.md.
 */
public final class BiomeDecoration {
   /** selectionStart, endDepth, deepStart, fullDensityStart, quantizeCoord(0.03125), seed. */
   public final long[] longs;
   /** originalRoot, backgroundRoot, riverRoot (-1: none), hasStage, banding, horizontalSize, verticalSize. */
   public final int[] ints;
   public final float density;
   public final float presetCoverage;
   public final int[] flags;
   public final int[] bounds;
   public final int[] first;
   public final int[] count;
   public final int[] biome;

   private BiomeDecoration(long[] longs, int[] ints, float density, float presetCoverage, int[] flags, int[] bounds, int[] first, int[] count, int[] biome) {
      this.longs = longs;
      this.ints = ints;
      this.density = density;
      this.presetCoverage = presetCoverage;
      this.flags = flags;
      this.bounds = bounds;
      this.first = first;
      this.count = count;
      this.biome = biome;
   }

   @SuppressWarnings("unchecked")
   public static BiomeDecoration of(RandomState random, BiomeTable table) throws ReflectiveOperationException {
      Object plan = BiomeTable.call(random.sampler(), "getWorldgenPlan");
      Object decoration = BiomeTable.call(plan, "selectionDecoration");
      List<?> stages = (List<?>)BiomeTable.call(decoration, "stages");
      if (stages.size() != 1) {
         throw new IllegalStateException("the biome plan has " + stages.size() + " decoration stages");
      }

      Object decorator = BiomeTable.call(stages.get(0), "decorator");
      Map<Object, Object> layouts = null;
      Object preset = null;
      Object roles = null;
      for (Field field : decorator.getClass().getDeclaredFields()) {
         field.setAccessible(true);
         Object value = field.get(decorator);
         if (value instanceof Map<?, ?> map) {
            layouts = (Map<Object, Object>)map;
         } else if (value != null && value.getClass().getSimpleName().equals("Preset")) {
            preset = value;
         } else if (value != null && value.getClass().getSimpleName().equals("TerrainBiomeRoleSelection")) {
            roles = value;
         }
      }

      if (layouts == null || preset == null || roles == null) {
         throw new IllegalStateException("the decoration stage is not FreeTerraForged's selection policy (" + decorator.getClass().getName() + ")");
      }

      Object layout = layouts.get(table.providerId);
      if (layout == null) {
         throw new IllegalStateException("no underground layout for " + table.providerId);
      }

      BiomeTable.Trees trees = new BiomeTable.Trees(table.numbering, table.biomes);
      trees.add(table.candidates);
      Climate.ParameterList<?> original = (Climate.ParameterList<?>)BiomeTable.field(layout, "original");
      int originalRoot = original == table.candidates ? 0 : trees.add(original);
      int backgroundRoot = trees.add((Climate.ParameterList<?>)BiomeTable.field(layout, "background"));
      Climate.ParameterList<?> river = (Climate.ParameterList<?>)((Map<?, ?>)BiomeTable.field(roles, "rivers")).get(table.candidates);
      int riverRoot = river == null ? -1 : trees.add(river);
      boolean hasStage = BiomeTable.field(layout, "shallowStage") != null || BiomeTable.field(layout, "deepStage") != null;
      long[] longs = {(Long)BiomeTable.field(layout, "selectionStart"), (Long)BiomeTable.field(layout, "endDepth"), (Long)BiomeTable.field(layout, "deepStart"),
         (Long)BiomeTable.field(layout, "fullDensityStart"), Climate.quantizeCoord(0.03125f), (Long)BiomeTable.field(layout, "seed")};
      int[] ints = {originalRoot, backgroundRoot, riverRoot, hasStage ? 1 : 0, (Boolean)BiomeTable.field(layout, "bandingEnabled") ? 1 : 0,
         (Integer)BiomeTable.field(layout, "horizontalSize"), (Integer)BiomeTable.field(layout, "verticalSize")};
      float density = (Float)BiomeTable.field(layout, "density");
      Object shape = BiomeTable.field(BiomeTable.call(preset, "climate"), "biomeShape");
      float presetCoverage = (Float)BiomeTable.call(shape, "undergroundBiomeCoverage");
      Method isCave = null;
      for (Method method : layout.getClass().getMethods()) {
         if (method.getName().equals("isCaveCandidate") && method.getParameterCount() == 1) {
            isCave = method;
         }
      }

      if (isCave == null) {
         throw new NoSuchMethodException("Layout.isCaveCandidate");
      }

      int[] bounds = trees.bounds();
      int[] first = trees.first();
      int[] count = trees.count();
      int[] biome = trees.biome();
      int[] flags = new int[table.biomes.size()];
      for (int b = 0; b < flags.length; b++) {
         Object value = table.biomes.get(b);
         @SuppressWarnings("unchecked")
         Holder<Biome> holder = (Holder<Biome>)value;
         flags[b] = ((Boolean)isCave.invoke(layout, value) ? 1 : 0) | (holder.is(BiomeTags.IS_RIVER) ? 2 : 0);
      }

      return new BiomeDecoration(longs, ints, density, presetCoverage, flags, bounds, first, count, biome);
   }
}
