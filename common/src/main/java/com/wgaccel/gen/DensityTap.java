package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.mixin.access.NoiseChunkAccessor;
import com.wgaccel.platform.Mc;
import com.wgaccel.platform.Names;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * GPU step G2.3's reference: a few real chunks' final density, block by block, exactly as the game (vanilla, C2ME and
 * FreeTerraForged) works it out during the noise fill, taken where each block hands it to the aquifer
 * ({@code mixin/check/AquiferTapMixin}, applied only in check runs; C2ME keeps its own cell caches, so vanilla's
 * CacheAllInCell list is empty); and the CellSampler values the GPU needs (FreeTerraForged's CacheChunk, per block
 * column, read while its tile is held). Only chunks without structure terrain adaptation or blending (the GPU does not do those yet). Off until
 * {@link #enable} (gpuMode CHECK, after the GPU code is ready).
 */
public final class DensityTap {
   /** A tapped chunk: its geometry, the CellSampler inputs (k * 289 + lx * 17 + lz) and the density per block ((ly * 16 + lz) * 16 + lx). */
   public record Tap(int chunkX, int chunkZ, int cellMinY, int cellCountY, int cellWidth, int cellHeight, double[] cells, double[] density, boolean[] filled,
         int[] states, Aquifers aquifer) {
   }

   /**
    * The chunk's aquifer as the GPU needs it, from vanilla's own code: grid (minGridX, minGridY, minGridZ, sizeX, sizeY,
    * sizeZ), each grid cell's random centre (x, y, z) and fluid status (level, type code), each column's lava level from
    * the global fluid picker (FreeTerraForged's), the sea level and the default fluid's code; enabled false for the
    * disabled aquifer (picker only).
    */
   public record Aquifers(boolean enabled, int[] grid, int[] loc, int[] status, int[] lava, int sea, int seaType) {
   }

   private static volatile List<Object> samplers;
   private static volatile Consumer<Tap> sink;
   private static int remaining;
   private static int skipped;
   private static final ThreadLocal<Object[]> CURRENT = new ThreadLocal<>();

   private DensityTap() {
   }

   /** Starts tapping: this many chunks go to the sink; samplers are the GPU code's CellSampler inputs, in order. */
   public static synchronized void enable(List<Object> cellSamplers, int chunks, Consumer<Tap> consumer) {
      samplers = cellSamplers;
      sink = consumer;
      remaining = chunks;
   }

   private static synchronized boolean take() {
      if (remaining <= 0) {
         return false;
      }

      remaining--;
      return true;
   }

   /** At the start of a chunk's fill (inside FreeTerraForged's tile stage). */
   public static void begin(NoiseChunk noise, ChunkAccess chunk) {
      if (sink == null) {
         return;
      }

      NoiseChunkAccessor access = (NoiseChunkAccessor)noise;
      if (access.wgaccel$blender() != Blender.empty() || hasStructures(access.wgaccel$beardifier())) {
         synchronized (DensityTap.class) {
            skipped++;
         }

         return;
      }

      if (!take()) {
         return;
      }

      try {
         int chunkX = chunk.getPos().getMinBlockX();
         int chunkZ = chunk.getPos().getMinBlockZ();
         List<Object> inputs = samplers;
         double[] cells = new double[Math.max(1, inputs.size() * 289)];
         for (int k = 0; k < inputs.size(); k++) {
            DensityFunction wrapped = access.wgaccel$wrapped().get((DensityFunction)inputs.get(k));
            if (wrapped == null) {
               throw new IllegalStateException("the noise chunk has no wrapped form of CellSampler " + inputs.get(k));
            }

            for (int lx = 0; lx <= 16; lx++) {
               for (int lz = 0; lz <= 16; lz++) {
                  cells[k * 289 + lx * 17 + lz] = wrapped.compute(new DensityFunction.SinglePointContext(chunkX + lx, 0, chunkZ + lz));
               }
            }
         }

         int height = access.wgaccel$cellCountY() * access.wgaccel$cellHeight();
         Tap tap = new Tap(chunkX, chunkZ, access.wgaccel$cellNoiseMinY(), access.wgaccel$cellCountY(), access.wgaccel$cellWidth(), access.wgaccel$cellHeight(),
            cells, new double[256 * height], new boolean[256 * height], new int[256 * height], aquifers(noise.aquifer(), chunkX, chunkZ));
         CURRENT.set(new Object[] {noise, tap});
      } catch (RuntimeException failure) {
         WorldgenAccelerator.LOG.warn("WGA GPU density chunk check: could not tap the chunk: {}", failure.toString());
      }
   }

   /** Every block of a fill (from the aquifer tap, check runs only): a tapped chunk's final density, copied. */
   public static void block(DensityFunction.FunctionContext context, double density) {
      if (sink == null) {
         return;
      }

      Object[] current = CURRENT.get();
      if (current == null || current[0] != context) {
         return;
      }

      Tap tap = (Tap)current[1];
      int lx = context.blockX() - tap.chunkX();
      int lz = context.blockZ() - tap.chunkZ();
      int ly = context.blockY() - tap.cellMinY() * tap.cellHeight();
      if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && ly >= 0 && ly < tap.cellCountY() * tap.cellHeight()) {
         int at = (ly * 16 + lz) * 16 + lx;
         tap.density()[at] = density;
         tap.filled()[at] = true;
      }
   }

   /**
    * Every block's state from the fill (check runs only): 0 none (the default block), 1 copper ore, 2 raw copper block,
    * 3 granite, 4 deepslate iron ore, 5 raw iron block, 6 tuff, 7 air, 8 water, 9 lava, 10 anything else.
    */
   public static void state(NoiseChunk noise, BlockState state) {
      if (sink == null) {
         return;
      }

      Object[] current = CURRENT.get();
      if (current == null || current[0] != noise) {
         return;
      }

      Tap tap = (Tap)current[1];
      int lx = noise.blockX() - tap.chunkX();
      int lz = noise.blockZ() - tap.chunkZ();
      int ly = noise.blockY() - tap.cellMinY() * tap.cellHeight();
      if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && ly >= 0 && ly < tap.cellCountY() * tap.cellHeight()) {
         tap.states()[(ly * 16 + lz) * 16 + lx] = code(state);
      }
   }

   /** The aquifer's grid, centres and statuses, and the picker's lava level per column, all by vanilla's and FreeTerraForged's own code. */
   private static Aquifers aquifers(Aquifer aquifer, int chunkX, int chunkZ) {
      try {
         Object picker = field(aquifer, "globalFluidPicker");
         if (picker == null) {
            picker = field(aquifer, "fluidPicker");
         }

         Aquifer.FluidPicker fluids = (Aquifer.FluidPicker)picker;
         int[] lava = new int[256];
         int sea = 0;
         int seaType = 8;
         for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               Aquifer.FluidStatus low = fluids.computeFluid(chunkX + lx, -1_000_000, chunkZ + lz);
               lava[lx * 16 + lz] = code((BlockState)field(low, "fluidType")) == 9 ? (Integer)field(low, "fluidLevel") : Integer.MIN_VALUE;
               Aquifer.FluidStatus high = fluids.computeFluid(chunkX + lx, 1_000_000, chunkZ + lz);
               sea = (Integer)field(high, "fluidLevel");
               seaType = code((BlockState)field(high, "fluidType"));
            }
         }

         if (!Names.className(aquifer.getClass()).endsWith("NoiseBasedAquifer")) {
            return new Aquifers(false, new int[6], new int[3], new int[2], lava, sea, seaType);
         }

         int minGridX = (Integer)field(aquifer, "minGridX");
         int minGridY = (Integer)field(aquifer, "minGridY");
         int minGridZ = (Integer)field(aquifer, "minGridZ");
         int sizeX = (Integer)field(aquifer, "gridSizeX");
         int sizeZ = (Integer)field(aquifer, "gridSizeZ");
         long[] cache = (long[])field(aquifer, "aquiferLocationCache");
         int sizeY = cache.length / (sizeX * sizeZ);
         PositionalRandomFactory random = (PositionalRandomFactory)field(aquifer, "positionalRandomFactory");
         Method status = Names.declaredMethod(aquifer.getClass(), "getAquiferStatus", long.class);
         status.setAccessible(true);
         int cells = sizeX * sizeY * sizeZ;
         int[] loc = new int[cells * 3];
         int[] statuses = new int[cells * 2];
         for (int gy = 0; gy < sizeY; gy++) {
            for (int gz = 0; gz < sizeZ; gz++) {
               for (int gx = 0; gx < sizeX; gx++) {
                  int index = (gy * sizeZ + gz) * sizeX + gx;
                  int x = minGridX + gx;
                  int y = minGridY + gy;
                  int z = minGridZ + gz;
                  long packed = cache[index];
                  if (packed == Long.MAX_VALUE) {
                     RandomSource source = random.at(x, y, z);
                     packed = BlockPos.asLong(x * 16 + source.nextInt(10), y * 12 + source.nextInt(9), z * 16 + source.nextInt(10));
                  }

                  loc[index * 3] = BlockPos.getX(packed);
                  loc[index * 3 + 1] = BlockPos.getY(packed);
                  loc[index * 3 + 2] = BlockPos.getZ(packed);
                  Object fluid = status.invoke(aquifer, packed);
                  statuses[index * 2] = (Integer)field(fluid, "fluidLevel");
                  statuses[index * 2 + 1] = code((BlockState)field(fluid, "fluidType"));
               }
            }
         }

         return new Aquifers(true, new int[] {minGridX, minGridY, minGridZ, sizeX, sizeY, sizeZ}, loc, statuses, lava, sea, seaType);
      } catch (ReflectiveOperationException failure) {
         throw new IllegalStateException("could not read the aquifer: " + failure, failure);
      }
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            java.lang.reflect.Field field = Names.declaredField(type, name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      return null;
   }

   private static int code(BlockState state) {
      if (state == null) {
         return 0;
      }

      Block block = state.getBlock();
      if (block == Blocks.COPPER_ORE) {
         return 1;
      } else if (block == Blocks.RAW_COPPER_BLOCK) {
         return 2;
      } else if (block == Blocks.GRANITE) {
         return 3;
      } else if (block == Blocks.DEEPSLATE_IRON_ORE) {
         return 4;
      } else if (block == Blocks.RAW_IRON_BLOCK) {
         return 5;
      } else if (block == Blocks.TUFF) {
         return 6;
      } else if (state.isAir()) {
         return 7;
      } else if (block == Blocks.WATER) {
         return 8;
      } else if (block == Blocks.LAVA) {
         return 9;
      }

      return 10;
   }

   /** At the end of a chunk's fill: a finished tap goes to the GPU check. */
   public static void end() {
      Object[] current = CURRENT.get();
      if (current != null) {
         CURRENT.remove();
         sink.accept((Tap)current[1]);
      }
   }

   public static synchronized int skipped() {
      return skipped;
   }

   static boolean hasStructures(DensityFunctions.BeardifierOrMarker beardifier) {
      if (!(beardifier instanceof Beardifier)) {
         return false;
      }

      return !Mc.pieces((Beardifier)beardifier).isEmpty() || !Mc.junctions((Beardifier)beardifier).isEmpty();
   }
}
