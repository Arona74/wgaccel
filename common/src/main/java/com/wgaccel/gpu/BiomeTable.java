package com.wgaccel.gpu;

import com.mojang.datafixers.util.Pair;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * GPU step G5.2: the overworld biome plan's single climate table as the GPU searches it: the provider's id and
 * candidates, every point's seven parameters (min, max; the offset as the seventh) as ints, a number per distinct biome,
 * and FreeTerraForged's OceanClimateProjection (source range and target maximum); and Minecraft's R-tree over the
 * table, flattened (G5.2c): per node its seven parameter bounds (nodeBounds, 14 ints), its children's range (nodeFirst,
 * nodeCount; children of a node are contiguous, the root is node 0) or for a leaf (count 0) its biome's number. Read by reflection from the climate
 * sampler's plan; with several providers the first one's table (C1); throws for a deferred fallback table.
 */
public final class BiomeTable {
   public final Object providerId;
   public final Climate.ParameterList<?> candidates;
   public final List<? extends Pair<Climate.ParameterPoint, ?>> values;
   public final int[] points;
   public final int[] biomeIds;
   public final int biomeCount;
   public final long sourceMin;
   public final long sourceMax;
   public final long targetMax;
   /** The distinct biomes by number (the GPU's search answers a number). */
   public final List<Object> biomes;
   public final int[] nodeBounds;
   public final int[] nodeFirst;
   public final int[] nodeCount;
   public final int[] nodeBiome;
   /** Biome to number (identity), extended by the decoration's tables (G5.4) as {@link #biomes} is. */
   public final Map<Object, Integer> numbering;
   /**
    * C1: the other providers (registration order after the first): their ids, candidate tables, tree roots in the node
    * arrays (after the first's tree), ocean projection maxima; and the number of TerraBlender's deferred placeholder
    * (-1: none), whose search FreeTerraForged redoes in its fallback table (the CPU's).
    */
   public final List<Object> extraIds = new java.util.ArrayList<>();
   public final List<Climate.ParameterList<?>> extraCandidates = new java.util.ArrayList<>();
   public int[] extraRoots = new int[0];
   public long[] extraTargetMax = new long[0];
   public int deferredNumber = -1;
   /** C1: FreeTerraForged's spatial resolver (region cell -> provider), or null. */
   public Object spatialResolver;

   private BiomeTable(Object providerId, Climate.ParameterList<?> candidates, List<? extends Pair<Climate.ParameterPoint, ?>> values, int[] points, int[] biomeIds,
         int biomeCount, long sourceMin, long sourceMax, long targetMax, List<Object> biomes, int[] nodeBounds, int[] nodeFirst, int[] nodeCount, int[] nodeBiome, Map<Object, Integer> numbering) {
      this.numbering = numbering;
      this.providerId = providerId;
      this.candidates = candidates;
      this.values = values;
      this.points = points;
      this.biomeIds = biomeIds;
      this.biomeCount = biomeCount;
      this.sourceMin = sourceMin;
      this.sourceMax = sourceMax;
      this.targetMax = targetMax;
      this.biomes = biomes;
      this.nodeBounds = nodeBounds;
      this.nodeFirst = nodeFirst;
      this.nodeCount = nodeCount;
      this.nodeBiome = nodeBiome;
   }

