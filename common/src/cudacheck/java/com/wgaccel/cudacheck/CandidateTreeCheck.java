package com.wgaccel.cudacheck;

import com.mojang.datafixers.util.Pair;
import com.wgaccel.gen.CandidateTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * CandidateTree against FreeTerraForged's CandidateFit.find scan (copied here): random tables (7,593 points of 53
 * biomes, as with No Man's Land, and small ones with coarse values for many equal distances), random targets.
 */
public final class CandidateTreeCheck {
   public static void main(String[] args) {
      Random random = new Random(1);
      long differences = 0;
      long queries = 0;
      for (int table = 0; table < 40; table++) {
         int size = table < 4 ? 7593 : 64 + random.nextInt(2000);
         int biomes = 1 + random.nextInt(table < 4 ? 53 : 12);
         long grain = table % 3 == 0 ? 2500 : 1;
         List<Holder<Biome>> holders = new ArrayList<>();
         for (int b = 0; b < biomes; b++) {
            @SuppressWarnings("unchecked")
            Holder<Biome> holder = (Holder<Biome>)(Holder<?>)Holder.direct("biome " + b);
            holders.add(holder);
         }

         List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new ArrayList<>();
         for (int i = 0; i < size; i++) {
            Climate.Parameter[] p = new Climate.Parameter[6];
            for (int d = 0; d < 6; d++) {
               long a = (random.nextInt(21) - 10) * 1000L / grain * grain;
               long w = random.nextInt(3) == 0 ? 0 : random.nextInt(8) * 500L / grain * grain;
               p[d] = new Climate.Parameter(a, a + w);
            }

            long offset = random.nextInt(4) == 0 ? random.nextInt(3) * 1000L : 0L;
            entries.add(Pair.of(new Climate.ParameterPoint(p[0], p[1], p[2], p[3], p[4], p[5], offset), holders.get(random.nextInt(biomes))));
         }

         CandidateTree tree = CandidateTree.of(entries);
         for (int q = 0; q < 20000; q++) {
            long[] t = new long[6];
            for (int d = 0; d < 6; d++) {
               t[d] = (random.nextInt(25001) - 12500) / grain * grain;
            }

            Climate.TargetPoint target = new Climate.TargetPoint(t[0], t[1], t[2], t[3], t[4], t[5]);
            Holder<Biome> selected = entries.get(random.nextInt(size)).getSecond();
            long[] expected = scan(entries, target, selected);
            long[] found = tree.find(target, selected);
            queries++;
            if (found == null || found[0] != expected[0] || found[1] != expected[1] || found[2] != expected[2] || (expected[2] >= 0 && found[3] != expected[3])) {
               if (differences++ < 10) {
                  System.out.printf("table %d query %d: scan %s, tree %s%n", table, q, java.util.Arrays.toString(expected), java.util.Arrays.toString(found));
               }
            }
         }
      }

      // Timing on vanilla's overworld table (OverworldBiomeBuilder), each biome key its own holder.
      List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new ArrayList<>();
      List<Holder<Biome>> holders = new ArrayList<>();
      java.util.Map<Object, Holder<Biome>> byKey = new java.util.HashMap<>();
      java.util.function.Consumer<Pair<Climate.ParameterPoint, Object>> add = pair -> {
         @SuppressWarnings("unchecked")
         Holder<Biome> holder = byKey.computeIfAbsent(pair.getSecond(), key -> (Holder<Biome>)(Holder<?>)Holder.direct(key));
         entries.add(Pair.of(pair.getFirst(), holder));
      };
      try {
         java.lang.reflect.Method addBiomes = net.minecraft.world.level.biome.OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", java.util.function.Consumer.class);
         addBiomes.setAccessible(true);
         addBiomes.invoke(new net.minecraft.world.level.biome.OverworldBiomeBuilder(), add);
      } catch (ReflectiveOperationException failed) {
         throw new IllegalStateException(failed);
      }
      holders.addAll(new java.util.LinkedHashSet<>(byKey.values()));
      System.out.printf("vanilla overworld table: %,d points, %d biomes%n", entries.size(), holders.size());
      int checked = 0;
      CandidateTree tree = CandidateTree.of(entries);
      for (int q = 0; q < 50000; q++) {
         Climate.TargetPoint target = new Climate.TargetPoint(random.nextInt(24001) - 12000L, random.nextInt(24001) - 12000L, random.nextInt(24001) - 12000L,
            random.nextInt(24001) - 12000L, random.nextInt(24001) - 12000L, random.nextInt(24001) - 12000L);
         Holder<Biome> selected = holders.get(random.nextInt(holders.size()));
         long[] expected = scan(entries, target, selected);
         long[] found = tree.find(target, selected);
         checked++;
         if (found == null || found[0] != expected[0] || found[1] != expected[1] || found[2] != expected[2] || found[3] != expected[3]) {
            differences++;
         }
      }

      queries += checked;
      Climate.TargetPoint[] targets = new Climate.TargetPoint[4096];
      for (int i = 0; i < targets.length; i++) {
         targets[i] = new Climate.TargetPoint(random.nextInt(20001) - 10000L, random.nextInt(20001) - 10000L, random.nextInt(20001) - 10000L,
            random.nextInt(20001) - 10000L, random.nextInt(20001) - 10000L, random.nextInt(20001) - 10000L);
      }

      long sink = 0;
      for (int round = 0; round < 3; round++) {
         long start = System.nanoTime();
         for (Climate.TargetPoint target : targets) {
            sink += scan(entries, target, holders.get((int)((target.temperature() & 1023) % holders.size())))[1];
         }

         long middle = System.nanoTime();
         for (Climate.TargetPoint target : targets) {
            sink += tree.find(target, holders.get((int)((target.temperature() & 1023) % holders.size())))[1];
         }

         long end = System.nanoTime();
         System.out.printf("round %d: scan %.2f us, tree %.2f us per query%n", round, (middle - start) / 1e3 / targets.length, (end - middle) / 1e3 / targets.length);
      }

      System.out.printf("%,d queries, %,d differences%s (%d)%n", queries, differences, differences == 0 ? ": IDENTICAL" : "", sink & 1);
      if (differences != 0) {
         System.exit(1);
      }
   }

   /** FreeTerraForged 1.0.0014's WorldgenPlans.CandidateFit.find, as indices and distances. */
   private static long[] scan(List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries, Climate.TargetPoint target, Holder<Biome> selected) {
      long ultimate = -1;
      long ultimateDistance = 0;
      long penultimate = -1;
      long penultimateDistance = 0;
      for (int i = 0; i < entries.size(); i++) {
         Pair<Climate.ParameterPoint, Holder<Biome>> entry = entries.get(i);
         long distance = fitness(entry.getFirst(), target);
         if (entry.getSecond().equals(selected)) {
            if (ultimate < 0 || distance < ultimateDistance) {
               ultimate = i;
               ultimateDistance = distance;
            }
         } else if (penultimate < 0 || distance < penultimateDistance) {
            penultimate = i;
            penultimateDistance = distance;
         }
      }

      return new long[] {ultimate, ultimateDistance, penultimate, penultimateDistance};
   }

   private static long fitness(Climate.ParameterPoint point, Climate.TargetPoint target) {
      return square(point.temperature().distance(target.temperature())) + square(point.humidity().distance(target.humidity()))
         + square(point.continentalness().distance(target.continentalness())) + square(point.erosion().distance(target.erosion()))
         + square(point.depth().distance(target.depth())) + square(point.weirdness().distance(target.weirdness())) + square(point.offset());
   }

   private static long square(long value) {
      return value * value;
   }
}
