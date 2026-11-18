package com.wgaccel.gpu;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.filter.FilterGrid;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step S6.2: FreeTerraForged's CliffRelaxation filter (21 Oct; MIT, ReTerraForged) on the GPU, with the erosion
 * filter's mode (config gpuErosionFilter): CHECK runs FreeTerraForged's own filter as well and compares every cell bit
 * for bit ("WGA GPU cliff relaxation check"); ON uses the GPU's heights (committed to the cells as FreeTerraForged does).
 * Floats only, so no cell is left to the CPU. Its constants are checked once against the ones ported; any difference
 * or GPU failure turns it off and FreeTerraForged's own filter runs.
 */
public final class CliffRelaxationGpu {
   private static volatile boolean disabled;
   private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> KNOWN = new java.util.concurrent.ConcurrentHashMap<>();
   private static final AtomicInteger tiles = new AtomicInteger();
   private static final AtomicLong cells = new AtomicLong();
   private static final AtomicLong different = new AtomicLong();
   private static final AtomicLong gpuNanos = new AtomicLong();
   private static final AtomicLong ftfNanos = new AtomicLong();
   private static final AtomicInteger logged = new AtomicInteger();
   /** The GPU thread's buffers (only that thread touches them). */
   private static final ThreadLocal<ErosionKernels.Buffers> DEVICE = new ThreadLocal<>();

   private CliffRelaxationGpu() {
   }

   /** Called instead of FreeTerraForged's CliffRelaxation.apply; original runs its own filter. */
   public static void applyTile(Object relaxation, Cell[] backing, int size, int blockX, int blockZ, Runnable original) {
      if (!enabled()) { original.run(); return; }
      apply(relaxation, backing, size, blockX, blockZ, 1.0F, false, original);
   }

   /** Current FTF region/chunk grids have world-space cell spacing, unlike the removed tile Filterable. */
   public static void applyGrid(Object relaxation, FilterGrid map, Runnable original) {
      if (!enabled()) { original.run(); return; }
      float spacing = map.getCellSpacing() > 0.0F ? map.getCellSpacing() : 1.0F;
      apply(relaxation, map.getBacking(), map.getTotalSize(), map.getBlockX(), map.getBlockZ(), spacing, true, original);
   }

   public static boolean enabled() {
      return Config.GPU_CLIFF_RELAXATION.get() && Config.GPU_EROSION_FILTER.get() != Config.GpuFilterMode.OFF
         && Config.GPU_MODE.get() != Config.GpuMode.OFF && !disabled && Lwjgl.PRESENT;
   }

