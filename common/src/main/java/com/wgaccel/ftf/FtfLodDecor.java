package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.feature.ColumnDecorator;
import etcodehome.freeterraforged.world.worldgen.feature.ScreeNoises;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * FreeTerraForged's feature-step ground for the LOD columns, which the stand-ins (noise and surface only) do not have:
 * its scree feature (RAW_GENERATION: rock on steep and high ground, tuff bands, coarse dirt, gravel; sandstone and
 * terracotta on desert slopes) and its snow decorator's erosion (no snow on steep cold slopes), with FreeTerraForged's
 * own private methods for the choices (getMaterial, sampleMaterial, snowErosion, by reflection) on the column's cell.
 *
 * <p>A column is its top blocks, from the ground down (blocks[0] at the ground), as the feature would leave them.
 */
public final class FtfLodDecor {
   private static final boolean FTF = com.wgaccel.platform.Platform.get().isModLoaded("freeterraforged");
   public static final LongAdder COLUMNS = new LongAdder();
   public static final LongAdder CHANGED = new LongAdder();
   /**
    * Snow in the LOD columns, for the log: dry columns the scree looked at; their tops that were snow (Still Life's caps)
    * and of those kept by the bridge; cold columns FTF's snow erosion looked at and of those eroded; columns with a snow
    * layer.
    */
   private static final java.util.concurrent.atomic.AtomicLong LOOKED = new java.util.concurrent.atomic.AtomicLong();
   private static final LongAdder SNOW_TOPS = new LongAdder();
   private static final LongAdder SNOW_KEPT = new LongAdder();
   private static final LongAdder COLD = new LongAdder();
   private static final LongAdder COLD_ERODED = new LongAdder();
   public static final LongAdder SNOW_LAYERS = new LongAdder();
   public static final LongAdder BARE = new LongAdder();
   private static volatile boolean broken;

   private final GeneratorContext context;
   private final Levels levels;
   private final ScreeNoises noises;
   private final ChunkGenerator generator;
   private final int seaLevel;
   /** ScreeFeature.Config, or null when the scree feature is off in the preset. */
   private final Object scree;
   /** DecorateSnowFeature.Config's erode_config when its erosion is on, else null. */
   private final Object snowErode;
   private final Method getMaterial;
   private final Method sampleMaterial;
   private final Object screeMaterials;
   private final Method snowErosion;
   private final Method screeSteepness;
   private final Method slopeModifier;
   private final float screeSteepnessValue;
   private final float slopeModifierValue;
   private final float heightModifierValue;

   private FtfLodDecor(GeneratorContext context, ScreeNoises noises, ChunkGenerator generator, Object scree, Object snowErode, Method getMaterial, Method sampleMaterial,
         Object screeMaterials, Method snowErosion) throws ReflectiveOperationException {
      this.context = context;
      this.levels = context.levels;
      this.noises = noises;
      this.generator = generator;
      this.seaLevel = generator.getSeaLevel();
      this.scree = scree;
      this.snowErode = snowErode;
      this.getMaterial = getMaterial;
      this.sampleMaterial = sampleMaterial;
      this.screeMaterials = screeMaterials;
      this.snowErosion = snowErosion;
      Object config = scree != null ? scree : snowErode;
      this.screeSteepness = config.getClass().getMethod("screeSteepness");
      this.slopeModifier = config.getClass().getMethod("slopeModifier");
      this.screeSteepnessValue = scree != null ? (Float)this.screeSteepness.invoke(scree) : 0.0F;
      this.slopeModifierValue = (Float)this.slopeModifier.invoke(config);
      this.heightModifierValue = (Float)config.getClass().getMethod("heightModifier").invoke(config);
   }

