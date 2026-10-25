package com.wgaccel.cudacheck;

import com.wgaccel.gpu.Gpu;
import com.wgaccel.gpu.VanillaKernels;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.lwjgl.system.MemoryUtil;

/**
 * Outside the game (`gradlew.bat cudaVanillaNoiseCheck`): vanilla's own NormalNoise (the real Minecraft classes) with
 * a few vanilla-like settings, against {@link VanillaKernels} on the GPU, compared bit for bit.
 */
public final class VanillaNoiseCheck {
   private VanillaNoiseCheck() {
   }

   public static void main(String[] args) throws Exception {
      List<NormalNoise> noises = List.of(
         NormalNoise.create(new XoroshiroRandomSource(1L), new NormalNoise.NoiseParameters(-7, 1.0, 1.0)),
         NormalNoise.create(new XoroshiroRandomSource(2L), new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 2.0, 2.0, 1.0, 1.0, 1.0, 0.0, 2.0, 0.0)),
         NormalNoise.create(new XoroshiroRandomSource(3L), new NormalNoise.NoiseParameters(-3, 1.0, 1.0, 1.0, 0.0)),
         NormalNoise.create(new XoroshiroRandomSource(4L), new NormalNoise.NoiseParameters(-1, 1.0, 0.0, 1.0)),
         NormalNoise.create(new XoroshiroRandomSource(5L), new NormalNoise.NoiseParameters(-10, 1.5, 0.0, 1.0, 0.0, 0.0, 0.0)));
      DoubleArrayList d = new DoubleArrayList();
      IntArrayList p = new IntArrayList();
      int points = 4096;
      IntBuffer offsets = MemoryUtil.memAllocInt(noises.size() * 2);
      for (int n = 0; n < noises.size(); n++) {
         NormalNoise noise = noises.get(n);
         offsets.put(n * 2, d.size()).put(n * 2 + 1, p.size());
         d.add((Double)field(noise, "valueFactor"));
         pack((PerlinNoise)field(noise, "first"), d, p);
         pack((PerlinNoise)field(noise, "second"), d, p);
      }

      DoubleBuffer xs = MemoryUtil.memAllocDouble(points);
      DoubleBuffer ys = MemoryUtil.memAllocDouble(points);
      DoubleBuffer zs = MemoryUtil.memAllocDouble(points);
      Random random = new Random(2L);
      for (int i = 0; i < points; i++) {
         double range = i % 16 == 0 ? 6.0e7 : 2.0e5;
         xs.put(i, (random.nextDouble() - 0.5) * range);
         ys.put(i, (random.nextDouble() - 0.5) * (i % 16 == 0 ? range : 2.0e3));
         zs.put(i, (random.nextDouble() - 0.5) * range);
      }

      int values = noises.size() * points;
      DoubleBuffer out = MemoryUtil.memAllocDouble(values);
      try (Gpu gpu = new Gpu(VanillaKernels.NOISE, "vanilla_noise.cu")) {
         long result = gpu.allocate(8L * values);
         gpu.run("mcNormalCheck", (values + 255) / 256, 1, MemoryUtil.memAllocDouble(d.size()).put(0, d.toDoubleArray()),
            MemoryUtil.memAllocInt(p.size()).put(0, p.toIntArray()), offsets, noises.size(), xs, ys, zs, points, result);
         gpu.download(result, out);
      }

      int different = 0;
      for (int n = 0; n < noises.size(); n++) {
         for (int i = 0; i < points; i++) {
            double expected = noises.get(n).getValue(xs.get(i), ys.get(i), zs.get(i));
            double actual = out.get(n * points + i);
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual) && different++ < 5) {
               System.out.printf(Locale.ROOT, "noise %d at (%s, %s, %s): java %s, gpu %s%n", n, xs.get(i), ys.get(i), zs.get(i), expected, actual);
            }
         }
      }

      System.out.printf(Locale.ROOT, "NormalNoise: %d noises x %,d points: %s%n", noises.size(), points, different == 0 ? "BIT-IDENTICAL" : different + " DIFFER");
   }

   private static void pack(PerlinNoise perlin, DoubleArrayList d, IntArrayList p) throws ReflectiveOperationException {
      ImprovedNoise[] levels = (ImprovedNoise[])field(perlin, "noiseLevels");
      DoubleList amplitudes = (DoubleList)field(perlin, "amplitudes");
      d.add((Double)field(perlin, "lowestFreqInputFactor"));
      d.add((Double)field(perlin, "lowestFreqValueFactor"));
      d.add(levels.length);
      for (int i = 0; i < levels.length; i++) {
         ImprovedNoise level = levels[i];
         d.add(amplitudes.getDouble(i));
         d.add(level == null ? 0.0 : level.xo);
         d.add(level == null ? 0.0 : level.yo);
         d.add(level == null ? 0.0 : level.zo);
         d.add(level == null ? 0.0 : 1.0);
         byte[] permutation = level == null ? null : (byte[])field(level, "p");
         for (int k = 0; k < 256; k++) {
            p.add(permutation == null ? 0 : permutation[k] & 0xFF);
         }
      }
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
   }
}
