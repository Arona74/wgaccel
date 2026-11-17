package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

/**
 * Disk features' columns skipped when no block of them can pass the disk's target (config fastDiskColumns). DiskFeature
 * tests its target at every position of every column of its disk, top to bottom: William Wythers' Overhauled Overworld
 * places such disks 100 to 256 times a chunk with radii up to 8 and 9 heights (stone cliffs, crusts, sand and mud fixes),
 * and the 7 Nov 2026 profile (1.21.1, r1000) had DiskFeature at 42% of all CPU, nearly all of it block reads in the
 * target's tests, for positions that do not pass.
 * <p>
 * Many targets can only pass when the position's own block is one of a few (the matching_blocks and matching_block_tag
 * tests at offset 0 of an all_of, on every branch of an any_of): from the target's tree, a condition on that block every
 * passing position meets. A column whose chunk sections have no such block in their palettes (a palette holds every block
 * of its section, and maybe more) cannot pass anywhere, so it is not tested; when they may have one, the column's own
 * blocks are read straight from the sections, and a column with none of them is not tested either. That is the same world: a position's own
 * block does not change in its column before it is tested (the column only writes blocks it has tested), and a failing
 * test draws no random numbers and writes nothing. About 1 skipped column in 256 is tested anyway (only the tests, which
 * write nothing); a position that passes turns this off.
 */
public final class DiskColumns {
   private static final int CHECK_EVERY = 256;
   private static final LongAdder COLUMNS = new LongAdder();
   private static final LongAdder SKIPPED = new LongAdder();
   /** Of those, the columns whose sections hold such a block somewhere, skipped by reading the column's own blocks. */
   private static final LongAdder SKIPPED_BY_BLOCKS = new LongAdder();
   private record Derived(Predicate<BlockState> need, boolean exact) {
   }

   private static final Derived NONE = new Derived(null, false);
   private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
   private static final LongAdder CHECKED = new LongAdder();
   private static final ConcurrentHashMap<BlockPredicate, Derived> NEEDS = new ConcurrentHashMap<>();
   private static volatile boolean off;
   private static volatile Field offsetField;
   private static volatile Field blocksField;
   private static volatile Field tagField;
   private static volatile Field predicatesField;

   private DiskColumns() {
   }

   public static final int DISK_EMPTY = -1;
   public static final int DISK_UNINDEXED = 0;
   public static final int DISK_ACTIVE = 1;

   public static boolean on() {
      return !off && Config.FAST_DISK_COLUMNS.get();
   }

