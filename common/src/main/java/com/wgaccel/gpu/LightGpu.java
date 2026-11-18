package com.wgaccel.gpu;

import static com.wgaccel.gpu.GpuApi.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Pure-GPU executor for 3x3 chunk lighting ({@link LightKernels}: {@code lightInit}, {@code lightRelax}, {@code lightPack}).
 * Uses per-thread lanes (non-blocking stream + grow-only device & page-locked host buffers) inside the shared
 * {@link GpuService#context()}. Holds no Minecraft classes.
 */
public final class LightGpu implements AutoCloseable {
   private static final int MAX_RELAX_PAIRS = 8;

   public static final class Job {
      public int activeSecCount;
      public boolean hasSkyLight;
      public int airStateId;
      public int opaqueStateId;
      /** 9 * activeSecCount entries: < 0 means uniform (-1 - stateId), >= 0 means slot in denseStates. */
      public int[] sectionMode = new int[0];
      public int denseCount;
      /** denseCount * 4096 canonical light state IDs in (y<<8)|(z<<4)|x order. */
      public short[] denseStates = new short[0];
   }

   public static final class Result {
      public int secCount;
      /** bit 0: hasBlockLight, bit 1: hasSkyNonZero, bit 2: hasSkyNon15. */
      public int[] flags = new int[0];
      public byte[] blockData = new byte[0];
      public byte[] skyData = new byte[0];

      public void ensure(int secs) {
         if (this.flags.length < secs) {
            this.flags = new int[secs];
         }
         int bytes = secs * 2048;
         if (this.blockData.length < bytes) {
            this.blockData = new byte[bytes];
            this.skyData = new byte[bytes];
         }
         this.secCount = secs;
      }
   }

   private final class Lane {
      private final long stream;
      private int secCapacity;
      private int denseCapacity;
      private long sectionModeDevice;
      private long sectionModeHost;
      private long denseDevice;
      private long denseHost;
      private long devStates;
      private long devLightA;
      private long devLightB;
      private long changedDevice;
      private long changedHost;
      private long zeroHost;
      private long outDevice;
      private long outHost;
      private final long[] events = new long[4];
      private int runs;

      Lane() {
         GpuSelfTest.check(cuCtxSetCurrent(LightGpu.this.context), "cuCtxSetCurrent");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            this.stream = GpuStreams.create();
            for (int i = 0; i < this.events.length; i++) {
               GpuSelfTest.check(cuEventCreate(pointer, CU_EVENT_DEFAULT), "cuEventCreate");
               this.events[i] = pointer.get(0);
            }
         }
         this.changedDevice = device(4L);
         this.changedHost = host(4L);
         this.zeroHost = host(4L);
         MemoryUtil.memPutInt(this.zeroHost, 0);
         LightGpu.this.allLanes.add(this);
      }

      void ensure(int secs, int denseCount) {
         if (secs > this.secCapacity) {
            if (this.sectionModeDevice != 0L) {
               cuMemFree(this.sectionModeDevice);
               ncuMemFreeHost(this.sectionModeHost);
               cuMemFree(this.devStates);
               cuMemFree(this.devLightA);
               cuMemFree(this.devLightB);
               cuMemFree(this.outDevice);
               ncuMemFreeHost(this.outHost);
            }
            this.secCapacity = Math.max(16, secs + 8);
            long modeBytes = 4L * 9L * this.secCapacity;
            long voxels = 48L * 48L * 16L * this.secCapacity;
            long outBytes = 4100L * this.secCapacity; // [2048 block + 2048 sky + 4 flags] per section
            this.sectionModeDevice = device(modeBytes);
            this.sectionModeHost = host(modeBytes);
            this.devStates = device(2L * voxels);
            this.devLightA = device(voxels);
            this.devLightB = device(voxels);
            this.outDevice = device(outBytes);
            this.outHost = host(outBytes);
         }

         int neededDense = Math.max(1, denseCount);
         if (neededDense > this.denseCapacity) {
            if (this.denseDevice != 0L) {
               cuMemFree(this.denseDevice);
               ncuMemFreeHost(this.denseHost);
            }
            this.denseCapacity = Math.max(16, neededDense + neededDense / 4);
            long denseBytes = 8192L * this.denseCapacity;
            this.denseDevice = device(denseBytes);
            this.denseHost = host(denseBytes);
         }
      }

      private synchronized void free() {
         if (GpuBackend.OPENCL && this.stream != 0L) {
            cuStreamSynchronize(this.stream);
         }
         long[] devices = {this.sectionModeDevice, this.denseDevice, this.devStates, this.devLightA, this.devLightB,
            this.changedDevice, this.outDevice};
         for (long d : devices) {
            if (d != 0L) {
               cuMemFree(d);
            }
         }
         this.sectionModeDevice = this.denseDevice = this.devStates = this.devLightA = this.devLightB = 0L;
         this.changedDevice = this.outDevice = 0L;

         long[] hosts = {this.sectionModeHost, this.denseHost, this.changedHost, this.zeroHost, this.outHost};
         for (long h : hosts) {
            if (h != 0L) {
               ncuMemFreeHost(h);
            }
         }
         this.sectionModeHost = this.denseHost = this.changedHost = this.zeroHost = this.outHost = 0L;
         this.secCapacity = 0;
         this.denseCapacity = 0;

         for (int i = 0; i < this.events.length; i++) {
            if (this.events[i] != 0L) {
               cuEventDestroy(this.events[i]);
               this.events[i] = 0L;
            }
         }
         GpuStreams.destroy(this.stream);
      }
   }

   private final long context;
   private final long module;
   private final long initFn;
   private final long relaxFn;
   private final long packFn;
   private final long stateMetaDevice;
   private final long stateShapesDevice;
   private final long shapeMatrixDevice;
   private final int shapeWords;
   private final ThreadLocal<Lane> lanes = ThreadLocal.withInitial(Lane::new);
   private final ConcurrentLinkedQueue<Lane> allLanes = new ConcurrentLinkedQueue<>();
   private final LongAdder[] timings = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
   private volatile boolean closed;

   public LightGpu(long context, int[] stateMeta, short[] stateShapes, int[] shapeMatrix, int shapeWords) {
      this.context = context;
      this.shapeWords = shapeWords;
      GpuSelfTest.check(cuCtxSetCurrent(context), "cuCtxSetCurrent");
      this.module = Gpu.loadModule(LightKernels.SOURCE, "light.cu");
      this.initFn = function(this.module, "lightInit");
      this.relaxFn = function(this.module, "lightRelax");
      this.packFn = function(this.module, "lightPack");
      this.stateMetaDevice = uploadInts(stateMeta);
      this.stateShapesDevice = uploadShorts(stateShapes);
      this.shapeMatrixDevice = uploadInts(shapeMatrix);
   }

   public void run(Job job, Result out) {
      if (this.closed || job.activeSecCount <= 0) {
         out.secCount = 0;
         return;
      }

      int secs = job.activeSecCount;
      Lane lane = this.lanes.get();
      GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      lane.ensure(secs, job.denseCount);

      int modeCount = 9 * secs;
      MemoryUtil.memIntBuffer(lane.sectionModeHost, modeCount).put(0, job.sectionMode, 0, modeCount);
      GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.sectionModeDevice, lane.sectionModeHost, 4L * modeCount, lane.stream), "copy sectionMode");
      if (job.denseCount > 0) {
         int denseShorts = job.denseCount * 4096;
         MemoryUtil.memShortBuffer(lane.denseHost, denseShorts).put(0, job.denseStates, 0, denseShorts);
         GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.denseDevice, lane.denseHost, 2L * denseShorts, lane.stream), "copy denseStates");
      }

      boolean timed = lane.runs++ % 16 == 0 && GpuBackend.TIMING;
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[0], lane.stream), "cuEventRecord");
      }

      int skyFlag = job.hasSkyLight ? 1 : 0;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer initArgs = stack.mallocPointer(11);
         initArgs.put(0, stack.pointers(lane.sectionModeDevice).address());
         initArgs.put(1, stack.pointers(lane.denseDevice).address());
         initArgs.put(2, stack.pointers(this.stateMetaDevice).address());
         initArgs.put(3, stack.pointers(this.stateShapesDevice).address());
         initArgs.put(4, stack.pointers(this.shapeMatrixDevice).address());
         initArgs.put(5, MemoryUtil.memAddress(stack.ints(this.shapeWords)));
         initArgs.put(6, MemoryUtil.memAddress(stack.ints(secs)));
         initArgs.put(7, MemoryUtil.memAddress(stack.ints(skyFlag)));
         initArgs.put(8, MemoryUtil.memAddress(stack.ints(job.airStateId)));
         initArgs.put(9, stack.pointers(lane.devStates).address());
         initArgs.put(10, stack.pointers(lane.devLightA).address());
         GpuSelfTest.check(cuLaunchKernel(this.initFn, 3, 3, 1, 16, 16, 1, 0, lane.stream, initArgs, null), "cuLaunchKernel lightInit");

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[1], lane.stream), "cuEventRecord");
         }

         PointerBuffer relaxAB = stack.mallocPointer(12);
         relaxAB.put(0, stack.pointers(lane.devStates).address());
         relaxAB.put(1, stack.pointers(lane.devLightA).address());
         relaxAB.put(2, stack.pointers(lane.devLightB).address());
         relaxAB.put(3, stack.pointers(this.stateMetaDevice).address());
         relaxAB.put(4, stack.pointers(this.stateShapesDevice).address());
         relaxAB.put(5, stack.pointers(this.shapeMatrixDevice).address());
         relaxAB.put(6, MemoryUtil.memAddress(stack.ints(this.shapeWords)));
         relaxAB.put(7, MemoryUtil.memAddress(stack.ints(secs)));
         relaxAB.put(8, MemoryUtil.memAddress(stack.ints(skyFlag)));
         relaxAB.put(9, MemoryUtil.memAddress(stack.ints(job.airStateId)));
         relaxAB.put(10, MemoryUtil.memAddress(stack.ints(job.opaqueStateId)));
         relaxAB.put(11, stack.pointers(lane.changedDevice).address());

         PointerBuffer relaxBA = stack.mallocPointer(12);
         for (int a = 0; a < 12; a++) {
            relaxBA.put(a, relaxAB.get(a));
         }
         relaxBA.put(1, stack.pointers(lane.devLightB).address());
         relaxBA.put(2, stack.pointers(lane.devLightA).address());

         for (int pair = 0; pair < MAX_RELAX_PAIRS; pair++) {
            GpuSelfTest.check(cuLaunchKernel(this.relaxFn, 3, secs, 3, 16, 1, 16, 0, lane.stream, relaxAB, null), "cuLaunchKernel lightRelax A->B");
            // Only track changes on the second half of each ping-pong pair so the final state rests in devLightA
            GpuSelfTest.check(ncuMemcpyHtoDAsync(lane.changedDevice, lane.zeroHost, 4L, lane.stream), "zero changed");
            GpuSelfTest.check(cuLaunchKernel(this.relaxFn, 3, secs, 3, 16, 1, 16, 0, lane.stream, relaxBA, null), "cuLaunchKernel lightRelax B->A");
            GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.changedHost, lane.changedDevice, 4L, lane.stream), "read changed");
            GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize relax");
            if (MemoryUtil.memGetInt(lane.changedHost) == 0) {
               break;
            }
         }

         if (timed) {
            GpuSelfTest.check(cuEventRecord(lane.events[2], lane.stream), "cuEventRecord");
         }

         long blockOutBytes = 2048L * secs;
         long skyOutDev = lane.outDevice + blockOutBytes;
         long flagsOutDev = skyOutDev + blockOutBytes;
         PointerBuffer packArgs = stack.mallocPointer(5);
         packArgs.put(0, stack.pointers(lane.devLightA).address());
         packArgs.put(1, MemoryUtil.memAddress(stack.ints(secs)));
         packArgs.put(2, stack.pointers(lane.outDevice).address());
         packArgs.put(3, stack.pointers(skyOutDev).address());
         packArgs.put(4, stack.pointers(flagsOutDev).address());
         GpuSelfTest.check(cuLaunchKernel(this.packFn, secs, 1, 1, 256, 1, 1, 0, lane.stream, packArgs, null), "cuLaunchKernel lightPack");
      }

      long totalOutBytes = 4100L * secs;
      GpuSelfTest.check(ncuMemcpyDtoHAsync(lane.outHost, lane.outDevice, totalOutBytes, lane.stream), "copy light out");
      if (timed) {
         GpuSelfTest.check(cuEventRecord(lane.events[3], lane.stream), "cuEventRecord");
      }
      GpuSelfTest.check(cuStreamSynchronize(lane.stream), "cuStreamSynchronize pack");

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

      out.ensure(secs);
      int blockBytes = 2048 * secs;
      MemoryUtil.memByteBuffer(lane.outHost, blockBytes).get(0, out.blockData, 0, blockBytes);
      MemoryUtil.memByteBuffer(lane.outHost + blockBytes, blockBytes).get(0, out.skyData, 0, blockBytes);
      MemoryUtil.memIntBuffer(lane.outHost + 2L * blockBytes, secs).get(0, out.flags, 0, secs);
   }

   public String timings() {
      double n = Math.max(1, this.timings[0].sum());
      return String.format(java.util.Locale.ROOT, "on GPU per chunk (sampled): init %.3f ms, relax %.3f, pack+out %.3f",
         this.timings[1].sum() / 1000.0 / n, this.timings[2].sum() / 1000.0 / n, this.timings[3].sum() / 1000.0 / n);
   }

   @Override
   public void close() {
      this.closed = true;
      try {
         GpuSelfTest.check(cuCtxSetCurrent(this.context), "cuCtxSetCurrent");
      } catch (RuntimeException ignored) {
         return;
      }
      for (Lane lane : this.allLanes) {
         lane.free();
      }
      this.allLanes.clear();
      if (this.stateMetaDevice != 0L) cuMemFree(this.stateMetaDevice);
      if (this.stateShapesDevice != 0L) cuMemFree(this.stateShapesDevice);
      if (this.shapeMatrixDevice != 0L) cuMemFree(this.shapeMatrixDevice);
      if (this.module != 0L && !GpuBackend.OPENCL && !GpuBackend.HIP) {
         com.wgaccel.gpu.cuda.CU.cuModuleUnload(this.module);
      }
   }

   private static long function(long module, String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer fn = stack.mallocPointer(1);
         GpuSelfTest.check(cuModuleGetFunction(fn, module, stack.ASCII(name)), "cuModuleGetFunction " + name);
         return fn.get(0);
      }
   }

   private static long device(long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer ptr = stack.mallocPointer(1);
         GpuSelfTest.check(cuMemAlloc(ptr, Math.max(4L, bytes)), "cuMemAlloc");
         return ptr.get(0);
      }
   }

   private static long host(long bytes) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer ptr = stack.mallocPointer(1);
         GpuSelfTest.check(cuMemAllocHost(ptr, Math.max(4L, bytes)), "cuMemAllocHost");
         return ptr.get(0);
      }
   }

   private static long uploadInts(int[] values) {
      ByteBuffer buf = MemoryUtil.memAlloc(4 * Math.max(1, values.length));
      try {
         if (values.length > 0) {
            buf.asIntBuffer().put(0, values);
         }
         long d = device(buf.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(d, buf), "copy ints");
         return d;
      } finally {
         MemoryUtil.memFree(buf);
      }
   }

   private static long uploadShorts(short[] values) {
      ByteBuffer buf = MemoryUtil.memAlloc(2 * Math.max(1, values.length));
      try {
         if (values.length > 0) {
            buf.asShortBuffer().put(0, values);
         }
         long d = device(buf.remaining());
         GpuSelfTest.check(cuMemcpyHtoD(d, buf), "copy shorts");
         return d;
      } finally {
         MemoryUtil.memFree(buf);
      }
   }
}
