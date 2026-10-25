package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.ftf.FtfGround;
import com.wgaccel.mixin.access.BeardifierAccessor;
import com.wgaccel.platform.Platform;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Plan item B1: FreeTerraForged's 3D terrain filled only up to what a chunk can hold, not its whole configured height.
 *
 * <p>Above its ground FTF's density only falls (no 3D noise, caves only carve), so the cut is the highest of: the
 * highest ground of the chunk plus a margin, sea level (FTF's only water above the caves), and the top of any structure
 * piece that raises the land under it. The noise chunk is made with the cut height (its corner columns, aquifer grid
 * and surface scan all follow), the fill stops there, and the sections above stay air. Every chunk is checked: if any
 * block reaches the top noise cell of the cut, a warning names the chunk (the margin was too small) and the cut stops
 * for the world. A world's first chunks are filled whole on probation first ({@link World}): presets and datapacks can
 * give a terrain FreeTerraForged's ground does not hold.
 */
public final class FillToGround {
   /** Blocks kept above the highest ground: two noise cells. */
   private static final int MARGIN = 16;
   /** Blocks kept above structure pieces and junctions (their terrain adaptation reaches about 12). */
   private static final int STRUCTURE_MARGIN = 16;
   private static final boolean FTF = Platform.get().isModLoaded("freeterraforged");
   /** FreeTerraForged without the tile system FtfGround reads (1.0.0013 and later). */
   private static volatile boolean ftfGroundGone;
   public static final LongAdder CUT = new LongAdder();
   public static final LongAdder TOUCHED = new LongAdder();
   /** Chunks that held blocks before their fill, not checked. */
   public static final LongAdder PREFILLED = new LongAdder();
   private static final AtomicInteger WARNINGS = new AtomicInteger();

   /** The full noise settings of the noise chunk being made, when trim cut it: its aquifer keeps the full height. */
   private static final ThreadLocal<NoiseSettings> FULL = new ThreadLocal<>();

   private FillToGround() {
   }

   /**
    * The height range for the noise chunk's aquifer: the full one even when the fill is cut, because carvers later ask
    * the same aquifer about blocks up to the full height (a cut aquifer grid gave index errors in low terrain). The
    * aquifer's cells are the same either way; only its cache covers more.
    */
   public static int[] aquiferRange(int minY, int height) {
      NoiseSettings full = FULL.get();
      FULL.remove();
      return full == null ? new int[] {minY, height} : new int[] {full.minY(), full.height()};
   }

   /** The noise settings for this chunk's noise chunk: the cut height, or the full one when cutting is not safe. */
   public static NoiseSettings trim(NoiseSettings full, ChunkAccess chunk, RandomState random, DensityFunctions.BeardifierOrMarker beardifier,
         NoiseGeneratorSettings settings, Blender blender) {
      if (!FTF || !Config.FILL_TO_GROUND.getAsBoolean() || blender != Blender.empty()) {
         return full;
      }

      long start = System.nanoTime();
      int ground;
      try {
         if (com.wgaccel.ftf.FtfCellsGround.available(chunk)) {
            // FreeTerraForged 1.0.0013 and later: the chunk's own cells (no tiles).
            ground = com.wgaccel.ftf.FtfCellsGround.highest(random, chunk);
         } else if (ftfGroundGone) {
            return full;
         } else {
            ground = FtfGround.highest(random, chunk.getPos());
         }
      } catch (ReflectiveOperationException failed) {
         return full;
      } catch (LinkageError gone) {
         // FreeTerraForged 1.0.0013 removed its tile system (TileCache, Tile), which FtfGround reads: no ground to cut
         // at, the full height is filled (a crash before 0.10.19).
         ftfGroundGone = true;
         com.wgaccel.WgaChat.warnOff("WGA fillToGround off for FreeTerraForged: its tiles are not there in this version ({})", gone.toString());
         return full;
      }

      Stats.record(Stage.FTF_GROUND, System.nanoTime() - start);
      if (ground == FtfGround.UNKNOWN) {
         return full;
      }

      int top = Math.max(ground, settings.seaLevel()) + MARGIN;
      top = Math.max(top, structureTop(beardifier));
      // Other mods' terrain (ModTerrain: No Man's Land's altar, RoadWeaver's road fills): solid up to its top whatever
      // the ground.
      int modTop = ModTerrain.top(beardifier);
      if (modTop == Integer.MAX_VALUE) {
         return full;
      }

      top = Math.max(top, modTop == Integer.MIN_VALUE ? modTop : modTop + STRUCTURE_MARGIN);
      int cellHeight = full.getCellHeight();
      int fullCells = Math.floorDiv(full.height(), cellHeight);
      int cells = Math.floorDiv(top - full.minY() + cellHeight - 1, cellHeight) + 1;
      if (cells >= fullCells) {
         return full;
      }

      World world = world(random);
      if (world.off) {
         return full;
      }

      if (!world.trusted) {
         // On probation: the whole height filled, the cut it would have had compared with the chunk's highest block.
         if (PROBATION.size() < 4096) {
            PROBATION.put(chunk.getPos().toLong(), cells);
         }

         return full;
      }

      CUT.increment();
      FULL.set(full);
      return new NoiseSettings(full.minY(), Math.max(1, cells) * cellHeight, full.noiseSizeHorizontal(), full.noiseSizeVertical());
   }

   /**
    * The world's fill-to-ground state. Its first {@link #PROBATION_CHUNKS} chunks are filled whole and their highest block
    * compared with the cut they would have had: a block in its top cell or above turns fill-to-ground off for the world
    * (its terrain is not FreeTerraForged's ground alone: a preset's own density functions rose 4 cells over the cut at a
    * user's spawn and 56 chunks were cut off, 24 Oct 2026); all clean, the cut is used. A cut chunk reaching its top
    * cell later turns it off too.
    */
   private static final class World {
      final Object random;
      final AtomicInteger clean = new AtomicInteger();
      volatile boolean trusted;
      volatile boolean off;

      World(Object random) {
         this.random = random;
      }
   }

   private static final int PROBATION_CHUNKS = 256;
   /** Chunks on probation: the cells the cut would have kept. */
   private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> PROBATION = new java.util.concurrent.ConcurrentHashMap<>();
   private static volatile World world;

   private static World world(RandomState random) {
      World known = world;
      if (known != null && known.random == random) {
         return known;
      }

      synchronized (FillToGround.class) {
         known = world;
         if (known == null || known.random != random) {
            known = new World(random);
            PROBATION.clear();
            world = known;
         }

         return known;
      }
   }

   /**
    * After the fill: blocks reaching the top cell of a cut (or of the cut a chunk on probation would have had). A chunk
    * that held blocks before its fill is not judged: its highest block may be its own from before (Distant Horizons'
    * Surface Only generation fills again chunks it read from the save; a tree on a peak reached the top cell of the
    * cut, 25 Oct 2026). The fill sets no air, so those blocks stay whatever the cut.
    */
   public static void check(ChunkPos pos, int cellsFilled, int fullCells, int cellsNeeded, boolean prefilled) {
      World current = world;
      Integer predicted = PROBATION.remove(pos.toLong());
      if (prefilled) {
         PREFILLED.increment();
         return;
      }

      if (predicted != null && current != null && !current.off) {
         if (cellsNeeded >= predicted) {
            current.off = true;
            com.wgaccel.WgaChat.warnOff("WGA fill-to-ground off for this world: chunk {}'s terrain reaches cell {} of {}, its cut would have kept {} (this"
               + " world's terrain rises above FreeTerraForged's ground: a preset's or datapack's own density); the whole height is filled", pos,
               cellsNeeded, fullCells, predicted);
         } else if (!current.trusted && current.clean.incrementAndGet() >= PROBATION_CHUNKS) {
            current.trusted = true;
            WorldgenAccelerator.LOG.info("WGA fill-to-ground: on for this world ({} chunks filled whole stayed under their cut)", PROBATION_CHUNKS);
         }
      }

      if (cellsFilled < fullCells && cellsNeeded >= cellsFilled) {
         TOUCHED.increment();
         if (current != null && !current.off) {
            current.off = true;
            com.wgaccel.WgaChat.warnOff("WGA fill-to-ground off for this world: chunk {} reached the top cell of its cut; the whole height is filled from now on",
               pos);
         }

         if (WARNINGS.incrementAndGet() <= 20) {
            com.wgaccel.WgaChat.warnOff("WGA fill-to-ground: chunk {} has blocks in the top cell of its cut ({} of {} cells): its terrain may be cut off; please report",
               pos, cellsFilled, fullCells);
         }
      }
   }

   private static int structureTop(DensityFunctions.BeardifierOrMarker beardifier) {
      if (!(beardifier instanceof Beardifier)) {
         return Integer.MIN_VALUE;
      }

      BeardifierAccessor access = (BeardifierAccessor)beardifier;
      int top = Integer.MIN_VALUE;
      ObjectListIterator<Beardifier.Rigid> pieces = access.wgaccel$pieceIterator();
      while (pieces.hasNext()) {
         Beardifier.Rigid piece = pieces.next();
         if (piece.terrainAdjustment() != TerrainAdjustment.NONE) {
            top = Math.max(top, piece.box().maxY() + STRUCTURE_MARGIN);
         }
      }

      pieces.back(Integer.MAX_VALUE);
      ObjectListIterator<JigsawJunction> junctions = access.wgaccel$junctionIterator();
      while (junctions.hasNext()) {
         JigsawJunction junction = junctions.next();
         top = Math.max(top, junction.getSourceGroundY() + Math.abs(junction.getDeltaY()) + STRUCTURE_MARGIN);
      }

      junctions.back(Integer.MAX_VALUE);
      return top;
   }
}
