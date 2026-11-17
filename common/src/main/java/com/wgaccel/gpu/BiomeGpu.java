package com.wgaccel.gpu;

import static com.wgaccel.gpu.GpuApi.*;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G5.2b: a chunk's biome climate and climate-table search for production ({@link BiomeKernels}: biomeClimate,
 * then biomeSearch on its results without a copy in between), in the shared context, run by the generation threads on
 * their own lanes (a non-blocking stream, grow-only device and page-locked host buffers), like {@link FillGpu}. Uses no
 * Minecraft classes.
 */
public final class BiomeGpu {
   /** One chunk: its cells' quart positions (x, y, z each) and the inputs as {@code BiomeInputs} reads them. */
   public static final class Job {
      public int[] at = new int[3];
      public int count;
      public double[] cells = new double[1];
      public int chunkX;
      public int chunkZ;
      public boolean banding;
      public long seed;
      public float coverage;
      public int size;
      public int verticalSize;
      public int surfaceDepth;
      public int endDepth;
      public int add21875;
      public int add03125;
      public final int[] minimumSurface = new int[16];
      /** G5.4: per quart column (qx * 4 + qz) whether FreeTerraForged's tile has a river there (1) or not (0). */
      public final int[] rivers = new int[16];
      /** C1: per quart column its provider's number (0 the first; with setProviders). */
      public final int[] provider = new int[16];
   }

   private final class Lane {
      private final long stream;
      private int capacity;
      private int cellsCapacity;
      private long atDevice;
      private long atHost;
      private long cellsDevice;
      private long cellsHost;
      private long minimumDevice;
      private long minimumHost;
      private long climateDevice;
      private long projectedDevice;
      private long chosenDevice;
      private long tiedDevice;
      private long outHost;
      private final long[] events = new long[4];
      private int runs;

      Lane() {
         GpuSelfTest.check(cuCtxSetCurrent(BiomeGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }

         this.minimumDevice = device(4L * 16);
         this.minimumHost = host(4L * 16);
      }

      void ensure(int count, int cells) {
         if (count > this.capacity) {
            if (this.atDevice != 0L) {
               cuMemFree(this.atDevice);
               ncuMemFreeHost(this.atHost);
               cuMemFree(this.climateDevice);
               cuMemFree(this.projectedDevice);
               cuMemFree(this.chosenDevice);
               cuMemFree(this.tiedDevice);
               ncuMemFreeHost(this.outHost);
            }

            this.capacity = Math.max(1024, count + count / 4);
            long n = this.capacity;
            this.atDevice = device(12L * n);
            this.atHost = host(12L * n);
            this.climateDevice = device(48L * n);
            this.projectedDevice = device(48L * n);
            this.chosenDevice = device(4L * n);
            this.tiedDevice = device(4L * n);
            // Out on the host: climate, projected, chosen, tied one after another.
            this.outHost = host(104L * n);
         }

         if (cells > this.cellsCapacity) {
            if (this.cellsDevice != 0L) {
               cuMemFree(this.cellsDevice);
               ncuMemFreeHost(this.cellsHost);
            }

            this.cellsCapacity = cells;
            this.cellsDevice = device(8L * cells);
            this.cellsHost = host(8L * cells);
         }
      }
   }

   private final long context;
   private final long module;
   private final long climate;
   private final long search;
   private final long noiseData;
   private final long permutations;
   private final long bounds;
   private final long first;
   private final long count;
   private final long nodeBiome;
   private final int sourceMin;
   private final int sourceMax;
   private final int targetMax;
   /** Sampled runs (every 16th per lane): count, then microseconds of climate, search and copy out. */
   private final java.util.concurrent.atomic.LongAdder[] timings = {new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(),
      new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()};
   private final ThreadLocal<Lane> lanes = ThreadLocal.withInitial(Lane::new);
   private final long climateBatch;
   private final long decorate;
   private final long decorLongs;
   private final long decorInts;
   private final long decorFlags;
   private final float decorDensity;
   private final float decorCoverage;
   private final ThreadLocal<BatchLane> batchLanes = ThreadLocal.withInitial(BatchLane::new);
   /** C1: the other providers' searches (biomeTreeSearchRoot), their roots and ocean projection maxima; none by default. */
   private long searchRoot;
   private int[] extraRoots = new int[0];
   private int[] extraTargetMax = new int[0];

   /** C1: search the other providers' trees too (their roots in the node arrays, after the first provider's). */
   public void setExtras(int[] roots, long[] targetMax) {
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      this.searchRoot = roots.length == 0 ? 0L : function(this.module, "biomeTreeSearchRoot");
      this.extraRoots = roots.clone();
      this.extraTargetMax = new int[targetMax.length];
      for (int i = 0; i < targetMax.length; i++) {
         this.extraTargetMax[i] = Math.toIntExact(targetMax[i]);
      }
   }

   public int extras() {
      return this.extraRoots.length;
   }

   /** C1: the other providers' decoration settings (their Layouts and flags, as {@link BiomeDecoration} has them). */
   private long[] extraDecorLongs = new long[0];
   private long[] extraDecorInts = new long[0];
   private long[] extraDecorFlags = new long[0];
   private float[] extraDecorDensity = new float[0];

