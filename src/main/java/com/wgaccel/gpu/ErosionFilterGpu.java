package com.wgaccel.gpu;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G1.6a: FreeTerraForged's tile erosion filter on the GPU (config gpuErosionFilter). CHECK: the GPU works
 * every tile out, then FreeTerraForged's own filter runs as usual and every cell is compared bit for bit (and so is
 * this class's CPU copy of the per-cell step, on every cell); logged as "WGA GPU erosion filter check". ON: the GPU's
 * result is used, and the few cells it was unsure of (a double exp, cos, sin or pow too near a float rounding
 * boundary) are redone here on the CPU with the same Java as FreeTerraForged's. Any GPU failure turns it off and
 * FreeTerraForged's filter runs instead.
 * <p>
 * FreeTerraForged 1.0.0013 (deleteTiles) runs the same 21 Oct filter on each chunk's 40 x 40 grid (cell.filter.Erosion),
 * with other constants and NoiseUtil's table sin / cos ({@link ErosionKernels.Variant#FTF_1_0_0013}): algorithm 3. Its
 * grids are many and small (the 23 Oct 2026 profile: 43,288 grids, 1.91 ms each on the CPU, 17% of all CPU time), so
 * every 64th one is also run by FreeTerraForged's own filter and compared; a difference turns the GPU off.
 */
public final class ErosionFilterGpu {
   private static final Map<Object, Params> PARAMS = Collections.synchronizedMap(new WeakHashMap<>());
   private static final float TAU = (float)(Math.PI * 2);
   private static volatile boolean disabled;
   private static final AtomicInteger tiles = new AtomicInteger();
   private static final AtomicLong cells = new AtomicLong();
   private static final AtomicLong unsureCells = new AtomicLong();
   private static final AtomicLong gpuDiffer = new AtomicLong();
   private static final AtomicLong cpuDiffer = new AtomicLong();
   private static final AtomicLong gpuNanos = new AtomicLong();
   private static final AtomicLong ftfNanos = new AtomicLong();
   private static final AtomicInteger logged = new AtomicInteger();
   /** The GPU thread's buffers (only that thread touches them). */
   private static final ThreadLocal<ErosionKernels.Buffers> DEVICE = new ThreadLocal<>();
   /** G6c: each generation thread's host buffers for 1.0.0013's grids (Job's eleven), reused grid after grid. */
   private static final ThreadLocal<java.nio.Buffer[]> POOL = new ThreadLocal<>();

   private ErosionFilterGpu() {
   }

   private static final AtomicInteger SAMPLED = new AtomicInteger();

   /**
    * Called instead of FreeTerraForged's Erosion.apply (a 1.0.0010 tile or a 1.0.0013 chunk grid: its cells, side and
    * corner block); original runs FreeTerraForged's own filter.
    */
   public static void apply(Object erosion, Cell[] backing, int n, int blockX, int blockZ, Runnable original) {
      Config.GpuFilterMode mode = Config.GPU_EROSION_FILTER.get();
      if (mode == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF || disabled || !Lwjgl.PRESENT) {
         original.run();
         return;
      }

      if (backing.length != n * n) {
         disable("the tile has " + backing.length + " cells, not " + n + " x " + n);
         original.run();
         return;
      }

      // S6 guard: the port is of the erosion filter with the cliff relaxation inside it (FTF 1.0.0010 of 13 Oct). The
      // newer FTF (21 Oct) rewrote it (window max/min, box blur, envelope; CliffRelaxation now a filter of its own) with
      // the same field names, so reading the fields alone would run the old algorithm on it: a different world.
      int algorithm = algorithm(erosion);
      if (algorithm == 0) {
         disable("this FreeTerraForged's erosion filter is not one the GPU port knows (neither the 13 Oct one with relaxCliffs nor the 21 Oct one with the same constants); its own filter runs");
         original.run();
         return;
      }

      Params params;
      try {
         params = params(erosion, algorithm);
      } catch (ReflectiveOperationException | RuntimeException failure) {
         disable("FreeTerraForged's erosion filter could not be read: " + failure);
         original.run();
         return;
      }

      // 1.0.0013's grids: every 64th also FreeTerraForged's own and compared (a 1.0.0010 tile was checked offline).
      boolean sampled = algorithm == 3 && SAMPLED.getAndIncrement() % 64 == 0;
      boolean check = mode == Config.GpuFilterMode.CHECK || sampled;
      Job job = new Job(params, backing, n, blockX, blockZ, check, algorithm);
      try {
         long start = System.nanoTime();
         try {
            if (algorithm == 3) {
               // 1.0.0013's chunk grids: batched with the others waiting (G6c).
               ErosionGridBatch.run(job);
            } else {
               GpuService.call(job::run);
            }
         } catch (Exception | LinkageError failure) {
            if (job.pooled) {
               // A batch that did not finish may still write into the thread's buffers later: this thread takes new ones.
               POOL.remove();
            }

            disable(failure.toString());
            original.run();
            return;
         }

         gpuNanos.addAndGet(System.nanoTime() - start);
         if (check) {
            int different = check(job, backing, original);
            if (sampled && different > 0) {
               disable(different + " cells of a checked chunk grid differ from FreeTerraForged's own filter");
            }
         } else {
            use(job, backing);
         }
      } finally {
         job.free();
      }
   }

   private static volatile Integer algorithm;

