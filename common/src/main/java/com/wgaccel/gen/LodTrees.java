package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.AcaciaFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.MegaPineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.PineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.SpruceFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.DarkOakTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.GiantTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/**
 * Stand-in trees for the Voxy LODs (voxyTrees): each biome's trees (its vegetal decoration's features that are, or
 * pick between, vanilla tree features: TreeConfiguration) with their own log and leaves, height and crown size.
 *
 * <p>How many and where: the feature's own counting placement (count, rarity, noise-based counts, the random spot in
 * the chunk) run with a random of ours, so forests are as dense as the game's and patchy where its noise says; not the
 * game's own spots (those depend on every earlier feature's use of the chunk's random). Which tree, how tall and how
 * wide: the feature's choice and its trunk and foliage placers' own heights and radii, from a random per tree spot (the
 * same whichever neighbouring chunk is being written, so a crown over a chunk edge matches on both sides). The shape:
 * a trunk and a ball, cone (spruces, pines) or flat (acacia) crown; 2 x 2 trunks for the giant ones. Features that are
 * not vanilla trees (some mods' own trees) are left out.
 */
final class LodTrees {
   /** Crowns reach this far (blocks) from their trunk at most: trees in the chunks around are looked at too. */
   static final int REACH = 8;

   enum Crown {
      BALL,
      CONE,
      FLAT
   }

   /** A kind of tree (or huge mushroom): its blocks (Voxy ids) and placers. */
   static final class Kind {
      /** Null for a huge mushroom. */
      final TreeConfiguration config;
      final int log;
      final int leaves;
      final Crown crown;
      final boolean wide;
      /** Huge mushrooms: 1 red (a dome), 2 brown (a flat cap); 0 a tree. Their cap radius. */
      final int mushroom;
      final int capRadius;
      /** FreeTerraForged's template trees: each template's 12 mirror and rotation forms; null otherwise. */
      final List<com.wgaccel.ftf.FtfTrees.Shape[]> templates;
      /** For the log: the blocks' names. */
      final String names;
      /** The game's placers failed for this kind: its simple shape from then on. */
      volatile boolean placersFailed;
      /**
       * How far its leaves reach from the trunk (blocks, the mean of a few grown ones): no other tree grows under them
       * (the game's heightmap finds the leaves, a sapling does not take there).
       */
      int footprint = 2;
      /**
       * The counting and spreading modifiers of the placed features between the source and this tree (a selector's
       * entry with its own count, a random patch's tries: Still Life's groups), run again from the source's spot; null
       * when there are none (one tree at the spot).
       */
      List<PlacementModifier> inner;
      /**
       * Lifeforged's terrain filters of the placed features between the source and this tree (Still Life's groups),
       * checked at each of its spots ({@link #allowed}); empty when there are none.
       */
      List<PlacementModifier> innerFilters = List.of();

      Kind(List<com.wgaccel.ftf.FtfTrees.Shape[]> templates, int radius, String names) {
         this.config = null;
         this.templates = templates;
         this.mushroom = 0;
         this.log = 0;
         this.leaves = 0;
         this.capRadius = radius;
         this.names = names;
         this.crown = Crown.BALL;
         this.wide = false;
      }

      Kind(int mushroom, int stem, int cap, int capRadius, String names) {
         this.config = null;
         this.templates = null;
         this.mushroom = mushroom;
         this.log = stem;
         this.leaves = cap;
         this.capRadius = capRadius;
         this.names = names;
         this.crown = Crown.BALL;
         this.wide = false;
      }

      Kind(TreeConfiguration config, int log, int leaves, String names) {
         this.config = config;
         this.templates = null;
         this.mushroom = 0;
         this.capRadius = 0;
         this.names = names;
         this.log = log;
         this.leaves = leaves;
         Object foliage = config.foliagePlacer;
         this.crown = foliage instanceof SpruceFoliagePlacer || foliage instanceof PineFoliagePlacer || foliage instanceof MegaPineFoliagePlacer
            ? Crown.CONE
            : foliage instanceof AcaciaFoliagePlacer ? Crown.FLAT : Crown.BALL;
         Object trunk = config.trunkPlacer;
         this.wide = trunk instanceof GiantTrunkPlacer || trunk instanceof DarkOakTrunkPlacer;
      }
   }

   /** A tree to draw: trunk at (x, z) world, height blocks of trunk, the crown radius and height; seed: its growth's random. */
   record Tree(int x, int z, Kind kind, int height, int radius, int crown, long seed) {
   }

   private record Key(long chunk, Holder<Biome> biome) {
   }

