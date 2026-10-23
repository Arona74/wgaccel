package com.wgaccel.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G6c: FreeTerraForged 1.0.0013's erosion filter on many chunk grids at once. One grid at a time (G6b, the
 * 23 Oct 2026 run) waited 4.0 ms on the GPU against FreeTerraForged's 1.9 ms on the CPU: each grid was 7 uploads, 12
 * launches and 4 downloads, every one waited for, and ~700 grids a second queued on the GPU threads (1:02 against 0:56).
 * Here a grid joins a queue and a GPU thread takes every grid waiting (up to {@link #MAX}) as one batch: one upload of
 * all their inputs (and corner blocks), the 21 Oct filter's passes over all of them (ErosionKernels' evb kernels), one
 * download of all their results. No grid waits for others to arrive: the batches are what queued while the GPU threads
 * were busy. The grids that need more (an unsure cell, or a checked grid) get the envelope, slopes and gate too.
 */
final class ErosionGridBatch {
   static final int MAX = 64;
   private static final long WAIT_SECONDS = 30L;
   private static final ConcurrentLinkedQueue<ErosionFilterGpu.Job> PENDING = new ConcurrentLinkedQueue<>();
   /** The GPU thread's buffers (only that thread touches them). */
   private static final ThreadLocal<Device> DEVICE = new ThreadLocal<>();
   private static final LongAdder BATCHES = new LongAdder();
   private static final LongAdder GRIDS = new LongAdder();
   private static final LongAdder GPU_NANOS = new LongAdder();

   private ErosionGridBatch() {
   }

   /** Runs the job's grid on the GPU with whatever other grids wait; returns once its results are in the job. */
   static void run(ErosionFilterGpu.Job job) throws Exception {
      PENDING.add(job);
      GpuService.post(ErosionGridBatch::drain);
      try {
         // Never forever (0.17.3: a world once did not finish closing; a stuck GPU must not hold a chunk up): past this
         // the grid is FreeTerraForged's own and the GPU erosion turns off.
         job.done.get(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException late) {
         throw new IllegalStateException("a batch did not finish in " + WAIT_SECONDS + " s");
      } catch (ExecutionException failure) {
         throw failure.getCause() instanceof Exception exception ? exception : new IllegalStateException(failure.getCause());
      }
   }

   static String summary() {
      long batches = Math.max(1, BATCHES.sum());
      return String.format(Locale.ROOT, "; in %,d batches (%.1f grids each, %.2f ms each on the GPU thread)", BATCHES.sum(), (double)GRIDS.sum() / batches,
         GPU_NANOS.sum() / 1.0e6 / batches);
   }

   /** On a GPU thread: every grid waiting with the same filter and size as the first, as one batch. */
   private static void drain(Gpu gpu) {
      ErosionFilterGpu.Job first = PENDING.poll();
      if (first == null) {
         return;
      }

      List<ErosionFilterGpu.Job> batch = new ArrayList<>();
      List<ErosionFilterGpu.Job> other = new ArrayList<>();
      batch.add(first);
      while (batch.size() < MAX) {
         ErosionFilterGpu.Job next = PENDING.poll();
         if (next == null) {
            break;
         }

         (next.params == first.params && next.n == first.n ? batch : other).add(next);
      }

      // Another filter or size (another world): back in the queue, for the threads posted with them.
      PENDING.addAll(other);
      try {
         long start = System.nanoTime();
         run(gpu, batch);
         GPU_NANOS.add(System.nanoTime() - start);
         BATCHES.increment();
         GRIDS.add(batch.size());
         for (ErosionFilterGpu.Job job : batch) {
            job.done.complete(null);
         }
      } catch (Throwable failure) {
         for (ErosionFilterGpu.Job job : batch) {
            job.done.completeExceptionally(failure);
         }
      }
   }

   private static void run(Gpu gpu, List<ErosionFilterGpu.Job> batch) {
      ErosionFilterGpu.Job first = batch.get(0);
      int grids = batch.size();
      int n = first.n;
      int nn = n * n;
      int count = grids * nn;
      Device d = DEVICE.get();
      if (d == null || d.cells < MAX * nn) {
         d = new Device(gpu, MAX * nn);
         DEVICE.set(d);
      }

      if (d.params != first.params) {
         gpu.enqueueCopyIn(d.paramsDevice, first.params.packed);
         d.params = first.params;
      }

      // Inputs: height, heightErosion, sediment, modifier, edge, river, mask, then the corner blocks.
      long in = MemoryUtil.memAddress(d.inHost);
      for (int j = 0; j < grids; j++) {
         ErosionFilterGpu.Job job = batch.get(j);
         long at = 4L * j * nn;
         long bytes = 4L * nn;
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.height), in + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.heightErosion), in + 4L * count + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.sediment), in + 8L * count + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.modifier), in + 12L * count + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.edge), in + 16L * count + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.river), in + 20L * count + at, bytes);
         MemoryUtil.memCopy(MemoryUtil.memAddress(job.mask), in + 24L * count + at, bytes);
         MemoryUtil.memPutInt(in + 28L * count + 4L * j, job.blockX);
         MemoryUtil.memPutInt(in + 28L * count + 4L * (grids + j), job.blockZ);
      }

      // Queued like the kernels after it: the download below is the batch's only wait.
      gpu.enqueueCopyIn(d.in, MemoryUtil.memByteBuffer(in, (int)(28L * count + 8L * grids)));
      long height = d.in;
      long heightErosion = d.in + 4L * count;
      long sediment = d.in + 8L * count;
      long modifier = d.in + 12L * count;
      long edge = d.in + 16L * count;
      long river = d.in + 20L * count;
      long mask = d.in + 24L * count;
      long blockXs = d.in + 28L * count;
      long blockZs = blockXs + 4L * grids;
      // Scratch: the envelope, slopes and gate one after another (downloaded together when a grid needs them).
      long env = d.scratch;
      long slopeX = d.scratch + 4L * count;
      long slopeZ = d.scratch + 8L * count;
      long zone = d.scratch + 12L * count;
      // Results: height, heightErosion, sediment, unsure.
      long outHeight = d.out;
      long outHeightErosion = d.out + 4L * count;
      long outSediment = d.out + 8L * count;
      long unsure = d.out + 12L * count;

      // ErosionKernels.runV2's passes, over every grid of the batch: queued back to back, waited for once by the download.
      ErosionKernels.Variant v = first.params.variant;
      int blocks = (count + 255) / 256;
      gpu.enqueue("ev2Envelope", blocks, 1, count, height, env);
      gpu.enqueue("evbWindowRows", blocks, 1, grids, n, 3, 1, env, d.tmp);
      gpu.enqueue("evbWindowCols", blocks, 1, grids, n, 3, 1, d.tmp, env);
      gpu.enqueue("evbSlopes", blocks, 1, grids, n, height, d.paramsDevice, slopeX, slopeZ);
      gpu.enqueue("ev2Gate", blocks, 1, count, slopeX, slopeZ, zone);
      gpu.enqueue("evbWindowRows", blocks, 1, grids, n, 2, 0, zone, d.tmp);
      gpu.enqueue("evbWindowCols", blocks, 1, grids, n, 2, 0, d.tmp, zone);
      gpu.enqueue("evbBlurRows", blocks, 1, grids, n, 2, zone, d.tmp);
      gpu.enqueue("evbBlurCols", blocks, 1, grids, n, 2, d.tmp, zone);
      gpu.enqueue("evbMain", blocks, 1, grids, n, first.params.seed, first.params.octaves, blockXs, blockZs, d.paramsDevice, height, env, slopeX, slopeZ, zone,
         mask, modifier, edge, river, heightErosion, sediment, outHeight, outHeightErosion, outSediment, unsure, v.slopeStart(), v.range(), v.depthPerSlope(),
         v.margin(), v.tableTrig() ? 1 : 0);
      gpu.enqueue("eroFinish", blocks, 1, count, d.paramsDevice, height, unsure, outHeight, outHeightErosion);

      long out = MemoryUtil.memAddress(d.outHost);
      ByteBuffer results = MemoryUtil.memByteBuffer(out, (int)(16L * count));
      gpu.download(d.out, results.asFloatBuffer());
      boolean more = false;
      for (int j = 0; j < grids; j++) {
         ErosionFilterGpu.Job job = batch.get(j);
         long at = 4L * j * nn;
         long bytes = 4L * nn;
         MemoryUtil.memCopy(out + at, MemoryUtil.memAddress(job.outHeight), bytes);
         MemoryUtil.memCopy(out + 4L * count + at, MemoryUtil.memAddress(job.outHeightErosion), bytes);
         MemoryUtil.memCopy(out + 8L * count + at, MemoryUtil.memAddress(job.outSediment), bytes);
         MemoryUtil.memCopy(out + 12L * count + at, MemoryUtil.memAddress(job.unsure), bytes);
         job.needsGrids = job.all;
         for (int i = 0; i < nn && !job.needsGrids; i++) {
            job.needsGrids = job.unsure.get(i) != 0;
         }

         more |= job.needsGrids;
      }

      if (!more) {
         return;
      }

      // The CPU step's grids for the grids that need them: the envelope, slopes and gate, and their own inputs (the
      // filter does not change them, so they are the state from before it).
      gpu.download(d.scratch, MemoryUtil.memByteBuffer(out, (int)(16L * count)).asFloatBuffer());
      for (int j = 0; j < grids; j++) {
         ErosionFilterGpu.Job job = batch.get(j);
         if (!job.needsGrids) {
            continue;
         }

         long at = 4L * j * nn;
         job.relaxed = slice(out + at, nn);
         job.slopeX = slice(out + 4L * count + at, nn);
         job.slopeZ = slice(out + 8L * count + at, nn);
         job.zone = slice(out + 12L * count + at, nn);
         job.preHeight = slice(MemoryUtil.memAddress(job.height), nn);
         job.preHeightErosion = slice(MemoryUtil.memAddress(job.heightErosion), nn);
         job.preSediment = slice(MemoryUtil.memAddress(job.sediment), nn);
      }
   }

   /** A new buffer (freed with the job) holding a copy of nn floats. */
   private static FloatBuffer slice(long from, int nn) {
      FloatBuffer into = MemoryUtil.memAllocFloat(nn);
      MemoryUtil.memCopy(from, MemoryUtil.memAddress(into), 4L * nn);
      return into;
   }

   /** A GPU thread's buffers for batches of up to cells cells. */
   private static final class Device {
      final int cells;
      final long in;
      final long scratch;
      final long tmp;
      final long out;
      final long paramsDevice;
      final ByteBuffer inHost;
      final ByteBuffer outHost;
      ErosionFilterGpu.Params params;

      Device(Gpu gpu, int cells) {
         this.cells = cells;
         this.in = gpu.allocate(28L * cells + 8L * MAX);
         this.scratch = gpu.allocate(16L * cells);
         this.tmp = gpu.allocate(4L * cells);
         this.out = gpu.allocate(16L * cells);
         this.paramsDevice = gpu.allocate(4L * ErosionFilterGpu.Params.PACKED);
         this.inHost = MemoryUtil.memAlloc((int)(28L * cells + 8L * MAX));
         this.outHost = MemoryUtil.memAlloc((int)(16L * cells));
      }
   }
}
