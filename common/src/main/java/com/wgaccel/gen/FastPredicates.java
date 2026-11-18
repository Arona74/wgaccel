package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Mc;
import com.wgaccel.platform.Names;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.blockpredicates.HasSturdyFacePredicate;
import net.minecraft.world.level.levelgen.blockpredicates.InsideWorldBoundsPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.MatchingBlockTagPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate;
import net.minecraft.world.level.material.Fluid;

/**
 * Compiles vanilla {@link BlockPredicate} trees into allocation-free direct-section evaluators
 * ({@code fastCompiledPredicates}). Reads blocks directly from {@link WorldGenRegion} chunk sections
 * within an active scoped {@link EvalContext}, avoiding per-offset {@link BlockPos} allocations and
 * repeated region chunk-map lookups while preserving short-circuit and tag-reload semantics.
 */
public final class FastPredicates {
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
   private static final ConcurrentHashMap<BlockPredicate, Node> COMPILED = new ConcurrentHashMap<>();
   private static final ThreadLocal<EvalContext> CONTEXT = ThreadLocal.withInitial(EvalContext::new);

   private static volatile Field offsetField;
   private static volatile Field combiningPredicatesField;
   private static volatile Field notPredicateField;
   private static volatile Field matchingBlocksField;
   private static volatile Field matchingTagField;
   private static volatile Field matchingFluidsField;
   private static volatile Field sturdyOffsetField;
   private static volatile Field sturdyDirField;
   private static volatile Field surviveOffsetField;
   private static volatile Field surviveStateField;
   private static volatile Field boundsOffsetField;
   private static volatile Field simpleStateField;

   private FastPredicates() {
   }

   public static boolean on() {
      return Config.FAST_COMPILED_PREDICATES.get();
   }

   /** Pre-compiled condition nodes + result providers for RuleBased(Block)StateProvider. */
   public static final class RulePlan {
      final Node[] conditions;
      final BlockStateProvider[] thenProviders;
      final BlockStateProvider fallback;
      public final boolean pureDirect;
      final IndexedDispatch indexed;
      /** All the provider's own predicates (also the trimmed trailing ones), for the sampled check of the chosen rule. */
      final BlockPredicate[] raw;

      RulePlan(Node[] conditions, BlockStateProvider[] thenProviders, BlockStateProvider fallback, boolean pureDirect, IndexedDispatch indexed,
            BlockPredicate[] raw) {
         this.conditions = conditions;
         this.thenProviders = thenProviders;
         this.fallback = fallback;
         this.pureDirect = pureDirect;
         this.indexed = indexed;
         this.raw = raw;
      }
   }

   /** The sampled checks of the compiled predicates: a block test against BlockPredicate.test, a rule choice against the rules' own tests. */
   public static final FastCheck CHECK = new FastCheck("compiled block predicates", 64);
   public static final FastCheck RULE_CHECK = new FastCheck("compiled block rules", 64);

   /** Offset-grouped rule lookup table for large providers of single-offset static block-match rules (e.g. Still Life's 119-rule stone_cliff). */
   private static final class IndexedDispatch {
      final int[] ox;
      final int[] oy;
      final int[] oz;
      final int[] minRule;
      final Reference2IntOpenHashMap<Block>[] maps;