   public static BiomeTable of(RandomState random) throws ReflectiveOperationException {
      Object plan = call(random.sampler(), "getWorldgenPlan");
      Object selection = call(plan, "providerSelection");
      List<?> providers = (List<?>)call(selection, "providers");
      // C1: several providers (TerraBlender's regions, as Biomes O' Plenty's) are fine: the GPU's table is the first one's
      // (registration order) and a cell whose region belongs to another provider is searched on the CPU. A deferred
      // placeholder (its fallback table) is not known to the GPU.
      if (providers.isEmpty()) {
         throw new IllegalStateException("the biome plan has no providers");
      }

      Object provider = providers.get(0);
      // TerraBlender's deferred placeholder ("vanilla decides here") sends a search to the fallback table; fine while the
      // GPU's table (the first provider's) cannot answer it (FTF's resolveProvider: deferred only when it is selected).
      Optional<?> deferred = (Optional<?>)field(selection, "deferredPlaceholder");
      if (deferred.isPresent()) {
         for (Object entry : ((Climate.ParameterList<?>)call(provider, "candidates")).values()) {
            if (deferred.get().equals(((Pair<?, ?>)entry).getSecond())) {
               throw new IllegalStateException("the first provider's table holds the deferred placeholder");
            }
         }
      }

      Object id = call(provider, "id");
      Climate.ParameterList<?> table = (Climate.ParameterList<?>)call(provider, "candidates");
      Object projection = ((Map<?, ?>)field(selection, "oceanProjections")).get(id);
      Class<?> projectionClass = Names.forName("etcodehome.freeterraforged.world.worldgen.runtime.OceanClimateProjection");
      long sourceMin = (Long)staticField(projectionClass, "SOURCE_MIN");
      long sourceMax = (Long)staticField(projectionClass, "SOURCE_MAX");
      long targetMax = projection == null ? sourceMax : (Long)field(projection, "targetMax");
      List<Climate.ParameterList<?>> extras = new java.util.ArrayList<>();
      List<Object> extraIds = new java.util.ArrayList<>();
      long[] extraTargetMax = new long[providers.size() - 1];
      for (int i = 1; i < providers.size(); i++) {
         Object other = providers.get(i);
         Object otherId = call(other, "id");
         Object otherProjection = ((Map<?, ?>)field(selection, "oceanProjections")).get(otherId);
         extraIds.add(otherId);
         extras.add((Climate.ParameterList<?>)call(other, "candidates"));
         extraTargetMax[i - 1] = otherProjection == null ? sourceMax : (Long)field(otherProjection, "targetMax");
      }

      BiomeTable result = of(id, table, sourceMin, sourceMax, targetMax, extras);
      result.extraIds.addAll(extraIds);
      result.extraCandidates.addAll(extras);
      result.extraTargetMax = extraTargetMax;
      result.spatialResolver = ((Optional<?>)call(call(plan, "spatialOwnership"), "resolver")).orElse(null);
      if (deferred.isPresent()) {
         Integer number = result.numbering.get(deferred.get());
         result.deferredNumber = number == null ? -1 : number;
      }

      return result;
   }

   /**
    * C2: the table of a plain Climate.ParameterList (vanilla's MultiNoiseBiomeSource), with no ocean projection (the
    * kernels skip it when the target and source maxima are equal).
    */
   public static BiomeTable ofParameters(Object id, Climate.ParameterList<?> table) throws ReflectiveOperationException {
      return of(id, table, 0L, 0L, 0L, List.of());
   }

   private static BiomeTable of(Object id, Climate.ParameterList<?> table, long sourceMin, long sourceMax, long targetMax, List<Climate.ParameterList<?>> extras)
         throws ReflectiveOperationException {
      List<? extends Pair<Climate.ParameterPoint, ?>> values = table.values();
      int[] points = new int[values.size() * 14];
      int[] biomeIds = new int[values.size()];
      Map<Object, Integer> distinct = new IdentityHashMap<>();
      for (int i = 0; i < values.size(); i++) {
         Climate.ParameterPoint point = values.get(i).getFirst();
         Climate.Parameter[] parameters = {point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.depth(), point.weirdness(),
            new Climate.Parameter(point.offset(), point.offset())};
         for (int k = 0; k < 7; k++) {
            points[i * 14 + 2 * k] = Math.toIntExact(parameters[k].min());
            points[i * 14 + 2 * k + 1] = Math.toIntExact(parameters[k].max());
         }

         biomeIds[i] = distinct.computeIfAbsent(values.get(i).getSecond(), key -> distinct.size());
      }

      List<Object> biomes = new java.util.ArrayList<>(java.util.Collections.nCopies(distinct.size(), null));
      for (Map.Entry<Object, Integer> entry : distinct.entrySet()) {
         biomes.set(entry.getValue(), entry.getKey());
      }

      // Minecraft's R-tree over the same table, breadth first so every node's children are contiguous.
      Trees trees = new Trees(distinct, biomes);
      trees.add(table);
      int[] roots = new int[extras.size()];
      for (int i = 0; i < extras.size(); i++) {
         roots[i] = trees.add(extras.get(i));
      }

      BiomeTable result = new BiomeTable(id, table, values, points, biomeIds, distinct.size(), sourceMin, sourceMax, targetMax, biomes, trees.bounds(),
         trees.first(), trees.count(), trees.biome(), distinct);
      result.extraRoots = roots;
      return result;
   }

