package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * GPU step G5.2a's reference: in a few overworld chunks, every climate sample of the biome step (the cell's quart
 * position and the target point FreeTerraForged's sampler gave) and every climate table search (the target in, the
 * projected target and the biome out), plus the CellSampler values the climate functions read on the chunk's 16 quart
 * columns (through the chunk's own noise chunk, at its first sample, as the sampler reads them). Off until {@link #enable}.
 */
public final class BiomeTap {
   /** A tapped chunk: samples (x, y, z quart, then 6 target longs each), searches (6 in, 6 out, the biome), and its inputs. */
   public static final class Tap extends BiomeInputs {
      public int[] sampleAt = new int[3 * 8192];
      public long[] sampled = new long[6 * 8192];
      public int samples;
      public long[] searchIn = new long[6 * 8192];
      public long[] searchOut = new long[6 * 8192];
      public Object[] searchBiome = new Object[8192];
      public boolean[] fallback = new boolean[8192];
      public int searches;
      boolean failed;
   }

   private static volatile Consumer<Tap> sink;
   private static volatile List<Object> cellSamplers;
   private static volatile RandomState overworld;
   private static int remaining;
   private static final ThreadLocal<Tap> CURRENT = new ThreadLocal<>();
   private static final ThreadLocal<ChunkAccess> CHUNK = new ThreadLocal<>();

   private BiomeTap() {
   }

   public static synchronized void enable(RandomState random, List<Object> samplers, int chunks, Consumer<Tap> consumer) {
      overworld = random;
      cellSamplers = samplers;
      remaining = chunks;
      sink = consumer;
   }

   private static synchronized boolean take() {
      if (remaining <= 0) {
         return false;
      }

      remaining--;
      return true;
   }

   /** At doCreateBiomes' start. */
   public static void begin(ChunkAccess chunk, RandomState random) {
      if (sink == null || random != overworld || !take()) {
         return;
      }

      Tap tap = new Tap();
      tap.chunkX = chunk.getPos().getMinBlockX();
      tap.chunkZ = chunk.getPos().getMinBlockZ();
      CURRENT.set(tap);
      CHUNK.set(chunk);
   }

   public static void sampled(Object sampler, int quartX, int quartY, int quartZ, Climate.TargetPoint target) {
      Tap tap = CURRENT.get();
      if (tap == null || tap.failed) {
         return;
      }

      try {
         if (tap.cells == null) {
            tap.read(CHUNK.get(), sampler, cellSamplers);
         }

         if (tap.samples == tap.sampleAt.length / 3) {
            tap.sampleAt = Arrays.copyOf(tap.sampleAt, tap.sampleAt.length * 2);
            tap.sampled = Arrays.copyOf(tap.sampled, tap.sampled.length * 2);
         }

         int i = tap.samples++;
         tap.sampleAt[i * 3] = quartX;
         tap.sampleAt[i * 3 + 1] = quartY;
         tap.sampleAt[i * 3 + 2] = quartZ;
         put(tap.sampled, i, target);
      } catch (RuntimeException | ReflectiveOperationException failure) {
         tap.failed = true;
         WorldgenAccelerator.LOG.warn("WGA GPU biome check: could not tap a sample: {}", failure.toString());
      }
   }

   public static void searched(Climate.TargetPoint in, Climate.TargetPoint out, Holder<Biome> biome, boolean usedFallback) {
      Tap tap = CURRENT.get();
      if (tap == null || tap.failed) {
         return;
      }

      if (tap.searches == tap.searchBiome.length) {
         tap.searchIn = Arrays.copyOf(tap.searchIn, tap.searchIn.length * 2);
         tap.searchOut = Arrays.copyOf(tap.searchOut, tap.searchOut.length * 2);
         tap.searchBiome = Arrays.copyOf(tap.searchBiome, tap.searchBiome.length * 2);
         tap.fallback = Arrays.copyOf(tap.fallback, tap.fallback.length * 2);
      }

      int i = tap.searches++;
      put(tap.searchIn, i, in);
      put(tap.searchOut, i, out);
      tap.searchBiome[i] = biome;
      tap.fallback[i] = usedFallback;
   }

   /** At doCreateBiomes' end: the tap goes to the check. */
   public static void end() {
      Tap tap = CURRENT.get();
      CURRENT.remove();
      CHUNK.remove();
      if (tap != null && !tap.failed) {
         // A chunk whose cells were sampled on other threads arrives with none (then the log says 0).
         if (tap.cells == null) {
            tap.cells = new double[1];
         }

         sink.accept(tap);
      }
   }

   private static void put(long[] into, int i, Climate.TargetPoint target) {
      into[i * 6] = target.temperature();
      into[i * 6 + 1] = target.humidity();
      into[i * 6 + 2] = target.continentalness();
      into[i * 6 + 3] = target.erosion();
      into[i * 6 + 4] = target.depth();
      into[i * 6 + 5] = target.weirdness();
   }
}
