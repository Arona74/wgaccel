package com.wgaccel.gen;

import com.wgaccel.platform.Mc;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Reflection bridge and column height evaluator for Nature's Spirit's {@code SurfaceBuilderMixin} pillars
 * ({@code sugi_forest}, {@code blooming_sugi_forest}, {@code stratified_desert}, {@code lively_dunes},
 * {@code blooming_dunes}). Mirrors {@code SurfaceBuilderMixin}'s noise math identically so {@link GpuSurface}
 * can accelerate pillar chunks on CUDA/OpenCL without falling the whole chunk back to the CPU.
 */
public final class NaturesSpiritPillars {
   private static final List<String> SUGI_NAMES = List.of("sugi_forest", "blooming_sugi_forest");
   private static final List<String> DESERT_NAMES = List.of("stratified_desert", "lively_dunes", "blooming_dunes");

   private final NormalNoise sugiPillar;
   private final NormalNoise sugiPillarRoof;
   private final NormalNoise sugiSurface;
   private final NormalNoise desertPillar;
   private final NormalNoise desertPillarRoof;
   private final NormalNoise desertSurface;
   private final Field configFlag;
   private final ReferenceOpenHashSet<Biome> sugiBiomes = new ReferenceOpenHashSet<>();
   private final ReferenceOpenHashSet<Biome> desertBiomes = new ReferenceOpenHashSet<>();
   private final ReferenceOpenHashSet<Biome> allBiomes = new ReferenceOpenHashSet<>();
   private final int[] biomeIds;

   private NaturesSpiritPillars(NormalNoise sugiPillar, NormalNoise sugiPillarRoof, NormalNoise sugiSurface,
         NormalNoise desertPillar, NormalNoise desertPillarRoof, NormalNoise desertSurface, Field configFlag,
         Registry<Biome> biomes) {
      this.sugiPillar = sugiPillar;
      this.sugiPillarRoof = sugiPillarRoof;
      this.sugiSurface = sugiSurface;
      this.desertPillar = desertPillar;
      this.desertPillarRoof = desertPillarRoof;
      this.desertSurface = desertSurface;
      this.configFlag = configFlag;
      it.unimi.dsi.fastutil.ints.IntArrayList ids = new it.unimi.dsi.fastutil.ints.IntArrayList(5);
      if (biomes != null) {
         for (String name : SUGI_NAMES) {
            Biome b = Mc.value(biomes, Mc.key(Registries.BIOME, "natures_spirit", name));
            if (b != null) {
               this.sugiBiomes.add(b);
               this.allBiomes.add(b);
               ids.add(biomes.getId(b));
            }
         }
         for (String name : DESERT_NAMES) {
            Biome b = Mc.value(biomes, Mc.key(Registries.BIOME, "natures_spirit", name));
            if (b != null) {
               this.desertBiomes.add(b);
               this.allBiomes.add(b);
               ids.add(biomes.getId(b));
            }
         }
      }
      this.biomeIds = ids.toIntArray();
   }

   /** Returns a handle for this {@link SurfaceSystem}, or {@code null} if Nature's Spirit pillar hooks are absent. */
   public static NaturesSpiritPillars of(SurfaceSystem system, Registry<Biome> biomes) {
      if (!Platform.get().isModLoaded("natures_spirit")) {
         return null;
      }

      try {
         NormalNoise sp = noise(system, "naturespirit$sugiPillarNoise");
         NormalNoise spr = noise(system, "naturespirit$sugiPillarRoofNoise");
         NormalNoise ss = noise(system, "naturespirit$sugiSurfaceNoise");
         NormalNoise dp = noise(system, "naturespirit$stratifiedDesertPillarNoise");
         NormalNoise dpr = noise(system, "naturespirit$stratifiedDesertPillarRoofNoise");
         NormalNoise ds = noise(system, "naturespirit$stratifiedDesertSurfaceNoise");
         if (sp == null || spr == null || ss == null || dp == null || dpr == null || ds == null) {
            return null;
         }

         Class<?> cfgClass = Names.forName("net.hibiscus.naturespirit.config.NSConfig");
         Field flag = Names.declaredField(cfgClass, "sugiAndStratifiedPillars");
         flag.setAccessible(true);
         NaturesSpiritPillars instance = new NaturesSpiritPillars(sp, spr, ss, dp, dpr, ds, flag, biomes);
         return instance.allBiomes.isEmpty() ? null : instance;
      } catch (ReflectiveOperationException | LinkageError ignored) {
         return null;
      }
   }

   private static NormalNoise noise(SurfaceSystem system, String name) throws ReflectiveOperationException {
      Field f = Names.declaredField(SurfaceSystem.class, name);
      f.setAccessible(true);
      return (NormalNoise)f.get(system);
   }

