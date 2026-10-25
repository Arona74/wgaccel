package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.SurfaceCodegen;
import com.wgaccel.mixin.access.SurfaceContextAccessor;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GPU step G3.2's reference: in a few chunks after the surface code is compiled, every surface rule call
 * (SurfaceSystem.buildSurface's tryApply) with its context's values and its result, as the game works them out: block
 * position, stone depths, water height, surface depth and secondary, min surface level, the block's biome (registry id),
 * temperature (cold enough to snow), steepness (Worldgen Patches' version, by the context's own condition), and
 * FreeTerraForged's strata layers for the column (read from its rule's buffer once the column has used it). Taking the
 * values runs the context's lazy getters early, which changes nothing: they are pure. Off until {@link #enable}.
 */
public final class SurfaceTap {
   public static final int STRATA_MAX = 64;
   public static final int FIELDS = 13;

   /** A tapped chunk's calls (FIELDS ints each), results (state index, -1 for null), and per-column secondary and strata. */
   public static final class Tap {
      public int[] calls = new int[FIELDS * 4096];
      public int[] results = new int[4096];
      public int count;
      public final double[] secondary = new double[256];
      public final float[] strataDepth = new float[256 * STRATA_MAX];
      public final int[] strataState = new int[256 * STRATA_MAX];
      public final int[] strataCount = new int[256];
      final boolean[] strataDone = new boolean[256];
      /** G3.3a: the column heights (WORLD_SURFACE_WG after FreeTerraForged's river water), the region's quart biome ids around the
       * chunk ((qx * sz + qz) * sy + qy) and their origin and size (gx, gy, gz, sx, sy, sz), and the biome zoom seed. */
      public final int[] heights = new int[256];
      public int[] quarts;
      public final int[] grid = new int[6];
      public long zoomSeed;
      /** G3.3b: FreeTerraForged's StrataRule.Source of the chunk's rule tree (its selector and layer sets), or null. */
      public Object strataSource;
      /** G3.3c: every block before the column loop (after FreeTerraForged's river water) and after the surface step, per column
       * ((lx * 16 + lz) * height + y - minY); their kinds for the GPU (0 air, 1 fluid, 2 the default block, 3 other stone); each
       * column's start (WORLD_SURFACE_WG + 1) and min surface level without the surface depth (vanilla's preliminary
       * surface lerp). */
      public BlockState[] before;
      public BlockState[] after;
      public byte[] kinds;
      public final int[] tops = new int[256];
      public final int[] minBase = new int[256];
      public int minY;
      public int height;
      boolean failed;
      int chunkX;
      int chunkZ;

      public int chunkX() {
         return this.chunkX;
      }

      public int chunkZ() {
         return this.chunkZ;
      }
   }

   private static final class Current {
      final Tap tap = new Tap();
      SurfaceContextAccessor context;
      net.minecraft.world.level.chunk.ChunkAccess chunk;
      net.minecraft.world.level.levelgen.NoiseChunk noise;
      BlockState defaultBlock;
      Object steep;
      Object strata;
      final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
   }

   private static volatile Consumer<Tap> sink;
   private static volatile SurfaceCodegen codegen;
   private static volatile Registry<Biome> biomes;
   private static int remaining;
   private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();
   private static Method conditionTest;

   private SurfaceTap() {
   }

   public static synchronized void enable(SurfaceCodegen code, Registry<Biome> registry, int chunks, Consumer<Tap> consumer) {
      codegen = code;
      biomes = registry;
      remaining = chunks;
      sink = consumer;
   }

   private static synchronized boolean take() {
      if (remaining <= 0) {
         return false;
      }

      remaining--;
      return true;
   }

   public static void begin(net.minecraft.world.level.chunk.ChunkAccess chunk, Object biomeManager, net.minecraft.world.level.levelgen.NoiseChunk noise,
         Object surfaceSystem) {
      if (sink == null || !take()) {
         return;
      }

      Current current = new Current();
      current.chunk = chunk;
      current.noise = noise;
      try {
         current.defaultBlock = (BlockState)field(surfaceSystem, "defaultBlock");
      } catch (ReflectiveOperationException failure) {
         current.tap.failed = true;
      }

      current.tap.chunkX = chunk.getPos().x;
      current.tap.chunkZ = chunk.getPos().z;
      Arrays.fill(current.tap.strataCount, -1);
      try {
         quarts(current.tap, chunk, biomeManager);
      } catch (ReflectiveOperationException | RuntimeException failure) {
         current.tap.failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU surface rule check: could not read the biome grid: {}", failure.toString());
      }

      CURRENT.set(current);
   }