   /** The decorator for a FreeTerraForged world whose preset has scree or snow erosion, or null. */
   public static FtfLodDecor of(ServerLevel level, RandomState random) {
      if (!FTF || broken || !((Object)random instanceof FTFRandomState ftf) || !ftf.isTerraForged()) {
         return null;
      }

      try {
         GeneratorContext context = ftf.generatorContext();
         if (context == null || context.lookup == null) {
            return null;
         }

         // GeneratorContext.screeNoises: 1.0.0032 (the feature's own noises, made from the world's seed).
         Field noisesField = GeneratorContext.class.getField("screeNoises");
         ScreeNoises noises = (ScreeNoises)noisesField.get(context);
         if (noises == null) {
            return null;
         }

         Registry<PlacedFeature> placed = com.wgaccel.platform.Mc.registry(level.registryAccess(), Registries.PLACED_FEATURE);
         Object scree = config(placed, "scree");
         Object snow = config(placed, "decorate_snow");
         Object snowErode = null;
         if (snow != null && (Boolean)snow.getClass().getMethod("erode").invoke(snow)) {
            snowErode = snow.getClass().getMethod("erodeConfig").invoke(snow);
         }

         if (scree == null && snowErode == null) {
            return null;
         }

         Class<?> feature = Class.forName("etcodehome.freeterraforged.world.worldgen.feature.ScreeFeature", false, FTFRandomState.class.getClassLoader());
         Method getMaterial = null;
         Method sampleMaterial = null;
         for (Method method : feature.getDeclaredMethods()) {
            if (method.getName().equals("getMaterial") && method.getParameterCount() == 12) {
               getMaterial = method;
            } else if (method.getName().equals("sampleMaterial") && method.getParameterCount() == 9) {
               sampleMaterial = method;
            }
         }

         Field materials = feature.getDeclaredField("SCREE_MATERIALS");
         materials.setAccessible(true);
         Method snowErosion = null;
         Class<?> snowFeature = Class.forName("etcodehome.freeterraforged.world.worldgen.feature.DecorateSnowFeature", false, FTFRandomState.class.getClassLoader());
         for (Method method : snowFeature.getDeclaredMethods()) {
            if (method.getName().equals("snowErosion") && method.getParameterCount() == 5) {
               snowErosion = method;
            }
         }

         if (getMaterial == null || sampleMaterial == null || snowErosion == null) {
            throw new NoSuchMethodException("ScreeFeature.getMaterial / sampleMaterial or DecorateSnowFeature.snowErosion");
         }

         getMaterial.setAccessible(true);
         sampleMaterial.setAccessible(true);
         snowErosion.setAccessible(true);
         return new FtfLodDecor(context, noises, level.getChunkSource().getGenerator(), scree, snowErode, getMaterial, sampleMaterial, materials.get(null), snowErosion);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: FreeTerraForged's scree and snow erosion could not be set up (LODs without them): {}", failure.toString());
         return null;
      }
   }

   /** The configured feature's config of FreeTerraForged's placed feature, or null when the preset has none. */
   private static Object config(Registry<PlacedFeature> placed, String name) {
      PlacedFeature feature = com.wgaccel.platform.Mc.value(placed, com.wgaccel.platform.Mc.key(Registries.PLACED_FEATURE, "freeterraforged", name));
      if (feature == null) {
         return null;
      }

      ConfiguredFeature<?, ?> configured = feature.feature().value();
      FeatureConfiguration config = configured.config();
      return config;
   }

   /** The stand-in chunk's FreeTerraForged cells (z * 16 + x; Object so callers load no FreeTerraForged class), or null. */
   public Object[] cells(ChunkAccess chunk) {
      if (broken || !(chunk instanceof IChunkCellsHolder holder)) {
         return null;
      }

      try {
         return holder.freeterraforged$getOrCreateChunkCells(this.context);
      } catch (RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: no FreeTerraForged cells for the scree (LODs without it): {}", failure.toString());
         return null;
      }
   }

