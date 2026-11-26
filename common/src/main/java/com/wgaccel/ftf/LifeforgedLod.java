package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Platform;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Lifeforged (the Still Life x FreeTerraForged bridge, 6.0 and later) on the LOD columns, through its public BridgeHooks:
 * <ul>
 *   <li>at the head of FreeTerraForged's erodeColumn (the LOD scree, {@link FtfLodDecor#scree}): its snow caps (with their
 *       fade to bare rock) and its ground cover, which takes the column over from FTF's own erosion (talus, scree fields,
 *       risers, the regional rock on bare ground);</li>
 *   <li>at the start of the light step: its deep rock (rock_strata=deep: the regional rock through the land's body and
 *       under Still Life's geology skin), over the whole chunk once its columns are made.</li>
 * </ul>
 * Both run on a stand-in chunk holding the LOD columns' ground ({@link com.wgaccel.platform.Mc#columnChunk}: their
 * GROUND_DEPTH layers and the default block under them, a fluid block over wet ground, the stand-in's FreeTerraForged cells,
 * the columns' own biomes), and the columns are read back from it. Left out (FreeTerraForged's own erosion, without deep
 * rock, as before) for a bridge without these hooks (5.x: {@link StillLifeSnow}) or once a hook fails.
 *
 * <p>Not followed: its shores and mountain-snow passes (the whole chunk, at the end of the erosion decorator), its
 * natural snow layers (the LODs have one snow layer), and deep rock on blocks the LOD terrain features set (they come
 * after it in the LODs, before it in the game).
 */
public final class LifeforgedLod {
   /** The ground under a column's layers the chunk holds: Lifeforged's deep rock reworks Still Life's skin 40 deep. */
   private static final int DEPTH = 40;
   private static final Method EROSION_PASS;
   private static final Method KEEP_SNOW;
   private static final Method GROUND_COVER;
   private static final Method BEGIN;
   private static final Method END;
   private static final Method DEEP_ROCK;
   private static final Method SET_CELLS;
   private static volatile boolean broken;
   private static final ThreadLocal<Chunk> CURRENT = new ThreadLocal<>();

   private static final AtomicLong CHUNKS = new AtomicLong();
   private static final LongAdder COLUMNS = new LongAdder();
   private static final LongAdder COVERED = new LongAdder();
   private static final LongAdder SNOW_KEPT = new LongAdder();
   private static final LongAdder COVER_NANOS = new LongAdder();
   private static final LongAdder DEEP_NANOS = new LongAdder();
   private static final LongAdder CHANGED = new LongAdder();

   static {
      Method erosionPass = null;
      Method keepSnow = null;
      Method groundCover = null;
      Method begin = null;
      Method end = null;
      Method deepRock = null;
      Method setCells = null;
      if (Platform.get().isModLoaded("stilllife_ftf_bridge") && Platform.get().isModLoaded("freeterraforged")) {
         try {
            Class<?> hooks = Class.forName("stilllife.ftfbridge.BridgeHooks", false, LifeforgedLod.class.getClassLoader());
            for (Method method : hooks.getMethods()) {
               int n = method.getParameterCount();
               switch (method.getName()) {
                  case "erosionPass" -> erosionPass = n == 3 ? method : erosionPass;
                  case "keepSnow" -> keepSnow = n == 6 ? method : keepSnow;
                  case "groundCover" -> groundCover = n == 7 ? method : groundCover;
                  case "groundCoverBegin" -> begin = n == 1 ? method : begin;
                  case "groundCoverEnd" -> end = n == 0 ? method : end;
                  case "deepRock" -> deepRock = n == 2 ? method : deepRock;
                  default -> {
                  }
               }
            }

            if (erosionPass == null || keepSnow == null || groundCover == null || begin == null || end == null || deepRock == null) {
               // 5.x: package-private hooks, snow caps only (StillLifeSnow).
               throw new NoSuchMethodException("BridgeHooks: no public erosionPass / keepSnow / groundCover / deepRock (a bridge before 6.0)");
            }

            // IChunkCellsHolder.freeterraforged$setChunkCells (FreeTerraForged 1.0.0013 and later; the build compiles
            // against 1.0.0010's interface).
            Class<?> holder = Class.forName("etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder", false, FTFRandomState.class.getClassLoader());
            setCells = holder.getMethod("freeterraforged$setChunkCells", Cell[].class);
            WorldgenAccelerator.LOG.info("WGA LOD columns: Lifeforged's ground cover, snow caps and deep rock followed in the LODs (its BridgeHooks)");
         } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            erosionPass = null;
            WorldgenAccelerator.LOG.info("WGA LOD columns: Lifeforged's ground cover and deep rock not followed in the LODs ({})", failure.toString());
         }
      }

