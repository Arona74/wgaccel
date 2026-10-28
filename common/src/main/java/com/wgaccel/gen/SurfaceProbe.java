package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * GPU step G3.1 (measuring for the surface on the GPU): SurfaceSystem.buildSurface split into what runs before the
 * rules (FreeTerraForged's river water, its head hook), making the rule tree for the chunk, and the column loop (every
 * block from the top down to the world's floor, the rules tried on each stone block); in one chunk of eight the rules
 * themselves are timed and their calls counted. The first chunk's rule source is written out as a tree in
 * logs/wgaccel-surface-rules.txt with its node kinds counted ("WGA surface rules").
 */
public final class SurfaceProbe {
   private static final int SAMPLE_EVERY = 8;
   private static final AtomicLong CHUNKS = new AtomicLong();
   private static final AtomicBoolean DUMPED = new AtomicBoolean();
   public static final LongAdder RULE_CALLS = new LongAdder();
   public static final LongAdder SAMPLED_CHUNKS = new LongAdder();

   private static final class State {
      long start;
      boolean sampled;
      long ruleStart;
      long ruleNanos;
      long calls;
   }

   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private SurfaceProbe() {
   }

   public static void begin(Object ruleSource) {
      State state = STATE.get();
      state.start = System.nanoTime();
      state.sampled = CHUNKS.getAndIncrement() % SAMPLE_EVERY == 0;
      state.ruleNanos = 0L;
      state.calls = 0L;
      if (!DUMPED.getAndSet(true)) {
         dump(ruleSource);
      }
   }

   /** Just before the rule tree is made: everything since begin was the head hooks (FreeTerraForged's river water). */
   public static long beforeRules() {
      long now = System.nanoTime();
      Stats.record(Stage.SURFACE_BEFORE, now - STATE.get().start);
      return now;
   }

   public static void rulesMade(long start) {
      Stats.record(Stage.SURFACE_RULE_TREE, System.nanoTime() - start);
   }

   public static void ruleStart() {
      State state = STATE.get();
      if (state.sampled) {
         state.ruleStart = System.nanoTime();
      }
   }

   public static void ruleEnd() {
      State state = STATE.get();
      if (state.sampled) {
         state.ruleNanos += System.nanoTime() - state.ruleStart;
         state.calls++;
      }
   }

   public static void end() {
      State state = STATE.get();
      Stats.record(Stage.SURFACE_SYSTEM, System.nanoTime() - state.start);
      if (state.sampled) {
         Stats.record(Stage.SURFACE_RULES, state.ruleNanos);
         RULE_CALLS.add(state.calls);
         SAMPLED_CHUNKS.increment();
      }
   }

   private static void dump(Object root) {
      try {
         StringBuilder out = new StringBuilder("The overworld's surface rule source as SurfaceSystem.buildSurface gets it (Worldgen Accelerator, GPU step G3.1).\n");
         Map<String, Integer> kinds = new TreeMap<>();
         Map<Object, Integer> ids = new IdentityHashMap<>();
         dump(root, 0, out, kinds, ids);
         Files.writeString(Platform.logs().resolve("wgaccel-surface-rules.txt"), out, StandardCharsets.UTF_8);
         WorldgenAccelerator.LOG.info("WGA surface rules: {} distinct nodes of {} kinds: {}", ids.size(), kinds.size(), kinds);
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA surface rules: could not write the rule tree: {}", failure.toString());
      }
   }

   private static void dump(Object node, int depth, StringBuilder out, Map<String, Integer> kinds, Map<Object, Integer> ids) throws ReflectiveOperationException {
      String indent = "  ".repeat(depth);
      if (node == null || depth > 60) {
         out.append(indent).append(node == null ? "null" : "...").append('\n');
         return;
      }

      Class<?> type = node.getClass();
      if (node instanceof Number || node instanceof Boolean || node instanceof CharSequence || Names.className(type).startsWith("net.minecraft.world.level.block.state")
         || Names.className(type).startsWith("net.minecraft.resources") || Names.className(type).contains("VerticalAnchor")) {
         out.append(indent).append(node).append('\n');
         return;
      }

      // Not into biomes, registries or the client: a modpack's rules (biome conditions holding biome holders) led the walk
      // through every biome's features into textures and renderers (30,993 nodes of 226 kinds, 27 Oct 2026).
      if (node instanceof net.minecraft.world.level.biome.Biome || node instanceof net.minecraft.core.Registry<?> || Names.className(type).contains(".client.")
         || Names.className(type).startsWith("com.mojang.blaze3d")) {
         out.append(indent).append(Names.className(type).substring(Names.className(type).lastIndexOf('.') + 1)).append(" (not followed)\n");
         return;
      }

      if (node instanceof Enum<?> constant && !isRule(type)) {
         out.append(indent).append(constant.name()).append('\n');
         return;
      }

      Integer known = ids.get(node);
      if (known != null) {
         out.append(indent).append('#').append(known).append(" again\n");
         return;
      }

      if (node instanceof List<?> list) {
         out.append(indent).append("list of ").append(list.size()).append('\n');
         for (Object each : list) {
            dump(each, depth + 1, out, kinds, ids);
         }

         return;
      }

      String kind = Names.className(type).substring(Names.className(type).lastIndexOf('.') + 1);
      if (node instanceof Enum<?> constant) {
         kind = kind + "." + constant.name();
      }

      int id = ids.size();
      ids.put(node, id);
      kinds.merge(kind, 1, Integer::sum);
      out.append(indent).append('#').append(id).append(' ').append(kind).append('\n');
      if (node instanceof Enum<?>) {
         return;
      }

      if (type.isRecord()) {
         for (RecordComponent component : type.getRecordComponents()) {
            out.append(indent).append("  ").append(Names.name(component)).append(":\n");
            dump(Names.value(component, node), depth + 2, out, kinds, ids);
         }

         return;
      }

      for (Class<?> c = type; c != null && !Names.className(c).startsWith("java."); c = c.getSuperclass()) {
         for (java.lang.reflect.Field field : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
               continue;
            }

            try {
               field.setAccessible(true);
               out.append(indent).append("  ").append(Names.name(field)).append(":\n");
               dump(field.get(node), depth + 2, out, kinds, ids);
            } catch (RuntimeException ignored) {
            }
         }
      }
   }

   private static boolean isRule(Class<?> type) {
      for (Class<?> each : type.getInterfaces()) {
         if (Names.className(each).contains("SurfaceRules")) {
            return true;
         }
      }

      return false;
   }
}
