package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.TileNoiseGpu;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * T1's game side (config gpuTileNoise). A tile batch's thread holds its tile's table ({@link #batch}); the block column
 * FreeTerraForged's heightmap is working on is marked around Heightmap.applyTerrain. In the batch's dry pass
 * ({@link #record}) the GPU's populators only note that the column needs them and return placeholders; in the real pass
 * a populator asked there takes its four values from the table. ON: the GPU's values are used; every 64th use per
 * thread is instead worked out by FTF too and the cell's results compared bit for bit (a difference turns the GPU off).
 * CHECK: FTF always works it out and every use is compared. OFF: nothing is made on the GPU.
 */
public final class TileNoise {
   private static final int CHECK_EVERY = 64;

   private static final class State {
      int depth;
      int worldX;
      int worldZ;
      TileNoiseGpu.Table table;
      IntArrayList[] needs;
      long uses;
      final float[] values = new float[4];
      Object checking;
      float expectHeight;
      float expectErosion;
      float expectWeirdness;
   }

   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

   private TileNoise() {
   }

   public static boolean on() {
      return Config.GPU_TILE_NOISE.get() != Config.GpuFilterMode.OFF && !TileNoiseGpu.disabled();
   }

   /** A tile batch starts (its table, or null) or ends (null). */
   public static void batch(TileNoiseGpu.Table table) {
      State state = STATE.get();
      state.table = table;
      state.needs = null;
   }

   /** The dry pass starts (true) or ends (false: returns what was noted, per populator number). */
   public static IntArrayList[] record(boolean start) {
      State state = STATE.get();
      if (start) {
         state.needs = new IntArrayList[state.table == null ? 0 : state.table.populators()];
         return null;
      }

      IntArrayList[] needs = state.needs;
      state.needs = null;
      return needs;
   }

   /** Heightmap.applyTerrain(cell, x, z) starts (x, z: the block column, ints as floats). */
   public static void enter(float x, float z) {
      State state = STATE.get();
      if (state.depth++ == 0) {
         state.worldX = (int)x;
         state.worldZ = (int)z;
      }
   }

   public static void exit() {
      State state = STATE.get();
      if (--state.depth <= 0) {
         state.depth = 0;
         state.checking = null;
      }
   }

   /**
    * TerrainPopulator.apply(cell, x, z) starts: true when the cell was filled here (from the GPU, or a placeholder in the
    * dry pass; the method is then skipped), false when FreeTerraForged works it out (for a check, {@link #after} compares).
    */
   public static boolean apply(Object populator, Cell cell, float x, float z, Object type, float baseScale, float heightScale) {
      State state = STATE.get();
      state.checking = null;
      TileNoiseGpu.Table table = state.table;
      if (state.depth == 0 || table == null || !table.containsColumn(state.worldX, state.worldZ)) {
         return false;
      }

      int n = table.number(populator);
      if (n < 0) {
         return false;
      }

      if (state.needs != null) {
         // The dry pass: noted; a placeholder so the rest of the chain can run (it does not choose populators by these).
         IntArrayList list = state.needs[n];
         if (list == null) {
            list = state.needs[n] = new IntArrayList();
         }

         list.add(table.cell(state.worldX, state.worldZ));
         cell.terrain = (etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain)type;
         cell.height = 0.0F;
         cell.erosion = 0.0F;
         cell.weirdness = 0.0F;
         return true;
      }

      if (!on() || !TileNoiseGpu.lookup(table, n, state.worldX, state.worldZ, x, z, state.values)) {
         TileNoiseGpu.CPU.increment();
         return false;
      }

      float[] v = state.values;
      float height = Math.max(v[0] * baseScale + v[1] * heightScale, 0.0F);
      boolean check = Config.GPU_TILE_NOISE.get() == Config.GpuFilterMode.CHECK || ++state.uses % CHECK_EVERY == 0;
      if (check) {
         state.checking = populator;
         state.expectHeight = height;
         state.expectErosion = v[2];
         state.expectWeirdness = v[3];
         return false;
      }

      cell.terrain = (etcodehome.freeterraforged.world.worldgen.cell.terrain.Terrain)type;
      cell.height = height;
      cell.erosion = v[2];
      cell.weirdness = v[3];
      TileNoiseGpu.USED.increment();
      return true;
   }

   /** TerrainPopulator.apply returns: a checked call's results against the GPU's. */
   public static void after(Object populator, Cell cell) {
      State state = STATE.get();
      if (state.checking != populator || populator == null) {
         return;
      }

      state.checking = null;
      TileNoiseGpu.CHECKED.increment();
      if ((TileNoiseGpu.CHECKED.sum() & 1023) == 0) {
         WorldgenAccelerator.LOG.info(TileNoiseGpu.summary());
      }

      if (Float.floatToRawIntBits(cell.height) != Float.floatToRawIntBits(state.expectHeight)
         || Float.floatToRawIntBits(cell.erosion) != Float.floatToRawIntBits(state.expectErosion)
         || Float.floatToRawIntBits(cell.weirdness) != Float.floatToRawIntBits(state.expectWeirdness)) {
         TileNoiseGpu.disable(String.format(java.util.Locale.ROOT, "column (%d, %d): FTF height %s erosion %s weirdness %s, GPU %s %s %s", state.worldX, state.worldZ,
            cell.height, cell.erosion, cell.weirdness, state.expectHeight, state.expectErosion, state.expectWeirdness));
      }
   }
}
