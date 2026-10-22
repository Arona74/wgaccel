package com.wgaccel.cudacheck;

import com.wgaccel.gpu.ErosionKernels;
import com.wgaccel.gpu.Gpu;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * Outside the game (`gradlew.bat cudaErosionCheck`): FreeTerraForged's tile erosion filter copied here over plain arrays
 * (its cells' fields as arrays, the same Java otherwise) and run on a made-up tile, against {@link ErosionKernels} on the
 * GPU, compared bit for bit. Catches CUDA translation mistakes before a world load; the in-game check compares with
 * FreeTerraForged itself.
 */
public final class ErosionFilterCheck {
   private static final float TAU = (float)(Math.PI * 2);

   private ErosionFilterCheck() {
   }

   /** Settings like a typical preset's (the filter's fields and its ErosionFilterSettings). */
   private static final float SCALE_BLOCKS = 96.0F;
   private static final float WORLD_HEIGHT = 1152.0F;
   private static final float STRENGTH = 0.2F;
   private static final float GULLY_WEIGHT = 0.5F;
   private static final float DETAIL = 3.0F;
   private static final float LACUNARITY = 2.0F;
   private static final float GAIN = 0.5F;
   private static final float PHACELLE_SCALE = 1.0F;
   private static final float PHACELLE_OFFSET = 0.5F;
   private static final float PHACELLE_NORMALIZATION = 0.5F;
   private static final float ROUNDING_MIN = 0.1F;
   private static final float ROUNDING_MAX = 1.0F;
   private static final float SLOPE_ONSET_BASE = 1.0F;
   private static final float SLOPE_ONSET_RIDGE = 1.0F;
   private static final float SLOPE_ONSET_OCTAVE = 1.0F;
   private static final float WATER = 0.24F;
   private static final float GROUND = 0.245F;
   private static final float MOD_MIN = GROUND;
   private static final float MOD_MAX = 0.26F;
   private static final float MOD_RANGE = MOD_MAX - MOD_MIN;
   private static final int SEED = 12345 + 12768;
   private static final int OCTAVES = 5;

   public static void main(String[] args) throws Exception {
      int n = 160;
      int count = n * n;
      int blockX = -1_234_560;
      int blockZ = 987_648;
      Random random = new Random(7L);
      float[] height = new float[count];
      boolean[] mask = new boolean[count];
      float[] emod = new float[count];
      float[] edge = new float[count];
      float[] river = new float[count];
      for (int z = 0; z < n; z++) {
         for (int x = 0; x < n; x++) {
            int i = z * n + x;
            height[i] = 0.3F + 0.2F * (float)Math.sin(x * 0.05) * (float)Math.cos(z * 0.07) + 0.01F * random.nextFloat() + (x % 17 == 0 ? 0.02F : 0.0F);
            mask[i] = random.nextInt(20) == 0;
            emod[i] = random.nextInt(4) == 0 ? 0.5F : 1.0F;
            edge[i] = random.nextFloat() * 0.3F;
            river[i] = random.nextInt(5) == 0 ? random.nextFloat() * 0.1F : 1.0F;
         }
      }

      height[5] = 0.2F;
      float[] cellHeight = height.clone();
      float[] heightErosion = new float[count];
      float[] sediment = new float[count];
      float[] pre = new float[count];
      long cpuStart = System.nanoTime();
      reference(n, cellHeight, mask, emod, edge, river, heightErosion, sediment, pre, blockX, blockZ);
      double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;

      float minRelief = 3.0F / WORLD_HEIGHT;
      float maxRelief = 8.0F / WORLD_HEIGHT;
      float invRange = 1.0F / Math.max(1.0E-5F, maxRelief - minRelief);
      float[] p = {SCALE_BLOCKS, WORLD_HEIGHT, STRENGTH, GULLY_WEIGHT, DETAIL, LACUNARITY, GAIN, PHACELLE_SCALE, PHACELLE_OFFSET, PHACELLE_NORMALIZATION,
         ROUNDING_MIN, ROUNDING_MAX, SLOPE_ONSET_BASE, SLOPE_ONSET_RIDGE, SLOPE_ONSET_OCTAVE, MOD_MIN, MOD_MAX, MOD_RANGE, WATER, GROUND, minRelief, invRange};
      long start = System.nanoTime();
      try (Gpu gpu = new Gpu(ErosionKernels.SOURCE, "erosion_check.cu")) {
         // As the game runs it since 0.9.56: on its own non-blocking stream of the highest priority.
         gpu.useStream(com.wgaccel.gpu.GpuStreams.create());
         System.out.printf(Locale.ROOT, "compiled and loaded in %.0f ms%n", (System.nanoTime() - start) / 1.0e6);
         ErosionKernels.Buffers d = new ErosionKernels.Buffers(gpu, count);
         gpu.copyIn(d.params, MemoryUtil.memAllocFloat(p.length).put(0, p));
         gpu.copyIn(d.height, floats(height));
         IntBuffer maskBuffer = MemoryUtil.memAllocInt(count);
         for (int i = 0; i < count; i++) {
            maskBuffer.put(i, mask[i] ? 1 : 0);
         }

         gpu.copyIn(d.mask, maskBuffer);
         gpu.copyIn(d.modifier, floats(emod));
         gpu.copyIn(d.edge, floats(edge));
         gpu.copyIn(d.river, floats(river));
         float gpuMs = 0;
         for (int round = 0; round < 3; round++) {
            // The relaxation adds to heightErosion in place: every round starts from the inputs again.
            gpu.copyIn(d.heightErosion, floats(new float[count]));
            gpu.copyIn(d.sediment, floats(new float[count]));
            long run = System.nanoTime();
            ErosionKernels.run(gpu, d, n, SEED, OCTAVES, blockX, blockZ);
            gpuMs = (System.nanoTime() - run) / 1.0e6F;
         }

         FloatBuffer outHeight = MemoryUtil.memAllocFloat(count);
         FloatBuffer outHeightErosion = MemoryUtil.memAllocFloat(count);
         FloatBuffer outSediment = MemoryUtil.memAllocFloat(count);
         IntBuffer unsure = MemoryUtil.memAllocInt(count);
         gpu.download(d.outHeight, outHeight);
         gpu.download(d.outHeightErosion, outHeightErosion);
         gpu.download(d.outSediment, outSediment);
         gpu.download(d.unsure, unsure);
         int different = 0;
         int notSure = 0;
         int changed = 0;
         for (int i = 0; i < count; i++) {
            if (unsure.get(i) != 0) {
               notSure++;
               continue;
            }

            if (cellHeight[i] != height[i]) {
               changed++;
            }

            if (!same(outHeight.get(i), cellHeight[i]) || !same(outHeightErosion.get(i), heightErosion[i]) || !same(outSediment.get(i), sediment[i])) {
               if (different++ < 8) {
                  System.out.printf(Locale.ROOT, "cell (%d, %d): height %s / java %s, heightErosion %s / %s, sediment %s / %s%n", i % n, i / n, outHeight.get(i),
                     cellHeight[i], outHeightErosion.get(i), heightErosion[i], outSediment.get(i), sediment[i]);
               }
            }
         }

         System.out.printf(Locale.ROOT, "%,d cells (%,d changed by erosion): %s; %d unsure; GPU %.2f ms per tile, Java %.1f ms%n", count, changed,
            different == 0 ? "BIT-IDENTICAL" : different + " DIFFER", notSure, gpuMs, cpuMs);
      }

      v2(n, height, mask, emod, edge, river, blockX, blockZ, p, random, ErosionKernels.Variant.OCT_21);
      // FreeTerraForged 1.0.0013's filter (its chunk grids): the same with its constants and NoiseUtil's table sin / cos.
      v2(n, height, mask, emod, edge, river, blockX, blockZ, p, random, ErosionKernels.Variant.FTF_1_0_0013);
      batched(n, height, mask, emod, edge, river, blockX, blockZ, p);
      cliff(n, height, mask, emod, edge, river, blockX, blockZ);
   }

   /** S6.2: FreeTerraForged's CliffRelaxation (Java copy below) against {@link ErosionKernels#runCliff}, the same tile. */
   private static void cliff(int n, float[] height, boolean[] mask, float[] emod, float[] edge, float[] river, int blockX, int blockZ) throws Exception {
      int count = n * n;
      int seed = 12345 + 7919;
      float[] heights = height.clone();
      float[] mods = new float[count];
      long cpuStart = System.nanoTime();
      referenceCliff(n, heights, mask, emod, edge, river, mods, seed, blockX, blockZ);
      double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;
      float minRelief = 3.0F / WORLD_HEIGHT;
      float maxRelief = 8.0F / WORLD_HEIGHT;
      float reliefSpan = Math.max(1e-5F, maxRelief - minRelief);
      float[] packed = new float[ErosionKernels.Buffers.PARAMS];
      packed[15] = MOD_MIN;
      packed[16] = MOD_MAX;
      packed[17] = MOD_RANGE;
      try (Gpu gpu = new Gpu(ErosionKernels.SOURCE, "cliff_check.cu")) {
         gpu.useStream(com.wgaccel.gpu.GpuStreams.create());
         ErosionKernels.Buffers d = new ErosionKernels.Buffers(gpu, count);
         gpu.copyIn(d.params, MemoryUtil.memAllocFloat(packed.length).put(0, packed));
         gpu.copyIn(d.height, floats(height));
         IntBuffer maskBuffer = MemoryUtil.memAllocInt(count);
         for (int i = 0; i < count; i++) {
            maskBuffer.put(i, mask[i] ? 1 : 0);
         }

         gpu.copyIn(d.mask, maskBuffer);
         gpu.copyIn(d.modifier, floats(emod));
         gpu.copyIn(d.edge, floats(edge));
         gpu.copyIn(d.river, floats(river));
         long relaxed = 0;
         float gpuMs = 0;
         for (int round = 0; round < 3; round++) {
            long run = System.nanoTime();
            relaxed = ErosionKernels.runCliff(gpu, d, n, seed, blockX, blockZ, 1.0F / Math.max(1.0F, 28.0F), 1.0F / Math.max(1.0F, 11.0F), minRelief, reliefSpan);
            gpuMs = (System.nanoTime() - run) / 1.0e6F;
         }

         FloatBuffer out = MemoryUtil.memAllocFloat(count);
         FloatBuffer gpuMods = MemoryUtil.memAllocFloat(count);
         gpu.download(relaxed, out);
         gpu.download(d.mods, gpuMods);
         int different = 0;
         int changed = 0;
         for (int i = 0; i < count; i++) {
            changed += heights[i] != height[i] ? 1 : 0;
            if (!same(out.get(i), heights[i]) || !same(gpuMods.get(i), mods[i])) {
               if (different++ < 8) {
                  System.out.printf(Locale.ROOT, "cliff: cell (%d, %d): height %s / java %s, mods %s / %s%n", i % n, i / n, out.get(i), heights[i], gpuMods.get(i), mods[i]);
               }
            }
         }

         System.out.printf(Locale.ROOT, "CliffRelaxation: %,d cells (%,d changed): %s; GPU %.2f ms per tile, Java %.1f ms%n", count, changed,
            different == 0 ? "BIT-IDENTICAL" : different + " DIFFER", gpuMs, cpuMs);
      }
   }

   /** FreeTerraForged's 21 Oct CliffRelaxation.apply (MIT, ReTerraForged): heights after the passes (not committed), mods. */
   private static void referenceCliff(int tileSize, float[] heights, boolean[] erosionMask, float[] emod, float[] edge, float[] river, float[] mods, int seed,
         int worldBlockX, int worldBlockZ) {
      int total = tileSize * tileSize;
      float[] next = new float[total];
      float[] rateMul = new float[total];
      float[] reliefMul = new float[total];
      float minRelief = 3.0f / WORLD_HEIGHT;
      float maxRelief = 8.0f / WORLD_HEIGHT;
      float reliefSpan = Math.max(1e-5f, maxRelief - minRelief);
      for (int i = 0; i < total; ++i) {
         mods[i] = (erosionMask[i] || !Float.isFinite(heights[i])) ? 0.0f : modify(heights[i], emod[i], edge[i], river[i]);
      }

      float broadFreq = 1.0f / Math.max(1.0f, 28.0f);
      float detailFreq = 1.0f / Math.max(1.0f, 11.0f);
      for (int z = 0; z < tileSize; ++z) {
         int row = z * tileSize;
         float wz = (float)(worldBlockZ + z);
         for (int x = 0; x < tileSize; ++x) {
            int idx = row + x;
            if (mods[idx] == 0.0f) {
               rateMul[idx] = 1.0f;
               reliefMul[idx] = 1.0f;
               continue;
            }

            float wx = (float)(worldBlockX + x);
            float strengthN = centeredNoise(wx, wz, broadFreq, detailFreq, seed);
            float thresholdN = centeredNoise(wx, wz, broadFreq, detailFreq, seed + 6007);
            rateMul[idx] = 1.0f + 0.5f * strengthN;
            reliefMul[idx] = 1.0f + 0.35f * thresholdN;
         }
      }

      for (int pass = 0; pass < 3; ++pass) {
         for (int z = 0; z < tileSize; ++z) {
            int row = z * tileSize;
            int zPrevRow = Math.max(0, z - 1) * tileSize;
            int zNextRow = Math.min(tileSize - 1, z + 1) * tileSize;
            for (int x = 0; x < tileSize; ++x) {
               int idx = row + x;
               float c = heights[idx];
               float m = mods[idx];
               if (m == 0.0f) {
                  next[idx] = c;
                  continue;
               }

               float l = heights[row + Math.max(0, x - 1)];
               float r = heights[row + Math.min(tileSize - 1, x + 1)];
               float d = heights[zPrevRow + x];
               float u = heights[zNextRow + x];
               float avg = 0.25f * (l + r + d + u);
               float hi = Math.max(Math.max(c, l), Math.max(Math.max(r, d), u));
               float lo = Math.min(Math.min(c, l), Math.min(Math.min(r, d), u));
               float rm = reliefMul[idx];
               float t = clamp((hi - lo - minRelief * rm) / (reliefSpan * rm), 0.0f, 1.0f);
               float maskValue = t * t * (3.0f - 2.0f * t);
               float rate = Math.min(0.5f, 0.4f * rateMul[idx]);
               float delta = (avg - c) * maskValue * rate;
               next[idx] = c + m * delta;
            }
         }

         System.arraycopy(next, 0, heights, 0, total);
      }
   }

   private static float centeredNoise(float wx, float wz, float broadFreq, float detailFreq, int seed) {
      float broad = valueNoise(wx * broadFreq, wz * broadFreq, seed);
      float detail = valueNoise(wx * detailFreq, wz * detailFreq, seed + 101);
      float n = (broad + 0.35f * detail) / (1.0f + 0.35f);
      return (n - 0.5f) * 2.0f;
   }

   private static float valueNoise(float px, float pz, int seed) {
      int xi = (int)Math.floor(px);
      int zi = (int)Math.floor(pz);
      float fx = px - xi;
      float fz = pz - zi;
      fx = fx * fx * (3.0f - 2.0f * fx);
      fz = fz * fz * (3.0f - 2.0f * fz);
      float v00 = hash01(xi, zi, seed);
      float v10 = hash01(xi + 1, zi, seed);
      float v01 = hash01(xi, zi + 1, seed);
      float v11 = hash01(xi + 1, zi + 1, seed);
      float a = v00 + fx * (v10 - v00);
      float b = v01 + fx * (v11 - v01);
      return a + fz * (b - a);
   }

   private static float hash01(int x, int z, int seed) {
      int n = x * 374761393 + z * 668265263 + seed * 144677219;
      n = (n ^ (n >> 13)) * 1274126177;
      n ^= (n >> 16);
      return (n & 0x7FFFFFFF) / (float)0x7FFFFFFF;
   }

   /**
    * G6c: four 40 x 40 grids cut from the tile (as 1.0.0013's chunk grids), each through ErosionKernels.runV2 alone and
    * all four through the batched evb kernels in one go (the way ErosionGridBatch runs them); every result compared.
    */
   private static void batched(int n, float[] height, boolean[] mask, float[] emod, float[] edge, float[] river, int blockX, int blockZ, float[] p)
         throws Exception {
      int g = 40;
      int nn = g * g;
      int grids = 4;
      int[][] corners = {{0, 0}, {60, 17}, {n - g, n - g}, {33, 101}};
      int count = grids * nn;
      float[][] in = new float[7][count];
      int[] bx = new int[grids];
      int[] bz = new int[grids];
      for (int j = 0; j < grids; j++) {
         bx[j] = blockX + corners[j][0];
         bz[j] = blockZ + corners[j][1];
         for (int z = 0; z < g; z++) {
            for (int x = 0; x < g; x++) {
               int from = (corners[j][1] + z) * n + corners[j][0] + x;
               int to = j * nn + z * g + x;
               in[0][to] = height[from];
               in[1][to] = (from % 7) * 0.001F;
               in[2][to] = (from % 5) * 0.1F - 0.2F;
               in[3][to] = emod[from];
               in[4][to] = edge[from];
               in[5][to] = river[from];
               in[6][to] = mask[from] ? 1 : 0;
            }
         }
      }

      ErosionKernels.Variant v = ErosionKernels.Variant.FTF_1_0_0013;
      try (Gpu gpu = new Gpu(ErosionKernels.SOURCE, "erosion_batch_check.cu")) {
         gpu.useStream(com.wgaccel.gpu.GpuStreams.create());
         // One by one, the single-grid kernels.
         float[][] single = new float[4][count];
         ErosionKernels.Buffers d = new ErosionKernels.Buffers(gpu, nn);
         gpu.copyIn(d.params, MemoryUtil.memAllocFloat(p.length).put(0, p));
         long oneStart = System.nanoTime();
         for (int j = 0; j < grids; j++) {
            gpu.copyIn(d.height, floats(Arrays.copyOfRange(in[0], j * nn, (j + 1) * nn)));
            gpu.copyIn(d.heightErosion, floats(Arrays.copyOfRange(in[1], j * nn, (j + 1) * nn)));
            gpu.copyIn(d.sediment, floats(Arrays.copyOfRange(in[2], j * nn, (j + 1) * nn)));
            gpu.copyIn(d.modifier, floats(Arrays.copyOfRange(in[3], j * nn, (j + 1) * nn)));
            gpu.copyIn(d.edge, floats(Arrays.copyOfRange(in[4], j * nn, (j + 1) * nn)));
            gpu.copyIn(d.river, floats(Arrays.copyOfRange(in[5], j * nn, (j + 1) * nn)));
            IntBuffer m = MemoryUtil.memAllocInt(nn);
            for (int i = 0; i < nn; i++) {
               m.put(i, (int)in[6][j * nn + i]);
            }

            gpu.copyIn(d.mask, m);
            ErosionKernels.runV2(gpu, d, g, SEED, OCTAVES, bx[j], bz[j], v);
            FloatBuffer o = MemoryUtil.memAllocFloat(nn);
            IntBuffer u = MemoryUtil.memAllocInt(nn);
            gpu.download(d.outHeight, o);
            o.get(0, single[0], j * nn, nn);
            gpu.download(d.outHeightErosion, o);
            o.get(0, single[1], j * nn, nn);
            gpu.download(d.outSediment, o);
            o.get(0, single[2], j * nn, nn);
            gpu.download(d.unsure, u);
            for (int i = 0; i < nn; i++) {
               single[3][j * nn + i] = u.get(i);
            }
         }

         double oneMs = (System.nanoTime() - oneStart) / 1.0e6;
         // All at once, the batched kernels (separate buffers here; ErosionGridBatch packs them into one).
         long h = gpu.upload(floats(in[0]));
         long he = gpu.upload(floats(in[1]));
         long sed = gpu.upload(floats(in[2]));
         long mod = gpu.upload(floats(in[3]));
         long ed = gpu.upload(floats(in[4]));
         long rv = gpu.upload(floats(in[5]));
         IntBuffer maskAll = MemoryUtil.memAllocInt(count);
         for (int i = 0; i < count; i++) {
            maskAll.put(i, (int)in[6][i]);
         }

         long mk = gpu.upload(maskAll);
         long bxs = gpu.upload(MemoryUtil.memAllocInt(grids).put(0, bx));
         long bzs = gpu.upload(MemoryUtil.memAllocInt(grids).put(0, bz));
         long env = gpu.allocate(4L * count);
         long tmp = gpu.allocate(4L * count);
         long sx = gpu.allocate(4L * count);
         long sz = gpu.allocate(4L * count);
         long zone = gpu.allocate(4L * count);
         long oh = gpu.allocate(4L * count);
         long ohe = gpu.allocate(4L * count);
         long os = gpu.allocate(4L * count);
         long un = gpu.allocate(4L * count);
         int blocks = (count + 255) / 256;
         long batchStart = System.nanoTime();
         gpu.enqueue("ev2Envelope", blocks, 1, count, h, env);
         gpu.enqueue("evbWindowRows", blocks, 1, grids, g, 3, 1, env, tmp);
         gpu.enqueue("evbWindowCols", blocks, 1, grids, g, 3, 1, tmp, env);
         gpu.enqueue("evbSlopes", blocks, 1, grids, g, h, d.params, sx, sz);
         gpu.enqueue("ev2Gate", blocks, 1, count, sx, sz, zone);
         gpu.enqueue("evbWindowRows", blocks, 1, grids, g, 2, 0, zone, tmp);
         gpu.enqueue("evbWindowCols", blocks, 1, grids, g, 2, 0, tmp, zone);
         gpu.enqueue("evbBlurRows", blocks, 1, grids, g, 2, zone, tmp);
         gpu.enqueue("evbBlurCols", blocks, 1, grids, g, 2, tmp, zone);
         gpu.enqueue("evbMain", blocks, 1, grids, g, SEED, OCTAVES, bxs, bzs, d.params, h, env, sx, sz, zone, mk, mod, ed, rv, he, sed, oh, ohe, os, un,
            v.slopeStart(), v.range(), v.depthPerSlope(), v.margin(), 1);
         gpu.enqueue("eroFinish", blocks, 1, count, d.params, h, un, oh, ohe);
         gpu.finish();
         double batchMs = (System.nanoTime() - batchStart) / 1.0e6;
         FloatBuffer o = MemoryUtil.memAllocFloat(count);
         IntBuffer u = MemoryUtil.memAllocInt(count);
         int different = 0;
         long[] outs = {oh, ohe, os};
         for (int k = 0; k < 3; k++) {
            gpu.download(outs[k], o);
            for (int i = 0; i < count; i++) {
               if (!same(o.get(i), single[k][i]) && different++ < 8) {
                  System.out.printf(Locale.ROOT, "batched grids: grid %d cell %d field %d: %s / alone %s%n", i / nn, i % nn, k, o.get(i), single[k][i]);
               }
            }
         }

         gpu.download(un, u);
         for (int i = 0; i < count; i++) {
            if (u.get(i) != (int)single[3][i] && different++ < 8) {
               System.out.printf(Locale.ROOT, "batched grids: grid %d cell %d unsure %d / alone %d%n", i / nn, i % nn, u.get(i), (int)single[3][i]);
            }
         }

         System.out.printf(Locale.ROOT, "batched 1.0.0013 grids: %d grids of 40 x 40 at once against one by one: %s; kernels %.2f ms for all (one by one with copies %.2f ms)%n",
            grids, different == 0 ? "IDENTICAL" : different + " DIFFER", batchMs, oneMs);
      }
   }

   /** S6: FreeTerraForged's 21 Oct erosion filter (Java copy below) against {@link ErosionKernels#runV2}, the same tile. */
   private static void v2(int n, float[] height, boolean[] mask, float[] emod, float[] edge, float[] river, int blockX, int blockZ, float[] p, Random random,
         ErosionKernels.Variant variant) throws Exception {
      String name = variant.tableTrig() ? "1.0.0013 filter" : "21 Oct filter";
      if (variant.tableTrig()) {
         java.lang.reflect.Field sin = etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil.class.getDeclaredField("SIN");
         sin.setAccessible(true);
         System.out.println("sine table: " + (java.util.Arrays.equals((float[])sin.get(null), ErosionKernels.SIN) ? "the same as FreeTerraForged's NoiseUtil.SIN"
            : "DIFFERENT from FreeTerraForged's NoiseUtil.SIN"));
      }
      int count = n * n;
      float[] startSediment = new float[count];
      float[] startErosion = new float[count];
      for (int i = 0; i < count; i++) {
         startSediment[i] = random.nextInt(3) == 0 ? random.nextFloat() * 2.0F - 1.0F : 0.0F;
         startErosion[i] = random.nextInt(3) == 0 ? random.nextFloat() * 0.01F : 0.0F;
      }

      float[] cellHeight = height.clone();
      float[] heightErosion = startErosion.clone();
      float[] sediment = startSediment.clone();
      long cpuStart = System.nanoTime();
      referenceV2(n, cellHeight, mask, emod, edge, river, heightErosion, sediment, blockX, blockZ, variant);
      double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;
      try (Gpu gpu = new Gpu(ErosionKernels.SOURCE, "erosion_v2_check.cu")) {
         gpu.useStream(com.wgaccel.gpu.GpuStreams.create());
         ErosionKernels.Buffers d = new ErosionKernels.Buffers(gpu, count);
         gpu.copyIn(d.params, MemoryUtil.memAllocFloat(p.length).put(0, p));
         gpu.copyIn(d.height, floats(height));
         IntBuffer maskBuffer = MemoryUtil.memAllocInt(count);
         for (int i = 0; i < count; i++) {
            maskBuffer.put(i, mask[i] ? 1 : 0);
         }

         gpu.copyIn(d.mask, maskBuffer);
         gpu.copyIn(d.modifier, floats(emod));
         gpu.copyIn(d.edge, floats(edge));
         gpu.copyIn(d.river, floats(river));
         gpu.copyIn(d.heightErosion, floats(startErosion));
         gpu.copyIn(d.sediment, floats(startSediment));
         float gpuMs = 0;
         for (int round = 0; round < 3; round++) {
            long run = System.nanoTime();
            ErosionKernels.runV2(gpu, d, n, SEED, OCTAVES, blockX, blockZ, variant);
            gpuMs = (System.nanoTime() - run) / 1.0e6F;
         }

         FloatBuffer outHeight = MemoryUtil.memAllocFloat(count);
         FloatBuffer outHeightErosion = MemoryUtil.memAllocFloat(count);
         FloatBuffer outSediment = MemoryUtil.memAllocFloat(count);
         IntBuffer unsure = MemoryUtil.memAllocInt(count);
         gpu.download(d.outHeight, outHeight);
         gpu.download(d.outHeightErosion, outHeightErosion);
         gpu.download(d.outSediment, outSediment);
         gpu.download(d.unsure, unsure);
         int different = 0;
         int notSure = 0;
         int changed = 0;
         int sedimentChanged = 0;
         for (int i = 0; i < count; i++) {
            if (unsure.get(i) != 0) {
               notSure++;
               continue;
            }

            changed += cellHeight[i] != height[i] ? 1 : 0;
            sedimentChanged += sediment[i] != startSediment[i] ? 1 : 0;
            if (!same(outHeight.get(i), cellHeight[i]) || !same(outHeightErosion.get(i), heightErosion[i]) || !same(outSediment.get(i), sediment[i])) {
               if (different++ < 8) {
                  System.out.printf(Locale.ROOT, "%s: cell (%d, %d): height %s / java %s, heightErosion %s / %s, sediment %s / %s%n", name, i % n, i / n,
                     outHeight.get(i), cellHeight[i], outHeightErosion.get(i), heightErosion[i], outSediment.get(i), sediment[i]);
               }
            }
         }

         System.out.printf(Locale.ROOT, "%s: %,d cells (%,d heights and %,d sediments changed): %s; %d unsure; GPU %.2f ms per tile, Java %.1f ms%n",
            name, count, changed, sedimentChanged, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER", notSure, gpuMs, cpuMs);
      }
   }

   /** FreeTerraForged's 21 Oct Erosion.apply (MIT, ReTerraForged), its cells as arrays. */
   private static void referenceV2(int tileSize, float[] cellHeight, boolean[] erosionMask, float[] emod, float[] edge, float[] river, float[] heightErosion,
         float[] cellSediment, int worldBlockX, int worldBlockZ, ErosionKernels.Variant variant) {
      int length = tileSize * tileSize;
      float[] pre = cellHeight.clone();
      float[] heights = cellHeight.clone();
      float[] envHi = new float[length];
      float[] slopeX = new float[length];
      float[] slopeZ = new float[length];
      float[] gateSlope = new float[length];
      float[] gateTmp = new float[length];
      float[] phacelleOut = new float[4];
      for (int i = 0; i < length; ++i) {
         float h = heights[i];
         envHi[i] = Float.isFinite(h) ? h : Float.NEGATIVE_INFINITY;
      }

      windowMax(envHi, gateTmp, tileSize, 3);
      float wh = WORLD_HEIGHT;
      int last = tileSize - 1;
      for (int z = 0; z < tileSize; ++z) {
         int row = z * tileSize;
         slopeX[row] = (heights[row + 1] - heights[row]) * wh;
         for (int x = 1; x < last; ++x) {
            int idx = row + x;
            float c = heights[idx];
            slopeX[idx] = minmod(c - heights[idx - 1], heights[idx + 1] - c) * wh;
         }

         slopeX[row + last] = (heights[row + last] - heights[row + last - 1]) * wh;
         if (z == 0) {
            int nextRow = row + tileSize;
            for (int x = 0; x < tileSize; ++x) {
               slopeZ[row + x] = (heights[nextRow + x] - heights[row + x]) * wh;
            }
         } else if (z == last) {
            int prevRow = row - tileSize;
            for (int x = 0; x < tileSize; ++x) {
               slopeZ[row + x] = (heights[row + x] - heights[prevRow + x]) * wh;
            }
         } else {
            int prevRow = row - tileSize;
            int nextRow = row + tileSize;
            for (int x = 0; x < tileSize; ++x) {
               float c = heights[row + x];
               slopeZ[row + x] = minmod(c - heights[prevRow + x], heights[nextRow + x] - c) * wh;
            }
         }
      }

      for (int i = 0; i < length; ++i) {
         float sx = slopeX[i];
         float sz = slopeZ[i];
         float len = (float)Math.sqrt(sx * sx + sz * sz + 1e-10f);
         gateSlope[i] = Float.isFinite(len) ? len : 0.0f;
      }

      windowMin(gateSlope, gateTmp, tileSize, 2);
      boxBlur(gateSlope, gateTmp, tileSize, 2);
      float slopeGateRange = variant.range();
      for (int z = 0; z < tileSize; ++z) {
         int row = z * tileSize;
         float worldZ = (float)(worldBlockZ + z);
         for (int x = 0; x < tileSize; ++x) {
            int idx = row + x;
            if (erosionMask[idx]) {
               continue;
            }

            float baseHeight = heights[idx];
            if (!Float.isFinite(baseHeight)) {
               continue;
            }

            float curSlopeX = slopeX[idx];
            float curSlopeZ = slopeZ[idx];
            float slopeLen = (float)Math.sqrt(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1e-10f);
            float heightFade = clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);
            float ridgeMapCombiMask = Math.min(0.98f, easeOut(slopeLen * 0.01f * SLOPE_ONSET_RIDGE));
            float rawT = clamp((slopeLen - variant.slopeStart()) / slopeGateRange, 0.0f, 1.0f);
            if (rawT <= 0.0f) {
               cellSediment[idx] = clamp(cellSediment[idx] + heightFade * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
               continue;
            }

            float smoothSlope = gateSlope[idx];
            float ampT = clamp((smoothSlope - variant.slopeStart()) / slopeGateRange, 0.0f, 1.0f);
            float flatGate = ampT * ampT * (3.0f - 2.0f * ampT);
            float erosionFactor = modify(cellHeight[idx], emod[idx], edge[idx], river[idx]);
            if (erosionFactor == 0.0F) {
               continue;
            }

            float worldX = (float)(worldBlockX + x);
            float currentStrength = STRENGTH * SCALE_BLOCKS;
            float freq = 1.0f / (SCALE_BLOCKS * PHACELLE_SCALE);
            float roundingForInput = lerp(ROUNDING_MIN, ROUNDING_MAX, clamp(heightFade + 0.5f, 0.0f, 1.0f));
            float combiMask = easeOut(smoothStart(slopeLen * SLOPE_ONSET_BASE, roundingForInput * SLOPE_ONSET_BASE));
            float ridgeMapFadeTarget = heightFade;
            float gullySlopeX = curSlopeX;
            float gullySlopeZ = curSlopeZ;
            float accumulatedHeightDelta = 0.0f;
            float roundingMult = 1.0f;
            float fadeTarget = 0.0f;
            for (int octave = 0; octave < OCTAVES; ++octave) {
               float gullyLen = (float)Math.sqrt(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ);
               float normGullyX = gullyLen > 1e-10f ? gullySlopeX / gullyLen : 0.0f;
               float normGullyZ = gullyLen > 1e-10f ? gullySlopeZ / gullyLen : 0.0f;
               samplePhacelleNoise(worldX * freq, worldZ * freq, normGullyX, normGullyZ, PHACELLE_SCALE, PHACELLE_OFFSET, PHACELLE_NORMALIZATION, SEED + octave,
                  phacelleOut, variant.tableTrig());
               float phacelleX = phacelleOut[0];
               float phacelleY = phacelleOut[1];
               float phacelleZ = phacelleOut[2];
               float phacelleW = phacelleOut[3];
               float sloping = Math.abs(phacelleY);
               float gradient = currentStrength * TAU * PHACELLE_SCALE * freq;
               float gullySign = Math.signum(phacelleY);
               gullySlopeX += gullySign * phacelleZ * gradient * GULLY_WEIGHT;
               gullySlopeZ += gullySign * phacelleW * gradient * GULLY_WEIGHT;
               float noiseTerm = phacelleX * GULLY_WEIGHT;
               float fadedGullyVal = lerp(fadeTarget, noiseTerm, combiMask);
               accumulatedHeightDelta += fadedGullyVal * currentStrength;
               fadeTarget = fadedGullyVal;
               float roundingOctave = lerp(ROUNDING_MIN, ROUNDING_MAX, clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
               float newMask = easeOut(smoothStart(sloping * SLOPE_ONSET_BASE, roundingOctave * SLOPE_ONSET_BASE));
               combiMask = powInv(combiMask, DETAIL) * newMask;
               ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
               ridgeMapCombiMask = Math.min(0.98f, ridgeMapCombiMask * easeOut(sloping * SLOPE_ONSET_OCTAVE));
               currentStrength *= GAIN;
               freq *= LACUNARITY;
               roundingMult *= 0.001f;
            }

            if (!Float.isFinite(accumulatedHeightDelta)) {
               continue;
            }

            cellSediment[idx] = clamp(cellSediment[idx] + ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
            if (flatGate <= 0.0f) {
               continue;
            }

            float sedimentModifier = Math.max(0.2f, 1.0F - cellSediment[idx]);
            float capSlope = Math.min(smoothSlope, 3.0f);
            float maxDepth = variant.depthPerSlope() * capSlope * SCALE_BLOCKS;
            float gatedBlocks = clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
            float limitedBlocks = gatedBlocks;
            if (limitedBlocks > 0.0f) {
               float headroom = (envHi[idx] - baseHeight) * wh + variant.margin();
               limitedBlocks = limitedBlocks * headroom / (limitedBlocks + headroom);
            }

            float deltaNormalized = limitedBlocks / WORLD_HEIGHT;
            float change = erosionFactor * deltaNormalized;
            heightErosion[idx] += change;
            cellHeight[idx] += change;
         }
      }

      for (int i = 0; i < length; i++) {
         boolean wasLand = pre[i] > WATER;
         boolean afterIsWater = cellHeight[i] <= WATER;
         if (wasLand && afterIsWater) {
            heightErosion[i] += GROUND - cellHeight[i];
            cellHeight[i] = GROUND;
         }
      }
   }

   private static void windowMax(float[] buf, float[] tmp, int size, int r) {
      int last = size - 1;
      for (int z = 0; z < size; ++z) {
         int row = z * size;
         for (int x = 0; x < size; ++x) {
            int lo = Math.max(0, x - r);
            int hi = Math.min(last, x + r);
            float m = buf[row + lo];
            for (int k = lo + 1; k <= hi; ++k) {
               float v = buf[row + k];
               if (v > m) m = v;
            }
            tmp[row + x] = m;
         }
      }

      for (int z = 0; z < size; ++z) {
         int lo = Math.max(0, z - r);
         int hi = Math.min(last, z + r);
         for (int x = 0; x < size; ++x) {
            float m = tmp[lo * size + x];
            for (int k = lo + 1; k <= hi; ++k) {
               float v = tmp[k * size + x];
               if (v > m) m = v;
            }
            buf[z * size + x] = m;
         }
      }
   }

   private static void windowMin(float[] buf, float[] tmp, int size, int r) {
      int last = size - 1;
      for (int z = 0; z < size; ++z) {
         int row = z * size;
         for (int x = 0; x < size; ++x) {
            int lo = Math.max(0, x - r);
            int hi = Math.min(last, x + r);
            float m = buf[row + lo];
            for (int k = lo + 1; k <= hi; ++k) {
               float v = buf[row + k];
               if (v < m) m = v;
            }
            tmp[row + x] = m;
         }
      }

      for (int z = 0; z < size; ++z) {
         int lo = Math.max(0, z - r);
         int hi = Math.min(last, z + r);
         for (int x = 0; x < size; ++x) {
            float m = tmp[lo * size + x];
            for (int k = lo + 1; k <= hi; ++k) {
               float v = tmp[k * size + x];
               if (v < m) m = v;
            }
            buf[z * size + x] = m;
         }
      }
   }

   private static void boxBlur(float[] buf, float[] tmp, int size, int r) {
      int last = size - 1;
      for (int z = 0; z < size; ++z) {
         int row = z * size;
         for (int x = 0; x < size; ++x) {
            int lo = Math.max(0, x - r);
            int hi = Math.min(last, x + r);
            float sum = 0.0f;
            for (int k = lo; k <= hi; ++k) {
               sum += buf[row + k];
            }
            tmp[row + x] = sum / (hi - lo + 1);
         }
      }

      for (int z = 0; z < size; ++z) {
         int lo = Math.max(0, z - r);
         int hi = Math.min(last, z + r);
         float inv = 1.0f / (hi - lo + 1);
         for (int x = 0; x < size; ++x) {
            float sum = 0.0f;
            for (int k = lo; k <= hi; ++k) {
               sum += tmp[k * size + x];
            }
            buf[z * size + x] = sum * inv;
         }
      }
   }

   /** FreeTerraForged's NoiseUtil.sin: SIN[(int)(r * radToIndex) & SIN_MASK]. */
   private static float tableSin(float r) {
      return ErosionKernels.SIN[(int)(r * ErosionKernels.RAD_TO_INDEX) & 4095];
   }

   private static FloatBuffer floats(float[] values) {
      return MemoryUtil.memAllocFloat(values.length).put(0, values);
   }

   private static boolean same(float a, float b) {
      return Float.isNaN(a) ? Float.isNaN(b) : Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
   }

   private static float modify(float height, float em, float edge, float river) {
      float strengthModifier = 1.0F;
      if (em != 1.0F) {
         float alpha = map(edge, 0.0F, 0.15F, 0.15F);
         strengthModifier = lerp(1.0F, em, alpha);
      }

      if (river < 0.1F) {
         strengthModifier *= map(river, 0.002F, 0.1F, 0.098F);
      }

      float valueModifier = height > MOD_MAX ? 1.0F : (height < MOD_MIN ? 0.0F : (height - MOD_MIN) / MOD_RANGE);
      return valueModifier * strengthModifier * 1.0F;
   }

   /** FreeTerraForged's Erosion.apply, its cells as arrays. */
   private static void reference(int tileSize, float[] cellHeight, boolean[] erosionMask, float[] emod, float[] edge, float[] river, float[] heightErosion,
         float[] sediment, float[] preErosionHeight, int worldBlockX, int worldBlockZ) {
      int count = cellHeight.length;
      float[] heights = new float[count];
      float[] relaxed = new float[count];
      float[] relaxMod = new float[count];
      float[] cliffZone = new float[count];
      float[] slopeX = new float[count];
      float[] slopeZ = new float[count];
      float[] phacelleOut = new float[4];
      for (int i = 0; i < count; i++) {
         preErosionHeight[i] = cellHeight[i];
         heights[i] = cellHeight[i];
      }

      relaxCliffs(cellHeight, erosionMask, emod, edge, river, heightErosion, heights, relaxed, relaxMod, cliffZone, tileSize);
      float wh = WORLD_HEIGHT;
      int last = tileSize - 1;
      for (int z = 0; z < tileSize; z++) {
         int row = z * tileSize;
         slopeX[row] = (heights[row + 1] - heights[row]) * wh;
         for (int x = 1; x < last; x++) {
            int idx = row + x;
            float c = heights[idx];
            slopeX[idx] = minmod(c - heights[idx - 1], heights[idx + 1] - c) * wh;
         }

         slopeX[row + last] = (heights[row + last] - heights[row + last - 1]) * wh;
         if (z == 0) {
            int nextRow = row + tileSize;
            for (int x = 0; x < tileSize; x++) {
               slopeZ[row + x] = (heights[nextRow + x] - heights[row + x]) * wh;
            }
         } else if (z == last) {
            int prevRow = row - tileSize;
            for (int x = 0; x < tileSize; x++) {
               slopeZ[row + x] = (heights[row + x] - heights[prevRow + x]) * wh;
            }
         } else {
            int prevRow = row - tileSize;
            int nextRow = row + tileSize;
            for (int x = 0; x < tileSize; x++) {
               float c = heights[row + x];
               slopeZ[row + x] = minmod(c - heights[prevRow + x], heights[nextRow + x] - c) * wh;
            }
         }
      }

      float slopeGateRange = Math.max(1.0E-5F, 0.45000002F);
      for (int z = 0; z < tileSize; z++) {
         int row = z * tileSize;
         float worldZ = (float)(worldBlockZ + z);
         for (int x = 0; x < tileSize; x++) {
            int idx = row + x;
            if (erosionMask[idx]) {
               continue;
            }

            float baseHeight = heights[idx];
            if (!Float.isFinite(baseHeight)) {
               continue;
            }

            float curSlopeX = slopeX[idx];
            float curSlopeZ = slopeZ[idx];
            float slopeLen = (float)Math.sqrt((double)(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1.0E-10F));
            float slopeT = clamp((slopeLen - 0.15F) / slopeGateRange, 0.0F, 1.0F);
            float flatGate = slopeT * slopeT * (3.0F - 2.0F * slopeT);
            if (flatGate <= 0.0F) {
               continue;
            }

            float erosionFactor = modify(cellHeight[idx], emod[idx], edge[idx], river[idx]);
            if (erosionFactor == 0.0F) {
               continue;
            }

            float worldX = (float)(worldBlockX + x);
            float zone = cliffZone[idx];
            float heightFade = clamp((baseHeight - 0.5F) * 2.0F, -1.0F, 1.0F);
            float currentStrength = STRENGTH * SCALE_BLOCKS;
            float freq = 1.0F / (SCALE_BLOCKS * PHACELLE_SCALE);
            float roundingForInput = lerp(ROUNDING_MIN, ROUNDING_MAX, clamp(heightFade + 0.5F, 0.0F, 1.0F));
            float combiMask = easeOut(smoothStart(slopeLen * SLOPE_ONSET_BASE, roundingForInput * SLOPE_ONSET_BASE));
            float ridgeMapCombiMask = easeOut(slopeLen * 0.01F * SLOPE_ONSET_RIDGE);
            float ridgeMapFadeTarget = heightFade;
            float gullySlopeX = curSlopeX;
            float gullySlopeZ = curSlopeZ;
            float accumulatedHeightDelta = 0.0F;
            float roundingMult = 1.0F;
            float fadeTarget = 0.0F;
            for (int octave = 0; octave < OCTAVES; octave++) {
               float gullyLen = (float)Math.sqrt((double)(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ));
               float normGullyX = gullyLen > 1.0E-10F ? gullySlopeX / gullyLen : 0.0F;
               float normGullyZ = gullyLen > 1.0E-10F ? gullySlopeZ / gullyLen : 0.0F;
               samplePhacelleNoise(worldX * freq, worldZ * freq, normGullyX, normGullyZ, PHACELLE_SCALE, PHACELLE_OFFSET, PHACELLE_NORMALIZATION, SEED + octave,
                  phacelleOut);
               float phacelleX = phacelleOut[0];
               float phacelleY = phacelleOut[1];
               float phacelleZ = phacelleOut[2];
               float phacelleW = phacelleOut[3];
               float sloping = Math.abs(phacelleY);
               float gradient = currentStrength * TAU * PHACELLE_SCALE * freq;
               float gullySign = Math.signum(phacelleY);
               gullySlopeX += gullySign * phacelleZ * gradient * GULLY_WEIGHT;
               gullySlopeZ += gullySign * phacelleW * gradient * GULLY_WEIGHT;
               float noiseTerm = phacelleX * GULLY_WEIGHT;
               float fadedGullyVal = lerp(fadeTarget, noiseTerm, combiMask);
               accumulatedHeightDelta += fadedGullyVal * currentStrength;
               fadeTarget = fadedGullyVal;
               float roundingOctave = lerp(ROUNDING_MIN, ROUNDING_MAX, clamp(phacelleX + 0.5F, 0.0F, 1.0F)) * roundingMult;
               float newMask = easeOut(smoothStart(sloping * SLOPE_ONSET_BASE, roundingOctave * SLOPE_ONSET_BASE));
               combiMask = powInv(combiMask, DETAIL) * newMask;
               ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
               ridgeMapCombiMask *= easeOut(sloping * SLOPE_ONSET_OCTAVE);
               currentStrength *= GAIN;
               freq *= LACUNARITY;
               roundingMult *= 0.001F;
            }

            if (Float.isFinite(accumulatedHeightDelta)) {
               float sedimentUpdate = ridgeMapFadeTarget * (1.0F - ridgeMapCombiMask) * (1.0F - zone);
               sediment[idx] = clamp(sediment[idx] + sedimentUpdate, -1.0F, 1.0F);
               float sedimentModifier = lerp(Math.max(0.2F, 1.0F - sediment[idx]), 1.0F, zone);
               float capSlope = Math.min(slopeLen, 3.0F);
               float maxDepth = 0.5F * capSlope * SCALE_BLOCKS;
               float localLo = baseHeight;
               float localHi = baseHeight;
               if (zone > 0.0F) {
                  int zMin = Math.max(0, z - 2);
                  int zMax = Math.min(last, z + 2);
                  int xMin = Math.max(0, x - 2);
                  int xMax = Math.min(last, x + 2);
                  for (int wz = zMin; wz <= zMax; wz++) {
                     int wRow = wz * tileSize;
                     for (int wx = xMin; wx <= xMax; wx++) {
                        float h = heights[wRow + wx];
                        if (h < localLo) {
                           localLo = h;
                        }

                        if (h > localHi) {
                           localHi = h;
                        }
                     }
                  }

                  float reliefBlocks = (localHi - localLo) * wh;
                  float reliefDepth = Math.min(maxDepth, 0.35F * reliefBlocks);
                  maxDepth = lerp(maxDepth, reliefDepth, zone);
               }

               float gatedBlocks = clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
               float deltaNormalized = gatedBlocks / WORLD_HEIGHT;
               float change = erosionFactor * deltaNormalized;
               if (zone > 0.0F) {
                  float bounded = clamp(baseHeight + change, localLo, localHi) - baseHeight;
                  change = lerp(change, bounded, zone);
               }

               heightErosion[idx] += change;
               cellHeight[idx] += change;
            }
         }
      }

      for (int i = 0; i < count; i++) {
         boolean wasLand = preErosionHeight[i] > WATER;
         boolean afterIsWater = cellHeight[i] <= WATER;
         if (wasLand && afterIsWater) {
            heightErosion[i] = heightErosion[i] + (GROUND - cellHeight[i]);
            cellHeight[i] = GROUND;
         }
      }
   }

   private static void relaxCliffs(float[] cellHeight, boolean[] erosionMask, float[] emod, float[] edge, float[] river, float[] heightErosion, float[] heights,
         float[] next, float[] mods, float[] zone, int mapSize) {
      float minRelief = 3.0F / WORLD_HEIGHT;
      float maxRelief = 8.0F / WORLD_HEIGHT;
      float invRange = 1.0F / Math.max(1.0E-5F, maxRelief - minRelief);
      int total = mapSize * mapSize;
      for (int i = 0; i < total; i++) {
         mods[i] = !erosionMask[i] && Float.isFinite(heights[i]) ? modify(cellHeight[i], emod[i], edge[i], river[i]) : 0.0F;
      }

      Arrays.fill(zone, 0, total, 0.0F);
      for (int pass = 0; pass < 3; pass++) {
         for (int z = 0; z < mapSize; z++) {
            int row = z * mapSize;
            int zPrevRow = Math.max(0, z - 1) * mapSize;
            int zNextRow = Math.min(mapSize - 1, z + 1) * mapSize;
            for (int x = 0; x < mapSize; x++) {
               int idx = row + x;
               float c = heights[idx];
               float m = mods[idx];
               if (m == 0.0F) {
                  next[idx] = c;
               } else {
                  float l = heights[row + Math.max(0, x - 1)];
                  float r = heights[row + Math.min(mapSize - 1, x + 1)];
                  float d = heights[zPrevRow + x];
                  float u = heights[zNextRow + x];
                  float avg = 0.25F * (l + r + d + u);
                  float hi = Math.max(Math.max(c, l), Math.max(Math.max(r, d), u));
                  float lo = Math.min(Math.min(c, l), Math.min(Math.min(r, d), u));
                  float t = clamp((hi - lo - minRelief) * invRange, 0.0F, 1.0F);
                  float mask = t * t * (3.0F - 2.0F * t);
                  if (mask > zone[idx]) {
                     zone[idx] = mask;
                  }

                  float delta = (avg - c) * mask * 0.4F;
                  next[idx] = c + m * delta;
               }
            }
         }

         System.arraycopy(next, 0, heights, 0, total);
      }

      for (int i = 0; i < total; i++) {
         float h = heights[i];
         if (mods[i] != 0.0F && Float.isFinite(h)) {
            heightErosion[i] = heightErosion[i] + (h - cellHeight[i]);
            cellHeight[i] = h;
         }
      }

      dilateZone(zone, next, mapSize, 3);
   }

   private static void dilateZone(float[] zone, float[] tmp, int size, int radius) {
      int last = size - 1;
      float invReach = 1.0F / ((float)radius + 1.0F);
      for (int z = 0; z < size; z++) {
         int row = z * size;
         for (int x = 0; x < size; x++) {
            float best = 0.0F;
            for (int k = -radius; k <= radius; k++) {
               int xx = Math.min(last, Math.max(0, x + k));
               float v = zone[row + xx] * (1.0F - (float)Math.abs(k) * invReach);
               if (v > best) {
                  best = v;
               }
            }

            tmp[row + x] = best;
         }
      }

      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            float best = 0.0F;
            for (int kx = -radius; kx <= radius; kx++) {
               int zz = Math.min(last, Math.max(0, z + kx));
               float v = tmp[zz * size + x] * (1.0F - (float)Math.abs(kx) * invReach);
               if (v > best) {
                  best = v;
               }
            }

            zone[z * size + x] = best;
         }
      }
   }

   private static float minmod(float back, float fwd) {
      if (back * fwd <= 0.0F) {
         return 0.0F;
      }

      return Math.abs(back) < Math.abs(fwd) ? back : fwd;
   }

   private static void samplePhacelleNoise(float px, float pz, float normDirX, float normDirZ, float freq, float offset, float normalization, int seed,
         float[] result) {
      samplePhacelleNoise(px, pz, normDirX, normDirZ, freq, offset, normalization, seed, result, false);
   }

   /** tableTrig: FreeTerraForged 1.0.0013's (far cell points skipped; NoiseUtil's table sin / cos, as ErosionKernels.SIN holds it). */
   private static void samplePhacelleNoise(float px, float pz, float normDirX, float normDirZ, float freq, float offset, float normalization, int seed,
         float[] result, boolean tableTrig) {
      float sideDirX = -normDirZ * freq * TAU;
      float sideDirZ = normDirX * freq * TAU;
      offset *= TAU;
      int pIntX = (int)Math.floor((double)px);
      int pIntZ = (int)Math.floor((double)pz);
      float pFracX = px - (float)pIntX;
      float pFracZ = pz - (float)pIntZ;
      float phaseDirX = 0.0F;
      float phaseDirZ = 0.0F;
      float weightSum = 0.0F;
      for (int i = -1; i <= 2; i++) {
         for (int j = -1; j <= 2; j++) {
            int cellX = pIntX + i;
            int cellZ = pIntZ + j;
            float hashX = hash(cellX, cellZ, seed) * 0.5F;
            float hashZ = hash(cellX, cellZ, seed + 1013) * 0.5F;
            float vecX = pFracX - (float)i - hashX;
            float vecZ = pFracZ - (float)j - hashZ;
            float sqrDist = vecX * vecX + vecZ * vecZ;
            if (tableTrig) {
               if (sqrDist >= 2.25f) {
                  continue;
               }

               float weight = (float)Math.exp((double)(-sqrDist * 2.0F)) - 0.01111F;
               if (weight <= 0.0f) {
                  continue;
               }

               weightSum += weight;
               float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
               phaseDirX += tableSin(waveInput + 1.5708f) * weight;
               phaseDirZ += tableSin(waveInput) * weight;
               continue;
            }

            float weight = Math.max(0.0F, (float)Math.exp((double)(-sqrDist * 2.0F)) - 0.01111F);
            weightSum += weight;
            float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
            phaseDirX += (float)Math.cos((double)waveInput) * weight;
            phaseDirZ += (float)Math.sin((double)waveInput) * weight;
         }
      }

      float invWeight = 1.0F / Math.max(weightSum, 1.0E-5F);
      float interpX = phaseDirX * invWeight;
      float interpZ = phaseDirZ * invWeight;
      float magnitude = (float)Math.sqrt((double)(interpX * interpX + interpZ * interpZ));
      magnitude = Math.max(1.0F - normalization, magnitude);
      result[0] = interpX / magnitude;
      result[1] = interpZ / magnitude;
      result[2] = sideDirX / (freq * TAU + 1.0E-5F);
      result[3] = sideDirZ / (freq * TAU + 1.0E-5F);
   }

   private static float hash(int x, int z, int seed) {
      int n = x * 374761393 + z * 668265263 + seed * 144677219;
      n = (n ^ n >> 13) * 1274126177;
      return (float)((n ^ n >> 16) & 2147483647) / 2.1474836E9F - 0.5F;
   }

   private static float clamp(float value, float min, float max) {
      return value < min ? min : (value > max ? max : value);
   }

   private static float map(float value, float min, float max, float range) {
      float dif = clamp(value, min, max) - min;
      return dif >= range ? 1.0F : dif / range;
   }

   private static float lerp(float a, float b, float t) {
      return a + t * (b - a);
   }

   private static float easeOut(float t) {
      float v = 1.0F - clamp(t, 0.0F, 1.0F);
      return 1.0F - v * v;
   }

   private static float powInv(float t, float power) {
      return 1.0F - (float)Math.pow((double)(1.0F - clamp(t, 0.0F, 1.0F)), (double)power);
   }

   private static float smoothStart(float t, float smoothing) {
      return t >= smoothing ? t - 0.5F * smoothing : 0.5F * t * t / Math.max(smoothing, 1.0E-5F);
   }
}
