package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.SurfaceCodegen;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Locale;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * I3: the surface prepass's warp column walk ({@link SurfaceCodegen#PREPASS_WARP}) against the old one-thread walk (copied
 * below from the production kernel) on made-up columns: runs of air, fluid, stone and other solids, tops above, inside and
 * below the chunk. `gradlew.bat cudaPrepassCheck`.
 */
public final class PrepassCheck {
   private static final String SOURCE = SurfaceCodegen.PREPASS_WARP + """
      extern "C" __global__ void serial(const unsigned char* kinds, int minY, int height, const int* tops, int wayBelowMinY, int* above, int* below, int* water) {
          int t = blockIdx.x * blockDim.x + threadIdx.x;
          if (t >= 256) return;
          int top = tops[t];
          for (int i = 0; i < height; i++) above[i * 256 + t] = 0;
          int i2 = 0;
          int j2 = (-2147483647 - 1);
          int k2 = 2147483647;
          for (int i3 = top; i3 >= minY; i3--) {
              int k = i3 >= minY && i3 < minY + height ? kinds[(i3 - minY) * 256 + t] : 0;
              if (k == 0) {
                  i2 = 0;
                  j2 = (-2147483647 - 1);
              } else if (k == 1) {
                  if (j2 == (-2147483647 - 1)) j2 = i3 + 1;
              } else {
                  if (k2 >= i3) {
                      k2 = wayBelowMinY;
                      for (int j3 = i3 - 1; j3 >= minY - 1; j3--) {
                          int kk = j3 >= minY && j3 < minY + height ? kinds[(j3 - minY) * 256 + t] : 0;
                          if (kk < 2) {
                              k2 = j3 + 1;
                              break;
                          }
                      }
                  }
                  i2++;
                  if (k == 2) {
                      int at = (i3 - minY) * 256 + t;
                      above[at] = i2;
                      below[at] = i3 - k2 + 1;
                      water[at] = j2;
                  }
              }
          }
      }
      extern "C" __global__ void warp(const unsigned char* kinds, int minY, int height, const int* tops, int* above, int* below, int* water) {
          int t = blockIdx.x * 8 + (threadIdx.x >> 5);
          if (t >= 256) return;
          prepassWarpScan(kinds, minY, height, tops[t], t, threadIdx.x & 31, above, below, water);
      }
      """;

   private PrepassCheck() {
   }

   public static void main(String[] args) throws Exception {
      int minY = -64;
      int height = 448;
      int cells = 256 * height;
      Random random = new Random(20261021L);
      long different = 0;
      long stone = 0;
      try (Gpu gpu = new Gpu(SOURCE, "prepass_check.cu")) {
         long kinds = gpu.allocate(cells);
         long tops = gpu.allocate(4L * 256);
         long[] serial = {gpu.allocate(4L * cells), gpu.allocate(4L * cells), gpu.allocate(4L * cells)};
         long[] warp = {gpu.allocate(4L * cells), gpu.allocate(4L * cells), gpu.allocate(4L * cells)};
         ByteBuffer kindBuffer = MemoryUtil.memAlloc(cells);
         IntBuffer topBuffer = MemoryUtil.memAllocInt(256);
         IntBuffer fill = MemoryUtil.memAllocInt(cells);
         IntBuffer[] a = {MemoryUtil.memAllocInt(cells), MemoryUtil.memAllocInt(cells), MemoryUtil.memAllocInt(cells)};
         IntBuffer[] b = {MemoryUtil.memAllocInt(cells), MemoryUtil.memAllocInt(cells), MemoryUtil.memAllocInt(cells)};
         float serialMs = 0;
         float warpMs = 0;
         int rounds = 200;
         for (int round = 0; round < rounds; round++) {
            for (int t = 0; t < 256; t++) {
               // Runs of random kinds and lengths; kind 3 is a solid that is not stone (counted, never written).
               int y = 0;
               while (y < height) {
                  int kind = random.nextInt(10) < 4 ? 2 : random.nextInt(4);
                  int length = 1 + random.nextInt(random.nextBoolean() ? 4 : 80);
                  for (int i = 0; i < length && y < height; i++, y++) {
                     kindBuffer.put(y * 256 + t, (byte)kind);
                  }
               }

               int pick = random.nextInt(8);
               topBuffer.put(t, pick == 0 ? minY - 1 - random.nextInt(5) : pick == 1 ? minY + height + random.nextInt(40) : minY + random.nextInt(height));
            }

            // Different garbage in each output first, so cells one walk leaves alone and the other writes are seen.
            for (int i = 0; i < cells; i++) {
               fill.put(i, 0x5a5a5a5a ^ i);
            }

            gpu.copyIn(kinds, kindBuffer);
            gpu.copyIn(tops, topBuffer);
            for (int j = 0; j < 3; j++) {
               gpu.copyIn(serial[j], fill);
               gpu.copyIn(warp[j], fill);
            }

            long start = System.nanoTime();
            gpu.run("serial", 1, 1, kinds, minY, height, tops, -2097152, serial[0], serial[1], serial[2]);
            serialMs += (System.nanoTime() - start) / 1.0e6F;
            start = System.nanoTime();
            gpu.run("warp", 32, 1, kinds, minY, height, tops, warp[0], warp[1], warp[2]);
            warpMs += (System.nanoTime() - start) / 1.0e6F;
            for (int j = 0; j < 3; j++) {
               gpu.download(serial[j], a[j]);
               gpu.download(warp[j], b[j]);
            }

            for (int i = 0; i < cells; i++) {
               stone += kindBuffer.get(i) == 2 && a[0].get(i) != 0 ? 1 : 0;
               for (int j = 0; j < 3; j++) {
                  if (a[j].get(i) != b[j].get(i)) {
                     if (different++ < 10) {
                        System.out.printf(Locale.ROOT, "round %d column %d y %d (kind %d, top %d): %s %d / warp %d%n", round, i & 255, minY + (i >> 8),
                           kindBuffer.get(i), topBuffer.get(i & 255), new String[] {"above", "below", "water"}[j], a[j].get(i), b[j].get(i));
                     }
                  }
               }
            }
         }

         System.out.printf(Locale.ROOT, "surface prepass, %d made-up chunks (%,d stone blocks): %s; one thread per column %.3f ms, a warp per column %.3f ms "
            + "(per chunk, with the launch)%n", rounds, stone, different == 0 ? "IDENTICAL" : different + " DIFFER", serialMs / rounds, warpMs / rounds);
      }
   }
}
