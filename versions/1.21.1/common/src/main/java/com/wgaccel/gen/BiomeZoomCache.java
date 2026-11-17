package com.wgaccel.gen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;

/** Pure corner jitter only: no biome result, mutable world state or RNG object is cached. */
public final class BiomeZoomCache {
   private static final int SLOTS = 256;
   private static final ThreadLocal<Cache> LOCAL = ThreadLocal.withInitial(Cache::new);
   private static volatile boolean disabled;
   private BiomeZoomCache() {}

   private static final class Cache {
      final long[] seeds = new long[SLOTS];
      final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
      final boolean[] valid = new boolean[SLOTS];
      final double[] jitterX = new double[SLOTS], jitterY = new double[SLOTS], jitterZ = new double[SLOTS];
      long turn;

      double distance(long seed, int x, int y, int z, double dx, double dy, double dz) {
         int hash = x * 0x9e3779b9 ^ Integer.rotateLeft(y * 0x85ebca6b, 11) ^ Integer.rotateLeft(z * 0xc2b2ae35, 22)
            ^ (int)seed ^ (int)(seed >>> 32);
         int slot = (hash ^ hash >>> 16) & (SLOTS - 1);
         if (!this.valid[slot] || this.seeds[slot] != seed || this.xs[slot] != x || this.ys[slot] != y || this.zs[slot] != z) {
            long mixed = seed;
            mixed = LinearCongruentialGenerator.next(mixed, x);
            mixed = LinearCongruentialGenerator.next(mixed, y);
            mixed = LinearCongruentialGenerator.next(mixed, z);
            mixed = LinearCongruentialGenerator.next(mixed, x);
            mixed = LinearCongruentialGenerator.next(mixed, y);
            mixed = LinearCongruentialGenerator.next(mixed, z);
            this.jitterX[slot] = fiddle(mixed);
            mixed = LinearCongruentialGenerator.next(mixed, seed);
            this.jitterY[slot] = fiddle(mixed);
            mixed = LinearCongruentialGenerator.next(mixed, seed);
            this.jitterZ[slot] = fiddle(mixed);
            this.seeds[slot] = seed; this.xs[slot] = x; this.ys[slot] = y; this.zs[slot] = z; this.valid[slot] = true;
         }
         // Minecraft adds Z squared, Y squared, X squared in this order. Do not reassociate or use a float cache.
         return Mth.square(dz + this.jitterZ[slot]) + Mth.square(dy + this.jitterY[slot]) + Mth.square(dx + this.jitterX[slot]);
      }
   }

   private static double fiddle(long seed) { return (Math.floorMod(seed >> 24, 1024) / 1024.0D - 0.5D) * 0.9D; }

   /** Public for the independent lab comparison against Minecraft's own private distance method. */
   public static double distance(long seed, int x, int y, int z, double dx, double dy, double dz) {
      return LOCAL.get().distance(seed, x, y, z, dx, dy, dz);
   }

   public static double checked(long seed, int x, int y, int z, double dx, double dy, double dz, Operation<Double> original) {
      if (disabled) return original.call(seed, x, y, z, dx, dy, dz);
      Cache cache = LOCAL.get();
      double value = cache.distance(seed, x, y, z, dx, dy, dz);
      long turn = cache.turn++;
      if (turn < 64 || (turn & 1023) == 0) {
         double expected = original.call(seed, x, y, z, dx, dy, dz);
         if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(value)) {
            disabled = true;
            com.wgaccel.WgaChat.warnOff("WGA biome zoom cache off: corner distance differs from Minecraft");
         }
         return expected;
      }
      return value;
   }
}
