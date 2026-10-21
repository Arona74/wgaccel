package com.wgaccel.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.NoiseCheck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.Holder;

/**
 * GPU step G1.1: which of FreeTerraForged's noise modules the live preset uses, and how many, by walking everything
 * reachable from its heightmap (continents, regions, terrain types, rivers, climate). Only those modules need GPU
 * versions first. Logged once per terrain generator.
 */
public final class NoiseInventory {
   private static final Set<Object> DONE = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
   private static final int MAX_OBJECTS = 200_000;

   private NoiseInventory() {
   }

   public static void log(Object heightmap) {
      if (heightmap == null || !DONE.add(heightmap)) {
         return;
      }

      try {
         Map<String, Integer> noises = new TreeMap<>();
         Map<String, List<Object>> nodes = new HashMap<>();
         Map<String, Integer> others = new TreeMap<>();
         Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
         ArrayDeque<Object> queue = new ArrayDeque<>();
         queue.add(heightmap);
         while (!queue.isEmpty() && seen.size() < MAX_OBJECTS) {
            Object object = queue.poll();
            if (object == null || !seen.add(object)) {
               continue;
            }

            Class<?> type = object.getClass();
            String name = type.getName();
            // Caches, pools and finished tiles hold data, not the recipe: skip them.
            if (name.startsWith("etcodehome.freeterraforged.concurrent.") || name.contains(".densityfunction.tile.")
               || name.equals("etcodehome.freeterraforged.world.worldgen.cell.Cell")) {
               continue;
            }

            if (object instanceof Noise) {
               String kind = type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
               noises.merge(kind, 1, Integer::sum);
               nodes.computeIfAbsent(kind, k -> new ArrayList<>()).add(object);
            } else if (type.getName().startsWith("etcodehome.")) {
               others.merge(type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName(), 1, Integer::sum);
            }

            if (object instanceof Holder<?> holder) {
               if (holder.isBound()) {
                  queue.add(holder.value());
               }
            } else if (type.isArray()) {
               if (!type.getComponentType().isPrimitive()) {
                  for (int i = 0, n = Array.getLength(object); i < n; i++) {
                     queue.add(Array.get(object, i));
                  }
               }
            } else if (object instanceof Iterable<?> iterable && !type.getName().startsWith("etcodehome.")) {
               for (Object element : iterable) {
                  queue.add(element);
               }
            } else if (object instanceof Map<?, ?> map && !type.getName().startsWith("etcodehome.")) {
               queue.addAll(map.keySet());
               queue.addAll(map.values());
            } else if (type.getName().startsWith("etcodehome.")) {
               for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                  for (Field field : c.getDeclaredFields()) {
                     if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                     }

                     try {
                        field.setAccessible(true);
                        queue.add(field.get(object));
                     } catch (ReflectiveOperationException | RuntimeException ignored) {
                     }
                  }
               }
            }
         }

         int total = noises.values().stream().mapToInt(Integer::intValue).sum();
         WorldgenAccelerator.LOG.info("WGA FTF noise inventory: {} noise nodes of {} kinds: {}", total, noises.size(), noises);
         WorldgenAccelerator.LOG.info("WGA FTF terrain objects: {}", others);
         if (Config.GPU_MODE.get() == Config.GpuMode.CHECK && com.wgaccel.gpu.Lwjgl.PRESENT) {
            NoiseCheck.start(nodes);
         }
      } catch (RuntimeException | LinkageError failure) {
         WorldgenAccelerator.LOG.warn("WGA FTF noise inventory failed", failure);
      }
   }
}
