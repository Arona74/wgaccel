package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * A jigsaw piece's jigsaw blocks, rotated, made once per template and rotation. The jigsaw assembly asks every candidate
 * piece for them in all four rotations at position zero, for every jigsaw it tries to attach to: in Conquest
 * ReTerraForged's walled town (2026-10-27 probe) 4.59 million lists, of which 99.7% were a (piece, rotation) asked
 * before, and making them (the palette, every block's position and state rotated) was most of the 40% of jigsaw time
 * in getShuffledJigsawBlocks. At position zero the list depends only on the template and the rotation: the palette is
 * chosen by a random seeded from the position, not the assembly's random. The caller shuffles and sorts a fresh copy
 * with the assembly's random as before, so the pieces and the random draws are the same. Every 256th list is compared
 * with a newly made one; a difference turns this off.
 */
public final class JigsawLists {
   private static volatile boolean disabled;
   private static final LongAdder CALLS = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();

   private JigsawLists() {
   }

   /** A structure template's lists, one per rotation (StructureTemplateMixin; dropped when the template is loaded or filled again). */
   public interface Template {
      List<?>[] wgaccel$jigsaws();

      void wgaccel$jigsaws(List<?>[] lists);
   }

   /**
    * The template's jigsaw blocks at position for rotation, as original makes them (unmodifiable: the caller copies it
    * before shuffling); null when this is off or the position is not zero, and then the caller calls original itself.
    */
   @SuppressWarnings("unchecked")
   public static <T> List<T> get(Object template, BlockPos position, Rotation rotation, Supplier<List<T>> original) {
      if (disabled || !BlockPos.ZERO.equals(position) || !Config.FAST_JIGSAW.get()) {
         return null;
      }

      Template cache = (Template)template;
      List<?>[] lists = cache.wgaccel$jigsaws();
      if (lists == null) {
         // Two threads racing here each make an array; the one kept loses nothing but a list made again.
         lists = new List<?>[Rotation.values().length];
         cache.wgaccel$jigsaws(lists);
      }

      int index = rotation.ordinal();
      List<T> list = (List<T>)lists[index];
      if (list == null) {
         // List.copyOf is immutable with final fields (and its elements are records): safe to share without a lock.
         list = List.copyOf(original.get());
         lists[index] = list;
         return list;
      }

      if (sample()) {
         return verify(list, original.get());
      }

      return list;
   }

   /** Whether this list is one of the compared ones (about 1 in 256, per thread: no shared counter per call). */
   private static boolean sample() {
      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(256) != 0) {
         return false;
      }

      CALLS.add(256);
      return true;
   }

   /** A compared list: the newly made one is used; a difference turns this off. */
   private static <T> List<T> verify(List<T> kept, List<T> real) {
      CHECKED.increment();
      if (!kept.equals(real)) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA fast jigsaw: a kept jigsaw list differs from the game's; off, the game makes them again");
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 4096 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }

      return real;
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA fast jigsaw: %,d lists, checked %,d: %s", CALLS.sum(), CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