   /**
    * Which FreeTerraForged erosion filter this is: 1 the 13 Oct one (relaxCliffs inside), 2 the 21 Oct rewrite (window
    * max / min and box blur, CliffRelaxation a filter of its own) whose constants are all the ones ported (S6), 0 any
    * other (the GPU then stays off: the same field names could hide a different algorithm).
    */
   private static int algorithm(Object erosion) {
      Integer known = algorithm;
      if (known == null) {
         java.util.Set<String> methods = new java.util.HashSet<>();
         for (java.lang.reflect.Method method : erosion.getClass().getDeclaredMethods()) {
            methods.add(method.getName());
         }

         int found = 0;
         boolean grid = erosion.getClass().getName().endsWith(".cell.filter.Erosion");
         if (methods.contains("relaxCliffs")) {
            found = 1;
         } else if (methods.containsAll(java.util.List.of("windowMax", "windowMin", "boxBlur", "minmod", "samplePhacelleNoise"))) {
            if (!grid && constants(erosion.getClass(), ErosionKernels.Variant.OCT_21, 96.0F)) {
               found = 2;
            } else if (grid && constants(erosion.getClass(), ErosionKernels.Variant.FTF_1_0_0013, 300.0F) && sinTable()) {
               found = 3;
            }
         }

         algorithm = known = found;
         WorldgenAccelerator.LOG.info("WGA GPU erosion filter: FreeTerraForged's filter is {}", found == 1 ? "the 13 Oct one (relaxCliffs inside)"
            : found == 2 ? "the 21 Oct rewrite (ported, S6)" : found == 3 ? "1.0.0013's on chunk grids (the 21 Oct one with its constants and table sin / cos)"
            : "unknown (the GPU stays off)");
      }

      return known;
   }

   /** The filter's constants: the variant's (gate, depth cap, margin) and wavelength, the rest written into the port. */
   private static boolean constants(Class<?> type, ErosionKernels.Variant v, float wavelength) {
      Object[][] expected = {{"BASE_WAVELENGTH_BLOCKS", wavelength}, {"FLAT_SLOPE_START", v.slopeStart()}, {"FLAT_SLOPE_FULL", v.slopeFull()}, {"GATE_SMOOTH_RADIUS", 2},
         {"ENVELOPE_RADIUS", 3}, {"ENVELOPE_MARGIN_BLOCKS", v.margin()}, {"MAX_DEPTH_PER_SLOPE", v.depthPerSlope()}, {"MAX_SLOPE_FOR_CAP", 3.0F}, {"RIDGE_SLOPE_SCALE", 0.01F},
         {"RIDGE_MASK_MAX", 0.98F}, {"HEIGHT_MIDPOINT", 0.5F}, {"NON_NEGATIVE_NOISE", false}, {"ROUNDING_DECAY_PER_OCTAVE", 0.001F},
         {"MIN_SEDIMENT_MODIFIER", 0.2F}, {"TAU", 6.2831855F}};
      try {
         for (Object[] each : expected) {
            Field field = type.getDeclaredField((String)each[0]);
            field.setAccessible(true);
            if (!each[1].equals(field.get(null))) {
               WorldgenAccelerator.LOG.info("WGA GPU erosion filter: {} is {}, the port has {}", each[0], field.get(null), each[1]);
               return false;
            }
         }

         return true;
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.info("WGA GPU erosion filter: could not read its constants: {}", failure.toString());
         return false;
      }
   }

   /** FreeTerraForged's NoiseUtil.SIN and radToIndex are the ones the module was made with ({@link ErosionKernels#SIN}). */
   private static boolean sinTable() {
      try {
         Field sin = NoiseUtil.class.getDeclaredField("SIN");
         Field radToIndex = NoiseUtil.class.getDeclaredField("radToIndex");
         sin.setAccessible(true);
         radToIndex.setAccessible(true);
         float[] theirs = (float[])sin.get(null);
         boolean same = java.util.Arrays.equals(theirs, ErosionKernels.SIN)
            && Float.floatToRawIntBits((Float)radToIndex.get(null)) == Float.floatToRawIntBits(ErosionKernels.RAD_TO_INDEX);
         if (!same) {
            WorldgenAccelerator.LOG.info("WGA GPU erosion filter: FreeTerraForged's sine table is not the one the GPU has");
         }

         return same;
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.info("WGA GPU erosion filter: could not read FreeTerraForged's sine table: {}", failure.toString());
         return false;
      }
   }

   /** The per-cell step of the job's algorithm on the CPU (unsure cells and CHECK). */
   private static void step(Job job, Cell cell, int x, int z, float[][] grids, float[] phacelle) {
      if (job.algorithm >= 2) {
         cpuCellV2(job.params, cell, x, z, job.n, job.blockX, job.blockZ, grids[4], grids[0], grids[1], grids[2], grids[3], phacelle);
      } else {
         cpuCell(job.params, cell, x, z, job.n, job.blockX, job.blockZ, grids[0], grids[1], grids[2], grids[3], phacelle);
      }
   }

   private static void disable(String why) {
      if (!disabled) {
         disabled = true;
         com.wgaccel.WgaChat.warnOff("WGA GPU erosion filter off, FreeTerraForged's own filter runs instead: {}", why);
      }
   }

