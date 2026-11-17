package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G1.3, whole trees (config gpuMode CHECK): every node of the live preset that {@link NoiseCodegen} can turn
 * into CUDA is evaluated as the root of its tree on the GPU and by FreeTerraForged's own Java at the same points and
 * seeds, and compared bit for bit; logged as "WGA GPU tree check". The generated source is saved as
 * logs/wgaccel-noise-trees.cu. Changes nothing in the world.
 */
final class TreeCheck {
   /** Noise samples the CPU spends per root at most (expensive trees are compared at fewer points, at least 64). */
   private static final long CPU_BUDGET = 400_000;
   /** Noise samples per launch at most, so no launch runs long (Windows resets a GPU that is busy for over 2 s). */
   private static final long LAUNCH_SAMPLES = 1_000_000_000L;

   /** Roots costlier than this per point are left out (a 256-point slice of one would pass LAUNCH_SAMPLES). */
   private static final long MAX_COST = LAUNCH_SAMPLES / 256;

   private TreeCheck() {
   }

   static void run(Map<String, List<Object>> nodes, FloatBuffer xs, FloatBuffer zs, IntBuffer seeds, int points) throws Exception {
      NoiseCodegen codegen = new NoiseCodegen(pairs(NoiseUtil.GRAD_2D_24), pairs(NoiseUtil.CELL_2D), (float[])field("SIN"), (Float)field("radToIndex"));
      Map<String, Integer> missing = new TreeMap<>();
      List<Object> roots = new ArrayList<>();
      for (List<Object> list : nodes.values()) {
         for (Object node : list) {
            String kind = NoiseCodegen.kind(node);
            if (kind.equals("Constant") || kind.equals("HolderHolder")) {
               continue;
            }

            try {
               codegen.function(node);
               roots.add(node);
            } catch (NoiseCodegen.Unsupported unsupported) {
               missing.merge(unsupported.kind, 1, Integer::sum);
            }
         }
      }

      if (!missing.isEmpty()) {
         WorldgenAccelerator.LOG.info("WGA GPU tree check: nodes held back by kinds with no GPU version yet: {}", missing);
      }

      long maxCost = 0;
      int tooCostly = 0;
      for (int r = roots.size() - 1; r >= 0; r--) {
         long cost = codegen.cost(roots.get(r));
         if (cost > MAX_COST) {
            roots.remove(r);
            tooCostly++;
         } else {
            maxCost = Math.max(maxCost, cost);
         }
      }

      if (tooCostly > 0) {
         WorldgenAccelerator.LOG.info("WGA GPU tree check: {} roots left out: over {} noise samples per point", tooCostly, MAX_COST);
      }

      if (roots.isEmpty()) {
         return;
      }

      roots.sort(Comparator.comparingInt(codegen::id));
      List<String> names = new ArrayList<>();
      int tops = 0;
      for (Object root : roots) {
         names.add(codegen.function(root));
         tops += codegen.isChild(root) ? 0 : 1;
      }

      String source = codegen.source(names);
      try {
         Files.writeString(Platform.logs().resolve("wgaccel-noise-trees.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      long compileStart = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "noise_trees.cu")) {
         double compileMs = (System.nanoTime() - compileStart) / 1.0e6;
         int rows = roots.size();
         long dx = gpu.upload(xs);
         long dz = gpu.upload(zs);
         long dseeds = gpu.upload(seeds);
         long dout = gpu.allocate(4L * points * rows);
         long dunsure = gpu.allocate(4L * points * rows);
         long gpuStart = System.nanoTime();
         int launches = 0;
         for (int first = 0; first < rows; ) {
            int count = 0;
            long samples = 0;
            while (first + count < rows && count < 4096) {
               long more = codegen.cost(roots.get(first + count)) * points;
               if (count > 0 && samples + more > LAUNCH_SAMPLES) {
                  break;
               }

               samples += more;
               count++;
            }

            // One costly root alone is run in slices of points.
            int slice = count > 1 || samples <= LAUNCH_SAMPLES ? points : (int)Math.max(256, LAUNCH_SAMPLES / Math.max(1, samples / points) / 256 * 256);
            for (int offset = 0; offset < points; offset += slice) {
               int width = Math.min(slice, points - offset);
               gpu.run("treeCheck", (width + 255) / 256, count, dx, dz, dseeds, points, first, offset, dout + 4L * points * first, dunsure + 4L * points * first);
               launches++;
            }

            first += count;
         }

         double gpuMs = (System.nanoTime() - gpuStart) / 1.0e6;
         FloatBuffer out = MemoryUtil.memAllocFloat(points * rows);
         IntBuffer unsure = MemoryUtil.memAllocInt(points * rows);
         try {
            gpu.download(dout, out);
            gpu.download(dunsure, unsure);
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
               "WGA GPU tree check: %,d nodes as roots (%,d tree tops, costliest %,d noise samples per point), CUDA source %,d KB; compiled and loaded in %.0f ms; GPU %.1f ms for %,d x %,d points in %d launches",
               rows, tops, maxCost, source.length() / 1024, compileMs, gpuMs, rows, points, launches));
            compare(roots, codegen, out, unsure, xs, zs, seeds, points);
         } finally {
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(unsure);
         }
      }
   }

