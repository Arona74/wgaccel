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
   private record Source(int index, List<PlacementModifier> placement, float[] chances, Kind[] kinds, PlacementContext context, Pick[] picks) {
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

   LodTrees(ToIntFunction<BlockState> ids, ServerLevel level) {
      this.ids = ids;
      this.level = level;
      this.seed = level.getSeed();
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
               this.tree(kind, x, z, own, out);
            } else {
               // The chosen entry's own counts and spread, from this spot (as the game runs a nested placed feature).
               for (BlockPos at : this.spots(kind.inner, source.context, own, spot)) {
                  this.tree(kind, at.getX(), at.getZ(), new LegacyRandomSource(mix(own.nextLong() ^ mix(at.getX() * 0x632BE59BD9B4E019L + at.getZ()))), out);
               }
            }
         }
      }
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
         for (Holder<PlacedFeature> placed : vegetation) {
            index++;
            List<float[]> chances = new ArrayList<>();
            List<Kind> kinds = new ArrayList<>();
            this.collect(placed.value(), 1.0F, chances, kinds, 0);
            if (kinds.isEmpty()) {
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
            List<String> skipped = new ArrayList<>();
            for (PlacementModifier modifier : placed.value().placement()) {
               if (this.counted(modifier)) {
                  placement.add(modifier);
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
            }

            described.add(placed.getRegisteredName() + " [" + trees + "; counted by "
               + placement.stream().map(m -> m.getClass().getSimpleName()).toList() + ", not checked " + skipped + "]");

            float[] chance = new float[kinds.size()];
            for (int i = 0; i < chance.length; i++) {
               chance[i] = chances.get(i)[0];
            }

            PlacementContext context = new PlacementContext(this.level, this.level.getChunkSource().getGenerator(), Optional.of(placed.value()));
            found.add(new Source(index, List.copyOf(placement), chance, kinds.toArray(new Kind[0]), context, this.picks(placed.value())));
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
      for (PlacementModifier modifier : placed.placement()) {
         if (this.counted(modifier)) {
            mine.add(modifier);
         }
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