   public void setExtraDecoration(long[][] longs, int[][] ints, float[] density, int[][] flags) {
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      int count = longs.length;
      this.extraDecorLongs = new long[count];
      this.extraDecorInts = new long[count];
      this.extraDecorFlags = new long[count];
      for (int k = 0; k < count; k++) {
         ByteBuffer buffer = MemoryUtil.memAlloc(8 * longs[k].length);
         try {
            buffer.asLongBuffer().put(0, longs[k]);
            this.extraDecorLongs[k] = device(buffer.remaining());
            GpuSelfTest.check(cuMemcpyHtoD(this.extraDecorLongs[k], buffer), "copy in");
         } finally {
            MemoryUtil.memFree(buffer);
         }

         this.extraDecorInts[k] = upload(ints[k]);
         this.extraDecorFlags[k] = upload(flags[k]);
      }

      this.extraDecorDensity = density.clone();
   }

   // C1: one provider per cell (setProviders): biomeTreeSearchMulti / biomeDecorateMulti instead of the single ones.
   private long searchMulti;
   private long decorateMulti;
   private long rootsDevice;
   private long targetMaxesDevice;
   private long pAllDevice;
   private long qAllDevice;
   private long flagsAllDevice;
   private long densitiesDevice;
   private int flagStride;
   private boolean multiDecorates;

   /**
    * C1: every provider's tree root and ocean projection maximum (the first's at 0), and with decoration (non-null) each
    * one's Layout settings, flags (one length) and density; each cell then searches and decorates only its column's
    * provider (Job.provider).
    */
   public void setProviders(int[] roots, long[] targetMax, long[][] longs, int[][] ints, float[] density, int[][] flags) {
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      this.searchMulti = function(this.module, "biomeTreeSearchMulti");
      this.rootsDevice = upload(roots);
      int[] maxima = new int[targetMax.length];
      for (int i = 0; i < maxima.length; i++) {
         maxima[i] = Math.toIntExact(targetMax[i]);
      }

      this.targetMaxesDevice = upload(maxima);
      this.multiDecorates = longs != null && this.decorate != 0L;
      if (this.multiDecorates) {
         this.decorateMulti = function(this.module, "biomeDecorateMulti");
         long[] pAll = new long[6 * longs.length];
         int[] qAll = new int[7 * ints.length];
         this.flagStride = flags[0].length;
         int[] flagsAll = new int[this.flagStride * flags.length];
         for (int k = 0; k < longs.length; k++) {
            System.arraycopy(longs[k], 0, pAll, 6 * k, 6);
            System.arraycopy(ints[k], 0, qAll, 7 * k, 7);
            System.arraycopy(flags[k], 0, flagsAll, this.flagStride * k, this.flagStride);
         }

         ByteBuffer buffer = MemoryUtil.memAlloc(8 * pAll.length);
         try {
            buffer.asLongBuffer().put(0, pAll);
            this.pAllDevice = device(buffer.remaining());
            GpuSelfTest.check(cuMemcpyHtoD(this.pAllDevice, buffer), "copy in");
         } finally {
            MemoryUtil.memFree(buffer);
         }

         this.qAllDevice = upload(qAll);
         this.flagsAllDevice = upload(flagsAll);
         ByteBuffer floats = MemoryUtil.memAlloc(4 * density.length);
         try {
            floats.asFloatBuffer().put(0, density);
            this.densitiesDevice = device(floats.remaining());
            GpuSelfTest.check(cuMemcpyHtoD(this.densitiesDevice, floats), "copy in");
         } finally {
            MemoryUtil.memFree(floats);
         }
      }
   }

   /** C1: whether cells search (and decorate) their own column's provider. */
   public boolean multi() {
      return this.searchMulti != 0L;
   }

   /** Whether the other providers' cells are decorated on the GPU too. */
   public boolean decoratesExtras() {
      return this.decorate != 0L && this.extraDecorLongs.length == this.extraRoots.length && this.extraRoots.length > 0;
   }
   /** Sampled batches: chunks in them, then microseconds of copy in + climate, search, copy out. */
   private final java.util.concurrent.atomic.LongAdder[] batchTimings = {new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(),
      new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()};

   /** A lane for batches (the biome look-ahead): grow-only buffers for many chunks' cells at once. */
   private final class BatchLane {
      private final long stream;
      private long capacity;
      private long chunks;
      private long cellsCapacity;
      private long atDevice;
      private long atHost;
      private long climateDevice;
      private long projectedDevice;
      private long chosenDevice;
      private long tiedDevice;
      private long outHost;
      private long cellsDevice;
      private long cellsHost;
      private long xzDevice;
      private long xzHost;
      private long minimumDevice;
      private long minimumHost;
      private long riversDevice;
      private long riversHost;
      private long providerDevice;
      private long providerHost;
      private long decoratedDevice;
      private long extraCapacity;
      private long extraProjectedDevice;
      private long extraCCDevice;
      private long extraChosenDevice;
      private long extraTiedDevice;
      private long extraDecoratedDevice;
      private long extraHost;
      private final long[] events = new long[4];
      private int runs;

