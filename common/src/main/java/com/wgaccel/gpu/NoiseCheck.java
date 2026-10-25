package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.function.EdgeFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G1.3 (config gpuMode CHECK): the live FreeTerraForged preset's noise nodes, kind by kind, evaluated on the
 * GPU and by FreeTerraForged's own Java at the same points and seeds, compared bit for bit, and the result logged
 * ("WGA GPU noise check: ..."); then whole trees ({@link TreeCheck}). Runs on its own thread at world load; changes
 * nothing in the world.
 */
public final class NoiseCheck {
   private static final int POINTS = 4096;
   private static final String SOURCE = NoiseKernels.NOISE_UTIL + NoiseKernels.PERLIN + NoiseKernels.RIDGE + NoiseKernels.SIMPLEX + NoiseKernels.PERLIN2 + NoiseKernels.WORLEY + NoiseKernels.PERLIN_CHECK;

   private NoiseCheck() {
   }

   public static void start(Map<String, List<Object>> nodes) {
      Thread thread = new Thread(() -> {
         try {
            run(nodes);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU noise check failed: {}", failure.toString());
         }
      }, "WGA GPU noise check");
      thread.setDaemon(true);
      thread.start();
   }

   private static void run(Map<String, List<Object>> nodes) throws ReflectiveOperationException {
      FloatBuffer xs = MemoryUtil.memAllocFloat(POINTS);
      FloatBuffer zs = MemoryUtil.memAllocFloat(POINTS);
      IntBuffer seeds = MemoryUtil.memAllocInt(POINTS);
      Random random = new Random(1L);
      for (int p = 0; p < POINTS; p++) {
         xs.put(p, (random.nextFloat() - 0.5F) * 400_000.0F);
         zs.put(p, (random.nextFloat() - 0.5F) * 400_000.0F);
         seeds.put(p, random.nextInt());
      }

      try {
         try (Gpu gpu = new Gpu(SOURCE, "noise_check.cu")) {
            check("Perlin", nodes.get("Perlin"), xs, zs, seeds, gpu, NoiseCheck::perlin);
            check("PerlinRidge", nodes.get("PerlinRidge"), xs, zs, seeds, gpu, (list, g) -> ridge(list, g, 0));
            check("Billow", nodes.get("Billow"), xs, zs, seeds, gpu, (list, g) -> ridge(list, g, 1));
            check("Simplex", nodes.get("Simplex"), xs, zs, seeds, gpu, (list, g) -> simplex(list, g, 79.869484F));
            check("Simplex2", nodes.get("Simplex2"), xs, zs, seeds, gpu, (list, g) -> simplex(list, g, 99.83685F));
            check("Perlin2", nodes.get("Perlin2"), xs, zs, seeds, gpu, NoiseCheck::perlin2);
            check("Worley", worleyWithoutLookups(nodes.get("Worley")), xs, zs, seeds, gpu, NoiseCheck::worley);
            check("WorleyEdge", nodes.get("WorleyEdge"), xs, zs, seeds, gpu, NoiseCheck::worleyEdge);
         }

         try {
            TreeCheck.run(nodes, xs, zs, seeds, POINTS);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU tree check failed: {}", failure.toString());
         }
      } finally {
         MemoryUtil.memFree(xs);
         MemoryUtil.memFree(zs);
         MemoryUtil.memFree(seeds);
      }
   }

   /** Packs a kind's nodes and launches its check kernel; returns the GPU results (nodes x points). */
   @FunctionalInterface
   private interface Launcher {
      FloatBuffer launch(List<Object> nodes, Inputs inputs) throws ReflectiveOperationException;
   }

   private record Inputs(Gpu gpu, FloatBuffer xs, FloatBuffer zs, IntBuffer seeds) {
   }

   private static void check(String kind, List<Object> list, FloatBuffer xs, FloatBuffer zs, IntBuffer seeds, Gpu gpu, Launcher launcher)
         throws ReflectiveOperationException {
      if (list == null || list.isEmpty()) {
         return;
      }

      long start = System.nanoTime();
      FloatBuffer out = launcher.launch(list, new Inputs(gpu, xs, zs, seeds));
      double gpuMs = (System.nanoTime() - start) / 1.0e6;
      long cpuStart = System.nanoTime();
      int different = 0;
      String first = null;
      for (int i = 0; i < list.size(); i++) {
         Noise noise = (Noise)list.get(i);
         for (int p = 0; p < POINTS; p++) {
            float expected = noise.compute(xs.get(p), zs.get(p), seeds.get(p));
            float actual = out.get(i * POINTS + p);
            boolean same = Float.isNaN(expected) ? Float.isNaN(actual) : Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual);
            if (!same) {
               if (first == null) {
                  first = String.format(Locale.ROOT, "node %s at (%s, %s) seed %d: java %s, gpu %s", list.get(i), xs.get(p), zs.get(p), seeds.get(p), expected, actual);
               }

               different++;
            }
         }
      }