   /** The noise biomes BiomeManager.getBiome can reach from this chunk's blocks, as the region gives them (y clamped by the chunk). */
   private static void quarts(Tap tap, net.minecraft.world.level.chunk.ChunkAccess chunk, Object biomeManager) throws ReflectiveOperationException {
      Object source = field(biomeManager, "noiseBiomeSource");
      tap.zoomSeed = (Long)field(biomeManager, "biomeZoomSeed");
      Method getter = null;
      for (Method each : source.getClass().getMethods()) {
         if (Names.name(each).equals("getNoiseBiome") && each.getParameterCount() == 3) {
            getter = each;
         }
      }

      if (getter == null) {
         throw new NoSuchMethodException("getNoiseBiome");
      }

      getter.setAccessible(true);
      int gx = (chunk.getPos().getMinBlockX() >> 2) - 1;
      int gz = (chunk.getPos().getMinBlockZ() >> 2) - 1;
      int gy = chunk.getMinBuildHeight() >> 2;
      int sy = ((chunk.getMaxBuildHeight() - 1) >> 2) - gy + 1;
      tap.grid[0] = gx;
      tap.grid[1] = gy;
      tap.grid[2] = gz;
      tap.grid[3] = 6;
      tap.grid[4] = sy;
      tap.grid[5] = 6;
      tap.quarts = new int[6 * 6 * sy];
      for (int qx = 0; qx < 6; qx++) {
         for (int qz = 0; qz < 6; qz++) {
            for (int qy = 0; qy < sy; qy++) {
               @SuppressWarnings("unchecked")
               Holder<Biome> biome = (Holder<Biome>)getter.invoke(source, gx + qx, gy + qy, gz + qz);
               tap.quarts[(qx * 6 + qz) * sy + qy] = biomes.getId(biome.value());
            }
         }
      }
   }

   /** C1: Lithostitched's SlopeCondition input: the highest minus the lowest of the four neighbours' WORLD_SURFACE_WG heights. */
   private static int slope(net.minecraft.world.level.chunk.ChunkAccess chunk, int x, int z) {
      net.minecraft.world.level.levelgen.Heightmap.Types type = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG;
      int north = chunk.getHeight(type, x, Math.max(z - 1, 0));
      int south = chunk.getHeight(type, x, Math.min(z + 1, 15));
      int west = chunk.getHeight(type, Math.max(x - 1, 0), z);
      int east = chunk.getHeight(type, Math.min(x + 1, 15), z);
      return Math.max(Math.max(north, south), Math.max(east, west)) - Math.min(Math.min(north, south), Math.min(east, west));
   }

   /** The rule tree made for the chunk: its context, the context's steep condition and FreeTerraForged's strata rule. */
   public static void built(Object context, Object rule) {
      Current current = CURRENT.get();
      if (current == null) {
         return;
      }

      try {
         current.context = (SurfaceContextAccessor)context;
         for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               current.tap.heights[lx * 16 + lz] = current.chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
            }
         }

         before(current);

