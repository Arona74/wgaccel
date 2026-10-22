package com.wgaccel.gpu;

import static com.wgaccel.gpu.GpuApi.*;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G2.5: the noise fill's kernels (densityCorners and fillColumns, then fillStates from {@link DensityCodegen#fillSource}) in a
 * shared context, run by the generation threads themselves: each thread gets a lane (its own non-blocking stream,
 * device buffers and page-locked host buffers) on first use, so chunks of different threads overlap on the GPU. Uses
 * no Minecraft classes, so the standalone check can run it.
 */
public final class FillGpu {
   /** One chunk's inputs, as {@code DensityTap} works them out on the CPU. */
   public static final class Job {
      public double[] cells;
      public int chunkX;
      public int chunkZ;
      public int cellMinY;
      public int cellCountY;
      public int cellWidth;
      public int cellHeight;
      public long seedLo;
      public long seedHi;
      public boolean veins;
      /** 0 the disabled aquifer, 1 vanilla's, 2 C2ME's. */
      public int aquifer;
      /** minGridX, minGridY, minGridZ, sizeX, sizeY, sizeZ of the cells in loc and status. */
      public final int[] grid = new int[6];
      public final int[] lava = new int[256];
      public int[] loc = new int[3];
      /** Per cell: level, type code, identity number. */
      public int[] status = new int[3];
      public int cellsUsed;
      public int sea;
      public int seaType;
      /** S7: the Beardifier's pieces and junctions (see DensityCodegen's bf_value); {0, 0} without structures. */
      public int[] beard = {0, 0};
   }

   private final class Lane {
      private final long stream;
      private long cellsDevice;
      private long intsDevice;
      private long cornersDevice;
      private long codesDevice;
      private long columnsDevice;
      private final long[] events = new long[5];
      private int fills;
      private long cellsHost;
      private long intsHost;
      private long codesHost;
      private long cellsBytes;
      private long intsBytes;
      private long cornersBytes;
      private long codesBytes;
      private long paramsDevice;
      private long paramsHost;
      private long paramsBytes;
      private long batchColumnsDevice;
      private long batchColumnsBytes;

      Lane() {
         GpuSelfTest.check(cuCtxSetCurrent(FillGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }
      }

      void ensure(long cells, long ints, long corners, long codes) {
         if (cells > this.cellsBytes) {
            this.freeCells();
            this.cellsBytes = grow(cells);
            this.cellsDevice = device(this.cellsBytes);
            this.cellsHost = host(this.cellsBytes);
         }

         if (ints > this.intsBytes) {
            this.freeInts();
            this.intsBytes = grow(ints);
            this.intsDevice = device(this.intsBytes);
            this.intsHost = host(this.intsBytes);
         }

         if (corners > this.cornersBytes) {
            if (this.cornersDevice != 0L) {
               cuMemFree(this.cornersDevice);
            }

            this.cornersBytes = grow(corners);
            this.cornersDevice = device(this.cornersBytes);
         }

         if (this.columnsDevice == 0L) {
            this.columnsDevice = device(8L * Math.max(1, FillGpu.this.columnCount * 512));
         }

         if (codes > this.codesBytes) {
            if (this.codesDevice != 0L) {
               cuMemFree(this.codesDevice);
               ncuMemFreeHost(this.codesHost);
            }

            this.codesBytes = grow(codes);
            this.codesDevice = device(this.codesBytes);
            this.codesHost = host(this.codesBytes);
         }
      }

      void ensureBatch(long params, long columns) {
         if (params > this.paramsBytes) {
            if (this.paramsDevice != 0L) {
               cuMemFree(this.paramsDevice);
               ncuMemFreeHost(this.paramsHost);
            }

            this.paramsBytes = grow(params);
            this.paramsDevice = device(this.paramsBytes);
            this.paramsHost = host(this.paramsBytes);
         }

         if (columns > this.batchColumnsBytes) {
            if (this.batchColumnsDevice != 0L) {
               cuMemFree(this.batchColumnsDevice);
            }

            this.batchColumnsBytes = grow(columns);
            this.batchColumnsDevice = device(this.batchColumnsBytes);
         }
      }

      private void freeCells() {
         if (this.cellsDevice != 0L) {
            cuMemFree(this.cellsDevice);
            ncuMemFreeHost(this.cellsHost);
         }
      }

      private void freeInts() {
         if (this.intsDevice != 0L) {
            cuMemFree(this.intsDevice);
            ncuMemFreeHost(this.intsHost);
         }
      }
   }

