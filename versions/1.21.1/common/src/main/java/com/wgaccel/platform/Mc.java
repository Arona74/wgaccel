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

   /** A section's block states as saved. */
   public static PalettedContainerRO.PackedData<BlockState> pack(PalettedContainerRO<BlockState> states) {
      return states.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES);
   }

   /** Sets a block in a chunk being generated (not moved by a piston). */
   public static void setBlock(ChunkAccess chunk, BlockPos pos, BlockState state) {
      chunk.setBlockState(pos, state, false);
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
}
