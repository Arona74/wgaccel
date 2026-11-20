package com.wgaccel.platform;

import com.wgaccel.mixin.access.AquiferStatusInvoker;
import com.wgaccel.mixin.access.BeardifierAccessor;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * The Minecraft calls whose names or forms differ between the versions the mod is made for, each in that version's own
 * form (versions/<mc>/common has one of these per version). This one is 1.21.1's.
 */
public final class Mc {
   /**
    * Whether a section's own recount (its constructor's) gives the counts block-by-block writing would: here no (the
    * recount counts fluid blocks twice as non-empty and only randomly ticking fluids; writing counts every fluid as
    * ticking), so sections made at once get the written counts set.
    */
   public static final boolean SECTION_RECOUNT_EXACT = false;

   /** The GPU fill's kernel for vanilla's NoiseBasedAquifer here: 1, 1.21.1's. */
   public static final int VANILLA_AQUIFER = 1;

   /** gpuNoiseFillVanilla's default: off (vanilla radius 2000: 2:15 without the mod, 2:29 with the GPU fill). */
   public static final boolean GPU_FILL_VANILLA_DEFAULT = false;

   /**
    * The default of 0.64.1's rewrites that have no live check (they use a feature's random or write blocks: fastPlacementStreams,
    * fastGeodes, fastCarvers, fastOreBlocks, fastSculkMoves, fastNoiseInterpolation): on here, where upstream measured them (FreeTerraForged worlds).
    */
   public static final boolean UNCHECKED_REWRITES = true;

   private Mc() {
   }

   /** No skipSamplingAboveY before 26.1: every height is sampled. */
   public static int skipSamplingAbove(Aquifer.NoiseBasedAquifer aquifer) {
      return Integer.MAX_VALUE;
   }

   public static int x(ChunkPos pos) {
      return pos.x;
   }

   public static int z(ChunkPos pos) {
      return pos.z;
   }

   /** The chunk's key (ChunkPos.toLong). */
   public static long key(ChunkPos pos) {
      return pos.toLong();
   }

   public static long key(int x, int z) {
      return ChunkPos.asLong(x, z);
   }

   public static ChunkPos chunk(long key) {
      return new ChunkPos(key);
   }

   /** The lowest block height. */
   public static int minY(LevelHeightAccessor height) {
      return height.getMinBuildHeight();
   }

   /** One above the highest block height. */
   public static int maxY(LevelHeightAccessor height) {
      return height.getMaxBuildHeight();
   }

   /** The lowest section's Y (in sections). */
   public static int minSection(LevelHeightAccessor height) {
      return height.getMinSection();
   }