   /**
    * Begins a batched disk placement for {@code target} centered at {@code (ox, oz)} with {@code radius}.
    * Resolves the target need once and checks overlapping chunk sections' palettes; returns {@link #DISK_EMPTY}
    * when no overlapping section in {@code [bottom + 1 .. top]} can possibly pass {@code target}.
    */
   public static int beginDisk(BlockPredicate target, WorldGenLevel level, int ox, int oz, int radius, int top, int bottom) {
      if (top <= bottom || radius < 0) {
         return DISK_EMPTY;
      }

      Column column = COLUMN.get();
      Derived derived = NEEDS.computeIfAbsent(target, DiskColumns::deriveNeed);
      Predicate<BlockState> need = derived.need;
      column.diskTarget = target;
      column.diskDerived = derived;
      column.diskActive = false;

      if (need == null) {
         column.diskIndexed = false;
         column.diskActive = true;
         return DISK_UNINDEXED;
      }

      int minCx = (ox - radius) >> 4;
      int maxCx = (ox + radius) >> 4;
      int minCz = (oz - radius) >> 4;
      int maxCz = (oz + radius) >> 4;
      int spanX = maxCx - minCx + 1;
      int spanZ = maxCz - minCz + 1;
      if (spanX > 2 || spanZ > 2) {
         column.diskIndexed = false;
         column.diskActive = true;
         return DISK_UNINDEXED;
      }

      column.diskMinCx = minCx;
      column.diskMinCz = minCz;
      column.diskSpanX = spanX;
      boolean anyMaybe = false;
      for (int dz = 0; dz < spanZ; dz++) {
         for (int dx = 0; dx < spanX; dx++) {
            int slot = dz * spanX + dx;
            ChunkAccess chunk = level.getChunk(minCx + dx, minCz + dz, ChunkStatus.EMPTY, false);
            column.diskChunks[slot] = chunk;
            if (chunk == null) {
               column.diskChunkHas[slot] = true;
               anyMaybe = true;
               continue;
            }

            int low = chunk.getSectionIndex(bottom + 1);
            int high = chunk.getSectionIndex(top);
            LevelChunkSection[] sections = chunk.getSections();
            int minY = com.wgaccel.platform.Mc.minY(chunk);
            if (low < 0 || high >= sections.length || bottom + 1 < minY || top >= minY + chunk.getHeight()) {
               column.diskChunkHas[slot] = true;
               anyMaybe = true;
               continue;
            }

            boolean chunkHas = false;
            for (int sy = high; sy >= low; sy--) {
               if (sections[sy].maybeHas(need)) {
                  chunkHas = true;
                  break;
               }
            }
            column.diskChunkHas[slot] = chunkHas;
            anyMaybe |= chunkHas;
         }
      }

      if (!anyMaybe) {
         int cols = countCircleColumns(radius);
         column.columns += cols;
         column.skipped += cols;
         if (column.columns >= FLUSH) {
            column.flush();
         }
         if (java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0) {
            check(target, level, top, bottom, new BlockPos(ox, top, oz));
         }
         clearDiskChunks(column);
         return DISK_EMPTY;
      }

      column.diskIndexed = true;
      column.diskActive = true;
      return DISK_ACTIVE;
   }

   /** Fast column pre-check inside an active {@link #beginDisk} batch using hoisted derived state and cached chunks. */
   public static boolean skipDiskColumn(WorldGenLevel level, int top, int bottom, BlockPos pos) {
      Column column = COLUMN.get();
      if (++column.columns >= FLUSH) {
         column.flush();
      }

      Derived derived = column.diskDerived;
      Predicate<BlockState> need = derived.need;
      if (need == null || top <= bottom) {
         return false;
      }

      int cx = pos.getX() >> 4;
      int cz = pos.getZ() >> 4;
      ChunkAccess chunk;
      if (column.diskIndexed) {
         int slot = (cz - column.diskMinCz) * column.diskSpanX + (cx - column.diskMinCx);
         if (!column.diskChunkHas[slot]) {
            column.skipped++;
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0) {
               check(column.diskTarget, level, top, bottom, pos);
            }
            return true;
         }
         chunk = column.diskChunks[slot];
      } else {
         chunk = level.getChunk(cx, cz, ChunkStatus.EMPTY, false);
      }

