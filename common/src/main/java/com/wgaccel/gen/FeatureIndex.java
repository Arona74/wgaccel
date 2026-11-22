package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The indices the game's decoration seeds each feature's and structure's random with: a feature's place in its step's
 * list across all biomes (FeatureSorter's index mapping, not its place in its biome's own list), a structure's place among
 * the structures of its step. FreeTerraForged decorates from its own plan (its feature steps and structure order, put in
 * place of the game's while it decorates): read from that plan when the generator is its (by reflection: its runtime
 * classes are package-private), else the generator's own.
 */
final class FeatureIndex {
   private static volatile boolean ftfBroken;

   private FeatureIndex() {
   }

   /** The steps' features with their indices as the generator decorates; null when they cannot be read. */
   static List<FeatureSorter.StepFeatureData> steps(ChunkGenerator generator) {
      Object plan = ftfPlan(generator);
      if (plan != null) {
         try {
            @SuppressWarnings("unchecked")
            List<FeatureSorter.StepFeatureData> steps = (List<FeatureSorter.StepFeatureData>)plan.getClass().getMethod("featureSteps").invoke(plan);
            return steps;
         } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            broken(failure);
         }
      }

      try {
         return ((com.wgaccel.mixin.access.ChunkGeneratorAccessor)generator).wgaccel$featuresPerStep().get();
      } catch (RuntimeException | LinkageError failure) {
         return null;
      }
   }

   /** The feature's index in step as the generator seeds it, or fallback when it is not there. */
   static int index(List<FeatureSorter.StepFeatureData> steps, int step, PlacedFeature feature, int fallback) {
      if (steps == null || step >= steps.size()) {
         return fallback;
      }

      FeatureSorter.StepFeatureData data = steps.get(step);
      try {
         int index = data.indexMapping().applyAsInt(feature);
         return index >= 0 ? index : fallback;
      } catch (RuntimeException missing) {
         return fallback;
      }
   }

   /** The structures in the order the generator's decoration goes through them (FreeTerraForged's plan), or null: the registry's. */
   @SuppressWarnings("unchecked")
   static List<Structure> structures(ChunkGenerator generator) {
      Object plan = ftfPlan(generator);
      if (plan == null) {
         return null;
      }

      try {
         return ((java.util.stream.Stream<Structure>)plan.getClass().getMethod("structureValues").invoke(plan)).toList();
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken(failure);
         return null;
      }
   }

   /** FreeTerraForged's current biome decoration plan (runtime.current().biomeDecorationPlan()), or null. */
   private static Object ftfPlan(ChunkGenerator generator) {
      if (ftfBroken || !generator.getClass().getName().equals("etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator")) {
         return null;
      }

      try {
         Field runtime = generator.getClass().getDeclaredField("runtime");
         runtime.setAccessible(true);
         Object binding = runtime.get(generator);
         if (binding == null) {
            return null;
         }

         Method current = binding.getClass().getDeclaredMethod("current");
         current.setAccessible(true);
         Object state = current.invoke(binding);
         Method plan = state.getClass().getDeclaredMethod("biomeDecorationPlan");
         plan.setAccessible(true);
         return plan.invoke(state);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken(failure);
         return null;
      }
   }

   private static void broken(Throwable failure) {
      if (!ftfBroken) {
         ftfBroken = true;
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: FreeTerraForged's decoration plan not readable ({}); features seeded by the game's own"
            + " step lists", failure.toString());
      }
   }
}
