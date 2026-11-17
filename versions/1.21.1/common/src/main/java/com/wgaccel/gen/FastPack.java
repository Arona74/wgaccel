package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.util.PaletteRemap;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/** 1.21.1's saved section palette: call its original converter once per old local ID, in original encounter order. */
public final class FastPack {
   private static final AtomicLong APPLIED = new AtomicLong();
   private static volatile boolean disabled;
   private FastPack() {}

   public static boolean swap(int[] values, IntUnaryOperator convert) {
      if (disabled || !Config.FAST_CHUNK_PACK.get()) return false;
      long n = APPLIED.getAndIncrement();
      int[] original = n < 64 || (n & 255) == 0 ? values.clone() : null;
      if (!PaletteRemap.swap(values, convert)) return false;
      if (original != null) {
         int previous = -1, mapped = -1;
         for (int i = 0; i < original.length; i++) {
            int id = original[i];
            if (id != previous) { previous = id; mapped = convert.applyAsInt(id); }
            original[i] = mapped;
         }
         if (!Arrays.equals(values, original)) {
            disabled = true;
            System.arraycopy(original, 0, values, 0, values.length);
            com.wgaccel.WgaChat.warnOff("WGA fast 1.21.1 chunk pack off: saved palette IDs differ from the original converter");
         } else if (n == 0) WorldgenAccelerator.LOG.info("WGA fast 1.21.1 chunk pack: first local palette checked IDENTICAL; first 64 and every 256th checked");
      }
      return true;
   }
}