      /** C1: room for the other providers' results (extra = cells x providers). */
      void ensureExtra(long extra) {
         if (extra > this.extraCapacity) {
            if (this.extraCCDevice != 0L) {
               cuMemFree(this.extraProjectedDevice);
               cuMemFree(this.extraCCDevice);
               cuMemFree(this.extraChosenDevice);
               cuMemFree(this.extraTiedDevice);
               cuMemFree(this.extraDecoratedDevice);
               ncuMemFreeHost(this.extraHost);
            }

            this.extraCapacity = extra + extra / 4;
            this.extraProjectedDevice = device(48L * this.extraCapacity);
            this.extraCCDevice = device(8L * this.extraCapacity);
            this.extraChosenDevice = device(4L * this.extraCapacity);
            this.extraTiedDevice = device(4L * this.extraCapacity);
            this.extraDecoratedDevice = device(4L * this.extraCapacity);
            // Out on the host: the projected continentalness, the chosen numbers, the decorated numbers.
            this.extraHost = host(16L * this.extraCapacity);
         }
      }

      BatchLane() {
         GpuSelfTest.check(cuCtxSetCurrent(BiomeGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }
      }

      void ensure(long count, long chunks, long cells) {
         if (count > this.capacity) {
            if (this.atDevice != 0L) {
               cuMemFree(this.atDevice);
               ncuMemFreeHost(this.atHost);
               cuMemFree(this.climateDevice);
               cuMemFree(this.projectedDevice);
               cuMemFree(this.chosenDevice);
               cuMemFree(this.tiedDevice);
               cuMemFree(this.decoratedDevice);
               ncuMemFreeHost(this.outHost);
            }

            this.capacity = count + count / 4;
            long n = this.capacity;
            this.atDevice = device(12L * n);
            this.atHost = host(12L * n);
            this.climateDevice = device(48L * n);
            this.projectedDevice = device(48L * n);
            this.chosenDevice = device(4L * n);
            this.tiedDevice = device(4L * n);
            this.decoratedDevice = device(4L * n);
            // Out on the host: climate, projected, chosen, tied, decorated one after another.
            this.outHost = host(108L * n);
         }

         if (chunks > this.chunks) {
            if (this.xzDevice != 0L) {
               cuMemFree(this.xzDevice);
               ncuMemFreeHost(this.xzHost);
               cuMemFree(this.minimumDevice);
               ncuMemFreeHost(this.minimumHost);
               cuMemFree(this.riversDevice);
               ncuMemFreeHost(this.riversHost);
               cuMemFree(this.providerDevice);
               ncuMemFreeHost(this.providerHost);
            }

            this.chunks = chunks;
            this.riversDevice = device(64L * chunks);
            this.riversHost = host(64L * chunks);
            this.providerDevice = device(64L * chunks);
            this.providerHost = host(64L * chunks);
            this.xzDevice = device(8L * chunks);
            this.xzHost = host(8L * chunks);
            this.minimumDevice = device(64L * chunks);
            this.minimumHost = host(64L * chunks);
         }

         if (cells > this.cellsCapacity) {
            if (this.cellsDevice != 0L) {
               cuMemFree(this.cellsDevice);
               ncuMemFreeHost(this.cellsHost);
            }

            this.cellsCapacity = cells;
            this.cellsDevice = device(8L * cells);
            this.cellsHost = host(8L * cells);
         }
      }
   }

   /** The sampled batch times per chunk, for the logs. */
   public String batchTimings() {
      double chunks = Math.max(1, this.batchTimings[0].sum());
      return String.format(java.util.Locale.ROOT, "on the GPU per chunk, sampled: copy in + climate %.3f ms, search %.3f, copy out %.3f",
         this.batchTimings[1].sum() / 1000.0 / chunks, this.batchTimings[2].sum() / 1000.0 / chunks, this.batchTimings[3].sum() / 1000.0 / chunks);
   }

   /**
    * Several chunks in one launch per kernel (all with the same cell count and world policy: the first job's), on this
    * thread's batch lane, waited for; per job its outputs as {@link #run} gives them.
    */
   public void runBatch(java.util.List<Job> jobs, java.util.List<long[]> climateOut, java.util.List<long[]> projectedOut, java.util.List<int[]> chosenOut,
      java.util.List<int[]> tiedOut) {
      this.runBatch(jobs, climateOut, projectedOut, chosenOut, tiedOut, null);
   }

   /** Whether the decoration (G5.4) was compiled in: runBatch can then give each cell's decorated biome too. */
   public boolean decorates() {
      return this.decorate != 0L;
   }

   /**
    * As above; with decoratedOut (and the decoration compiled in) also each cell's decorated biome's number, or -1 where
    * the CPU decorates (a tie, a region's stage choice).
    */
   public void runBatch(java.util.List<Job> jobs, java.util.List<long[]> climateOut, java.util.List<long[]> projectedOut, java.util.List<int[]> chosenOut,
      java.util.List<int[]> tiedOut, java.util.List<int[]> decoratedOut) {
      this.runBatch(jobs, climateOut, projectedOut, chosenOut, tiedOut, decoratedOut, null, null, null);
   }

