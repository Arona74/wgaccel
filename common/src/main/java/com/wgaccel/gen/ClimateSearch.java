package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.biome.Climate;

/**
 * A biome table's search (Climate.ParameterList.findValueIndex) on a flat copy of its own R-tree (28 Oct 2026 profile:
 * the R-tree search was 10.3% of the CPU in vanilla 26.1.2, a pointer chase through node and Parameter objects with a
 * new long[7] per call). The copy has each node's seven bounds side by side in one int[] and each node's children
 * numbered one after another; the search is vanilla's (the children in their order, the same strict comparisons, the
 * same running best), starts from vanilla's own last answer on this thread and leaves its answer there, so ties come
 * out as vanilla's and vanilla's search, when it runs in between, sees the same last answer. A distance stops adding
 * once it reaches the best so far: vanilla then skips that node anyway (exact while the sums cannot overflow, so only
 * tables and targets within 2^28 are searched here). Every 256th answer is compared with vanilla's search; a
 * difference turns this off.
 */
public final class ClimateSearch {
   private static final long LIMIT = 1L << 28;
   private static volatile boolean disabled;
   private static final LongAdder CHECKED = new LongAdder();
   private static final AtomicLong CHECK_COUNT = new AtomicLong();
   private static final LongAdder DIFFERENT = new LongAdder();
   /**
    * Answers asked, real searches and who asked for them (every 4096th sampled), for the summary. 19 Nov 2026, FTF +
    * Regions Unexplored + Oh The Biomes We've Gone (9,112-entry table): 63% of 100M answers needed a search (FTF's
    * provider selection and underground banding: each cell below the surface has its own depth), and only 0.6% of those
    * searches were of a target this thread had searched before: no cache can help there.
    */
   private static final LongAdder ASKED = new LongAdder();
   private static final LongAdder SEARCHED = new LongAdder();
   private static final java.util.concurrent.ConcurrentHashMap<String, LongAdder> SEARCH_CALLERS = new java.util.concurrent.ConcurrentHashMap<>();
   private static final StackWalker WALKER = StackWalker.getInstance();
   /** A table that cannot be copied (another node class, bounds too wide): searched by vanilla. */
   public static final ClimateSearch NONE = new ClimateSearch();
   private final int[] bounds;
   private final int[] first;
   private final int[] count;
   private final Object[] leaves;
   private final Object[] values;
   private final Map<Object, Integer> leafNodes;
   private final ThreadLocal<Object> lastResult;
   private final ThreadLocal<State> state;
   private final int globalMinDepth;
   private final int globalMaxDepth;
   private final boolean allSameMaxDepth;
   private final int uniformMinDepth;
   private final int uniformMaxDepth;

   /** Per thread: the last answer's node (matched with vanilla's last answer), the target and the running best. */
   private static final class State {
      static final int CACHE_SIZE = 1024;
      static final int CACHE_MASK = CACHE_SIZE - 1;
      static final int COL_SIZE = 64;
      static final int COL_MASK = COL_SIZE - 1;
      static final int MRU_SIZE = 16;
      static final int MRU_MASK = MRU_SIZE - 1;
      Object lastLeaf;
      int lastNode = -1;
      long t0, t1, t2, t3, t4, t5;
      int best;
      long bestDistance;
      boolean zeroExact;
      long zeroMinT4, zeroMaxT4;
      long safeLo, safeHi;
      Object compared;
      int calls;
      int searches;
      final Object[] cLast = new Object[CACHE_SIZE];
      final long[] ct0 = new long[CACHE_SIZE];
      final long[] ct1 = new long[CACHE_SIZE];
      final long[] ct2 = new long[CACHE_SIZE];
      final long[] ct3 = new long[CACHE_SIZE];
      final long[] ct4 = new long[CACHE_SIZE];
      final long[] ct5 = new long[CACHE_SIZE];
      final int[] cNode = new int[CACHE_SIZE];
      final long[] colT0 = new long[COL_SIZE];
      final long[] colT1 = new long[COL_SIZE];
      final long[] colT2 = new long[COL_SIZE];
      final long[] colT3 = new long[COL_SIZE];
      final long[] colT5 = new long[COL_SIZE];
      final long[] colMaxT4 = new long[COL_SIZE];
      final int[] colNode = new int[COL_SIZE];
      final Object[] colLeaf = new Object[COL_SIZE];
      final Object[] colOtherLast = new Object[COL_SIZE];
      final long[] mruT0 = new long[MRU_SIZE];
      final long[] mruT1 = new long[MRU_SIZE];
      final long[] mruT2 = new long[MRU_SIZE];
      final long[] mruT3 = new long[MRU_SIZE];
      final long[] mruT5 = new long[MRU_SIZE];
      final long[] mruMinT4 = new long[MRU_SIZE];
      final long[] mruMaxT4 = new long[MRU_SIZE];
      final int[] mruNode = new int[MRU_SIZE];
      final Object[] mruLeaf = new Object[MRU_SIZE];
      final Object[] mruOtherLast = new Object[MRU_SIZE];