      return skipInChunk(column.diskTarget, derived, need, chunk, level, top, bottom, pos, column);
   }

   /** Ends the active {@link #beginDisk} batch on this thread. */
   public static void endDisk() {
      Column column = COLUMN.get();
      column.diskActive = false;
      column.diskIndexed = false;
      column.diskTarget = null;
      column.diskDerived = null;
      clearDiskChunks(column);
   }

   private static void clearDiskChunks(Column column) {
      for (int i = 0; i < 4; i++) {
         column.diskChunks[i] = null;
      }
   }

   private static int countCircleColumns(int radius) {
      int r2 = radius * radius;
      int count = 0;
      for (int dz = -radius; dz <= radius; dz++) {
         int dz2 = dz * dz;
         for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz2 <= r2) {
               count++;
            }
         }
      }
      return count;
   }

   /**
    * Whether the column from top down to above bottom at the position's x and z cannot pass the target anywhere (its
    * sections' palettes hold no block the target needs there); false when that cannot be told.
    */
   public static boolean skip(BlockPredicate target, WorldGenLevel level, int top, int bottom, BlockPos pos) {
      Column column = COLUMN.get();
      if (++column.columns >= FLUSH) {
         column.flush();
      }

      Derived derived = NEEDS.computeIfAbsent(target, DiskColumns::deriveNeed);
      Predicate<BlockState> need = derived.need;
      if (need == null || top <= bottom) {
         return false;
      }

      ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.EMPTY, false);
      return skipInChunk(target, derived, need, chunk, level, top, bottom, pos, column);
   }

   private static boolean skipInChunk(BlockPredicate target, Derived derived, Predicate<BlockState> need, ChunkAccess chunk,
         WorldGenLevel level, int top, int bottom, BlockPos pos, Column column) {
      if (chunk == null) {
         return false;
      }

      int low = chunk.getSectionIndex(bottom + 1);
      int high = chunk.getSectionIndex(top);
      LevelChunkSection[] sections = chunk.getSections();
      int minY = com.wgaccel.platform.Mc.minY(chunk);
      if (low < 0 || high >= sections.length || bottom + 1 < minY || top >= minY + chunk.getHeight()) {
         return false;
      }

      int lx = pos.getX() & 15;
      int lz = pos.getZ() & 15;
      long mask = 0L;
      boolean anyMaybe = false;
      for (int sy = high; sy >= low; sy--) {
         LevelChunkSection section = sections[sy];
         if (!section.maybeHas(need)) {
            continue;
         }

         anyMaybe = true;
         int secMinY = minY + (sy << 4);
         int yTop = Math.min(top, secMinY + 15);
         int yBot = Math.max(bottom + 1, secMinY);
         if (section.hasOnlyAir()) {
            if (need.test(AIR)) {
               for (int y = yTop; y >= yBot; y--) {
                  if (top - y >= 64) {
                     return false;
                  }

                  mask |= 1L << (top - y);
               }
            }
            continue;
         }

         for (int y = yTop; y >= yBot; y--) {
            if (need.test(section.getBlockState(lx, y & 15, lz))) {
               if (top - y >= 64) {
                  return false;
               }

               mask |= 1L << (top - y);
            }
         }
      }

      if (mask != 0L) {
         column.target = target;
         column.x = pos.getX();
         column.z = pos.getZ();
         column.top = top;
         column.mask = mask;
         column.exact = derived.exact;
         return false;
      }

      if (anyMaybe) {
         column.skippedByBlocks++;
      }

      column.skipped++;
      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0) {
         check(target, level, top, bottom, pos);
      }

      return true;
   }

   /** The column being placed on this thread whose own blocks were read ({@link #skip}): the heights that may pass. */
   private static final class Column {
      boolean diskActive;
      boolean diskIndexed;
      BlockPredicate diskTarget;
      Derived diskDerived;
      int diskMinCx;
      int diskMinCz;
      int diskSpanX;
      final ChunkAccess[] diskChunks = new ChunkAccess[4];
      final boolean[] diskChunkHas = new boolean[4];

      BlockPredicate target;
      int x;
      int z;
      int top;
      long mask;
      boolean exact;
      // This thread's counts, added to the shared ones every FLUSH columns (a shared counter per column and test was
      // 1.7% of all CPU in the 7 Nov profile).
      long columns;
      long skipped;
      long skippedByBlocks;
      long positions;

      void flush() {
         COLUMNS.add(this.columns);
         SKIPPED.add(this.skipped);
         SKIPPED_BY_BLOCKS.add(this.skippedByBlocks);
         POSITIONS_SKIPPED.add(this.positions);
         this.columns = 0L;
         this.skipped = 0L;
         this.skippedByBlocks = 0L;
         this.positions = 0L;
      }
   }

   private static final int FLUSH = 4096;

   private static final ThreadLocal<Column> COLUMN = ThreadLocal.withInitial(Column::new);
   private static final LongAdder POSITIONS_SKIPPED = new LongAdder();

   /**
    * The target's test at a position of the column being placed: false at once where its own block, read as the column
    * began, is none the target needs (it cannot have changed since: the column only writes blocks it has tested); else
    * the test. About 1 in 256 such answers is tested anyway.
    */
   public static boolean test(BlockPredicate target, WorldGenLevel level, BlockPos pos) {
      Column column = COLUMN.get();
      if (column.target == target && column.x == pos.getX() && column.z == pos.getZ()) {
         int row = column.top - pos.getY();
         if (row >= 0 && row < 64) {
            if ((column.mask & 1L << row) == 0L) {
               column.positions++;
               if (java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0) {
                  CHECKED.increment();
                  if (FastPredicates.testOnce(target, level, pos)) {
                     if (!off) {
                        off = true;
                        WorldgenAccelerator.LOG.warn("WGA disk columns: a skipped position passes its target at {} (block {}); off, disks test every position again",
                           pos.immutable(), level.getBlockState(pos));
                     }

                     return true;
                  }
               }

               return false;
            }

            if (column.exact) {
               column.positions++;
               if (java.util.concurrent.ThreadLocalRandom.current().nextInt(CHECK_EVERY) == 0) {
                  CHECKED.increment();
                  if (!FastPredicates.testOnce(target, level, pos)) {
                     if (!off) {
                        off = true;
                        WorldgenAccelerator.LOG.warn("WGA disk columns: an exact position fails its target at {} (block {}); off, disks test every position again",
                           pos.immutable(), level.getBlockState(pos));
                     }

                     return false;
                  }
               }

               return true;
            }
         }
      }

      return FastPredicates.testOnce(target, level, pos);
   }

   /** The column is placed: its heights no longer apply. */
   public static void done() {
      COLUMN.get().target = null;
   }

   /** A skipped column tested anyway: no position may pass. */
   private static void check(BlockPredicate target, WorldGenLevel level, int top, int bottom, BlockPos pos) {
      CHECKED.increment();
      BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(pos.getX(), top, pos.getZ());
      for (int y = top; y > bottom; y--) {
         at.setY(y);
         if (target.test(level, at) && !off) {
            off = true;
            WorldgenAccelerator.LOG.warn("WGA disk columns: a skipped column passes its target at {} (block {}); off, disks test every column again", at.immutable(),
               level.getBlockState(at));
            return;
         }
      }
   }

   /** Whether any disk column was seen (for the end-of-run report). */
   public static boolean used() {
      return COLUMNS.sum() > 0L;
   }

   public static String summary() {
      long columns = COLUMNS.sum();
      return String.format(Locale.ROOT, "WGA disk columns: %,d of %,d columns skipped (%.0f%%; %,d of them by their own blocks), %,d tests of other columns answered"
         + " by their own blocks, checked %,d%s", SKIPPED.sum(), columns, columns == 0 ? 0.0 : 100.0 * SKIPPED.sum() / columns, SKIPPED_BY_BLOCKS.sum(),
         POSITIONS_SKIPPED.sum(), CHECKED.sum(), off ? " (a test passed: off)" : ": IDENTICAL");
   }

   private static Derived deriveNeed(BlockPredicate target) {
      try {
         Derived derived = derive(target);
         WorldgenAccelerator.LOG.debug("WGA disk columns: target {} needs {}", target, derived);
         return derived == null ? NONE : derived;
      } catch (ReflectiveOperationException | RuntimeException failure) {
         return NONE;
      }
   }

   /**
    * A condition the position's own block meets wherever the predicate passes, or null when none is known: matching
    * blocks or a block tag at offset 0; all_of, its children's known conditions all; any_of, its children's conditions,
    * one of them, when every child has one. Anything else (not, solid, replaceable, fluids, offsets other than 0) passes
    * or fails on other blocks too, so it gives none.
    */
   @SuppressWarnings("unchecked")
   private static Derived derive(BlockPredicate predicate) throws ReflectiveOperationException {
      String kind = Names.simpleName(predicate.getClass());
      switch (kind) {
         case "MatchingBlocksPredicate" -> {
            if (!Vec3i.ZERO.equals(field(predicate, "offset"))) {
               return null;
            }

            HolderSet<Block> blocks = (HolderSet<Block>)field(predicate, "blocks");
            if (blocks.unwrapKey().isPresent()) {
               // A tag's holder set follows the tag: asked as the test asks it.
               return new Derived(state -> state.is(blocks), true);
            }

            BlockSet set = new BlockSet();
            blocks.forEach(holder -> set.blocks.add(holder.value()));
            return new Derived(set, true);
         }
         case "MatchingBlockTagPredicate" -> {
            if (!Vec3i.ZERO.equals(field(predicate, "offset"))) {
               return null;
            }

            TagKey<Block> tag = (TagKey<Block>)field(predicate, "tag");
            return new Derived(state -> state.is(tag), true);
         }
         case "AllOfPredicate" -> {
            Predicate<BlockState> all = null;
            boolean allExact = true;
            for (BlockPredicate child : (List<BlockPredicate>)field(predicate, "predicates")) {
               Derived sub = derive(child);
               if (sub == null) {
                  allExact = false;
               } else {
                  Predicate<BlockState> need = sub.need;
                  all = all == null ? need : all instanceof BlockSet a && need instanceof BlockSet b ? a.and(b) : all.and(need);
                  allExact &= sub.exact;
               }
            }

            return all == null ? null : new Derived(all, allExact);
         }
         case "AnyOfPredicate" -> {
            Predicate<BlockState> any = null;
            boolean allExact = true;
            for (BlockPredicate child : (List<BlockPredicate>)field(predicate, "predicates")) {
               Derived sub = derive(child);
               if (sub == null) {
                  return null;
               }

               Predicate<BlockState> need = sub.need;
               any = any == null ? need : any instanceof BlockSet a && need instanceof BlockSet b ? a.or(b) : any.or(need);
               allExact &= sub.exact;
            }

            return any == null ? null : new Derived(any, allExact);
         }
         default -> {
            return null;
         }
      }
   }

   /**
    * A condition that is a fixed set of blocks (matching_blocks with a list, and their all_of / any_of): one identity
    * lookup instead of a chain of holder-set tests (3% of all CPU as lambdas in the 7 Nov profile).
    */
   private static final class BlockSet implements Predicate<BlockState> {
      final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block> blocks = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

      @Override
      public boolean test(BlockState state) {
         return this.blocks.contains(state.getBlock());
      }

      BlockSet and(BlockSet other) {
         BlockSet both = new BlockSet();
         for (Block block : this.blocks) {
            if (other.blocks.contains(block)) {
               both.blocks.add(block);
            }
         }

         return both;
      }

      BlockSet or(BlockSet other) {
         BlockSet either = new BlockSet();
         either.blocks.addAll(this.blocks);
         either.blocks.addAll(other.blocks);
         return either;
      }

      @Override
      public String toString() {
         return this.blocks.toString();
      }
   }

   private static Object field(Object owner, String name) throws ReflectiveOperationException {
      Field known = switch (name) {
         case "offset" -> offsetField;
         case "blocks" -> blocksField;
         case "tag" -> tagField;
         default -> predicatesField;
      };
      if (known == null) {
         for (Class<?> type = owner.getClass(); type != null && known == null; type = type.getSuperclass()) {
            try {
               known = Names.declaredField(type, name);
            } catch (NoSuchFieldException ignored) {
            }
         }

         if (known == null) {
            throw new NoSuchFieldException(Names.className(owner.getClass()) + "." + name);
         }

         known.setAccessible(true);
         switch (name) {
            case "offset" -> offsetField = known;
            case "blocks" -> blocksField = known;
            case "tag" -> tagField = known;
            default -> predicatesField = known;
         }
      }

      return known.get(owner);
   }
}