   public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.registryOrThrow(key);
   }

   /** The registry's value for the key, null when it has none. */
   public static <T> T value(Registry<T> registry, ResourceKey<T> key) {
      return registry.get(key);
   }

   public static <T> Holder<T> holder(Registry<T> registry, ResourceKey<T> key) {
      return registry.getHolderOrThrow(key);
   }

   /** The value's id in the registry ("namespace:path"), null when it is not there. */
   public static <T> String name(Registry<T> registry, T value) {
      ResourceLocation id = registry.getKey(value);
      return id != null ? id.toString() : null;
   }

   /** The key's id ("namespace:path"). */
   /**
    * Whether the chunk is saved finished (status full) in the level's region files: false when it is not saved or saved at
    * an earlier step (the neighbours started around finished chunks, up to 8 chunks out for structure starts), true when it
    * cannot be read (never written over). Asynchronous: the reads of a tile's chunks go out together (one at a time through
    * the storage thread made the Voxy generator's first ring 3x slower).
    */
   public static java.util.concurrent.CompletableFuture<Boolean> savedFull(net.minecraft.server.level.ServerLevel level, int x, int z) {
      try {
         return level.getChunkSource().chunkMap.read(new ChunkPos(x, z)).handle((tag, failure) -> failure != null
            || tag.isPresent() && tag.get().getString("Status").endsWith("full"));
      } catch (RuntimeException failure) {
         return java.util.concurrent.CompletableFuture.completedFuture(true);
      }
   }

   public static String id(ResourceKey<?> key) {
      return key.location().toString();
   }

   /** The key's id's path. */
   public static String path(ResourceKey<?> key) {
      return key.location().getPath();
   }

   public static <T> ResourceKey<T> key(ResourceKey<? extends Registry<T>> registry, String namespace, String path) {
      return ResourceKey.create(registry, ResourceLocation.fromNamespaceAndPath(namespace, path));
   }

   /** RandomState's positional random factory named by id (an id as a density function or surface rule holds it). */
   public static Object randomFactory(RandomState random, Object id) {
      return random.getOrCreateRandomFactory((ResourceLocation)id);
   }

   /** The game's version, as its title screen shows it. */
   public static String version() {
      return SharedConstants.getCurrentVersion().getName();
   }

   /** Where players spawn in the level. */
   public static BlockPos spawn(ServerLevel level) {
      return level.getSharedSpawnPos();
   }

   /** An empty chunk at pos for running the noise on (no blocks, no structures). */
   public static ProtoChunk emptyChunk(ChunkPos pos, LevelHeightAccessor height, RegistryAccess access) {
      return new ProtoChunk(pos, UpgradeData.EMPTY, height, access.registryOrThrow(Registries.BIOME), null);
   }

   /** A beardifier with no structures in it (adds nothing to the density). */
   public static Beardifier noStructures() {
      return new Beardifier(new it.unimi.dsi.fastutil.objects.ObjectArrayList<Beardifier.Rigid>().iterator(),
         new it.unimi.dsi.fastutil.objects.ObjectArrayList<JigsawJunction>().iterator());
   }

   /** A section's block states, all fill. */
   public static PalettedContainer<BlockState> blockStates(BlockState fill) {
      return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, fill, PalettedContainer.Strategy.SECTION_STATES);
   }

   /** Construct a local palette directly; the caller already packed Minecraft's padded bit storage. */
   public static PalettedContainer<BlockState> packedBlockStates(BlockState[] palette, int size, int bits, long[] data) {
      var strategy = PalettedContainer.Strategy.SECTION_STATES;
      return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, strategy, strategy.getConfiguration(Block.BLOCK_STATE_REGISTRY, bits),
         new net.minecraft.util.SimpleBitStorage(bits, 4096, data), java.util.Arrays.asList(java.util.Arrays.copyOf(palette, size)));
   }

   /** A section's block states as saved. */
   public static PalettedContainerRO.PackedData<BlockState> pack(PalettedContainerRO<BlockState> states) {
      return states.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES);
   }

   /** Sets a block in a chunk being generated (not moved by a piston). */
   public static void setBlock(ChunkAccess chunk, BlockPos pos, BlockState state) {
      chunk.setBlockState(pos, state, false);
   }

   /** Whether it snows at pos in the biome (seaLevel: the world's, which 26.1.2 asks for). */
   public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, int seaLevel) {
      return biome.coldEnoughToSnow(pos);
   }

   /** The biome's temperature at pos, height adjusted (seaLevel: the world's, which 26.1.2 asks for). */
   public static float temperature(Biome biome, BlockPos pos, int seaLevel) {
      try {
         return (Float)Temperature.METHOD.invoke(biome, pos);
      } catch (ReflectiveOperationException failure) {
         throw new IllegalStateException(failure);
      }
   }

   /** Biome.getTemperature(BlockPos), private in 1.21.1 (public with the sea level in 26.1.2). */
   private static final class Temperature {
      static final java.lang.reflect.Method METHOD;

      static {
         try {
            METHOD = Names.declaredMethod(Biome.class, "getTemperature", BlockPos.class);
            METHOD.setAccessible(true);
         } catch (NoSuchMethodException failure) {
            throw new ExceptionInInitializerError(failure);
         }
      }
   }

   /** Whether it snows at pos in the biome; context: the surface rules' context there (for its sea level). */
   public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, Object context) {
      return biome.coldEnoughToSnow(pos);
   }

   /** The beardifier's structure pieces (its iterator left at the start, as found). */
   public static List<Beardifier.Rigid> pieces(Beardifier beardifier) {
      return drain(((BeardifierAccessor)beardifier).wgaccel$pieceIterator());
   }

   /** The beardifier's jigsaw junctions (its iterator left at the start, as found). */
   public static List<JigsawJunction> junctions(Beardifier beardifier) {
      return drain(((BeardifierAccessor)beardifier).wgaccel$junctionIterator());
   }

   private static <T> List<T> drain(ObjectListIterator<T> iterator) {
      iterator.back(Integer.MAX_VALUE);
      List<T> list = new ArrayList<>();
      while (iterator.hasNext()) {
         list.add(iterator.next());
      }

      iterator.back(Integer.MAX_VALUE);
      return list;
   }

   /** The aquifer's fluid status of a grid cell: index in its caches, packed its centre's position. */
   public static Aquifer.FluidStatus aquiferStatus(Aquifer.NoiseBasedAquifer aquifer, int index, long packed) {
      return ((AquiferStatusInvoker)(Object)aquifer).wgaccel$status(packed);
   }

   /** A section's block-state data as PalettedContainer.read takes it after the palette: the packed longs, with its length first. */
   public static void writeStateData(FriendlyByteBuf buffer, long[] data) {
      buffer.writeLongArray(data);
   }

   /** A features step's region was made, before any feature is placed (nothing here: Lithos is 26.1.2 only). */
   public static void featuresStart(net.minecraft.server.level.WorldGenRegion region) {
   }

   /** Block light attenuation (0..15) queried statically at BlockPos.ZERO. */
   public static int lightBlock(BlockState state) {
      return state.getLightBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
   }

   /** Directional face occlusion shape for lighting checks. */
   public static net.minecraft.world.phys.shapes.VoxelShape occlusionShape(BlockState state, net.minecraft.core.Direction dir) {
      return net.minecraft.world.level.lighting.LightEngine.getOcclusionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, dir);
   }
   /** The provider's block state at pos (level: the feature's level, which 26.1.2's providers take). */
   public static BlockState state(net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider provider, net.minecraft.world.level.WorldGenLevel level, net.minecraft.util.RandomSource random, BlockPos pos) {
      return provider.getState(random, pos);
   }

   /** The int provider's largest value. */
   public static int maxValue(net.minecraft.util.valueproviders.IntProvider provider) {
      return provider.getMaxValue();
   }

   /** ChunkAccess.setBlockState with 1.21.1's moved-by-piston flag (an update flag on 26.1.2). */
   public static BlockState setBlockState(ChunkAccess chunk, BlockPos pos, BlockState state, boolean moving) {
      return chunk.setBlockState(pos, state, moving);
   }

   /** A block getter over blocks, from minY up for height blocks, without block entities. */
   public static net.minecraft.world.level.BlockGetter blockGetter(int minY, int height, java.util.function.Function<BlockPos, BlockState> blocks) {
      return new net.minecraft.world.level.BlockGetter() {
         @Override
         public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
         }

         @Override
         public BlockState getBlockState(BlockPos pos) {
            return blocks.apply(pos);
         }

         @Override
         public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return blocks.apply(pos).getFluidState();
         }

         @Override
         public int getHeight() {
            return height;
         }

         @Override
         public int getMinBuildHeight() {
            return minY;
         }
      };
   }

   /** The blocks a geode's sample points may not land on (1.21.1: the global tag; GeodeFeature ignores the configured one). */
   public static net.minecraft.tags.TagKey<Block> geodeInvalidBlocks(net.minecraft.world.level.levelgen.GeodeBlockSettings settings) {
      return net.minecraft.tags.BlockTags.GEODE_INVALID_BLOCKS;
   }

   /** A block state provider's block at pos with no level (26.1.2 asks for one; the simple providers ignore it). */
   public static net.minecraft.world.level.block.state.BlockState providedState(
      net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider provider, net.minecraft.util.RandomSource random,
      net.minecraft.core.BlockPos pos) {
      return provider.getState(random, pos);
   }

   /**
    * A tree grown by its own trunk and foliage placers (TreeFeature's steps) in an empty world: logs and leaves handed out.
    * False when this version cannot (the caller draws a simple shape).
    */
   public static boolean growTree(net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration config,
      net.minecraft.util.RandomSource random, int height, net.minecraft.core.BlockPos pos, int foliageHeight, int foliageRadius,
      java.util.function.BiConsumer<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> logs,
      java.util.function.BiConsumer<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> leaves) {
      // NeoForge's TrunkPlacer.setDirtAt casts the world to LevelReader: an empty one that is both.
      net.minecraft.world.level.LevelSimulatedReader empty = com.wgaccel.gen.EmptyWorld.of(net.minecraft.world.level.LevelSimulatedReader.class,
         net.minecraft.world.level.LevelReader.class);
      net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer.FoliageSetter setter =
         new net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer.FoliageSetter() {
            @Override
            public void set(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
               leaves.accept(pos, state);
            }

            @Override
            public boolean isSet(net.minecraft.core.BlockPos pos) {
               return false;
            }
         };
      for (net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer.FoliageAttachment attachment
         : config.trunkPlacer.placeTrunk(empty, logs, random, height, pos, config)) {
         config.foliagePlacer.createFoliage(empty, setter, random, config, height, attachment, foliageHeight, foliageRadius);
      }

      return true;
   }

   /** A huge mushroom's cap provider, stem provider and cap radius. */
   public static Object[] mushroom(net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration config) {
      return new Object[]{config.capProvider, config.stemProvider, config.foliageRadius};
   }

   /** A random patch's tries, xz spread, placed feature and y spread (RandomPatchConfiguration), else null. */
   public static Object[] patch(net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration config) {
      if (config instanceof net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration patch) {
         return new Object[]{patch.tries(), patch.xzSpread(), patch.feature(), patch.ySpread()};
      }

      return null;
   }
}
