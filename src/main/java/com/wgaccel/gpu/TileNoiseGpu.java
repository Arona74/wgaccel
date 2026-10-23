package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step T1 (config gpuTileNoise): FreeTerraForged's costliest tile work - the terrain populators' noise (in a run of
 * 0.9.67 about 75% of its tile threads' time was the mountain chains' Erosion noise module) - worked out on the GPU.
 * For each heightmap (one per world) the TerrainPopulators reachable from its terrain whose four noise trees (base,
 * height, erosion, weirdness) all have a GPU version and are costly are found; their trees are made into CUDA by
 * {@link NoiseCodegen} (bit-identical, G1) and compiled into the shared context (four consecutive rows per populator).
 *
 * <p>T1c (a column uses only one region's mountains, but making every populator for every column cost ~150 ms of GPU
 * per tile): each of FreeTerraForged's tile batches first walks its columns through Heightmap.applyTerrain with these
 * populators only noting that the column needs them ({@link #need}; they return placeholders, everything else runs as
 * usual), then {@link #fill} makes each populator's values on the GPU at exactly the columns that asked, then the
 * batch's real pass takes them ({@link #lookup}). Anything not made, unsure, or asked at other coordinates is the CPU's.
 * FreeTerraForged's classes only through reflection, so other versions only lose the speed.
 */
public final class TileNoiseGpu {
   /** A populator is put on the GPU when the summed cost of its trees is at least this share of the costliest's. */
   private static final double COST_SHARE = 0.2;
   /**
    * T2 (FreeTerraForged 1.0.0013's raw chunk blocks, {@link com.wgaccel.ftf.FtfRawBlocks}): every populator with a GPU
    * version, not only the costliest. There the others would run twice on the CPU (in the dry pass and the real one);
    * the 23 Oct 2026 profile had them at 28% of the blocks' time with only 4 of 42 on the GPU. Set before the program is made.
    */
   public static volatile boolean allPopulators;
   private static final long LAUNCH_SAMPLES = 400_000_000L;

   /** One heightmap's program: per chosen populator its number (rows 4n..4n+3: base, height, erosion, weirdness). */
   static final class Program {
      final float frequency;
      final Map<Object, Integer> numbers = new IdentityHashMap<>();
      final long[] costs;
      final Gpu gpu;

      Program(float frequency, List<Object> populators, long[] costs, Gpu gpu) {
         this.frequency = frequency;
         for (Object populator : populators) {
            this.numbers.put(populator, this.numbers.size());
         }

         this.costs = costs;
         this.gpu = gpu;
      }
   }

   /** A tile's values: per populator 4 floats per column and a state per column (0 not made, 1 made, 2 made unsure). */
   public static final class Table {
      final Program program;
      final int minX;
      final int minZ;
      final int size;
      final float[][] values;
      final byte[][] state;

      Table(Program program, int minX, int minZ, int size) {
         this.program = program;
         this.minX = minX;
         this.minZ = minZ;
         this.size = size;
         this.values = new float[program.numbers.size()][];
         this.state = new byte[program.numbers.size()][];
      }

      boolean contains(int x, int z) {
         return x >= this.minX && x < this.minX + this.size && z >= this.minZ && z < this.minZ + this.size;
      }

      public boolean containsColumn(int x, int z) {
         return this.contains(x, z);
      }

      /** The populator's number, or -1. */
      public int number(Object populator) {
         Integer n = this.program.numbers.get(populator);
         return n == null ? -1 : n;
      }

      public int populators() {
         return this.program.numbers.size();
      }

      public int cell(int worldX, int worldZ) {
         return (worldX - this.minX) + (worldZ - this.minZ) * this.size;
      }

      private synchronized void ensure(int n) {
         if (this.values[n] == null) {
            int cells = this.size * this.size;
            this.values[n] = new float[4 * cells];
            this.state[n] = new byte[cells];
         }
      }
   }

   private static final Map<Object, Object> PROGRAMS = Collections.synchronizedMap(new IdentityHashMap<>());
   private static final Object FAILED = new Object();
   private static final Map<Object, Table> TABLES = Collections.synchronizedMap(new IdentityHashMap<>());
   private static volatile boolean disabled;
   public static final LongAdder TILES = new LongAdder();
   public static final LongAdder BATCHES = new LongAdder();
   public static final LongAdder GPU_NANOS = new LongAdder();
   public static final LongAdder DRY_NANOS = new LongAdder();
   public static final LongAdder MADE = new LongAdder();
   public static final LongAdder USED = new LongAdder();
   public static final LongAdder CPU = new LongAdder();
   public static final LongAdder CHECKED = new LongAdder();
   public static final LongAdder DIFFERENT = new LongAdder();
   private static final java.util.Set<String> TRACED = java.util.concurrent.ConcurrentHashMap.newKeySet();

   private TileNoiseGpu() {
   }

   /** Each step's first time, once in the log (to see where a tile's GPU noise stops). */
   public static void trace(String step) {
      String key = step.replaceAll("[-0-9]+", "#");
      if (TRACED.add(key)) {
         WorldgenAccelerator.LOG.info("WGA GPU tile noise trace: {}", step);
      }
   }

   public static boolean disabled() {
      return disabled;
   }

   /** A difference found by a check: off from now on (the CPU makes everything). */
   public static void disable(String why) {
      DIFFERENT.increment();
      if (!disabled) {
         disabled = true;
         WorldgenAccelerator.LOG.warn("WGA GPU tile noise off: {}", why);
      }
   }

   /** At a tile's start: an empty table for it (filled per batch), or null (the CPU makes it as before). */
   public static Table begin(Object tile, Object heightmap, int minX, int minZ, int size) {
      if (disabled || !Lwjgl.PRESENT) {
         trace("begin: " + (disabled ? "disabled" : "no LWJGL"));
         return null;
      }

      try {
         Object made = PROGRAMS.get(heightmap);
         if (made == null) {
            synchronized (PROGRAMS) {
               made = PROGRAMS.get(heightmap);
               if (made == null) {
                  try {
                     made = build(heightmap);
                  } catch (Throwable failure) {
                     WorldgenAccelerator.LOG.warn("WGA GPU tile noise: no program for this world's terrain: {}", failure.toString());
                     made = FAILED;
                  }

                  PROGRAMS.put(heightmap, made);
               }
            }
         }

         if (!(made instanceof Program p) || p.gpu == null) {
            trace("begin: " + (made == FAILED ? "no program (failed)" : "no populators on the GPU"));
            return null;
         }

         Table table = new Table(p, minX, minZ, size);
         TABLES.put(tile, table);
         TILES.increment();
         trace("begin: a table");
         return table;
      } catch (Throwable failure) {
         disabled = true;
         WorldgenAccelerator.LOG.warn("WGA GPU tile noise off: {}", failure.toString());
         return null;
      }
   }

   /** The tile's table, or null. */
   public static Table tableFor(Object tile) {
      return TABLES.get(tile);
   }

   /** At a tile's end. */
   public static void end(Object tile) {
      TABLES.remove(tile);
   }

   /**
    * After a batch's dry pass: per populator number the cells that asked for it (needs[n]); each populator's four rows
    * made on the GPU at those columns.
    */
   public static void fill(Table table, IntArrayList[] needs) {
      Program p = table.program;
      long start = System.nanoTime();
      try {
         for (int n = 0; n < needs.length; n++) {
            IntArrayList cells = needs[n];
            if (cells == null || cells.isEmpty()) {
               continue;
            }

            table.ensure(n);
            int points = cells.size();
            float[] x = new float[points];
            float[] z = new float[points];
            for (int i = 0; i < points; i++) {
               int cell = cells.getInt(i);
               x[i] = (float)(table.minX + cell % table.size) * p.frequency;
               z[i] = (float)(table.minZ + cell / table.size) * p.frequency;
            }

            float[] out = new float[4 * points];
            byte[] unsure = new byte[4 * points];
            evaluate(p, x, z, points, 4 * n, out, unsure);
            float[] values = table.values[n];
            byte[] state = table.state[n];
            for (int i = 0; i < points; i++) {
               int cell = cells.getInt(i);
               boolean sure = true;
               for (int k = 0; k < 4; k++) {
                  values[4 * cell + k] = out[k * points + i];
                  sure &= unsure[k * points + i] == 0;
               }

               state[cell] = (byte)(sure ? 1 : 2);
            }

            MADE.add(points);
         }

         BATCHES.increment();
      } catch (Throwable failure) {
         disable("GPU: " + failure);
      } finally {
         GPU_NANOS.add(System.nanoTime() - start);
      }
   }

   /**
    * The populator's four values at the column (out[0..3]: base, height, erosion, weirdness) when the GPU made them sure
    * and the coordinates are the ones the GPU used; false otherwise.
    */
   public static boolean lookup(Table table, int n, int worldX, int worldZ, float x, float z, float[] out) {
      byte[] state = table.state[n];
      if (state == null) {
         return false;
      }

      float frequency = table.program.frequency;
      if (Float.floatToRawIntBits(x) != Float.floatToRawIntBits((float)worldX * frequency)
         || Float.floatToRawIntBits(z) != Float.floatToRawIntBits((float)worldZ * frequency)) {
         return false;
      }

      int cell = table.cell(worldX, worldZ);
      if (state[cell] != 1) {
         return false;
      }

      float[] values = table.values[n];
      for (int k = 0; k < 4; k++) {
         out[k] = values[4 * cell + k];
      }

      return true;
   }

   /** Rows first..first+3 at these points into out[k * points + i] (and unsure), in launches of at most LAUNCH_SAMPLES samples. */
   private static void evaluate(Program p, float[] x, float[] z, int points, int first, float[] out, byte[] unsure) {
      FloatBuffer xs = MemoryUtil.memAllocFloat(points);
      FloatBuffer zs = MemoryUtil.memAllocFloat(points);
      IntBuffer seeds = MemoryUtil.memCallocInt(points);
      FloatBuffer values = MemoryUtil.memAllocFloat(4 * points);
      IntBuffer flags = MemoryUtil.memAllocInt(4 * points);
      long dx = 0;
      long dz = 0;
      long dseeds = 0;
      long dout = 0;
      long dflags = 0;
      try {
         xs.put(0, x, 0, points);
         zs.put(0, z, 0, points);
         dx = p.gpu.upload(xs);
         dz = p.gpu.upload(zs);
         dseeds = p.gpu.upload(seeds);
         dout = p.gpu.allocate(16L * points);
         dflags = p.gpu.allocate(16L * points);
         for (int i = 0; i < 4; ) {
            int run = 1;
            long samples = p.costs[first + i] * points;
            while (i + run < 4 && samples + p.costs[first + i + run] * points <= LAUNCH_SAMPLES) {
               samples += p.costs[first + i + run] * points;
               run++;
            }

            int slice = run > 1 || samples <= LAUNCH_SAMPLES ? points : (int)Math.max(256, LAUNCH_SAMPLES / Math.max(1, samples / points) / 256 * 256);
            for (int offset = 0; offset < points; offset += slice) {
               int width = Math.min(slice, points - offset);
               p.gpu.run("treeCheck", (width + 255) / 256, run, dx, dz, dseeds, points, first + i, offset, dout + 4L * points * i, dflags + 4L * points * i);
            }

            i += run;
         }

         p.gpu.download(dout, values);
         p.gpu.download(dflags, flags);
         values.get(0, out, 0, 4 * points);
         for (int i = 0; i < 4 * points; i++) {
            unsure[i] = (byte)(flags.get(i) != 0 ? 1 : 0);
         }
      } finally {
         for (long device : new long[] {dx, dz, dseeds, dout, dflags}) {
            if (device != 0L) {
               p.gpu.free(device);
            }
         }

         MemoryUtil.memFree(xs);
         MemoryUtil.memFree(zs);
         MemoryUtil.memFree(seeds);
         MemoryUtil.memFree(values);
         MemoryUtil.memFree(flags);
      }
   }

   private static Program build(Object heightmap) throws Exception {
      float frequency = (Float)NoiseCodegen.invoke(heightmap, "terrainFrequency");
      Object terrain = NoiseCodegen.invoke(heightmap, "terrain");
      List<Object> found = new ArrayList<>();
      walk(terrain, Collections.newSetFromMap(new IdentityHashMap<>()), found, 0);
      NoiseCodegen codegen = new NoiseCodegen(TreeCheck.pairs(NoiseUtil.GRAD_2D_24), TreeCheck.pairs(NoiseUtil.CELL_2D), (float[])TreeCheck.field("SIN"),
         (Float)TreeCheck.field("radToIndex"));
      String[] parts = {"base", "height", "erosion", "weirdness"};
      List<Object> supported = new ArrayList<>();
      List<long[]> costs = new ArrayList<>();
      long maxCost = 0;
      int unsupported = 0;
      Map<String, Integer> missingKinds = new java.util.TreeMap<>();
      for (Object populator : found) {
         long[] cost = new long[4];
         try {
            for (int k = 0; k < 4; k++) {
               Object noise = NoiseCodegen.invoke(populator, parts[k]);
               codegen.function(noise);
               cost[k] = Math.max(1, codegen.cost(noise));
            }
         } catch (NoiseCodegen.Unsupported missing) {
            unsupported++;
            missingKinds.merge(missing.kind, 1, Integer::sum);
            continue;
         }

         supported.add(populator);
         costs.add(cost);
         maxCost = Math.max(maxCost, cost[0] + cost[1] + cost[2] + cost[3]);
      }

      List<Object> chosen = new ArrayList<>();
      List<Long> rowCosts = new ArrayList<>();
      List<String> names = new ArrayList<>();
      for (int i = 0; i < supported.size(); i++) {
         long[] cost = costs.get(i);
         if (!allPopulators && cost[0] + cost[1] + cost[2] + cost[3] < COST_SHARE * maxCost) {
            continue;
         }

         Object populator = supported.get(i);
         chosen.add(populator);
         for (int k = 0; k < 4; k++) {
            names.add(codegen.function(NoiseCodegen.invoke(populator, parts[k])));
            rowCosts.add(cost[k]);
         }
      }

      if (chosen.isEmpty()) {
         WorldgenAccelerator.LOG.info("WGA GPU tile noise: none of the {} terrain populators found has a GPU version ({} unsupported; kinds without one: {})",
            found.size(), unsupported, missingKinds);
         return new Program(frequency, List.of(), new long[0], null);
      }

      String source = codegen.source(names);
      try {
         java.nio.file.Files.writeString(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-tile-noise.cu"), source,
            java.nio.charset.StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      long compileStart = System.nanoTime();
      // Compiled at the first terrain tile (FreeTerraForged 1.0.0010), so during the spawn area: on the world-loading
      // screen's list too (0.17.2; it had shown "GPU ready" while this still compiled).
      GpuPrepare.Task task = GpuPrepare.begin("tile noise");
      Gpu gpu;
      try {
         gpu = new Gpu(GpuService.context(), source, "tile_noise.cu");
         task.done();
      } catch (Throwable failure) {
         task.failed(failure.toString());
         throw failure;
      }
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU tile noise: %d of %d terrain populators on the GPU (%d without a GPU version: %s; the rest cheap), costliest %,d noise samples per column; compiled in %.0f ms",
         chosen.size(), found.size(), unsupported, missingKinds, maxCost, (System.nanoTime() - compileStart) / 1.0e6));
      return new Program(frequency, chosen, rowCosts.stream().mapToLong(Long::longValue).toArray(), gpu);
   }

   /** Every TerrainPopulator reachable from the terrain's populators (fields, arrays, lambdas' captured values); noise trees are not entered. */
   private static void walk(Object node, Set<Object> seen, List<Object> found, int depth) throws IllegalAccessException {
      if (node == null || depth > 40 || !seen.add(node)) {
         return;
      }

      Class<?> type = node.getClass();
      if (type.isArray()) {
         if (!type.getComponentType().isPrimitive()) {
            for (int i = 0; i < java.lang.reflect.Array.getLength(node); i++) {
               walk(java.lang.reflect.Array.get(node, i), seen, found, depth + 1);
            }
         }

         return;
      }

      String name = type.getName();
      if (name.endsWith(".TerrainPopulator")) {
         found.add(node);
         return;
      }

      boolean ftf = name.startsWith("etcodehome.freeterraforged.world.worldgen.cell") || type.isSynthetic() || name.contains("$$Lambda");
      if (!ftf) {
         return;
      }

      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
         for (Field field : c.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
               continue;
            }

            try {
               field.setAccessible(true);
            } catch (RuntimeException inaccessible) {
               continue;
            }

            walk(field.get(node), seen, found, depth + 1);
         }
      }
   }

   public static String summary() {
      long batches = Math.max(1, BATCHES.sum());
      return String.format(Locale.ROOT,
         "WGA GPU tile noise: %,d tiles, %,d batches (dry pass %.1f ms, GPU %.1f ms per batch; %,d populator columns made); populator columns from the GPU %,d, on the CPU %,d; checked %,d: %s",
         TILES.sum(), BATCHES.sum(), DRY_NANOS.sum() / 1.0e6 / batches, GPU_NANOS.sum() / 1.0e6 / batches, MADE.sum(), USED.sum(), CPU.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER");
   }

   /** For the memory report: tiles whose GPU table is still held. */
   public static String memorySummary() {
      return "tile noise tables " + TABLES.size();
   }
}
