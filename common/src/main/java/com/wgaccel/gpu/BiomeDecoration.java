package com.wgaccel.gpu;

import com.wgaccel.platform.Names;
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
   /** C1: the other providers' settings (as longs, ints, density and flags above), in BiomeTable's extra order. */
   public long[][] extraLongs = new long[0][];
   public int[][] extraInts = new int[0][];
   public float[] extraDensity = new float[0];
   public int[][] extraFlags = new int[0][];
   /** C1: decoration stages after FreeTerraForged's (Lithostitched's injectors), run on the CPU after the GPU's result. */
   public int laterStages;

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
      // C1: several providers (each decorated with its own Layout and river table) and later stages (run on the CPU after
      // the GPU's result of the first, FreeTerraForged's own; the check below makes sure the first is that one).
      List<?> stages = (List<?>)BiomeTable.call(decoration, "stages");
      if (stages.isEmpty()) {
         throw new IllegalStateException("the biome plan has no decoration stages");
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
         } else if (value != null && Names.simpleName(value.getClass()).equals("Preset")) {
            preset = value;
         } else if (value != null && Names.simpleName(value.getClass()).equals("TerrainBiomeRoleSelection")) {
            roles = value;
         }
      }

      if (layouts == null || preset == null || roles == null) {
         throw new IllegalStateException("the decoration stage is not FreeTerraForged's selection policy (" + Names.className(decorator.getClass()) + ")");
      }

      Object shape = BiomeTable.field(BiomeTable.call(preset, "climate"), "biomeShape");
      float presetCoverage = (Float)BiomeTable.call(shape, "undergroundBiomeCoverage");
      Map<?, ?> rivers = (Map<?, ?>)BiomeTable.field(roles, "rivers");
      // The node arrays: the providers' own trees first, as BiomeTable laid them out (the extras' roots must match), then
      // each provider's original, background and river trees.
      BiomeTable.Trees trees = new BiomeTable.Trees(table.numbering, table.biomes);
      trees.add(table.candidates);
      for (int k = 0; k < table.extraCandidates.size(); k++) {
         int root = trees.add(table.extraCandidates.get(k));
         if (root != table.extraRoots[k]) {
            throw new IllegalStateException("provider " + table.extraIds.get(k) + "'s tree at " + root + ", not " + table.extraRoots[k]);
         }
      }

      int providers = 1 + table.extraCandidates.size();
      Object[] layoutsByProvider = new Object[providers];
      long[][] longsAll = new long[providers][];
      int[][] intsAll = new int[providers][];
      float[] densityAll = new float[providers];
      for (int k = 0; k < providers; k++) {
         Object id = k == 0 ? table.providerId : table.extraIds.get(k - 1);
         Climate.ParameterList<?> candidates = k == 0 ? table.candidates : table.extraCandidates.get(k - 1);
         int ownRoot = k == 0 ? 0 : table.extraRoots[k - 1];
         Object layout = layouts.get(id);
         if (layout == null) {
            throw new IllegalStateException("no underground layout for " + id);
         }

         Climate.ParameterList<?> original = (Climate.ParameterList<?>)BiomeTable.field(layout, "original");
         int originalRoot = original == candidates ? ownRoot : trees.add(original);
         int backgroundRoot = trees.add((Climate.ParameterList<?>)BiomeTable.field(layout, "background"));
         Climate.ParameterList<?> river = (Climate.ParameterList<?>)rivers.get(candidates);
         int riverRoot = river == null ? -1 : trees.add(river);
         boolean hasStage = BiomeTable.field(layout, "shallowStage") != null || BiomeTable.field(layout, "deepStage") != null;
         longsAll[k] = new long[] {(Long)BiomeTable.field(layout, "selectionStart"), (Long)BiomeTable.field(layout, "endDepth"),
            (Long)BiomeTable.field(layout, "deepStart"), (Long)BiomeTable.field(layout, "fullDensityStart"), Climate.quantizeCoord(0.03125f),
            (Long)BiomeTable.field(layout, "seed")};
         intsAll[k] = new int[] {originalRoot, backgroundRoot, riverRoot, hasStage ? 1 : 0, (Boolean)BiomeTable.field(layout, "bandingEnabled") ? 1 : 0,
            (Integer)BiomeTable.field(layout, "horizontalSize"), (Integer)BiomeTable.field(layout, "verticalSize")};
         densityAll[k] = (Float)BiomeTable.field(layout, "density");
         layoutsByProvider[k] = layout;
      }

      Method isCave = null;
      for (Method method : layoutsByProvider[0].getClass().getMethods()) {
         if (Names.name(method).equals("isCaveCandidate") && method.getParameterCount() == 1) {
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
      int[][] flagsAll = new int[providers][table.biomes.size()];
      for (int k = 0; k < providers; k++) {
         for (int b = 0; b < table.biomes.size(); b++) {
            Object value = table.biomes.get(b);
            @SuppressWarnings("unchecked")
            Holder<Biome> holder = (Holder<Biome>)value;
            flagsAll[k][b] = ((Boolean)isCave.invoke(layoutsByProvider[k], value) ? 1 : 0) | (holder.is(BiomeTags.IS_RIVER) ? 2 : 0);
         }
      }

      BiomeDecoration result = new BiomeDecoration(longsAll[0], intsAll[0], densityAll[0], presetCoverage, flagsAll[0], bounds, first, count, biome);
      result.extraLongs = java.util.Arrays.copyOfRange(longsAll, 1, providers);
      result.extraInts = java.util.Arrays.copyOfRange(intsAll, 1, providers);
      result.extraDensity = java.util.Arrays.copyOfRange(densityAll, 1, providers);
      result.extraFlags = java.util.Arrays.copyOfRange(flagsAll, 1, providers);
      result.laterStages = stages.size() - 1;
      return result;

   }
}
