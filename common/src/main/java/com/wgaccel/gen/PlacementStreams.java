package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

/**
 * Direct depth-first push evaluation for {@code PlacedFeature.placeWithContext} (config {@code fastPlacementStreams}).
 * Evaluates standard vanilla {@link PlacementModifier} chains using primitive {@code (x, y, z)} coordinates and
 * compiled {@link FastPredicates}, eliminating nested {@code Stream.flatMap} pipelines and per-attempt
 * {@link BlockPos} allocations while preserving exact RNG order, null-stream semantics, and fallback behavior.
 */
public final class PlacementStreams {
   private static final boolean FTF_LOADED = detectFtf();
   private static final ConcurrentHashMap<PlacementModifier, Step> PRIMITIVE_STEPS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlacementModifier, Step> FTF_STEPS = new ConcurrentHashMap<>();
   private static volatile FtfCache ftfCache;
   private static volatile boolean ftfReflectFailed;

   private record FtfCache(Object placedFeatures, IdentityHashMap<PlacedFeature, Boolean> hooked) {
   }

   private static final class FtfHooks {
      private static volatile boolean resolved;
      private static volatile Class<?> genClass;
      private static volatile Method planMethod;
      private static volatile Method placedFeaturesMethod;
      private static volatile Method surfaceMapMethod;
      private static volatile Method chunkLocalMapMethod;
      private static volatile Method oreMapMethod;
      private static volatile Method surfaceEligibleMethod;
      private static volatile Method chunkLocalEligibleMethod;

      private static boolean ensureResolved() {
         if (resolved) {
            return genClass != null;
         }
         synchronized (FtfHooks.class) {
            if (resolved) {
               return genClass != null;
            }
            try {
               Class<?> gc = Names.forName("etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator");
               Method pm = gc.getMethod("plan");
               Class<?> planClass = Names.forName("etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlan");
               Method pfm = planClass.getMethod("placedFeatures");
               Class<?> pfClass = Names.forName("etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans$PlacedFeatures");
               Method sm = pfClass.getMethod("surfaceClassifications");
               Method cm = pfClass.getMethod("chunkLocalClassifications");
               Method om = pfClass.getMethod("oreTransforms");
               Class<?> surfClass = Names.forName("etcodehome.freeterraforged.world.worldgen.feature.placement.SurfacePlacementClassifier$Classification");
               Method se = surfClass.getMethod("eligible");
               Class<?> clClass = Names.forName("etcodehome.freeterraforged.world.worldgen.feature.placement.ChunkLocalPlacementClassifier$Classification");
               Method cle = clClass.getMethod("eligible");
               planMethod = pm;
               placedFeaturesMethod = pfm;
               surfaceMapMethod = sm;
               chunkLocalMapMethod = cm;
               oreMapMethod = om;
               surfaceEligibleMethod = se;
               chunkLocalEligibleMethod = cle;
               genClass = gc;
            } catch (Throwable failure) {
               if (!ftfReflectFailed) {
                  ftfReflectFailed = true;
                  WorldgenAccelerator.LOG.warn("WGA placement streams: FreeTerraForged eligibility reflection failed (using conservative FTF steps): {}",
                     failure.toString());
               }
            } finally {
               resolved = true;
            }
            return genClass != null;
         }
      }

      static boolean isHooked(PlacedFeature self, PlacementContext context) {
         if (self == null || ftfReflectFailed || !ensureResolved()) {
            return true;
         }
         ChunkGenerator generator = context.generator();
         if (generator == null || !genClass.isInstance(generator)) {
            return false;
         }
         try {
            @SuppressWarnings("unchecked")
            Optional<Object> planOpt = (Optional<Object>)planMethod.invoke(generator);
            if (planOpt == null || planOpt.isEmpty()) {
               return true;
            }
            Object placedFeatures = placedFeaturesMethod.invoke(planOpt.get());
            if (placedFeatures == null) {
               return true;
            }
            FtfCache cache = ftfCache;
            if (cache == null || cache.placedFeatures != placedFeatures) {
               cache = buildCache(placedFeatures);
               ftfCache = cache;
            }
            if (cache.hooked.containsKey(self)) {
               return true;
            }
            Optional<PlacedFeature> top = context.topFeature();
            if (top.isPresent()) {
               PlacedFeature topFeature = top.get();
               if (topFeature != self && cache.hooked.containsKey(topFeature)) {
                  return true;
               }
            }
            return false;
         } catch (Throwable failure) {
            if (!ftfReflectFailed) {
               ftfReflectFailed = true;
               WorldgenAccelerator.LOG.warn("WGA placement streams: FreeTerraForged eligibility check failed (using conservative FTF steps): {}",
                  failure.toString());
            }
            return true;
         }
      }

