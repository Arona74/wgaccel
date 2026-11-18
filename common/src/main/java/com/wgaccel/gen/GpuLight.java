package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WgaChat;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.GpuBackend;
import com.wgaccel.gpu.GpuPrepare;
import com.wgaccel.gpu.GpuService;
import com.wgaccel.gpu.LightGpu;
import com.wgaccel.gpu.Lwjgl;
import com.wgaccel.platform.Mc;
import com.wgaccel.platform.Platform;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Wave 5 GPU chunk lighting ({@code config gpuLight}): computes both BlockLight and SkyLight for a chunk across its
 * 3x3 neighbor halo on the GPU ({@link LightGpu}) and installs the resulting sections into {@link ThreadedLevelLightEngine}.
 */
public final class GpuLight {
   private static final int CHECK_EVERY = 64;
   private static final Direction[] DIRS = {
      Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
   };

   private static final class LightLut {
      final short[] rawToLight;
      final int[] stateMeta;
      final short[] stateShapes;
      final int[] shapeMatrix;
      final int shapeWords;
      final int airLightId;
      final int opaqueLightId;
      final int uniqueStates;
      final int uniqueShapes;

      LightLut() {
         int totalStates = Block.BLOCK_STATE_REGISTRY.size();
         this.rawToLight = new short[totalStates];

         List<VoxelShape> shapes = new ArrayList<>();
         shapes.add(Shapes.empty());
         Reference2IntOpenHashMap<VoxelShape> shapeRefCache = new Reference2IntOpenHashMap<>();
         shapeRefCache.defaultReturnValue(-1);
         shapeRefCache.put(Shapes.empty(), 0);

         List<Integer> metaList = new ArrayList<>();
         List<short[]> shapesPerState = new ArrayList<>();
         Map<LightSignature, Integer> sigToId = new HashMap<>();

         for (int rawId = 0; rawId < totalStates; rawId++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(rawId);
            if (state == null) {
               this.rawToLight[rawId] = 0;
               continue;
            }
            int emission = state.getLightEmission() & 15;
            int rawLightBlock = Mc.lightBlock(state);
            int opacity = Math.max(1, Math.min(15, rawLightBlock));
            boolean skyOpaque = rawLightBlock != 0;
            boolean hasShape = state.canOcclude() && state.useShapeForLightOcclusion();
            int meta = emission | (opacity << 4) | (skyOpaque ? 0x100 : 0) | (hasShape ? 0x200 : 0);

            int[] dirIds = new int[6];
            if (hasShape) {
               for (int d = 0; d < 6; d++) {
                  VoxelShape vs = Mc.occlusionShape(state, DIRS[d]);
                  if (vs.isEmpty()) {
                     dirIds[d] = 0;
                  } else {
                     int sid = shapeRefCache.getInt(vs);
                     if (sid < 0) {
                        sid = findOrAddShape(shapes, vs);
                        shapeRefCache.put(vs, sid);
                     }
                     dirIds[d] = sid;
                  }
               }
            }

            LightSignature sig = new LightSignature(meta, dirIds[0], dirIds[1], dirIds[2], dirIds[3], dirIds[4], dirIds[5]);
            Integer existing = sigToId.get(sig);
            int lightId;
            if (existing != null) {
               lightId = existing;
            } else {
               lightId = metaList.size();
               if (lightId >= 65535) {
                  throw new IllegalStateException("Too many unique light signatures: " + lightId);
               }
               sigToId.put(sig, lightId);
               metaList.add(meta);
               shapesPerState.add(new short[]{
                  (short)dirIds[0], (short)dirIds[1], (short)dirIds[2],
                  (short)dirIds[3], (short)dirIds[4], (short)dirIds[5]
               });
            }
            this.rawToLight[rawId] = (short)lightId;
         }

         this.uniqueStates = metaList.size();
         this.uniqueShapes = shapes.size();
         this.stateMeta = new int[this.uniqueStates];
         this.stateShapes = new short[this.uniqueStates * 6];
         for (int i = 0; i < this.uniqueStates; i++) {
            this.stateMeta[i] = metaList.get(i);
            System.arraycopy(shapesPerState.get(i), 0, this.stateShapes, i * 6, 6);
         }

         this.shapeWords = (this.uniqueShapes + 31) >>> 5;
         this.shapeMatrix = new int[this.uniqueShapes * this.shapeWords];
         for (int a = 0; a < this.uniqueShapes; a++) {
            VoxelShape sa = shapes.get(a);
            for (int b = 0; b < this.uniqueShapes; b++) {
               if (Shapes.faceShapeOccludes(sa, shapes.get(b))) {
                  this.shapeMatrix[a * this.shapeWords + (b >>> 5)] |= (1 << (b & 31));
               }
            }
         }

         this.airLightId = lightStateId(Blocks.AIR.defaultBlockState());
         this.opaqueLightId = lightStateId(Blocks.STONE.defaultBlockState());
      }