   /**
    * The scree feature on one dry column (ground the top solid block, no water above): blocks[k] is the block k below
    * the ground, changed in place. The cell's steepness is computed when the cell has none (the fast stand-ins' cells).
    */
   public void scree(Object column, int x, int z, int ground, Holder<Biome> biome, BlockState[] blocks, BlockState defaultBlock) {
      if (this.scree == null || broken) {
         return;
      }

      COLUMNS.increment();
      try {
         Cell cell = (Cell)column;
         int scaledY = this.levels.scale(cell.height);
         BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, ground, z);
         if (biome.is(Biomes.DESERT)) {
            this.desert(cell, x, z, blocks);
            return;
         }

         if (ground > scaledY || ground < this.seaLevel - 1 || biome.is(Biomes.WOODED_BADLANDS) || biome.is(Biomes.BADLANDS)) {
            return;
         }

         if (cell.gradient == 0.0F) {
            this.steepness(cell, x, z);
         }

         long looked = LOOKED.incrementAndGet();
         if (looked == 200_000L || looked % 1_000_000L == 0L) {
            WorldgenAccelerator.LOG.info("WGA LOD columns: snow on {} dry columns the FTF scree looked at: {} snow tops (Still Life's caps), {} of them"
               + " kept by the Still Life FTF bridge; {} cold columns FTF's snow erosion looked at, {} eroded; {} columns with a snow layer", looked,
               SNOW_TOPS.sum(), SNOW_KEPT.sum(), COLD.sum(), COLD_ERODED.sum(), SNOW_LAYERS.sum());
         }

         boolean snowTop = blocks[0].is(Blocks.SNOW_BLOCK) || blocks[0].is(Blocks.POWDER_SNOW);
         float steepness = cell.gradient + this.noises.rand.compute(x, z, 1) * this.slopeModifierValue;
         LifeforgedLod.Chunk lifeforged = LifeforgedLod.current();
         if (lifeforged != null) {
            // Lifeforged 6 (its hook at erodeColumn's head): its snow caps, then its ground cover in place of FTF's erosion.
            int took = LifeforgedLod.erode(lifeforged, this.scree, this.levels, this.noises.rand, cell, x, z, ground, blocks, defaultBlock, steepness);
            if (snowTop) {
               SNOW_TOPS.increment();
               if (took == LifeforgedLod.KEPT) {
                  SNOW_KEPT.increment();
               }
            }

            if (took != LifeforgedLod.NONE) {
               return;
            }

            snowTop = false;
         }

         boolean kept = lifeforged == null && StillLifeSnow.keep(this.scree, blocks[0], steepness);
         if (snowTop) {
            SNOW_TOPS.increment();
            if (kept) {
               SNOW_KEPT.increment();
            }
         }

         if (kept) {
            // The Still Life FTF bridge's snow caps: its hook at erodeColumn's head leaves a snow top off cliffs alone.
            return;
         }

         if (cell.terrain.isRiver() || cell.terrain.isWetland() || cell.terrain == TerrainType.VOLCANO_PIPE) {
            return;
         }

         BlockState top = blocks[0];
         if (!top.is(FTFBlockTags.ERODIBLE)) {
            return;
         }

         BlockState material = (BlockState)this.getMaterial.invoke(null, this.scree, this.levels, this.noises.rand, this.noises.clusterNoise, this.noises.warpX,
            this.noises.warpZ, this.generator, cell, pos, ground, top, defaultBlock);
         if (material != top) {
            CHANGED.increment();
            if (material.is(FTFBlockTags.ROCK) && !material.is(Blocks.TUFF)) {
               // erodeRock: the column down to the first rock layer within 32 blocks, as that rock (gravel without one).
               int depth = Math.min(32, blocks.length);
               BlockState rock = Blocks.GRAVEL.defaultBlockState();
               for (int dy = 3; dy < Math.min(32, blocks.length); dy++) {
                  if (blocks[dy].is(FTFBlockTags.ROCK)) {
                     rock = blocks[dy];
                     depth = dy + 1;
                     break;
                  }
               }

               fill(blocks, 0, depth, rock);
               return;
            }

            fill(blocks, 0, 4, material);
         }

         // placeScree (the steepness worked out above, the same)
         if (steepness < this.screeSteepnessValue) {
            return;
         }

         float sediment = cell.sediment * (3.0F / 255.0F);
         float noise = this.noises.rand.compute(x, z, 2) * (3.0F / 255.0F);
         if (sediment + noise > 0.55F) {
            BlockState chosen = (BlockState)this.sampleMaterial.invoke(null, this.screeMaterials, x, z, this.noises.clusterNoise, this.noises.warpX, this.noises.warpZ,
               this.noises.rand, 3, Blocks.GRAVEL.defaultBlockState());
            fill(blocks, 0, 2, chosen);
            CHANGED.increment();
         }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: FreeTerraForged's scree failed (LODs without it from now): {}", failure.toString());
      }
   }

   /** ScreeFeature.erodeDesert: smooth sandstone on desert slopes, terracotta on the high steep ones (4 blocks). */
   private void desert(Cell cell, int x, int z, BlockState[] blocks) throws ReflectiveOperationException {
      float min = this.levels.ground(10);
      float threshold = this.levels.ground(40);
      if (cell.gradient == 0.0F) {
         this.steepness(cell, x, z);
      }

      if (cell.gradient < 0.15F || cell.height < min) {
         return;
      }

      float value = cell.height + this.noises.desertErosionVariance.compute(x, z, 0);
      if (cell.gradient > 0.3F || value > threshold) {
         BlockState state = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
         if (value > threshold) {
            if (cell.gradient > 0.975F) {
               state = Blocks.TERRACOTTA.defaultBlockState();
            } else if (cell.gradient > 0.85F) {
               state = Blocks.BROWN_TERRACOTTA.defaultBlockState();
            } else if (cell.gradient > 0.75F) {
               state = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            } else if (cell.gradient > 0.65F) {
               state = Blocks.TERRACOTTA.defaultBlockState();
            }
         }

         for (int dy = 0; dy < 4 && dy < blocks.length; dy++) {
            blocks[dy] = state;
         }

         CHANGED.increment();
      }
   }

   /**
    * Whether FreeTerraForged's snow decorator takes the snow off this column (its erosion: steep cold ground, high
    * steep ground); ground is the top solid block.
    */
   public boolean snowEroded(Object column, int x, int z, int ground, Holder<Biome> biome) {
      if (this.snowErode == null || broken) {
         return false;
      }

      try {
         Cell cell = (Cell)column;
         int scaledY = this.levels.scale(cell.height);
         if (Math.abs(scaledY - ground) > 2 || ground < this.seaLevel) {
            return false;
         }

         BlockPos pos = new BlockPos(x, ground, z);
         if (com.wgaccel.platform.Mc.temperature(biome.value(), pos, this.seaLevel) > 0.25F) {
            return false;
         }

         if (cell.gradient == 0.0F) {
            this.steepness(cell, x, z);
         }

         float var = -ColumnDecorator.sampleNoise((float)x, (float)z, 16, 0);
         float hNoise = this.noises.rand.compute(x, z, 4) * this.heightModifierValue;
         float sNoise = this.noises.rand.compute(x, z, 5) * this.slopeModifierValue;
         float vModifier = cell.terrain == TerrainType.VOLCANO ? 0.15F : 0.0F;
         float height = cell.height + var + hNoise + vModifier;
         float steepness = cell.gradient + var + sNoise + vModifier;
         boolean eroded = (Boolean)this.snowErosion.invoke(null, this.snowErode, (float)x, (float)z, steepness, height);
         COLD.increment();
         if (eroded) {
            COLD_ERODED.increment();
         }

         return eroded;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: FreeTerraForged's snow erosion failed (LODs without it from now): {}", failure.toString());
         return false;
      }
   }

   /** WorldLookup.computeSteepness (1.0.0013 and later; the build compiles against 1.0.0010's, without it). */
   private void steepness(Cell cell, int x, int z) throws ReflectiveOperationException {
      Method method = steepness;
      if (method == null) {
         method = this.context.lookup.getClass().getMethod("computeSteepness", Cell.class, int.class, int.class);
         steepness = method;
      }

      method.invoke(this.context.lookup, cell, x, z);
   }

   private static volatile Method steepness;

   /** ColumnDecorator.replaceSolid down a column: the blocks from..to (not air) as the state. */
   private static void fill(BlockState[] blocks, int from, int to, BlockState state) {
      for (int dy = from; dy < to && dy < blocks.length; dy++) {
         if (!blocks[dy].isAir()) {
            blocks[dy] = state;
         }
      }
   }
}