      private static synchronized FtfCache buildCache(Object placedFeatures) throws ReflectiveOperationException {
         FtfCache existing = ftfCache;
         if (existing != null && existing.placedFeatures == placedFeatures) {
            return existing;
         }
         IdentityHashMap<PlacedFeature, Boolean> hooked = new IdentityHashMap<>();
         Map<?, ?> surfaces = (Map<?, ?>)surfaceMapMethod.invoke(placedFeatures);
         if (surfaces != null) {
            for (Map.Entry<?, ?> entry : surfaces.entrySet()) {
               if (entry.getKey() instanceof PlacedFeature pf && entry.getValue() != null
                     && Boolean.TRUE.equals(surfaceEligibleMethod.invoke(entry.getValue()))) {
                  hooked.put(pf, Boolean.TRUE);
               }
            }
         }
         Map<?, ?> chunkLocals = (Map<?, ?>)chunkLocalMapMethod.invoke(placedFeatures);
         if (chunkLocals != null) {
            for (Map.Entry<?, ?> entry : chunkLocals.entrySet()) {
               if (entry.getKey() instanceof PlacedFeature pf && entry.getValue() != null
                     && Boolean.TRUE.equals(chunkLocalEligibleMethod.invoke(entry.getValue()))) {
                  hooked.put(pf, Boolean.TRUE);
               }
            }
         }
         Map<?, ?> ores = (Map<?, ?>)oreMapMethod.invoke(placedFeatures);
         if (ores != null) {
            for (Map.Entry<?, ?> entry : ores.entrySet()) {
               if (entry.getKey() instanceof PlacedFeature pf && entry.getValue() != null) {
                  hooked.put(pf, Boolean.TRUE);
               }
            }
         }
         WorldgenAccelerator.LOG.debug("WGA placement streams: indexed {} FTF-hooked features", hooked.size());
         return new FtfCache(placedFeatures, hooked);
      }
   }

