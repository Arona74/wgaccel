package com.wgaccel.stats;

import com.wgaccel.platform.Mc;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Time per named thing: every placed feature (trees, ores, snow and ice, flowers...), every structure's start (where
 * the game tries to begin a structure, jigsaw assembly included) and the placing of a structure's pieces into each
 * chunk. Only the outermost call counts (features placing other features are inside their parent's time).
 */
public final class Named {
   public enum Category {
      FEATURE,
      STRUCTURE_START,
      STRUCTURE_PIECES
   }

   private static final Map<Category, ConcurrentHashMap<String, Counter>> COUNTERS = new EnumMap<>(Category.class);
   private static final ThreadLocal<int[]> FEATURE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

   static {
      for (Category category : Category.values()) {
         COUNTERS.put(category, new ConcurrentHashMap<>());
      }
   }

   private Named() {
   }

   /** Enters a placed feature; true when it is the outermost one on this thread (only those are timed). */
   public static boolean enterFeature() {
      return FEATURE_DEPTH.get()[0]++ == 0;
   }

   public static void exitFeature() {
      FEATURE_DEPTH.get()[0]--;
   }

   public static void record(Category category, String name, long nanos, boolean hit) {
      Counter counter = COUNTERS.get(category).computeIfAbsent(name, n -> new Counter());
      counter.nanos.add(nanos);
      counter.calls.increment();
      if (hit) {
         counter.hits.increment();
      }
   }

   public static String feature(WorldGenLevel level, PlacedFeature feature) {
      String id = Mc.name(Mc.registry(level.registryAccess(), Registries.PLACED_FEATURE), feature);
      if (id != null) {
         return id;
      }

      return "inline " + BuiltInRegistries.FEATURE.getKey(feature.feature().value().feature());
   }

   public static String structure(StructureSet.StructureSelectionEntry entry) {
      return entry.structure().unwrapKey().map(Mc::id)
         .orElseGet(() -> "inline " + BuiltInRegistries.STRUCTURE_TYPE.getKey(entry.structure().value().type()));
   }

   public static String structure(WorldGenLevel level, Structure structure) {
      String id = Mc.name(Mc.registry(level.registryAccess(), Registries.STRUCTURE), structure);
      return id != null ? id : "inline " + BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
   }

   /** Totals per name: {nanoseconds, calls, hits}. */
   public static Map<Category, Map<String, long[]>> snapshot() {
      Map<Category, Map<String, long[]>> snapshot = new EnumMap<>(Category.class);
      COUNTERS.forEach((category, counters) -> {
         Map<String, long[]> values = new HashMap<>();
         counters.forEach((name, counter) -> values.put(name, new long[]{counter.nanos.sum(), counter.calls.sum(), counter.hits.sum()}));
         snapshot.put(category, values);
      });
      return snapshot;
   }

   public static Map<Category, Map<String, long[]>> minus(Map<Category, Map<String, long[]>> now, Map<Category, Map<String, long[]>> base) {
      Map<Category, Map<String, long[]>> result = new EnumMap<>(Category.class);
      now.forEach((category, values) -> {
         Map<String, long[]> before = base.getOrDefault(category, Map.of());
         Map<String, long[]> delta = new HashMap<>();
         values.forEach((name, v) -> {
            long[] b = before.getOrDefault(name, new long[3]);
            if (v[1] - b[1] > 0L) {
               delta.put(name, new long[]{v[0] - b[0], v[1] - b[1], v[2] - b[2]});
            }
         });
         result.put(category, delta);
      });
      return result;
   }

   public static long totalNanos(Map<String, long[]> values) {
      long total = 0L;
      for (long[] v : values.values()) {
         total += v[0];
      }

      return total;
   }

   /** The costliest names, as "name avg ms (share%)"; with hits, "name avg ms, hits/calls started (share%)". */
   public static String top(Map<String, long[]> values, int count, boolean withHits) {
      long total = totalNanos(values);
      List<Map.Entry<String, long[]>> entries = new ArrayList<>(values.entrySet());
      entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, long[]> entry : entries) {
         if (parts.size() >= count) {
            break;
         }

         long[] v = entry.getValue();
         double avg = v[0] / 1.0e6 / v[1];
         double share = total == 0L ? 0.0 : 100.0 * v[0] / total;
         parts.add(withHits
            ? String.format(Locale.ROOT, "%s %.2f ms, %,d of %,d started (%.0f%%)", entry.getKey(), avg, v[2], v[1], share)
            : String.format(Locale.ROOT, "%s %.2f ms x %,d (%.0f%%)", entry.getKey(), avg, v[1], share));
      }

      return parts.isEmpty() ? "none" : String.join(", ", parts);
   }

   private static final class Counter {
      final LongAdder nanos = new LongAdder();
      final LongAdder calls = new LongAdder();
      final LongAdder hits = new LongAdder();
   }
}
