package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.DensityCheck;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.fml.loading.FMLPaths;

/**
 * GPU step G2.1: the overworld's live noise router (what the 3D fill works out: final density, aquifer noises, ore
 * veins), every density function tree written out to logs/wgaccel-density-tree.txt with its kinds and settings, shared
 * nodes once, and the node count by kind logged ("WGA density functions"). Only those kinds need GPU versions. C2ME's
 * compiled functions are followed to the vanilla trees they were compiled from (their blending fallback). With gpuMode
 * CHECK, the noises found are checked on the GPU ({@link DensityCheck}).
 */
public final class DensityInventory {
   private static final int MAX_NODES = 50_000;

   private DensityInventory() {
   }

   public static void log(ServerLevel overworld) {
      try {
         NoiseRouter router = overworld.getChunkSource().randomState().router();
         Map<Object, Integer> ids = new IdentityHashMap<>();
         Map<String, Integer> kinds = new TreeMap<>();
         Set<NormalNoise> noises = Collections.newSetFromMap(new IdentityHashMap<>());
         StringBuilder out = new StringBuilder("The overworld's noise router (Worldgen Accelerator, GPU step G2.1). #N marks a node; \"again\" a shared one.\n");
         List<String> sizes = new ArrayList<>();
         for (RecordComponent component : NoiseRouter.class.getRecordComponents()) {
            int before = ids.size();
            out.append('\n').append(component.getName()).append(":\n");
            dump(component.getAccessor().invoke(router), 1, out, ids, kinds, noises);
            sizes.add(component.getName() + " " + (ids.size() - before));
         }

         try {
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-density-tree.txt"), out, StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }

         WorldgenAccelerator.LOG.info("WGA density functions: {} distinct nodes of {} kinds: {}", ids.size(), kinds.size(), kinds);
         WorldgenAccelerator.LOG.info("WGA density functions new per router field (in order): {}; {} NormalNoise instances", String.join(", ", sizes),
            noises.size());
         if (Config.GPU_MODE.get() == Config.GpuMode.CHECK && com.wgaccel.gpu.Lwjgl.PRESENT) {
            List<Object> functions = new ArrayList<>();
            for (Object node : ids.keySet()) {
               if (node instanceof DensityFunction) {
                  functions.add(node);
               }
            }

            BlockPos spawn = overworld.getSharedSpawnPos();
            boolean veins = overworld.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator
               && generator.generatorSettings().value().oreVeinsEnabled();
            boolean aquifers = overworld.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator2
               && generator2.generatorSettings().value().isAquifersEnabled();
            WorldgenAccelerator.LOG.info("WGA overworld noise settings: ore veins {}, aquifers {}", veins ? "on" : "off", aquifers ? "on" : "off");
            DensityCheck.start(new ArrayList<>(noises), functions, router, overworld.getChunkSource().randomState().oreRandom(), veins, spawn.getX(), spawn.getZ(),
               overworld.getMinBuildHeight(), overworld.getHeight());
         }
      } catch (Exception | LinkageError failure) {
         WorldgenAccelerator.LOG.warn("WGA density function inventory failed: {}", failure.toString());
      }
   }

   private static void dump(Object node, int depth, StringBuilder out, Map<Object, Integer> ids, Map<String, Integer> kinds, Set<NormalNoise> noises)
         throws ReflectiveOperationException {
      out.append("  ".repeat(depth));
      if (node == null) {
         out.append("null\n");
         return;
      }

      Integer known = ids.get(node);
      if (known != null) {
         out.append('#').append(known).append(" again\n");
         return;
      }

      if (ids.size() >= MAX_NODES) {
         out.append("...\n");
         return;
      }

      int id = ids.size();
      ids.put(node, id);
      String kind = kind(node);
      List<Object> children = new ArrayList<>();
      List<String> settings = new ArrayList<>();
      for (Map.Entry<String, Object> field : fields(node).entrySet()) {
         Object value = field.getValue();
         String name = field.getKey();
         if (value instanceof DensityFunction || isSpline(value)) {
            children.add(value);
         } else if (value instanceof Holder<?> holder) {
            String key = holder.unwrapKey().map(k -> k.location().toString()).orElse("direct");
            Object held = holder.isBound() ? holder.value() : null;
            if (held instanceof DensityFunction) {
               settings.add(name + "=" + key);
               children.add(held);
            } else {
               settings.add(name + "=" + key);
            }
         } else if (value instanceof List<?> list && !list.isEmpty() && (list.get(0) instanceof DensityFunction || isSpline(list.get(0)))) {
            children.addAll(list);
            settings.add(name + "=" + list.size() + " items");
         } else if (value instanceof DensityFunction.NoiseHolder holder) {
            settings.add(name + "=" + holder.noiseData().unwrapKey().map(k -> k.location().toString()).orElse("direct"));
            if (holder.noise() != null) {
               noises.add(holder.noise());
            }
         } else if (value instanceof Supplier<?> supplier && node.getClass().getName().contains("c2me")) {
            Object original = supplier.get();
            if (original instanceof DensityFunction) {
               settings.add(name + " (the vanilla tree it was compiled from)");
               children.add(original);
            }
         } else if (value instanceof NormalNoise) {
            continue;
         } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < Math.min(length, 8); i++) {
               values.append(i == 0 ? "" : " ").append(Array.get(value, i));
            }

            settings.add(name + "=[" + values + (length > 8 ? " ..." : "") + "] (" + length + ")");
         } else if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?> || value instanceof String || value instanceof Character) {
            settings.add(name + "=" + value);
         } else if (value != null) {
            settings.add(name + "=" + kind(value));
         }
      }

      kinds.merge(kind, 1, Integer::sum);
      out.append('#').append(id).append(' ').append(kind);
      if (!settings.isEmpty()) {
         out.append(" (").append(String.join(", ", settings)).append(')');
      }

      out.append('\n');
      for (Object child : children) {
         dump(child, depth + 1, out, ids, kinds, noises);
      }
   }

   private static boolean isSpline(Object value) {
      return value != null && (value.getClass().getName().startsWith("net.minecraft.util.CubicSpline")
         || value.getClass().getName().contains("DensityFunctions$Spline$Coordinate"));
   }

   /** A kind's name: the class (with its outer class for nested ones), and the type of vanilla's two- and one-argument nodes. */
   private static String kind(Object node) {
      Class<?> type = node.getClass();
      String name = type.getName();
      int dot = name.lastIndexOf('.');
      String kind = (dot < 0 ? name : name.substring(dot + 1)).replace("DensityFunctions$", "");
      try {
         if (kind.equals("Ap2") || kind.equals("Mapped") || kind.equals("MulOrAdd") || kind.equals("Marker")) {
            Object operation = fields(node).get("type");
            if (operation != null) {
               kind += "." + operation;
            }
         }
      } catch (ReflectiveOperationException ignored) {
      }

      return kind;
   }

   /** A record's components, or an object's non-static fields, by name. */
   private static Map<String, Object> fields(Object object) throws ReflectiveOperationException {
      Map<String, Object> values = new TreeMap<>();
      Class<?> type = object.getClass();
      if (type.isRecord()) {
         for (RecordComponent component : type.getRecordComponents()) {
            component.getAccessor().setAccessible(true);
            values.put(component.getName(), component.getAccessor().invoke(object));
         }

         return values;
      }

      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
         for (Field field : c.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
               continue;
            }

            try {
               field.setAccessible(true);
               values.putIfAbsent(field.getName(), field.get(object));
            } catch (RuntimeException ignored) {
            }
         }
      }

      return values;
   }
}