   private static boolean detectFtf() {
      try {
         return com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static volatile Field countField;
   private static volatile Field heightmapField;
   private static volatile Field xzSpreadField;
   private static volatile Field ySpreadField;
   private static volatile Field heightProviderField;
   private static volatile Field chanceField;
   private static volatile Field predicateField;
   private static volatile Field waterDepthField;
   private static volatile Field relHeightmapField;
   private static volatile Field relMinField;
   private static volatile Field relMaxField;
   private static volatile Field scanDirField;
   private static volatile Field scanTargetField;
   private static volatile Field scanAllowedField;
   private static volatile Field scanMaxStepsField;
   private static volatile Field noiseRatioField;
   private static volatile Field noiseFactorField;
   private static volatile Field noiseOffsetField;
   private static volatile Field thresholdLevelField;
   private static volatile Field thresholdBelowField;
   private static volatile Field thresholdAboveField;

   private PlacementStreams() {
   }

   /** Immutable compiled step arrays (zero-Stream primitive steps and conservative FTF-compatible steps). */
   public static final class Plan {
      final Step[] primitiveSteps;
      final boolean hasPrimitiveFallback;
      final Step[] ftfSteps;
      final boolean hasFtfFallback;

      Plan(Step[] primitiveSteps, boolean hasPrimitiveFallback, Step[] ftfSteps, boolean hasFtfFallback) {
         this.primitiveSteps = primitiveSteps;
         this.hasPrimitiveFallback = hasPrimitiveFallback;
         this.ftfSteps = ftfSteps;
         this.hasFtfFallback = hasFtfFallback;
      }
   }

   /** Pre-compiles modifier array into typed push steps for zero-Stream or BlockPos-native dispatch. */
   public static Plan compile(PlacementModifier[] modifiers) {
      Step[] primitiveSteps = new Step[modifiers.length];
      boolean hasPrimitiveFallback = false;
      for (int i = 0; i < modifiers.length; i++) {
         Step step = compileStep(modifiers[i], false);
         primitiveSteps[i] = step;
         if (step instanceof FallbackStep) {
            hasPrimitiveFallback = true;
         }
      }
      if (!FTF_LOADED) {
         return new Plan(primitiveSteps, hasPrimitiveFallback, primitiveSteps, hasPrimitiveFallback);
      }

      Step[] ftfSteps = new Step[modifiers.length];
      boolean hasFtfFallback = false;
      for (int i = 0; i < modifiers.length; i++) {
         Step step = compileStep(modifiers[i], true);
         ftfSteps[i] = step;
         if (step instanceof FallbackStep) {
            hasFtfFallback = true;
         }
      }
      return new Plan(primitiveSteps, hasPrimitiveFallback, ftfSteps, hasFtfFallback);
   }

   /** Runs all placement modifiers in order and places the feature at each resulting position. */
   public static boolean place(PlacementModifier[] modifiers, PlacementContext context, RandomSource random, ConfiguredFeature<?, ?> feature, BlockPos pos) {
      return place(null, compile(modifiers), context, random, feature, pos);
   }

   /** Runs pre-compiled placement steps using primitive dispatch when Stream-free or BlockPos-native dispatch when FTF hooks or fallbacks apply. */
   public static boolean place(PlacedFeature self, Plan plan, PlacementContext context, RandomSource random, ConfiguredFeature<?, ?> feature, BlockPos pos) {
      boolean useFtf = FTF_LOADED && FtfHooks.isHooked(self, context);
      Step[] steps = useFtf ? plan.ftfSteps : plan.primitiveSteps;
      boolean hasFallback = useFtf ? plan.hasFtfFallback : plan.hasPrimitiveFallback;
      if (steps.length == 0) {
         return feature.place(context.getLevel(), context.generator(), random, pos);
      }

      if (hasFallback) {
         PosRunner runner = new PosRunner(steps, context, random, feature);
         try {
            runner.dispatch(0, pos);
            return runner.placed;
         } finally {
            runner.close();
         }
      }

      Runner runner = new Runner(steps, context, random, feature);
      try {
         runner.dispatch(0, pos.getX(), pos.getY(), pos.getZ());
         return runner.placed;
      } finally {
         runner.close();
      }
   }

   private static Step compileStep(PlacementModifier modifier, boolean ftfHooked) {
      ConcurrentHashMap<PlacementModifier, Step> cache = ftfHooked ? FTF_STEPS : PRIMITIVE_STEPS;
      if (cache.size() > 8192) {
         cache.clear();
      }
      return cache.computeIfAbsent(modifier, m -> buildStep(m, ftfHooked));
   }

   private static Step buildStep(PlacementModifier mod, boolean ftfHooked) {
      try {
         Class<?> cls = mod.getClass();
         if (!ftfHooked) {
            if (cls == CountPlacement.class) {
               Field f = field(countField, cls, "count");
               countField = f;
               return new CountStep((IntProvider)f.get(mod));
            }
            if (cls == InSquarePlacement.class) {
               return InSquareStep.INSTANCE;
            }
            if (cls == RandomOffsetPlacement.class) {
               Field fxz = field(xzSpreadField, cls, "xzSpread");
               xzSpreadField = fxz;
               Field fy = field(ySpreadField, cls, "ySpread");
               ySpreadField = fy;
               return new RandomOffsetStep((IntProvider)fxz.get(mod), (IntProvider)fy.get(mod));
            }
            if (cls == HeightRangePlacement.class) {
               Field f = field(heightProviderField, cls, "height");
               heightProviderField = f;
               return new HeightRangeStep((HeightProvider)f.get(mod));
            }
            if (cls == RarityFilter.class) {
               Field f = field(chanceField, cls, "chance");
               chanceField = f;
               return new RarityStep(f.getInt(mod));
            }
            if (cls == EnvironmentScanPlacement.class) {
               Field fd = field(scanDirField, cls, "directionOfSearch");
               scanDirField = fd;
               Field ft = field(scanTargetField, cls, "targetCondition");
               scanTargetField = ft;
               Field fa = field(scanAllowedField, cls, "allowedSearchCondition");
               scanAllowedField = fa;
               Field fm = field(scanMaxStepsField, cls, "maxSteps");
               scanMaxStepsField = fm;
               BlockPredicate rawTarget = (BlockPredicate)ft.get(mod);
               BlockPredicate rawAllowed = (BlockPredicate)fa.get(mod);
               return new EnvironmentScanStep((Direction)fd.get(mod), rawTarget, FastPredicates.compile(rawTarget),
                  rawAllowed, FastPredicates.compile(rawAllowed), fm.getInt(mod));
            }
         }
         if (cls == HeightmapPlacement.class) {
            Field f = field(heightmapField, cls, "heightmap");
            heightmapField = f;
            return new HeightmapStep((Heightmap.Types)f.get(mod));
         }
         if (cls == BiomeFilter.class) {
            return BiomeStep.INSTANCE;
         }
         if (cls == BlockPredicateFilter.class) {
            Field f = field(predicateField, cls, "predicate");
            predicateField = f;
            BlockPredicate raw = (BlockPredicate)f.get(mod);
            return new PredicateStep(raw, FastPredicates.compile(raw));
         }
         if (cls == SurfaceWaterDepthFilter.class) {
            Field f = field(waterDepthField, cls, "maxWaterDepth");
            waterDepthField = f;
            return new SurfaceWaterDepthStep(f.getInt(mod));
         }
         if (cls == SurfaceRelativeThresholdFilter.class) {
            Field fh = field(relHeightmapField, cls, "heightmap");
            relHeightmapField = fh;
            Field fmin = field(relMinField, cls, "minInclusive");
            relMinField = fmin;
            Field fmax = field(relMaxField, cls, "maxInclusive");
            relMaxField = fmax;
            return new SurfaceRelativeStep((Heightmap.Types)fh.get(mod), fmin.getInt(mod), fmax.getInt(mod));
         }
         if (cls == NoiseBasedCountPlacement.class) {
            Field fr = field(noiseRatioField, cls, "noiseToCountRatio");
            noiseRatioField = fr;
            Field ff = field(noiseFactorField, cls, "noiseFactor");
            noiseFactorField = ff;
            Field fo = field(noiseOffsetField, cls, "noiseOffset");
            noiseOffsetField = fo;
            return new NoiseBasedCountStep(fr.getInt(mod), ff.getDouble(mod), fo.getDouble(mod));
         }
         if (cls == NoiseThresholdCountPlacement.class) {
            Field fl = field(thresholdLevelField, cls, "noiseLevel");
            thresholdLevelField = fl;
            Field fb = field(thresholdBelowField, cls, "belowNoise");
            thresholdBelowField = fb;
            Field fa = field(thresholdAboveField, cls, "aboveNoise");
            thresholdAboveField = fa;
            return new NoiseThresholdCountStep(fl.getDouble(mod), fb.getInt(mod), fa.getInt(mod));
         }
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.debug("WGA placement modifier fallback for {}: {}", Names.className(mod.getClass()), failure.toString());
      }
      return new FallbackStep(mod);
   }

   private static Field field(Field cached, Class<?> owner, String name) throws NoSuchFieldException {
      if (cached != null) {
         return cached;
      }
      Field f = Names.declaredField(owner, name);
      f.setAccessible(true);
      return f;
   }

   private abstract static class RunnerBase {
      final Step[] steps;
      final PlacementContext context;
      final WorldGenLevel level;
      final ChunkGenerator generator;
      final RandomSource random;
      final ConfiguredFeature<?, ?> feature;
      final int minY;
      FastPredicates.EvalContext predCtx;
      boolean predChecked;
      BlockPos.MutableBlockPos mpos;
      boolean placed;

      RunnerBase(Step[] steps, PlacementContext context, RandomSource random, ConfiguredFeature<?, ?> feature) {
         this.steps = steps;
         this.context = context;
         this.level = context.getLevel();
         this.generator = context.generator();
         this.random = random;
         this.feature = feature;
         this.minY = context.getMinBuildHeight();
      }

      FastPredicates.EvalContext predCtx() {
         if (!this.predChecked) {
            this.predChecked = true;
            if (FastPredicates.on()) {
               this.predCtx = FastPredicates.begin(this.level);
            }
         }
         return this.predCtx;
      }

      BlockPos.MutableBlockPos mpos() {
         if (this.mpos == null) {
            this.mpos = new BlockPos.MutableBlockPos();
         }
         return this.mpos;
      }

      void close() {
         if (this.predCtx != null) {
            this.predCtx.end();
         }
      }
   }

   private static final class Runner extends RunnerBase {
      Runner(Step[] steps, PlacementContext context, RandomSource random, ConfiguredFeature<?, ?> feature) {
         super(steps, context, random, feature);
      }

      void dispatch(int index, int x, int y, int z) {
         if (index == this.steps.length) {
            if (this.feature.place(this.level, this.generator, this.random, new BlockPos(x, y, z))) {
               this.placed = true;
            }
            return;
         }

         this.steps[index].run(this, index + 1, x, y, z);
      }
   }

   private static final class PosRunner extends RunnerBase implements Consumer<BlockPos> {
      int nextIdx;

      PosRunner(Step[] steps, PlacementContext context, RandomSource random, ConfiguredFeature<?, ?> feature) {
         super(steps, context, random, feature);
      }

      @Override
      public void accept(BlockPos outPos) {
         this.dispatch(this.nextIdx, outPos);
      }

      void dispatch(int index, BlockPos pos) {
         if (index == this.steps.length) {
            if (this.feature.place(this.level, this.generator, this.random, pos)) {
               this.placed = true;
            }
            return;
         }

         this.steps[index].runPos(this, index + 1, pos);
      }
   }

   public sealed interface Step permits CountStep, InSquareStep, HeightmapStep, RandomOffsetStep, HeightRangeStep,
         BiomeStep, RarityStep, PredicateStep, SurfaceWaterDepthStep, SurfaceRelativeStep, EnvironmentScanStep,
         NoiseBasedCountStep, NoiseThresholdCountStep, FallbackStep {
      void run(Runner runner, int nextIdx, int x, int y, int z);

      void runPos(PosRunner runner, int nextIdx, BlockPos pos);
   }

   private static final class CountStep implements Step {
      final IntProvider count;

      CountStep(IntProvider count) {
         this.count = count;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int n = this.count.sample(r.random);
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int n = this.count.sample(r.random);
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, pos);
         }
      }
   }