   private static void compare(List<Object> roots, NoiseCodegen codegen, FloatBuffer out, IntBuffer unsure, FloatBuffer xs, FloatBuffer zs, IntBuffer seeds,
         int points) throws ReflectiveOperationException {
      long cpuStart = System.nanoTime();
      long compared = 0;
      long different = 0;
      long notSure = 0;
      int failedRoots = 0;
      List<String> firsts = new ArrayList<>();
      Map<String, int[]> byKind = new TreeMap<>();
      for (int r = 0; r < roots.size(); r++) {
         Object root = roots.get(r);
         Noise noise = (Noise)root;
         String kind = NoiseCodegen.kind(root);
         int[] tally = byKind.computeIfAbsent(kind, k -> new int[2]);
         tally[0]++;
         int sample = (int)Math.max(64, Math.min(points, CPU_BUDGET / Math.max(1, codegen.cost(root))));
         boolean rootDiffers = false;
         for (int p = 0; p < sample; p++) {
            int at = r * points + p;
            if (unsure.get(at) != 0) {
               notSure++;
               continue;
            }

            float expected;
            try {
               expected = noise.compute(xs.get(p), zs.get(p), seeds.get(p));
            } catch (RuntimeException failure) {
               if (failedRoots++ == 0) {
                  WorldgenAccelerator.LOG.warn("WGA GPU tree check: Java failed on {} ({}): {}", codegen.function(root), kind, failure.toString());
               }

               break;
            }

            float actual = out.get(at);
            compared++;
            boolean same = Float.isNaN(expected) ? Float.isNaN(actual) : Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual);
            if (!same) {
               different++;
               if (!rootDiffers) {
                  rootDiffers = true;
                  tally[1]++;
                  if (firsts.size() < 8) {
                     firsts.add(String.format(Locale.ROOT, "%s (%s) at (%s, %s) seed %d: java %s, gpu %s", codegen.function(root), kind, xs.get(p), zs.get(p),
                        seeds.get(p), expected, actual));
                  }
               }
            }
         }
      }

      double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU tree check: %,d values compared: %s; %,d left to the CPU (pow too close to a rounding boundary); Java %.0f ms (one thread)",
         compared, different == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", different), notSure, cpuMs));
      StringBuilder kinds = new StringBuilder();
      for (Map.Entry<String, int[]> entry : byKind.entrySet()) {
         kinds.append(kinds.isEmpty() ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue()[0]);
         if (entry.getValue()[1] > 0) {
            kinds.append(" (").append(entry.getValue()[1]).append(" differ)");
         }
      }

      WorldgenAccelerator.LOG.info("WGA GPU tree check by kind (roots checked): {}", kinds);
      for (String first : firsts) {
         WorldgenAccelerator.LOG.warn("WGA GPU tree check: difference in {}", first);
      }

      if (failedRoots > 0) {
         WorldgenAccelerator.LOG.warn("WGA GPU tree check: Java failed on {} roots", failedRoots);
      }
   }

   static float[] pairs(NoiseUtil.Vec2f[] table) {
      float[] values = new float[table.length * 2];
      for (int i = 0; i < table.length; i++) {
         values[i * 2] = table[i].x();
         values[i * 2 + 1] = table[i].y();
      }

      return values;
   }

   /** One of NoiseUtil's private static fields (its sine table and index factor), so the GPU uses exactly its values. */
   static Object field(String name) throws ReflectiveOperationException {
      Field field = Names.declaredField(NoiseUtil.class, name);
      field.setAccessible(true);
      return field.get(null);
   }
}