   private final long context;
   private final long module;
   private final long corners;
   private final long states;
   private final long columns;
   private final long cornersBatch;
   private final long columnsBatch;
   private final long statesBatch;
   private final int columnCount;
   /** C2: cache slots of the prefilled corner columns (0: the source has none), their kernels. */
   private final int cornerSlots;
   private long cornerColumns;
   private long cornerColumnsBatch;
   private final long noiseData;
   private final long permutations;
   private final int interpolators;
   private long beardKernel;
   private final ThreadLocal<Lane> lanes = ThreadLocal.withInitial(Lane::new);
   /** Every 16th fill of a lane timed on the GPU by events: samples, then microseconds of corners, columns, states, copy out. */
   private final java.util.concurrent.atomic.LongAdder[] timings = {new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(),
      new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder(), new java.util.concurrent.atomic.LongAdder()};

   /** The timed fills' GPU time per kernel in ms (corners, columns, states, copy out), or null before the first sample. */
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

   /** Longs per chunk in a batch's parameter table (see {@link #batchSource}). */
   private static final int PARAMS = 24;

   /**
    * G4a: the fill source with batch entries. Each of the three kernels (densityCorners, fillColumns, fillStates)
    * becomes a device function with the same body, called by its old entry (unchanged parameters) and by a batch entry
    * that runs it for chunk blockIdx.y of a parameter table (PARAMS longs per chunk: 0 cells, 1 corners, 2 columns, 3
    * grid, 4 loc, 5 status, 6 lava, 7 codes as device addresses; 8 seedLo, 9 seedHi; 10 chunkX, 11 chunkZ, 12
    * cellMinY, 13 cellCountY, 14 cellWidth, 15 cellHeight, 16 corner stride, 17 veins, 18 sea, 19 seaType, 20
    * aquifer). A chunk's blocks are worked out by the same code either way; the bodies index their threads by
    * blockIdx.x and threadIdx.x only and return early past their own chunk's work.
    */
   public static String batchSource(String source) {
      // C2: cornerColumns (prefilled corner column subtrees) only in sources that have it.
      boolean prefill = source.contains("extern \"C\" __global__ void cornerColumns(");
      if (prefill) {
         source = source.replace("extern \"C\" __global__ void cornerColumns(", "__device__ __forceinline__ void cornerColumns_body(") + """
            extern "C" __global__ void cornerColumns(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY,
                                                     int cellCountY, int cellWidth, int cellHeight, int interpolators, int slots, double* corners) {
                cornerColumns_body(cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, interpolators, slots, corners);
            }
            extern "C" __global__ void cornerColumnsBatch(const long long* jobs, const double* nd, const int* np, int interpolators, int slots) {
                const long long* p = jobs + blockIdx.y * %1$d;
                cornerColumns_body((const double*)p[0], nd, np, (int)p[10], (int)p[11], (int)p[12], (int)p[13], (int)p[14], (int)p[15], interpolators,
                                   slots, (double*)p[1]);
            }
            """.formatted(PARAMS);
      }

      String[] names = {"densityCorners", "fillColumns", "fillStates"};
      for (String name : names) {
         String entry = "extern \"C\" __global__ void " + name + "(";
         if (!source.contains(entry)) {
            throw new IllegalStateException("the fill source has no " + name + " kernel");
         }

         source = source.replace(entry, "__device__ __forceinline__ void " + name + "_body(");
      }

      return source + """
         extern "C" __global__ void densityCorners(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                                   int cellWidth, int cellHeight, int interpolators, double* corners) {
             densityCorners_body(cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, interpolators, corners);
         }
         extern "C" __global__ void fillColumns(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                                int cellWidth, int cellHeight, int count, double* columns) {
             fillColumns_body(cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, count, columns);
         }
         extern "C" __global__ void fillStates(const double* cells, const double* nd, const int* np, int chunkX, int chunkZ, int cellMinY, int cellCountY,
                                               int cellWidth, int cellHeight, const double* corners, int cornerStride, long long seedLo, long long seedHi,
                                               int veins, const int* grid, const int* loc, const int* status, const int* lava, int sea, int seaType,
                                               int aquifer, unsigned char* codes, const double* columns, const int* beard, const float* beardKernel) {
             fillStates_body(cells, nd, np, chunkX, chunkZ, cellMinY, cellCountY, cellWidth, cellHeight, corners, cornerStride, seedLo, seedHi, veins, grid,
                             loc, status, lava, sea, seaType, aquifer, codes, columns, beard, beardKernel);
         }
         extern "C" __global__ void densityCornersBatch(const long long* jobs, const double* nd, const int* np, int interpolators) {
             const long long* p = jobs + blockIdx.y * %1$d;
             densityCorners_body((const double*)p[0], nd, np, (int)p[10], (int)p[11], (int)p[12], (int)p[13], (int)p[14], (int)p[15], interpolators,
                                 (double*)p[1]);
         }
         extern "C" __global__ void fillColumnsBatch(const long long* jobs, const double* nd, const int* np, int count) {
             const long long* p = jobs + blockIdx.y * %1$d;
             fillColumns_body((const double*)p[0], nd, np, (int)p[10], (int)p[11], (int)p[12], (int)p[13], (int)p[14], (int)p[15], count, (double*)p[2]);
         }
         extern "C" __global__ void fillStatesBatch(const long long* jobs, const double* nd, const int* np, const float* beardKernel) {
             const long long* p = jobs + blockIdx.y * %1$d;
             fillStates_body((const double*)p[0], nd, np, (int)p[10], (int)p[11], (int)p[12], (int)p[13], (int)p[14], (int)p[15], (const double*)p[1],
                             (int)p[16], p[8], p[9], (int)p[17], (const int*)p[3], (const int*)p[4], (const int*)p[5], (const int*)p[6], (int)p[18],
                             (int)p[19], (int)p[20], (unsigned char*)p[7], (const double*)p[2], (const int*)p[21], beardKernel);
         }
         """.formatted(PARAMS);
   }

