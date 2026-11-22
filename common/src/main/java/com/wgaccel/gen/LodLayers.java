package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * AronaLayers' layers in the Voxy LODs (voxyAronaLayers): its own placement run as it is, through its public
 * {@code io.arona74.aronalayers.api.LayersApi.placeLayers(ServerLevel, ChunkAccess)} (API version 2, reached by
 * reflection), on a stand-in chunk built from the LOD columns (the ground's top blocks, water, the columns' biomes, its
 * worldgen heightmaps primed). The blocks it changed come back as edits on the LOD (Layer Supported LOD then draws
 * them at their real height).
 */
final class LodLayers {
   /** Blocks of ground under each column's top in the stand-in chunk. */
   private static final int DEPTH = 6;
   private static final int[] NONE = new int[0];
   private static volatile MethodHandle place;
   private static volatile boolean looked;
   private final ServerLevel level;
   private final ToIntFunction<BlockState> ids;
   private final int minY;
   private final int maxY;
   private volatile boolean failed;
   /** Runs, runs AronaLayers declined (false), runs with no block changed, blocks changed; empty runs described. */
   private static final java.util.concurrent.atomic.AtomicLong RUNS = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.LongAdder DECLINED = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.LongAdder EMPTY = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.LongAdder BLOCKS = new java.util.concurrent.atomic.LongAdder();
   private static final java.util.concurrent.atomic.AtomicInteger DESCRIBED = new java.util.concurrent.atomic.AtomicInteger();

   LodLayers(ServerLevel level, ToIntFunction<BlockState> ids) {
      this.level = level;
      this.ids = ids;
      this.minY = com.wgaccel.platform.Mc.minY(level);
      this.maxY = this.minY + level.getHeight() - 1;
   }

   /** AronaLayers with LayersApi.placeLayers installed (looked up once). */
   static boolean available() {
      if (!looked) {
         synchronized (LodLayers.class) {
            if (!looked) {
               place = find();
               looked = true;
            }
         }
      }

      return place != null;
   }

   private static MethodHandle find() {
      if (!com.wgaccel.platform.Platform.get().isModLoaded("aronalayers")) {
         return null;
      }

      try {
         Class<?> api = Class.forName("io.arona74.aronalayers.api.LayersApi");
         int version = api.getField("VERSION").getInt(null);
         MethodHandle handle = version < 2 ? null : MethodHandles.publicLookup().findStatic(api, "placeLayers",
            MethodType.methodType(boolean.class, ServerLevel.class, ChunkAccess.class));
         WorldgenAccelerator.LOG.info(handle != null
            ? "WGA Voxy LODs: AronaLayers' layers placed by AronaLayers itself (voxyAronaLayers)"
            : "WGA Voxy LODs: AronaLayers' LayersApi is version {}, layers in the LODs need version 2 (placeLayers)", version);
         return handle;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: AronaLayers has no LayersApi.placeLayers; no layers in the LODs ({})", missing.toString());
         return null;
      }
   }