   /** ON: the GPU's cells, the unsure ones redone here. */
   private static void use(Job job, Cell[] backing) {
      int count = backing.length;
      int unsure = 0;
      for (int i = 0; i < count; i++) {
         Cell cell = backing[i];
         cell.preErosionHeight = job.height.get(i);
         if (job.unsure.get(i) == 0) {
            cell.height = job.outHeight.get(i);
            cell.heightErosion = job.outHeightErosion.get(i);
            cell.sediment = job.outSediment.get(i);
         } else {
            unsure++;
         }
      }

      if (unsure > 0) {
         float[][] grids = job.grids();
         float[] phacelle = new float[4];
         for (int i = 0; i < count; i++) {
            if (job.unsure.get(i) != 0) {
               Cell cell = backing[i];
               cell.height = job.preHeight.get(i);
               cell.heightErosion = job.preHeightErosion.get(i);
               cell.sediment = job.preSediment.get(i);
               step(job, cell, i % job.n, i / job.n, grids, phacelle);
               finish(job.params, cell, job.height.get(i));
            }
         }
      }

      cells.addAndGet(count);
      unsureCells.addAndGet(unsure);
      int done = tiles.incrementAndGet();
      if (done == 1 || done % 128 == 0) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU erosion filter: %,d tiles on the GPU (%.2f ms each with copies), %,d cells, %,d unsure cells redone on the CPU%s",
            done, gpuNanos.get() / 1.0e6 / done, cells.get(), unsureCells.get(), job.algorithm == 3 ? ErosionGridBatch.summary() : ""));
      }
   }

   /** CHECK: FreeTerraForged's own filter runs, then the GPU's cells and this class's CPU step are compared with it. */
   private static int check(Job job, Cell[] backing, Runnable original) {
      int count = backing.length;
      Cell[] port = new Cell[count];
      for (int i = 0; i < count; i++) {
         port[i] = new Cell();
         port[i].copyFrom(backing[i]);
         port[i].height = job.preHeight.get(i);
         port[i].heightErosion = job.preHeightErosion.get(i);
         port[i].sediment = job.preSediment.get(i);
      }

      long start = System.nanoTime();
      original.run();
      ftfNanos.addAndGet(System.nanoTime() - start);
      float[][] grids = job.grids();
      float[] phacelle = new float[4];
      int unsure = 0;
      int gpuDifferent = 0;
      int cpuDifferent = 0;
      for (int i = 0; i < count; i++) {
         Cell cell = backing[i];
         int x = i % job.n;
         int z = i / job.n;
         if (job.unsure.get(i) != 0) {
            unsure++;
         } else if (!same(job.outHeight.get(i), cell.height) || !same(job.outHeightErosion.get(i), cell.heightErosion)
            || !same(job.outSediment.get(i), cell.sediment) || !same(job.height.get(i), cell.preErosionHeight)) {
            gpuDifferent++;
            report("GPU", job, x, z, job.outHeight.get(i), job.outHeightErosion.get(i), job.outSediment.get(i), cell);
         }

         Cell mine = port[i];
         step(job, mine, x, z, grids, phacelle);
         finish(job.params, mine, job.height.get(i));
         if (!same(mine.height, cell.height) || !same(mine.heightErosion, cell.heightErosion) || !same(mine.sediment, cell.sediment)) {
            cpuDifferent++;
            report("CPU step", job, x, z, mine.height, mine.heightErosion, mine.sediment, cell);
         }
      }

      cells.addAndGet(count);
      unsureCells.addAndGet(unsure);
      gpuDiffer.addAndGet(gpuDifferent);
      cpuDiffer.addAndGet(cpuDifferent);
      int done = tiles.incrementAndGet();
      if (done == 1 || done % 32 == 0) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU erosion filter check: %,d tiles, %,d cells: GPU %s, CPU step %s; %,d unsure cells (left to the CPU step); GPU %.2f ms per tile (with copies), FreeTerraForged %.1f ms",
            done, cells.get(), gpuDiffer.get() == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d cells DIFFER", gpuDiffer.get()),
            cpuDiffer.get() == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d cells DIFFER", cpuDiffer.get()), unsureCells.get(),
            gpuNanos.get() / 1.0e6 / done, ftfNanos.get() / 1.0e6 / done));
      }

      return gpuDifferent + cpuDifferent;
   }

   private static void report(String what, Job job, int x, int z, float height, float heightErosion, float sediment, Cell expected) {
      if (logged.incrementAndGet() <= 12) {
         WorldgenAccelerator.LOG.warn(String.format(Locale.ROOT, "WGA GPU erosion filter check: %s differs at tile block (%d, %d) cell (%d, %d): height %s / FTF %s, heightErosion %s / %s, sediment %s / %s",
            what, job.blockX, job.blockZ, x, z, height, expected.height, heightErosion, expected.heightErosion, sediment, expected.sediment));
      }
   }

   private static boolean same(float a, float b) {
      return Float.isNaN(a) ? Float.isNaN(b) : Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
   }

   /** A tile's cells packed for the GPU and the results. Host buffers off-heap; freed by {@link #free}. */
   static final class Job {
      final Params params;
      final int n;
      final int count;
      final int blockX;
      final int blockZ;
      final boolean all;
      final int algorithm;
      final FloatBuffer height;
      final IntBuffer mask;
      final FloatBuffer modifier;
      final FloatBuffer edge;
      final FloatBuffer river;
      final FloatBuffer heightErosion;
      final FloatBuffer sediment;
      final FloatBuffer outHeight;
      final FloatBuffer outHeightErosion;
      final FloatBuffer outSediment;
      final IntBuffer unsure;
      FloatBuffer preHeight;
      FloatBuffer preHeightErosion;
      FloatBuffer preSediment;
      FloatBuffer relaxed;
      FloatBuffer slopeX;
      FloatBuffer slopeZ;
      FloatBuffer zone;
      /** G6c: done once a batch has put the results in (or failed). */
      final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
      /** G6c: the grid has an unsure cell or is checked, so the batch hands it the grids for the CPU step. */
      boolean needsGrids;
      /** G6c: the eleven host buffers are the calling thread's own, kept for its next grid ({@link #POOL}); not freed. */
      final boolean pooled;

      Job(Params params, Cell[] backing, int n, int blockX, int blockZ, boolean all, int algorithm) {
         this.params = params;
         this.algorithm = algorithm;
         this.n = n;
         this.count = backing.length;
         this.blockX = blockX;
         this.blockZ = blockZ;
         this.all = all;
         // 1.0.0013's grids (~700 a second): the thread's own buffers, as a thread has one grid at a time (G6c).
         this.pooled = algorithm == 3;
         java.nio.Buffer[] own = this.pooled ? pool(this.count) : null;
         this.height = own != null ? (FloatBuffer)own[0] : MemoryUtil.memAllocFloat(this.count);
         this.mask = own != null ? (IntBuffer)own[1] : MemoryUtil.memAllocInt(this.count);
         this.modifier = own != null ? (FloatBuffer)own[2] : MemoryUtil.memAllocFloat(this.count);
         this.edge = own != null ? (FloatBuffer)own[3] : MemoryUtil.memAllocFloat(this.count);
         this.river = own != null ? (FloatBuffer)own[4] : MemoryUtil.memAllocFloat(this.count);
         this.heightErosion = own != null ? (FloatBuffer)own[5] : MemoryUtil.memAllocFloat(this.count);
         this.sediment = own != null ? (FloatBuffer)own[6] : MemoryUtil.memAllocFloat(this.count);
         this.outHeight = own != null ? (FloatBuffer)own[7] : MemoryUtil.memAllocFloat(this.count);
         this.outHeightErosion = own != null ? (FloatBuffer)own[8] : MemoryUtil.memAllocFloat(this.count);
         this.outSediment = own != null ? (FloatBuffer)own[9] : MemoryUtil.memAllocFloat(this.count);
         this.unsure = own != null ? (IntBuffer)own[10] : MemoryUtil.memAllocInt(this.count);
         for (int i = 0; i < this.count; i++) {
            Cell cell = backing[i];
            this.height.put(i, cell.height);
            this.mask.put(i, cell.erosionMask ? 1 : 0);
            this.modifier.put(i, cell.terrain.erosionModifier());
            this.edge.put(i, cell.terrainRegionEdge);
            this.river.put(i, cell.riverMask);
            this.heightErosion.put(i, cell.heightErosion);
            this.sediment.put(i, cell.sediment);
         }
      }

      /** On the GPU thread: uploads, runs the filter's passes, downloads. */
      Void run(Gpu gpu) {
         ErosionKernels.Buffers d = DEVICE.get();
         if (d == null || d.cells < this.count) {
            d = new ErosionKernels.Buffers(gpu, this.count);
            DEVICE.set(d);
         }

         gpu.copyIn(d.params, this.params.packed);
         gpu.copyIn(d.height, this.height);
         gpu.copyIn(d.mask, this.mask);
         gpu.copyIn(d.modifier, this.modifier);
         gpu.copyIn(d.edge, this.edge);
         gpu.copyIn(d.river, this.river);
         gpu.copyIn(d.heightErosion, this.heightErosion);
         gpu.copyIn(d.sediment, this.sediment);
         long relaxed = this.algorithm == 3 ? ErosionKernels.runV2(gpu, d, this.n, this.params.seed, this.params.octaves, this.blockX, this.blockZ,
               ErosionKernels.Variant.FTF_1_0_0013)
            : this.algorithm == 2 ? ErosionKernels.runV2(gpu, d, this.n, this.params.seed, this.params.octaves, this.blockX, this.blockZ)
            : ErosionKernels.run(gpu, d, this.n, this.params.seed, this.params.octaves, this.blockX, this.blockZ);
         gpu.download(d.outHeight, this.outHeight);
         gpu.download(d.outHeightErosion, this.outHeightErosion);
         gpu.download(d.outSediment, this.outSediment);
         gpu.download(d.unsure, this.unsure);
         boolean any = this.all;
         for (int i = 0; i < this.count && !any; i++) {
            any = this.unsure.get(i) != 0;
         }

         if (any) {
            this.preHeight = download(gpu, d.cellHeight);
            this.preHeightErosion = download(gpu, d.heightErosion);
            this.preSediment = download(gpu, d.sediment);
            this.relaxed = download(gpu, relaxed);
            this.slopeX = download(gpu, d.slopeX);
            this.slopeZ = download(gpu, d.slopeZ);
            this.zone = download(gpu, d.zone);
         }

         return null;
      }

      private FloatBuffer download(Gpu gpu, long from) {
         FloatBuffer into = MemoryUtil.memAllocFloat(this.count);
         gpu.download(from, into);
         return into;
      }

      /**
       * The grids for the CPU step: the relaxed heights (21 Oct: the envelope), slopes, cliff zone (21 Oct: the smoothed
       * gate slope), and the heights the filter started from.
       */
      float[][] grids() {
         float[][] grids = new float[5][this.count];
         this.relaxed.get(0, grids[0]);
         this.slopeX.get(0, grids[1]);
         this.slopeZ.get(0, grids[2]);
         this.zone.get(0, grids[3]);
         this.height.get(0, grids[4]);
         return grids;
      }

      void free() {
         java.nio.Buffer[] own = {this.height, this.mask, this.modifier, this.edge, this.river, this.heightErosion, this.sediment, this.outHeight,
            this.outHeightErosion, this.outSediment, this.unsure};
         for (java.nio.Buffer buffer : this.pooled ? new java.nio.Buffer[0] : own) {
            MemoryUtil.memFree(buffer);
         }

         for (java.nio.Buffer buffer : new java.nio.Buffer[] {this.preHeight, this.preHeightErosion, this.preSediment, this.relaxed, this.slopeX, this.slopeZ,
            this.zone}) {
            if (buffer != null) {
               MemoryUtil.memFree(buffer);
            }
         }
      }

      /** The calling thread's eleven buffers of count cells (Job's order), made the first time and on a size change. */
      private static java.nio.Buffer[] pool(int count) {
         java.nio.Buffer[] own = POOL.get();
         if (own == null || own[0].capacity() != count) {
            if (own != null) {
               for (java.nio.Buffer buffer : own) {
                  MemoryUtil.memFree(buffer);
               }
            }

            own = new java.nio.Buffer[11];
            for (int i = 0; i < 11; i++) {
               own[i] = i == 1 || i == 10 ? MemoryUtil.memAllocInt(count) : MemoryUtil.memAllocFloat(count);
            }

            POOL.set(own);
         }

         return own;
      }
   }

   /** One FreeTerraForged erosion filter's settings, read once (its fields are private). */
   static final class Params {
      static final int PACKED = ErosionKernels.Buffers.PARAMS;
      final int seed;
      final int octaves;
      /** FreeTerraForged's Modifier (tile.filter in 1.0.0010, cell.filter in 1.0.0013) and its modify(Cell, float). */
      final Object modifier;
      final java.lang.reflect.Method modify;
      final ErosionKernels.Variant variant;
      final float scaleBlocks;
      final float worldHeight;
      final float strength;
      final float gullyWeight;
      final float detail;
      final float lacunarity;
      final float gain;
      final float phacelleScale;
      final float phacelleOffset;
      final float phacelleNormalization;
      final float roundingMin;
      final float roundingMax;
      final float slopeOnsetBase;
      final float slopeOnsetRidge;
      final float slopeOnsetOctave;
      final float water;
      final float ground;
      final FloatBuffer packed;

      Params(Object erosion, int algorithm) throws ReflectiveOperationException {
         this.seed = (Integer)field(erosion, "seed");
         this.octaves = (Integer)field(erosion, "octaves");
         this.modifier = field(erosion, "modifier");
         // 1.0.0013's Modifier.range is an anonymous class and modify a default method of the Modifier interface: the
         // public methods include it (1.0.0010's is declared on the class, also public).
         java.lang.reflect.Method modify = null;
         for (java.lang.reflect.Method each : this.modifier.getClass().getMethods()) {
            if (each.getName().equals("modify") && each.getParameterCount() == 2 && each.getParameterTypes()[0] == Cell.class
               && each.getParameterTypes()[1] == float.class) {
               modify = each;
            }
         }

         if (modify == null) {
            throw new NoSuchMethodException(this.modifier.getClass().getName() + ".modify(Cell, float)");
         }

         modify.setAccessible(true);
         this.modify = modify;
         this.variant = algorithm == 3 ? ErosionKernels.Variant.FTF_1_0_0013 : ErosionKernels.Variant.OCT_21;
         this.scaleBlocks = (Float)field(erosion, "scaleBlocks");
         this.worldHeight = (Float)field(erosion, "worldHeight");
         this.strength = (Float)field(erosion, "strength");
         this.gullyWeight = (Float)field(erosion, "gullyWeight");
         this.detail = (Float)field(erosion, "detail");
         this.lacunarity = (Float)field(erosion, "lacunarity");
         this.gain = (Float)field(erosion, "gain");
         Object s = field(erosion, "s");
         this.phacelleScale = (Float)field(s, "phacelleScale");
         this.phacelleOffset = (Float)field(s, "phacelleOffset");
         this.phacelleNormalization = (Float)field(s, "phacelleNormalization");
         this.roundingMin = (Float)field(s, "roundingMin");
         this.roundingMax = (Float)field(s, "roundingMax");
         this.slopeOnsetBase = (Float)field(s, "slopeOnsetBase");
         this.slopeOnsetRidge = (Float)field(s, "slopeOnsetRidge");
         this.slopeOnsetOctave = (Float)field(s, "slopeOnsetOctave");
         Levels levels = (Levels)field(erosion, "levels");
         this.water = levels.water;
         this.ground = levels.ground;
         float minRelief = 3.0F / this.worldHeight;
         float maxRelief = 8.0F / this.worldHeight;
         float invRange = 1.0F / Math.max(1.0E-5F, maxRelief - minRelief);
         float[] p = {this.scaleBlocks, this.worldHeight, this.strength, this.gullyWeight, this.detail, this.lacunarity, this.gain, this.phacelleScale,
            this.phacelleOffset, this.phacelleNormalization, this.roundingMin, this.roundingMax, this.slopeOnsetBase, this.slopeOnsetRidge, this.slopeOnsetOctave,
            (Float)field(this.modifier, "min"), (Float)field(this.modifier, "max"), (Float)field(this.modifier, "range"), this.water, this.ground, minRelief,
            invRange};
         this.packed = MemoryUtil.memAllocFloat(PACKED).put(0, p);
      }
   }

   private static Params params(Object erosion, int algorithm) throws ReflectiveOperationException {
      Params known = PARAMS.get(erosion);
      if (known == null) {
         known = new Params(erosion, algorithm);
         PARAMS.put(erosion, known);
      }

      return known;
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(target.getClass().getName() + "." + name);
   }

   /** A cell that was land before the filter and is water after it goes to the ground level (FreeTerraForged's last pass). */
   private static void finish(Params p, Cell cell, float preErosionHeight) {
      if (preErosionHeight > p.water && cell.height <= p.water) {
         cell.heightErosion = cell.heightErosion + (p.ground - cell.height);
         cell.height = p.ground;
      }
   }

   /**
    * FreeTerraForged's per-cell erosion step (the body of its main loop), the same Java, for a cell holding its state
    * after the cliff relaxation; heights are the relaxed heights.
    */
   static void cpuCell(Params p, Cell cell, int x, int z, int n, int worldBlockX, int worldBlockZ, float[] heights, float[] slopeX, float[] slopeZ,
         float[] cliffZone, float[] phacelleOut) {
      int idx = z * n + x;
      int last = n - 1;
      float wh = p.worldHeight;
      if (cell.erosionMask) {
         return;
      }

      float baseHeight = heights[idx];
      if (!Float.isFinite(baseHeight)) {
         return;
      }

      float curSlopeX = slopeX[idx];
      float curSlopeZ = slopeZ[idx];
      float slopeLen = (float)Math.sqrt((double)(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1.0E-10F));
      float slopeT = NoiseUtil.clamp((slopeLen - 0.15F) / Math.max(1.0E-5F, 0.45000002F), 0.0F, 1.0F);
      float flatGate = slopeT * slopeT * (3.0F - 2.0F * slopeT);
      if (flatGate <= 0.0F) {
         return;
      }

      float erosionFactor = modify(p, cell);
      if (erosionFactor == 0.0F) {
         return;
      }

      float worldX = (float)(worldBlockX + x);
      float worldZ = (float)(worldBlockZ + z);
      float zone = cliffZone[idx];
      float heightFade = NoiseUtil.clamp((baseHeight - 0.5F) * 2.0F, -1.0F, 1.0F);
      float currentStrength = p.strength * p.scaleBlocks;
      float freq = 1.0F / (p.scaleBlocks * p.phacelleScale);
      float roundingForInput = lerp(p.roundingMin, p.roundingMax, NoiseUtil.clamp(heightFade + 0.5F, 0.0F, 1.0F));
      float combiMask = easeOut(smoothStart(slopeLen * p.slopeOnsetBase, roundingForInput * p.slopeOnsetBase));
      float ridgeMapCombiMask = easeOut(slopeLen * 0.01F * p.slopeOnsetRidge);
      float ridgeMapFadeTarget = heightFade;
      float gullySlopeX = curSlopeX;
      float gullySlopeZ = curSlopeZ;
      float accumulatedHeightDelta = 0.0F;
      float roundingMult = 1.0F;
      float fadeTarget = 0.0F;
      for (int octave = 0; octave < p.octaves; octave++) {
         float gullyLen = (float)Math.sqrt((double)(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ));
         float normGullyX = gullyLen > 1.0E-10F ? gullySlopeX / gullyLen : 0.0F;
         float normGullyZ = gullyLen > 1.0E-10F ? gullySlopeZ / gullyLen : 0.0F;
         samplePhacelleNoise(worldX * freq, worldZ * freq, normGullyX, normGullyZ, p.phacelleScale, p.phacelleOffset, p.phacelleNormalization, p.seed + octave,
            phacelleOut);
         float phacelleX = phacelleOut[0];
         float phacelleY = phacelleOut[1];
         float phacelleZ = phacelleOut[2];
         float phacelleW = phacelleOut[3];
         float sloping = Math.abs(phacelleY);
         float gradient = currentStrength * TAU * p.phacelleScale * freq;
         float gullySign = Math.signum(phacelleY);
         gullySlopeX += gullySign * phacelleZ * gradient * p.gullyWeight;
         gullySlopeZ += gullySign * phacelleW * gradient * p.gullyWeight;
         float noiseTerm = phacelleX * p.gullyWeight;
         float fadedGullyVal = lerp(fadeTarget, noiseTerm, combiMask);
         accumulatedHeightDelta += fadedGullyVal * currentStrength;
         fadeTarget = fadedGullyVal;
         float roundingOctave = lerp(p.roundingMin, p.roundingMax, NoiseUtil.clamp(phacelleX + 0.5F, 0.0F, 1.0F)) * roundingMult;
         float newMask = easeOut(smoothStart(sloping * p.slopeOnsetBase, roundingOctave * p.slopeOnsetBase));
         combiMask = powInv(combiMask, p.detail) * newMask;
         ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
         ridgeMapCombiMask *= easeOut(sloping * p.slopeOnsetOctave);
         currentStrength *= p.gain;
         freq *= p.lacunarity;
         roundingMult *= 0.001F;
      }

      if (!Float.isFinite(accumulatedHeightDelta)) {
         return;
      }

      float sedimentUpdate = ridgeMapFadeTarget * (1.0F - ridgeMapCombiMask) * (1.0F - zone);
      cell.sediment = NoiseUtil.clamp(cell.sediment + sedimentUpdate, -1.0F, 1.0F);
      float sedimentModifier = lerp(Math.max(0.2F, 1.0F - cell.sediment), 1.0F, zone);
      float capSlope = Math.min(slopeLen, 3.0F);
      float maxDepth = 0.5F * capSlope * p.scaleBlocks;
      float localLo = baseHeight;
      float localHi = baseHeight;
      if (zone > 0.0F) {
         int zMin = Math.max(0, z - 2);
         int zMax = Math.min(last, z + 2);
         int xMin = Math.max(0, x - 2);
         int xMax = Math.min(last, x + 2);
         for (int wz = zMin; wz <= zMax; wz++) {
            int wRow = wz * n;
            for (int wx = xMin; wx <= xMax; wx++) {
               float h = heights[wRow + wx];
               if (h < localLo) {
                  localLo = h;
               }

               if (h > localHi) {
                  localHi = h;
               }
            }
         }

         float reliefBlocks = (localHi - localLo) * wh;
         float reliefDepth = Math.min(maxDepth, 0.35F * reliefBlocks);
         maxDepth = lerp(maxDepth, reliefDepth, zone);
      }

      float gatedBlocks = NoiseUtil.clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
      float deltaNormalized = gatedBlocks / p.worldHeight;
      float change = erosionFactor * deltaNormalized;
      if (zone > 0.0F) {
         float bounded = NoiseUtil.clamp(baseHeight + change, localLo, localHi) - baseHeight;
         change = lerp(change, bounded, zone);
      }

      cell.heightErosion += change;
      cell.height += change;
   }

   /**
    * S6: FreeTerraForged's 21 Oct per-cell erosion step (the body of its step-3 loop; FreeTerraForged, MIT, ReTerraForged),
    * the same Java, for a cell holding its state from before the filter: heights are the heights the filter started
    * from, envelope the window max of them (radius 3), gate the smoothed slope length.
    */
   static void cpuCellV2(Params p, Cell cell, int x, int z, int n, int worldBlockX, int worldBlockZ, float[] heights, float[] envelope, float[] slopeX,
         float[] slopeZ, float[] gate, float[] phacelleOut) {
      int idx = z * n + x;
      if (cell.erosionMask) {
         return;
      }

      float baseHeight = heights[idx];
      if (!Float.isFinite(baseHeight)) {
         return;
      }

      ErosionKernels.Variant v = p.variant;
      float slopeGateRange = v.range();
      float curSlopeX = slopeX[idx];
      float curSlopeZ = slopeZ[idx];
      float slopeLen = (float)Math.sqrt((double)(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1.0E-10F));
      float heightFade = NoiseUtil.clamp((baseHeight - 0.5F) * 2.0F, -1.0F, 1.0F);
      float ridgeMapCombiMask = Math.min(0.98F, easeOut(slopeLen * 0.01F * p.slopeOnsetRidge));
      float rawT = NoiseUtil.clamp((slopeLen - v.slopeStart()) / slopeGateRange, 0.0F, 1.0F);
      if (rawT <= 0.0F) {
         cell.sediment = NoiseUtil.clamp(cell.sediment + heightFade * (1.0F - ridgeMapCombiMask), -1.0F, 1.0F);
         return;
      }

      float smoothSlope = gate[idx];
      float ampT = NoiseUtil.clamp((smoothSlope - v.slopeStart()) / slopeGateRange, 0.0F, 1.0F);
      float flatGate = ampT * ampT * (3.0F - 2.0F * ampT);
      float erosionFactor = modify(p, cell);
      if (erosionFactor == 0.0F) {
         return;
      }

      float worldX = (float)(worldBlockX + x);
      float worldZ = (float)(worldBlockZ + z);
      float currentStrength = p.strength * p.scaleBlocks;
      float freq = 1.0F / (p.scaleBlocks * p.phacelleScale);
      float roundingForInput = lerp(p.roundingMin, p.roundingMax, NoiseUtil.clamp(heightFade + 0.5F, 0.0F, 1.0F));
      float combiMask = easeOut(smoothStart(slopeLen * p.slopeOnsetBase, roundingForInput * p.slopeOnsetBase));
      float ridgeMapFadeTarget = heightFade;
      float gullySlopeX = curSlopeX;
      float gullySlopeZ = curSlopeZ;
      float accumulatedHeightDelta = 0.0F;
      float roundingMult = 1.0F;
      float fadeTarget = 0.0F;
      for (int octave = 0; octave < p.octaves; octave++) {
         float gullyLen = (float)Math.sqrt((double)(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ));
         float normGullyX = gullyLen > 1.0E-10F ? gullySlopeX / gullyLen : 0.0F;
         float normGullyZ = gullyLen > 1.0E-10F ? gullySlopeZ / gullyLen : 0.0F;
         samplePhacelleNoise(worldX * freq, worldZ * freq, normGullyX, normGullyZ, p.phacelleScale, p.phacelleOffset, p.phacelleNormalization, p.seed + octave,
            phacelleOut, v.tableTrig());
         float phacelleX = phacelleOut[0];
         float phacelleY = phacelleOut[1];
         float phacelleZ = phacelleOut[2];
         float phacelleW = phacelleOut[3];
         float sloping = Math.abs(phacelleY);
         float gradient = currentStrength * TAU * p.phacelleScale * freq;
         float gullySign = Math.signum(phacelleY);
         gullySlopeX += gullySign * phacelleZ * gradient * p.gullyWeight;
         gullySlopeZ += gullySign * phacelleW * gradient * p.gullyWeight;
         float noiseTerm = phacelleX * p.gullyWeight;
         float fadedGullyVal = lerp(fadeTarget, noiseTerm, combiMask);
         accumulatedHeightDelta += fadedGullyVal * currentStrength;
         fadeTarget = fadedGullyVal;
         float roundingOctave = lerp(p.roundingMin, p.roundingMax, NoiseUtil.clamp(phacelleX + 0.5F, 0.0F, 1.0F)) * roundingMult;
         float newMask = easeOut(smoothStart(sloping * p.slopeOnsetBase, roundingOctave * p.slopeOnsetBase));
         combiMask = powInv(combiMask, p.detail) * newMask;
         ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
         ridgeMapCombiMask = Math.min(0.98F, ridgeMapCombiMask * easeOut(sloping * p.slopeOnsetOctave));
         currentStrength *= p.gain;
         freq *= p.lacunarity;
         roundingMult *= 0.001F;
      }

      if (!Float.isFinite(accumulatedHeightDelta)) {
         return;
      }

      cell.sediment = NoiseUtil.clamp(cell.sediment + ridgeMapFadeTarget * (1.0F - ridgeMapCombiMask), -1.0F, 1.0F);
      if (flatGate <= 0.0F) {
         return;
      }

      float sedimentModifier = Math.max(0.2F, 1.0F - cell.sediment);
      float capSlope = Math.min(smoothSlope, 3.0F);
      float maxDepth = v.depthPerSlope() * capSlope * p.scaleBlocks;
      float gatedBlocks = NoiseUtil.clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
      float limitedBlocks = gatedBlocks;
      if (limitedBlocks > 0.0F) {
         float headroom = (envelope[idx] - baseHeight) * p.worldHeight + v.margin();
         limitedBlocks = limitedBlocks * headroom / (limitedBlocks + headroom);
      }

      float deltaNormalized = limitedBlocks / p.worldHeight;
      float change = erosionFactor * deltaNormalized;
      cell.heightErosion += change;
      cell.height += change;
   }

   private static float modify(Params p, Cell cell) {
      try {
         return (Float)p.modify.invoke(p.modifier, cell, 1.0F);
      } catch (ReflectiveOperationException failure) {
         throw new IllegalStateException(failure);
      }
   }

   private static void samplePhacelleNoise(float px, float pz, float normDirX, float normDirZ, float freq, float offset, float normalization, int seed,
         float[] result) {
      samplePhacelleNoise(px, pz, normDirX, normDirZ, freq, offset, normalization, seed, result, false);
   }

   /** tableTrig: FreeTerraForged 1.0.0013's version (far cell points skipped, NoiseUtil's table sin / cos). */
   private static void samplePhacelleNoise(float px, float pz, float normDirX, float normDirZ, float freq, float offset, float normalization, int seed,
         float[] result, boolean tableTrig) {
      float sideDirX = -normDirZ * freq * TAU;
      float sideDirZ = normDirX * freq * TAU;
      offset *= TAU;
      int pIntX = (int)Math.floor((double)px);
      int pIntZ = (int)Math.floor((double)pz);
      float pFracX = px - (float)pIntX;
      float pFracZ = pz - (float)pIntZ;
      float phaseDirX = 0.0F;
      float phaseDirZ = 0.0F;
      float weightSum = 0.0F;
      for (int i = -1; i <= 2; i++) {
         for (int j = -1; j <= 2; j++) {
            int cellX = pIntX + i;
            int cellZ = pIntZ + j;
            float hashX = hash(cellX, cellZ, seed) * 0.5F;
            float hashZ = hash(cellX, cellZ, seed + 1013) * 0.5F;
            float vecX = pFracX - (float)i - hashX;
            float vecZ = pFracZ - (float)j - hashZ;
            float sqrDist = vecX * vecX + vecZ * vecZ;
            if (tableTrig) {
               if (sqrDist >= 2.25F) {
                  continue;
               }

               float weight = (float)Math.exp((double)(-sqrDist * 2.0F)) - 0.01111F;
               if (weight <= 0.0F) {
                  continue;
               }

               weightSum += weight;
               float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
               phaseDirX += NoiseUtil.cos(waveInput) * weight;
               phaseDirZ += NoiseUtil.sin(waveInput) * weight;
               continue;
            }

            float weight = Math.max(0.0F, (float)Math.exp((double)(-sqrDist * 2.0F)) - 0.01111F);
            weightSum += weight;
            float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
            phaseDirX += (float)Math.cos((double)waveInput) * weight;
            phaseDirZ += (float)Math.sin((double)waveInput) * weight;
         }
      }

      float invWeight = 1.0F / Math.max(weightSum, 1.0E-5F);
      float interpX = phaseDirX * invWeight;
      float interpZ = phaseDirZ * invWeight;
      float magnitude = (float)Math.sqrt((double)(interpX * interpX + interpZ * interpZ));
      magnitude = Math.max(1.0F - normalization, magnitude);
      result[0] = interpX / magnitude;
      result[1] = interpZ / magnitude;
      result[2] = sideDirX / (freq * TAU + 1.0E-5F);
      result[3] = sideDirZ / (freq * TAU + 1.0E-5F);
   }

   private static float hash(int x, int z, int seed) {
      int n = x * 374761393 + z * 668265263 + seed * 144677219;
      n = (n ^ n >> 13) * 1274126177;
      return (float)((n ^ n >> 16) & 2147483647) / 2.1474836E9F - 0.5F;
   }

   private static float lerp(float a, float b, float t) {
      return a + t * (b - a);
   }

   private static float easeOut(float t) {
      float v = 1.0F - NoiseUtil.clamp(t, 0.0F, 1.0F);
      return 1.0F - v * v;
   }

   private static float powInv(float t, float power) {
      return 1.0F - (float)Math.pow((double)(1.0F - NoiseUtil.clamp(t, 0.0F, 1.0F)), (double)power);
   }

   private static float smoothStart(float t, float smoothing) {
      return t >= smoothing ? t - 0.5F * smoothing : 0.5F * t * t / Math.max(smoothing, 1.0E-5F);
   }
}
