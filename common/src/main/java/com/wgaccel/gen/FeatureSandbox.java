package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Mods' own features run as they are, outside a world, for the Voxy LODs (Conquest of Arborea's forests: their spots,
 * kinds and schematics are all its own code; Lithos's rock and ground cover). The level it gets is a stand-in: blocks read from the LOD columns (the
 * ground, its top block, water up to the surface, air above, and the blocks the feature set so far), heightmaps from the
 * ground; the blocks it sets are kept. Nothing of the real world is read or written. A call the stand-in cannot answer
 * is logged once by name (the feature may then fail: its trees are left out).
 */
final class FeatureSandbox {
   /** The columns around: block column (x, z) to its ground (top solid block), surface (water top) and blocks. */
   interface Columns {
      int ground(int x, int z);

      int surface(int x, int z);

      /** The ground's block k under its top (0: the top block; null: stone). */
      BlockState layer(int x, int z, int k);

      /** The fluid block over the ground (null: water). */
      BlockState fluid(int x, int z);

      /** The column's biome. */
      Holder<Biome> biome(int x, int z);
   }

   /** A block the feature set: absolute position, its state. */
   interface Sink {
      /** run: the index in the runs of the feature that set it last. */
      void set(int x, int y, int z, BlockState state, int run);
   }

   private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
   private final ServerLevel level;
   private final ChunkGenerator generator;
   private final long seed;
   private final int minY;
   private final int height;

   FeatureSandbox(ServerLevel level) {
      this.level = level;
      this.generator = level.getChunkSource().getGenerator();
      this.seed = level.getSeed();
      this.minY = com.wgaccel.platform.Mc.minY(level);
      this.height = level.getHeight();
   }

   /**
    * Runs the features (in their order: each sees what the ones before set) at chunk (cx, cz) as the game's decoration
    * steps start them (the chunk's corner at the bottom of the world, the random of their index in their step), then hands
    * the final state of each block they set to sink. A feature that fails is left out (logged once).
    */
   void run(List<LodTrees.Run> runs, int cx, int cz, Columns columns, Sink sink) {
      int x0 = cx << 4;
      int z0 = cz << 4;
      net.minecraft.world.level.levelgen.WorldgenRandom random = new net.minecraft.world.level.levelgen.WorldgenRandom(
         new net.minecraft.world.level.levelgen.XoroshiroRandomSource(net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed()));
      long decoration = random.setDecorationSeed(this.seed, x0, z0);
      Stage stage = new Stage(columns, random);
      stage.cx = cx;
      stage.cz = cz;
      WorldGenLevel fake = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, stage);
      for (int r = 0; r < runs.size(); r++) {
         LodTrees.Run run = runs.get(r);
         stage.current = r;
         PlacedFeature placed = run.placed();
         random.setFeatureSeed(decoration, run.index(), run.step());
         try {
            // As PlacedFeature.placeWithContext (private; placeWithBiomeCheck would count in the features report): its
            // placement from the chunk's corner, then the feature at each position (none for Arborea's and Lithos's).
            net.minecraft.world.level.levelgen.placement.PlacementContext context = new net.minecraft.world.level.levelgen.placement.PlacementContext(
               fake, this.generator, java.util.Optional.of(placed));
            java.util.stream.Stream<BlockPos> positions = java.util.stream.Stream.of(new BlockPos(x0, this.minY, z0));
            for (net.minecraft.world.level.levelgen.placement.PlacementModifier modifier : placed.placement()) {
               positions = positions.flatMap(pos -> modifier.getPositions(context, random, pos));
            }

            net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?> feature = placed.feature().value();
            positions.forEach(pos -> feature.place(fake, this.generator, random, pos));
         } catch (RuntimeException | LinkageError failure) {
            if (SAID.add("failed " + placed.feature().value().feature().getClass().getName())) {
               WorldgenAccelerator.LOG.warn("WGA Voxy LODs: {} failed outside a world; left out where it fails (said once)", name(placed), failure);
            }
         }
      }