      private static int findOrAddShape(List<VoxelShape> shapes, VoxelShape vs) {
         for (int i = 1; i < shapes.size(); i++) {
            VoxelShape other = shapes.get(i);
            if (other == vs || (!Shapes.joinIsNotEmpty(other, vs, BooleanOp.NOT_SAME))) {
               return i;
            }
         }
         int id = shapes.size();
         shapes.add(vs);
         return id;
      }

      short lightStateId(BlockState state) {
         int id = Block.BLOCK_STATE_REGISTRY.getId(state);
         return id >= 0 && id < this.rawToLight.length ? this.rawToLight[id] : (short)this.airLightId;
      }

      private record LightSignature(int meta, int d0, int d1, int d2, int d3, int d4, int d5) {
      }
   }

   private record Program(LightLut lut, LightGpu gpu) {
   }

   private static final class LaneScratch {
      final LightGpu.Job job = new LightGpu.Job();
      final LightGpu.Result result = new LightGpu.Result();
      final ChunkAccess[] chunks = new ChunkAccess[9];
   }

   private static final boolean SCALABLE_LUX = safeIsModLoaded("scalablelux");
   private static volatile Program program;
   private static volatile boolean failed;
   private static final AtomicInteger CHUNK_SEQ = new AtomicInteger();
   private static final LongAdder GPU_CHUNKS = new LongAdder();
   private static final LongAdder CHECKED_CHUNKS = new LongAdder();
   private static final LongAdder CPU_FALLBACKS = new LongAdder();
   private static final ThreadLocal<LaneScratch> SCRATCH = ThreadLocal.withInitial(LaneScratch::new);

   private GpuLight() {
   }

   private static boolean safeIsModLoaded(String modId) {
      try {
         return Platform.get().isModLoaded(modId);
      } catch (Throwable ignored) {
         return false;
      }
   }

   public static boolean blockedByMod() {
      return SCALABLE_LUX;
   }

   public static boolean active() {
      if (SCALABLE_LUX || failed) {
         return false;
      }
      if (Config.GPU_MODE.get() == Config.GpuMode.OFF || !Lwjgl.PRESENT) {
         return false;
      }
      return Config.GPU_LIGHT.get() != Config.GpuFilterMode.OFF;
   }