   private static void apply(Object relaxation, Cell[] backing, int n, int blockX, int blockZ, float spacing, boolean spaced, Runnable original) {
      Config.GpuFilterMode mode = Config.GPU_EROSION_FILTER.get();
      if (!Config.GPU_CLIFF_RELAXATION.get() || mode == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF || disabled || !Lwjgl.PRESENT) {
         original.run();
         return;
      }

      if (backing.length != n * n || n < 2) {
         disable("the tile has " + backing.length + " cells, not " + n + " x " + n);
         original.run();
         return;
      }

      float[] packed;
      int seed;
      float worldHeight;
      try {
         if (!constants(relaxation.getClass())) {
            disable("this FreeTerraForged's CliffRelaxation has other constants than the GPU port");
            original.run();
            return;
         }

         seed = (Integer)field(relaxation, "seed");
         worldHeight = (Float)field(relaxation, "worldHeight");
         Object modifier = field(relaxation, "modifier");
         packed = new float[ErosionKernels.Buffers.PARAMS];
         packed[15] = (Float)field(modifier, "min");
         packed[16] = (Float)field(modifier, "max");
         packed[17] = (Float)field(modifier, "range");
      } catch (ReflectiveOperationException | RuntimeException failure) {
         disable("FreeTerraForged's CliffRelaxation could not be read: " + failure);
         original.run();
         return;
      }

      // As FreeTerraForged works them out.
      float minRelief = (3.0F * spacing) / worldHeight;
      float maxRelief = (8.0F * spacing) / worldHeight;
      float reliefSpan = Math.max(1e-5F, maxRelief - minRelief);
      float broadFreq = 1.0F / Math.max(1.0F, 28.0F);
      float detailFreq = 1.0F / Math.max(1.0F, 11.0F);
      int count = backing.length;
      float[][] input = new float[4][count];
      int[] masks = new int[count];
      for (int i = 0; i < count; i++) {
         Cell cell = backing[i];
         input[0][i] = cell.height; masks[i] = cell.erosionMask ? 1 : 0;
         input[1][i] = cell.terrain.erosionModifier(); input[2][i] = cell.terrainRegionEdge; input[3][i] = cell.riverMask;
      }
      long start = System.nanoTime();
      float[][] result;
      try {
         // The queued job owns native host buffers. A timed-out caller may run the CPU filter, but cannot free
         // memory that a delayed GPU job still references. Only immutable heap snapshots cross that boundary.
         result = GpuService.call(gpu -> run(gpu, n, seed, blockX, blockZ, broadFreq, detailFreq, minRelief, reliefSpan, spacing, spaced, input, masks, packed));
      } catch (Exception | LinkageError failure) {
         disable(failure.toString()); original.run(); return;
      }
      float[] out = result[0], mods = result[1];
      gpuNanos.addAndGet(System.nanoTime() - start);
      if (mode == Config.GpuFilterMode.CHECK || tiles.get() < 8 || (tiles.get() & 63) == 0) {
         check(backing, n, blockX, blockZ, out, mods, original);
      } else {
         for (int i = 0; i < count; i++) {
            float h = out[i];
            if (mods[i] == 0.0F || !Float.isFinite(h)) continue;
            Cell cell = backing[i]; cell.heightErosion += h - cell.height; cell.height = h;
         }
         cells.addAndGet(count);
         int done = tiles.incrementAndGet();
         if (done == 1 || done % 128 == 0) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU cliff relaxation: %,d tiles on the GPU (%.2f ms each with copies), %,d cells", done,
               gpuNanos.get() / 1.0e6 / done, cells.get()));
         }
      }
   }

   private static float[][] run(Gpu gpu, int n, int seed, int blockX, int blockZ, float broadFreq, float detailFreq, float minRelief,
      float reliefSpan, float spacing, boolean spaced, float[][] input, int[] masks, float[] packed) {
      int count = masks.length;
      // Allocate and dispose on the GPU worker, including exceptional/late jobs.
      java.util.List<java.nio.Buffer> host = new java.util.ArrayList<>();
      try {
         FloatBuffer height = MemoryUtil.memAllocFloat(count); host.add(height); height.put(0, input[0]);
         IntBuffer mask = MemoryUtil.memAllocInt(count); host.add(mask); mask.put(0, masks);
         FloatBuffer emod = MemoryUtil.memAllocFloat(count); host.add(emod); emod.put(0, input[1]);
         FloatBuffer edge = MemoryUtil.memAllocFloat(count); host.add(edge); edge.put(0, input[2]);
         FloatBuffer river = MemoryUtil.memAllocFloat(count); host.add(river); river.put(0, input[3]);
         FloatBuffer out = MemoryUtil.memAllocFloat(count); host.add(out);
         FloatBuffer mods = MemoryUtil.memAllocFloat(count); host.add(mods);
         FloatBuffer params = MemoryUtil.memAllocFloat(packed.length); host.add(params); params.put(0, packed);
         ErosionKernels.Buffers d = DEVICE.get();
         if (d == null || d.cells < count) { d = new ErosionKernels.Buffers(gpu, count); DEVICE.set(d); }
         gpu.copyIn(d.params, params); gpu.copyIn(d.height, height); gpu.copyIn(d.mask, mask);
         gpu.copyIn(d.modifier, emod); gpu.copyIn(d.edge, edge); gpu.copyIn(d.river, river);
         long relaxed = ErosionKernels.runCliff(gpu, d, n, seed, blockX, blockZ, broadFreq, detailFreq, minRelief, reliefSpan, spacing, spaced);
         gpu.download(relaxed, out); gpu.download(d.mods, mods);
         float[][] result = new float[2][count]; out.get(0, result[0]); mods.get(0, result[1]);
         return result;
      } finally { for (java.nio.Buffer buffer : host) MemoryUtil.memFree(buffer); }
   }

   /** CHECK: FreeTerraForged's own filter runs on the cells; the GPU's commit is made on copies and compared. */
   private static void check(Cell[] backing, int n, int blockX, int blockZ, float[] out, float[] mods, Runnable original) {
      int count = backing.length;
      float[] height = new float[count];
      float[] heightErosion = new float[count];
      for (int i = 0; i < count; i++) {
         height[i] = backing[i].height;
         heightErosion[i] = backing[i].heightErosion;
         float h = out[i];
         if (mods[i] != 0.0F && Float.isFinite(h)) {
            heightErosion[i] += h - height[i];
            height[i] = h;
         }
      }

      long start = System.nanoTime();
      original.run();
      ftfNanos.addAndGet(System.nanoTime() - start);
      int tileDifferent = 0;
      for (int i = 0; i < count; i++) {
         Cell cell = backing[i];
         if (!same(height[i], cell.height) || !same(heightErosion[i], cell.heightErosion)) {
            tileDifferent++;
            if (logged.incrementAndGet() <= 12) {
               WorldgenAccelerator.LOG.warn(String.format(Locale.ROOT, "WGA GPU cliff relaxation check: tile block (%d, %d) cell (%d, %d): height %s / FTF %s, heightErosion %s / %s",
                  blockX, blockZ, i % n, i / n, height[i], cell.height, heightErosion[i], cell.heightErosion));
            }
         }
      }

      cells.addAndGet(count);
      different.addAndGet(tileDifferent);
      if (tileDifferent != 0) disable("a checked grid differs in " + tileDifferent + " cells");
      int done = tiles.incrementAndGet();
      if (done == 1 || done % 32 == 0) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU cliff relaxation check: %,d tiles, %,d cells: %s; GPU %.2f ms per tile (with copies), FreeTerraForged %.1f ms",
            done, cells.get(), different.get() == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d cells DIFFER", different.get()), gpuNanos.get() / 1.0e6 / done,
            ftfNanos.get() / 1.0e6 / done));
      }
   }

   /** The filter's constants the port has written in. */
   private static boolean constants(Class<?> type) throws ReflectiveOperationException {
      Boolean result = KNOWN.get(type);
      if (result == null) {
         Object[][] expected = {{"CLIFF_PASSES", 3}, {"CLIFF_RELAX_RATE", 0.4F}, {"MAX_RATE", 0.5F}, {"MIN_CLIFF_BLOCKS", 3.0F}, {"MAX_CLIFF_BLOCKS", 8.0F},
            {"VARIATION_WAVELENGTH_BLOCKS", 28.0F}, {"DETAIL_WAVELENGTH_BLOCKS", 11.0F}, {"DETAIL_WEIGHT", 0.35F}, {"STRENGTH_VARIATION", 0.5F},
            {"THRESHOLD_VARIATION", 0.35F}};
         boolean all = true;
         for (Object[] each : expected) {
            Field field = Names.declaredField(type, (String)each[0]);
            field.setAccessible(true);
            if (!each[1].equals(field.get(null))) {
               WorldgenAccelerator.LOG.info("WGA GPU cliff relaxation: {} is {}, the port has {}", each[0], field.get(null), each[1]);
               all = false;
            }
         }

         KNOWN.put(type, result = all);
      }

      return result;
   }

   private static void disable(String why) {
      if (!disabled) {
         disabled = true;
         com.wgaccel.WgaChat.warnOff("WGA GPU cliff relaxation off, FreeTerraForged's own filter runs instead: {}", why);
      }
   }

   private static boolean same(float a, float b) {
      return Float.isNaN(a) ? Float.isNaN(b) : Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = Names.declaredField(type, name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(Names.className(target.getClass()) + "." + name);
   }
}