   /** Live Nature's Spirit {@code NSConfig.sugiAndStratifiedPillars} toggle. */
   public boolean enabledInNs() {
      try {
         return this.configFlag.getBoolean(null);
      } catch (IllegalAccessException ignored) {
         return true;
      }
   }

   /** Fast chunk-level check: true only if at least one section's biome palette holds a pillar biome. */
   public boolean chunkHasBiome(ChunkAccess chunk) {
      return !this.allBiomes.isEmpty() && this.enabledInNs() && hasBiomePalette(chunk);
   }

   /**
    * True when {@code chunk} or any of its 8 immediate neighbors holds a pillar biome (since {@link BiomeManager#getBiome}
    * biome zoom can sample up to 2 blocks across a chunk boundary), or conservatively if a neighbor cannot be inspected.
    */
   public boolean chunkOrBorderHasBiome(ChunkAccess chunk, BiomeManager.NoiseBiomeSource biomes) {
      if (this.allBiomes.isEmpty() || !this.enabledInNs()) {
         return false;
      }

      if (hasBiomePalette(chunk)) {
         return true;
      }

      if (!(biomes instanceof net.minecraft.server.level.WorldGenRegion region)) {
         return true;
      }

      int cx = Mc.x(chunk.getPos());
      int cz = Mc.z(chunk.getPos());
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
               continue;
            }

