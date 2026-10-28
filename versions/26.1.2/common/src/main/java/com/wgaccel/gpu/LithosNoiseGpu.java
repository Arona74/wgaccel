package com.wgaccel.gpu;

import com.wgaccel.mixin.access.NormalNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.lwjgl.system.MemoryUtil;

/**
 * Lithos's rock noise on the GPU ({@link com.wgaccel.gen.LithosNoise}): for each of a chunk's 256 columns and ROWS
 * heights from the column's top row down, the six NormalNoise samples behind Lithos's five noise functions (its class
 * worldgen.t: a, b, c, both of d's, e), at the same coordinates as Lithos makes them from the block position, operation
 * for operation (VanillaKernels' mc_normal, exact flags). Lithos's own scaling of each sample stays on the CPU. One
 * instance per thread that runs it (its own stream and buffers).
 */
public final class LithosNoiseGpu implements AutoCloseable {
   /** Samples per block, in the order of {@link com.wgaccel.gen.LithosNoise}'s functions. */
   public static final int SAMPLES = 6;
   private static final String KERNEL = """
      extern "C" __global__ void lithosNoise(const double* D, const int* P, const int* tops, int minX, int minZ, int rows, double* out) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= 256 * rows) return;
          int col = i / rows;
          int k = i - col * rows;
          double x = (double)(minX + (col & 15));
          double y = (double)(tops[col] - k);
          double z = (double)(minZ + (col >> 4));
          double* o = out + i * 6;
          o[0] = mc_normal(D, P, x * 0.020833333333333332 + 5000.0, y * 0.020833333333333332 * 0.3, z * 0.020833333333333332 - 5000.0);
          o[1] = mc_normal(D, P, x * 0.08333333333333333 + 1000.0, y * 0.08333333333333333, z * 0.08333333333333333 - 1000.0);
          o[2] = mc_normal(D, P, x * 0.010416666666666666, y * 0.16666666666666666, z * 0.010416666666666666);
          o[3] = mc_normal(D, P, x * 0.025 + 3000.0, y * 0.025 * 0.3, z * 0.025 - 3000.0);
          o[4] = mc_normal(D, P, x * 0.07142857142857142 - 3000.0, y * 0.07142857142857142 * 0.3, z * 0.07142857142857142 + 3000.0);
          o[5] = mc_normal(D, P, x * 0.1 + 9000.0, y * 0.1, z * 0.1 - 9000.0);
      }
      """;

   private final Gpu gpu;
   private final int rows;
   private final long noiseData;
   private final long permutations;
   private final long tops;
   private final long out;
   private final IntBuffer topsIn = MemoryUtil.memAllocInt(256);
   private final DoubleBuffer values;

   public LithosNoiseGpu(long context, NormalNoise noise, int rows) {
      this.gpu = new Gpu(context, VanillaKernels.NOISE + KERNEL, "lithos_noise.cu");
      this.gpu.useStream(GpuStreams.create());
      this.rows = rows;
      DoubleArrayList d = new DoubleArrayList();
      IntArrayList p = new IntArrayList();
      NormalNoiseAccessor access = (NormalNoiseAccessor)noise;
      d.add(access.wgaccel$valueFactor());
      DensityCheck.pack(access.wgaccel$first(), d, p);
      DensityCheck.pack(access.wgaccel$second(), d, p);
      DoubleBuffer dIn = MemoryUtil.memAllocDouble(d.size()).put(0, d.toDoubleArray());
      IntBuffer pIn = MemoryUtil.memAllocInt(p.size()).put(0, p.toIntArray());
      try {
         this.noiseData = this.gpu.upload(dIn);
         this.permutations = this.gpu.upload(pIn);
      } finally {
         MemoryUtil.memFree(dIn);
         MemoryUtil.memFree(pIn);
      }

      this.tops = this.gpu.allocate(4L * 256);
      this.out = this.gpu.allocate(8L * 256 * rows * SAMPLES);
      this.values = MemoryUtil.memAllocDouble(256 * rows * SAMPLES);
   }

   /** The samples for the chunk whose block origin is (minX, minZ), each column from tops[column] down, into into[(column * rows + k) * 6 + sample]. */
   public void run(int minX, int minZ, int[] columnTops, double[] into) {
      this.topsIn.put(0, columnTops, 0, 256);
      this.gpu.copyIn(this.tops, this.topsIn);
      this.gpu.run("lithosNoise", (256 * this.rows + 255) / 256, 1, this.noiseData, this.permutations, this.tops, minX, minZ, this.rows, this.out);
      this.gpu.download(this.out, this.values);
      this.values.get(0, into, 0, into.length);
   }

   @Override
   public void close() {
      MemoryUtil.memFree(this.topsIn);
      MemoryUtil.memFree(this.values);
      this.gpu.close();
   }
}
