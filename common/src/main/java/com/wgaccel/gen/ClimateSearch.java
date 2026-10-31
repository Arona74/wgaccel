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

   /** Per thread: the last answer's node (matched with vanilla's last answer), the target and the running best. */
   private static final class State {
      Object lastLeaf;
      int lastNode = -1;
      long t0, t1, t2, t3, t4, t5;
      int best;
      long bestDistance;
      Object compared;
      int calls;
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

   /** SubTree.search over this node's children, the running best in the state. */
   private void search(State s, int node) {
      int start = this.first[node];
      int end = start + this.count[node];
      for (int child = start; child < end; child++) {
         long d = distance(s, child, s.bestDistance);
         if (s.bestDistance > d) {
            if (this.count[child] == 0) {
               s.bestDistance = d;
               s.best = child;
            } else {
               search(s, child);
            }
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
      long t0 = target.temperature(), t1 = target.humidity(), t2 = target.continentalness(), t3 = target.erosion(), t4 = target.depth(), t5 = target.weirdness();
      if (!fits(t0) || !fits(t1) || !fits(t2) || !fits(t3) || !fits(t4) || !fits(t5)) {
         return null;
      }

      s.t0 = t0;
      s.t1 = t1;
      s.t2 = t2;
      s.t3 = t3;
      s.t4 = t4;
      s.t5 = t5;
      Object last = this.lastResult.get();
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

      s.best = lastNode;
      s.bestDistance = lastNode < 0 ? Long.MAX_VALUE : distance(s, lastNode, Long.MAX_VALUE);
      search(s, 0);
      Object leaf = this.leaves[s.best];
      s.lastLeaf = leaf;
      s.lastNode = s.best;
      if ((++s.calls & 255) == 0) {
         // Compared: vanilla searches from the same last answer and sets it itself.
         s.compared = this.values[s.best];
         return null;
      }

      this.lastResult.set(leaf);
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

   public static String summary() {
      return String.format(Locale.ROOT, "WGA climate search: about %,d searches, checked %,d: %s", CHECKED.sum() * 256, CHECKED.sum(),
         DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