      for (Map.Entry<Long, BlockState> entry : stage.set.entrySet()) {
         long pos = entry.getKey();
         sink.set(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), entry.getValue(), stage.by.get(pos));
      }
   }

   static String name(PlacedFeature placed) {
      return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FEATURE.getKey(placed.feature().value().feature()));
   }

   /** The stand-in level of one run. */
   private final class Stage implements InvocationHandler {
      final Columns columns;
      final RandomSource random;
      /** The blocks set in this run (packed position to state), read back. */
      final java.util.HashMap<Long, BlockState> set = new java.util.HashMap<>();
      /** Which run set each of them last ({@link #current} when it did). */
      final java.util.HashMap<Long, Integer> by = new java.util.HashMap<>();
      int current;
      /** The chunk being decorated. */
      int cx;
      int cz;
      /** The stand-in chunks handed out for their step ({@code getChunk}), by position. */
      final java.util.HashMap<Long, net.minecraft.world.level.chunk.ProtoChunk> chunks = new java.util.HashMap<>();

      Stage(Columns columns, RandomSource random) {
         this.columns = columns;
         this.random = random;
      }

      BlockState state(BlockPos pos) {
         BlockState own = this.set.get(pos.asLong());
         if (own != null) {
            return own;
         }

         int x = pos.getX();
         int y = pos.getY();
         int z = pos.getZ();
         int ground = this.columns.ground(x, z);
         if (y > ground) {
            if (y <= this.columns.surface(x, z)) {
               BlockState fluid = this.columns.fluid(x, z);
               return fluid == null ? Blocks.WATER.defaultBlockState() : fluid;
            }

            return Blocks.AIR.defaultBlockState();
         }

         BlockState layer = this.columns.layer(x, z, ground - y);
         return layer == null ? Blocks.STONE.defaultBlockState() : layer;
      }

      /** Sky light at pos: 15 over the column's surface (minus the water above, one a block), 0 in the ground. */
      int sky(BlockPos pos) {
         int ground = this.columns.ground(pos.getX(), pos.getZ());
         int surface = this.columns.surface(pos.getX(), pos.getZ());
         int y = pos.getY();
         return y > surface ? 15 : y > ground ? Math.max(0, 15 - (surface - y + 1)) : 0;
      }

      int height(Heightmap.Types type, int x, int z) {
         // One over the highest block the heightmap counts: the ground, or the water's top for those that count fluids.
         boolean fluids = type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG;
         int top = fluids ? Math.max(this.columns.ground(x, z), this.columns.surface(x, z)) : this.columns.ground(x, z);
         return top + 1;
      }

      void put(BlockPos pos, BlockState state) {
         this.set.put(pos.asLong(), state);
         this.by.put(pos.asLong(), this.current);
      }

      Holder<Biome> biome(int qx, int qy, int qz) {
         return FeatureSandbox.this.generator.getBiomeSource().getNoiseBiome(qx, qy, qz,
            FeatureSandbox.this.level.getChunkSource().randomState().sampler());
      }

      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
         String name = method.getName();
         int n = args == null ? 0 : args.length;
         switch (name) {
            case "getBlockState":
               if (n == 1 && args[0] instanceof BlockPos pos) {
                  return this.state(pos);
               }
               break;
            case "getFluidState":
               if (n == 1 && args[0] instanceof BlockPos pos) {
                  FluidState fluid = this.state(pos).getFluidState();
                  return fluid == null ? Fluids.EMPTY.defaultFluidState() : fluid;
               }
               break;
            case "isStateAtPosition":
               if (n == 2 && args[0] instanceof BlockPos pos && args[1] instanceof java.util.function.Predicate<?> test) {
                  @SuppressWarnings("unchecked")
                  java.util.function.Predicate<BlockState> states = (java.util.function.Predicate<BlockState>)test;
                  return states.test(this.state(pos));
               }
               break;
            case "isFluidAtPosition":
               if (n == 2 && args[0] instanceof BlockPos pos && args[1] instanceof java.util.function.Predicate<?> test) {
                  @SuppressWarnings("unchecked")
                  java.util.function.Predicate<FluidState> fluids = (java.util.function.Predicate<FluidState>)test;
                  return fluids.test(this.state(pos).getFluidState());
               }
               break;
            case "setBlock":
               if (n >= 2 && args[0] instanceof BlockPos pos && args[1] instanceof BlockState state) {
                  this.put(pos.immutable(), state);
                  return true;
               }
               break;
            case "removeBlock":
               if (n >= 1 && args[0] instanceof BlockPos pos) {
                  this.put(pos.immutable(), Blocks.AIR.defaultBlockState());
                  return true;
               }
               break;
            case "destroyBlock":
               if (n >= 1 && args[0] instanceof BlockPos pos) {
                  this.put(pos.immutable(), Blocks.AIR.defaultBlockState());
                  return true;
               }
               break;
            case "getHeight":
               if (n == 3 && args[0] instanceof Heightmap.Types type) {
                  return this.height(type, (Integer)args[1], (Integer)args[2]);
               }

               if (n == 0) {
                  return FeatureSandbox.this.height;
               }
               break;
            case "getHeightmapPos":
               if (n == 2 && args[0] instanceof Heightmap.Types type && args[1] instanceof BlockPos pos) {
                  return new BlockPos(pos.getX(), this.height(type, pos.getX(), pos.getZ()), pos.getZ());
               }
               break;
            case "getMinY":
            case "getMinBuildHeight":
               if (n == 0) {
                  return FeatureSandbox.this.minY;
               }
               break;
            case "getSeed":
               return FeatureSandbox.this.seed;
            case "getLevel":
               // The real level, for what is the world's rather than the chunks' (Conquest's landform API takes its
               // generator from it); the blocks are still asked of the stand-in.
               if (n == 0) {
                  return FeatureSandbox.this.level;
               }
               break;
            case "getRandom":
               return this.random;
            case "registryAccess":
               return FeatureSandbox.this.level.registryAccess();
            case "enabledFeatures":
               return FeatureSandbox.this.level.enabledFeatures();
            case "dimensionType":
               return FeatureSandbox.this.level.dimensionType();
            case "getSeaLevel":
               return FeatureSandbox.this.level.getSeaLevel();
            case "isClientSide":
               return false;
            case "ensureCanWrite":
               return true;
            case "hasChunk":
               return false;
            case "getChunk":
               // getChunk(x, z) and getChunk(x, z, status, load): an empty stand-in chunk, for its step. The chunk
               // being decorated is at the carvers step, the ones around already decorated (features): as for the
               // second of two neighbours in the game, so Lithos's ground cover does the border columns between them
               // (with "none decorated" no run did them: a line at every chunk edge).
               if ((n == 2 || n == 4) && args[0] instanceof Integer cx && args[1] instanceof Integer cz) {
                  return this.chunks.computeIfAbsent(com.wgaccel.platform.Mc.key(cx, cz), key -> {
                     net.minecraft.world.level.chunk.ProtoChunk chunk = com.wgaccel.platform.Mc.emptyChunk(
                        new net.minecraft.world.level.ChunkPos(cx, cz), FeatureSandbox.this.level, FeatureSandbox.this.level.registryAccess());
                     chunk.setPersistedStatus(cx == this.cx && cz == this.cz
                        ? net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS : net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
                     return chunk;
                  });
               }
               break;
            case "getBlockEntity":
               return null;
            // Light, without a light engine (plants' survival checks: mushrooms want the dark, flowers the sky): full sky
            // light over the column's surface, none at or under its ground; no block light.
            case "getRawBrightness":
               if (n == 2 && args[0] instanceof BlockPos pos && args[1] instanceof Integer darken) {
                  return Math.max(0, this.sky(pos) - darken);
               }
               break;
            case "getMaxLocalRawBrightness":
               if (n >= 1 && args[0] instanceof BlockPos pos) {
                  return this.sky(pos);
               }
               break;
            case "getBrightness":
               if (n == 2 && args[0] instanceof net.minecraft.world.level.LightLayer layer && args[1] instanceof BlockPos pos) {
                  return layer == net.minecraft.world.level.LightLayer.SKY ? this.sky(pos) : 0;
               }
               break;
            case "canSeeSky":
            case "canSeeSkyFromBelowWater":
               if (n == 1 && args[0] instanceof BlockPos pos) {
                  return pos.getY() > this.columns.surface(pos.getX(), pos.getZ());
               }
               break;
            case "getSkyDarken":
               return 0;
            case "getBiome":
               if (n == 1 && args[0] instanceof BlockPos pos) {
                  return this.biome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
               }
               break;
            case "getNoiseBiome":
            case "getUncachedNoiseBiome":
               if (n == 3) {
                  return this.biome((Integer)args[0], (Integer)args[1], (Integer)args[2]);
               }
               break;
            case "setCurrentlyGenerating":
            case "blockUpdated":
            case "updateNeighborsAt":
            case "scheduleTick":
            case "gameEvent":
            case "levelEvent":
            case "playSound":
            case "addParticle":
            case "neighborShapeChanged":
               return null;
            case "addFreshEntity":
               return false;
            case "hashCode":
               if (n == 0) {
                  return System.identityHashCode(proxy);
               }
               break;
            case "equals":
               if (n == 1) {
                  return proxy == args[0];
               }
               break;
            case "toString":
               if (n == 0) {
                  return "wgaccel Voxy LOD stand-in level";
               }
               break;
            default:
               break;
         }

         if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
         }

         if (SAID.add(name + Arrays.toString(method.getParameterTypes()))) {
            // Its answer: nothing (the feature may fail on it).
            WorldgenAccelerator.LOG.info("WGA Voxy LOD trees: a feature run outside the world asked for {}({}), which the stand-in level"
               + " leaves empty (said once)", name, Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList());
         }

         Class<?> type = method.getReturnType();
         if (type == boolean.class) {
            return false;
         } else if (type == int.class) {
            return 0;
         } else if (type == long.class) {
            return 0L;
         } else if (type == float.class) {
            return 0.0F;
         } else if (type == double.class) {
            return 0.0;
         } else if (type == byte.class || type == short.class || type == char.class) {
            return type == char.class ? (Object)(char)0 : type == byte.class ? (Object)(byte)0 : (Object)(short)0;
         }

         return null;
      }
   }
}