            ChunkAccess neighbor = region.getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES, false);
            if (neighbor == null || hasBiomePalette(neighbor)) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean hasBiomePalette(ChunkAccess chunk) {
      for (LevelChunkSection section : chunk.getSections()) {
         if (section != null && section.getBiomes().maybeHas(holder -> this.allBiomes.contains(holder.value()))) {
            return true;
         }
      }

      return false;
   }

   /** Fast quart-id check for {@link SurfaceAhead}: true if any biome ID matches one of the 5 NS pillar biomes. */
   public boolean containsAnyBiomeId(int[] ids) {
      if (this.biomeIds.length == 0 || ids == null) {
         return false;
      }

      for (int id : ids) {
         for (int target : this.biomeIds) {
            if (id == target) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Computes each column's exact Nature's Spirit pillar top block Y (matching {@code SurfaceBuilderMixin.injectPillars}),
    * storing {@link Integer#MIN_VALUE} where no pillar spawns. Returns the maximum pillar top across the chunk, or
    * {@link Integer#MIN_VALUE} when zero columns spawn a pillar.
    */
   public int computeChunkPillars(ChunkAccess chunk, BiomeManager biomeManager, BlockPos.MutableBlockPos pos, int[] outTops) {
      java.util.Arrays.fill(outTops, Integer.MIN_VALUE);
      int minX = chunk.getPos().getMinBlockX();
      int minZ = chunk.getPos().getMinBlockZ();
      int maxY = Mc.maxY(chunk) - 1;
      int maxTop = Integer.MIN_VALUE;

      for (int lx = 0; lx < 16; lx++) {
         int x = minX + lx;
         for (int lz = 0; lz < 16; lz++) {
            int z = minZ + lz;
            int worldSurfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) + 1;
            Biome biome = biomeManager.getBiome(pos.set(x, worldSurfaceY, z)).value();
            int surfaceY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, lz) + 1;
            int j = Integer.MIN_VALUE;
            if (this.sugiBiomes.contains(biome)) {
               j = sugiPillarTop(x, z, surfaceY);
            } else if (this.desertBiomes.contains(biome)) {
               j = stratifiedDesertPillarTop(x, z, surfaceY);
            }

            if (j != Integer.MIN_VALUE) {
               int clamped = Math.min(j, maxY);
               if (clamped >= Mc.minY(chunk)) {
                  outTops[(lx << 4) | lz] = clamped;
                  if (clamped > maxTop) {
                     maxTop = clamped;
                  }
               }
            }
         }
      }
      return maxTop;
   }

   private int sugiPillarTop(int x, int z, int surfaceY) {
      double e = Math.min(
         Math.abs(this.sugiSurface.getValue((double)x, 0.0, (double)z) * 8.5),
         this.sugiPillarRoof.getValue((double)x * 0.2, 0.0, (double)z * 0.2) * 12.0
      );
      if (e > -10.0) {
         double h = Math.abs(this.sugiPillar.getValue((double)x * 0.9, 0.0, (double)z * 0.8) * 2.05);
         double i = 32.0 + Math.min(e * e * 6.75, Math.ceil(h * 30.0) + 48.0);
         int j = Mth.floor(i);
         if (surfaceY <= j) {
            return j;
         }
      }
      return Integer.MIN_VALUE;
   }

   private int stratifiedDesertPillarTop(int x, int z, int surfaceY) {
      double e = Math.min(
         Math.abs(this.desertSurface.getValue((double)x, 0.0, (double)z) * 8.5),
         this.desertPillar.getValue((double)x * 0.2, 0.0, (double)z * 0.2) * 14.0
      );
      if (!(e <= 0.0)) {
         double h = Math.abs(this.desertPillarRoof.getValue((double)x * 0.75, 0.0, (double)z * 0.75) * 2.25);
         double i = 54.0 + Math.min(e * e * 3.5, Math.ceil(h * 30.0) + 38.0);
         int j = Mth.floor(i);
         if (surfaceY <= j) {
            return j;
         }
      }
      return Integer.MIN_VALUE;
   }

   private static NaturesSpiritPillars getOrReflectNoises(SurfaceSystem system) {
      NaturesSpiritPillars cached = INSTANCE_CACHE.get(system);
      if (cached != null) {
         return cached;
      }
      if (!Platform.get().isModLoaded("natures_spirit")) {
         return null;
      }
      try {
         NormalNoise sp = noise(system, "naturespirit$sugiPillarNoise");
         NormalNoise spr = noise(system, "naturespirit$sugiPillarRoofNoise");
         NormalNoise ss = noise(system, "naturespirit$sugiSurfaceNoise");
         NormalNoise dp = noise(system, "naturespirit$stratifiedDesertPillarNoise");
         NormalNoise dpr = noise(system, "naturespirit$stratifiedDesertPillarRoofNoise");
         NormalNoise ds = noise(system, "naturespirit$stratifiedDesertSurfaceNoise");
         if (sp == null || spr == null || ss == null || dp == null || dpr == null || ds == null) {
            return null;
         }
         Class<?> cfgClass = Names.forName("net.hibiscus.naturespirit.config.NSConfig");
         Field flag = Names.declaredField(cfgClass, "sugiAndStratifiedPillars");
         flag.setAccessible(true);
         NaturesSpiritPillars instance = new NaturesSpiritPillars(sp, spr, ss, dp, dpr, ds, flag, null);
         INSTANCE_CACHE.put(system, instance);
         return instance;
      } catch (ReflectiveOperationException | LinkageError ignored) {
         return null;
      }
   }

   /** Fast CPU fallback matching {@code SurfaceBuilderMixin.placeSugiPillar} without its dead first loop. */
   public static boolean fastCpuSugi(SurfaceSystem system, net.minecraft.world.level.chunk.BlockColumn column,
         net.minecraft.world.level.block.state.BlockState defaultBlock, int x, int z, int surfaceY,
         net.minecraft.world.level.LevelHeightAccessor level) {
      NaturesSpiritPillars handle = getOrReflectNoises(system);
      if (handle == null) {
         return false;
      }
      int j = handle.sugiPillarTop(x, z, surfaceY);
      if (j != Integer.MIN_VALUE) {
         fillPillarCpu(level, column, defaultBlock, j);
      }
      return true;
   }

   /** Fast CPU fallback matching {@code SurfaceBuilderMixin.placeStratifiedDesertPillar} without its dead first loop. */
   public static boolean fastCpuStratifiedDesert(SurfaceSystem system, net.minecraft.world.level.chunk.BlockColumn column,
         net.minecraft.world.level.block.state.BlockState defaultBlock, int x, int z, int surfaceY,
         net.minecraft.world.level.LevelHeightAccessor level) {
      NaturesSpiritPillars handle = getOrReflectNoises(system);
      if (handle == null) {
         return false;
      }
      int j = handle.stratifiedDesertPillarTop(x, z, surfaceY);
      if (j != Integer.MIN_VALUE) {
         fillPillarCpu(level, column, defaultBlock, j);
      }
      return true;
   }

   private static void fillPillarCpu(net.minecraft.world.level.LevelHeightAccessor level, net.minecraft.world.level.chunk.BlockColumn column,
         net.minecraft.world.level.block.state.BlockState defaultBlock, int j) {
      int minY = Mc.minY(level);
      net.minecraft.world.level.block.Block waterBlock = net.minecraft.world.level.block.Blocks.WATER;
      for (int k = j; k >= minY; --k) {
         net.minecraft.world.level.block.state.BlockState state = column.getBlock(k);
         if (!state.isAir() && !state.is(waterBlock)) {
            break;
         }
         column.setBlock(k, defaultBlock);
      }
   }

   private static final java.util.Map<SurfaceSystem, NaturesSpiritPillars> INSTANCE_CACHE =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

   public static void register(SurfaceSystem system, NaturesSpiritPillars instance) {
      if (instance != null) {
         INSTANCE_CACHE.put(system, instance);
      }
   }
}