   /**
    * Minecraft R-trees flattened one after another into one set of node arrays (each tree's nodes breadth first, so a
    * node's children are contiguous; leaves numbered by biome, a new biome added to the numbering). The first tree's
    * root is node 0.
    */
   public static final class Trees {
      private final Map<Object, Integer> numbering;
      private final List<Object> biomes;
      private final it.unimi.dsi.fastutil.ints.IntArrayList bounds = new it.unimi.dsi.fastutil.ints.IntArrayList();
      private final it.unimi.dsi.fastutil.ints.IntArrayList first = new it.unimi.dsi.fastutil.ints.IntArrayList();
      private final it.unimi.dsi.fastutil.ints.IntArrayList count = new it.unimi.dsi.fastutil.ints.IntArrayList();
      private final it.unimi.dsi.fastutil.ints.IntArrayList biome = new it.unimi.dsi.fastutil.ints.IntArrayList();

      public Trees(Map<Object, Integer> numbering, List<Object> biomes) {
         this.numbering = numbering;
         this.biomes = biomes;
      }

      /** A table's R-tree; returns its root node. */
      public int add(Climate.ParameterList<?> table) throws ReflectiveOperationException {
         Object root = field(field(table, "index"), "root");
         int base = this.count.size();
         List<Object> nodes = new java.util.ArrayList<>();
         nodes.add(root);
         List<int[]> ranges = new java.util.ArrayList<>();
         for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            Object children = Names.simpleName(node.getClass()).equals("SubTree") ? field(node, "children") : null;
            if (children != null) {
               int n = java.lang.reflect.Array.getLength(children);
               ranges.add(new int[] {base + nodes.size(), n});
               for (int c = 0; c < n; c++) {
                  nodes.add(java.lang.reflect.Array.get(children, c));
               }
            } else {
               ranges.add(new int[] {0, 0});
            }
         }

         for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            Object[] space = (Object[])field(node, "parameterSpace");
            if (space.length != 7) {
               throw new IllegalStateException("an R-tree node with " + space.length + " parameters");
            }

            for (int k = 0; k < 7; k++) {
               Climate.Parameter parameter = (Climate.Parameter)space[k];
               this.bounds.add(Math.toIntExact(parameter.min()));
               this.bounds.add(Math.toIntExact(parameter.max()));
            }

            this.first.add(ranges.get(i)[0]);
            this.count.add(ranges.get(i)[1]);
            int number = 0;
            if (ranges.get(i)[1] == 0) {
               Object value = field(node, "value");
               Integer known = this.numbering.get(value);
               if (known == null) {
                  known = this.numbering.size();
                  this.numbering.put(value, known);
                  this.biomes.add(value);
               }

               number = known;
            }

            this.biome.add(number);
         }

         return base;
      }

      public int[] bounds() {
         return this.bounds.toIntArray();
      }

      public int[] first() {
         return this.first.toIntArray();
      }

      public int[] count() {
         return this.count.toIntArray();
      }

      public int[] biome() {
         return this.biome.toIntArray();
      }
   }

   static Object call(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         for (Method method : type.getDeclaredMethods()) {
            if (Names.name(method).equals(name) && method.getParameterCount() == 0) {
               method.setAccessible(true);
               return method.invoke(target);
            }
         }
      }

      for (Method method : target.getClass().getMethods()) {
         if (Names.name(method).equals(name) && method.getParameterCount() == 0) {
            return method.invoke(target);
         }
      }

      throw new NoSuchMethodException(Names.className(target.getClass()) + "." + name);
   }

   static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = Names.declaredField(type, name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(Names.className(target.getClass()) + "." + name);
   }

   private static Object staticField(Class<?> type, String name) throws ReflectiveOperationException {
      Field field = Names.declaredField(type, name);
      field.setAccessible(true);
      return field.get(null);
   }
}