   /**
    * C1: as above; with extraCCOut and extraChosenOut (and other providers set) also, per job, each other provider k's
    * projected continentalness and chosen number (-1: a tie) for each cell i at k * cells + i.
    */
   public void runBatch(java.util.List<Job> jobs, java.util.List<long[]> climateOut, java.util.List<long[]> projectedOut, java.util.List<int[]> chosenOut,
      java.util.List<int[]> tiedOut, java.util.List<int[]> decoratedOut, java.util.List<long[]> extraCCOut, java.util.List<int[]> extraChosenOut,
      java.util.List<int[]> extraDecoratedOut) {
      int chunks = jobs.size();
      if (chunks == 0) {
         return;
      }

      Job firstJob = jobs.get(0);
      int per = firstJob.count;
      int stride = firstJob.cells.length;
      for (Job job : jobs) {
         if (job.count != per || job.cells.length != stride) {
            throw new IllegalArgumentException("a batch of chunks of different sizes");
         }
      }

      int n = per * chunks;
      BatchLane lane = this.batchLanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      lane.ensure(n, chunks, (long)stride * chunks);
      java.nio.IntBuffer at = MemoryUtil.memIntBuffer(lane.atHost, 3 * n);
      java.nio.DoubleBuffer cells = MemoryUtil.memDoubleBuffer(lane.cellsHost, stride * chunks);
      java.nio.IntBuffer xz = MemoryUtil.memIntBuffer(lane.xzHost, 2 * chunks);
      java.nio.IntBuffer minimum = MemoryUtil.memIntBuffer(lane.minimumHost, 16 * chunks);
      java.nio.IntBuffer rivers = MemoryUtil.memIntBuffer(lane.riversHost, 16 * chunks);
      java.nio.IntBuffer providers = MemoryUtil.memIntBuffer(lane.providerHost, 16 * chunks);
      for (int j = 0; j < chunks; j++) {
         Job job = jobs.get(j);
         at.put(3 * per * j, job.at, 0, 3 * per);
         cells.put(stride * j, job.cells, 0, stride);
         xz.put(2 * j, job.chunkX);
         xz.put(2 * j + 1, job.chunkZ);
         minimum.put(16 * j, job.minimumSurface, 0, 16);
         rivers.put(16 * j, job.rivers, 0, 16);
         providers.put(16 * j, job.provider, 0, 16);
      }

      boolean decorating = decoratedOut != null && this.decorate != 0L && (this.searchMulti == 0L || this.multiDecorates);
      int extras = extraCCOut != null && extraChosenOut != null ? this.extraRoots.length : 0;
      boolean decoratingExtras = decorating && extras > 0 && extraDecoratedOut != null && this.decoratesExtras();
      if (extras > 0) {
         lane.ensureExtra((long)n * extras);
      }

      boolean timed = lane.runs++ % 4 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.atDevice, lane.atHost, 12L * n, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.cellsDevice, lane.cellsHost, 8L * stride * chunks, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.xzDevice, lane.xzHost, 8L * chunks, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.minimumDevice, lane.minimumHost, 64L * chunks, lane.stream), "copy in");
      if (decorating) {
         GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.riversDevice, lane.riversHost, 64L * chunks, lane.stream), "copy in");
      }

      if (this.searchMulti != 0L) {
         GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.providerDevice, lane.providerHost, 64L * chunks, lane.stream), "copy in");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer c = stack.mallocPointer(19);
         c.put(0, stack.pointers(lane.atDevice).address());
         c.put(1, MemoryUtil.memAddress(stack.ints(n)));
         c.put(2, MemoryUtil.memAddress(stack.ints(per)));
         c.put(3, stack.pointers(lane.cellsDevice).address());
         c.put(4, MemoryUtil.memAddress(stack.ints(stride)));
         c.put(5, stack.pointers(lane.xzDevice).address());
         c.put(6, stack.pointers(this.noiseData).address());
         c.put(7, stack.pointers(this.permutations).address());
         c.put(8, MemoryUtil.memAddress(stack.ints(firstJob.banding ? 1 : 0)));
         c.put(9, MemoryUtil.memAddress(stack.doubles(Double.longBitsToDouble(firstJob.seed))));
         c.put(10, MemoryUtil.memAddress(stack.floats(firstJob.coverage)));
         c.put(11, MemoryUtil.memAddress(stack.ints(firstJob.size)));
         c.put(12, MemoryUtil.memAddress(stack.ints(firstJob.verticalSize)));
         c.put(13, MemoryUtil.memAddress(stack.ints(firstJob.surfaceDepth)));
         c.put(14, MemoryUtil.memAddress(stack.ints(firstJob.endDepth)));
         c.put(15, MemoryUtil.memAddress(stack.ints(firstJob.add21875)));
         c.put(16, MemoryUtil.memAddress(stack.ints(firstJob.add03125)));
         c.put(17, stack.pointers(lane.minimumDevice).address());
         c.put(18, stack.pointers(lane.climateDevice).address());
         GpuSelfTest.check(cuLaunchKernel(this.climateBatch, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, c, null), "cuLaunchKernel biomeClimateBatch");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         PointerBuffer s = stack.mallocPointer(12);
         s.put(0, stack.pointers(lane.climateDevice).address());
         s.put(1, MemoryUtil.memAddress(stack.ints(n)));
         s.put(2, stack.pointers(this.bounds).address());
         s.put(3, stack.pointers(this.first).address());
         s.put(4, stack.pointers(this.count).address());
         s.put(5, stack.pointers(this.nodeBiome).address());
         s.put(6, MemoryUtil.memAddress(stack.ints(this.sourceMin)));
         s.put(7, MemoryUtil.memAddress(stack.ints(this.sourceMax)));
         s.put(8, MemoryUtil.memAddress(stack.ints(this.targetMax)));
         s.put(9, stack.pointers(lane.projectedDevice).address());
         s.put(10, stack.pointers(lane.chosenDevice).address());
         s.put(11, stack.pointers(lane.tiedDevice).address());
         if (this.searchMulti != 0L) {
            PointerBuffer m = stack.mallocPointer(17);
            m.put(0, stack.pointers(lane.climateDevice).address());
            m.put(1, MemoryUtil.memAddress(stack.ints(n)));
            m.put(2, MemoryUtil.memAddress(stack.ints(per)));
            m.put(3, stack.pointers(lane.atDevice).address());
            m.put(4, stack.pointers(lane.xzDevice).address());
            m.put(5, stack.pointers(lane.providerDevice).address());
            m.put(6, stack.pointers(this.rootsDevice).address());
            m.put(7, stack.pointers(this.targetMaxesDevice).address());
            m.put(8, stack.pointers(this.bounds).address());
            m.put(9, stack.pointers(this.first).address());
            m.put(10, stack.pointers(this.count).address());
            m.put(11, stack.pointers(this.nodeBiome).address());
            m.put(12, MemoryUtil.memAddress(stack.ints(this.sourceMin)));
            m.put(13, MemoryUtil.memAddress(stack.ints(this.sourceMax)));
            m.put(14, stack.pointers(lane.projectedDevice).address());
            m.put(15, stack.pointers(lane.chosenDevice).address());
            m.put(16, stack.pointers(lane.tiedDevice).address());
            GpuSelfTest.check(cuLaunchKernel(this.searchMulti, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, m, null), "cuLaunchKernel biomeTreeSearchMulti");
         } else {
            GpuSelfTest.check(cuLaunchKernel(this.search, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, s, null), "cuLaunchKernel biomeTreeSearch");
         }

         for (int k = 0; k < extras; k++) {
            PointerBuffer e = stack.mallocPointer(14);
            e.put(0, stack.pointers(lane.climateDevice).address());
            e.put(1, MemoryUtil.memAddress(stack.ints(n)));
            e.put(2, MemoryUtil.memAddress(stack.ints(this.extraRoots[k])));
            e.put(3, stack.pointers(this.bounds).address());
            e.put(4, stack.pointers(this.first).address());
            e.put(5, stack.pointers(this.count).address());
            e.put(6, stack.pointers(this.nodeBiome).address());
            e.put(7, MemoryUtil.memAddress(stack.ints(this.sourceMin)));
            e.put(8, MemoryUtil.memAddress(stack.ints(this.sourceMax)));
            e.put(9, MemoryUtil.memAddress(stack.ints(this.extraTargetMax[k])));
            e.put(10, stack.pointers(lane.extraProjectedDevice + 48L * k * n).address());
            e.put(11, stack.pointers(lane.extraCCDevice + 8L * k * n).address());
            e.put(12, stack.pointers(lane.extraChosenDevice + 4L * k * n).address());
            e.put(13, stack.pointers(lane.extraTiedDevice + 4L * k * n).address());
            GpuSelfTest.check(cuLaunchKernel(this.searchRoot, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, e, null), "cuLaunchKernel biomeTreeSearchRoot");
         }

         if (decorating) {
            PointerBuffer d = stack.mallocPointer(20);
            d.put(0, stack.pointers(lane.climateDevice).address());
            d.put(1, stack.pointers(lane.projectedDevice).address());
            d.put(2, stack.pointers(lane.chosenDevice).address());
            d.put(3, stack.pointers(lane.tiedDevice).address());
            d.put(4, stack.pointers(lane.atDevice).address());
            d.put(5, MemoryUtil.memAddress(stack.ints(n)));
            d.put(6, MemoryUtil.memAddress(stack.ints(per)));
            d.put(7, stack.pointers(lane.xzDevice).address());
            d.put(8, stack.pointers(lane.minimumDevice).address());
            d.put(9, stack.pointers(lane.riversDevice).address());
            d.put(10, stack.pointers(this.decorLongs).address());
            d.put(11, stack.pointers(this.decorInts).address());
            d.put(12, MemoryUtil.memAddress(stack.floats(this.decorDensity)));
            d.put(13, MemoryUtil.memAddress(stack.floats(this.decorCoverage)));
            d.put(14, stack.pointers(this.decorFlags).address());
            d.put(15, stack.pointers(this.bounds).address());
            d.put(16, stack.pointers(this.first).address());
            d.put(17, stack.pointers(this.count).address());
            d.put(18, stack.pointers(this.nodeBiome).address());
            d.put(19, stack.pointers(lane.decoratedDevice).address());
            if (this.searchMulti != 0L) {
               PointerBuffer dm = stack.mallocPointer(22);
               for (int a = 0; a < 10; a++) {
                  dm.put(a, d.get(a));
               }

               dm.put(10, stack.pointers(this.pAllDevice).address());
               dm.put(11, stack.pointers(this.qAllDevice).address());
               dm.put(12, stack.pointers(lane.providerDevice).address());
               dm.put(13, MemoryUtil.memAddress(stack.ints(this.flagStride)));
               dm.put(14, stack.pointers(this.densitiesDevice).address());
               dm.put(15, d.get(13));
               dm.put(16, stack.pointers(this.flagsAllDevice).address());
               dm.put(17, d.get(15));
               dm.put(18, d.get(16));
               dm.put(19, d.get(17));
               dm.put(20, d.get(18));
               dm.put(21, d.get(19));
               GpuSelfTest.check(cuLaunchKernel(this.decorateMulti, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, dm, null), "cuLaunchKernel biomeDecorateMulti");
            } else {
               GpuSelfTest.check(cuLaunchKernel(this.decorate, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, d, null), "cuLaunchKernel biomeDecorate");
            }

            // C1: each other provider's cells with its own Layout, flags and river table (their roots in its ints).
            for (int k = 0; decoratingExtras && k < extras; k++) {
               d.put(1, stack.pointers(lane.extraProjectedDevice + 48L * k * n).address());
               d.put(2, stack.pointers(lane.extraChosenDevice + 4L * k * n).address());
               d.put(3, stack.pointers(lane.extraTiedDevice + 4L * k * n).address());
               d.put(10, stack.pointers(this.extraDecorLongs[k]).address());
               d.put(11, stack.pointers(this.extraDecorInts[k]).address());
               d.put(12, MemoryUtil.memAddress(stack.floats(this.extraDecorDensity[k])));
               d.put(14, stack.pointers(this.extraDecorFlags[k]).address());
               d.put(19, stack.pointers(lane.extraDecoratedDevice + 4L * k * n).address());
               GpuSelfTest.check(cuLaunchKernel(this.decorate, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, d, null), "cuLaunchKernel biomeDecorate");
            }
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }
      }

      long decoratedHost = lane.outHost + 104L * n;
      if (decorating) {
         GpuSelfTest.check(ncuMemcpyDtoHAsync(decoratedHost, lane.decoratedDevice, 4L * n, lane.stream), "copy out");
      }

      long climateHost = lane.outHost;
      long projectedHost = lane.outHost + 48L * n;
      long chosenHost = lane.outHost + 96L * n;
      long tiedHost = lane.outHost + 100L * n;
      GpuSelfTest.check(ncuMemcpyDtoHAsync(climateHost, lane.climateDevice, 48L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(projectedHost, lane.projectedDevice, 48L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(chosenHost, lane.chosenDevice, 4L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(tiedHost, lane.tiedDevice, 4L * n, lane.stream), "copy out");
      long extraCCHost = lane.extraHost;
      long extraChosenHost = lane.extraHost + 8L * n * extras;
      long extraDecoratedHost = lane.extraHost + 12L * n * extras;
      if (extras > 0) {
         GpuSelfTest.check(ncuMemcpyDtoHAsync(extraCCHost, lane.extraCCDevice, 8L * n * extras, lane.stream), "copy out");
         GpuSelfTest.check(ncuMemcpyDtoHAsync(extraChosenHost, lane.extraChosenDevice, 4L * n * extras, lane.stream), "copy out");
         if (decoratingExtras) {
            GpuSelfTest.check(ncuMemcpyDtoHAsync(extraDecoratedHost, lane.extraDecoratedDevice, 4L * n * extras, lane.stream), "copy out");
         }
      }

      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize");
      for (int j = 0; extras > 0 && j < chunks; j++) {
         long[] cc = extraCCOut.get(j);
         int[] chosen = extraChosenOut.get(j);
         for (int k = 0; k < extras; k++) {
            MemoryUtil.memLongBuffer(extraCCHost + 8L * ((long)k * n + (long)per * j), per).get(0, cc, k * per, per);
            MemoryUtil.memIntBuffer(extraChosenHost + 4L * ((long)k * n + (long)per * j), per).get(0, chosen, k * per, per);
            if (decoratingExtras) {
               MemoryUtil.memIntBuffer(extraDecoratedHost + 4L * ((long)k * n + (long)per * j), per).get(0, extraDecoratedOut.get(j), k * per, per);
            }
         }
      }

      if (timed) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer ms = stack.mallocFloat(1);
            for (int i = 0; i < 3; i++) {
               GpuSelfTest.check(cuEventElapsedTime(ms, lane.events[i], lane.events[i + 1]), "cuEventElapsedTime");
               this.batchTimings[i + 1].add((long)(ms.get(0) * 1000.0f));
            }

            this.batchTimings[0].add(chunks);
         }
      }

      for (int j = 0; j < chunks; j++) {
         MemoryUtil.memLongBuffer(climateHost + 48L * per * j, 6 * per).get(0, climateOut.get(j), 0, 6 * per);
         MemoryUtil.memLongBuffer(projectedHost + 48L * per * j, 6 * per).get(0, projectedOut.get(j), 0, 6 * per);
         MemoryUtil.memIntBuffer(chosenHost + 4L * per * j, per).get(0, chosenOut.get(j), 0, per);
         MemoryUtil.memIntBuffer(tiedHost + 4L * per * j, per).get(0, tiedOut.get(j), 0, per);
         if (decorating) {
            MemoryUtil.memIntBuffer(decoratedHost + 4L * per * j, per).get(0, decoratedOut.get(j), 0, per);
         }
      }
   }

   public BiomeGpu(long context, String source, double[] noise, int[] perm, int[] bounds, int[] first, int[] count, int[] nodeBiome, long sourceMin, long sourceMax, long targetMax) {
      this(context, source, noise, perm, bounds, first, count, nodeBiome, sourceMin, sourceMax, targetMax, null, null, 0f, 0f, null);
   }

   /**
    * With FreeTerraForged's decoration (G5.4, decorLongs non-null): the trees are the decoration's combined ones (the main
    * table's at root 0), its settings as {@link BiomeDecoration} has them.
    */
   public BiomeGpu(long context, String source, double[] noise, int[] perm, int[] bounds, int[] first, int[] count, int[] nodeBiome, long sourceMin, long sourceMax,
      long targetMax, long[] decorLongs, int[] decorInts, float decorDensity, float decorCoverage, int[] decorFlags) {
      this.context = context;
      GpuSelfTest.check(cuCtxSetCurrent(context), "cuCtxSetCurrent");
      this.module = Gpu.loadModule(source, "biomes.cu");
      this.climate = function(this.module, "biomeClimate");
      this.search = function(this.module, "biomeTreeSearch");
      this.climateBatch = function(this.module, "biomeClimateBatch");
      this.noiseData = upload(noise);
      this.permutations = upload(perm);
      this.bounds = upload(bounds);
      this.first = upload(first);
      this.count = upload(count);
      this.nodeBiome = upload(nodeBiome);
      this.sourceMin = Math.toIntExact(sourceMin);
      this.sourceMax = Math.toIntExact(sourceMax);
      this.targetMax = Math.toIntExact(targetMax);
      if (decorLongs != null) {
         this.decorate = function(this.module, "biomeDecorate");
         ByteBuffer longs = MemoryUtil.memAlloc(8 * decorLongs.length);
         try {
            longs.asLongBuffer().put(0, decorLongs);
            this.decorLongs = device(longs.remaining());
            GpuSelfTest.check(cuMemcpyHtoD(this.decorLongs, longs), "copy in");
         } finally {
            MemoryUtil.memFree(longs);
         }

         this.decorInts = upload(decorInts);
         this.decorFlags = upload(decorFlags);
         this.decorDensity = decorDensity;
         this.decorCoverage = decorCoverage;
      } else {
         this.decorate = 0L;
         this.decorLongs = 0L;
         this.decorInts = 0L;
         this.decorFlags = 0L;
         this.decorDensity = 0f;
         this.decorCoverage = 0f;
      }
   }

   /** The sampled kernel times per chunk, for the logs. */
   public String timings() {
      double runs = Math.max(1, this.timings[0].sum());
      return String.format(java.util.Locale.ROOT, "on the GPU, sampled: climate %.2f ms, search %.2f, copy out %.2f", this.timings[1].sum() / 1000.0 / runs,
         this.timings[2].sum() / 1000.0 / runs, this.timings[3].sum() / 1000.0 / runs);
   }

   /**
    * The chunk's cells on this thread's lane, waited for: per cell its target point (climate[6 i ..]), the projected
    * target (projected[6 i ..]), the chosen biome's number (BiomeTable.biomes) and whether the smallest distance is shared by another biome.
    */
   public void run(Job job, long[] climateOut, long[] projectedOut, int[] chosenOut, int[] tiedOut) {
      int n = job.count;
      if (n == 0) {
         return;
      }

      Lane lane = this.lanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      lane.ensure(n, Math.max(1, job.cells.length));
      MemoryUtil.memIntBuffer(lane.atHost, 3 * n).put(0, job.at, 0, 3 * n);
      MemoryUtil.memDoubleBuffer(lane.cellsHost, job.cells.length).put(0, job.cells);
      MemoryUtil.memIntBuffer(lane.minimumHost, 16).put(0, job.minimumSurface);
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.atDevice, lane.atHost, 12L * n, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.cellsDevice, lane.cellsHost, 8L * job.cells.length, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.minimumDevice, lane.minimumHost, 4L * 16, lane.stream), "copy in");
      boolean timed = lane.runs++ % 16 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer c = stack.mallocPointer(18);
         c.put(0, stack.pointers(lane.atDevice).address());
         c.put(1, MemoryUtil.memAddress(stack.ints(n)));
         c.put(2, stack.pointers(lane.cellsDevice).address());
         c.put(3, MemoryUtil.memAddress(stack.ints(job.chunkX)));
         c.put(4, MemoryUtil.memAddress(stack.ints(job.chunkZ)));
         c.put(5, stack.pointers(this.noiseData).address());
         c.put(6, stack.pointers(this.permutations).address());
         c.put(7, MemoryUtil.memAddress(stack.ints(job.banding ? 1 : 0)));
         c.put(8, MemoryUtil.memAddress(stack.doubles(Double.longBitsToDouble(job.seed))));
         c.put(9, MemoryUtil.memAddress(stack.floats(job.coverage)));
         c.put(10, MemoryUtil.memAddress(stack.ints(job.size)));
         c.put(11, MemoryUtil.memAddress(stack.ints(job.verticalSize)));
         c.put(12, MemoryUtil.memAddress(stack.ints(job.surfaceDepth)));
         c.put(13, MemoryUtil.memAddress(stack.ints(job.endDepth)));
         c.put(14, MemoryUtil.memAddress(stack.ints(job.add21875)));
         c.put(15, MemoryUtil.memAddress(stack.ints(job.add03125)));
         c.put(16, stack.pointers(lane.minimumDevice).address());
         c.put(17, stack.pointers(lane.climateDevice).address());
         GpuSelfTest.check(cuLaunchKernel(this.climate, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, c, null), "cuLaunchKernel biomeClimate");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         PointerBuffer s = stack.mallocPointer(12);
         s.put(0, stack.pointers(lane.climateDevice).address());
         s.put(1, MemoryUtil.memAddress(stack.ints(n)));
         s.put(2, stack.pointers(this.bounds).address());
         s.put(3, stack.pointers(this.first).address());
         s.put(4, stack.pointers(this.count).address());
         s.put(5, stack.pointers(this.nodeBiome).address());
         s.put(6, MemoryUtil.memAddress(stack.ints(this.sourceMin)));
         s.put(7, MemoryUtil.memAddress(stack.ints(this.sourceMax)));
         s.put(8, MemoryUtil.memAddress(stack.ints(this.targetMax)));
         s.put(9, stack.pointers(lane.projectedDevice).address());
         s.put(10, stack.pointers(lane.chosenDevice).address());
         s.put(11, stack.pointers(lane.tiedDevice).address());
         GpuSelfTest.check(cuLaunchKernel(this.search, (n + 127) / 128, 1, 1, 128, 1, 1, 0, lane.stream, s, null), "cuLaunchKernel biomeTreeSearch");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

      }

      long climateHost = lane.outHost;
      long projectedHost = lane.outHost + 48L * n;
      long chosenHost = lane.outHost + 96L * n;
      long tiedHost = lane.outHost + 100L * n;
      GpuSelfTest.check(ncuMemcpyDtoHAsync(climateHost, lane.climateDevice, 48L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(projectedHost, lane.projectedDevice, 48L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(chosenHost, lane.chosenDevice, 4L * n, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(tiedHost, lane.tiedDevice, 4L * n, lane.stream), "copy out");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize");
      if (timed) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer ms = stack.mallocFloat(1);
            for (int i = 0; i < 3; i++) {
               GpuSelfTest.check(cuEventElapsedTime(ms, lane.events[i], lane.events[i + 1]), "cuEventElapsedTime");
               this.timings[i + 1].add((long)(ms.get(0) * 1000.0f));
            }

            this.timings[0].increment();
         }
      }

      MemoryUtil.memLongBuffer(climateHost, 6 * n).get(0, climateOut, 0, 6 * n);
      MemoryUtil.memLongBuffer(projectedHost, 6 * n).get(0, projectedOut, 0, 6 * n);
      MemoryUtil.memIntBuffer(chosenHost, n).get(0, chosenOut, 0, n);
      MemoryUtil.memIntBuffer(tiedHost, n).get(0, tiedOut, 0, n);
   }

   private static long function(long module, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         GpuSelfTest.check(cuModuleGetFunction(function, module, stack.ASCII(name)), "cuModuleGetFunction " + name);
         return function.get(0);
      }
   }

   private static long device(long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         GpuSelfTest.check(cuMemAlloc(pointer, Math.max(4L, bytes)), "cuMemAlloc");
         return pointer.get(0);
      }
   }

   private static long host(long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         GpuSelfTest.check(cuMemAllocHost(pointer, Math.max(4L, bytes)), "cuMemAllocHost");
         return pointer.get(0);
      }
   }

   private static long upload(double[] values) {
      ByteBuffer buffer = MemoryUtil.memAlloc(8 * Math.max(1, values.length));
      try {
         buffer.asDoubleBuffer().put(0, values);
         long device = device(buffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(device, buffer), "copy in");
         return device;
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static long upload(int[] values) {
      ByteBuffer buffer = MemoryUtil.memAlloc(4 * Math.max(1, values.length));
      try {
         buffer.asIntBuffer().put(0, values);
         long device = device(buffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(device, buffer), "copy in");
         return device;
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }
}