   public static void start(ServerLevel level) {
      if (SCALABLE_LUX) {
         if (Config.GPU_LIGHT.get() != Config.GpuFilterMode.OFF) {
            WorldgenAccelerator.LOG.info("WGA GPU light: stayed off because ScalableLux is installed");
         }
         return;
      }
      if (Config.GPU_LIGHT.get() == Config.GpuFilterMode.OFF || Config.GPU_MODE.get() == Config.GpuMode.OFF || !Lwjgl.PRESENT) {
         return;
      }
      failed = false;
      CHUNK_SEQ.set(0);
      GPU_CHUNKS.reset();
      CHECKED_CHUNKS.reset();
      CPU_FALLBACKS.reset();
      GpuPrepare.Task prep = GpuPrepare.begin("light");
      long started = System.nanoTime();
      Thread t = new Thread(() -> {
         try {
            LightLut lut = new LightLut();
            LightGpu gpu = new LightGpu(GpuService.context(), lut.stateMeta, lut.stateShapes, lut.shapeMatrix, lut.shapeWords);
            program = new Program(lut, gpu);
            prep.done();
            WorldgenAccelerator.LOG.info("WGA GPU light: ready in {} ms ({} unique light states, {} occlusion shapes, {})",
               (System.nanoTime() - started) / 1_000_000L, lut.uniqueStates, lut.uniqueShapes, Config.GPU_LIGHT.get());
         } catch (Throwable failure) {
            failed = true;
            prep.failed(failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
            WorldgenAccelerator.LOG.warn("WGA GPU light failed to start ({}); falling back to CPU lighting",
               com.wgaccel.gpu.GpuSelfTest.describe(failure));
         }
      }, "WGA GPU light compile");
      t.setDaemon(true);
      t.start();
   }

   public static void stopWorld() {
      Program old = program;
      program = null;
      if (old != null) {
         if (GPU_CHUNKS.sum() > 0 || CHECKED_CHUNKS.sum() > 0) {
            WorldgenAccelerator.LOG.info("WGA GPU light: {} chunks on GPU, {} checked, {} CPU fallbacks; {}",
               GPU_CHUNKS.sum(), CHECKED_CHUNKS.sum(), CPU_FALLBACKS.sum(), old.gpu().timings());
         }
         old.gpu().close();
      }
   }

   /** Computes chunk lighting on GPU or returns null if unavailable; handles CHECK/ON verification against Vanilla. */
   public static CompletableFuture<ChunkAccess> light(ServerLevel level, ThreadedLevelLightEngine lightEngine,
         StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
         java.util.function.Supplier<CompletableFuture<ChunkAccess>> cpuFallback) {
      if (!active() || chunk.isLightCorrect()) {
         return cpuFallback.get();
      }
      Program prog = program;
      if (prog == null) {
         CPU_FALLBACKS.increment();
         return cpuFallback.get();
      }

      Config.GpuFilterMode mode = Config.GPU_LIGHT.get();
      boolean checkOnly = mode == Config.GpuFilterMode.CHECK;
      int seq = CHUNK_SEQ.getAndIncrement();
      boolean verify = checkOnly || (seq % CHECK_EVERY == 0);

      LaneScratch scratch = SCRATCH.get();
      int activeMinSec;
      try {
         activeMinSec = buildJob(level, prog.lut(), cache, chunk, scratch);
      } catch (Throwable failure) {
         CPU_FALLBACKS.increment();
         WorldgenAccelerator.LOG.warn("WGA GPU light: gather failed at {}: {}", chunk.getPos(), failure.toString());
         return cpuFallback.get();
      }
      if (activeMinSec == Integer.MIN_VALUE) {
         CPU_FALLBACKS.increment();
         return cpuFallback.get();
      }

      LightGpu.Result gpuResult = scratch.result;
      try {
         prog.gpu().run(scratch.job, gpuResult);
      } catch (Throwable failure) {
         failed = true;
         CPU_FALLBACKS.increment();
         WgaChat.warnOff("GPU light turned itself off after execution error at {}: {}", chunk.getPos(),
            com.wgaccel.gpu.GpuSelfTest.describe(failure));
         return cpuFallback.get();
      }

      if (verify) {
         CHECKED_CHUNKS.increment();
         if (!verifyIsolated(level, scratch.chunks, chunk.getPos(), activeMinSec, gpuResult)) {
            CPU_FALLBACKS.increment();
            return cpuFallback.get();
         }
         if (checkOnly) {
            return cpuFallback.get();
         }
      }

      if (lightEngine instanceof GpuLightTarget target) {
         GPU_CHUNKS.increment();
         return target.wgaccel$applyGpuLight(chunk, activeMinSec, gpuResult);
      }
      CPU_FALLBACKS.increment();
      return cpuFallback.get();
   }

   private static boolean verifyIsolated(ServerLevel level, ChunkAccess[] chunks, ChunkPos centerPos, int activeMinSec, LightGpu.Result gpuResult) {
      int cx0 = Mc.x(centerPos);
      int cz0 = Mc.z(centerPos);
      int minY = Mc.minY(level);
      int height = level.getHeight();
      boolean hasSky = level.dimensionType().hasSkyLight();

      BlockGetter localBlockGetter = Mc.blockGetter(minY, height, pos -> {
         int dx = SectionPos.blockToSectionCoord(pos.getX()) - cx0;
         int dz = SectionPos.blockToSectionCoord(pos.getZ()) - cz0;
         if (dx < -1 || dx > 1 || dz < -1 || dz > 1) {
            return Blocks.BEDROCK.defaultBlockState();
         }
         return chunks[(dz + 1) * 3 + (dx + 1)].getBlockState(pos);
      });

      LightChunkGetter localGetter = new LightChunkGetter() {
         @Override
         public LightChunk getChunkForLighting(int x, int z) {
            int dx = x - cx0;
            int dz = z - cz0;
            return dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1 ? chunks[(dz + 1) * 3 + (dx + 1)] : null;
         }

         @Override
         public BlockGetter getLevel() {
            return localBlockGetter;
         }
      };

      LevelLightEngine cpuEng = new LevelLightEngine(localGetter, true, hasSky);
      for (int i = 0; i < 9; i++) {
         ChunkAccess c = chunks[i];
         ChunkPos cp = c.getPos();
         LevelChunkSection[] cSecs = c.getSections();
         for (int s = 0; s < cSecs.length; s++) {
            if (!cSecs[s].hasOnlyAir()) {
               cpuEng.updateSectionStatus(SectionPos.of(cp, c.getSectionYFromSectionIndex(s)), false);
            }
         }
      }
      for (int i = 0; i < 9; i++) {
         ChunkPos cp = chunks[i].getPos();
         cpuEng.setLightEnabled(cp, true);
         cpuEng.propagateLightSources(cp);
      }
      while (cpuEng.hasLightWork()) {
         cpuEng.runLightUpdates();
      }

      LayerLightEventListener blkListener = cpuEng.getLayerListener(LightLayer.BLOCK);
      LayerLightEventListener skyListener = hasSky ? cpuEng.getLayerListener(LightLayer.SKY) : null;
      BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
      int baseX = centerPos.getMinBlockX();
      int baseZ = centerPos.getMinBlockZ();
      int secs = gpuResult.secCount;
      for (int s = 0; s < secs; s++) {
         int sy = activeMinSec + s;
         int baseY = SectionPos.sectionToBlockCoord(sy);
         int sOff = s * 2048;
         for (int ly = 0; ly < 16; ly++) {
            int wy = baseY + ly;
            for (int lz = 0; lz < 16; lz++) {
               int wz = baseZ + lz;
               for (int xp = 0; xp < 8; xp++) {
                  int bByteIdx = sOff + (ly << 7) + (lz << 3) + xp;
                  int wx0 = baseX + (xp << 1);
                  int wx1 = wx0 + 1;

                  int gb = gpuResult.blockData[bByteIdx] & 0xFF;
                  int cb0 = blkListener.getLightValue(mpos.set(wx0, wy, wz));
                  int cb1 = blkListener.getLightValue(mpos.set(wx1, wy, wz));
                  if ((gb & 15) != cb0 || ((gb >> 4) & 15) != cb1) {
                     boolean firstBad = (gb & 15) != cb0;
                     failed = true;
                     WgaChat.warnOff("GPU light turned itself off: BlockLight mismatch at chunk ({}, {}) block ({}, {}, {}): GPU={}, CPU={}",
                        cx0, cz0, firstBad ? wx0 : wx1, wy, wz, firstBad ? (gb & 15) : ((gb >> 4) & 15), firstBad ? cb0 : cb1);
                     return false;
                  }

                  if (skyListener != null) {
                     int gs = gpuResult.skyData[bByteIdx] & 0xFF;
                     int cs0 = skyListener.getLightValue(mpos.set(wx0, wy, wz));
                     int cs1 = skyListener.getLightValue(mpos.set(wx1, wy, wz));
                     if ((gs & 15) != cs0 || ((gs >> 4) & 15) != cs1) {
                        boolean firstBad = (gs & 15) != cs0;
                        failed = true;
                        WgaChat.warnOff("GPU light turned itself off: SkyLight mismatch at chunk ({}, {}) block ({}, {}, {}): GPU={}, CPU={}",
                           cx0, cz0, firstBad ? wx0 : wx1, wy, wz, firstBad ? (gs & 15) : ((gs >> 4) & 15), firstBad ? cs0 : cs1);
                        return false;
                     }
                  }
               }
            }
         }
      }
      return true;
   }

   private static int buildJob(ServerLevel level, LightLut lut, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess center, LaneScratch scratch) {
      ChunkPos pos = center.getPos();
      ChunkAccess[] chunks = scratch.chunks;
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            GenerationChunkHolder holder = cache.get(Mc.x(pos) + dx, Mc.z(pos) + dz);
            ChunkAccess c = holder != null ? holder.getLatestChunk() : null;
            if (c == null) {
               return Integer.MIN_VALUE;
            }
            chunks[(dz + 1) * 3 + (dx + 1)] = c;
         }
      }