   /** Compiles the fill source (with its batch entries) into the context (made current on this thread) and uploads the packed noises. */
   public FillGpu(long context, String source, double[] noise, int[] perm, int interpolators, int columnCount) {
      this.context = context;
      this.columnCount = columnCount;
      this.cornerSlots = cornerSlots(source);
      GpuSelfTest.check(cuCtxSetCurrent(context), "cuCtxSetCurrent");
      this.module = Gpu.loadModule(batchSource(source), "noise_fill.cu");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("densityCorners")), "cuModuleGetFunction densityCorners");
         this.corners = function.get(0);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("fillStates")), "cuModuleGetFunction fillStates");
         this.states = function.get(0);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("fillColumns")), "cuModuleGetFunction fillColumns");
         this.columns = function.get(0);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("densityCornersBatch")), "cuModuleGetFunction densityCornersBatch");
         this.cornersBatch = function.get(0);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("fillColumnsBatch")), "cuModuleGetFunction fillColumnsBatch");
         this.columnsBatch = function.get(0);
         GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("fillStatesBatch")), "cuModuleGetFunction fillStatesBatch");
         this.statesBatch = function.get(0);
         if (this.cornerSlots > 0) {
            GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("cornerColumns")), "cuModuleGetFunction cornerColumns");
            this.cornerColumns = function.get(0);
            GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII("cornerColumnsBatch")), "cuModuleGetFunction cornerColumnsBatch");
            this.cornerColumnsBatch = function.get(0);
         }
      }

      DoubleBuffer noiseBuffer = MemoryUtil.memAllocDouble(Math.max(1, noise.length)).put(0, noise);
      IntBuffer permBuffer = MemoryUtil.memAllocInt(Math.max(1, perm.length)).put(0, perm);
      try {
         this.noiseData = device(8L * noiseBuffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(this.noiseData, noiseBuffer), "copy in");
         this.permutations = device(4L * permBuffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(this.permutations, permBuffer), "copy in");
      } finally {
         MemoryUtil.memFree(noiseBuffer);
         MemoryUtil.memFree(permBuffer);
      }

      this.interpolators = interpolators;
      float[] none = new float[1];
      this.setBeardKernel(none);
   }

   /**
    * C2: the cache slots of a source whose corner kernel reads prefilled corner columns (the highest dc_cc / dc_cm
    * number + 1), or 0. The prefilled values sit in the corners buffer after the interpolators' corners: slots * 2 per
    * corner column.
    */
   static int cornerSlots(String source) {
      if (!source.contains("c.ccolumns = corners + (long long)interpolators * per;")) {
         return 0;
      }

      int slots = 0;
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("double dc_c[cm](\\d+)\\(").matcher(source);
      while (m.find()) {
         slots = Math.max(slots, Integer.parseInt(m.group(1)) + 1);
      }

      return slots;
   }

   /** C2: the prefilled corner columns' doubles for a chunk of cells this wide. */
   private long cornerColumnDoubles(int cellWidth) {
      int nx = 16 / cellWidth + 1;
      return (long)this.cornerSlots * 2 * nx * nx;
   }

   /** S7: Minecraft's Beardifier.BEARD_KERNEL (13,824 floats), uploaded once; a stand-in until the game gives it. */
   public void setBeardKernel(float[] kernel) {
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      java.nio.FloatBuffer buffer = MemoryUtil.memAllocFloat(Math.max(1, kernel.length)).put(0, kernel);
      try {
         long device = device(4L * buffer.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(device, buffer), "copy in");
         this.beardKernel = device;
      } finally {
         MemoryUtil.memFree(buffer);
      }
   }

   private static long grow(long bytes) {
      return Math.max(4096L, bytes + bytes / 4);
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

   /**
    * Works one chunk out on this thread's lane and waits for it: corners, then every block's code byte (state code in
    * bits 0 to 3, the fluid update flag in bit 4), at (ly * 16 + lz) * 16 + lx, copied into codes.
    */
   public void fill(Job job, byte[] codes) {
      Lane lane = this.lanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      int nx = 16 / job.cellWidth + 1;
      int per = nx * nx * (job.cellCountY + 1);
      int blocks = 256 * job.cellCountY * job.cellHeight;
      int cellsCount = Math.max(1, job.cells.length);
      int locOffset = 8 + 256;
      int statusOffset = locOffset + 3 * job.cellsUsed;
      int beardOffset = statusOffset + 3 * job.cellsUsed;
      int intsCount = beardOffset + job.beard.length;
      lane.ensure(8L * cellsCount, 4L * intsCount, 8L * Math.max(1, this.interpolators * per + this.cornerColumnDoubles(job.cellWidth)), blocks);
      MemoryUtil.memDoubleBuffer(lane.cellsHost, cellsCount).put(0, job.cells, 0, job.cells.length);
      IntBuffer ints = MemoryUtil.memIntBuffer(lane.intsHost, intsCount);
      ints.put(0, job.grid);
      ints.put(8, job.lava);
      ints.put(locOffset, job.loc, 0, 3 * job.cellsUsed);
      ints.put(statusOffset, job.status, 0, 3 * job.cellsUsed);
      ints.put(beardOffset, job.beard);
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.cellsDevice, lane.cellsHost, 8L * cellsCount, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.intsDevice, lane.intsHost, 4L * intsCount, lane.stream), "copy in");
      boolean timed = lane.fills++ % 16 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer params = stack.mallocPointer(11);
         params.put(0, stack.pointers(lane.cellsDevice).address());
         params.put(1, stack.pointers(this.noiseData).address());
         params.put(2, stack.pointers(this.permutations).address());
         params.put(3, MemoryUtil.memAddress(stack.ints(job.chunkX)));
         params.put(4, MemoryUtil.memAddress(stack.ints(job.chunkZ)));
         params.put(5, MemoryUtil.memAddress(stack.ints(job.cellMinY)));
         params.put(6, MemoryUtil.memAddress(stack.ints(job.cellCountY)));
         params.put(7, MemoryUtil.memAddress(stack.ints(job.cellWidth)));
         params.put(8, MemoryUtil.memAddress(stack.ints(job.cellHeight)));
         params.put(9, MemoryUtil.memAddress(stack.ints(this.interpolators)));
         params.put(10, stack.pointers(lane.cornersDevice).address());
         if (this.cornerSlots > 0 && this.interpolators > 0) {
            PointerBuffer prefill = stack.mallocPointer(12);
            for (int i = 0; i < 10; i++) {
               prefill.put(i, params.get(i));
            }

            prefill.put(10, MemoryUtil.memAddress(stack.ints(this.cornerSlots)));
            prefill.put(11, params.get(10));
            long threads = this.cornerColumnDoubles(job.cellWidth);
            GpuSelfTest.check(cuLaunchKernel(this.cornerColumns, (int)((threads + 255) / 256), 1, 1, 256, 1, 1, 0, lane.stream, prefill, null),
               "cuLaunchKernel cornerColumns");
         }

         if (this.interpolators > 0) {
            GpuSelfTest.check(cuLaunchKernel(this.corners, (this.interpolators * per + 255) / 256, 1, 1, 256, 1, 1, 0, lane.stream, params, null),
               "cuLaunchKernel densityCorners");
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         if (this.columnCount > 0) {
            PointerBuffer column = stack.mallocPointer(11);
            for (int i = 0; i < 9; i++) {
               column.put(i, params.get(i));
            }

            column.put(9, MemoryUtil.memAddress(stack.ints(this.columnCount)));
            column.put(10, stack.pointers(lane.columnsDevice).address());
            GpuSelfTest.check(cuLaunchKernel(this.columns, (this.columnCount * 512 + 255) / 256, 1, 1, 256, 1, 1, 0, lane.stream, column, null),
               "cuLaunchKernel fillColumns");
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

         PointerBuffer state = stack.mallocPointer(25);
         for (int i = 0; i < 9; i++) {
            state.put(i, params.get(i));
         }

         state.put(9, stack.pointers(lane.cornersDevice).address());
         state.put(10, MemoryUtil.memAddress(stack.ints(per)));
         state.put(11, MemoryUtil.memAddress(stack.longs(job.seedLo)));
         state.put(12, MemoryUtil.memAddress(stack.longs(job.seedHi)));
         state.put(13, MemoryUtil.memAddress(stack.ints(job.veins ? 1 : 0)));
         state.put(14, stack.pointers(lane.intsDevice).address());
         state.put(15, stack.pointers(lane.intsDevice + 4L * locOffset).address());
         state.put(16, stack.pointers(lane.intsDevice + 4L * statusOffset).address());
         state.put(17, stack.pointers(lane.intsDevice + 4L * 8).address());
         state.put(18, MemoryUtil.memAddress(stack.ints(job.sea)));
         state.put(19, MemoryUtil.memAddress(stack.ints(job.seaType)));
         state.put(20, MemoryUtil.memAddress(stack.ints(job.aquifer)));
         state.put(21, stack.pointers(lane.codesDevice).address());
         state.put(22, stack.pointers(lane.columnsDevice).address());
         state.put(23, stack.pointers(lane.intsDevice + 4L * beardOffset).address());
         state.put(24, stack.pointers(this.beardKernel).address());
         GpuSelfTest.check(cuLaunchKernel(this.states, (blocks + 255) / 256, 1, 1, 256, 1, 1, 0, lane.stream, state, null), "cuLaunchKernel fillStates");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
         }
      }

      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.codesHost, lane.codesDevice, blocks, lane.stream), "copy out");
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
      ByteBuffer out = MemoryUtil.memByteBuffer(lane.codesHost, blocks);
      out.get(0, codes, 0, blocks);
   }

   /**
    * G4a: several chunks in one launch per kernel on this thread's lane, waiting for all: each chunk's codes as
    * {@link #fill} gives them. The inputs of all chunks go over in one copy, the codes come back in one.
    */
   public void fillBatch(java.util.List<Job> jobs, java.util.List<byte[]> codes) {
      int n = jobs.size();
      if (n == 0) {
         return;
      }

      Lane lane = this.lanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      long[] cellsAt = new long[n];
      long[] intsAt = new long[n];
      long[] cornersAt = new long[n];
      long[] codesAt = new long[n];
      int[] pers = new int[n];
      int[] blockCounts = new int[n];
      long cellsTotal = 0;
      long intsTotal = 0;
      long cornersTotal = 0;
      long codesTotal = 0;
      int maxCorners = 1;
      int maxBlocks = 1;
      for (int i = 0; i < n; i++) {
         Job job = jobs.get(i);
         int nx = 16 / job.cellWidth + 1;
         pers[i] = nx * nx * (job.cellCountY + 1);
         blockCounts[i] = 256 * job.cellCountY * job.cellHeight;
         cellsAt[i] = cellsTotal;
         cellsTotal += Math.max(1, job.cells.length);
         intsAt[i] = intsTotal;
         intsTotal += 8 + 256 + 6L * job.cellsUsed + job.beard.length;
         cornersAt[i] = cornersTotal;
         cornersTotal += Math.max(1L, (long)this.interpolators * pers[i] + this.cornerColumnDoubles(job.cellWidth));
         codesAt[i] = codesTotal;
         codesTotal += (blockCounts[i] + 15) & ~15;
         maxCorners = Math.max(maxCorners, this.interpolators * pers[i]);
         maxBlocks = Math.max(maxBlocks, blockCounts[i]);
      }

      long columnsPer = Math.max(1L, this.columnCount * 512L);
      lane.ensure(8L * cellsTotal, 4L * intsTotal, 8L * cornersTotal, codesTotal);
      lane.ensureBatch(8L * PARAMS * n, 8L * columnsPer * n);
      java.nio.DoubleBuffer cellsHost = MemoryUtil.memDoubleBuffer(lane.cellsHost, (int)cellsTotal);
      IntBuffer intsHost = MemoryUtil.memIntBuffer(lane.intsHost, (int)intsTotal);
      java.nio.LongBuffer params = MemoryUtil.memLongBuffer(lane.paramsHost, PARAMS * n);
      for (int i = 0; i < n; i++) {
         Job job = jobs.get(i);
         cellsHost.put((int)cellsAt[i], job.cells, 0, job.cells.length);
         int ints = (int)intsAt[i];
         intsHost.put(ints, job.grid);
         intsHost.put(ints + 8, job.lava);
         intsHost.put(ints + 8 + 256, job.loc, 0, 3 * job.cellsUsed);
         intsHost.put(ints + 8 + 256 + 3 * job.cellsUsed, job.status, 0, 3 * job.cellsUsed);
         intsHost.put(ints + 8 + 256 + 6 * job.cellsUsed, job.beard);
         int p = i * PARAMS;
         long intsDevice = lane.intsDevice + 4L * ints;
         params.put(p, lane.cellsDevice + 8L * cellsAt[i]);
         params.put(p + 1, lane.cornersDevice + 8L * cornersAt[i]);
         params.put(p + 2, lane.batchColumnsDevice + 8L * columnsPer * i);
         params.put(p + 3, intsDevice);
         params.put(p + 4, intsDevice + 4L * (8 + 256));
         params.put(p + 5, intsDevice + 4L * (8 + 256 + 3 * job.cellsUsed));
         params.put(p + 6, intsDevice + 4L * 8);
         params.put(p + 7, lane.codesDevice + codesAt[i]);
         params.put(p + 8, job.seedLo);
         params.put(p + 9, job.seedHi);
         params.put(p + 10, job.chunkX);
         params.put(p + 11, job.chunkZ);
         params.put(p + 12, job.cellMinY);
         params.put(p + 13, job.cellCountY);
         params.put(p + 14, job.cellWidth);
         params.put(p + 15, job.cellHeight);
         params.put(p + 16, pers[i]);
         params.put(p + 17, job.veins ? 1 : 0);
         params.put(p + 18, job.sea);
         params.put(p + 19, job.seaType);
         params.put(p + 20, job.aquifer);
         params.put(p + 21, intsDevice + 4L * (8 + 256 + 6 * job.cellsUsed));
      }

      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.cellsDevice, lane.cellsHost, 8L * cellsTotal, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.intsDevice, lane.intsHost, 4L * intsTotal, lane.stream), "copy in");
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.paramsDevice, lane.paramsHost, 8L * PARAMS * n, lane.stream), "copy in");
      boolean timed = lane.fills++ % 16 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         long table = stack.pointers(lane.paramsDevice).address();
         long nd = stack.pointers(this.noiseData).address();
         long np = stack.pointers(this.permutations).address();
         if (this.cornerSlots > 0 && this.interpolators > 0) {
            long most = 0;
            for (Job job : jobs) {
               most = Math.max(most, this.cornerColumnDoubles(job.cellWidth));
            }

            PointerBuffer prefill = stack.mallocPointer(5);
            prefill.put(0, table).put(1, nd).put(2, np).put(3, MemoryUtil.memAddress(stack.ints(this.interpolators)))
               .put(4, MemoryUtil.memAddress(stack.ints(this.cornerSlots)));
            GpuSelfTest.check(cuLaunchKernel(this.cornerColumnsBatch, (int)((most + 255) / 256), n, 1, 256, 1, 1, 0, lane.stream, prefill, null),
               "cuLaunchKernel cornerColumnsBatch");
         }

         if (this.interpolators > 0) {
            PointerBuffer corner = stack.mallocPointer(4);
            corner.put(0, table).put(1, nd).put(2, np).put(3, MemoryUtil.memAddress(stack.ints(this.interpolators)));
            GpuSelfTest.check(cuLaunchKernel(this.cornersBatch, (maxCorners + 255) / 256, n, 1, 256, 1, 1, 0, lane.stream, corner, null),
               "cuLaunchKernel densityCornersBatch");
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         if (this.columnCount > 0) {
            PointerBuffer column = stack.mallocPointer(4);
            column.put(0, table).put(1, nd).put(2, np).put(3, MemoryUtil.memAddress(stack.ints(this.columnCount)));
            GpuSelfTest.check(cuLaunchKernel(this.columnsBatch, (this.columnCount * 512 + 255) / 256, n, 1, 256, 1, 1, 0, lane.stream, column, null),
               "cuLaunchKernel fillColumnsBatch");
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

         PointerBuffer state = stack.mallocPointer(4);
         state.put(0, table).put(1, nd).put(2, np).put(3, stack.pointers(this.beardKernel).address());
         GpuSelfTest.check(cuLaunchKernel(this.statesBatch, (maxBlocks + 255) / 256, n, 1, 256, 1, 1, 0, lane.stream, state, null),
            "cuLaunchKernel fillStatesBatch");
         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
         }
      }

      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.codesHost, lane.codesDevice, codesTotal, lane.stream), "copy out");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[4], lane.stream), "cuEventRecord");
      }

      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize");
      if (timed) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer ms = stack.mallocFloat(1);
            for (int i = 0; i < 4; i++) {
               GpuSelfTest.check(cuEventElapsedTime(ms, lane.events[i], lane.events[i + 1]), "cuEventElapsedTime");
               // Per chunk, so the report reads like the single path's.
               this.timings[i + 1].add((long)(ms.get(0) * 1000.0f / n));
            }

            this.timings[0].increment();
         }
      }

      ByteBuffer out = MemoryUtil.memByteBuffer(lane.codesHost, (int)codesTotal);
      for (int i = 0; i < n; i++) {
         out.get((int)codesAt[i], codes.get(i), 0, blockCounts[i]);
      }
   }
}