   /**
    * Each worker thread's last chunks' trees: a chunk's trees are looked at by each of the 3 x 3 chunks around it as they
    * are written (one after another by the same thread), and the spots' FreeTerraForged chances read its cells.
    */
   private final ThreadLocal<java.util.LinkedHashMap<Key, List<Tree>>> recent = ThreadLocal.withInitial(
      () -> new java.util.LinkedHashMap<>(1024, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<Key, List<Tree>> eldest) {
            return this.size() > 2048;
         }
      });

   /** Where a tree's blocks go: solid ones (logs, stems) over leaves, leaves only into air. */
   interface Out {
      void set(int x, int y, int z, int id, boolean solid);
   }

   /** One placed tree feature of a biome: its placement and the trees it picks (probabilities; the rest: no tree). */
   private record Source(int index, List<PlacementModifier> placement, List<PlacementModifier> filters, float[] chances, Kind[] kinds,
         PlacementContext context, Pick[] picks) {
   }

   /**
    * FreeTerraForged's weighted pick (ChanceFeature) at the top of a source: each entry's chance worked out at the spot by
    * its own modifiers (elevation, biome edge: its redwoods only low down, nothing high on mountains), then its trees.
    */
   private record Pick(Object entry, float[] chances, Kind[] kinds) {
   }

   /**
    * A patch of plants placed before the trees (RandomPatchFeature of one block). Those a tree cannot replace (not in
    * #replaceable_by_trees: pink petals...) block a trunk spot (TreeFeature's room check fails at its base); the others
    * (grass) only take the ground first, so a later patch cannot go there.
    */
   private record Cover(int index, List<PlacementModifier> placement, PlacementContext context, int tries, int spread, int ySpread,
      boolean blocking) {
   }

   /**
    * A cover's try: world column (x, z), dy blocks from the patch centre's height (its centre column cx, cz: the ground
    * there + 1, as its MOTION_BLOCKING heightmap gives); it takes only where that lands just over the ground.
    */
   interface Mark {
      void at(int x, int z, int dy, int cx, int cz, boolean blocking);
   }

   private static final Cover[] NO_COVERS = new Cover[0];
   private final Map<Holder<Biome>, Cover[]> covers = new ConcurrentHashMap<>();
   private static final Source[] NONE = new Source[0];
   private final ToIntFunction<BlockState> ids;
   private final ServerLevel level;
   private final long seed;
   /** FreeTerraForged installed: its plan's features and its placements ({@link com.wgaccel.ftf.FtfTrees}). */
   private final boolean ftf = com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
   private volatile boolean warned;
   private final Map<Holder<Biome>, Source[]> sources = new ConcurrentHashMap<>();
   /** Voxy ids of the states the game's placers set (logs' axes, leaves' distances...). */
   private final Map<BlockState, Integer> stateIds = new ConcurrentHashMap<>();

   /**
    * Features run as they are ({@link FeatureSandbox}), by feature id: their spots, kinds and shapes are all the mod's own
    * code (Conquest of Arborea's forests: forest cells, 1,577 schematics), and Lithos's (every feature of its namespace:
    * strata, layered and cliff stone, ground cover; voxyLithos).
    */
   static final java.util.Set<String> RUN = java.util.Set.of("conquest-of-arborea:tree_forest");

   /**
    * Whether a run's feature is on (its own config switch: Lithos's five, Arborea's forests; a feature without one is on).
    * Read when the runs are picked, so a change applies to the LODs made from then on.
    */
   static boolean enabled(Run run) {
      com.wgaccel.Setting<Boolean> setting = switch (run.name()) {
         case "lithos:strata" -> com.wgaccel.Config.VOXY_LITHOS_STRATA;
         case "lithos:layered_stone" -> com.wgaccel.Config.VOXY_LITHOS_LAYERED_STONE;
         case "lithos:cliff_stone" -> com.wgaccel.Config.VOXY_LITHOS_CLIFF_STONE;
         case "lithos:ground_cover" -> com.wgaccel.Config.VOXY_LITHOS_GROUND_COVER;
         case "lithos:vegetation_fill" -> com.wgaccel.Config.VOXY_LITHOS_VEGETATION_FILL;
         case "conquest-of-arborea:tree_forest" -> com.wgaccel.Config.VOXY_ARBOREA_FORESTS;
         default -> null;
      };
      return setting == null || setting.get();
   }

   /**
    * An ore (vanilla's ore and scattered ore features, or an ore by its id): left out of the ores step's terrain features,
    * which keeps its ground disks (Still Life's dirt, moss, gravel and snow; the game's sand, clay and gravel under water).
    */
   private static boolean ore(PlacedFeature placed, String name) {
      net.minecraft.world.level.levelgen.feature.Feature<?> feature = placed.feature().value().feature();
      return feature instanceof net.minecraft.world.level.levelgen.feature.OreFeature
         || feature instanceof net.minecraft.world.level.levelgen.feature.ScatteredOreFeature
         || name.contains(":ore_") || name.contains("/ore/") || name.contains("_ore");
   }

   /** FreeTerraForged's features the LOD columns work out themselves ({@code ftf/FtfLodDecor}). */
   private static final java.util.Set<String> FTF_OWN = java.util.Set.of("freeterraforged:decorate_snow", "freeterraforged:scree");

   /** What a run is for: trees (voxyTrees), Lithos's rock (voxyLithos), plants (voxyPlants). */
   static final int TREES = 0;
   static final int LITHOS = 1;
   static final int PLANTS = 2;
   /**
    * Terrain features (voxyTerrainFeatures): the biome's features in its raw-generation, local-modifications,
    * surface-structures and top-layer steps, and its ores step's ground disks (Still Life's snow disks on its peaks; its
    * cliff and slope rock, unless over voxyTerrainCostLimit) (ice spikes and ice patches, icebergs, mossy boulders, desert wells, Still Life's mountain snow; a mod's
    * in those steps; not the game's own freezing), run as they are.
    */
   static final int TERRAIN = 3;

   /**
    * Run as it is, by feature id: its kind ({@link #TREES}: Arborea's forests; {@link #LITHOS}: Lithos's; {@link #PLANTS}:
    * Arborea's others, its ground cover and shore plants), or null (the vegetal step's plants are found apart).
    */
   static Integer runs(String feature) {
      return RUN.contains(feature) ? (Integer)TREES : feature.startsWith("lithos:") ? (Integer)LITHOS
         : feature.startsWith("conquest-of-arborea:") ? (Integer)PLANTS : null;
   }

   /**
    * What each run feature changes that the LODs show, by placed feature: [0] columns whose top block it changed, [1]
    * blocks it changed 1 to 4 under the top (steps' sides), [2] blocks over the ground it set; [3] chunk runs it was in.
    * Its final state only (a block a later feature set again counts for that one), and only where it differs from the
    * LOD's own ground.
    */
   private static final Map<String, java.util.concurrent.atomic.LongAdder[]> CHANGES = new ConcurrentHashMap<>();
   private static final java.util.concurrent.atomic.AtomicLong CHANGE_RUNS = new java.util.concurrent.atomic.AtomicLong();

   private static java.util.concurrent.atomic.LongAdder[] changes(String name) {
      return CHANGES.computeIfAbsent(name, k -> new java.util.concurrent.atomic.LongAdder[]{
         new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(),
         new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()});
   }

   /** Terrain features described once when a costly run of theirs showed nothing. */
   private static final java.util.Set<String> DESCRIBED = ConcurrentHashMap.newKeySet();

   /** Why a terrain feature's run showed nothing: what it set, and the chunk's ground as the stand-in shows it. */
   private static void describe(String name, int cx, int cz, FeatureSandbox.Columns columns, long nanos, int setAll, int setAir) {
      Map<String, Integer> tops = new java.util.TreeMap<>();
      int low = Integer.MAX_VALUE;
      int high = Integer.MIN_VALUE;
      int wet = 0;
      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            int g = columns.ground((cx << 4) + x, (cz << 4) + z);
            low = Math.min(low, g);
            high = Math.max(high, g);
            wet += columns.surface((cx << 4) + x, (cz << 4) + z) > g ? 1 : 0;
            BlockState top = columns.layer((cx << 4) + x, (cz << 4) + z, 0);
            tops.merge(top == null ? "null (stone)" : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(top.getBlock())), 1, Integer::sum);
         }
      }

      WorldgenAccelerator.LOG.info("WGA Voxy LODs: {} showed nothing in chunk {}, {} ({} ms): it set {} blocks ({} air, the rest outside the"
         + " chunk's columns or under its ground); the chunk's ground y {} to {}, {} columns under water, top blocks {}", name, cx, cz,
         String.format(java.util.Locale.ROOT, "%.1f", nanos / 1.0e6), setAll, setAir, low, high, wet, tops);
   }

   /** The counts so far as a log line (each feature: per chunk run). */
   private static void logChanges(long runs) {
      StringBuilder line = new StringBuilder();
      double[] total = {0};
      // Slowest first: what costs the LODs most, and what it shows for it.
      CHANGES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[4].sum(), a.getValue()[4].sum())).forEach(e -> {
         java.util.concurrent.atomic.LongAdder[] c = e.getValue();
         double n = Math.max(1, c[3].sum());
         double ms = c[4].sum() / 1.0e6 / n;
         total[0] += ms;
         line.append(line.length() == 0 ? "" : "; ").append(e.getKey()).append(String.format(java.util.Locale.ROOT,
            " %.2f ms: %.1f top blocks, %.1f side blocks (1-4 under), %.1f over the ground", ms, c[0].sum() / n, c[1].sum() / n, c[2].sum() / n));
      });
      line.insert(0, String.format(java.util.Locale.ROOT, "%.1f ms a chunk run in all; ", total[0]));
      WorldgenAccelerator.LOG.info("WGA Voxy LODs: what the run features change that the LODs show, per chunk, after {} chunk runs: {}", runs, line);
   }

   /** Ground edits kept: this many blocks under each column's top (what the LODs show of the ground). */
   static final int GROUND_KEPT = 16;

   /**
    * A feature run as it is: the placed feature, its index in the biome's step and that step (the game's feature random),
    * and whether it is Lithos's (voxyLithos) rather than trees (voxyTrees).
    */
   record Run(PlacedFeature placed, int index, int step, int kind, String name) {
   }

   /** Runs in the game's order: step, then index. */
   static final java.util.Comparator<Run> ORDER = java.util.Comparator.comparingInt(Run::step).thenComparingInt(Run::index);

   /** A run's context: its 3 x 3 chunks all known ({@link #COMPLETE}), some guessed ({@link #PARTIAL}), or a far sample's. */
   static final int COMPLETE = 0;
   static final int PARTIAL = 1;
   static final int SAMPLED = 2;

   private record RunKey(long chunk, List<Run> runs, int context) {
   }

   /** Some biome has features run as they are (the near tiles then work out the chunks around them too). */
   private volatile boolean anyRuns;

   boolean anyRuns() {
      return this.anyRuns;
   }

   private static final Run[] NO_RUNS = new Run[0];
   private static final int[] NO_BLOCKS = new int[0];
   private final Map<Holder<Biome>, Run[]> runs = new ConcurrentHashMap<>();
   private final FeatureSandbox sandbox;
   /** Each worker thread's last runs (see {@link #ran}), asked by each of the 3 x 3 chunks around. */
   private final ThreadLocal<java.util.LinkedHashMap<RunKey, int[]>> ran = ThreadLocal.withInitial(
      () -> new java.util.LinkedHashMap<>(256, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<RunKey, int[]> eldest) {
            return this.size() > 256;
         }
      });

   /** Chunk runs a feature is watched before it may be left out ({@link #idle}). */
   private static final int WATCHED = 256;
   /** Time (ns a chunk run) under which a feature that shows nothing is still run (cheap; may show rarely). */
   private static final long IDLE_COST = 250_000L;

   /** Each run's chunk runs, time (ns) and blocks it showed in its own chunk: [0] runs, [1] ns, [2] shown. */
   private final Map<Run, java.util.concurrent.atomic.LongAdder[]> watch = new ConcurrentHashMap<>();
   /** Runs left out: costly, and nothing of them showed in {@link #WATCHED} chunk runs (palm crowns where no palm grows). */
   private final java.util.Set<Run> idle = ConcurrentHashMap.newKeySet();

   private java.util.concurrent.atomic.LongAdder[] watch(Run run) {
      return this.watch.computeIfAbsent(run, k -> new java.util.concurrent.atomic.LongAdder[]{
         new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()});
   }

   /** Adds a chunk run to run's watch; leaves it out once it has cost time for nothing. */
   private void watched(Run run, long nanos, int shown) {
      java.util.concurrent.atomic.LongAdder[] w = this.watch(run);
      w[0].increment();
      w[1].add(nanos);
      w[2].add(shown);
      long n = w[0].sum();
      if (n >= WATCHED && w[2].sum() == 0 && w[1].sum() / n >= IDLE_COST && this.idle.add(run)) {
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: {} left out where it ran ({} ms a chunk run, nothing shown in {} chunk runs)",
            run.name(), String.format(java.util.Locale.ROOT, "%.2f", w[1].sum() / 1.0e6 / n), n);
      }

      int limit = com.wgaccel.Config.VOXY_TERRAIN_COST_LIMIT.get();
      if (run.kind() == TERRAIN && limit > 0 && n >= WATCHED && w[1].sum() / n > limit * 1_000_000L && this.idle.add(run)) {
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: {} left out where it ran: {} ms a chunk run, over voxyTerrainCostLimit ({} ms; it showed {}"
            + " blocks a chunk run)", run.name(), String.format(java.util.Locale.ROOT, "%.2f", w[1].sum() / 1.0e6 / n), limit,
            String.format(java.util.Locale.ROOT, "%.1f", w[2].sum() / (double)n));
      }
   }

   /** The generator's step lists with the game's feature indices ({@link FeatureIndex}), read once. */
   private volatile List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> globalSteps;
   private volatile boolean globalRead;

   private List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> globalSteps(net.minecraft.world.level.chunk.ChunkGenerator generator) {
      if (!this.globalRead) {
         this.globalSteps = FeatureIndex.steps(generator);
         this.globalRead = true;
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: features seeded {}", this.globalSteps != null
            ? "by their index in the generator's decoration steps, as the game" : "by their place in each biome (the steps were not readable)");
      }

      return this.globalSteps;
   }

   LodTrees(ToIntFunction<BlockState> ids, ServerLevel level) {
      this.ids = ids;
      this.level = level;
      this.seed = level.getSeed();
      this.sandbox = new FeatureSandbox(level);
   }

   /** The biome's features run as they are. */
   Run[] runs(Holder<Biome> biome) {
      this.sources.computeIfAbsent(biome, this::sources);
      return this.runs.getOrDefault(biome, NO_RUNS);
   }

   /**
    * The blocks the runs (in {@link #ORDER}) set when started in chunk (cx, cz), the level around read from columns: x and
    * z from the chunk's corner, y from that column's ground (1 the block over it, 0 its top block...), Voxy id, solid (1:
    * logs, rock...; 0: leaves). Air and ground more than {@link #GROUND_KEPT} deep are left out. Once per chunk on this
    * thread, under key and its context ({@link #COMPLETE}, {@link #PARTIAL}: some chunks around guessed, kept apart so a
    * guess never stands for the complete run; {@link #SAMPLED}: a far chunk's run standing for the chunks around it).
    */
   int[] ran(List<Run> runs, int cx, int cz, long key, int context, FeatureSandbox.Columns columns) {
      java.util.LinkedHashMap<RunKey, int[]> ran = this.ran.get();
      RunKey at = new RunKey(key, runs, context);
      int[] blocks = ran.get(at);
      if (blocks == null) {
         if (!this.idle.isEmpty()) {
            List<Run> busy = new ArrayList<>(runs.size());
            for (Run run : runs) {
               if (!this.idle.contains(run)) {
                  busy.add(run);
               }
            }

            if (busy.size() < runs.size()) {
               runs = busy;
               if (runs.isEmpty()) {
                  ran.put(at, NO_BLOCKS);
                  return NO_BLOCKS;
               }
            }
         }

         List<Run> running = runs;
         int[][] list = {new int[256 * 5]};
         int[] count = {0};
         int x0 = cx << 4;
         int z0 = cz << 4;
         int[] shown = new int[runs.size()];
         // Every block each run set (air and outside this chunk included), its air among them: for describing a terrain
         // feature that showed nothing.
         int[] setAll = new int[runs.size()];
         int[] setAir = new int[runs.size()];
         java.util.concurrent.atomic.LongAdder[][] counts = new java.util.concurrent.atomic.LongAdder[runs.size()][];
         for (int r = 0; r < counts.length; r++) {
            counts[r] = changes(runs.get(r).name());
            counts[r][3].increment();
         }

         long[] nanos = new long[runs.size()];
         this.sandbox.run(runs, cx, cz, columns, (x, y, z, state, r) -> {
            setAll[r]++;
            if (state.isAir()) {
               setAir[r]++;
               return;
            }

            int dy = y - columns.ground(x, z);
            // What it changed that the LODs show (in this chunk's columns: a neighbour's are counted by its own run).
            if (x >> 4 == cx && z >> 4 == cz) {
               if (dy > 0) {
                  counts[r][2].increment();
                  shown[r]++;
               } else if (dy >= -4 && state != columns.layer(x, z, -dy)) {
                  counts[r][dy == 0 ? 0 : 1].increment();
                  shown[r]++;
               }
            }

            if (dy <= -GROUND_KEPT || dy <= 0 && running.get(r).kind() == PLANTS && !state.canOcclude()) {
               // Too deep to show; plants' thin blocks in the ground (lichen, roots...) are not kept (their whole ones are:
               // Still Life's rooted dirt, podzol and coarse dirt patches in the vegetal step).
               return;
            }

            if (count[0] + 5 > list[0].length) {
               list[0] = java.util.Arrays.copyOf(list[0], list[0].length * 2);
            }

            int[] a = list[0];
            a[count[0]++] = x - x0;
            a[count[0]++] = dy;
            a[count[0]++] = z - z0;
            a[count[0]++] = this.id(state);
            // 0 leaves (into air), 1 logs, rock... (over them), 2 plants (into air only, no snow on them).
            a[count[0]++] = running.get(r).kind() == PLANTS ? 2 : state.is(net.minecraft.tags.BlockTags.LEAVES) ? 0 : 1;
         }, nanos);
         for (int r = 0; r < nanos.length; r++) {
            counts[r][4].add(nanos[r]);
            this.watched(runs.get(r), nanos[r], shown[r]);
            if (shown[r] == 0 && running.get(r).kind() == TERRAIN && nanos[r] > 2_000_000L && DESCRIBED.add(running.get(r).name())) {
               describe(running.get(r).name(), cx, cz, columns, nanos[r], setAll[r], setAir[r]);
            }
         }
         blocks = count[0] == 0 ? NO_BLOCKS : java.util.Arrays.copyOf(list[0], count[0]);
         ran.put(at, blocks);
         long done = CHANGE_RUNS.incrementAndGet();
         if (done == 500 || done % 5000 == 0) {
            logChanges(done);
         }
      }

      return blocks;
   }

   /** The trees biome's tree features put in chunk (cx, cz) (the caller checks the biome and ground at each). */
   List<Tree> trees(Holder<Biome> biome, int cx, int cz) {
      java.util.LinkedHashMap<Key, List<Tree>> recent = this.recent.get();
      Key key = new Key(com.wgaccel.platform.Mc.key(cx, cz), biome);
      List<Tree> trees = recent.get(key);
      if (trees == null) {
         trees = new ArrayList<>();
         this.trees(biome, cx, cz, trees);
         recent.put(key, trees);
      }

      return trees;
   }

   private void trees(Holder<Biome> biome, int cx, int cz, List<Tree> out) {
      Source[] sources = this.sources.computeIfAbsent(biome, this::sources);
      for (Source source : sources) {
         long h = mix(this.seed ^ mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + source.index));
         RandomSource random = new LegacyRandomSource(h);
         List<BlockPos> spots = this.spots(source.placement, source.context, random, cx, cz);
         for (BlockPos spot : spots) {
            // Without a spread (FreeTerraForged's plains, savanna and badlands trees), the chunk's corner, as in the game.
            int x = spot.getX();
            int z = spot.getZ();
            if (!this.allowed(source.filters, x, z, h)) {
               continue;
            }

            // The tree from the spot alone: the same whichever chunk is being written.
            RandomSource own = new LegacyRandomSource(mix(h ^ mix(x * 0x632BE59BD9B4E019L + z)));
            float[] chances = source.chances;
            Kind[] kinds = source.kinds;
            if (source.picks != null) {
               // ChanceContext.nextIndex: an entry by its chance here (none when they are all 0).
               float[] weights = new float[source.picks.length];
               float total = 0;
               for (int i = 0; i < weights.length; i++) {
                  weights[i] = com.wgaccel.ftf.FtfTrees.entryChance(source.picks[i].entry, this.level, own, x, z);
                  total += weights[i];
               }

               if (total <= 0) {
                  continue;
               }

               float at = own.nextFloat() * total;
               int chosen = weights.length - 1;
               for (int i = 0; i < weights.length; i++) {
                  at -= weights[i];
                  if (at <= 0) {
                     chosen = i;
                     break;
                  }
               }

               chances = source.picks[chosen].chances;
               kinds = source.picks[chosen].kinds;
            }

            float pick = own.nextFloat();
            Kind kind = null;
            for (int i = 0; i < chances.length; i++) {
               pick -= chances[i];
               if (pick < 0) {
                  kind = kinds[i];
                  break;
               }
            }

            if (kind == null) {
               continue;
            }

            if (kind.inner == null) {
               if (this.allowed(kind.innerFilters, x, z, h)) {
                  this.tree(kind, x, z, own, out);
               }
            } else {
               // The chosen entry's own counts and spread, from this spot (as the game runs a nested placed feature).
               for (BlockPos at : this.spots(kind.inner, source.context, own, spot)) {
                  if (!this.allowed(kind.innerFilters, at.getX(), at.getZ(), h)) {
                     continue;
                  }

                  this.tree(kind, at.getX(), at.getZ(), new LegacyRandomSource(mix(own.nextLong() ^ mix(at.getX() * 0x632BE59BD9B4E019L + at.getZ()))), out);
               }
            }
         }
      }
   }

   /** The thread's placement context on a stand-in level for Lifeforged's terrain filters ({@link #allowed}). */
   private final ThreadLocal<PlacementContext> filterContext = ThreadLocal.withInitial(this::newFilterContext);

   private PlacementContext newFilterContext() {
      java.util.LinkedHashMap<Long, net.minecraft.world.level.chunk.ChunkAccess> chunks = new java.util.LinkedHashMap<>(64, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(java.util.Map.Entry<Long, net.minecraft.world.level.chunk.ChunkAccess> eldest) {
            return this.size() > 64;
         }
      };
      // Empty chunks (before the noise step: the filter reads FreeTerraForged's model, not blocks), their cells made by
      // FreeTerraForged when asked, their noise biomes the biome source's (the filter's climate asks the chunk).
      net.minecraft.world.level.biome.BiomeSource source = this.level.getChunkSource().getGenerator().getBiomeSource();
      net.minecraft.world.level.biome.Climate.Sampler sampler = this.level.getChunkSource().randomState().sampler();
      net.minecraft.world.level.biome.BiomeResolver biomes = (qx, qy, qz, unused) -> source.getNoiseBiome(qx, qy, qz, sampler);
      net.minecraft.world.level.WorldGenLevel standIn = new FeatureSandbox(this.level).cellsLevel(key -> chunks.computeIfAbsent(key,
         k -> com.wgaccel.platform.Mc.columnChunk(com.wgaccel.platform.Mc.chunk(k), this.level, this.level.registryAccess(), biomes)));
      return new PlacementContext(standIn, this.level.getChunkSource().getGenerator(), Optional.empty());
   }

   private static final java.util.concurrent.atomic.LongAdder FILTERED = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.LongAdder FILTER_PASSED = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.LongAdder FILTER_FAILED = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.AtomicLong FILTER_LOGGED = new java.util.concurrent.atomic.AtomicLong();
   private volatile boolean filterWarned;

   /**
    * Whether a tree spot at (x, z) passes Lifeforged's terrain filters (Still Life's treeline, slopes, talus, clearings),
    * run as they are at FreeTerraForged's model ground on a stand-in level; their random from the spot and the source's
    * seed h (theirs in the game is the feature's: the same share skipped, not the same spots). A filter that fails lets
    * the spot through.
    */
   private boolean allowed(List<PlacementModifier> filters, int x, int z, long h) {
      if (filters.isEmpty()) {
         return true;
      }

      int y = com.wgaccel.ftf.LifeforgedLod.modelSurface(this.level, x, z);
      if (y == Integer.MIN_VALUE) {
         return true;
      }

      BlockPos pos = new BlockPos(x, y, z);
      RandomSource random = new LegacyRandomSource(mix(h ^ mix(x * 0x2545F4914F6CDD1DL + z * 0x9E3779B97F4A7C15L)));
      boolean passed = true;
      try {
         PlacementContext context = this.filterContext.get();
         for (PlacementModifier filter : filters) {
            if (filter.getPositions(context, random, pos).findAny().isEmpty()) {
               passed = false;
               break;
            }
         }
      } catch (RuntimeException | LinkageError failure) {
         FILTER_FAILED.increment();
         if (!this.filterWarned) {
            this.filterWarned = true;
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD trees: Lifeforged's terrain filter failed outside the world; trees let through where it fails (said once)",
               failure);
         }
      }

      FILTERED.increment();
      if (passed) {
         FILTER_PASSED.increment();
      }

      long n = FILTERED.sum();
      if (n >= 10_000L && FILTER_LOGGED.compareAndSet(0L, 1L) || n >= 1_000_000L && FILTER_LOGGED.compareAndSet(1L, 2L)) {
         WorldgenAccelerator.LOG.info("WGA Voxy LOD trees: Lifeforged's terrain filter let {} of {} tree spots through ({} of them because it failed)",
            FILTER_PASSED.sum(), n, FILTER_FAILED.sum());
      }

      return passed;
   }

   /** A tree of this kind at (x, z): its height and crown from the random. */
   private void tree(Kind kind, int x, int z, RandomSource own, List<Tree> out) {
      int height = 5;
      int radius = 2;
      int crown = 3;
      if (kind.templates != null) {
         radius = kind.capRadius;
      } else if (kind.mushroom != 0) {
         // HugeMushroomFeature's height.
         height = own.nextInt(3) + 4;
         height *= own.nextInt(12) == 0 ? 2 : 1;
         radius = kind.capRadius;
         crown = 0;
      } else {
         try {
            height = kind.config.trunkPlacer.getTreeHeight(own);
            crown = Math.max(1, kind.config.foliagePlacer.foliageHeight(own, height, kind.config));
            radius = Math.max(1, kind.config.foliagePlacer.foliageRadius(own, height - crown));
         } catch (RuntimeException odd) {
            // A placer that needs more than a random: the defaults.
         }
      }

      out.add(new Tree(x, z, kind, Math.min(height, 40), Math.min(radius, REACH - 1), Math.min(crown, 16), own.nextLong()));
   }

   /**
    * The ground cover biome's patches put in chunk (cx, cz) before its trees: each patch's tries (RandomPatchFeature's
    * spread) handed to the mark. Only after {@link #trees} has read the biome.
    */
   void covers(Holder<Biome> biome, int cx, int cz, Mark mark) {
      this.sources.computeIfAbsent(biome, this::sources);
      for (Cover cover : this.covers.getOrDefault(biome, NO_COVERS)) {
         long h = mix(this.seed ^ mix(cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + cover.index));
         RandomSource random = new LegacyRandomSource(h);
         int s = cover.spread;
         int ys = cover.ySpread;
         for (BlockPos centre : this.spots(cover.placement, cover.context, random, cx, cz)) {
            for (int t = 0; t < cover.tries; t++) {
               // RandomPatchFeature's offsets: x, y, z.
               int dx = random.nextInt(s + 1) - random.nextInt(s + 1);
               int dy = random.nextInt(ys + 1) - random.nextInt(ys + 1);
               int dz = random.nextInt(s + 1) - random.nextInt(s + 1);
               mark.at(centre.getX() + dx, centre.getZ() + dz, dy, centre.getX(), centre.getZ(), cover.blocking);
            }
         }
      }
   }

   /** A placement's spots in chunk (cx, cz): its counting and spreading modifiers run with a random of ours. */
   private List<BlockPos> spots(List<PlacementModifier> placement, PlacementContext context, RandomSource random, int cx, int cz) {
      return this.spots(placement, context, random, new BlockPos(cx * 16, 0, cz * 16));
   }

   /** A placement's spots from this start (a nested placed feature: its parent's spot). */
   private List<BlockPos> spots(List<PlacementModifier> placement, PlacementContext context, RandomSource random, BlockPos start) {
      int cx = start.getX() >> 4;
      int cz = start.getZ() >> 4;
      List<BlockPos> spots = List.of(start);
      for (PlacementModifier modifier : placement) {
         List<BlockPos> next = new ArrayList<>();
         if (this.ftf && com.wgaccel.ftf.FtfTrees.poisson(modifier)) {
            // Its spots in the whole chunk from the seed (its own getPositions asks the level for the chunk).
            com.wgaccel.ftf.FtfTrees.poissonPositions(modifier, this.level, cx, cz).forEach(next::add);
            spots = next;
            continue;
         }

         for (BlockPos spot : spots) {
            try {
               // A real context (mods hook these placements and look at it: FreeTerraForged's dynamic ores).
               modifier.getPositions(context, random, spot).forEach(next::add);
            } catch (RuntimeException failure) {
               next.add(spot);
               if (!this.warned) {
                  this.warned = true;
                  WorldgenAccelerator.LOG.warn("WGA Voxy LOD trees: a tree placement ({}) failed; it is left out (said once)",
                     modifier.getClass().getName(), failure);
               }
            }
         }

         spots = next;
      }

      return spots;
   }

   /** The counting and spreading modifiers we run (the rest, which read the world, are left to the column checks). */
   private boolean counted(PlacementModifier modifier) {
      return modifier instanceof CountPlacement || modifier instanceof RarityFilter || modifier instanceof NoiseBasedCountPlacement
         || modifier instanceof NoiseThresholdCountPlacement || modifier instanceof RandomOffsetPlacement || modifier instanceof InSquarePlacement
         || this.ftf && (com.wgaccel.ftf.FtfTrees.poisson(modifier) || com.wgaccel.ftf.FtfTrees.countExtra(modifier));
   }

   /** FreeTerraForged's weighted pick at the top of a placed feature: its entries and their trees; else null. */
   private Pick[] picks(PlacedFeature placed) {
      FeatureConfiguration config = placed.feature().value().config();
      if (!this.ftf || !com.wgaccel.ftf.FtfTrees.chance(config)) {
         return null;
      }

      List<Object> entries = com.wgaccel.ftf.FtfTrees.chanceEntries(config);
      List<Holder<PlacedFeature>> features = com.wgaccel.ftf.FtfTrees.chanceFeatures(config);
      Pick[] picks = new Pick[entries.size()];
      for (int i = 0; i < picks.length; i++) {
         List<float[]> chances = new ArrayList<>();
         List<Kind> kinds = new ArrayList<>();
         this.collect(features.get(i).value(), 1.0F, chances, kinds, 1);
         float[] chance = new float[kinds.size()];
         for (int k = 0; k < chance.length; k++) {
            chance[k] = chances.get(k)[0];
         }

         picks[i] = new Pick(entries.get(i), chance, kinds.toArray(new Kind[0]));
      }

      return picks;
   }

   /** A patch of blocks a tree cannot replace (pink petals...), else null. */
   private Cover cover(int index, Holder<PlacedFeature> placed) {
      Object[] patch = com.wgaccel.platform.Mc.patch(placed.value().feature().value().config());
      if (patch == null) {
         return null;
      }

      @SuppressWarnings("unchecked")
      Holder<PlacedFeature> inner = (Holder<PlacedFeature>)patch[2];
      if (!(inner.value().feature().value().config() instanceof net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration block)) {
         return null;
      }

      BlockState state = com.wgaccel.platform.Mc.providedState(block.toPlace(), new LegacyRandomSource(0L), BlockPos.ZERO);
      if (state.isAir()) {
         return null;
      }

      List<PlacementModifier> placement = new ArrayList<>();
      for (PlacementModifier modifier : placed.value().placement()) {
         if (this.counted(modifier)) {
            placement.add(modifier);
         }
      }

      return new Cover(index, List.copyOf(placement), new PlacementContext(this.level, this.level.getChunkSource().getGenerator(), Optional.of(placed.value())),
         (Integer)patch[0], (Integer)patch[1], (Integer)patch[3], !state.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES));
   }

   private Source[] sources(Holder<Biome> biome) {
      try {
         // FreeTerraForged decorates from its plan (its own trees in place of vanilla's); else the biome's settings.
         Iterable<Holder<PlacedFeature>> vegetation = this.ftf
            ? com.wgaccel.ftf.FtfTrees.vegetation(this.level.getChunkSource().getGenerator(), biome) : null;
         if (vegetation == null) {
            List<net.minecraft.core.HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
            int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
            if (steps.size() <= step) {
               return NONE;
            }

            vegetation = steps.get(step);
         }

         List<Source> found = new ArrayList<>();
         List<Cover> covers = new ArrayList<>();
         List<String> described = new ArrayList<>();
         int index = 0;
         // A mod's trees that are all its own code: the feature itself, in a stand-in level, whichever step it is in (mods
         // add theirs in code).
         List<Run> runs = new ArrayList<>();
         // The biome's features as the generator decorates it (FreeTerraForged: its plan's), each seeded by its index in
         // the step across all biomes, as the game's decoration (its place in this biome's list gave other spots).
         net.minecraft.world.level.chunk.ChunkGenerator generator = this.level.getChunkSource().getGenerator();
         List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> global = this.globalSteps(generator);
         List<net.minecraft.core.HolderSet<PlacedFeature>> all = generator.getBiomeGenerationSettings(biome).features();
         for (int step = 0; step < all.size(); step++) {
            int at = 0;
            for (Holder<PlacedFeature> placed : all.get(step)) {
               Integer kind = runs(FeatureSandbox.name(placed.value()));
               if (kind != null) {
                  runs.add(new Run(placed.value(), FeatureIndex.index(global, step, placed.value(), at), step, kind, placed.getRegisteredName()));
                  described.add(placed.getRegisteredName() + " [run as it is, in a stand-in level]");
               } else if (FTF_OWN.contains(placed.getRegisteredName())) {
                  // FreeTerraForged's own snow decorator and scree: the LOD columns work them out already.
               } else if (step == GenerationStep.Decoration.RAW_GENERATION.ordinal()
                  || step == GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal()
                  || step == GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal()
                  || step == GenerationStep.Decoration.UNDERGROUND_ORES.ordinal() && !ore(placed.value(), placed.getRegisteredName())
                  || step == GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal()
                     && !"minecraft:freeze_top_layer".equals(placed.getRegisteredName())) {
                  // Ice spikes, icebergs, boulders, wells: what stands on the ground (one that never shows is left out
                  // by the idle rule). The top layer's mods' snow too (Still Life's mountain snow); the game's own
                  // freezing is the LODs' snow and ice already.
                  runs.add(new Run(placed.value(), FeatureIndex.index(global, step, placed.value(), at), step, TERRAIN, placed.getRegisteredName()));
               }

               at++;
            }
         }

         for (Holder<PlacedFeature> placed : vegetation) {
            index++;
            if (runs(FeatureSandbox.name(placed.value())) != null) {
               continue;
            }

            List<float[]> chances = new ArrayList<>();
            List<Kind> kinds = new ArrayList<>();
            this.collect(placed.value(), 1.0F, chances, kinds, 0);
            if (kinds.isEmpty()) {
               // Not trees: plants (flowers, grass, bushes, Conquest's patches), run as they are (voxyPlants).
               int vegetal = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
               runs.add(new Run(placed.value(), FeatureIndex.index(global, vegetal, placed.value(), index - 1), vegetal, PLANTS,
                  placed.getRegisteredName()));

               Cover cover = found.isEmpty() ? this.cover(index, placed) : null;
               if (cover != null) {
                  covers.add(cover);
                  if (cover.blocking) {
                     described.add(placed.getRegisteredName() + " [ground cover trees cannot grow on: " + cover.tries + " tries within "
                        + cover.spread + "]");
                  }
               }

               continue;
            }

            List<PlacementModifier> placement = new ArrayList<>();
            List<PlacementModifier> filters = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            for (PlacementModifier modifier : placed.value().placement()) {
               if (this.counted(modifier)) {
                  placement.add(modifier);
               } else if (this.ftf && com.wgaccel.ftf.LifeforgedLod.terrainFilter(modifier)) {
                  filters.add(modifier);
               } else {
                  skipped.add(modifier.getClass().getSimpleName());
               }
            }

            StringBuilder trees = new StringBuilder();
            for (int i = 0; i < kinds.size(); i++) {
               trees.append(i == 0 ? "" : ", ").append(kinds.get(i).names).append(' ')
                  .append(String.format(java.util.Locale.ROOT, "%.0f%% (leaves reach %d)", chances.get(i)[0] * 100, kinds.get(i).footprint));
               if (kinds.get(i).inner != null) {
                  trees.append(" then ").append(kinds.get(i).inner.stream().map(m -> m.getClass().getSimpleName()).toList());
               }

               if (!kinds.get(i).innerFilters.isEmpty()) {
                  trees.append(" with Lifeforged's terrain filter");
               }
            }

            described.add(placed.getRegisteredName() + " [" + trees + "; counted by "
               + placement.stream().map(m -> m.getClass().getSimpleName()).toList() + (filters.isEmpty() ? "" : ", filtered by Lifeforged's terrain filter")
               + ", not checked " + skipped + "]");

            float[] chance = new float[kinds.size()];
            for (int i = 0; i < chance.length; i++) {
               chance[i] = chances.get(i)[0];
            }

            PlacementContext context = new PlacementContext(this.level, this.level.getChunkSource().getGenerator(), Optional.of(placed.value()));
            found.add(new Source(index, List.copyOf(placement), List.copyOf(filters), chance, kinds.toArray(new Kind[0]), context, this.picks(placed.value())));
         }

         this.runs.put(biome, runs.toArray(NO_RUNS));
         if (!runs.isEmpty()) {
            this.anyRuns = true;
         }
         // Only worth laying out when one of them blocks trees.
         this.covers.put(biome, covers.stream().anyMatch(Cover::blocking) ? covers.toArray(NO_COVERS) : NO_COVERS);
         WorldgenAccelerator.LOG.info("WGA Voxy LOD trees: {}: {}", biome.getRegisteredName(),
            described.isEmpty() ? "none" : String.join("; ", described));
         return found.toArray(NONE);
      } catch (RuntimeException failure) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD trees: the trees of a biome could not be read; it gets none", failure);
         return NONE;
      }
   }

   /**
    * The trees a placed feature makes, each with its probability (weight: the probability of reaching it). Below the
    * source (depth > 0), the placed feature's own counting modifiers, and a random patch's tries when they spread out,
    * go in front of the inner ones of each tree found under it.
    */
   private void collect(PlacedFeature placed, float weight, List<float[]> chances, List<Kind> kinds, int depth) {
      int before = kinds.size();
      this.collectHere(placed, weight, chances, kinds, depth);
      if (depth == 0 || kinds.size() == before) {
         return;
      }

      List<PlacementModifier> mine = new ArrayList<>();
      List<PlacementModifier> mineFilters = new ArrayList<>();
      for (PlacementModifier modifier : placed.placement()) {
         if (this.counted(modifier)) {
            mine.add(modifier);
         } else if (this.ftf && com.wgaccel.ftf.LifeforgedLod.terrainFilter(modifier)) {
            mineFilters.add(modifier);
         }
      }

      for (int i = before; i < kinds.size() && !mineFilters.isEmpty(); i++) {
         Kind kind = kinds.get(i);
         List<PlacementModifier> filters = new ArrayList<>(mineFilters);
         filters.addAll(kind.innerFilters);
         kind.innerFilters = List.copyOf(filters);
      }

      Object[] patch = com.wgaccel.platform.Mc.patch(placed.feature().value().config());
      if (patch != null && (Integer)patch[0] > 1 && (Integer)patch[1] > 1) {
         // RandomPatchFeature's tries spread around the spot (a pale oak or maple grove): about as many trees, spread
         // as far (its offsets are a triangle, these even).
         int spread = (Integer)patch[1];
         mine.add(CountPlacement.of((Integer)patch[0]));
         mine.add(RandomOffsetPlacement.of(net.minecraft.util.valueproviders.UniformInt.of(-spread, spread),
            net.minecraft.util.valueproviders.ConstantInt.of(0)));
      }

      if (mine.isEmpty()) {
         return;
      }

      for (int i = before; i < kinds.size(); i++) {
         Kind kind = kinds.get(i);
         List<PlacementModifier> inner = new ArrayList<>(mine);
         if (kind.inner != null) {
            inner.addAll(kind.inner);
         }

         kind.inner = List.copyOf(inner);
      }
   }

   private void collectHere(PlacedFeature placed, float weight, List<float[]> chances, List<Kind> kinds, int depth) {
      if (depth > 6 || weight <= 0) {
         return;
      }

      FeatureConfiguration config = placed.feature().value().config();
      net.minecraft.world.level.levelgen.feature.Feature<?> feature = placed.feature().value().feature();
      if (this.ftf && com.wgaccel.ftf.FtfTrees.template(config)) {
         // FreeTerraForged's trees and huge mushrooms: NBT templates.
         try {
            List<com.wgaccel.ftf.FtfTrees.Shape[]> templates = com.wgaccel.ftf.FtfTrees.templates(this.level.getServer(), config);
            if (!templates.isEmpty()) {
               // Its leaves' reach: the mean over its templates of the farthest leaf from the trunk.
               int sum = 0;
               for (com.wgaccel.ftf.FtfTrees.Shape[] forms : templates) {
                  com.wgaccel.ftf.FtfTrees.Shape shape = forms[0];
                  int far = 0;
                  for (int i = 0; i < shape.x().length; i++) {
                     if (!shape.states()[i].is(net.minecraft.tags.BlockTags.LOGS)) {
                        far = Math.max(far, Math.max(Math.abs(shape.x()[i]), Math.abs(shape.z()[i])));
                     }
                  }

                  sum += far;
               }

               int reach = Math.max(1, Math.round(sum / (float)templates.size()));
               Kind kind = new Kind(templates, Math.min(reach, REACH - 1), "template " + com.wgaccel.ftf.FtfTrees.templateNames(config));
               kind.footprint = reach;
               chances.add(new float[]{weight});
               kinds.add(kind);
            }
         } catch (RuntimeException | LinkageError failure) {
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD trees: FreeTerraForged's tree templates could not be read", failure);
         }

         return;
      }

      if (this.ftf && com.wgaccel.ftf.FtfTrees.bush(config) != null) {
         // FreeTerraForged's bushes: a log, leaves around it.
         BlockState[] parts = com.wgaccel.ftf.FtfTrees.bush(config);
         Kind kind = new Kind(3, this.ids.applyAsInt(parts[0]), this.ids.applyAsInt(parts[1]), 1,
            "bush " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(parts[0].getBlock()).getPath());
         kind.footprint = 1;
         chances.add(new float[]{weight});
         kinds.add(kind);
         return;
      }

      if (this.ftf && com.wgaccel.ftf.FtfTrees.chance(config)) {
         // Its weighted pick: by the base chances (always one of them, as ChanceContext picks).
         List<Holder<PlacedFeature>> features = com.wgaccel.ftf.FtfTrees.chanceFeatures(config);
         List<Float> weights = com.wgaccel.ftf.FtfTrees.chanceWeights(config);
         float total = 0;
         for (float w : weights) {
            total += w;
         }

         for (int i = 0; i < features.size() && total > 0; i++) {
            this.collect(features.get(i).value(), weight * weights.get(i) / total, chances, kinds, depth + 1);
         }

         return;
      }

      if (config instanceof net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration mushroom
         && (feature == net.minecraft.world.level.levelgen.feature.Feature.HUGE_RED_MUSHROOM
            || feature == net.minecraft.world.level.levelgen.feature.Feature.HUGE_BROWN_MUSHROOM)) {
         try {
            Object[] parts = com.wgaccel.platform.Mc.mushroom(mushroom);
            RandomSource random = new LegacyRandomSource(0L);
            BlockState cap = com.wgaccel.platform.Mc.providedState(
               (net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider)parts[0], random, BlockPos.ZERO);
            BlockState stem = com.wgaccel.platform.Mc.providedState(
               (net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider)parts[1], random, BlockPos.ZERO);
            boolean red = feature == net.minecraft.world.level.levelgen.feature.Feature.HUGE_RED_MUSHROOM;
            chances.add(new float[]{weight});
            kinds.add(new Kind(red ? 1 : 2, this.ids.applyAsInt(stem), this.ids.applyAsInt(cap), (Integer)parts[2],
               (red ? "huge red mushroom " : "huge brown mushroom ")
                  + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(cap.getBlock()).getPath()));
         } catch (RuntimeException needsLevel) {
            // Left out.
         }
      } else if (config instanceof TreeConfiguration tree) {
         BlockPos origin = BlockPos.ZERO;
         RandomSource random = new LegacyRandomSource(0L);
         Kind kind;
         try {
            BlockState log = com.wgaccel.platform.Mc.providedState(tree.trunkProvider, random, origin);
            BlockState leaves = com.wgaccel.platform.Mc.providedState(tree.foliageProvider, random, origin);
            kind = new Kind(tree, this.ids.applyAsInt(log), this.ids.applyAsInt(leaves),
               net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(log.getBlock()).getPath() + "/"
                  + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(leaves.getBlock()).getPath());
         } catch (RuntimeException needsLevel) {
            // A block provider that reads the world: this tree is left out.
            return;
         }

         chances.add(new float[]{weight});
         kind.footprint = this.footprint(kind);
         kinds.add(kind);
      } else if (config instanceof RandomFeatureConfiguration random) {
         float left = weight;
         for (WeightedPlacedFeature entry : random.features) {
            this.collect(entry.feature.value(), left * entry.chance, chances, kinds, depth + 1);
            left *= 1.0F - entry.chance;
         }

         this.collect(random.defaultFeature.value(), left, chances, kinds, depth + 1);
      } else if (config instanceof SimpleRandomFeatureConfiguration simple) {
         int n = simple.features.size();
         for (Holder<PlacedFeature> entry : simple.features) {
            this.collect(entry.value(), weight / n, chances, kinds, depth + 1);
         }
      } else if (config instanceof RandomBooleanFeatureConfiguration either) {
         this.collect(either.featureTrue.value(), weight / 2, chances, kinds, depth + 1);
         this.collect(either.featureFalse.value(), weight / 2, chances, kinds, depth + 1);
      } else if (config instanceof net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration patch) {
         // A patch of ground with a tree on it (Still Life's baobabs, redwoods, kapoks, old oaks): the tree.
         this.collect(patch.vegetationFeature.value(), weight * Math.min(1.0F, patch.vegetationChance * 4), chances, kinds, depth + 1);
      } else {
         Object[] patch = com.wgaccel.platform.Mc.patch(config);
         if (patch != null) {
            // A random patch of trees (Still Life's: mostly one try on the spot): its feature, its tries added by collect.
            @SuppressWarnings("unchecked")
            Holder<PlacedFeature> inner = (Holder<PlacedFeature>)patch[2];
            this.collect(inner.value(), weight, chances, kinds, depth + 1);
         }
      }
   }

   /**
    * One tree's blocks: the game's own trunk and foliage placers (TreeFeature's steps, in an empty world; 1.21.1) when
    * they can run, else a simple shape; huge mushrooms as HugeRed/BrownMushroomFeature lay them out.
    */
   void grow(Kind kind, int x, int y, int z, int height, int radius, int crown, RandomSource random, Out out) {
      if (kind.templates != null) {
         // TemplateFeature: a mirror, a rotation, then a template.
         int mirror = random.nextInt(3);
         int rotation = random.nextInt(4);
         com.wgaccel.ftf.FtfTrees.Shape shape = kind.templates.get(random.nextInt(kind.templates.size()))[mirror * 4 + rotation];
         for (int i = 0; i < shape.x().length; i++) {
            BlockState state = shape.states()[i];
            out.set(x + shape.x()[i], y + shape.y()[i], z + shape.z()[i], this.id(state), state.is(net.minecraft.tags.BlockTags.LOGS));
         }

         return;
      }

      if (kind.mushroom == 3) {
         // A bush (FreeTerraForged's BushFeature): its log, leaves on four sides and on top.
         out.set(x, y, z, kind.log, true);
         out.set(x + 1, y, z, kind.leaves, false);
         out.set(x - 1, y, z, kind.leaves, false);
         out.set(x, y, z + 1, kind.leaves, false);
         out.set(x, y, z - 1, kind.leaves, false);
         out.set(x, y + 1, z, kind.leaves, false);
         return;
      }

      if (kind.mushroom != 0) {
         mushroom(kind, x, y, z, height, out);
         return;
      }

      if (!kind.placersFailed) {
         try {
            if (com.wgaccel.platform.Mc.growTree(kind.config, random, height, new BlockPos(x, y, z), crown, radius,
               (pos, state) -> out.set(pos.getX(), pos.getY(), pos.getZ(), this.id(state), true),
               (pos, state) -> out.set(pos.getX(), pos.getY(), pos.getZ(), this.id(state), false))) {
               return;
            }
         } catch (RuntimeException | LinkageError failure) {
            kind.placersFailed = true;
            WorldgenAccelerator.LOG.warn("WGA Voxy LOD trees: the game's placers for {} could not run outside a world; a simple shape for it",
               kind.names, failure);
         }
      }

      basic(kind, x, y, z, height, radius, crown, out);
   }

   /** A tree kind's leaves' reach: 8 grown by its own placers in the empty world, the mean of their farthest leaf. */
   private int footprint(Kind kind) {
      int sum = 0;
      int n = 0;
      for (int sample = 1; sample <= 8; sample++) {
         try {
            RandomSource random = new LegacyRandomSource(sample * 0x9E3779B97F4A7C15L);
            int height = kind.config.trunkPlacer.getTreeHeight(random);
            int crown = Math.max(1, kind.config.foliagePlacer.foliageHeight(random, height, kind.config));
            int radius = Math.max(1, kind.config.foliagePlacer.foliageRadius(random, height - crown));
            int[] far = {0};
            if (!com.wgaccel.platform.Mc.growTree(kind.config, random, height, BlockPos.ZERO, crown, radius, (pos, state) -> {
            }, (pos, state) -> far[0] = Math.max(far[0], Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ()))))) {
               return Math.max(1, radius);
            }

            sum += far[0];
            n++;
         } catch (RuntimeException | LinkageError odd) {
            // Left out of the mean.
         }
      }

      return n == 0 ? 2 : Math.max(1, Math.round(sum / (float)n));
   }

   /**
    * TreeFeature's room check: from the base up to one over the top, each height's square (the tree's minimumSize: 0 at
    * the base, 1 or 2 higher) must be free of ground; the free height then must reach the tree's (or its
    * minClippedHeight). Other kinds (templates, mushrooms, bushes): only their own column, which the ground leaves free.
    */
   boolean fits(Kind kind, int height, int x, int z, int base, java.util.function.IntBinaryOperator ground) {
      if (kind.config == null) {
         return true;
      }

      net.minecraft.world.level.levelgen.feature.featuresize.FeatureSize size = kind.config.minimumSize;
      int free = height;
      outer:
      for (int y = 0; y <= height + 1; y++) {
         int r = size.getSizeAtHeight(height, y);
         for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
               if (ground.applyAsInt(x + dx, z + dz) >= base + y) {
                  free = y - 2;
                  break outer;
               }
            }
         }
      }

      return free >= height || size.minClippedHeight().isPresent() && free >= size.minClippedHeight().getAsInt();
   }

   private int id(BlockState state) {
      Integer id = this.stateIds.get(state);
      if (id == null) {
         id = this.ids.applyAsInt(state);
         this.stateIds.put(state, id);
      }

      return id;
   }

   private static void mushroom(Kind kind, int x, int y, int z, int height, Out out) {
      int r = kind.capRadius;
      if (kind.mushroom == 1) {
         // Red: a dome, its sides 3 blocks down (no corners), its top one narrower.
         for (int dy = height - 3; dy <= height; dy++) {
            int rr = dy < height ? r : r - 1;
            for (int dx = -rr; dx <= rr; dx++) {
               for (int dz = -rr; dz <= rr; dz++) {
                  boolean edgeX = dx == -rr || dx == rr;
                  boolean edgeZ = dz == -rr || dz == rr;
                  if (dy >= height || edgeX != edgeZ) {
                     out.set(x + dx, y + dy, z + dz, kind.leaves, false);
                  }
               }
            }
         }
      } else {
         // Brown: one flat layer, corners cut.
         for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
               if (Math.abs(dx) != r || Math.abs(dz) != r) {
                  out.set(x + dx, y + height, z + dz, kind.leaves, false);
               }
            }
         }
      }

      for (int dy = 0; dy < height; dy++) {
         out.set(x, y + dy, z, kind.log, true);
      }
   }

   /** The simple shape: a trunk and a ball, cone or flat crown. */
   private static void basic(Kind kind, int x, int base, int z, int height, int radius, int crown, Out out) {
      int top = base + height - 1;
      int w = kind.wide ? 1 : 0;
      switch (kind.crown) {
         case CONE -> {
            for (int y = top + 1; y >= top + 1 - crown; y--) {
               disc(out, x, y, z, w, Math.min(radius, (top + 2 - y) / 2), kind.leaves);
            }
         }
         case FLAT -> {
            disc(out, x, top, z, w, radius, kind.leaves);
            disc(out, x, top + 1, z, w, Math.max(1, radius - 1), kind.leaves);
         }
         default -> {
            for (int y = top - crown + 1; y <= top + 1; y++) {
               disc(out, x, y, z, w, y >= top ? Math.max(1, radius - 1) : radius, kind.leaves);
            }
         }
      }

      for (int y = base; y <= top; y++) {
         for (int a = 0; a <= w; a++) {
            for (int b = 0; b <= w; b++) {
               out.set(x + a, y, z + b, kind.log, true);
            }
         }
      }
   }

   /** A round layer of leaves (radius r around the trunk, w + 1 wide), corners left out. */
   private static void disc(Out out, int x, int y, int z, int w, int r, int leaves) {
      for (int dx = -r; dx <= r + w; dx++) {
         int ex = dx < 0 ? dx : Math.max(0, dx - w);
         for (int dz = -r; dz <= r + w; dz++) {
            int ez = dz < 0 ? dz : Math.max(0, dz - w);
            if (ex * ex + ez * ez <= r * r + r) {
               out.set(x + dx, y, z + dz, leaves, false);
            }
         }
      }
   }

   private static long mix(long h) {
      h ^= h >>> 31;
      h *= 0xBF58476D1CE4E5B9L;
      h ^= h >>> 29;
      h *= 0x94D049BB133111EBL;
      return h ^ h >>> 32;
   }
}
