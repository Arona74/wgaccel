package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * GPU step G5.1: the overworld's biome plan as FreeTerraForged compiled it (the climate sampler's functions, the plan's
 * spatial ownership, sampler decoration stages, provider selection with its climate tables and ocean projections, and
 * selection decoration stages), written to logs/wgaccel-biome-plan.txt; the stage ids and the kinds of objects are
 * logged as "WGA biome plan". Read by reflection only (FreeTerraForged is optional); density functions are named, not
 * opened (the noise inventory has them). Changes nothing.
 */
public final class BiomePlanInventory {
   private static final int MAX_DEPTH = 9;
   private static final int MAX_ITEMS = 24;

   private BiomePlanInventory() {
   }

   public static void log(ServerLevel overworld) {
      try {
         Climate.Sampler sampler = overworld.getChunkSource().randomState().sampler();
         Method getPlan = findMethod(sampler.getClass(), "getWorldgenPlan");
         if (getPlan == null) {
            WorldgenAccelerator.LOG.info("WGA biome plan: the climate sampler has no FreeTerraForged plan");
            return;
         }

         Object plan = getPlan.invoke(sampler);
         if (plan == null) {
            WorldgenAccelerator.LOG.info("WGA biome plan: none bound to the overworld's climate sampler");
            return;
         }

         Map<Object, Integer> ids = new IdentityHashMap<>();
         Map<String, Integer> kinds = new TreeMap<>();
         StringBuilder out = new StringBuilder("The overworld's biome plan (Worldgen Accelerator, GPU step G5.1). #N marks an object; \"again\" a shared one.\n");
         out.append("\nclimate sampler (").append(Names.className(sampler.getClass())).append("):\n");
         for (RecordComponent component : Climate.Sampler.class.getRecordComponents()) {
            Object value = Names.value(component, sampler);
            out.append("  ").append(Names.name(component)).append(": ").append(value instanceof DensityFunction ? describeFunction(value) : String.valueOf(value)).append('\n');
         }

         List<String> parts = new ArrayList<>();
         for (String part : new String[] {"spatialOwnership", "samplerDecoration", "providerSelection", "selectionDecoration", "biomeComposition"}) {
            Method accessor = findMethod(plan.getClass(), part);
            if (accessor == null) {
               continue;
            }

            int before = ids.size();
            out.append('\n').append(part).append(":\n");
            dump(accessor.invoke(plan), 1, out, ids, kinds);
            parts.add(part + " " + (ids.size() - before));
         }

         try {
            Files.writeString(Platform.logs().resolve("wgaccel-biome-plan.txt"), out, StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }

         WorldgenAccelerator.LOG.info("WGA biome plan: {} objects (new per part: {}); sampler stages {}; selection stages {}; providers {}", ids.size(),
            String.join(", ", parts), stageIds(plan, "samplerDecoration"), stageIds(plan, "selectionDecoration"), providerIds(plan));
         WorldgenAccelerator.LOG.info("WGA biome plan kinds: {}", kinds);
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA biome plan inventory failed: {}", failure.toString());
      }
   }

   private static String stageIds(Object plan, String part) throws ReflectiveOperationException {
      Object decoration = call(plan, part);
      Object stages = decoration == null ? null : call(decoration, "stages");
      if (!(stages instanceof List<?> list)) {
         return "none";
      }

      List<String> names = new ArrayList<>();
      for (Object stage : list) {
         Object decorator = call(stage, "decorator");
         names.add(call(stage, "id") + " (order " + call(stage, "order") + ", " + (decorator == null ? "null" : Names.simpleName(decorator.getClass())) + ")");
      }

      return names.toString();
   }

   private static String providerIds(Object plan) throws ReflectiveOperationException {
      Object selection = call(plan, "providerSelection");
      Object providers = selection == null ? null : call(selection, "providers");
      if (!(providers instanceof List<?> list)) {
         return "none";
      }

      List<String> names = new ArrayList<>();
      for (Object provider : list) {
         Object candidates = call(provider, "candidates");
         Object values = candidates instanceof Climate.ParameterList<?> parameters ? parameters.values() : null;
         names.add(call(provider, "id") + " (weight " + call(provider, "weight") + ", " + (values instanceof List<?> v ? v.size() : "?") + " climate points)");
      }

      return names.toString();
   }

   private static void dump(Object node, int depth, StringBuilder out, Map<Object, Integer> ids, Map<String, Integer> kinds) throws ReflectiveOperationException {
      String indent = "  ".repeat(depth);
      if (node == null || node instanceof Number || node instanceof Boolean || node instanceof CharSequence || node instanceof Enum<?> || node instanceof Character
         || Names.className(node.getClass()).equals("net.minecraft.resources.ResourceLocation") || node instanceof Class<?>) {
         out.append(indent).append(node).append('\n');
         return;
      }

      if (node instanceof Holder<?> holder) {
         out.append(indent).append("holder ").append(holder.unwrapKey().map(Object::toString).orElse("(direct " + Names.simpleName(holder.value().getClass()) + ")"))
            .append('\n');
         return;
      }

      if (node instanceof DensityFunction) {
         out.append(indent).append(describeFunction(node)).append('\n');
         return;
      }

      if (node instanceof Optional<?> optional) {
         if (optional.isEmpty()) {
            out.append(indent).append("(empty)\n");
         } else {
            dump(optional.get(), depth, out, ids, kinds);
         }

         return;
      }

      Integer known = ids.get(node);
      if (known != null) {
         out.append(indent).append('#').append(known).append(" again (").append(Names.simpleName(node.getClass())).append(")\n");
         return;
      }

      int id = ids.size();
      ids.put(node, id);
      String kind = Names.className(node.getClass());
      kind = kind.substring(kind.lastIndexOf('.') + 1);
      kinds.merge(kind, 1, Integer::sum);
      if (node instanceof Collection<?> collection) {
         out.append(indent).append('#').append(id).append(' ').append(kind).append(" of ").append(collection.size()).append('\n');
         if (depth < MAX_DEPTH) {
            int i = 0;
            for (Object item : collection) {
               if (i++ >= MAX_ITEMS) {
                  out.append(indent).append("  ... ").append(collection.size() - MAX_ITEMS).append(" more\n");
                  break;
               }

               dump(item, depth + 1, out, ids, kinds);
            }
         }

         return;
      }

      if (node instanceof Map<?, ?> map) {
         out.append(indent).append('#').append(id).append(' ').append(kind).append(" of ").append(map.size()).append('\n');
         if (depth < MAX_DEPTH) {
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
               if (i++ >= MAX_ITEMS) {
                  out.append(indent).append("  ... ").append(map.size() - MAX_ITEMS).append(" more\n");
                  break;
               }

               out.append(indent).append("  key ").append(entry.getKey()).append(":\n");
               dump(entry.getValue(), depth + 2, out, ids, kinds);
            }
         }

         return;
      }

      if (node.getClass().isArray()) {
         int length = Array.getLength(node);
         out.append(indent).append('#').append(id).append(' ').append(Names.simpleName(node.getClass().getComponentType())).append('[').append(length).append(']');
         if (node.getClass().getComponentType().isPrimitive()) {
            out.append(' ');
            for (int i = 0; i < Math.min(length, 12); i++) {
               out.append(i == 0 ? "" : ", ").append(Array.get(node, i));
            }

            out.append(length > 12 ? ", ..." : "").append('\n');
         } else {
            out.append('\n');
            for (int i = 0; i < Math.min(length, MAX_ITEMS) && depth < MAX_DEPTH; i++) {
               dump(Array.get(node, i), depth + 1, out, ids, kinds);
            }
         }

         return;
      }

      out.append(indent).append('#').append(id).append(' ').append(Names.className(node.getClass())).append('\n');
      if (depth >= MAX_DEPTH || skipped(node)) {
         return;
      }

      if (node.getClass().isRecord()) {
         for (RecordComponent component : node.getClass().getRecordComponents()) {
            out.append(indent).append("  .").append(Names.name(component)).append(":\n");
            dump(Names.value(component, node), depth + 2, out, ids, kinds);
         }

         return;
      }

      for (Class<?> type = node.getClass(); type != null && !Names.className(type).startsWith("java."); type = type.getSuperclass()) {
         for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
               continue;
            }

            try {
               field.setAccessible(true);
            } catch (RuntimeException inaccessible) {
               continue;
            }

            out.append(indent).append("  .").append(Names.name(field)).append(":\n");
            dump(field.get(node), depth + 2, out, ids, kinds);
         }
      }
   }

   /** Objects not opened: registries, caches, threads, locks, Minecraft's own big objects. */
   private static boolean skipped(Object node) {
      String name = Names.className(node.getClass());
      return name.contains("Registry") || name.contains("Cache") || name.contains("Thread") || name.contains("Lock") || name.contains("Executor")
         || name.startsWith("net.minecraft.server") || name.startsWith("net.minecraft.world.level.biome.Biome") || name.contains("Logger")
         || name.contains("Atomic") || name.contains("$$Lambda") || name.contains("Report") || name.contains("Diagnostics");
   }

   private static String describeFunction(Object function) {
      String name = Names.className(function.getClass());
      return "density function " + name.substring(name.lastIndexOf('.') + 1);
   }

   private static Object call(Object target, String name) throws ReflectiveOperationException {
      Method method = findMethod(target.getClass(), name);
      return method == null ? null : method.invoke(target);
   }

   private static Method findMethod(Class<?> type, String name) {
      for (Class<?> t = type; t != null; t = t.getSuperclass()) {
         for (Method method : t.getDeclaredMethods()) {
            if (Names.name(method).equals(name) && method.getParameterCount() == 0) {
               method.setAccessible(true);
               return method;
            }
         }
      }

      for (Method method : type.getMethods()) {
         if (Names.name(method).equals(name) && method.getParameterCount() == 0) {
            return method;
         }
      }

      return null;
   }
}