      int secCountWorld = center.getSectionsCount();
      int minSecWorld = Mc.minSection(center);
      int minNonAirIdx = secCountWorld;
      int maxNonAirIdx = -1;
      for (int i = 0; i < 9; i++) {
         LevelChunkSection[] secs = chunks[i].getSections();
         for (int s = 0; s < secCountWorld; s++) {
            if (!secs[s].hasOnlyAir()) {
               if (s < minNonAirIdx) minNonAirIdx = s;
               if (s > maxNonAirIdx) maxNonAirIdx = s;
            }
         }
      }

      if (maxNonAirIdx < 0) {
         // All 3x3 chunks empty air: fast handle without GPU (tiny trivial sky=15)
         return Integer.MIN_VALUE;
      }

      int activeMinIdx = Math.max(-1, minNonAirIdx - 1);
      int activeMaxIdx = Math.min(secCountWorld, maxNonAirIdx + 1);
      int activeSecs = activeMaxIdx - activeMinIdx + 1;
      int activeMinSec = minSecWorld + activeMinIdx;

      LightGpu.Job job = scratch.job;
      job.activeSecCount = activeSecs;
      job.hasSkyLight = level.dimensionType().hasSkyLight();
      job.airStateId = lut.airLightId;
      job.opaqueStateId = lut.opaqueLightId;
      if (job.sectionMode.length < 9 * activeSecs) {
         job.sectionMode = new int[9 * activeSecs];
      }

