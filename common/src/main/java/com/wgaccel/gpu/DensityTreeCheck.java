package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Platform;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G2.2b (config gpuMode CHECK): every density function node of the overworld's noise router that
 * {@link DensityCodegen} can turn into CUDA, worked out as the root of its tree on the GPU and by vanilla's own Java at
 * the same block positions, compared bit for bit; logged as "WGA GPU density tree check". The positions lie around
 * the world spawn (FreeTerraForged's tiles there are made already; its CellSampler values are worked out on the CPU
 * and handed in). The generated source is saved as logs/wgaccel-density-trees.cu. Changes nothing in the world.
 */
final class DensityTreeCheck {
   private static final int POINTS = 4096;
   private static final int ROWS_PER_LAUNCH = 32;

   private DensityTreeCheck() {
   }

   static void run(Collection<?> nodes, int spawnX, int spawnZ, int minY, int height) throws Exception {
      DensityCodegen codegen = new DensityCodegen();
      Map<String, Integer> missing = new TreeMap<>();
      List<Object> roots = new ArrayList<>();
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      for (Object node : nodes) {
         Object actual = DensityCodegen.unwrap(node);
         String kind = DensityCodegen.kind(actual);
         if (kind.equals("HolderHolder") || kind.equals("CompiledDensityFunction") || kind.equals("Constant") || !seen.add(actual)) {
            continue;
         }

         try {
            codegen.function(actual);
            roots.add(actual);
         } catch (DensityCodegen.Unsupported unsupported) {
            missing.merge(unsupported.kind, 1, Integer::sum);
         }
      }

      if (!missing.isEmpty()) {
         WorldgenAccelerator.LOG.info("WGA GPU density tree check: nodes held back by kinds with no GPU version yet: {}", missing);
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

      IntBuffer xs = MemoryUtil.memAllocInt(POINTS);
      IntBuffer ys = MemoryUtil.memAllocInt(POINTS);
      IntBuffer zs = MemoryUtil.memAllocInt(POINTS);
      Random random = new Random(3L);
      for (int p = 0; p < POINTS; p++) {
         xs.put(p, spawnX - 200 + random.nextInt(401));
         ys.put(p, minY + random.nextInt(height));
         zs.put(p, spawnZ - 200 + random.nextInt(401));
      }

      List<Object> samplers = codegen.cellSamplers();
      DoubleBuffer cells = MemoryUtil.memAllocDouble(Math.max(1, samplers.size() * POINTS));
      for (int k = 0; k < samplers.size(); k++) {
         DensityFunction sampler = (DensityFunction)samplers.get(k);
         for (int p = 0; p < POINTS; p++) {
            cells.put(k * POINTS + p, sampler.compute(new DensityFunction.SinglePointContext(xs.get(p), ys.get(p), zs.get(p))));
         }
      }

      double[] noiseData = codegen.noiseData();
      int[] permutations = codegen.permutations();
      DoubleBuffer nd = MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData);
      IntBuffer np = MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations);
      String source = codegen.source(names);
      try {
         Files.writeString(Platform.logs().resolve("wgaccel-density-trees.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      int rows = roots.size();
      DoubleBuffer out = MemoryUtil.memAllocDouble(rows * POINTS);
      long compileStart = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "density_trees.cu")) {
         double compileMs = (System.nanoTime() - compileStart) / 1.0e6;
         long dx = gpu.upload(xs);
         long dy = gpu.upload(ys);
         long dz = gpu.upload(zs);
         long dcells = gpu.upload(cells);
         long dnd = gpu.upload(nd);
         long dnp = gpu.upload(np);
         long dout = gpu.allocate(8L * rows * POINTS);
         long gpuStart = System.nanoTime();
         for (int first = 0; first < rows; first += ROWS_PER_LAUNCH) {
            int count = Math.min(ROWS_PER_LAUNCH, rows - first);
            gpu.run("densityCheck", (POINTS + 255) / 256, count, dx, dy, dz, dcells, POINTS, dnd, dnp, first, 0, dout + 8L * POINTS * first);
         }

         double gpuMs = (System.nanoTime() - gpuStart) / 1.0e6;
         gpu.download(dout, out);
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU density tree check: %,d nodes as roots (%,d tree tops), %d CellSampler inputs, CUDA source %,d KB; compiled and loaded in %.0f ms; GPU %.1f ms for %,d x %,d points",
            rows, tops, samplers.size(), source.length() / 1024, compileMs, gpuMs, rows, POINTS));
         compare(roots, codegen, out, xs, ys, zs);
      } finally {
         for (java.nio.Buffer buffer : new java.nio.Buffer[] {xs, ys, zs, cells, nd, np, out}) {
            MemoryUtil.memFree(buffer);
         }
      }
   }

   private static void compare(List<Object> roots, DensityCodegen codegen, DoubleBuffer out, IntBuffer xs, IntBuffer ys, IntBuffer zs)
         throws ReflectiveOperationException {
      long start = System.nanoTime();
      long compared = 0;
      long different = 0;
      int failedRoots = 0;
      List<String> firsts = new ArrayList<>();
      Map<String, int[]> byKind = new TreeMap<>();
      for (int r = 0; r < roots.size(); r++) {
         DensityFunction root = (DensityFunction)roots.get(r);
         String kind = DensityCodegen.kind(root);
         if (kind.equals("Ap2") || kind.equals("Mapped") || kind.equals("Marker")) {
            Object type = DensityCodegen.fields(root).get("type");
            kind += "." + type;
         }

         int[] tally = byKind.computeIfAbsent(kind, k -> new int[2]);
         tally[0]++;
         boolean rootDiffers = false;
         for (int p = 0; p < POINTS; p++) {
            double expected;
            try {
               expected = root.compute(new DensityFunction.SinglePointContext(xs.get(p), ys.get(p), zs.get(p)));
            } catch (RuntimeException failure) {
               if (failedRoots++ == 0) {
                  WorldgenAccelerator.LOG.warn("WGA GPU density tree check: Java failed on {} ({}): {}", codegen.function(root), kind, failure.toString());
               }

               break;
            }

            double actual = out.get(r * POINTS + p);
            compared++;
            boolean same = Double.isNaN(expected) ? Double.isNaN(actual) : Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual);
            if (!same) {
               different++;
               if (!rootDiffers) {
                  rootDiffers = true;
                  tally[1]++;
                  if (firsts.size() < 8) {
                     firsts.add(String.format(Locale.ROOT, "%s (%s) at (%d, %d, %d): java %s, gpu %s", codegen.function(root), kind, xs.get(p), ys.get(p), zs.get(p),
                        expected, actual));
                  }
               }
            }
         }
      }

      double cpuMs = (System.nanoTime() - start) / 1.0e6;
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU density tree check: %,d values compared: %s; Java %.0f ms (one thread)", compared,
         different == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", different), cpuMs));
      StringBuilder kinds = new StringBuilder();
      for (Map.Entry<String, int[]> entry : byKind.entrySet()) {
         kinds.append(kinds.isEmpty() ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue()[0]);
         if (entry.getValue()[1] > 0) {
            kinds.append(" (").append(entry.getValue()[1]).append(" differ)");
         }
      }

      WorldgenAccelerator.LOG.info("WGA GPU density tree check by kind (roots checked): {}", kinds);
      for (String first : firsts) {
         WorldgenAccelerator.LOG.warn("WGA GPU density tree check: difference in {}", first);
      }

      if (failedRoots > 0) {
         WorldgenAccelerator.LOG.warn("WGA GPU density tree check: Java failed on {} roots", failedRoots);
      }
   }
}
