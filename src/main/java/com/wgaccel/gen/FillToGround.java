package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.ftf.FtfGround;
import com.wgaccel.mixin.access.BeardifierAccessor;
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
import net.neoforged.fml.ModList;

/**
 * Plan item B1: FreeTerraForged's 3D terrain filled only up to what a chunk can hold, not its whole configured height.
 *
 * <p>Above its ground FTF's density only falls (no 3D noise, caves only carve), so the cut is the highest of: the
 * highest ground of the chunk plus a margin, sea level (FTF's only water above the caves), and the top of any structure
 * piece that raises the land under it. The noise chunk is made with the cut height (its corner columns, aquifer grid
 * and surface scan all follow), the fill stops there, and the sections above stay air. Every chunk is checked: if any
 * block reaches the top noise cell of the cut, a warning names the chunk (the margin was too small).
 */
public final class FillToGround {
   /** Blocks kept above the highest ground: two noise cells. */
   private static final int MARGIN = 16;
   /** Blocks kept above structure pieces and junctions (their terrain adaptation reaches about 12). */
   private static final int STRUCTURE_MARGIN = 16;
   private static final boolean FTF = ModList.get().isLoaded("freeterraforged");
   /** FreeTerraForged without the tile system FtfGround reads (1.0.0013 and later). */
   private static volatile boolean ftfGroundGone;
   public static final LongAdder CUT = new LongAdder();
   public static final LongAdder TOUCHED = new LongAdder();
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
      // No Man's Land's altar: solid up to its top whatever the ground (its centre piece has no terrain adjustment).
      int altar = NoMansLand.top(beardifier);
      if (altar == Integer.MAX_VALUE) {
         return full;
      }

      top = Math.max(top, altar == Integer.MIN_VALUE ? altar : altar + STRUCTURE_MARGIN);
      int cellHeight = full.getCellHeight();
      int fullCells = Math.floorDiv(full.height(), cellHeight);
      int cells = Math.floorDiv(top - full.minY() + cellHeight - 1, cellHeight) + 1;
      if (cells >= fullCells) {
         return full;
      }

      CUT.increment();
      FULL.set(full);
      return new NoiseSettings(full.minY(), Math.max(1, cells) * cellHeight, full.noiseSizeHorizontal(), full.noiseSizeVertical());
   }

   /** After the fill: blocks reaching the top cell of a cut chunk mean the margin was too small. */
   public static void check(ChunkPos pos, int cellsFilled, int fullCells, int cellsNeeded) {
      if (cellsFilled < fullCells && cellsNeeded >= cellsFilled) {
         TOUCHED.increment();
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
