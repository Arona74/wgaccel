package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.mixin.access.ImprovedNoiseAccessor;
import com.wgaccel.mixin.access.NormalNoiseAccessor;
import com.wgaccel.mixin.access.PerlinNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G2.2 (config gpuMode CHECK): the overworld's 3D noise, kind by kind, on the GPU and by vanilla's own Java
 * at the same points, compared bit for bit and logged ("WGA GPU density check: ..."). Starts with NormalNoise, the
 * noise under every vanilla noise density function. Runs on its own thread at world start; changes nothing.
 */
public final class DensityCheck {
   private static final int POINTS = 4096;

   private DensityCheck() {
   }

   /**
    * The NormalNoise check, then every density function node as a tree root ({@link DensityTreeCheck}) around the
    * spawn, then whole chunks' final density as the noise fill works it out ({@link DensityChunkCheck}).
    */
   public static void start(List<NormalNoise> noises, List<Object> nodes, NoiseRouter router, Object oreRandom, boolean veins, int spawnX, int spawnZ, int minY,
         int height) {
      Thread thread = new Thread(() -> {
         try {
            normalNoise(noises);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU density check failed: {}", failure.toString());
         }

         try {
            DensityTreeCheck.run(nodes, spawnX, spawnZ, minY, height);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU density tree check failed: {}", failure.toString());
         }

         try {
            DensityChunkCheck.run(DensityCodegen.unwrap(router.finalDensity()), DensityCodegen.unwrap(router.veinToggle()), DensityCodegen.unwrap(router.veinRidged()),
               DensityCodegen.unwrap(router.veinGap()), DensityCodegen.unwrap(router.barrierNoise()), oreRandom, veins);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU density chunk check failed: {}", failure.toString());
         }
      }, "WGA GPU density check");
      thread.setDaemon(true);
      thread.start();
   }

   private static void normalNoise(List<NormalNoise> noises) {
      if (noises.isEmpty()) {
         WorldgenAccelerator.LOG.info("WGA GPU density check: no NormalNoise found");
         return;
      }

      DoubleArrayList d = new DoubleArrayList();
      IntArrayList p = new IntArrayList();
      IntBuffer offsets = MemoryUtil.memAllocInt(noises.size() * 2);
      for (int n = 0; n < noises.size(); n++) {
         NormalNoiseAccessor noise = (NormalNoiseAccessor)noises.get(n);
         offsets.put(n * 2, d.size()).put(n * 2 + 1, p.size());
         d.add(noise.wgaccel$valueFactor());
         pack(noise.wgaccel$first(), d, p);
         pack(noise.wgaccel$second(), d, p);
      }

      DoubleBuffer xs = MemoryUtil.memAllocDouble(POINTS);
      DoubleBuffer ys = MemoryUtil.memAllocDouble(POINTS);
      DoubleBuffer zs = MemoryUtil.memAllocDouble(POINTS);
      Random random = new Random(2L);
      for (int i = 0; i < POINTS; i++) {
         // Mostly world-sized coordinates (as the density functions scale them), some huge ones for PerlinNoise's wrap.
         double range = i % 16 == 0 ? 6.0e7 : 2.0e5;
         xs.put(i, (random.nextDouble() - 0.5) * range);
         ys.put(i, (random.nextDouble() - 0.5) * (i % 16 == 0 ? range : 2.0e3));
         zs.put(i, (random.nextDouble() - 0.5) * range);
      }

      DoubleBuffer dBuffer = MemoryUtil.memAllocDouble(d.size()).put(0, d.toDoubleArray());
      IntBuffer pBuffer = MemoryUtil.memAllocInt(Math.max(1, p.size())).put(0, p.toIntArray());
      int values = noises.size() * POINTS;
      DoubleBuffer out = MemoryUtil.memAllocDouble(values);
      try (Gpu gpu = new Gpu(VanillaKernels.NOISE, "vanilla_noise.cu")) {
         long start = System.nanoTime();
         long result = gpu.allocate(8L * values);
         gpu.run("mcNormalCheck", (values + 255) / 256, 1, dBuffer, pBuffer, offsets, noises.size(), xs, ys, zs, POINTS, result);
         gpu.download(result, out);
         double gpuMs = (System.nanoTime() - start) / 1.0e6;
         long cpuStart = System.nanoTime();
         int different = 0;
         String first = null;
         for (int n = 0; n < noises.size(); n++) {
            NormalNoise noise = noises.get(n);
            for (int i = 0; i < POINTS; i++) {
               double expected = noise.getValue(xs.get(i), ys.get(i), zs.get(i));
               double actual = out.get(n * POINTS + i);
               boolean same = Double.isNaN(expected) ? Double.isNaN(actual) : Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual);
               if (!same) {
                  if (first == null) {
                     first = String.format(Locale.ROOT, "noise %d (%s) at (%s, %s, %s): java %s, gpu %s", n, noise.parameters(), xs.get(i), ys.get(i), zs.get(i),
                        expected, actual);
                  }

                  different++;
               }
            }
         }

         double cpuMs = (System.nanoTime() - cpuStart) / 1.0e6;
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU density check: NormalNoise, %d noises x %,d points = %,d values: %s; GPU %.2f ms (with copies), Java %.0f ms (one thread)",
            noises.size(), POINTS, values, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER", gpuMs, cpuMs));
         if (first != null) {
            WorldgenAccelerator.LOG.warn("WGA GPU density check: NormalNoise first difference: {}", first);
         }
      } finally {
         for (java.nio.Buffer buffer : new java.nio.Buffer[] {offsets, xs, ys, zs, dBuffer, pBuffer, out}) {
            MemoryUtil.memFree(buffer);
         }
      }
   }

   /** A PerlinNoise as VanillaKernels packs it: factors, octave count, then per octave its amplitude, offsets and permutation. */
   private static void pack(PerlinNoise perlin, DoubleArrayList d, IntArrayList p) {
      PerlinNoiseAccessor access = (PerlinNoiseAccessor)perlin;
      ImprovedNoise[] levels = access.wgaccel$noiseLevels();
      DoubleList amplitudes = access.wgaccel$amplitudes();
      d.add(access.wgaccel$lowestFreqInputFactor());
      d.add(access.wgaccel$lowestFreqValueFactor());
      d.add(levels.length);
      for (int i = 0; i < levels.length; i++) {
         ImprovedNoise level = levels[i];
         d.add(amplitudes.getDouble(i));
         d.add(level == null ? 0.0 : level.xo);
         d.add(level == null ? 0.0 : level.yo);
         d.add(level == null ? 0.0 : level.zo);
         d.add(level == null ? 0.0 : 1.0);
         byte[] permutation = level == null ? null : ((ImprovedNoiseAccessor)(Object)level).wgaccel$p();
         for (int k = 0; k < 256; k++) {
            p.add(permutation == null ? 0 : permutation[k] & 0xFF);
         }
      }
   }
}