      IndexedDispatch(int[] ox, int[] oy, int[] oz, int[] minRule, Reference2IntOpenHashMap<Block>[] maps) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.minRule = minRule;
         this.maps = maps;
      }
   }

   /** Compiles a rule-based state provider's predicate list once per provider instance. */
   public static RulePlan compileRules(List<BlockPredicate> predicates, BlockStateProvider[] thenProviders, BlockStateProvider fallback) {
      int effectiveLen = predicates.size();
      BlockState fbState = simpleConstState(fallback);
      if (fbState != null) {
         // Trailing rules that return the exact same constant state as fallback never alter the output.
         while (effectiveLen > 0 && simpleConstState(thenProviders[effectiveLen - 1]) == fbState) {
            effectiveLen--;
         }
      }

      Node[] conditions = new Node[effectiveLen];
      boolean pureDirect = true;
      for (int i = 0; i < effectiveLen; i++) {
         Node node = compile(predicates.get(i));
         conditions[i] = node;
         if (pureDirect && hasFallback(node)) {
            pureDirect = false;
         }
      }

      BlockStateProvider[] effectiveProviders = effectiveLen == thenProviders.length ? thenProviders : Arrays.copyOf(thenProviders, effectiveLen);
      IndexedDispatch indexed = buildIndexedDispatch(conditions, effectiveLen);
      return new RulePlan(conditions, effectiveProviders, fallback, pureDirect, indexed, predicates.toArray(new BlockPredicate[0]));
   }

   /** Whether the rule plans may be used (the predicates switch on, no difference found by the rule check). */
   public static boolean rulesOn() {
      return on() && RULE_CHECK.on();
   }

   /** The rule the game's own tests choose (raw.length: none, the fallback), counted as the plan's (trimmed rules are the fallback's). */
   private static int vanillaRule(RulePlan plan, WorldGenLevel level, BlockPos pos) {
      int n = plan.raw.length;
      int chosen = n;
      for (int i = 0; i < n; i++) {
         if (plan.raw[i].test(level, pos)) {
            chosen = i;
            break;
         }
      }

      return Math.min(chosen, plan.thenProviders.length);
   }

   /** Evaluates all rules of a compiled RulePlan under a single shared EvalContext epoch. */
   public static BlockState evalRulePlan(RulePlan plan, WorldGenLevel level, RandomSource random, BlockPos pos) {
      EvalContext ctx = CONTEXT.get();
      boolean alreadyOpen = ctx.level == level && ctx.depth > 0;
      if (!alreadyOpen) {
         ctx.start(level);
      } else {
         ctx.depth++;
      }

      int chosen;
      try {
         chosen = chooseRule(plan, ctx, pos);
         if (RULE_CHECK.sample()) {
            chosen = RULE_CHECK.sameValue(chosen, vanillaRule(plan, level, pos), pos).intValue();
         }
      } finally {
         if (alreadyOpen) {
            ctx.depth--;
         } else {
            ctx.end();
         }
      }

      // The provider asked after the rule is chosen, outside the evaluation (as before: it was the last call inside it).
      return Mc.state(chosen < plan.thenProviders.length ? plan.thenProviders[chosen] : plan.fallback, level, random, pos);
   }

   /** The index of the first rule whose condition holds at pos, thenProviders.length for the fallback. */
   private static int chooseRule(RulePlan plan, EvalContext ctx, BlockPos pos) {
      {
         ctx.epoch++;
         int x = pos.getX();
         int y = pos.getY();
         int z = pos.getZ();
         IndexedDispatch idx = plan.indexed;
         if (idx != null) {
            int best = Integer.MAX_VALUE;
            int[] ox = idx.ox;
            int[] oy = idx.oy;
            int[] oz = idx.oz;
            int[] minRule = idx.minRule;
            Reference2IntOpenHashMap<Block>[] maps = idx.maps;
            for (int k = 0; k < ox.length; k++) {
               if (minRule[k] >= best) {
                  break;
               }
               int hit = maps[k].getInt(ctx.read(x + ox[k], y + oy[k], z + oz[k]).getBlock());
               if (hit < best) {
                  best = hit;
                  if (best == 0) {
                     break;
                  }
               }
            }
            return Math.min(best, plan.thenProviders.length);
         }

         Node[] conditions = plan.conditions;
         for (int i = 0; i < conditions.length; i++) {
            if (conditions[i].test(ctx, x, y, z)) {
               return i;
            }
         }
         return plan.thenProviders.length;
      }
   }

   private static BlockState simpleConstState(BlockStateProvider provider) {
      if (!"SimpleStateProvider".equals(Names.simpleName(provider.getClass()))) {
         return null;
      }
      try {
         Field f = field(simpleStateField, provider.getClass(), "state");
         simpleStateField = f;
         return (BlockState)f.get(provider);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
         return null;
      }
   }

   private static boolean hasFallback(Node node) {
      if (node instanceof FallbackNode) {
         return true;
      }
      if (node instanceof NotNode not) {
         return hasFallback(not.child);
      }
      if (node instanceof AllOfNode all) {
         for (Node c : all.children) {
            if (hasFallback(c)) {
               return true;
            }
         }
      } else if (node instanceof AnyOfNode any) {
         for (Node c : any.children) {
            if (hasFallback(c)) {
               return true;
            }
         }
      }
      return false;
   }

   @SuppressWarnings("unchecked")
   private static IndexedDispatch buildIndexedDispatch(Node[] conditions, int len) {
      if (len < 8) {
         return null;
      }

      IntArrayList oxList = new IntArrayList(8);
      IntArrayList oyList = new IntArrayList(8);
      IntArrayList ozList = new IntArrayList(8);
      IntArrayList minList = new IntArrayList(8);
      List<Reference2IntOpenHashMap<Block>> mapList = new ArrayList<>(8);

      for (int i = 0; i < len; i++) {
         int ox, oy, oz;
         Node n = conditions[i];
         if (n instanceof SmallBlocksNode s) {
            ox = s.ox;
            oy = s.oy;
            oz = s.oz;
         } else if (n instanceof SetBlocksNode s) {
            ox = s.ox;
            oy = s.oy;
            oz = s.oz;
         } else {
            return null;
         }

         int slot = -1;
         for (int k = 0; k < oxList.size(); k++) {
            if (oxList.getInt(k) == ox && oyList.getInt(k) == oy && ozList.getInt(k) == oz) {
               slot = k;
               break;
            }
         }
         if (slot < 0) {
            slot = oxList.size();
            oxList.add(ox);
            oyList.add(oy);
            ozList.add(oz);
            minList.add(i);
            Reference2IntOpenHashMap<Block> map = new Reference2IntOpenHashMap<>();
            map.defaultReturnValue(Integer.MAX_VALUE);
            mapList.add(map);
         }

         Reference2IntOpenHashMap<Block> map = mapList.get(slot);
         if (n instanceof SmallBlocksNode s) {
            for (Block b : s.blocks) {
               if (!map.containsKey(b)) {
                  map.put(b, i);
               }
            }
         } else {
            SetBlocksNode s = (SetBlocksNode)n;
            for (Block b : s.blocks) {
               if (!map.containsKey(b)) {
                  map.put(b, i);
               }
            }
         }
      }

      return new IndexedDispatch(oxList.toIntArray(), oyList.toIntArray(), ozList.toIntArray(), minList.toIntArray(),
         mapList.toArray(Reference2IntOpenHashMap[]::new));
   }

   /** Returns a compiled evaluator node (never null). */
   public static Node compile(BlockPredicate predicate) {
      if (COMPILED.size() > 8192) {
         COMPILED.clear();
      }

      return COMPILED.computeIfAbsent(predicate, FastPredicates::buildNode);
   }

   /** Acquires this thread's evaluation context for one placement/feature run; call {@link EvalContext#end()} in finally. */
   public static EvalContext begin(WorldGenLevel level) {
      EvalContext ctx = CONTEXT.get();
      ctx.start(level);
      return ctx;
   }

   /** Evaluates a predicate at {@code pos} using a short-lived single-call {@link EvalContext}. */
   public static boolean testOnce(BlockPredicate predicate, WorldGenLevel level, BlockPos pos) {
      if (!on() || !CHECK.on()) {
         return predicate.test(level, pos);
      }

      EvalContext ctx = CONTEXT.get();
      Node node = ctx.lastPred == predicate ? ctx.lastNode : cachePred(ctx, predicate);
      if (node instanceof FallbackNode fallback) {
         return fallback.raw.test(level, pos);
      }

      boolean alreadyOpen = ctx.level == level && ctx.depth > 0;
      if (!alreadyOpen) {
         ctx.start(level);
      } else {
         ctx.depth++;
      }

      try {
         boolean fast = ctx.eval(node, pos.getX(), pos.getY(), pos.getZ());
         // Predicates only read the world, so asking the game's own test as well changes nothing.
         return CHECK.sample() ? CHECK.same(fast, predicate.test(level, pos), pos) : fast;
      } finally {
         if (alreadyOpen) {
            ctx.depth--;
         } else {
            ctx.end();
         }
      }
   }

   private static Node buildNode(BlockPredicate predicate) {
      try {
         String simple = Names.simpleName(predicate.getClass());
         return switch (simple) {
            case "TrueBlockPredicate" -> TrueNode.INSTANCE;
            case "AllOfPredicate" -> {
               List<BlockPredicate> list = combiningPredicates(predicate);
               Node[] children = new Node[list.size()];
               for (int i = 0; i < children.length; i++) {
                  children[i] = buildNode(list.get(i));
               }
               yield new AllOfNode(children);
            }
            case "AnyOfPredicate" -> {
               List<BlockPredicate> list = combiningPredicates(predicate);
               Node[] children = new Node[list.size()];
               for (int i = 0; i < children.length; i++) {
                  children[i] = buildNode(list.get(i));
               }
               yield new AnyOfNode(children);
            }
            case "NotPredicate" -> {
               Field f = field(notPredicateField, predicate.getClass(), "predicate");
               notPredicateField = f;
               yield new NotNode(buildNode((BlockPredicate)f.get(predicate)));
            }
            case "MatchingBlocksPredicate" -> {
               Vec3i off = stateOffset(predicate);
               Field f = field(matchingBlocksField, predicate.getClass(), "blocks");
               matchingBlocksField = f;
               @SuppressWarnings("unchecked")
               HolderSet<Block> blocks = (HolderSet<Block>)f.get(predicate);
               if (blocks.unwrapKey().isPresent()) {
                  yield new TagHolderBlocksNode(off.getX(), off.getY(), off.getZ(), blocks);
               }
               Block[] arr = new Block[blocks.size()];
               int idx = 0;
               for (Holder<Block> h : blocks) {
                  arr[idx++] = h.value();
               }
               yield arr.length <= 6 ? new SmallBlocksNode(off.getX(), off.getY(), off.getZ(), arr) : new SetBlocksNode(off.getX(), off.getY(), off.getZ(), arr);
            }
            case "MatchingBlockTagPredicate" -> {
               Vec3i off = stateOffset(predicate);
               Field f = field(matchingTagField, MatchingBlockTagPredicate.class, "tag");
               matchingTagField = f;
               @SuppressWarnings("unchecked")
               TagKey<Block> tag = (TagKey<Block>)f.get(predicate);
               yield new BlockTagNode(off.getX(), off.getY(), off.getZ(), tag);
            }
            case "MatchingFluidsPredicate" -> {
               Vec3i off = stateOffset(predicate);
               Field f = field(matchingFluidsField, predicate.getClass(), "fluids");
               matchingFluidsField = f;
               @SuppressWarnings("unchecked")
               HolderSet<Fluid> fluids = (HolderSet<Fluid>)f.get(predicate);
               yield new FluidsNode(off.getX(), off.getY(), off.getZ(), fluids);
            }
            case "SolidPredicate" -> {
               Vec3i off = stateOffset(predicate);
               yield new SolidNode(off.getX(), off.getY(), off.getZ());
            }
            case "ReplaceablePredicate" -> {
               Vec3i off = stateOffset(predicate);
               yield new ReplaceableNode(off.getX(), off.getY(), off.getZ());
            }
            case "HasSturdyFacePredicate" -> {
               Field fo = field(sturdyOffsetField, HasSturdyFacePredicate.class, "offset");
               sturdyOffsetField = fo;
               Field fd = field(sturdyDirField, HasSturdyFacePredicate.class, "direction");
               sturdyDirField = fd;
               Vec3i off = (Vec3i)fo.get(predicate);
               yield new SturdyFaceNode(off.getX(), off.getY(), off.getZ(), (Direction)fd.get(predicate));
            }
            case "WouldSurvivePredicate" -> {
               Field fo = field(surviveOffsetField, WouldSurvivePredicate.class, "offset");
               surviveOffsetField = fo;
               Field fs = field(surviveStateField, WouldSurvivePredicate.class, "state");
               surviveStateField = fs;
               Vec3i off = (Vec3i)fo.get(predicate);
               yield new WouldSurviveNode(off.getX(), off.getY(), off.getZ(), (BlockState)fs.get(predicate));
            }
            case "InsideWorldBoundsPredicate" -> {
               Field fo = field(boundsOffsetField, InsideWorldBoundsPredicate.class, "offset");
               boundsOffsetField = fo;
               Vec3i off = (Vec3i)fo.get(predicate);
               yield new InsideBoundsNode(off.getX(), off.getY(), off.getZ());
            }
            default -> new FallbackNode(predicate);
         };
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.debug("WGA compiled predicates fallback for {}: {}", Names.className(predicate.getClass()), failure.toString());
         return new FallbackNode(predicate);
      }
   }

   @SuppressWarnings("unchecked")
   private static List<BlockPredicate> combiningPredicates(BlockPredicate predicate) throws ReflectiveOperationException {
      Field f = field(combiningPredicatesField, predicate.getClass().getSuperclass(), "predicates");
      combiningPredicatesField = f;
      return (List<BlockPredicate>)f.get(predicate);
   }

   private static Vec3i stateOffset(BlockPredicate predicate) throws ReflectiveOperationException {
      Field f = field(offsetField, StateTestingPredicate.class, "offset");
      offsetField = f;
      return (Vec3i)f.get(predicate);
   }

   private static Field field(Field cached, Class<?> owner, String name) throws NoSuchFieldException {
      if (cached != null) {
         return cached;
      }

      Field f = Names.declaredField(owner, name);
      f.setAccessible(true);
      return f;
   }

   private static Node cachePred(EvalContext ctx, BlockPredicate predicate) {
      Node node = compile(predicate);
      ctx.lastPred = predicate;
      ctx.lastNode = node;
      return node;
   }

   /** Per-thread scoped direct-section reader and coordinate cache for a single feature/placement invocation. */
   public static final class EvalContext {
      private BlockPredicate lastPred;
      private Node lastNode;
      private WorldGenLevel level;
      private WorldGenRegion region;
      private int minY;
      private int maxY;
      private int minSection;
      private int sectionsCount;
      private int depth;

      private final int[] chunkX = new int[4];
      private final int[] chunkZ = new int[4];
      private final ChunkAccess[] chunkRef = new ChunkAccess[4];
      private final boolean[] chunkDirect = new boolean[4];
      private int nextSlot;

      private int epoch;
      private int lastEpoch = -1;
      private int lastX;
      private int lastY;
      private int lastZ;
      private BlockState lastState;
      private final int[] slotEpoch = new int[16];
      private final int[] slotX = new int[16];
      private final int[] slotY = new int[16];
      private final int[] slotZ = new int[16];
      private final BlockState[] slotState = new BlockState[16];

      private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

      void start(WorldGenLevel level) {
         if (this.depth++ > 0 && this.level == level) {
            return;
         }

         this.level = level;
         if (level instanceof WorldGenRegion r) {
            this.region = r;
            this.minY = Mc.minY(r);
            this.maxY = Mc.maxY(r);
            this.minSection = Mc.minSection(r);
            this.sectionsCount = r.getSectionsCount();
         } else {
            this.region = null;
         }
         clearChunks();
      }

      public void end() {
         if (--this.depth <= 0) {
            this.depth = 0;
            this.level = null;
            this.region = null;
            clearChunks();
         }
      }

      private void clearChunks() {
         for (int i = 0; i < 4; i++) {
            this.chunkRef[i] = null;
            this.chunkX[i] = Integer.MIN_VALUE;
            this.chunkZ[i] = Integer.MIN_VALUE;
         }
         java.util.Arrays.fill(this.slotEpoch, 0);
         java.util.Arrays.fill(this.slotState, null);
         this.lastEpoch = -1;
         this.lastState = null;
      }

      public boolean eval(Node node, int x, int y, int z) {
         this.epoch++;
         return node.test(this, x, y, z);
      }

      BlockState read(int x, int y, int z) {
         if (this.lastEpoch == this.epoch && this.lastX == x && this.lastY == y && this.lastZ == z) {
            return this.lastState;
         }

         int h = (x * 73856093 ^ y * 19349663 ^ z * 83492791) & 15;
         if (this.slotEpoch[h] == this.epoch && this.slotX[h] == x && this.slotY[h] == y && this.slotZ[h] == z) {
            BlockState cached = this.slotState[h];
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastState = cached;
            return cached;
         }

         BlockState state;
         WorldGenRegion r = this.region;
         if (r != null) {
            if (y < this.minY || y >= this.maxY) {
               state = VOID_AIR;
            } else {
               int cx = x >> 4;
               int cz = z >> 4;
               int hit = findChunkSlot(cx, cz);
               ChunkAccess chunk = hit >= 0 ? this.chunkRef[hit] : loadChunk(r, cx, cz);
               if (chunk == null) {
                  state = r.getBlockState(this.mpos.set(x, y, z));
               } else if (hit >= 0 ? this.chunkDirect[hit] : (chunk.getClass() == ProtoChunk.class || chunk.getClass() == LevelChunk.class)) {
                  int sy = (y >> 4) - this.minSection;
                  if (sy < 0 || sy >= this.sectionsCount) {
                     state = VOID_AIR;
                  } else {
                     LevelChunkSection sec = chunk.getSection(sy);
                     state = sec.getBlockState(x & 15, y & 15, z & 15);
                  }
               } else {
                  state = chunk.getBlockState(this.mpos.set(x, y, z));
               }
            }
         } else {
            state = this.level.getBlockState(this.mpos.set(x, y, z));
         }

         this.lastEpoch = this.epoch;
         this.lastX = x;
         this.lastY = y;
         this.lastZ = z;
         this.lastState = state;
         this.slotEpoch[h] = this.epoch;
         this.slotX[h] = x;
         this.slotY[h] = y;
         this.slotZ[h] = z;
         this.slotState[h] = state;
         return state;
      }

      private int findChunkSlot(int cx, int cz) {
         for (int i = 0; i < 4; i++) {
            if (this.chunkRef[i] != null && this.chunkX[i] == cx && this.chunkZ[i] == cz) {
               return i;
            }
         }
         return -1;
      }

      private ChunkAccess loadChunk(WorldGenRegion r, int cx, int cz) {
         if (!r.hasChunk(cx, cz)) {
            return null;
         }

         ChunkAccess chunk = r.getChunk(cx, cz);
         int slot = this.nextSlot;
         this.nextSlot = (slot + 1) & 3;
         this.chunkX[slot] = cx;
         this.chunkZ[slot] = cz;
         this.chunkRef[slot] = chunk;
         this.chunkDirect[slot] = chunk.getClass() == ProtoChunk.class || chunk.getClass() == LevelChunk.class;
         return chunk;
      }

      boolean outsideBuildHeight(int y) {
         if (this.region != null) {
            return y < this.minY || y >= this.maxY;
         }
         return this.level.isOutsideBuildHeight(y);
      }

      boolean canSurvive(BlockState state, int x, int y, int z) {
         return state.canSurvive(this.level, this.mpos.set(x, y, z));
      }

      boolean isFaceSturdy(BlockState state, Direction dir, int x, int y, int z) {
         return state.isFaceSturdy(this.level, this.mpos.set(x, y, z), dir);
      }

      boolean fallback(BlockPredicate raw, int x, int y, int z) {
         this.mpos.set(x, y, z);
         return raw.test(this.level, this.mpos);
      }
   }

   public sealed interface Node permits TrueNode, AllOfNode, AnyOfNode, NotNode, SmallBlocksNode, SetBlocksNode,
         TagHolderBlocksNode, BlockTagNode, FluidsNode, SolidNode, ReplaceableNode, SturdyFaceNode, WouldSurviveNode,
         InsideBoundsNode, FallbackNode {
      boolean test(EvalContext ctx, int x, int y, int z);
   }

   private static final class TrueNode implements Node {
      static final TrueNode INSTANCE = new TrueNode();

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return true;
      }
   }

   private static final class AllOfNode implements Node {
      private final Node[] children;

      AllOfNode(Node[] children) {
         this.children = children;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         for (Node child : this.children) {
            if (!child.test(ctx, x, y, z)) {
               return false;
            }
         }
         return true;
      }
   }

   private static final class AnyOfNode implements Node {
      private final Node[] children;

      AnyOfNode(Node[] children) {
         this.children = children;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         for (Node child : this.children) {
            if (child.test(ctx, x, y, z)) {
               return true;
            }
         }
         return false;
      }
   }

   private static final class NotNode implements Node {
      private final Node child;

      NotNode(Node child) {
         this.child = child;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return !this.child.test(ctx, x, y, z);
      }
   }

   private static final class SmallBlocksNode implements Node {
      private final int ox, oy, oz;
      private final Block[] blocks;

      SmallBlocksNode(int ox, int oy, int oz, Block[] blocks) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.blocks = blocks;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         Block block = ctx.read(x + this.ox, y + this.oy, z + this.oz).getBlock();
         for (Block candidate : this.blocks) {
            if (block == candidate) {
               return true;
            }
         }
         return false;
      }
   }

   private static final class SetBlocksNode implements Node {
      private final int ox, oy, oz;
      private final ReferenceOpenHashSet<Block> blocks;

      SetBlocksNode(int ox, int oy, int oz, Block[] arr) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.blocks = new ReferenceOpenHashSet<>(arr);
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return this.blocks.contains(ctx.read(x + this.ox, y + this.oy, z + this.oz).getBlock());
      }
   }

   private static final class TagHolderBlocksNode implements Node {
      private final int ox, oy, oz;
      private final HolderSet<Block> blocks;

      TagHolderBlocksNode(int ox, int oy, int oz, HolderSet<Block> blocks) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.blocks = blocks;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.read(x + this.ox, y + this.oy, z + this.oz).is(this.blocks);
      }
   }

   private static final class BlockTagNode implements Node {
      private final int ox, oy, oz;
      private final TagKey<Block> tag;

      BlockTagNode(int ox, int oy, int oz, TagKey<Block> tag) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.tag = tag;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.read(x + this.ox, y + this.oy, z + this.oz).is(this.tag);
      }
   }

   private static final class FluidsNode implements Node {
      private final int ox, oy, oz;
      private final HolderSet<Fluid> fluids;

      FluidsNode(int ox, int oy, int oz, HolderSet<Fluid> fluids) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.fluids = fluids;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.read(x + this.ox, y + this.oy, z + this.oz).getFluidState().is(this.fluids);
      }
   }

   private static final class SolidNode implements Node {
      private final int ox, oy, oz;

      SolidNode(int ox, int oy, int oz) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
      }

      @SuppressWarnings("deprecation")
      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.read(x + this.ox, y + this.oy, z + this.oz).isSolid();
      }
   }

   private static final class ReplaceableNode implements Node {
      private final int ox, oy, oz;

      ReplaceableNode(int ox, int oy, int oz) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.read(x + this.ox, y + this.oy, z + this.oz).canBeReplaced();
      }
   }

   private static final class SturdyFaceNode implements Node {
      private final int ox, oy, oz;
      private final Direction dir;

      SturdyFaceNode(int ox, int oy, int oz, Direction dir) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.dir = dir;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         int rx = x + this.ox;
         int ry = y + this.oy;
         int rz = z + this.oz;
         return ctx.isFaceSturdy(ctx.read(rx, ry, rz), this.dir, rx, ry, rz);
      }
   }

   private static final class WouldSurviveNode implements Node {
      private final int ox, oy, oz;
      private final BlockState state;

      WouldSurviveNode(int ox, int oy, int oz, BlockState state) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.state = state;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.canSurvive(this.state, x + this.ox, y + this.oy, z + this.oz);
      }
   }

   private static final class InsideBoundsNode implements Node {
      private final int ox, oy, oz;

      InsideBoundsNode(int ox, int oy, int oz) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return !ctx.outsideBuildHeight(y + this.oy);
      }
   }

   public static final class FallbackNode implements Node {
      final BlockPredicate raw;

      FallbackNode(BlockPredicate raw) {
         this.raw = raw;
      }

      @Override
      public boolean test(EvalContext ctx, int x, int y, int z) {
         return ctx.fallback(this.raw, x, y, z);
      }
   }
}
