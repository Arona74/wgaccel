package com.wgaccel.platform;

import com.wgaccel.mixin.access.AquiferStatusInvoker;
import com.wgaccel.mixin.access.BeardifierAccessor;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * The Minecraft calls whose names or forms differ between the versions the mod is made for, each in that version's own
 * form (versions/<mc>/common has one of these per version). This one is 26.1.2's.
 */
public final class Mc {
   /** Each registry access's palette factory (ProtoChunk takes one; it makes codecs, so made once per world). */
   private static final Map<RegistryAccess, PalettedContainerFactory> FACTORIES = new WeakHashMap<>();

   /**
    * Whether a section's own recount (its constructor's) gives the counts block-by-block writing would: here yes (a
    * separate fluid count, and only randomly ticking fluids counted as ticking, in both), so sections made at once keep it.
    */
   public static final boolean SECTION_RECOUNT_EXACT = true;

   /** The GPU fill's kernel for vanilla's NoiseBasedAquifer here: 3, 26.1's (the global fluid above skipSamplingAboveY, four nearest cells). */
   public static final int VANILLA_AQUIFER = 3;

   private Mc() {
   }

   /** The aquifer's skipSamplingAboveY: above it computeSubstance gives the global fluid without sampling (vanilla's and C2ME's). */
   public static int skipSamplingAbove(Aquifer.NoiseBasedAquifer aquifer) {
      return ((AquiferStatusInvoker)(Object)aquifer).wgaccel$skipSamplingAboveY();
   }

   public static int x(ChunkPos pos) {
      return pos.x();
   }

   public static int z(ChunkPos pos) {
      return pos.z();
   }

   /** The chunk's key (ChunkPos.toLong on 1.21.1). */
   public static long key(ChunkPos pos) {
      return pos.pack();
   }

   public static long key(int x, int z) {
      return ChunkPos.pack(x, z);
   }

   public static ChunkPos chunk(long key) {
      return ChunkPos.unpack(key);
   }

   /** The lowest block height. */
   public static int minY(LevelHeightAccessor height) {
      return height.getMinY();
   }

   /** One above the highest block height (getMaxY is the highest itself). */
   public static int maxY(LevelHeightAccessor height) {
      return height.getMinY() + height.getHeight();
   }

   /** The lowest section's Y (in sections). */
   public static int minSection(LevelHeightAccessor height) {
      return height.getMinSectionY();
   }

   public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.lookupOrThrow(key);
   }

   /** The registry's value for the key, null when it has none. */
   public static <T> T value(Registry<T> registry, ResourceKey<T> key) {
      return registry.getValue(key);
   }

   public static <T> Holder<T> holder(Registry<T> registry, ResourceKey<T> key) {
      return registry.getOrThrow(key);
   }

   /** The value's id in the registry ("namespace:path"), null when it is not there. */
   public static <T> String name(Registry<T> registry, T value) {
      Identifier id = registry.getKey(value);
      return id != null ? id.toString() : null;
   }

   /** The key's id ("namespace:path"). */
   public static String id(ResourceKey<?> key) {
      return key.identifier().toString();
   }

   /** The key's id's path. */
   public static String path(ResourceKey<?> key) {
      return key.identifier().getPath();
   }

   public static <T> ResourceKey<T> key(ResourceKey<? extends Registry<T>> registry, String namespace, String path) {
      return ResourceKey.create(registry, Identifier.fromNamespaceAndPath(namespace, path));
   }

   /** RandomState's positional random factory named by id (an id as a density function or surface rule holds it). */
   public static Object randomFactory(RandomState random, Object id) {
      return random.getOrCreateRandomFactory((Identifier)id);
   }

   /** The game's version, as its title screen shows it. */
   public static String version() {
      return SharedConstants.getCurrentVersion().name();
   }

   /** Where players spawn in the level. */
   public static BlockPos spawn(ServerLevel level) {
      return level.getRespawnData().pos();
   }

   /** An empty chunk at pos for running the noise on (no blocks, no structures). */
   public static ProtoChunk emptyChunk(ChunkPos pos, LevelHeightAccessor height, RegistryAccess access) {
      PalettedContainerFactory factory;
      synchronized (FACTORIES) {
         factory = FACTORIES.computeIfAbsent(access, PalettedContainerFactory::create);
      }

      return new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
   }

   /** A beardifier with no structures in it (adds nothing to the density). */
   public static Beardifier noStructures() {
      return Beardifier.EMPTY;
   }

   /** A section's block states, all fill. */
   public static PalettedContainer<BlockState> blockStates(BlockState fill) {
      return new PalettedContainer<>(fill, States.STRATEGY);
   }

   /** A section's block states as saved. */
   public static PalettedContainerRO.PackedData<BlockState> pack(PalettedContainerRO<BlockState> states) {
      return states.pack(States.STRATEGY);
   }

   /** Sets a block in a chunk being generated (not moved by a piston). */
   public static void setBlock(ChunkAccess chunk, BlockPos pos, BlockState state) {
      chunk.setBlockState(pos, state);
   }

   /** Whether it snows at pos in the biome; context: the surface rules' context there (for its sea level). */
   public static boolean coldEnoughToSnow(Biome biome, BlockPos pos, Object context) {
      return biome.coldEnoughToSnow(pos, ((SurfaceRules.Context)context).getSeaLevel());
   }

   /** The block states' palette strategy, made once the blocks are registered. */
   private static final class States {
      static final Strategy<BlockState> STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
   }

   /** The beardifier's structure pieces. */
   public static List<Beardifier.Rigid> pieces(Beardifier beardifier) {
      return ((BeardifierAccessor)beardifier).wgaccel$pieces();
   }

   /** The beardifier's jigsaw junctions. */
   public static List<JigsawJunction> junctions(Beardifier beardifier) {
      return ((BeardifierAccessor)beardifier).wgaccel$junctions();
   }

   /** The aquifer's fluid status of a grid cell: index in its caches, packed its centre's position. */
   public static Aquifer.FluidStatus aquiferStatus(Aquifer.NoiseBasedAquifer aquifer, int index, long packed) {
      return ((AquiferStatusInvoker)(Object)aquifer).wgaccel$status(index);
   }

   /** A section's block-state data as PalettedContainer.read takes it after the palette: the packed longs, without a length (the reader knows it from the bits). */
   public static void writeStateData(FriendlyByteBuf buffer, long[] data) {
      buffer.writeFixedSizeLongArray(data);
   }
}