      int denseCount = 0;
      int uniformAir = -1 - lut.airLightId;
      for (int cIdx = 0; cIdx < 9; cIdx++) {
         LevelChunkSection[] secs = chunks[cIdx].getSections();
         int baseSlot = cIdx * activeSecs;
         for (int s = 0; s < activeSecs; s++) {
            int idx = activeMinIdx + s;
            if (idx < 0 || idx >= secCountWorld) {
               job.sectionMode[baseSlot + s] = uniformAir;
               continue;
            }
            LevelChunkSection sec = secs[idx];
            if (sec.hasOnlyAir()) {
               job.sectionMode[baseSlot + s] = uniformAir;
               continue;
            }
            BlockState first = sec.getBlockState(0, 0, 0);
            short firstLightId = lut.lightStateId(first);
            if (!sec.getStates().maybeHas(st -> lut.lightStateId(st) != firstLightId)) {
               job.sectionMode[baseSlot + s] = -1 - firstLightId;
               continue;
            }

            int slot = denseCount++;
            int neededShorts = denseCount * 4096;
            if (job.denseStates.length < neededShorts) {
               job.denseStates = Arrays.copyOf(job.denseStates, Math.max(neededShorts, denseCount * 4096 + 16 * 4096));
            }
            job.sectionMode[baseSlot + s] = slot;
            int outOff = slot * 4096;
            for (int ly = 0; ly < 16; ly++) {
               for (int lz = 0; lz < 16; lz++) {
                  for (int lx = 0; lx < 16; lx++) {
                     job.denseStates[outOff + ((ly << 8) | (lz << 4) | lx)] = lut.lightStateId(sec.getBlockState(lx, ly, lz));
                  }
               }
            }
         }
      }
      job.denseCount = denseCount;
      return activeMinSec;
   }
}