      double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;
      int total = list.size() * POINTS;
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU noise check: %s, %d nodes x %,d points = %,d values: %s; GPU %.2f ms (with copies), Java %.0f ms (one thread)",
         kind, list.size(), POINTS, total, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER", gpuMs, cpuMs));
      if (first != null) {
         WorldgenAccelerator.LOG.warn("WGA GPU noise check: {} first difference: {}", kind, first);
      }

      MemoryUtil.memFree(out);
   }

   private static FloatBuffer perlin(List<Object> list, Inputs in) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 5);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes * 3);
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         fp.put(i * 5, (Float)c.get("frequency")).put(i * 5 + 1, (Float)c.get("lacunarity")).put(i * 5 + 2, (Float)c.get("gain"))
            .put(i * 5 + 3, (Float)c.get("min")).put(i * 5 + 4, (Float)c.get("max"));
         ip.put(i * 3, (Integer)c.get("seed")).put(i * 3 + 1, (Integer)c.get("octaves")).put(i * 3 + 2, ((Enum<?>)c.get("interpolation")).ordinal());
      }

      try {
         return in.gpu().launch("perlinCheck", nodes * POINTS, fp, ip, nodes, in.xs(), in.zs(), POINTS);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
      }
   }

   private static FloatBuffer ridge(List<Object> list, Inputs in, int billow) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 5);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes * 3);
      List<float[]> weights = new ArrayList<>();
      int offset = 0;
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         float[] w = (float[])c.get("spectralWeights");
         fp.put(i * 5, (Float)c.get("frequency")).put(i * 5 + 1, (Float)c.get("lacunarity")).put(i * 5 + 2, (Float)c.get("gain"))
            .put(i * 5 + 3, (Float)c.get("min")).put(i * 5 + 4, (Float)c.get("max"));
         ip.put(i * 3, (Integer)c.get("octaves")).put(i * 3 + 1, ((Enum<?>)c.get("interpolation")).ordinal()).put(i * 3 + 2, offset);
         weights.add(w);
         offset += w.length;
      }

      FloatBuffer allWeights = MemoryUtil.memAllocFloat(Math.max(1, offset));
      int at = 0;
      for (float[] w : weights) {
         for (float v : w) {
            allWeights.put(at++, v);
         }
      }

      try {
         return in.gpu().launch("ridgeCheck", nodes * POINTS, fp, ip, allWeights, nodes, in.xs(), in.zs(), in.seeds(), POINTS, billow);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
         MemoryUtil.memFree(allWeights);
      }
   }

   private static FloatBuffer simplex(List<Object> list, Inputs in, float scaler) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 5);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes);
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         fp.put(i * 5, (Float)c.get("frequency")).put(i * 5 + 1, (Float)c.get("lacunarity")).put(i * 5 + 2, (Float)c.get("gain"))
            .put(i * 5 + 3, (Float)c.get("min")).put(i * 5 + 4, (Float)c.get("max"));
         ip.put(i, (Integer)c.get("octaves"));
      }

      FloatBuffer g24 = grad24();
      try {
         return in.gpu().launch("simplexCheck", nodes * POINTS, g24, fp, ip, nodes, in.xs(), in.zs(), in.seeds(), POINTS, scaler);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
         MemoryUtil.memFree(g24);
      }
   }

   private static FloatBuffer perlin2(List<Object> list, Inputs in) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 5);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes * 3);
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         fp.put(i * 5, (Float)c.get("frequency")).put(i * 5 + 1, (Float)c.get("lacunarity")).put(i * 5 + 2, (Float)c.get("gain"))
            .put(i * 5 + 3, (Float)c.get("min")).put(i * 5 + 4, (Float)c.get("max"));
         ip.put(i * 3, (Integer)c.get("seed")).put(i * 3 + 1, (Integer)c.get("octaves")).put(i * 3 + 2, ((Enum<?>)c.get("interpolation")).ordinal());
      }

      FloatBuffer g24 = grad24();
      try {
         return in.gpu().launch("perlin2Check", nodes * POINTS, g24, fp, ip, nodes, in.xs(), in.zs(), POINTS);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
         MemoryUtil.memFree(g24);
      }
   }

   /** Worley nodes whose cells look up a nested noise wait for nested trees (G1.3 combinators); logged. */
   private static List<Object> worleyWithoutLookups(List<Object> list) throws ReflectiveOperationException {
      if (list == null) {
         return null;
      }

      List<Object> kept = new ArrayList<>();
      for (Object node : list) {
         if (!"NOISE_LOOKUP".equals(((Enum<?>)components(node).get("cellFunction")).name())) {
            kept.add(node);
         }
      }

      if (kept.size() < list.size()) {
         WorldgenAccelerator.LOG.info("WGA GPU noise check: Worley, {} of {} nodes look up a nested noise: not checked yet", list.size() - kept.size(), list.size());
      }

      return kept;
   }

   private static FloatBuffer worley(List<Object> list, Inputs in) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 4);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes * 2);
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         fp.put(i * 4, (Float)c.get("frequency")).put(i * 4 + 1, (Float)c.get("distance")).put(i * 4 + 2, (Float)c.get("min"))
            .put(i * 4 + 3, (Float)c.get("max"));
         ip.put(i * 2, ((Enum<?>)c.get("distanceFunction")).ordinal()).put(i * 2 + 1, ((Enum<?>)c.get("cellFunction")).ordinal());
      }

      FloatBuffer cell = cellTable();
      try {
         return in.gpu().launch("worleyCheck", nodes * POINTS, cell, fp, ip, nodes, in.xs(), in.zs(), in.seeds(), POINTS);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
         MemoryUtil.memFree(cell);
      }
   }

   private static FloatBuffer worleyEdge(List<Object> list, Inputs in) throws ReflectiveOperationException {
      int nodes = list.size();
      FloatBuffer fp = MemoryUtil.memAllocFloat(nodes * 5);
      IntBuffer ip = MemoryUtil.memAllocInt(nodes * 2);
      for (int i = 0; i < nodes; i++) {
         Map<String, Object> c = components(list.get(i));
         EdgeFunction edge = (EdgeFunction)c.get("edgeFunction");
         fp.put(i * 5, (Float)c.get("frequency")).put(i * 5 + 1, (Float)c.get("distance")).put(i * 5 + 2, edge.min()).put(i * 5 + 3, edge.max())
            .put(i * 5 + 4, edge.range());
         ip.put(i * 2, ((Enum<?>)c.get("distanceFunction")).ordinal()).put(i * 2 + 1, edge.ordinal());
      }

      FloatBuffer cell = cellTable();
      try {
         return in.gpu().launch("worleyEdgeCheck", nodes * POINTS, cell, fp, ip, nodes, in.xs(), in.zs(), in.seeds(), POINTS);
      } finally {
         MemoryUtil.memFree(fp);
         MemoryUtil.memFree(ip);
         MemoryUtil.memFree(cell);
      }
   }

   /** FTF's own 256-entry cell offset table (the caller frees the buffer). */
   private static FloatBuffer cellTable() {
      NoiseUtil.Vec2f[] table = NoiseUtil.CELL_2D;
      FloatBuffer cell = MemoryUtil.memAllocFloat(table.length * 2);
      for (int i = 0; i < table.length; i++) {
         cell.put(i * 2, table[i].x()).put(i * 2 + 1, table[i].y());
      }

      return cell;
   }

   /** FTF's own 32-entry gradient table, so the GPU uses exactly its values (the caller frees the buffer). */
   private static FloatBuffer grad24() {
      NoiseUtil.Vec2f[] table = NoiseUtil.GRAD_2D_24;
      FloatBuffer g24 = MemoryUtil.memAllocFloat(64);
      for (int i = 0; i < 32; i++) {
         g24.put(i * 2, table[i].x()).put(i * 2 + 1, table[i].y());
      }

      return g24;
   }

   /** A record's components by name (FTF's modules are package-private records). */
   static Map<String, Object> components(Object record) throws ReflectiveOperationException {
      Map<String, Object> values = new HashMap<>();
      for (RecordComponent component : record.getClass().getRecordComponents()) {
         Method accessor = component.getAccessor();
         accessor.setAccessible(true);
         values.put(Names.name(component), accessor.invoke(record));
      }

      return values;
   }
}
