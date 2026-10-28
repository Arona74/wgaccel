package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Mc;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/**
 * count_on_every_layer (config {@code fastLayerPlacement}, CountOnEveryLayerMixin): its findOnGroundYPosition scans a
 * column from the top down to the n-th ground (a non-empty block under an empty one, bedrock not counting) for layer n,
 * so every layer scans the column again, and to the bottom where it has fewer grounds (No Man's Land 1.6.2's roots,
 * icicles and shelf mushrooms, 40 to 100 a layer: 40 ms a chunk in FreeTerraForged's 768-block world). While
 * getPositions runs nothing is placed, so each column's scan is kept for the call and only continued as far as a layer
 * needs: the same grounds, never more block reads than the game's. Every 256th answer is compared with the game's scan;
 * a difference turns it off.
 */
public final class LayerGrounds {
   private static final ThreadLocal<Long2ObjectOpenHashMap<Column>> CALL = new ThreadLocal<>();
   private static final AtomicLong ANSWERS = new AtomicLong();
   public static final AtomicLong CHECKS = new AtomicLong();
   private static final AtomicInteger DIFFERENCES = new AtomicInteger();
   private static volatile boolean off;

   private LayerGrounds() {
   }

   /** A column's scan so far: the grounds found, the next y to look below and the block above it. */
   private static final class Column {
      final int top;
      final IntArrayList grounds = new IntArrayList(4);
      int y;
      BlockState above;

      Column(int top, BlockState above) {
         this.top = top;
         this.y = top;
         this.above = above;
      }
   }

   /** Around getPositions: the columns of this call (the outer call's put back after, should one nest). */
   public static Object begin() {
      if (off || !Config.FAST_LAYER_PLACEMENT.getAsBoolean()) {
         return Boolean.FALSE;
      }

      Long2ObjectOpenHashMap<Column> outer = CALL.get();
      CALL.set(new Long2ObjectOpenHashMap<>());
      return outer;
   }

   @SuppressWarnings("unchecked")
   public static void end(Object token) {
      if (token != Boolean.FALSE) {
         CALL.set((Long2ObjectOpenHashMap<Column>)token);
      }
   }

   /** findOnGroundYPosition(context, x, y, z, layer) from the column's kept scan; game is the game's own scan. */
   public static int ground(PlacementContext context, int x, int y, int z, int layer, IntSupplier game) {
      Long2ObjectOpenHashMap<Column> columns = CALL.get();
      if (columns == null || off) {
         return game.getAsInt();
      }

      long key = BlockPos.asLong(x, 0, z);
      Column column = columns.get(key);
      if (column == null || column.top != y) {
         column = new Column(y, context.getBlockState(new BlockPos(x, y, z)));
         columns.put(key, column);
      }

      int bottom = Mc.minY(context.getLevel()) + 1;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
      while (column.grounds.size() <= layer && column.y >= bottom) {
         pos.setY(column.y - 1);
         BlockState state = context.getBlockState(pos);
         boolean ground = !empty(state) && empty(column.above) && !state.is(Blocks.BEDROCK);
         column.above = state;
         column.y--;
         if (ground) {
            column.grounds.add(column.y + 1);
         }
      }

      int answer = layer < column.grounds.size() ? column.grounds.getInt(layer) : Integer.MAX_VALUE;
      if (ANSWERS.incrementAndGet() % 256 == 0) {
         long checks = CHECKS.incrementAndGet();
         int normal = game.getAsInt();
         if (checks % 4096 == 0) {
            WorldgenAccelerator.LOG.info(String.format(java.util.Locale.ROOT, "WGA fast every-layer placement: %,d answers, checked %,d: %s", ANSWERS.get(),
               checks, DIFFERENCES.get() == 0 && normal == answer ? "IDENTICAL" : "DIFFER (off)"));
         }

         if (normal != answer) {
            off = true;
            if (DIFFERENCES.incrementAndGet() <= 5) {
               WorldgenAccelerator.LOG.warn("WGA fast every-layer placement: column ({}, {}) layer {} from top {}: game {}, ours {}; off, please report",
                  x, z, layer, y, normal, answer);
            }
         }

         return normal;
      }

      return answer;
   }

   /** CountOnEveryLayerPlacement.isEmpty. */
   private static boolean empty(BlockState state) {
      return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
   }
}
