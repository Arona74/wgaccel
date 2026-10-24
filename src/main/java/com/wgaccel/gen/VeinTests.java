package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.mixin.access.StateTestingPredicateAccessor;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;

/**
 * No Man's Land 1.6.2's ore veins test every non-air block of a vein's columns with the vein's target condition
 * (OreVeinSystem.fillColumnForVein: about 68,000 a chunk, 5 ms in FreeTerraForged) through the level, right after
 * reading that block from the chunk's section. A vanilla state-testing predicate (matching_block_tag, matching_blocks,
 * solid and the rest) with no offset tests level.getBlockState(pos), the block just read, so it is asked about that
 * block directly (config {@code fastLayerPlacement}). Every 256th answer is compared with the level's; a difference
 * turns it off.
 */
public final class VeinTests {
   private static final AtomicLong ANSWERS = new AtomicLong();
   private static final AtomicLong CHECKS = new AtomicLong();
   private static volatile boolean off;

   private VeinTests() {
   }

   public static boolean test(BlockPredicate predicate, BlockState current, BooleanSupplier game) {
      if (off || current == null || !(predicate instanceof StateTestingPredicate) || !Config.FAST_LAYER_PLACEMENT.getAsBoolean()) {
         return game.getAsBoolean();
      }

      StateTestingPredicateAccessor access = (StateTestingPredicateAccessor)predicate;
      if (!Vec3i.ZERO.equals(access.wgaccel$offset())) {
         return game.getAsBoolean();
      }

      boolean answer = access.wgaccel$testState(current);
      if (ANSWERS.incrementAndGet() % 256 == 0) {
         long checks = CHECKS.incrementAndGet();
         boolean normal = game.getAsBoolean();
         if (normal != answer) {
            off = true;
            WorldgenAccelerator.LOG.warn("WGA No Man's Land vein tests: {} on {}: the level says {}, the block read {}; off, please report", predicate, current,
               normal, answer);
         } else if (checks % (1 << 20) == 0) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA No Man's Land vein tests: %,d answers from the block read, checked %,d: IDENTICAL",
               ANSWERS.get(), checks));
         }

         return normal;
      }

      return answer;
   }
}
