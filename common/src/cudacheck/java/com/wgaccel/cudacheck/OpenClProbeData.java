package com.wgaccel.cudacheck;

import com.wgaccel.gpu.GpuSelfTest;
import com.wgaccel.gpu.VanillaKernels;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Makes {@link OpenClProbe}'s data on a PC with the build (`gradlew.bat openClProbeJar` runs it): the mod's CUDA sources
 * it translates (the self-test's float kernel, vanilla's noise), and vanilla's own NormalNoise values (the real Minecraft
 * classes) at fixed points, so the probe can check bit for bit on a PC without Minecraft or the mod's build.
 */
public final class OpenClProbeData {
   static final int MAGIC = 0x57474143;
   static final int POINTS = 4096;

   private OpenClProbeData() {
   }

   public static void main(String[] args) throws Exception {
      Path out = Path.of(args[0]);
      Files.createDirectories(out.getParent());
      List<NormalNoise> noises = List.of(
         NormalNoise.create(new XoroshiroRandomSource(1L), new NormalNoise.NoiseParameters(-7, 1.0, 1.0)),
         NormalNoise.create(new XoroshiroRandomSource(2L), new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 2.0, 2.0, 1.0, 1.0, 1.0, 0.0, 2.0, 0.0)),
         NormalNoise.create(new XoroshiroRandomSource(3L), new NormalNoise.NoiseParameters(-3, 1.0, 1.0, 1.0, 0.0)),
         NormalNoise.create(new XoroshiroRandomSource(4L), new NormalNoise.NoiseParameters(-1, 1.0, 0.0, 1.0)),
         NormalNoise.create(new XoroshiroRandomSource(5L), new NormalNoise.NoiseParameters(-10, 1.5, 0.0, 1.0, 0.0, 0.0, 0.0)));
      DoubleArrayList d = new DoubleArrayList();
      IntArrayList p = new IntArrayList();
      int[] offsets = new int[noises.size() * 2];
      for (int n = 0; n < noises.size(); n++) {
         NormalNoise noise = noises.get(n);
         offsets[n * 2] = d.size();
         offsets[n * 2 + 1] = p.size();
         d.add((Double)field(noise, "valueFactor"));
         pack((PerlinNoise)field(noise, "first"), d, p);
         pack((PerlinNoise)field(noise, "second"), d, p);
      }

      double[] xs = new double[POINTS];
      double[] ys = new double[POINTS];
      double[] zs = new double[POINTS];
      Random random = new Random(2L);
      for (int i = 0; i < POINTS; i++) {
         double range = i % 16 == 0 ? 6.0e7 : 2.0e5;
         xs[i] = (random.nextDouble() - 0.5) * range;
         ys[i] = (random.nextDouble() - 0.5) * (i % 16 == 0 ? range : 2.0e3);
         zs[i] = (random.nextDouble() - 0.5) * range;
      }

      try (DataOutputStream data = new DataOutputStream(new FileOutputStream(out.toFile()))) {
         data.writeInt(MAGIC);
         text(data, GpuSelfTest.probeSource());
         text(data, VanillaKernels.NOISE);
         data.writeInt(noises.size());
         array(data, d.toDoubleArray());
         array(data, p.toIntArray());
         array(data, offsets);
         data.writeInt(POINTS);
         array(data, xs);
         array(data, ys);
         array(data, zs);
         for (NormalNoise noise : noises) {
            for (int i = 0; i < POINTS; i++) {
               data.writeDouble(noise.getValue(xs[i], ys[i], zs[i]));
            }
         }
      }

      System.out.println("OpenCL probe data: " + out);
   }

   private static void text(DataOutputStream data, String text) throws java.io.IOException {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      data.writeInt(bytes.length);
      data.write(bytes);
   }

   private static void array(DataOutputStream data, double[] values) throws java.io.IOException {
      data.writeInt(values.length);
      for (double value : values) {
         data.writeDouble(value);
      }
   }

   private static void array(DataOutputStream data, int[] values) throws java.io.IOException {
      data.writeInt(values.length);
      for (int value : values) {
         data.writeInt(value);
      }
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
