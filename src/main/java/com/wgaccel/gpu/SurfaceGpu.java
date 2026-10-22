package com.wgaccel.gpu;

import static com.wgaccel.gpu.GpuApi.*;

import com.wgaccel.Config;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G3.4: the surface for production. Two programs in the shared context: FreeTerraForged's strata
 * ({@link StrataGpu#productionSource}: strataSelect, strataLayers, strataFinish) and the production surface ({@link SurfaceCodegen#productionSource}:
 * surfacePrepass per column, then surfaceBlocks per block). Run by the generation threads themselves, each on its own
 * lane (a non-blocking stream, device buffers and page-locked host buffers), like {@link FillGpu}. Uses no Minecraft
 * classes.
 */
public final class SurfaceGpu {
   /**
    * One chunk's inputs, as {@code SurfaceTap} works them out before the surface. {@code height} may be less than the
    * chunk's: only blocks from minY up to the highest column start are read and written.
    */
   public static final class Job {
      /** Per y from minY, per column (t = lx * 16 + lz), at (y - minY) * 256 + t: the block kind byte. */
      public byte[] kinds;
      public int minY;
      public int height;
      public int minX;
      public int minZ;
      public final int[] tops = new int[256];
      public final int[] heights = new int[256];
      public final int[] minBase = new int[256];
      /** The quart biome ids and their count. */
      public int[] quarts = new int[1];
      public int quartCount;
      /** The quart grid: origin and sizes, as the check kernels take it. */
      public final int[] grid = new int[6];
      public long zoomSeed;
   }

   private final class Lane {
      private final long stream;
      private final long[] events = new long[5];
      private int runs;
      private int capacity;
      private int quartCapacity;
      private long kindsDevice;
      private long kindsHost;
      private long intsDevice;
      private long intsHost;
      private long quartsDevice;
      private long quartsHost;
      private long strataDepth;
      private long strataState;
      private long strataCount;
      private long unsureDevice;
      private long indexDevice;
      private long unsureLayer;
      private long above;
      private long below;
      private long water;
      private long columnDepth;
      private long columnSecondary;
      private long specialDevice;
      private long outDevice;
      private long outHost;
      private long flagsHost;

      Lane() {
         GpuSelfTest.check(cuCtxSetCurrent(SurfaceGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }

         int max = SurfaceGpu.this.strataMax;
         this.intsDevice = device(4L * (3 * 256 + 6));
         this.intsHost = host(4L * (3 * 256 + 6));
         this.strataDepth = device(4L * 256 * max);
         this.strataState = device(4L * 256 * max);
         this.strataCount = device(4L * 256);
         this.unsureDevice = device(4L * 256);
         this.indexDevice = device(4L * 256);
         this.unsureLayer = device(4L * 256 * max);
         this.columnDepth = device(4L * 256);
         this.columnSecondary = device(8L * 256);
         this.specialDevice = device(4L * 256);
         this.flagsHost = host(4L * 512);
      }

      /** Buffers for at least this many blocks per column (they only grow: the height worked on changes per chunk). */
      void ensure(int height, int quarts) {
         if (height > this.capacity) {
            if (this.kindsDevice != 0L) {
               cuMemFree(this.kindsDevice);
               ncuMemFreeHost(this.kindsHost);
               cuMemFree(this.above);
               cuMemFree(this.below);
               cuMemFree(this.water);
               cuMemFree(this.outDevice);
               ncuMemFreeHost(this.outHost);
            }

            this.capacity = Math.max(64, height + height / 4);
            long n = 256L * this.capacity;
            this.kindsDevice = device(n);
            this.kindsHost = host(n);
            this.above = device(4L * n);
            this.below = device(4L * n);
            this.water = device(4L * n);
            this.outDevice = device(2L * n);
            this.outHost = host(2L * n);
         }

         if (quarts > this.quartCapacity) {
            if (this.quartsDevice != 0L) {
               cuMemFree(this.quartsDevice);
               ncuMemFreeHost(this.quartsHost);
            }

            this.quartCapacity = Math.max(1024, quarts + quarts / 4);
            this.quartsDevice = device(4L * this.quartCapacity);
            this.quartsHost = host(4L * this.quartCapacity);
         }
      }
   }

   private final long context;
   private final long strataModule;
   private final long productionModule;
   private final long strataSelect;
   private final long strataLayers;
   private final long strataFinish;
   private final long prepass;
   private final long prepassWarp;
   private final long blocks;
   private final int strataMax;
   private final long nd;
   private final long np;
   private final long ind;
   private final long inp;
   private final long sd;
   private final long sp;
   private final long temperature;
   private final long modifier;
   private final ThreadLocal<Lane> lanes = ThreadLocal.withInitial(Lane::new);
   private final ThreadLocal<BatchLane> batchLanes = ThreadLocal.withInitial(BatchLane::new);
   private final long strataSelectBatch;
   private final long strataLayersBatch;
   private final long strataFinishBatch;
   private final long prepassBatch;
   private final long prepassWarpBatch;
   private final long blocksBatch;
   /** Longs per chunk in a batch's parameter table (see {@link #strataBatchSource}). */
   private static final int PARAMS = 24;
   public final LongAdder batches = new LongAdder();
   public final LongAdder batchChunks = new LongAdder();

   private static String bodies(String source, String... names) {
      for (String name : names) {
         String entry = "extern \"C\" __global__ void " + name + "(";
         if (!source.contains(entry)) {
            throw new IllegalStateException("the surface source has no " + name + " kernel");
         }

         source = source.replace(entry, "__device__ __forceinline__ void " + name + "_body(");
      }

      return source;
   }

   /**
    * G6b: the strata source with batch entries. Each kernel becomes a device function with the same body, called by its
    * old entry (unchanged parameters) and by a batch entry running it for chunk blockIdx.y of a parameter table (PARAMS
    * longs per chunk: 0 minX, 1 minZ, 2 minY, 3 height, 4 zoomSeed; device addresses 5 index, 6 count, 7 unsure, 8 strata
    * depth, 9 strata state, 10 unsure layer, 11 kinds, 12 tops, 13 heights, 14 minBase, 15 grid, 16 quarts, 17 above,
    * 18 below, 19 water, 20 column depth, 21 column secondary, 22 special, 23 out). The bodies index their threads by
    * blockIdx.x and threadIdx.x only and return early past their own chunk's work.
    */
   public static String strataBatchSource(String source) {
      return bodies(source, "strataSelect", "strataLayers", "strataFinish") + """
         extern "C" __global__ void strataSelect(int minX, int minZ, int* indexOut, int* countOut, int* unsureOut) {
             strataSelect_body(minX, minZ, indexOut, countOut, unsureOut);
         }
         extern "C" __global__ void strataLayers(int minX, int minZ, const int* indexIn, const int* countIn, float* depthOut, int* stateOut, int* unsureLayer) {
             strataLayers_body(minX, minZ, indexIn, countIn, depthOut, stateOut, unsureLayer);
         }
         extern "C" __global__ void strataFinish(const int* heights, float* depthOut, const int* countIn, int* unsureOut, const int* unsureLayer) {
             strataFinish_body(heights, depthOut, countIn, unsureOut, unsureLayer);
         }
         extern "C" __global__ void strataSelectBatch(const long long* jobs) {
             const long long* p = jobs + blockIdx.y * 24;
             strataSelect_body((int)p[0], (int)p[1], (int*)p[5], (int*)p[6], (int*)p[7]);
         }
         extern "C" __global__ void strataLayersBatch(const long long* jobs) {
             const long long* p = jobs + blockIdx.y * 24;
             strataLayers_body((int)p[0], (int)p[1], (const int*)p[5], (const int*)p[6], (float*)p[8], (int*)p[9], (int*)p[10]);
         }
         extern "C" __global__ void strataFinishBatch(const long long* jobs) {
             const long long* p = jobs + blockIdx.y * 24;
             strataFinish_body((const int*)p[13], (float*)p[8], (const int*)p[6], (int*)p[7], (const int*)p[10]);
         }
         """;
   }

   /** G6b: the production source with batch entries for surfacePrepass and surfaceBlocks (as {@link #strataBatchSource}). */
   public static String productionBatchSource(String source) {
      return bodies(source, "surfacePrepass", "surfacePrepassWarp", "surfaceBlocks") + """
         extern "C" __global__ void surfacePrepass(const unsigned char* kinds, int minY, int height, const int* tops, int minX, int minZ, const int* grid,
                                                   const int* g, long long zoomSeed, const double* ind, const int* inp, int* above, int* below,
                                                   int* water, int* columnDepth, double* columnSecondary, int* special) {
             surfacePrepass_body(kinds, minY, height, tops, minX, minZ, grid, g, zoomSeed, ind, inp, above, below, water, columnDepth, columnSecondary, special);
         }
         extern "C" __global__ void surfaceBlocks(int minY, int height, int minX, int minZ, const int* heights, const int* minBase, const int* above,
                                                  const int* below, const int* water, const int* columnDepth, const double* columnSecondary,
                                                  const float* strataDepth, const int* strataState, const int* strataCount, int strataMax, const int* grid,
                                                  const int* g, long long zoomSeed, const float* temperature, const int* modifier, const double* sd,
                                                  const int* sp, const double* nd, const int* np, short* out) {
             surfaceBlocks_body(minY, height, minX, minZ, heights, minBase, above, below, water, columnDepth, columnSecondary, strataDepth, strataState,
                                strataCount, strataMax, grid, g, zoomSeed, temperature, modifier, sd, sp, nd, np, out);
         }
         extern "C" __global__ void surfacePrepassBatch(const long long* jobs, const double* ind, const int* inp) {
             const long long* p = jobs + blockIdx.y * 24;
             surfacePrepass_body((const unsigned char*)p[11], (int)p[2], (int)p[3], (const int*)p[12], (int)p[0], (int)p[1], (const int*)p[16],
                                 (const int*)p[15], p[4], ind, inp, (int*)p[17], (int*)p[18], (int*)p[19], (int*)p[20], (double*)p[21], (int*)p[22]);
         }
         extern "C" __global__ void surfacePrepassWarp(const unsigned char* kinds, int minY, int height, const int* tops, int minX, int minZ, const int* grid,
                                                       const int* g, long long zoomSeed, const double* ind, const int* inp, int* above, int* below,
                                                       int* water, int* columnDepth, double* columnSecondary, int* special) {
             surfacePrepassWarp_body(kinds, minY, height, tops, minX, minZ, grid, g, zoomSeed, ind, inp, above, below, water, columnDepth, columnSecondary,
                                     special);
         }
         extern "C" __global__ void surfacePrepassWarpBatch(const long long* jobs, const double* ind, const int* inp) {
             const long long* p = jobs + blockIdx.y * 24;
             surfacePrepassWarp_body((const unsigned char*)p[11], (int)p[2], (int)p[3], (const int*)p[12], (int)p[0], (int)p[1], (const int*)p[16],
                                     (const int*)p[15], p[4], ind, inp, (int*)p[17], (int*)p[18], (int*)p[19], (int*)p[20], (double*)p[21], (int*)p[22]);
         }
         extern "C" __global__ void surfaceBlocksBatch(const long long* jobs, int strataMax, const float* temperature, const int* modifier, const double* sd,
                                                       const int* sp, const double* nd, const int* np) {
             const long long* p = jobs + blockIdx.y * 24;
             surfaceBlocks_body((int)p[2], (int)p[3], (int)p[0], (int)p[1], (const int*)p[13], (const int*)p[14], (const int*)p[17], (const int*)p[18],
                                (const int*)p[19], (const int*)p[20], (const double*)p[21], (const float*)p[8], (const int*)p[9], (const int*)p[6], strataMax,
                                (const int*)p[16], (const int*)p[15], p[4], temperature, modifier, sd, sp, nd, np, (short*)p[23]);
         }
         """;
   }

   /** A lane for batches (the surface look-ahead): per buffer one region per chunk at a fixed stride, grow-only. */
   private final class BatchLane {
      private final long stream;
      private int chunks;
      private int height;
      private int quarts;
      private long params;
      private long paramsHost;
      private long kinds;
      private long kindsHost;
      private long ints;
      private long intsHost;
      private long quartsDevice;
      private long quartsHost;
      private long index;
      private long count;
      private long unsure;
      private long strataDepth;
      private long strataState;
      private long unsureLayer;
      private long above;
      private long below;
      private long water;
      private long columnDepth;
      private long columnSecondary;
      private long special;
      private long out;
      private long outHost;
      private long flagsHost;
      /** Every 16th batch timed: before the copies in, after them, after strata, prepass, blocks, the copies out. */
      private final long[] events = new long[6];
      private int batches;

      BatchLane() {
         GpuSelfTest.check(cuCtxSetCurrent(SurfaceGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }
      }

      private void free() {
         long[] devices = {this.params, this.kinds, this.ints, this.quartsDevice, this.index, this.count, this.unsure, this.strataDepth, this.strataState,
            this.unsureLayer, this.above, this.below, this.water, this.columnDepth, this.columnSecondary, this.special, this.out};
         for (long device : devices) {
            if (device != 0L) {
               cuMemFree(device);
            }
         }

         long[] hosts = {this.paramsHost, this.kindsHost, this.intsHost, this.quartsHost, this.outHost, this.flagsHost};
         for (long host : hosts) {
            if (host != 0L) {
               ncuMemFreeHost(host);
            }
         }
      }

      /** Room for this many chunks of at most this many blocks per column and quarts. */
      void ensure(int chunks, int height, int quarts) {
         if (chunks <= this.chunks && height <= this.height && quarts <= this.quarts) {
            return;
         }

         this.free();
         this.chunks = Math.max(chunks, this.chunks);
         this.height = Math.max(64, Math.max(height + height / 4, this.height));
         this.quarts = Math.max(1024, Math.max(quarts + quarts / 4, this.quarts));
         long c = this.chunks;
         long blocks = 256L * this.height;
         int max = SurfaceGpu.this.strataMax;
         this.params = device(8L * PARAMS * c);
         this.paramsHost = host(8L * PARAMS * c);
         this.kinds = device(blocks * c);
         this.kindsHost = host(blocks * c);
         this.ints = device(4L * (3 * 256 + 6) * c);
         this.intsHost = host(4L * (3 * 256 + 6) * c);
         this.quartsDevice = device(4L * this.quarts * c);
         this.quartsHost = host(4L * this.quarts * c);
         this.index = device(4L * 256 * c);
         this.count = device(4L * 256 * c);
         this.unsure = device(4L * 256 * c);
         this.strataDepth = device(4L * 256 * max * c);
         this.strataState = device(4L * 256 * max * c);
         this.unsureLayer = device(4L * 256 * max * c);
         this.above = device(4L * blocks * c);
         this.below = device(4L * blocks * c);
         this.water = device(4L * blocks * c);
         this.columnDepth = device(4L * 256 * c);
         this.columnSecondary = device(8L * 256 * c);
         this.special = device(4L * 256 * c);
         this.out = device(2L * blocks * c);
         this.outHost = host(2L * blocks * c);
         this.flagsHost = host(4L * 512 * c);
      }
   }

   /**
    * G6b: several chunks' surfaces in one launch per kernel on this thread's batch lane, waited for. Per chunk as
    * {@link #run}: its blocks into its out array and whether it may be used (false: a column needs the CPU).
    */
   public boolean[] runBatch(java.util.List<Job> jobs, java.util.List<short[]> outs) {
      int chunks = jobs.size();
      boolean[] done = new boolean[chunks];
      if (chunks == 0) {
         return done;
      }

      int maxHeight = 1;
      int maxQuarts = 1;
      for (Job job : jobs) {
         maxHeight = Math.max(maxHeight, job.height);
         maxQuarts = Math.max(maxQuarts, job.quartCount);
      }

      long entered = System.nanoTime();
      BatchLane lane = this.batchLanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      lane.ensure(chunks, maxHeight, maxQuarts);
      long blocks = 256L * lane.height;
      int max = this.strataMax;
      java.nio.LongBuffer params = MemoryUtil.memLongBuffer(lane.paramsHost, PARAMS * chunks);
      for (int c = 0; c < chunks; c++) {
         Job job = jobs.get(c);
         int n = 256 * job.height;
         MemoryUtil.memByteBuffer(lane.kindsHost + blocks * c, n).put(0, job.kinds, 0, n);
         IntBuffer ints = MemoryUtil.memIntBuffer(lane.intsHost + 4L * (3 * 256 + 6) * c, 3 * 256 + 6);
         ints.put(0, job.tops).put(256, job.heights).put(512, job.minBase).put(768, job.grid);
         MemoryUtil.memIntBuffer(lane.quartsHost + 4L * lane.quarts * c, Math.max(1, job.quartCount)).put(0, job.quarts, 0, job.quartCount);
         long intsAt = lane.ints + 4L * (3 * 256 + 6) * c;
         long[] p = {job.minX, job.minZ, job.minY, job.height, job.zoomSeed, lane.index + 4L * 256 * c, lane.count + 4L * 256 * c, lane.unsure + 4L * 256 * c,
            lane.strataDepth + 4L * 256 * max * c, lane.strataState + 4L * 256 * max * c, lane.unsureLayer + 4L * 256 * max * c, lane.kinds + blocks * c, intsAt,
            intsAt + 4L * 256, intsAt + 4L * 512, intsAt + 4L * 768, lane.quartsDevice + 4L * lane.quarts * c, lane.above + 4L * blocks * c,
            lane.below + 4L * blocks * c, lane.water + 4L * blocks * c, lane.columnDepth + 4L * 256 * c, lane.columnSecondary + 8L * 256 * c,
            lane.special + 4L * 256 * c, lane.out + 2L * blocks * c};
         params.put(PARAMS * c, p);
      }

      boolean timed = lane.batches++ % 16 == 0 && GpuBackend.TIMING;
      long submitted = System.nanoTime();
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.params, lane.paramsHost, 8L * PARAMS * chunks, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.kinds, lane.kindsHost, blocks * chunks, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.ints, lane.intsHost, 4L * (3 * 256 + 6) * chunks, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.quartsDevice, lane.quartsHost, 4L * lane.quarts * chunks, lane.stream), "copy in");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer jobsArg = stack.mallocPointer(1);
         jobsArg.put(0, stack.pointers(lane.params).address());
         // I3: the strata kernels in blocks of 32 threads (more, smaller blocks start sooner on a busy card; the same work).
         int columnThreads = Config.GPU_SURFACE_SMALL_BLOCKS.get() ? 32 : 256;
         int layerThreads = Config.GPU_SURFACE_SMALL_BLOCKS.get() ? 32 : 128;
         GpuSelfTest.check(cuLaunchKernel(this.strataSelectBatch, 256 / columnThreads, chunks, 1, columnThreads, 1, 1, 0, lane.stream, jobsArg, null), "cuLaunchKernel strataSelectBatch");
         GpuSelfTest.check(cuLaunchKernel(this.strataLayersBatch, (256 * max + layerThreads - 1) / layerThreads, chunks, 1, layerThreads, 1, 1, 0, lane.stream, jobsArg, null),
            "cuLaunchKernel strataLayersBatch");
         GpuSelfTest.check(cuLaunchKernel(this.strataFinishBatch, 256 / columnThreads, chunks, 1, columnThreads, 1, 1, 0, lane.stream, jobsArg, null), "cuLaunchKernel strataFinishBatch");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

         PointerBuffer pre = stack.mallocPointer(3);
         pre.put(0, stack.pointers(lane.params).address());
         pre.put(1, stack.pointers(this.ind).address());
         pre.put(2, stack.pointers(this.inp).address());
         if (Config.GPU_SURFACE_WARP_PREPASS.get()) {
            // I3: a warp per column, 8 columns per block.
            GpuSelfTest.check(cuLaunchKernel(this.prepassWarpBatch, 32, chunks, 1, 256, 1, 1, 0, lane.stream, pre, null), "cuLaunchKernel surfacePrepassWarpBatch");
         } else {
            GpuSelfTest.check(cuLaunchKernel(this.prepassBatch, 1, chunks, 1, 256, 1, 1, 0, lane.stream, pre, null), "cuLaunchKernel surfacePrepassBatch");
         }
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
         }

         PointerBuffer block = stack.mallocPointer(8);
         block.put(0, stack.pointers(lane.params).address());
         block.put(1, MemoryUtil.memAddress(stack.ints(max)));
         block.put(2, stack.pointers(this.temperature).address());
         block.put(3, stack.pointers(this.modifier).address());
         block.put(4, stack.pointers(this.sd).address());
         block.put(5, stack.pointers(this.sp).address());
         block.put(6, stack.pointers(this.nd).address());
         block.put(7, stack.pointers(this.np).address());
         GpuSelfTest.check(cuLaunchKernel(this.blocksBatch, (256 * maxHeight + 255) / 256, chunks, 1, 256, 1, 1, 0, lane.stream, block, null),
            "cuLaunchKernel surfaceBlocksBatch");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[4], lane.stream), "cuEventRecord");
         }
      }

      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.flagsHost, lane.special, 4L * 256 * chunks, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.flagsHost + 4L * 256 * chunks, lane.unsure, 4L * 256 * chunks, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.outHost, lane.out, 2L * blocks * chunks, lane.stream), "copy out");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[5], lane.stream), "cuEventRecord");
      }

      long started = 0L;
      long finished = 0L;
      if (timed) {
         // I3: when the card starts the batch and ends it, seen from here (polled; the timed batches only).
         long enqueued = System.nanoTime();
         while (cuEventQuery(lane.events[0]) == CUDA_ERROR_NOT_READY) {
            Thread.onSpinWait();
         }

         started = System.nanoTime();
         while (cuEventQuery(lane.events[5]) == CUDA_ERROR_NOT_READY) {
            Thread.onSpinWait();
         }

         finished = System.nanoTime();
         this.hostTimings[1].add(submitted - entered);
         this.hostTimings[2].add(enqueued - submitted);
         this.hostTimings[3].add(started - enqueued);
         this.hostTimings[4].add(finished - started);
      }

      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize");
      if (timed) {
         this.hostTimings[5].add(System.nanoTime() - finished);
         this.hostTimings[0].increment();
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer ms = stack.mallocFloat(1);
            for (int i = 0; i < 5; i++) {
               GpuSelfTest.check(cuEventElapsedTime(ms, lane.events[i], lane.events[i + 1]), "cuEventElapsedTime");
               // Per chunk of the batch, so the report reads like the single path's.
               this.batchTimings[i + 1].add((long)(ms.get(0) * 1000.0f / chunks));
            }

            this.batchTimings[0].increment();
         }
      }
      this.batches.increment();
      this.batchChunks.add(chunks);
      IntBuffer special = MemoryUtil.memIntBuffer(lane.flagsHost, 256 * chunks);
      IntBuffer unsure = MemoryUtil.memIntBuffer(lane.flagsHost + 4L * 256 * chunks, 256 * chunks);
      for (int c = 0; c < chunks; c++) {
         boolean ok = true;
         for (int t = 0; t < 256 && ok; t++) {
            ok = special.get(256 * c + t) == 0 && unsure.get(256 * c + t) == 0;
         }

         done[c] = ok;
         if (ok) {
            int n = 256 * jobs.get(c).height;
            MemoryUtil.memShortBuffer(lane.outHost + 2L * blocks * c, n).get(0, outs.get(c), 0, n);
         }
      }

      return done;
   }
   /** Every 16th run of a lane timed on the GPU by events: samples, then microseconds of strata, prepass, blocks, copy out. */
   private final LongAdder[] timings = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};

   /** Every 16th batch of a lane timed by events, per chunk: samples, then microseconds of copy in, strata, prepass, blocks, copy out. */
   private final LongAdder[] batchTimings = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
   /** I3: the timed batches seen from the batch thread: samples, then ns of host prep, enqueueing, waiting for the card to start it, on the card, the wake-up after. */
   private final LongAdder[] hostTimings = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};

   /** Per timed batch in ms: host prep, enqueueing, waiting for the card to start it, on the card, waking up after; or null. */
   public double[] batchHostMillis() {
      long samples = this.hostTimings[0].sum();
      if (samples == 0) {
         return null;
      }

      double[] out = new double[5];
      for (int i = 0; i < 5; i++) {
         out[i] = this.hostTimings[i + 1].sum() / 1.0e6 / samples;
      }

      return out;
   }

   /** The timed batches' GPU time per chunk in ms (copy in, strata, prepass, blocks, copy out), or null before the first sample. */
   public double[] batchKernelMillis() {
      long samples = this.batchTimings[0].sum();
      if (samples == 0) {
         return null;
      }

      double[] out = new double[5];
      for (int i = 0; i < 5; i++) {
         out[i] = this.batchTimings[i + 1].sum() / 1000.0 / samples;
      }

      return out;
   }

   /** The timed runs' GPU time per kernel in ms (strata, prepass, blocks, copy out), or null before the first sample. */
   public double[] kernelMillis() {
      long samples = this.timings[0].sum();
      if (samples == 0) {
         return null;
      }

      double[] out = new double[4];
      for (int i = 0; i < 4; i++) {
         out[i] = this.timings[i + 1].sum() / 1000.0 / samples;
      }

      return out;
   }

   /** Compiles both programs into the context (made current on this thread) and uploads the constant arrays. */
   public SurfaceGpu(long context, String strataSource, int strataMax, String productionSource, double[] ruleNoise, int[] rulePermutations,
         double[] inputNoise, int[] inputPermutations, double[] simplexNoise, int[] simplexPermutations, float[] temperature, int[] modifier) {
      this.context = context;
      this.strataMax = Math.max(1, strataMax);
      GpuSelfTest.check(cuCtxSetCurrent(context), "cuCtxSetCurrent");
      this.strataModule = Gpu.loadModule(strataBatchSource(strataSource), "surface_strata.cu");
      this.productionModule = Gpu.loadModule(productionBatchSource(productionSource), "surface.cu");
      this.strataSelectBatch = function(this.strataModule, "strataSelectBatch");
      this.strataLayersBatch = function(this.strataModule, "strataLayersBatch");
      this.strataFinishBatch = function(this.strataModule, "strataFinishBatch");
      this.prepassBatch = function(this.productionModule, "surfacePrepassBatch");
      this.prepassWarpBatch = function(this.productionModule, "surfacePrepassWarpBatch");
      this.blocksBatch = function(this.productionModule, "surfaceBlocksBatch");
      this.strataSelect = function(this.strataModule, "strataSelect");
      this.strataLayers = function(this.strataModule, "strataLayers");
      this.strataFinish = function(this.strataModule, "strataFinish");
      this.prepass = function(this.productionModule, "surfacePrepass");
      this.prepassWarp = function(this.productionModule, "surfacePrepassWarp");
      this.blocks = function(this.productionModule, "surfaceBlocks");
      this.nd = upload(ruleNoise);
      this.np = upload(rulePermutations);
      this.ind = upload(inputNoise);
      this.inp = upload(inputPermutations);
      this.sd = upload(simplexNoise);
      this.sp = upload(simplexPermutations);
      this.temperature = upload(temperature);
      this.modifier = upload(modifier);
   }

   /**
    * Works one chunk's surface out on this thread's lane and waits for it. Fills {@code out} (256 * height shorts, at
    * (y - minY) * 256 + t) with a state index or -1 (no change). Returns false when a column needs the CPU (eroded
    * badlands, frozen oceans or an unknown biome at the top, or strata the GPU is unsure of); then {@code out} is not
    * to be used.
    */
   public boolean run(Job job, short[] out) {
      Lane lane = this.lanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      int n = 256 * job.height;
      lane.ensure(job.height, job.quartCount);
      MemoryUtil.memByteBuffer(lane.kindsHost, n).put(0, job.kinds, 0, n);
      IntBuffer ints = MemoryUtil.memIntBuffer(lane.intsHost, 3 * 256 + 6);
      ints.put(0, job.tops).put(256, job.heights).put(512, job.minBase).put(768, job.grid);
      MemoryUtil.memIntBuffer(lane.quartsHost, Math.max(1, job.quartCount)).put(0, job.quarts, 0, job.quartCount);
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.kindsDevice, lane.kindsHost, n, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.intsDevice, lane.intsHost, 4L * (3 * 256 + 6), lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.quartsDevice, lane.quartsHost, 4L * Math.max(1, job.quartCount), lane.stream), "copy in");
      long tops = lane.intsDevice;
      long heights = lane.intsDevice + 4L * 256;
      long minBase = lane.intsDevice + 4L * 512;
      long grid = lane.intsDevice + 4L * 768;
      boolean timed = lane.runs++ % 16 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         long minX = MemoryUtil.memAddress(stack.ints(job.minX));
         long minZ = MemoryUtil.memAddress(stack.ints(job.minZ));
         PointerBuffer select = stack.mallocPointer(5);
         select.put(0, minX);
         select.put(1, minZ);
         select.put(2, stack.pointers(lane.indexDevice).address());
         select.put(3, stack.pointers(lane.strataCount).address());
         select.put(4, stack.pointers(lane.unsureDevice).address());
         // I3: the strata kernels in blocks of 32 threads (more, smaller blocks start sooner on a busy card; the same work).
         int columnThreads = Config.GPU_SURFACE_SMALL_BLOCKS.get() ? 32 : 256;
         int layerThreads = Config.GPU_SURFACE_SMALL_BLOCKS.get() ? 32 : 128;
         GpuSelfTest.check(cuLaunchKernel(this.strataSelect, 256 / columnThreads, 1, 1, columnThreads, 1, 1, 0, lane.stream, select, null), "cuLaunchKernel strataSelect");
         PointerBuffer layers = stack.mallocPointer(7);
         layers.put(0, minX);
         layers.put(1, minZ);
         layers.put(2, stack.pointers(lane.indexDevice).address());
         layers.put(3, stack.pointers(lane.strataCount).address());
         layers.put(4, stack.pointers(lane.strataDepth).address());
         layers.put(5, stack.pointers(lane.strataState).address());
         layers.put(6, stack.pointers(lane.unsureLayer).address());
         GpuSelfTest.check(cuLaunchKernel(this.strataLayers, (256 * this.strataMax + layerThreads - 1) / layerThreads, 1, 1, layerThreads, 1, 1, 0, lane.stream, layers, null),
            "cuLaunchKernel strataLayers");
         PointerBuffer finish = stack.mallocPointer(5);
         finish.put(0, stack.pointers(heights).address());
         finish.put(1, stack.pointers(lane.strataDepth).address());
         finish.put(2, stack.pointers(lane.strataCount).address());
         finish.put(3, stack.pointers(lane.unsureDevice).address());
         finish.put(4, stack.pointers(lane.unsureLayer).address());
         GpuSelfTest.check(cuLaunchKernel(this.strataFinish, 256 / columnThreads, 1, 1, columnThreads, 1, 1, 0, lane.stream, finish, null), "cuLaunchKernel strataFinish");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         PointerBuffer pre = stack.mallocPointer(17);
         pre.put(0, stack.pointers(lane.kindsDevice).address());
         pre.put(1, MemoryUtil.memAddress(stack.ints(job.minY)));
         pre.put(2, MemoryUtil.memAddress(stack.ints(job.height)));
         pre.put(3, stack.pointers(tops).address());
         pre.put(4, MemoryUtil.memAddress(stack.ints(job.minX)));
         pre.put(5, MemoryUtil.memAddress(stack.ints(job.minZ)));
         pre.put(6, stack.pointers(lane.quartsDevice).address());
         pre.put(7, stack.pointers(grid).address());
         pre.put(8, MemoryUtil.memAddress(stack.longs(job.zoomSeed)));
         pre.put(9, stack.pointers(this.ind).address());
         pre.put(10, stack.pointers(this.inp).address());
         pre.put(11, stack.pointers(lane.above).address());
         pre.put(12, stack.pointers(lane.below).address());
         pre.put(13, stack.pointers(lane.water).address());
         pre.put(14, stack.pointers(lane.columnDepth).address());
         pre.put(15, stack.pointers(lane.columnSecondary).address());
         pre.put(16, stack.pointers(lane.specialDevice).address());
         if (Config.GPU_SURFACE_WARP_PREPASS.get()) {
            GpuSelfTest.check(cuLaunchKernel(this.prepassWarp, 32, 1, 1, 256, 1, 1, 0, lane.stream, pre, null), "cuLaunchKernel surfacePrepassWarp");
         } else {
            GpuSelfTest.check(cuLaunchKernel(this.prepass, 1, 1, 1, 256, 1, 1, 0, lane.stream, pre, null), "cuLaunchKernel surfacePrepass");
         }
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

         PointerBuffer block = stack.mallocPointer(25);
         block.put(0, MemoryUtil.memAddress(stack.ints(job.minY)));
         block.put(1, MemoryUtil.memAddress(stack.ints(job.height)));
         block.put(2, MemoryUtil.memAddress(stack.ints(job.minX)));
         block.put(3, MemoryUtil.memAddress(stack.ints(job.minZ)));
         block.put(4, stack.pointers(heights).address());
         block.put(5, stack.pointers(minBase).address());
         block.put(6, stack.pointers(lane.above).address());
         block.put(7, stack.pointers(lane.below).address());
         block.put(8, stack.pointers(lane.water).address());
         block.put(9, stack.pointers(lane.columnDepth).address());
         block.put(10, stack.pointers(lane.columnSecondary).address());
         block.put(11, stack.pointers(lane.strataDepth).address());
         block.put(12, stack.pointers(lane.strataState).address());
         block.put(13, stack.pointers(lane.strataCount).address());
         block.put(14, MemoryUtil.memAddress(stack.ints(this.strataMax)));
         block.put(15, stack.pointers(lane.quartsDevice).address());
         block.put(16, stack.pointers(grid).address());
         block.put(17, MemoryUtil.memAddress(stack.longs(job.zoomSeed)));
         block.put(18, stack.pointers(this.temperature).address());
         block.put(19, stack.pointers(this.modifier).address());
         block.put(20, stack.pointers(this.sd).address());
         block.put(21, stack.pointers(this.sp).address());
         block.put(22, stack.pointers(this.nd).address());
         block.put(23, stack.pointers(this.np).address());
         block.put(24, stack.pointers(lane.outDevice).address());
         GpuSelfTest.check(cuLaunchKernel(this.blocks, (n + 255) / 256, 1, 1, 256, 1, 1, 0, lane.stream, block, null), "cuLaunchKernel surfaceBlocks");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
         }
      }

      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.flagsHost, lane.specialDevice, 4L * 256, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.flagsHost + 4L * 256, lane.unsureDevice, 4L * 256, lane.stream), "copy out");
      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.outHost, lane.outDevice, 2L * n, lane.stream), "copy out");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[4], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize");
      if (timed) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer ms = stack.mallocFloat(1);
            for (int i = 0; i < 4; i++) {
               GpuSelfTest.check(cuEventElapsedTime(ms, lane.events[i], lane.events[i + 1]), "cuEventElapsedTime");
               this.timings[i + 1].add((long)(ms.get(0) * 1000.0f));
            }

            this.timings[0].increment();
         }
      }
      IntBuffer flags = MemoryUtil.memIntBuffer(lane.flagsHost, 512);
      for (int i = 0; i < 512; i++) {
         if (flags.get(i) != 0) {
            return false;
         }
      }

      ShortBuffer results = MemoryUtil.memShortBuffer(lane.outHost, n);
      results.get(0, out, 0, n);
      return true;
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

   private static long upload(float[] values) {
      ByteBuffer buffer = MemoryUtil.memAlloc(4 * Math.max(1, values.length));
      try {
         buffer.asFloatBuffer().put(0, values);
         long device = device(buffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(device, buffer), "copy in");
         return device;
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }
}