      State() {
         java.util.Arrays.fill(this.cNode, -1);
         java.util.Arrays.fill(this.colNode, -1);
         java.util.Arrays.fill(this.mruNode, -1);
      }
   }

   private ClimateSearch() {
      this.bounds = null;
      this.first = null;
      this.count = null;
      this.leaves = null;
      this.values = null;
      this.leafNodes = null;
      this.lastResult = null;
      this.state = null;
      this.globalMinDepth = Integer.MIN_VALUE;
      this.globalMaxDepth = Integer.MAX_VALUE;
      this.allSameMaxDepth = false;
      this.uniformMinDepth = Integer.MAX_VALUE;
      this.uniformMaxDepth = Integer.MIN_VALUE;
   }

   private ClimateSearch(int[] bounds, int[] first, int[] count, Object[] leaves, Object[] values, Map<Object, Integer> leafNodes, ThreadLocal<Object> lastResult) {
      this.bounds = bounds;
      this.first = first;
      this.count = count;
      this.leaves = leaves;
      this.values = values;
      this.leafNodes = leafNodes;
      this.lastResult = lastResult;
      this.state = ThreadLocal.withInitial(State::new);
      int minDepth = Integer.MAX_VALUE;
      int maxMinDepth = Integer.MIN_VALUE;
      int minMaxDepth = Integer.MAX_VALUE;
      int maxMaxDepth = Integer.MIN_VALUE;
      for (int i = 0; i < count.length; i++) {
         if (count[i] == 0) {
            int lo = bounds[i * 14 + 8];
            int hi = bounds[i * 14 + 9];
            minDepth = Math.min(minDepth, lo);
            maxMinDepth = Math.max(maxMinDepth, lo);
            minMaxDepth = Math.min(minMaxDepth, hi);
            maxMaxDepth = Math.max(maxMaxDepth, hi);
         }
      }
      this.globalMinDepth = minDepth;
      this.globalMaxDepth = maxMaxDepth;
      this.allSameMaxDepth = minMaxDepth == maxMaxDepth;
      this.uniformMinDepth = maxMinDepth;
      this.uniformMaxDepth = minMaxDepth;
   }

   public static boolean on() {
      return !disabled && Config.FAST_CLIMATE_SEARCH.get();
   }

