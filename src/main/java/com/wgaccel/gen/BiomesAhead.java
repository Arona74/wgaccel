package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gpu.BiomeGpu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * GPU step G5.3 (config gpuBiomesAhead ON, with gpuBiomes ON): the biome step's climate and table search worked out
 * ahead. A new overworld chunk is noticed at its structure starts (well before its biome step); worker threads make
 * its inputs from a stand-in chunk (its own noise chunk with FreeTerraForged's tile stage open, as the noise
 * look-ahead's, and the climate policy of the biome step's sampler, taken from the first chunk that reaches it), run
 * the chunk on the GPU on their own lanes and keep the result until the chunk's biome step takes it
 * ({@link GpuBiomes#begin}). A chunk whose result is not ready by then is the CPU's (not a GPU run that would make it
 * wait). Structure starts also run in a ring of chunks that never get further: their results are dropped, oldest first,
 * past the budget. Every 64th chunk the CPU still runs too and the results are compared.
 */
public final class BiomesAhead {
   private static final Object QUEUED = new Object();
   private static final Object TAKEN = new Object();
   private static final Object DONE = new Object();
   private static final int DONE_KEEP = 65_536;
   /** How long a biome step waits for its chunk still in the look-ahead's queue before doing it on the CPU. */
   private static final long TAKE_WAIT_NANOS = 10_000_000L;
   // Three threads: four (run AF) made smaller launches and more GPU time per chunk (2.3 -> 3.3 ms) for few more ready.
   private static final int THREADS_WITH_TILES = 3;
   /**
    * A chunk's result: per quart column (qx * 4 + qz) its temperature, humidity, continentalness, erosion, weirdness and
    * projected continentalness (the same up the whole column: 2D CellSampler fields; a chunk where they are not is the
    * CPU's), per cell (as GpuBiomes numbers them) its depth and chosen biome's number (-1: a tie, the CPU's), and the
    * BiomeSurfaceEnvelope minimums it was made from (the chunk's biome step reads them again: {@link GpuBiomes#begin}).
    */
   record Result(int quartY, int quartHeight, int[] columns, int[] cells, int[] decorated, int[] extra, int[] extraDecorated, int[] provider, int[] minimumSurface) {
   }

   private static volatile BiomesAhead current;

   /** The 4 x 4 area step: whether this chunk's biomes are still queued or being made. */
   public static boolean pending(long key) {
      BiomesAhead look = current;
      return look != null && look.states.get(key) == QUEUED;
   }

   /** The 4 x 4 area step: the chunk queued (at the front) unless already queued or made. */
   public static void soonKey(long key) {
      BiomesAhead look = current;
      if (look != null && policySampler != null && GpuBiomes.ready() && look.states.putIfAbsent(key, QUEUED) == null) {
         look.noticed.increment();
         look.soon.add(key);
      }
   }
   /** The biome step's climate sampler (its FreeTerraForged policy: seed, preset, surface context), from the first chunk. */
   private static volatile Object policySampler;

   private final ServerLevel level;
   private final NoiseBasedChunkGenerator generator;
   private final java.util.function.Supplier<net.minecraft.world.level.levelgen.Aquifer.FluidPicker> fluidPicker;
   private final int budget;
   private final LinkedBlockingQueue<Long> wanted = new LinkedBlockingQueue<>();
   /** Chunks that reached their structure references (real chunks, their biome step next): made first. */
   private final LinkedBlockingQueue<Long> soon = new LinkedBlockingQueue<>();
   private final ConcurrentHashMap<Long, Object> states = new ConcurrentHashMap<>();
   private final ConcurrentLinkedQueue<Long> readyOrder = new ConcurrentLinkedQueue<>();
   private final AtomicInteger readyCount = new AtomicInteger();
   private final ConcurrentLinkedQueue<Long> doneOrder = new ConcurrentLinkedQueue<>();
   private final AtomicInteger doneCount = new AtomicInteger();
   private final List<Thread> threads = new ArrayList<>();
   private volatile boolean stopped;
   private final AtomicInteger failures = new AtomicInteger();
   private final AtomicInteger dumped = new AtomicInteger();
   final LongAdder noticed = new LongAdder();
   final LongAdder made = new LongAdder();
   final LongAdder used = new LongAdder();
   final LongAdder late = new LongAdder();
   final LongAdder waited = new LongAdder();
   final LongAdder waitedInTime = new LongAdder();
   final LongAdder mismatched = new LongAdder();
   /** Results not used: the surface envelope's minimums at the chunk's biome step were not the ones the result was made from. */
   final LongAdder inputsChanged = new LongAdder();
   final java.util.concurrent.atomic.AtomicInteger inputsLogged = new java.util.concurrent.atomic.AtomicInteger();
   final LongAdder dropped = new LongAdder();
   final LongAdder skipped = new LongAdder();
   final LongAdder inputNanos = new LongAdder();
   final LongAdder gpuNanos = new LongAdder();
   final LongAdder batches = new LongAdder();

   private BiomesAhead(ServerLevel level, NoiseBasedChunkGenerator generator, int budget) throws ReflectiveOperationException {
      this.level = level;
      this.generator = generator;
      this.fluidPicker = GpuNoiseFill.fluidPicker(generator);
      this.budget = budget;
      int threads = com.wgaccel.ftf.FtfSlowPaths.lookAheadThreads(THREADS_WITH_TILES);
      for (int i = 0; i < threads; i++) {
         Thread thread = new Thread(this::run, "WGA GPU biomes ahead " + i);
         thread.setDaemon(true);
         thread.setPriority(Thread.NORM_PRIORITY - 1);
         this.threads.add(thread);
         thread.start();
      }
   }

   /** At the overworld's load: the look-ahead when gpuBiomes and gpuBiomesAhead are ON (it waits for the GPU program). */
   public static void start(ServerLevel overworld) {
      BiomesAhead old = current;
      current = null;
      policySampler = null;
      if (old != null) {
         old.stop();
      }

      if (!Config.GPU_BIOMES_AHEAD.get() || Config.GPU_BIOMES.get() != Config.GpuFilterMode.ON || Config.GPU_MODE.get() == Config.GpuMode.OFF
         || !com.wgaccel.gpu.Lwjgl.PRESENT || !(overworld.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
         return;
      }

      try {
         // Results wait from structure starts to the biome step; each is 8 bytes per cell (tall presets: ~40 KB).
         current = new BiomesAhead(overworld, generator, Math.max(4096, Config.GPU_LOOK_AHEAD_CHUNKS.get()));
         WorldgenAccelerator.LOG.info("WGA GPU biomes ahead: on ({} threads; chunks noticed at their structure starts)", current.threads.size());
      } catch (Throwable failure) {
         com.wgaccel.WgaChat.warnOff("WGA GPU biomes ahead off: {}", failure.toString());
      }
   }

   private void noticeOne(long key) {
      if (this.states.putIfAbsent(key, QUEUED) == null) {
         this.noticed.increment();
         this.wanted.add(key);
      }
   }

   static BiomesAhead active() {
      return current;
   }

   /** From the biome step's first climate sample: the sampler whose policy stand-ins use. */
   static void sawSampler(Object sampler) {
      if (policySampler == null && current != null) {
         policySampler = sampler;
      }
   }

   /**
    * At a new chunk's structure starts: the chunk alone. Structure starts also run in a ring of chunks that never get
    * further; their whole 4 x 4 areas (run of 0.9.45) made the ring wider still: 22,840 made for 13,312 used.
    */
   public static void noticed(ServerLevel level, ChunkPos pos) {
      BiomesAhead look = current;
      if (com.wgaccel.c2me.AreaStep.on()) {
         // The 4 x 4 area step queues the chunks itself.
         return;
      }

      if (Config.GPU_BIOMES_AHEAD_AT_REFERENCES.get()) {
         // Noticed at structure references instead (soon): only chunks that are going to be generated get there.
         return;
      }

      if (look != null && level == look.level && policySampler != null && GpuBiomes.ready()) {
         look.noticeOne(pos.toLong());
      }
   }

   /**
    * At a chunk's structure references: it is a real chunk and its biome step is next, so it goes to the front; S5: its
    * aligned 4 x 4 area is noticed now (its mates are real chunks too, but for the edge of what is asked).
    */
   public static void soon(ServerLevel level, ChunkPos pos) {
      BiomesAhead look = current;
      if (look == null || level != look.level || com.wgaccel.c2me.AreaStep.on()) {
         return;
      }

      if (Config.GPU_BIOMES_AHEAD_AT_REFERENCES.get() && policySampler != null && GpuBiomes.ready()) {
         look.noticeOne(pos.toLong());
      }

      if (look.states.get(pos.toLong()) == QUEUED) {
         look.soon.add(pos.toLong());
      }

      if (Config.GPU_AREA_BATCHES.get() && policySampler != null && GpuBiomes.ready()) {
         int ax = pos.x & ~3;
         int az = pos.z & ~3;
         for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
               look.noticeOne(ChunkPos.asLong(ax + dx, az + dz));
            }
         }
      }
   }

   private void stop() {
      this.stopped = true;
      for (Thread thread : this.threads) {
         thread.interrupt();
      }
   }

   /** The chunk's ready result, or null (not noticed, not made yet, or failed): then the chunk is the CPU's. */
   Result take(ChunkPos pos) {
      long key = pos.toLong();
      if (this.states.get(key) == QUEUED) {
         // Not made yet: to the front, and a short wait for it (the CPU's biome step costs ~20 ms; 0.9.45's run sent
         // 3,239 chunks there). Past the wait it is the CPU's: the maker skips it, as it is DONE then.
         this.soon.add(key);
         this.waited.increment();
         long until = System.nanoTime() + TAKE_WAIT_NANOS;
         while (this.states.get(key) == QUEUED && System.nanoTime() < until) {
            java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
         }

         if (this.states.get(key) instanceof Result) {
            this.waitedInTime.increment();
         }
      }

      Object state = this.done(key);
      if (state instanceof Result result) {
         this.readyCount.decrementAndGet();
         this.used.increment();
         return result;
      }

      this.late.increment();
      return null;
   }

   /**
    * The chunk's step has run (or it was dropped): marked DONE, so an area mate's notice never queues it again (run
    * of 0.9.44: a third of the look-ahead's GPU work was such chunks, made again and never used). The newest 65,536
    * are remembered. Returns what the chunk had before.
    */
   private Object done(long key) {
      Object before = this.states.put(key, DONE);
      if (before != DONE) {
         this.doneOrder.add(key);
         if (this.doneCount.incrementAndGet() > DONE_KEEP) {
            Long old = this.doneOrder.poll();
            if (old != null) {
               this.doneCount.decrementAndGet();
               this.states.remove(old, DONE);
            }
         }
      }

      return before;
   }

   /** At most this many chunks in one launch; their inputs are made first, so a batch also stops after BATCH_NANOS. */
   private static final int BATCH = 32;
   private static final long BATCH_NANOS = 8_000_000L;

   private void run() {
      // The highest priority, as the waited-for work (0.9.47's middle priority: 548 biome steps on the CPU, 0.9.46 308).
      List<BiomeInputs> inputsPool = new ArrayList<>();
      List<BiomeGpu.Job> jobsPool = new ArrayList<>();
      List<long[]> climatePool = new ArrayList<>();
      List<long[]> projectedPool = new ArrayList<>();
      List<int[]> chosenPool = new ArrayList<>();
      List<int[]> tiedPool = new ArrayList<>();
      List<int[]> decoratedPool = new ArrayList<>();
      List<long[]> extraCCPool = new ArrayList<>();
      List<int[]> extraChosenPool = new ArrayList<>();
      List<int[]> extraDecoratedPool = new ArrayList<>();
      List<BiomeGpu.Job> jobs = new ArrayList<>();
      java.util.Map<BiomeGpu.Job, int[]> providersOf = new java.util.IdentityHashMap<>();
      List<Long> keys = new ArrayList<>();
      List<int[]> heights = new ArrayList<>();
      NoiseGeneratorSettings settings = this.generator.generatorSettings().value();
      while (!this.stopped) {
         Long next = this.soon.poll();
         try {
            if (next == null) {
               next = this.wanted.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
         } catch (InterruptedException interrupted) {
            break;
         }

         if (next == null) {
            continue;
         }

         GpuBiomes.Program p = GpuBiomes.program();
         Object sampler = policySampler;
         jobs.clear();
         keys.clear();
         heights.clear();
         boolean decorating = p != null && p.gpu().decorates();
         long start = System.nanoTime();
         while (next != null) {
            long key = next;
            Object seen = this.states.get(key);
            if (seen != QUEUED) {
               // Taken already, or made (it was in both queues).
               if (seen == DONE) {
                  this.skipped.increment();
               }
            } else if (p == null || sampler == null) {
               this.states.remove(key, QUEUED);
               this.skipped.increment();
            } else {
               int slot = jobs.size();
               if (inputsPool.size() <= slot) {
                  inputsPool.add(new BiomeInputs());
                  jobsPool.add(new BiomeGpu.Job());
               }

               ChunkPos pos = new ChunkPos(key);
               try {
                  ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.registryAccess().registryOrThrow(Registries.BIOME), null);
                  NoiseChunk noise = NoiseChunk.forChunk(proto, p.random(), new Beardifier(
                     new it.unimi.dsi.fastutil.objects.ObjectArrayList<Beardifier.Rigid>().iterator(),
                     new it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.world.level.levelgen.structure.pools.JigsawJunction>().iterator()), settings,
                     this.fluidPicker.get(), Blender.empty());
                  BiomeInputs inputs = inputsPool.get(slot);
                  BiomeGpu.Job job = jobsPool.get(slot);
                  boolean rivers;
                  GpuNoiseFill.tileStage(noise, true);
                  try {
                     inputs.read(pos.getMinBlockX(), pos.getMinBlockZ(), noise, sampler, p.cellSamplers());
                     // C1: each column's provider (the chunk is the CPU's without it).
                     int[] known = new int[16];
                     if (p.gpu().multi() && !GpuBiomes.regions(job, known, p.table(), pos.getMinBlockX() >> 2, pos.getMinBlockZ() >> 2)) {
                        throw new IllegalStateException("no FreeTerraForged context for the providers yet");
                     }

                     providersOf.put(job, known);
                     rivers = p.gpu().decorates() && GpuBiomes.riverFlags(job, pos.getMinBlockX() >> 2, pos.getMinBlockZ() >> 2);
                  } finally {
                     GpuNoiseFill.tileStage(noise, false);
                  }

                  if (!rivers) {
                     decorating = false;
                  }

                  int quartY = proto.getMinBuildHeight() >> 2;
                  int quartHeight = (proto.getMaxBuildHeight() >> 2) - quartY;
                  GpuBiomes.job(job, inputs, pos.getMinBlockX() >> 2, quartY, pos.getMinBlockZ() >> 2, quartHeight);
                  if (!jobs.isEmpty() && (job.count != jobs.get(0).count || job.cells.length != jobs.get(0).cells.length)) {
                     throw new IllegalStateException("a chunk of another height");
                  }

                  jobs.add(job);
                  keys.add(key);
                  heights.add(new int[] {quartY, quartHeight});
               } catch (Throwable failure) {
                  this.states.remove(key, QUEUED);
                  this.skipped.increment();
                  if (this.failures.incrementAndGet() <= 8) {
                     WorldgenAccelerator.LOG.warn("WGA GPU biomes ahead: chunk {} failed: {}", pos, failure.toString());
                  }
               }
            }

            if (jobs.size() >= BATCH || System.nanoTime() - start > BATCH_NANOS) {
               break;
            }

            next = this.soon.poll();
            if (next == null) {
               next = this.wanted.poll();
            }
         }

         if (jobs.isEmpty()) {
            continue;
         }

         int n = jobs.get(0).count;
         while (climatePool.size() < jobs.size()) {
            climatePool.add(new long[0]);
            projectedPool.add(new long[0]);
            chosenPool.add(new int[0]);
            tiedPool.add(new int[0]);
            decoratedPool.add(new int[0]);
            extraCCPool.add(new long[0]);
            extraChosenPool.add(new int[0]);
            extraDecoratedPool.add(new int[0]);
         }

         int extras = p.gpu().extras();

         for (int j = 0; j < jobs.size(); j++) {
            if (climatePool.get(j).length < 6 * n) {
               climatePool.set(j, new long[6 * n]);
               projectedPool.set(j, new long[6 * n]);
               chosenPool.set(j, new int[n]);
               tiedPool.set(j, new int[n]);
               decoratedPool.set(j, new int[n]);
            }

            if (extraCCPool.get(j).length < extras * n) {
               extraCCPool.set(j, new long[extras * n]);
               extraChosenPool.set(j, new int[extras * n]);
               extraDecoratedPool.set(j, new int[extras * n]);
            }
         }

         long gpuStart = System.nanoTime();
         this.inputNanos.add(gpuStart - start);
         try {
            p.gpu().runBatch(jobs, climatePool, projectedPool, chosenPool, tiedPool, decorating ? decoratedPool : null, extras > 0 ? extraCCPool : null,
               extras > 0 ? extraChosenPool : null, extras > 0 ? extraDecoratedPool : null);
         } catch (Throwable failure) {
            com.wgaccel.WgaChat.warnOff("WGA GPU biomes ahead off: {}", failure.toString());
            for (Long key : keys) {
               this.states.remove(key, QUEUED);
            }

            current = null;
            this.stop();
            return;
         }

         this.gpuNanos.add(System.nanoTime() - gpuStart);
         this.batches.increment();
         for (int j = 0; j < jobs.size(); j++) {
            long key = keys.get(j);
            if (this.dumped.get() < com.wgaccel.gpu.BiomeDump.JOBS && this.dumped.incrementAndGet() <= com.wgaccel.gpu.BiomeDump.JOBS) {
               try {
                  com.wgaccel.gpu.BiomeDump.appendJob(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs").resolve("wgaccel-biome-jobs.bin"), jobs.get(j),
                     chosenPool.get(j), tiedPool.get(j));
               } catch (Exception ignored) {
               }
            }

            Result result = pack(heights.get(j)[0], heights.get(j)[1], n, climatePool.get(j), projectedPool.get(j), chosenPool.get(j), tiedPool.get(j),
               decorating ? java.util.Arrays.copyOf(decoratedPool.get(j), n) : null, extras, extraCCPool.get(j), extraChosenPool.get(j),
               decorating && p.gpu().decoratesExtras() ? java.util.Arrays.copyOf(extraDecoratedPool.get(j), extras * n) : null,
               p.gpu().multi() ? providersOf.get(jobs.get(j)) : null, jobs.get(j).minimumSurface);
            if (result == null) {
               this.states.remove(key, QUEUED);
               this.skipped.increment();
            } else if (this.states.replace(key, QUEUED, result)) {
               this.made.increment();
               this.readyCount.incrementAndGet();
               this.readyOrder.add(key);
            } else {
               this.states.remove(key, TAKEN);
            }
         }

         // Past the budget: the oldest results go (mostly the ring of chunks that only get structure starts).
         while (this.readyCount.get() > this.budget) {
            Long old = this.readyOrder.poll();
            if (old == null) {
               break;
            }

            Object state = this.states.get(old);
            if (state instanceof Result && this.states.remove(old, state)) {
               this.readyCount.decrementAndGet();
               this.dropped.increment();
            }
         }
      }
   }

   /** Null when a value does not fit an int or a column's climate is not the same at every height (the chunk is then the CPU's). */
   private static Result pack(int quartY, int quartHeight, int n, long[] climate, long[] projected, int[] chosen, int[] tied, int[] decorated, int extras,
         long[] extraCC, int[] extraChosen, int[] extraDecorated, int[] provider, int[] minimumSurface) {
      int[] columns = new int[16 * 6];
      int[] cells = new int[2 * n];
      for (int i = 0; i < n; i++) {
         int column = i & 15;
         long depth = climate[i * 6 + 4];
         if (depth != (int)depth) {
            return null;
         }

         cells[i * 2] = (int)depth;
         cells[i * 2 + 1] = tied[i] != 0 ? -1 : chosen[i];
         for (int k = 0; k < 6; k++) {
            long value = k == 4 ? projected[i * 6 + 2] : climate[i * 6 + k];
            if (value != (int)value) {
               return null;
            }

            if (i < 16) {
               columns[column * 6 + k] = (int)value;
            } else if (columns[column * 6 + k] != (int)value) {
               return null;
            }
         }
      }

      // C1: per other provider k, its 16 columns' projected continentalness then each cell's chosen number (-1: a tie).
      int[] extra = extras == 0 ? null : new int[extras * (16 + n)];
      for (int k = 0; k < extras; k++) {
         int at = k * (16 + n);
         for (int i = 0; i < n; i++) {
            long cc = extraCC[k * n + i];
            if (cc != (int)cc || i >= 16 && extra[at + (i & 15)] != (int)cc) {
               return null;
            }

            extra[at + (i & 15)] = (int)cc;
            extra[at + 16 + i] = extraChosen[k * n + i];
         }
      }

      return new Result(quartY, quartHeight, columns, cells, decorated, extra, extraDecorated, provider == null ? null : provider.clone(), minimumSurface.clone());
   }

   String summary() {
      long m = Math.max(1, this.made.sum());
      return String.format(Locale.ROOT,
         "; ahead: %,d noticed, %,d made in %,d launches (inputs %.2f ms and GPU %.2f ms per chunk on its own threads; %s), %,d used, %,d not ready (the CPU's; %,d waited for, %,d of them in time), %,d other height, %,d not used: surface envelope changed since made, %,d dropped, %,d skipped, %,d waiting",
         this.noticed.sum(), this.made.sum(), this.batches.sum(), this.inputNanos.sum() / 1.0e6 / m, this.gpuNanos.sum() / 1.0e6 / m, GpuBiomes.program() == null ? "-" : GpuBiomes.program().gpu().batchTimings(), this.used.sum(), this.late.sum(),
         this.waited.sum(), this.waitedInTime.sum(), this.mismatched.sum(), this.inputsChanged.sum(), this.dropped.sum(), this.skipped.sum(), this.readyCount.get());
   }

   /** For the memory report: what the biome look-ahead holds now. */
   public static String memorySummary() {
      BiomesAhead look = current;
      return look == null ? "biome look-ahead off" : String.format(java.util.Locale.ROOT, "biome look-ahead states %,d (results %,d), queues %,d + %,d",
         look.states.size(), look.readyCount.get(), look.wanted.size(), look.soon.size());
   }
}