         current.steep = field(context, "steep");
         current.strata = find(rule, new IdentityHashMap<>());
         current.tap.strataSource = current.strata;
      } catch (ReflectiveOperationException | RuntimeException failure) {
         current.tap.failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU surface rule check: could not tap the chunk: {}", failure.toString());
      }
   }

   public static BlockState result(BlockState state) {
      Current current = CURRENT.get();
      if (current == null || current.tap.failed || current.context == null) {
         return state;
      }

      try {
         record(current, state);
      } catch (ReflectiveOperationException | RuntimeException failure) {
         current.tap.failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU surface rule check: could not record a rule call: {}", failure.toString());
      }

      return state;
   }

   private static void record(Current current, BlockState state) throws ReflectiveOperationException {
      Tap tap = current.tap;
      SurfaceContextAccessor c = current.context;
      if (tap.count == tap.results.length) {
         tap.results = Arrays.copyOf(tap.results, tap.count * 2);
         tap.calls = Arrays.copyOf(tap.calls, tap.count * 2 * FIELDS);
      }

      int x = c.wgaccel$x();
      int y = c.wgaccel$y();
      int z = c.wgaccel$z();
      int column = (x & 15) * 16 + (z & 15);
      Holder<Biome> biome = c.wgaccel$biome().get();
      int at = tap.count * FIELDS;
      tap.calls[at] = x;
      tap.calls[at + 1] = y;
      tap.calls[at + 2] = z;
      tap.calls[at + 3] = c.wgaccel$stoneAbove();
      tap.calls[at + 4] = c.wgaccel$stoneBelow();
      tap.calls[at + 5] = c.wgaccel$water();
      tap.calls[at + 6] = c.wgaccel$depth();
      tap.calls[at + 7] = biomes.getId(biome.value());
      tap.calls[at + 8] = biome.value().coldEnoughToSnow(current.pos.set(x, y, z)) ? 1 : 0;
      tap.calls[at + 9] = test(current.steep) ? 1 : 0;
      tap.calls[at + 10] = c.wgaccel$minSurface();
      tap.calls[at + 11] = column;
      tap.calls[at + 12] = slope(current.chunk, x & 15, z & 15);
      tap.secondary[column] = c.wgaccel$secondary();
      tap.results[tap.count] = state == null ? -1 : codegen.state(state);
      tap.count++;
      if (current.strata != null && !tap.strataDone[column] && (Long)field(current.strata, "lastUpdateXZ") == c.wgaccel$lastUpdateXZ()) {
         List<?> layers = (List<?>)field(current.strata, "layers");
         float[] depths = (float[])field(current.strata, "depthBuffer");
         if (layers == null || layers.size() > STRATA_MAX) {
            throw new IllegalStateException("strata of " + (layers == null ? "no" : layers.size()) + " layers");
         }

         for (int i = 0; i < layers.size(); i++) {
            Object layer = layers.get(i);
            Method material = Names.declaredMethod(layer.getClass(), "material");
            material.setAccessible(true);
            tap.strataDepth[column * STRATA_MAX + i] = depths[i];
            tap.strataState[column * STRATA_MAX + i] = codegen.state((BlockState)material.invoke(layer));
         }

         tap.strataCount[column] = layers.size();
         tap.strataDone[column] = true;
      }
   }

   /** Every block of the chunk before the column loop, its kinds, the column starts and the min surface bases. */
   private static void before(Current current) {
      Tap tap = current.tap;
      net.minecraft.world.level.chunk.ChunkAccess chunk = current.chunk;
      tap.minY = chunk.getMinBuildHeight();
      tap.height = chunk.getHeight();
      tap.before = new BlockState[256 * tap.height];
      tap.kinds = new byte[256 * tap.height];
      int minX = chunk.getPos().getMinBlockX();
      int minZ = chunk.getPos().getMinBlockZ();
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int t = 0; t < 256; t++) {
         int lx = t / 16;
         int lz = t % 16;
         for (int y = 0; y < tap.height; y++) {
            BlockState state = chunk.getBlockState(pos.set(minX + lx, tap.minY + y, minZ + lz));
            tap.before[t * tap.height + y] = state;
            tap.kinds[t * tap.height + y] = (byte)(state.isAir() ? 0 : !state.getFluidState().isEmpty() ? 1 : state == current.defaultBlock ? 2 : 3);
         }

         tap.tops[t] = tap.heights[t] + 1;
      }

      // SurfaceRules.Context.getMinSurfaceLevel without the surface depth: the preliminary surface at the 16-block cell's corners.
      int cellX = minX >> 4;
      int cellZ = minZ >> 4;
      double c0 = current.noise.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
      double c1 = current.noise.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
      double c2 = current.noise.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
      double c3 = current.noise.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
      for (int t = 0; t < 256; t++) {
         int x = minX + t / 16;
         int z = minZ + t % 16;
         tap.minBase[t] = net.minecraft.util.Mth.floor(net.minecraft.util.Mth.lerp2((double)((float)(x & 15) / 16.0F), (double)((float)(z & 15) / 16.0F), c0, c1, c2, c3));
      }
   }

   public static void end() {
      Current current = CURRENT.get();
      if (current == null) {
         return;
      }

      CURRENT.remove();
      if (!current.tap.failed && current.tap.before != null) {
         Tap tap = current.tap;
         tap.after = new BlockState[256 * tap.height];
         int minX = current.chunk.getPos().getMinBlockX();
         int minZ = current.chunk.getPos().getMinBlockZ();
         BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
         for (int t = 0; t < 256; t++) {
            for (int y = 0; y < tap.height; y++) {
               tap.after[t * tap.height + y] = current.chunk.getBlockState(pos.set(minX + t / 16, tap.minY + y, minZ + t % 16));
            }
         }
      }

      if (!current.tap.failed) {
         for (int i = 0; i < 256; i++) {
            if (current.tap.strataCount[i] < 0) {
               current.tap.strataCount[i] = 0;
            }
         }

         sink.accept(current.tap);
      }
   }

   private static synchronized boolean test(Object condition) throws ReflectiveOperationException {
      if (conditionTest == null) {
         conditionTest = Names.method(condition.getClass(), "test");
         conditionTest.setAccessible(true);
      }

      return (Boolean)conditionTest.invoke(condition);
   }

   /** FreeTerraForged's StrataRule.Source inside a chunk's made rule tree, or null. */
   private static Object find(Object node, Map<Object, Boolean> seen) throws ReflectiveOperationException {
      if (node == null || seen.put(node, Boolean.TRUE) != null) {
         return null;
      }

      if (Names.className(node.getClass()).endsWith("StrataRule$Source")) {
         return node;
      }

      if (node instanceof List<?> list) {
         for (Object each : list) {
            Object found = find(each, seen);
            if (found != null) {
               return found;
            }
         }

         return null;
      }

      if (!Names.className(node.getClass()).contains("SurfaceRules$") || Names.className(node.getClass()).contains("Context")) {
         return null;
      }

      for (Class<?> type = node.getClass(); type != null && !Names.className(type).startsWith("java."); type = type.getSuperclass()) {
         for (Field each : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(each.getModifiers()) && !each.getType().isPrimitive()) {
               each.setAccessible(true);
               Object found = find(each.get(node), seen);
               if (found != null) {
                  return found;
               }
            }
         }
      }

      return null;
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
