package com.wgaccel.gen;

import com.mojang.datafixers.util.Pair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * FreeTerraForged's CandidateFit.find without its full scan (config {@code fasterBiomeChoice}): the closest table entry
 * of the chosen biome (ultimate) and the closest of any other biome (penultimate), by FTF's fitness (the squared
 * parameter distances plus the squared offset), each the first in table order among equal distances, as the scan's
 * strict {@code <} gives. Biolith's sub-biome criteria ask it for nearly every cell when a mod adds sub-biomes (No Man's
 * Land 1.6.2, 7,593 points: 34 us a cell, 56% of the chunk's work). Trees of bounding boxes, made once per table: one per
 * biome for the ultimate, one over the whole table for the penultimate (its nodes skipped when they hold only the chosen
 * biome). A node is skipped when its lower bound is above the best so far, or equal and its first entry comes later.
 * Every 4,096th answer is checked against the scan ({@code gradlew candidateTreeCheck} checks it on random tables).
 */
public final class CandidateTree {
   private static final int LEAF = 8;
   private static final long CHECK_EVERY = 4096;
   private static final AtomicLong CALLS = new AtomicLong();
   private static final Map<List<?>, CandidateTree> TREES = new IdentityHashMap<>();
   private static volatile List<?> lastList;
   private static volatile CandidateTree lastTree;

   /** Per entry: the 6 parameters' min and max (temperature, humidity, continentalness, erosion, depth, weirdness). */
   private final long[] lo;
   private final long[] hi;
   private final long[] offsetSquared;
   private final int[] biome;
   private final Object[] points;
   private final Object[] holders;
   private final Map<Holder<Biome>, Integer> ids = new HashMap<>();
   private final Nodes all;
   private final Nodes[] byBiome;

   private CandidateTree(List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries) {
      int n = entries.size();
      this.lo = new long[n * 6];
      this.hi = new long[n * 6];
      this.offsetSquared = new long[n];
      this.biome = new int[n];
      this.points = new Object[n];
      this.holders = new Object[n];
      int[] counts = new int[n];
      for (int i = 0; i < n; i++) {
         Pair<Climate.ParameterPoint, Holder<Biome>> entry = entries.get(i);
         Climate.ParameterPoint point = entry.getFirst();
         Climate.Parameter[] parameters = {point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.depth(), point.weirdness()};
         for (int d = 0; d < 6; d++) {
            this.lo[i * 6 + d] = parameters[d].min();
            this.hi[i * 6 + d] = parameters[d].max();
         }

         this.offsetSquared[i] = point.offset() * point.offset();
         this.biome[i] = this.ids.computeIfAbsent(entry.getSecond(), key -> this.ids.size());
         counts[this.biome[i]]++;
         this.points[i] = point;
         this.holders[i] = entry.getSecond();
      }

      int[] every = new int[n];
      for (int i = 0; i < n; i++) {
         every[i] = i;
      }

      this.all = new Nodes(this, every);
      this.byBiome = new Nodes[this.ids.size()];
      int[][] members = new int[this.ids.size()][];
      int[] filled = new int[this.ids.size()];
      for (int b = 0; b < members.length; b++) {
         members[b] = new int[counts[b]];
      }

      for (int i = 0; i < n; i++) {
         members[this.biome[i]][filled[this.biome[i]]++] = i;
      }

      for (int b = 0; b < members.length; b++) {
         this.byBiome[b] = new Nodes(this, members[b]);
      }
   }

   /** A tree over some entries: node ranges [start, end) of order, bounds, the first table index under each, biomes. */
   private static final class Nodes {
      final CandidateTree table;
      final int[] order;
      final int words;
      int[] start = new int[64];
      int[] end = new int[64];
      int[] left = new int[64];
      int[] right = new int[64];
      long[] boxLo = new long[64 * 6];
      long[] boxHi = new long[64 * 6];
      long[] boxOffset = new long[64];
      int[] firstIndex = new int[64];
      long[] bits;
      int count;

      Nodes(CandidateTree table, int[] order) {
         this.table = table;
         this.order = order;
         this.words = Math.max(1, (table.ids.size() + 63) / 64);
         this.bits = new long[64 * this.words];
         if (order.length > 0) {
            this.build(0, order.length);
         }
      }

