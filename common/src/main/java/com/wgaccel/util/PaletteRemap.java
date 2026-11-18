package com.wgaccel.util;

import java.util.function.IntUnaryOperator;

/** Reencode local palette IDs, preserving first-appearance order. Large/global palettes keep the caller's path. */
public final class PaletteRemap {
   private PaletteRemap() {}
   public static boolean swap(int[] values, IntUnaryOperator convert) {
      int max = -1;
      for (int id : values) {
         if (id < 0 || id >= 4096) return false;
         max = Math.max(max, id);
      }
      int[] known = new int[max + 1];
      for (int i = 0; i < values.length; i++) {
         int id = values[i];
         int mapped = known[id];
         if (mapped == 0) known[id] = mapped = convert.applyAsInt(id) + 1;
         values[i] = mapped - 1;
      }
      return true;
   }
}
