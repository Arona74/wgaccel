package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;

/**
 * Chunk saving's PalettedContainer.pack: its reencodeContents numbers a section's values anew in the order they are
 * first met, asking the new palette (a hash map) again at every change of id along the section (31 Oct 2026 profile,
 * vanilla 26.1.2: pack was 74% of encoding a chunk, 3.3% of the CPU). Here each old id is asked once and remembered in
 * an array: the new palette gets the same values in the same order (asking it again for a value it has changes
 * nothing), and every block the same number. Palettes too big for an array (the global one) take vanilla's loop. Every
 * 256th section's numbers are checked against the finished palette's own answers.
 */
public final class FastPack {
   private static final int MAX_PALETTE = 4096;
   private static volatile boolean disabled;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final ThreadLocal<int[]> TURN = ThreadLocal.withInitial(() -> new int[1]);

   private FastPack() {
   }

   /** PalettedContainer.reencodeContents. */
   public static <T> int[] reencode(BitStorage storage, Palette<T> oldPalette, Palette<T> newPalette) {
      int[] buffer = new int[storage.getSize()];
      storage.unpack(buffer);
      PaletteResize<T> dummy = PaletteResize.noResizeExpected();
      int size = oldPalette.getSize();
      if (disabled || size > MAX_PALETTE || !Config.FAST_CHUNK_PACK.get()) {
         vanilla(buffer, oldPalette, newPalette, dummy);
         return buffer;
      }

      int[] numbers = new int[size];
      java.util.Arrays.fill(numbers, -1);
      int[] original = (++TURN.get()[0] & 255) == 0 ? buffer.clone() : null;
      for (int index = 0; index < buffer.length; index++) {
         int id = buffer[index];
         // An id past the palette: vanilla's valueFor throws for it, so it is asked the same way.
         int number = id >= 0 && id < size ? numbers[id] : -1;
         if (number < 0) {
            number = newPalette.idFor(oldPalette.valueFor(id), dummy);
            if (id >= 0 && id < size) {
               numbers[id] = number;
            }
         }

         buffer[index] = number;
      }

      if (original != null) {
         check(original, buffer, oldPalette, newPalette, dummy);
      }

      return buffer;
   }

   /** Vanilla's loop, as it is. */
   private static <T> void vanilla(int[] buffer, Palette<T> oldPalette, Palette<T> newPalette, PaletteResize<T> dummy) {
      int lastReadId = -1;
      int lastWrittenId = -1;
      for (int index = 0; index < buffer.length; index++) {
         int id = buffer[index];
         if (id != lastReadId) {
            lastReadId = id;
            lastWrittenId = newPalette.idFor(oldPalette.valueFor(id), dummy);
         }

         buffer[index] = lastWrittenId;
      }
   }

   /** Each block's number against the finished palette's answer for its value (the palette has them all by now: asking adds nothing). */
   private static <T> void check(int[] original, int[] numbered, Palette<T> oldPalette, Palette<T> newPalette, PaletteResize<T> dummy) {
      CHECKED.increment();
      int differences = 0;
      for (int index = 0; index < original.length; index++) {
         differences += newPalette.idFor(oldPalette.valueFor(original[index]), dummy) != numbered[index] ? 1 : 0;
      }

      if (differences != 0) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA fast chunk pack: {} of {} blocks numbered unlike the palette's own answers; off, sections are packed vanilla's way",
               differences, original.length);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 4096 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA fast chunk pack: about %,d sections packed, checked %,d: %s", CHECKED.sum() * 256, CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