      private int build(int from, int to) {
         int node = this.count++;
         if (node == this.start.length) {
            int size = node * 2;
            this.start = Arrays.copyOf(this.start, size);
            this.end = Arrays.copyOf(this.end, size);
            this.left = Arrays.copyOf(this.left, size);
            this.right = Arrays.copyOf(this.right, size);
            this.boxLo = Arrays.copyOf(this.boxLo, size * 6);
            this.boxHi = Arrays.copyOf(this.boxHi, size * 6);
            this.boxOffset = Arrays.copyOf(this.boxOffset, size);
            this.firstIndex = Arrays.copyOf(this.firstIndex, size);
            this.bits = Arrays.copyOf(this.bits, size * this.words);
         }

         long[] lo = this.table.lo;
         long[] hi = this.table.hi;
         this.start[node] = from;
         this.end[node] = to;
         this.left[node] = -1;
         this.right[node] = -1;
         long offset = Long.MAX_VALUE;
         int first = Integer.MAX_VALUE;
         for (int d = 0; d < 6; d++) {
            this.boxLo[node * 6 + d] = Long.MAX_VALUE;
            this.boxHi[node * 6 + d] = Long.MIN_VALUE;
         }

         for (int k = from; k < to; k++) {
            int i = this.order[k];
            for (int d = 0; d < 6; d++) {
               this.boxLo[node * 6 + d] = Math.min(this.boxLo[node * 6 + d], lo[i * 6 + d]);
               this.boxHi[node * 6 + d] = Math.max(this.boxHi[node * 6 + d], hi[i * 6 + d]);
            }

            offset = Math.min(offset, this.table.offsetSquared[i]);
            first = Math.min(first, i);
            int b = this.table.biome[i];
            this.bits[node * this.words + (b >> 6)] |= 1L << (b & 63);
         }

         this.boxOffset[node] = offset;
         this.firstIndex[node] = first;
         if (to - from <= LEAF) {
            return node;
         }

         // Split at the median entry centre of the dimension whose centres spread widest.
         int widest = 0;
         long spread = -1L;
         for (int d = 0; d < 6; d++) {
            long low = Long.MAX_VALUE;
            long high = Long.MIN_VALUE;
            for (int k = from; k < to; k++) {
               int i = this.order[k];
               long centre = lo[i * 6 + d] + hi[i * 6 + d];
               low = Math.min(low, centre);
               high = Math.max(high, centre);
            }

            if (high - low > spread) {
               spread = high - low;
               widest = d;
            }
         }

         int dimension = widest;
         Integer[] part = new Integer[to - from];
         for (int k = from; k < to; k++) {
            part[k - from] = this.order[k];
         }

         Arrays.sort(part, (a, b) -> Long.compare(lo[a * 6 + dimension] + hi[a * 6 + dimension], lo[b * 6 + dimension] + hi[b * 6 + dimension]));
         for (int k = from; k < to; k++) {
            this.order[k] = part[k - from];
         }

         int middle = (from + to) >>> 1;
         int l = this.build(from, middle);
         int r = this.build(middle, to);
         this.left[node] = l;
         this.right[node] = r;
         return node;
      }

      private long bound(int node, long[] target) {
         long sum = this.boxOffset[node];
         for (int d = 0; d < 6; d++) {
            long v = distance(target[d], this.boxLo[node * 6 + d], this.boxHi[node * 6 + d]);
            sum += v * v;
         }

         return sum;
      }

      private boolean only(int node, int id) {
         for (int w = 0; w < this.words; w++) {
            long expected = w == id >> 6 ? 1L << (id & 63) : 0L;
            if (this.bits[node * this.words + w] != expected) {
               return false;
            }
         }

         return true;
      }

      /** best[0] distance, best[1] table index (-1: none yet); entries of biome skip (-1: none) left out. */
      void search(int node, long bound, long[] target, int skip, long[] best) {
         if (skip >= 0 && this.only(node, skip)) {
            return;
         }

         if (best[1] >= 0L && (bound > best[0] || bound == best[0] && this.firstIndex[node] > best[1])) {
            return;
         }

         if (this.left[node] < 0) {
            for (int k = this.start[node]; k < this.end[node]; k++) {
               int i = this.order[k];
               if (this.table.biome[i] == skip) {
                  continue;
               }

               long d = this.table.fitness(i, target);
               if (best[1] < 0L || d < best[0] || d == best[0] && i < best[1]) {
                  best[0] = d;
                  best[1] = i;
               }
            }

            return;
         }

         int a = this.left[node];
         int b = this.right[node];
         long boundA = this.bound(a, target);
         long boundB = this.bound(b, target);
         if (boundB < boundA || boundB == boundA && this.firstIndex[b] < this.firstIndex[a]) {
            this.search(b, boundB, target, skip, best);
            this.search(a, boundA, target, skip, best);
         } else {
            this.search(a, boundA, target, skip, best);
            this.search(b, boundB, target, skip, best);
         }
      }

      long[] find(long[] target, int skip) {
         long[] best = {0L, -1L};
         if (this.order.length > 0) {
            this.search(0, this.bound(0, target), target, skip, best);
         }

         return best;
      }
   }

   private static long distance(long target, long min, long max) {
      long above = target - max;
      long below = min - target;
      return above > 0L ? above : Math.max(below, 0L);
   }

   private long fitness(int i, long[] target) {
      long sum = 0L;
      for (int d = 0; d < 6; d++) {
         long v = distance(target[d], this.lo[i * 6 + d], this.hi[i * 6 + d]);
         sum += v * v;
      }

      return sum + this.offsetSquared[i];
   }

   /** The tree of this table (made on first use). */
   public static CandidateTree of(List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries) {
      if (lastList == entries) {
         CandidateTree tree = lastTree;
         if (tree != null && lastList == entries) {
            return tree;
         }
      }

      CandidateTree tree;
      synchronized (TREES) {
         tree = TREES.get(entries);
         if (tree == null) {
            if (TREES.size() > 16) {
               TREES.clear();
            }

            tree = new CandidateTree(entries);
            TREES.put(entries, tree);
         }
      }

      lastTree = tree;
      lastList = entries;
      return tree;
   }

   /** Whether this call should be checked against the scan. */
   public static boolean checkNow() {
      return CALLS.incrementAndGet() % CHECK_EVERY == 0L;
   }

   /**
    * {ultimate index, ultimate distance, penultimate index (-1: none), penultimate distance}, or null when the chosen
    * biome is not in the table (the scan then throws, as it should).
    */
   public long[] find(Climate.TargetPoint target, Holder<Biome> selected) {
      Integer id = this.ids.get(selected);
      if (id == null) {
         return null;
      }

      long[] t = {target.temperature(), target.humidity(), target.continentalness(), target.erosion(), target.depth(), target.weirdness()};
      long[] ultimate = this.byBiome[id].find(t, -1);
      long[] penultimate = this.all.find(t, id);
      return new long[] {ultimate[1], ultimate[0], penultimate[1], penultimate[0]};
   }

   public Climate.ParameterPoint point(int index) {
      return (Climate.ParameterPoint)this.points[index];
   }

   @SuppressWarnings("unchecked")
   public Holder<Biome> holder(int index) {
      return (Holder<Biome>)this.holders[index];
   }
}