   /**
    * The blocks AronaLayers changes in chunk (cx, cz), the columns read from columns: x and z in the chunk (0-15), y from
    * that column's ground (1 the block over it, 0 its top block...), Voxy id, solid (always 1).
    */
   int[] run(int cx, int cz, FeatureSandbox.Columns columns) {
      if (this.failed || !available()) {
         return NONE;
      }

      int x0 = cx << 4;
      int z0 = cz << 4;
      ChunkAccess chunk = com.wgaccel.platform.Mc.emptyChunk(new ChunkPos(cx, cz), this.level, this.level.registryAccess());
      int[] ground = new int[256];
      int[] top = new int[256];
      BlockState[] placed = new BlockState[256 * (DEPTH + 1)];
      for (int lz = 0; lz < 16; lz++) {
         for (int lx = 0; lx < 16; lx++) {
            int col = lz << 4 | lx;
            int g = columns.ground(x0 + lx, z0 + lz);
            int s = Math.max(g, columns.surface(x0 + lx, z0 + lz));
            ground[col] = g;
            top[col] = s;
            for (int k = DEPTH - 1; k >= 0; k--) {
               BlockState state = columns.layer(x0 + lx, z0 + lz, k);
               this.set(chunk, lx, g - k, lz, state == null ? Blocks.STONE.defaultBlockState() : state);
            }

            BlockState fluid = columns.fluid(x0 + lx, z0 + lz);
            for (int y = g + 1; y <= s; y++) {
               this.set(chunk, lx, y, lz, fluid == null ? Blocks.WATER.defaultBlockState() : fluid);
            }
         }
      }

      // The columns' biomes (its snowy-biome test reads them).
      net.minecraft.world.level.biome.Climate.Sampler sampler = this.level.getChunkSource().randomState().sampler();
      chunk.fillBiomesFromNoise((qx, qy, qz, s) -> columns.biome((qx << 2) + 2, (qz << 2) + 2), sampler);
      // A chunk being decorated is past its biomes (a ProtoChunk refuses getNoiseBiome before that: "Asking for biomes
      // before we have biomes", which failed every column of AronaLayers' snowy-biome test).
      if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto) {
         proto.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS);
      }
      Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
         Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
      long runs = RUNS.incrementAndGet();
      if (runs == 200 || runs % 5000 == 0) {
         WorldgenAccelerator.LOG.info("WGA Voxy LODs: AronaLayers on {} LOD chunks: {} declined (no layers for the level), {} with no block"
            + " changed, {} blocks changed a chunk", runs, DECLINED.sum(), EMPTY.sum(), String.format(java.util.Locale.ROOT, "%.1f",
            BLOCKS.sum() / (double)Math.max(1, runs - DECLINED.sum())));
      }

      try {
         if (!(boolean)place.invokeExact(this.level, chunk)) {
            DECLINED.increment();
            return NONE;
         }
      } catch (Throwable failure) {
         this.failed = true;
         WorldgenAccelerator.LOG.warn("WGA Voxy LODs: AronaLayers' placeLayers failed on a stand-in chunk; no layers in the LODs from now on", failure);
         return NONE;
      }

      // What changed: from the top's few blocks down to its ground, and up to two blocks over its surface.
      int[] out = new int[64 * 5];
      int n = 0;
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int col = 0; col < 256; col++) {
         int lx = col & 15;
         int lz = col >> 4;
         int g = ground[col];
         for (int y = Math.max(this.minY, g - 2); y <= Math.min(this.maxY, top[col] + 2); y++) {
            BlockState now = chunk.getBlockState(pos.set(x0 + lx, y, z0 + lz));
            BlockState before = y <= g ? columns.layer(x0 + lx, z0 + lz, g - y) : y <= top[col] ? columns.fluid(x0 + lx, z0 + lz) : null;
            if (before == null && y <= top[col]) {
               before = y <= g ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState();
            }

            if (now.isAir() || now == before) {
               continue;
            }

            if (n + 5 > out.length) {
               out = java.util.Arrays.copyOf(out, out.length * 2);
            }

            out[n++] = lx;
            out[n++] = y - g;
            out[n++] = lz;
            out[n++] = this.ids.applyAsInt(now);
            out[n++] = 1;
         }
      }

      BLOCKS.add(n / 5);
      if (n == 0) {
         EMPTY.increment();
         if (DESCRIBED.getAndIncrement() < 3) {
            this.describe(chunk, cx, cz, ground, top, columns);
         }
      }

      return n == 0 ? NONE : java.util.Arrays.copyOf(out, n);
   }

   /** Why a stand-in chunk got no layers: its middle column as AronaLayers sees it, and its skip counters (rough: shared). */
   private void describe(ChunkAccess chunk, int cx, int cz, int[] ground, int[] top, FeatureSandbox.Columns columns) {
      int col = 8 << 4 | 8;
      int x = (cx << 4) + 8;
      int z = (cz << 4) + 8;
      int g = ground[col];
      int floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).getFirstAvailable(8, 8);
      StringBuilder skips = new StringBuilder();
      try {
         Class<?> helper = Class.forName("io.arona74.aronalayers.gen.injection.LayerPlacementHelper");
         for (java.lang.reflect.Field field : helper.getFields()) {
            if (field.getName().startsWith("debugSkip") && field.get(null) instanceof java.util.concurrent.atomic.AtomicInteger count) {
               skips.append(' ').append(field.getName().substring(9)).append('=').append(count.get());
            }
         }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError none) {
         skips.append(" (not readable)");
      }

      WorldgenAccelerator.LOG.info("WGA Voxy LODs: AronaLayers changed nothing in LOD chunk {}, {}: its middle column ground {} (top {}), surface {},"
         + " OCEAN_FLOOR_WG first free {}, top block {}, over it {}; AronaLayers' skips (shared, rough):{}", cx, cz, g, top[col],
         columns.surface(x, z), floor, chunk.getBlockState(new BlockPos(x, g, z)), chunk.getBlockState(new BlockPos(x, g + 1, z)), skips);
   }

   private void set(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
      if (y < this.minY || y > this.maxY) {
         return;
      }

      LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
      section.setBlockState(lx, y & 15, lz, state, false);
   }
}