      EROSION_PASS = erosionPass;
      KEEP_SNOW = keepSnow;
      GROUND_COVER = groundCover;
      BEGIN = begin;
      END = end;
      DEEP_ROCK = deepRock;
      SET_CELLS = setCells;
   }

   private LifeforgedLod() {
   }

   /** Whether Lifeforged's hooks are there (and none has failed). */
   public static boolean available() {
      return EROSION_PASS != null && !broken;
   }

   /** The chunk this thread is making with Lifeforged, or null. */
   static Chunk current() {
      return broken ? null : CURRENT.get();
   }

   private static void failed(String what, Throwable failure) {
      if (!broken) {
         broken = true;
         Throwable cause = failure instanceof InvocationTargetException target && target.getCause() != null ? target.getCause() : failure;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: Lifeforged's {} failed on the LOD columns; LODs without its ground cover and deep rock from now",
            what, cause);
      }
   }

   /**
    * Starts a LOD chunk on this thread: its stand-in chunk (biomes from biomes, FreeTerraForged's cells the stand-in's),
    * and Lifeforged's erosion pass on level (a stand-in level: the seed, the real level, the chunks around). Null when
    * Lifeforged is not followed. Always {@link Chunk#end} it.
    */
   public static Chunk begin(ServerLevel level, ChunkPos pos, Object[] cells, BiomeResolver biomes, WorldGenLevel standIn) {
      if (!available() || cells == null) {
         return null;
      }

      ProtoChunk chunk = com.wgaccel.platform.Mc.columnChunk(pos, level, level.registryAccess(), biomes);
      // Its blocks set as a decorated chunk's (heightmaps kept up, WORLD_SURFACE for deep rock).
      chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
      Chunk made = new Chunk(chunk);
      try {
         SET_CELLS.invoke(chunk, (Object)cells);
         BEGIN.invoke(null, standIn);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         failed("erosion pass start", failure);
         return null;
      }

      CURRENT.set(made);
      return made;
   }

   /** A LOD chunk being made with Lifeforged: its stand-in chunk. */
   public static final class Chunk {
      public final ProtoChunk chunk;
      private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      private final int minY;
      private final int maxY;
      private boolean ended;

      Chunk(ProtoChunk chunk) {
         this.chunk = chunk;
         this.minY = com.wgaccel.platform.Mc.minY(chunk);
         this.maxY = this.minY + chunk.getHeight();
      }

      /**
       * Column (x, z) into the chunk: layers[k] at ground - k, the block under them (below) down to DEPTH, and fluid
       * (or nothing) over the ground.
       */
      public void put(int x, int z, int ground, BlockState[] layers, BlockState below, BlockState fluid) {
         for (int k = 0; k < DEPTH; k++) {
            this.set(x, ground - k, z, k < layers.length ? layers[k] : below);
         }

         if (fluid != null) {
            this.set(x, ground + 1, z, fluid);
         }
      }

      /** Column (x, z)'s layers back from the chunk. */
      public void read(int x, int z, int ground, BlockState[] layers) {
         for (int k = 0; k < layers.length; k++) {
            int y = ground - k;
            if (y >= this.minY && y < this.maxY) {
               BlockState state = this.chunk.getBlockState(this.pos.set(x, y, z));
               if (layers[k] != state) {
                  CHANGED.increment();
                  layers[k] = state;
               }
            }
         }
      }

      private void set(int x, int y, int z, BlockState state) {
         if (y >= this.minY && y < this.maxY) {
            com.wgaccel.platform.Mc.setBlockState(this.chunk, this.pos.set(x, y, z), state, false);
         }
      }

      /** Lifeforged's deep rock over the chunk (its columns all put), as at the start of the chunk's light step. */
      public void deepRock(ServerLevel level) {
         if (broken) {
            return;
         }

         long start = System.nanoTime();
         try {
            DEEP_ROCK.invoke(null, level, this.chunk);
         } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed("deep rock", failure);
         } finally {
            DEEP_NANOS.add(System.nanoTime() - start);
         }
      }

      /** Ends the chunk's erosion pass on this thread. */
      public void end() {
         if (this.ended) {
            return;
         }

         this.ended = true;
         CURRENT.remove();
         try {
            END.invoke(null);
         } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed("erosion pass end", failure);
         }

         long n = CHUNKS.incrementAndGet();
         if (n == 500L || n % 20_000L == 0L) {
            long columns = Math.max(1L, COLUMNS.sum());
            WorldgenAccelerator.LOG.info("WGA LOD columns: Lifeforged on {} LOD chunks: {} of {} dry columns its ground cover took over, {} snow"
               + " tops it kept; {} blocks changed; per chunk {} ms of ground cover and snow caps, {} ms of deep rock", n, COVERED.sum(), columns,
               SNOW_KEPT.sum(), CHANGED.sum(), String.format(java.util.Locale.ROOT, "%.2f", COVER_NANOS.sum() / 1.0e6 / n),
               String.format(java.util.Locale.ROOT, "%.2f", DEEP_NANOS.sum() / 1.0e6 / n));
         }
      }
   }

   /**
    * The head of FreeTerraForged's erodeColumn on one dry column of the current chunk, as Lifeforged's ScreeFeatureMixin:
    * its erosion pass settings, its snow caps (a snow top off cliffs kept, the cap's base bared), its ground cover.
    * {@link #KEPT} or {@link #COVERED_BY} when it took the column (FTF's own erosion then left out), {@link #NONE}
    * otherwise; the column's blocks (blocks[k] at ground - k) are read back either way.
    */
   static int erode(Chunk current, Object scree, Object levels, Object rand, Cell cell, int x, int z, int ground, BlockState[] blocks,
         BlockState defaultBlock, float steepness) {
      long start = System.nanoTime();
      COLUMNS.increment();
      try {
         current.put(x, z, ground, blocks, defaultBlock, null);
         EROSION_PASS.invoke(null, scree, levels, rand);
         if ((Boolean)KEEP_SNOW.invoke(null, scree, current.chunk, x, ground, z, steepness)) {
            current.read(x, z, ground, blocks);
            SNOW_KEPT.increment();
            return KEPT;
         }

         BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, ground, z);
         boolean covered = (Boolean)GROUND_COVER.invoke(null, scree, levels, rand, current.chunk, cell, pos, ground);
         current.read(x, z, ground, blocks);
         if (covered) {
            COVERED.increment();
            return COVERED_BY;
         }

         return NONE;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         failed("ground cover", failure);
         return NONE;
      } finally {
         COVER_NANOS.add(System.nanoTime() - start);
      }
   }

   /**
    * Whether the modifier is Lifeforged's placement filter {@code stilllife_ftf_bridge:terrain} (TerrainFilter: slope,
    * talus, treeline, clearings...; Still Life FTF 0.3.8 puts it on its trees), which reads only FreeTerraForged's model
    * of the column (and the real blocks within 2 blocks, its model instead in a chunk before the noise step): the LOD
    * trees run it on a stand-in level.
    */
   public static boolean terrainFilter(net.minecraft.world.level.levelgen.placement.PlacementModifier modifier) {
      return modifier.getClass().getName().equals("stilllife.ftfbridge.TerrainFilter");
   }

   /**
    * FreeTerraForged's model ground + 1 at (x, z) (the column's cell, unfiltered: the y a placement's heightmap gives,
    * near enough), the y Lifeforged's terrain filter is asked at; Integer.MIN_VALUE when not a FreeTerraForged world.
    */
   public static int modelSurface(ServerLevel level, int x, int z) {
      if (!((Object)level.getChunkSource().randomState() instanceof FTFRandomState ftf) || !ftf.isTerraForged()) {
         return Integer.MIN_VALUE;
      }

      etcodehome.freeterraforged.world.worldgen.GeneratorContext context = ftf.generatorContext();
      if (context == null || context.lookup == null) {
         return Integer.MIN_VALUE;
      }

      Cell cell = new Cell();
      context.lookup.applyCell(cell, x, z, false, true);
      return context.levels.scale(cell.height) + 1;
   }

   /** {@link #erode}'s answers: FTF's erosion goes on; a snow top kept; the ground cover took the column. */
   static final int NONE = 0;
   static final int KEPT = 1;
   static final int COVERED_BY = 2;
}
