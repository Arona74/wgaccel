package com.wgaccel.gen;

import com.wgaccel.mixin.access.ChunkAccessAccessor;
import com.wgaccel.mixin.access.NoiseChunkAccessor;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * What the GPU biome kernels need from one chunk besides its cell positions (GPU step G5.2): the climate functions'
 * CellSampler values on the chunk's 16 quart columns (through the chunk's own noise chunk's wrapped samplers, read at the
 * chunk's first climate sample, as the sampler reads them), and FreeTerraForged's underground climate policy as the
 * sampler has it: applied (banding on and a preset), the world seed, the biome shape's underground coverage, size and
 * vertical size, the quantized surface depth, end depth, 0.21875 and 0.03125, and per quart column (qx * 4 + qz from the
 * chunk's first quart) the BiomeSurfaceEnvelope's minimum surface y (Integer.MAX_VALUE without a surface context).
 */
public class BiomeInputs {
   public int chunkX;
   public int chunkZ;
   /** CellSampler k at column (lx, lz) of the 17 x 17 grid: cells[k * 289 + lx * 17 + lz] (quart columns filled). */
   public double[] cells;
   public boolean banding;
   public long seed;
   public float coverage;
   public int size;
   public int verticalSize;
   public int surfaceDepth;
   public int endDepth;
   public int add21875;
   public int add03125;
   public final int[] minimumSurface = new int[16];

   private static volatile Method endDepthMethod;
   private static volatile Method minimumMethod;
   /** ClimateDepthFunction.deferredLookup, CellSampler.getCachedOrLookup, WorldLookup.getContext (FreeTerraForged 1.0.0023). */
   private static volatile Method[] climateDepthMethods;
   /** Whether FreeTerraForged's climate sampler still changes a target's depth (UndergroundBiomeBanding.allowsCaveBiome, before 1.0.0023). */
   private static volatile Boolean depthPolicy;
   /** The surface context's envelope and the context, from the last read with banding (for {@link #minimumSurfaces}). */
   private static volatile Object[] lastEnvelope;
   /** FreeTerraForged 1.0.0023's climate depth function from the last read (for {@link #minimumSurfaces} without banding). */
   private static volatile Object lastClimateDepth;

   /** Reads everything for the chunk from its noise chunk and the climate sampler in use. */
   public void read(ChunkAccess chunk, Object sampler, List<Object> cellSamplers) throws ReflectiveOperationException {
      this.chunkX = chunk.getPos().getMinBlockX();
      this.chunkZ = chunk.getPos().getMinBlockZ();
      NoiseChunk noise = ((ChunkAccessAccessor)chunk).wgaccel$noiseChunk();
      if (noise == null) {
         throw new IllegalStateException("the chunk has no noise chunk at its first climate sample");
      }

      this.policy(sampler);
      this.cells(noise, cellSamplers);
   }

   /** The same for a chunk not made yet (the biome look-ahead): its stand-in noise chunk and a climate sampler's policy. */
   public void read(int chunkX, int chunkZ, NoiseChunk noise, Object sampler, List<Object> cellSamplers) throws ReflectiveOperationException {
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.policy(sampler);
      this.cells(noise, cellSamplers);
   }

   private void cells(NoiseChunk noise, List<Object> samplers) throws ReflectiveOperationException {
      if (this.cells == null || this.cells.length < Math.max(1, samplers.size() * 289)) {
         this.cells = new double[Math.max(1, samplers.size() * 289)];
      }

      for (int k = 0; k < samplers.size(); k++) {
         if (Names.simpleName(samplers.get(k).getClass()).equals("ClimateDepthFunction")) {
            this.surfaceHeights(samplers.get(k), k);
            continue;
         }

         DensityFunction wrapped = ((NoiseChunkAccessor)noise).wgaccel$wrapped().get((DensityFunction)samplers.get(k));
         if (wrapped == null) {
            throw new IllegalStateException("the noise chunk has no wrapped form of CellSampler " + samplers.get(k));
         }

         for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
               this.cells[k * 289 + lx * 17 + lz] = wrapped.compute(new DensityFunction.SinglePointContext(this.chunkX + lx, 0, this.chunkZ + lz));
            }
         }
      }
   }

   /**
    * FreeTerraForged 1.0.0023's climate depth's surface heights on the chunk's quart columns, also kept as the chunk's 16
    * minimum surfaces: FreeTerraForged's answer for a column can change (as its envelope's could), and a result made with
    * other heights is not used ({@link #minimumSurfaces}).
    */
   private void surfaceHeights(Object climateDepth, int k) throws ReflectiveOperationException {
      surfaceHeights(climateDepth, this.chunkX, this.chunkZ, this.minimumSurface);
      lastClimateDepth = climateDepth;
      for (int qx = 0; qx < 4; qx++) {
         for (int qz = 0; qz < 4; qz++) {
            this.cells[k * 289 + qx * 4 * 17 + qz * 4] = this.minimumSurface[qx * 4 + qz];
         }
      }
   }

   /**
    * The climate depth's surface height per quart column (qx * 4 + qz) as it works it out on this thread now: levels.scale
    * of the height of CellSampler.getCachedOrLookup(lookup, x, z, false), its deferred lookup's. That is the filtered
    * cell when its chunk is in the filtered chunk cache, else the raw one, kept per thread: another answer by timing.
    */
   private static void surfaceHeights(Object climateDepth, int chunkX, int chunkZ, int[] out) throws ReflectiveOperationException {
      Method[] methods = climateDepthMethods;
      if (methods == null) {
         Class<?> cellSampler = Class.forName("etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler", false, climateDepth.getClass().getClassLoader());
         Class<?> lookup = Class.forName("etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup", false, climateDepth.getClass().getClassLoader());
         methods = new Method[] {climateDepth.getClass().getMethod("deferredLookup"), cellSampler.getMethod("getCachedOrLookup", lookup, int.class, int.class, boolean.class),
            lookup.getMethod("getContext")};
         climateDepthMethods = methods;
      }

      Object lookup = ((java.util.function.Supplier<?>)methods[0].invoke(climateDepth)).get();
      Object context = lookup == null ? null : methods[2].invoke(lookup);
      Object levels = context == null ? null : field(context, "levels");
      if (levels == null) {
         // Its compute gives 0 everywhere then, which the GPU's version does not.
         throw new IllegalStateException("FreeTerraForged's climate depth has no world lookup yet");
      }

      etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels scale = (etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels)levels;
      for (int qx = 0; qx < 4; qx++) {
         for (int qz = 0; qz < 4; qz++) {
            etcodehome.freeterraforged.world.worldgen.cell.Cell cell = (etcodehome.freeterraforged.world.worldgen.cell.Cell)methods[1].invoke(null, lookup, chunkX + qx * 4,
               chunkZ + qz * 4, false);
            out[qx * 4 + qz] = scale.scale(cell.height);
         }
      }
   }

   private void policy(Object sampler) throws ReflectiveOperationException {
      this.banding = false;
      Arrays.fill(this.minimumSurface, Integer.MAX_VALUE);
      Method getSemantics = method(sampler.getClass(), "climateQuerySemantics");
      Object semantics = getSemantics == null ? null : getSemantics.invoke(sampler);
      if (semantics == null) {
         return;
      }

      Object policy = call(semantics, "policy");
      Object preset = call(semantics, "preset");
      if (!(Boolean)call(policy, "appliesUndergroundBanding") || preset == null || !depthPolicy()) {
         return;
      }

      this.banding = true;
      this.seed = (Long)call(semantics, "seed");
      Object shape = field(call(preset, "climate"), "biomeShape");
      Object properties = field(call(preset, "world"), "properties");
      int worldHeight = (Integer)field(properties, "worldHeight");
      int worldDepth = (Integer)field(properties, "worldDepth");
      this.coverage = (Float)call(shape, "undergroundBiomeCoverage");
      this.size = (Integer)call(shape, "undergroundBiomeSize");
      Method vertical = Names.method(shape.getClass(), "undergroundBiomeVerticalSize", int.class, int.class);
      this.verticalSize = (Integer)vertical.invoke(shape, worldHeight, worldDepth);
      Method endDepth = endDepthMethod;
      if (endDepth == null) {
         Class<?> bandingClass = Names.forName("etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding");
         endDepth = Names.declaredMethod(bandingClass, "endDepth", preset.getClass());
         endDepth.setAccessible(true);
         endDepthMethod = endDepth;
      }

      this.surfaceDepth = Math.toIntExact(Climate.quantizeCoord(-0.0037500262f));
      this.endDepth = Math.toIntExact(Climate.quantizeCoord((Float)endDepth.invoke(null, preset)));
      this.add21875 = Math.toIntExact(Climate.quantizeCoord(0.21875f));
      this.add03125 = Math.toIntExact(Climate.quantizeCoord(0.03125f));
      Object context = call(semantics, "surfaceContext");
      if (context == null) {
         return;
      }

      Object envelope = field(context, "biomeSurfaceEnvelope");
      Method minimum = minimumMethod;
      if (minimum == null) {
         for (Method each : envelope.getClass().getMethods()) {
            if (Names.name(each).equals("minimumSurfaceY") && each.getParameterCount() == 3) {
               minimum = each;
            }
         }

         if (minimum == null) {
            throw new NoSuchMethodException("BiomeSurfaceEnvelope.minimumSurfaceY");
         }

         minimumMethod = minimum;
      }

      lastEnvelope = new Object[] {envelope, context};
      minimumSurfaces(envelope, context, minimum, this.chunkX, this.chunkZ, this.minimumSurface);
   }

   /**
    * Whether the climate sampler sets a target's depth to the surface's where no cave biome may be (UndergroundBiomeClimatePolicy.apply
    * through UndergroundBiomeBanding.allowsCaveBiome). FreeTerraForged 1.0.0023 keeps every depth (its apply passes the target
    * through, the cave biomes being chosen later in the selection), and has no allowsCaveBiome.
    */
   public static boolean depthPolicy() {
      Boolean known = depthPolicy;
      if (known == null) {
         boolean found = false;
         try {
            Class<?> banding = Class.forName("etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding", false, BiomeInputs.class.getClassLoader());
            for (Method each : banding.getDeclaredMethods()) {
               found |= each.getName().equals("allowsCaveBiome");
            }
         } catch (ClassNotFoundException | LinkageError absent) {
         }

         depthPolicy = known = found;
      }

      return known;
   }

   private static void minimumSurfaces(Object envelope, Object context, Method minimum, int chunkX, int chunkZ, int[] out) throws ReflectiveOperationException {
      int quartX = chunkX >> 2;
      int quartZ = chunkZ >> 2;
      for (int qx = 0; qx < 4; qx++) {
         for (int qz = 0; qz < 4; qz++) {
            out[qx * 4 + qz] = (Integer)minimum.invoke(envelope, context, quartX + qx, quartZ + qz);
         }
      }
   }

   /**
    * The chunk's 16 envelope minimums as FreeTerraForged gives them now, on this thread, or false when no read with banding
    * has run yet. FreeTerraForged 1.0.0013's later builds give a quart another answer depending on when and on which
    * thread it is asked (BiomeSurfaceEnvelope: a per-thread table before a shared one, filled ahead from chunk grids with
    * the full minimum, on demand with a sampled one), so a result made ahead is used only if these are still the same.
    * FreeTerraForged 1.0.0023 (no envelope in the depth): its climate depth's surface heights, which can change too.
    */
   public static boolean minimumSurfaces(int chunkX, int chunkZ, int[] out) throws ReflectiveOperationException {
      Object climateDepth = lastClimateDepth;
      if (climateDepth != null) {
         surfaceHeights(climateDepth, chunkX, chunkZ, out);
         return true;
      }

      Object[] last = lastEnvelope;
      Method minimum = minimumMethod;
      if (last == null || minimum == null) {
         return false;
      }

      minimumSurfaces(last[0], last[1], minimum, chunkX, chunkZ, out);
      return true;
   }

   private static Method method(Class<?> type, String name) {
      for (Method each : type.getMethods()) {
         if (Names.name(each).equals(name) && each.getParameterCount() == 0) {
            return each;
         }
      }

      return null;
   }

   private static Object call(Object target, String name) throws ReflectiveOperationException {
      Method found = method(target.getClass(), name);
      if (found == null) {
         throw new NoSuchMethodException(Names.className(target.getClass()) + "." + name);
      }

      found.setAccessible(true);
      return found.invoke(target);
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field found = Names.declaredField(type, name);
            found.setAccessible(true);
            return found.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(Names.className(target.getClass()) + "." + name);
   }
}