   /** The flat copy of this table's R-tree, or {@link #NONE}. */
   @SuppressWarnings("unchecked")
   public static ClimateSearch of(Climate.ParameterList<?> table) {
      try {
         Object index = field(table, "index");
         Object root = field(index, "root");
         if (!Names.simpleName(root.getClass()).equals("SubTree")) {
            return NONE;
         }

         // Breadth first, so each node's children are contiguous; the root is node 0.
         List<Object> nodes = new ArrayList<>();
         List<int[]> ranges = new ArrayList<>();
         nodes.add(root);
         for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            String kind = Names.simpleName(node.getClass());
            if (kind.equals("SubTree")) {
               Object children = field(node, "children");
               int n = Array.getLength(children);
               ranges.add(new int[] {nodes.size(), n});
               for (int c = 0; c < n; c++) {
                  nodes.add(Array.get(children, c));
               }
            } else if (kind.equals("Leaf")) {
               ranges.add(new int[] {0, 0});
            } else {
               return NONE;
            }
         }

         int n = nodes.size();
         int[] bounds = new int[n * 14];
         int[] first = new int[n];
         int[] count = new int[n];
         Object[] leaves = new Object[n];
         Object[] values = new Object[n];
         Map<Object, Integer> leafNodes = new IdentityHashMap<>();
         for (int i = 0; i < n; i++) {
            Object node = nodes.get(i);
            Object[] space = (Object[])field(node, "parameterSpace");
            if (space.length != 7) {
               return NONE;
            }

            for (int k = 0; k < 7; k++) {
               Climate.Parameter parameter = (Climate.Parameter)space[k];
               if (Math.abs(parameter.min()) >= LIMIT || Math.abs(parameter.max()) >= LIMIT) {
                  return NONE;
               }

               bounds[i * 14 + k * 2] = (int)parameter.min();
               bounds[i * 14 + k * 2 + 1] = (int)parameter.max();
            }

            first[i] = ranges.get(i)[0];
            count[i] = ranges.get(i)[1];
            if (count[i] == 0) {
               leaves[i] = node;
               values[i] = field(node, "value");
               leafNodes.put(node, i);
            }
         }

         return new ClimateSearch(bounds, first, count, leaves, values, leafNodes, (ThreadLocal<Object>)field(index, "lastResult"));
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.info("WGA climate search: a biome table stays on vanilla's search ({})", failure.toString());
         return NONE;
      }
   }

   /** Returns the vanilla RTree's per-thread last-result holder for tie-breaking preservation, or null if unavailable. */
   @SuppressWarnings("unchecked")
   public static ThreadLocal<Object> lastResultOf(Climate.ParameterList<?> table) {
      try {
         Object index = field(table, "index");
         return (ThreadLocal<Object>)field(index, "lastResult");
      } catch (ReflectiveOperationException | RuntimeException failure) {
         return null;
      }
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
         try {
            Field field = Names.declaredField(type, name);
            field.setAccessible(true);
            return field.get(target);
         } catch (NoSuchFieldException ignored) {
         }
      }

      throw new NoSuchFieldException(Names.className(target.getClass()) + "." + name);
   }

   private static boolean fits(long value) {
      return value > -LIMIT && value < LIMIT;
   }

   private static long axis(long target, int min, int max) {
      // Climate.Parameter.distance(long), squared.
      long above = target - max;
      long below = min - target;
      long d = above > 0L ? above : Math.max(below, 0L);
      return d * d;
   }

   /** Node.distance(target) (the seventh axis, the offset, against 0), or some value at least {@code limit} once it gets there. */
   private long distance(State s, int node, long limit) {
      int[] b = this.bounds;
      int at = node * 14;
      long d = axis(s.t0, b[at], b[at + 1]) + axis(s.t1, b[at + 2], b[at + 3]);
      if (d >= limit) {
         return d;
      }

      d += axis(s.t2, b[at + 4], b[at + 5]) + axis(s.t3, b[at + 6], b[at + 7]);
      if (d >= limit) {
         return d;
      }

      return d + axis(s.t4, b[at + 8], b[at + 9]) + axis(s.t5, b[at + 10], b[at + 11]) + axis(0L, b[at + 12], b[at + 13]);
   }

   private void clampDepth(State s, int node, long dh, long bestDist) {
      if (dh >= bestDist) {
         return;
      }

      int[] b = this.bounds;
      int at = node * 14;
      int minT4 = b[at + 8];
      int maxT4 = b[at + 9];
      if (s.t4 < minT4) {
         long gap = (long)Math.sqrt((double)(bestDist - dh)) + 1L;
         long hi = minT4 - gap;
         if (hi < s.safeHi) {
            s.safeHi = hi;
         }
      } else if (s.t4 > maxT4) {
         long gap = (long)Math.sqrt((double)(bestDist - dh)) + 1L;
         long lo = maxT4 + gap;
         if (lo > s.safeLo) {
            s.safeLo = lo;
         }
      } else {
         s.safeLo = s.t4;
         s.safeHi = s.t4;
      }
   }

   /** SubTree.search over this node's children, the running best in the state, tracking exact valid depth interval. */
   private void search(State s, int node) {
      int[] b = this.bounds;
      int start = this.first[node];
      int end = start + this.count[node];
      for (int child = start; child < end; child++) {
         int at = child * 14;
         long dh = axis(s.t0, b[at], b[at + 1]) + axis(s.t1, b[at + 2], b[at + 3]);
         if (dh >= s.bestDistance) {
            continue;
         }
         dh += axis(s.t2, b[at + 4], b[at + 5]) + axis(s.t3, b[at + 6], b[at + 7]);
         if (dh >= s.bestDistance) {
            continue;
         }
         dh += axis(s.t5, b[at + 10], b[at + 11]) + axis(0L, b[at + 12], b[at + 13]);
         if (dh >= s.bestDistance) {
            continue;
         }

         long d = dh + axis(s.t4, b[at + 8], b[at + 9]);
         if (s.bestDistance > d) {
            if (this.count[child] == 0) {
               if (s.best >= 0 && s.safeLo <= s.safeHi) {
                  int pa = s.best * 14;
                  long oldDh = axis(s.t0, b[pa], b[pa + 1]) + axis(s.t1, b[pa + 2], b[pa + 3])
                     + axis(s.t2, b[pa + 4], b[pa + 5]) + axis(s.t3, b[pa + 6], b[pa + 7])
                     + axis(s.t5, b[pa + 10], b[pa + 11]) + axis(0L, b[pa + 12], b[pa + 13]);
                  clampDepth(s, s.best, oldDh, d);
               }
               s.bestDistance = d;
               s.best = child;
            } else {
               search(s, child);
            }
         } else if (s.safeLo <= s.safeHi) {
            clampDepth(s, child, dh, s.bestDistance);
         }
      }
   }

   /**
    * The table's answer for this target, or null when vanilla's search should run (a target too far out, a compared
    * call: {@link #compare} then checks vanilla's answer against this one).
    */
   public Object find(Climate.TargetPoint target) {
      State s = this.state.get();
      s.compared = null;
      ASKED.increment();
      long t0 = target.temperature(), t1 = target.humidity(), t2 = target.continentalness(), t3 = target.erosion(), t4 = target.depth(), t5 = target.weirdness();
      if (!fits(t0) || !fits(t1) || !fits(t2) || !fits(t3) || !fits(t4) || !fits(t5)) {
         return null;
      }

      Object last = this.lastResult.get();
      if (s.lastNode >= 0 && last == s.lastLeaf && t0 == s.t0 && t1 == s.t1 && t2 == s.t2 && t3 == s.t3 && t5 == s.t5
            && (t4 == s.t4 || (s.zeroExact && t4 >= s.zeroMinT4 && t4 <= s.zeroMaxT4))) {
         s.t4 = t4;
         if ((++s.calls & 255) == 0) {
            // Compared: vanilla searches from the same last answer and sets it itself.
            s.compared = this.values[s.lastNode];
            return null;
         }

         return this.values[s.lastNode];
      }

      int cSlot = -1;
      long colHash = t0 * 0x9E3779B97F4A7C15L ^ t1 * 0xBF58476D1CE4E5B9L ^ t2 * 0x94D049BB133111EBL
            ^ t3 * 0xD6E8FEB86659FD93L ^ t5 * 0xE7037ED1A0B428DBL;
      boolean inUniform = t4 >= this.uniformMinDepth && t4 <= this.uniformMaxDepth;
      boolean inGlobalMax = !inUniform && this.allSameMaxDepth && t4 >= this.globalMaxDepth;
      boolean inGlobalMin = !inUniform && !inGlobalMax && t4 <= this.globalMinDepth;
      if (inUniform || inGlobalMin || inGlobalMax) {
         cSlot = (int)(colHash ^ (colHash >>> 32)) & State.COL_MASK;
         int colNode = s.colNode[cSlot];
         long maxT4 = s.colMaxT4[cSlot];
         boolean depthMatch = inUniform ? maxT4 == Long.MAX_VALUE
               : (inGlobalMax ? maxT4 == Long.MAX_VALUE - 1L : (maxT4 < Long.MAX_VALUE - 1L && t4 <= maxT4));
         if (colNode >= 0 && depthMatch
               && s.colT0[cSlot] == t0 && s.colT1[cSlot] == t1 && s.colT2[cSlot] == t2
               && s.colT3[cSlot] == t3 && s.colT5[cSlot] == t5
               && (last == s.colLeaf[cSlot] || last == s.colOtherLast[cSlot])) {
            Object leaf = s.colLeaf[cSlot];
            s.lastLeaf = leaf;
            s.lastNode = colNode;
            if (inUniform) {
               s.zeroExact = true;
               s.zeroMinT4 = this.uniformMinDepth;
               s.zeroMaxT4 = this.uniformMaxDepth;
            } else if (inGlobalMax) {
               s.zeroExact = true;
               s.zeroMinT4 = this.globalMaxDepth;
               s.zeroMaxT4 = Integer.MAX_VALUE;
            } else {
               s.zeroExact = false;
            }
            s.t0 = t0;
            s.t1 = t1;
            s.t2 = t2;
            s.t3 = t3;
            s.t4 = t4;
            s.t5 = t5;
            if ((++s.calls & 255) == 0) {
               s.compared = this.values[colNode];
               return null;
            }

            if (last != leaf) {
               this.lastResult.set(leaf);
            }

            return this.values[colNode];
         }
      }

      int mSlot = (int)(colHash ^ (colHash >>> 32)) & State.MRU_MASK;
      int mNode = s.mruNode[mSlot];
      if (mNode >= 0 && t4 >= s.mruMinT4[mSlot] && t4 <= s.mruMaxT4[mSlot]
            && s.mruT0[mSlot] == t0 && s.mruT1[mSlot] == t1 && s.mruT2[mSlot] == t2
            && s.mruT3[mSlot] == t3 && s.mruT5[mSlot] == t5
            && (last == s.mruLeaf[mSlot] || last == s.mruOtherLast[mSlot])) {
         Object leaf = s.mruLeaf[mSlot];
         s.lastLeaf = leaf;
         s.lastNode = mNode;
         s.zeroExact = true;
         s.zeroMinT4 = s.mruMinT4[mSlot];
         s.zeroMaxT4 = s.mruMaxT4[mSlot];
         s.t0 = t0;
         s.t1 = t1;
         s.t2 = t2;
         s.t3 = t3;
         s.t4 = t4;
         s.t5 = t5;
         if ((++s.calls & 255) == 0) {
            s.compared = this.values[mNode];
            return null;
         }

         if (last != leaf) {
            this.lastResult.set(leaf);
         }

         return this.values[mNode];
      }

      long h = colHash ^ t4 * 0xA0761D6478BD642FL ^ System.identityHashCode(last);
      int slot = (int)(h ^ (h >>> 32)) & State.CACHE_MASK;
      int cachedNode = s.cNode[slot];
      if (cachedNode >= 0 && s.cLast[slot] == last
            && s.ct0[slot] == t0 && s.ct1[slot] == t1 && s.ct2[slot] == t2
            && s.ct3[slot] == t3 && s.ct4[slot] == t4 && s.ct5[slot] == t5) {
         Object leaf = this.leaves[cachedNode];
         s.lastLeaf = leaf;
         s.lastNode = cachedNode;
         s.zeroExact = false;
         s.t0 = t0;
         s.t1 = t1;
         s.t2 = t2;
         s.t3 = t3;
         s.t4 = t4;
         s.t5 = t5;
         if ((++s.calls & 255) == 0) {
            s.compared = this.values[cachedNode];
            return null;
         }

         if (last != leaf) {
            this.lastResult.set(leaf);
         }

         return this.values[cachedNode];
      }

      s.t0 = t0;
      s.t1 = t1;
      s.t2 = t2;
      s.t3 = t3;
      s.t4 = t4;
      s.t5 = t5;
      int lastNode;
      if (last == null) {
         lastNode = -1;
      } else if (last == s.lastLeaf) {
         lastNode = s.lastNode;
      } else {
         Integer known = this.leafNodes.get(last);
         if (known == null) {
            return null;
         }

         lastNode = known;
      }

      SEARCHED.increment();
      if ((++s.searches & 4095) == 0) {
         String caller = WALKER.walk(frames -> frames
            .filter(frame -> !frame.getClassName().startsWith("com.wgaccel.") && !frame.getClassName().startsWith("net.minecraft.world.level.biome.Climate"))
            .limit(3).map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
            .collect(java.util.stream.Collectors.joining(" < ")));
         SEARCH_CALLERS.computeIfAbsent(this.values.length + " entries: " + caller, key -> new LongAdder()).increment();
      }

      s.best = lastNode;
      s.bestDistance = lastNode < 0 ? Long.MAX_VALUE : distance(s, lastNode, Long.MAX_VALUE);
      s.safeLo = Integer.MIN_VALUE;
      s.safeHi = Integer.MAX_VALUE;
      if (s.bestDistance > 0L) {
         search(s, 0);
      }

      s.cLast[slot] = last;
      s.ct0[slot] = t0;
      s.ct1[slot] = t1;
      s.ct2[slot] = t2;
      s.ct3[slot] = t3;
      s.ct4[slot] = t4;
      s.ct5[slot] = t5;
      s.cNode[slot] = s.best;
      Object leaf = this.leaves[s.best];
      if (cSlot >= 0 && (inUniform || inGlobalMax || (inGlobalMin && this.bounds[s.best * 14 + 8] <= this.globalMinDepth))) {
         long cachedMax = inUniform ? Long.MAX_VALUE : (inGlobalMax ? Long.MAX_VALUE - 1L : t4);
         if (s.colNode[cSlot] == s.best && s.colMaxT4[cSlot] == cachedMax
               && s.colT0[cSlot] == t0 && s.colT1[cSlot] == t1 && s.colT2[cSlot] == t2
               && s.colT3[cSlot] == t3 && s.colT5[cSlot] == t5) {
            if (last != leaf) {
               s.colOtherLast[cSlot] = last;
            }
         } else {
            s.colT0[cSlot] = t0;
            s.colT1[cSlot] = t1;
            s.colT2[cSlot] = t2;
            s.colT3[cSlot] = t3;
            s.colT5[cSlot] = t5;
            s.colMaxT4[cSlot] = cachedMax;
            s.colNode[cSlot] = s.best;
            s.colLeaf[cSlot] = leaf;
            s.colOtherLast[cSlot] = last;
         }
      }
      s.lastLeaf = leaf;
      s.lastNode = s.best;
      if (inUniform) {
         s.zeroExact = true;
         s.zeroMinT4 = this.uniformMinDepth;
         s.zeroMaxT4 = this.uniformMaxDepth;
      } else if (inGlobalMax) {
         s.zeroExact = true;
         s.zeroMinT4 = this.globalMaxDepth;
         s.zeroMaxT4 = Integer.MAX_VALUE;
      } else {
         int base = s.best * 14;
         long winLo = this.bounds[base + 8];
         long winHi = this.bounds[base + 9];
         if (s.bestDistance == 0L) {
            s.zeroExact = true;
            s.zeroMinT4 = winLo;
            s.zeroMaxT4 = winHi;
         } else if (t4 >= winLo && t4 <= winHi && s.safeLo <= t4 && t4 <= s.safeHi) {
            s.zeroExact = true;
            s.zeroMinT4 = Math.max(winLo, s.safeLo);
            s.zeroMaxT4 = Math.min(winHi, s.safeHi);
         } else {
            s.zeroExact = false;
         }
      }
      if (s.zeroExact) {
         if (s.mruNode[mSlot] == s.best && s.mruMinT4[mSlot] == s.zeroMinT4 && s.mruMaxT4[mSlot] == s.zeroMaxT4
               && s.mruT0[mSlot] == t0 && s.mruT1[mSlot] == t1 && s.mruT2[mSlot] == t2
               && s.mruT3[mSlot] == t3 && s.mruT5[mSlot] == t5) {
            if (last != leaf) {
               s.mruOtherLast[mSlot] = last;
            }
         } else {
            s.mruT0[mSlot] = t0;
            s.mruT1[mSlot] = t1;
            s.mruT2[mSlot] = t2;
            s.mruT3[mSlot] = t3;
            s.mruT5[mSlot] = t5;
            s.mruMinT4[mSlot] = s.zeroMinT4;
            s.mruMaxT4[mSlot] = s.zeroMaxT4;
            s.mruNode[mSlot] = s.best;
            s.mruLeaf[mSlot] = leaf;
            s.mruOtherLast[mSlot] = last;
         }
      }
      if ((++s.calls & 255) == 0) {
         // Compared: vanilla searches from the same last answer and sets it itself.
         s.compared = this.values[s.best];
         return null;
      }

      if (last != leaf) {
         this.lastResult.set(leaf);
      }

      return this.values[s.best];
   }

   /** After vanilla's search of a compared call: its answer against this table's. */
   public void compare(Object vanilla) {
      State s = this.state.get();
      Object mine = s.compared;
      if (mine == null) {
         return;
      }

      s.compared = null;
      CHECKED.increment();
      if (mine != vanilla) {
         DIFFERENT.increment();
         if (!disabled) {
            disabled = true;
            WorldgenAccelerator.LOG.warn("WGA climate search: {} found where vanilla's search found {}; off, vanilla searches every table", mine, vanilla);
         }
      }

      if (CHECK_COUNT.incrementAndGet() % 65536 == 0) {
         WorldgenAccelerator.LOG.info(summary());
      }
   }

   public static long asked() {
      return ASKED.sum();
   }

   public static String summary() {
      StringBuilder callers = new StringBuilder();
      SEARCH_CALLERS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(8)
         .forEach(entry -> callers.append(callers.isEmpty() ? "" : "; ").append(entry.getValue().sum() * 4096).append(" from ").append(entry.getKey()));
      return String.format(Locale.ROOT,
         "WGA climate search: about %,d searches, checked %,d: %s; asked %,d, searched %,d (%.1f%%); searches by caller: %s",
         CHECKED.sum() * 256, CHECKED.sum(), DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)", ASKED.sum(), SEARCHED.sum(),
         100.0 * SEARCHED.sum() / Math.max(1, ASKED.sum()), callers);
   }
}