   private static final class InSquareStep implements Step {
      static final InSquareStep INSTANCE = new InSquareStep();

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int nx = r.random.nextInt(16) + x;
         int nz = r.random.nextInt(16) + z;
         r.dispatch(nextIdx, nx, y, nz);
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int nx = r.random.nextInt(16) + pos.getX();
         int nz = r.random.nextInt(16) + pos.getZ();
         r.dispatch(nextIdx, new BlockPos(nx, pos.getY(), nz));
      }
   }

   private static final class HeightmapStep implements Step {
      final Heightmap.Types heightmap;

      HeightmapStep(Heightmap.Types heightmap) {
         this.heightmap = heightmap;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int hy = r.context.getHeight(this.heightmap, x, z);
         if (hy > r.minY) {
            r.dispatch(nextIdx, x, hy, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int hy = r.context.getHeight(this.heightmap, pos.getX(), pos.getZ());
         if (hy > r.minY) {
            r.dispatch(nextIdx, hy == pos.getY() ? pos : new BlockPos(pos.getX(), hy, pos.getZ()));
         }
      }
   }

   private static final class RandomOffsetStep implements Step {
      final IntProvider xzSpread;
      final IntProvider ySpread;

      RandomOffsetStep(IntProvider xzSpread, IntProvider ySpread) {
         this.xzSpread = xzSpread;
         this.ySpread = ySpread;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int nx = x + this.xzSpread.sample(r.random);
         int ny = y + this.ySpread.sample(r.random);
         int nz = z + this.xzSpread.sample(r.random);
         r.dispatch(nextIdx, nx, ny, nz);
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int dx = this.xzSpread.sample(r.random);
         int dy = this.ySpread.sample(r.random);
         int dz = this.xzSpread.sample(r.random);
         r.dispatch(nextIdx, (dx == 0 && dy == 0 && dz == 0) ? pos : pos.offset(dx, dy, dz));
      }
   }

   private static final class HeightRangeStep implements Step {
      final HeightProvider height;

      HeightRangeStep(HeightProvider height) {
         this.height = height;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int hy = this.height.sample(r.random, r.context);
         r.dispatch(nextIdx, x, hy, z);
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int hy = this.height.sample(r.random, r.context);
         r.dispatch(nextIdx, hy == pos.getY() ? pos : new BlockPos(pos.getX(), hy, pos.getZ()));
      }
   }

   private static final class BiomeStep implements Step {
      static final BiomeStep INSTANCE = new BiomeStep();

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         if (matches(r, r.mpos().set(x, y, z))) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         if (matches(r, pos)) {
            r.dispatch(nextIdx, pos);
         }
      }

      private static boolean matches(RunnerBase r, BlockPos pos) {
         Optional<PlacedFeature> top = r.context.topFeature();
         if (top.isEmpty()) {
            throw new IllegalStateException("Tried to biome check an unregistered feature, or a feature that should not restrict the biome");
         }
         Holder<Biome> biome = r.level.getBiome(pos);
         return r.generator.getBiomeGenerationSettings(biome).hasFeature(top.get());
      }
   }

   private static final class RarityStep implements Step {
      final int chance;

      RarityStep(int chance) {
         this.chance = chance;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         if (r.random.nextFloat() < 1.0F / (float)this.chance) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         if (r.random.nextFloat() < 1.0F / (float)this.chance) {
            r.dispatch(nextIdx, pos);
         }
      }
   }

   private static final class PredicateStep implements Step {
      final BlockPredicate raw;
      final FastPredicates.Node compiled;

      PredicateStep(BlockPredicate raw, FastPredicates.Node compiled) {
         this.raw = raw;
         this.compiled = compiled;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         FastPredicates.EvalContext ctx = r.predCtx();
         boolean pass = ctx != null ? ctx.eval(this.compiled, x, y, z) : this.raw.test(r.level, r.mpos().set(x, y, z));
         if (pass) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         FastPredicates.EvalContext ctx = r.predCtx();
         boolean pass = ctx != null
               ? ctx.eval(this.compiled, pos.getX(), pos.getY(), pos.getZ())
               : this.raw.test(r.level, pos);
         if (pass) {
            r.dispatch(nextIdx, pos);
         }
      }
   }

   private static final class SurfaceWaterDepthStep implements Step {
      final int maxWaterDepth;

      SurfaceWaterDepthStep(int maxWaterDepth) {
         this.maxWaterDepth = maxWaterDepth;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         if (matches(r, x, z)) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         if (matches(r, pos.getX(), pos.getZ())) {
            r.dispatch(nextIdx, pos);
         }
      }

      private boolean matches(RunnerBase r, int x, int z) {
         int ocean = r.context.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
         int surface = r.context.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
         return surface - ocean <= this.maxWaterDepth;
      }
   }

   private static final class SurfaceRelativeStep implements Step {
      final Heightmap.Types heightmap;
      final int minInclusive;
      final int maxInclusive;

      SurfaceRelativeStep(Heightmap.Types heightmap, int minInclusive, int maxInclusive) {
         this.heightmap = heightmap;
         this.minInclusive = minInclusive;
         this.maxInclusive = maxInclusive;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         if (matches(r, x, y, z)) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         if (matches(r, pos.getX(), pos.getY(), pos.getZ())) {
            r.dispatch(nextIdx, pos);
         }
      }

      private boolean matches(RunnerBase r, int x, int y, int z) {
         long base = r.context.getHeight(this.heightmap, x, z);
         return (long)y >= base + (long)this.minInclusive && (long)y <= base + (long)this.maxInclusive;
      }
   }

   private static final class EnvironmentScanStep implements Step {
      final int dx, dy, dz;
      final BlockPredicate rawTarget;
      final FastPredicates.Node target;
      final BlockPredicate rawAllowed;
      final FastPredicates.Node allowed;
      final int maxSteps;

      EnvironmentScanStep(Direction dir, BlockPredicate rawTarget, FastPredicates.Node target,
            BlockPredicate rawAllowed, FastPredicates.Node allowed, int maxSteps) {
         this.dx = dir.getStepX();
         this.dy = dir.getStepY();
         this.dz = dir.getStepZ();
         this.rawTarget = rawTarget;
         this.target = target;
         this.rawAllowed = rawAllowed;
         this.allowed = allowed;
         this.maxSteps = maxSteps;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         long packed = scan(r, x, y, z);
         if (packed != Long.MIN_VALUE) {
            r.dispatch(nextIdx, BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         long packed = scan(r, pos.getX(), pos.getY(), pos.getZ());
         if (packed != Long.MIN_VALUE) {
            int cx = BlockPos.getX(packed);
            int cy = BlockPos.getY(packed);
            int cz = BlockPos.getZ(packed);
            r.dispatch(nextIdx, (cx == pos.getX() && cy == pos.getY() && cz == pos.getZ()) ? pos : new BlockPos(cx, cy, cz));
         }
      }

      private long scan(RunnerBase r, int x, int y, int z) {
         int cx = x, cy = y, cz = z;
         if (!test(r, this.allowed, this.rawAllowed, cx, cy, cz)) {
            return Long.MIN_VALUE;
         }

         for (int i = 0; i < this.maxSteps; i++) {
            if (test(r, this.target, this.rawTarget, cx, cy, cz)) {
               return BlockPos.asLong(cx, cy, cz);
            }
            cx += this.dx;
            cy += this.dy;
            cz += this.dz;
            if (r.level.isOutsideBuildHeight(cy)) {
               return Long.MIN_VALUE;
            }
            if (!test(r, this.allowed, this.rawAllowed, cx, cy, cz)) {
               break;
            }
         }

         return test(r, this.target, this.rawTarget, cx, cy, cz) ? BlockPos.asLong(cx, cy, cz) : Long.MIN_VALUE;
      }

      private static boolean test(RunnerBase r, FastPredicates.Node node, BlockPredicate raw, int x, int y, int z) {
         FastPredicates.EvalContext ctx = r.predCtx();
         if (ctx != null) {
            return ctx.eval(node, x, y, z);
         }
         return raw.test(r.level, r.mpos().set(x, y, z));
      }
   }

   private static final class NoiseBasedCountStep implements Step {
      final int noiseToCountRatio;
      final double noiseFactor;
      final double noiseOffset;

      NoiseBasedCountStep(int noiseToCountRatio, double noiseFactor, double noiseOffset) {
         this.noiseToCountRatio = noiseToCountRatio;
         this.noiseFactor = noiseFactor;
         this.noiseOffset = noiseOffset;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int n = count(x, z);
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int n = count(pos.getX(), pos.getZ());
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, pos);
         }
      }

      private int count(int x, int z) {
         double d = Biome.BIOME_INFO_NOISE.getValue((double)x / this.noiseFactor, (double)z / this.noiseFactor, false);
         return (int)Math.ceil((d + this.noiseOffset) * (double)this.noiseToCountRatio);
      }
   }

   private static final class NoiseThresholdCountStep implements Step {
      final double noiseLevel;
      final int belowNoise;
      final int aboveNoise;

      NoiseThresholdCountStep(double noiseLevel, int belowNoise, int aboveNoise) {
         this.noiseLevel = noiseLevel;
         this.belowNoise = belowNoise;
         this.aboveNoise = aboveNoise;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         int n = count(x, z);
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, x, y, z);
         }
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int n = count(pos.getX(), pos.getZ());
         for (int i = 0; i < n; i++) {
            r.dispatch(nextIdx, pos);
         }
      }

      private int count(int x, int z) {
         double d = Biome.BIOME_INFO_NOISE.getValue((double)x / 200.0D, (double)z / 200.0D, false);
         return d < this.noiseLevel ? this.belowNoise : this.aboveNoise;
      }
   }

   private static final class FallbackStep implements Step {
      final PlacementModifier modifier;

      FallbackStep(PlacementModifier modifier) {
         this.modifier = modifier;
      }

      @Override
      public void run(Runner r, int nextIdx, int x, int y, int z) {
         throw new UnsupportedOperationException("FallbackStep must run via PosRunner");
      }

      @Override
      public void runPos(PosRunner r, int nextIdx, BlockPos pos) {
         int prevNext = r.nextIdx;
         r.nextIdx = nextIdx;
         try (Stream<BlockPos> stream = this.modifier.getPositions(r.context, r.random, pos)) {
            if (stream != null) {
               stream.sequential().forEach(r);
            }
         } finally {
            r.nextIdx = prevNext;
         }
      }
   }
}
