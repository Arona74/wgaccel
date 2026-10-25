package com.wgaccel.gpu;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gen.SurfaceTap;
import com.wgaccel.platform.Platform;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G3.2 (config gpuSurface CHECK): the surface rule tree on the GPU ({@link SurfaceCodegen}), every rule call
 * of the first {@link #CHUNKS} chunks recomputed from the context values the game had ({@link SurfaceTap}) and compared
 * with the game's result: "WGA GPU surface rule check". The source is saved as logs/wgaccel-surface-rules.cu. Starts at
 * the overworld's first surface step; changes nothing.
 */
public final class SurfaceCheck {
   private static final int CHUNKS = 16;
   private static final AtomicBoolean STARTED = new AtomicBoolean();
   private static volatile RandomState overworld;

   private SurfaceCheck() {
   }

   /** At a chunk's surface step: the first overworld one starts the check on its own thread. */
   public static void maybeStart(RandomState random, WorldGenerationContext context, Registry<Biome> biomes, Object system, Object rules) {
      if (random != overworld || STARTED.get() || Config.GPU_SURFACE.get() != Config.GpuFilterMode.CHECK || Config.GPU_MODE.get() == Config.GpuMode.OFF || !Lwjgl.PRESENT
         || STARTED.getAndSet(true)) {
         return;
      }

      Thread thread = new Thread(() -> {
         try {
            run(random, context, biomes, system, rules);
         } catch (DensityCodegen.Unsupported unsupported) {
            com.wgaccel.WgaChat.warnOff("WGA GPU surface rule check off: {} has no GPU version", unsupported.kind);
         } catch (Throwable failure) {
            WorldgenAccelerator.LOG.warn("WGA GPU surface rule check failed: {}", failure.toString());
         }
      }, "WGA GPU surface check");
      thread.setDaemon(true);
      thread.start();
   }

   /** At server start: the overworld's random state (only its chunks are checked); a new world may check again. */
   public static void reset(RandomState overworldRandom) {
      overworld = overworldRandom;
      STARTED.set(false);
   }

   public static boolean isOverworld(RandomState random) {
      return random == overworld;
   }

   private static void run(RandomState random, WorldGenerationContext context, Registry<Biome> biomes, Object system, Object rules) throws Exception {
      SurfaceCodegen codegen = new SurfaceCodegen(random, context, biomes, system);
      codegen.generate(rules);
      String source = codegen.source();
      try {
         Files.writeString(Platform.logs().resolve("wgaccel-surface-rules.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      long compileStart = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "surface_rules.cu")) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface rule check: rule tree of %s, CUDA source %,d KB, compiled and loaded in %.0f ms; waiting for %d chunks",
            codegen.kinds(), source.length() / 1024, (System.nanoTime() - compileStart) / 1.0e6, CHUNKS));
         double[] noiseData = codegen.noiseData();
         int[] permutations = codegen.permutations();
         long dnd = gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData));
         long dnp = gpu.upload(MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations));
         LinkedBlockingQueue<SurfaceTap.Tap> taps = new LinkedBlockingQueue<>();
         SurfaceTap.enable(codegen, biomes, CHUNKS, taps::add);
         Inputs inputs = null;
         Strata strata = null;
         Loop loop = null;
         long calls = 0;
         long different = 0;
         int chunks = 0;
         int logged = 0;
         while (chunks < CHUNKS) {
            SurfaceTap.Tap tap = taps.poll(30, TimeUnit.MINUTES);
            if (tap == null) {
               break;
            }

            long start = System.nanoTime();
            int n = tap.count;
            long dout = gpu.allocate(4L * Math.max(1, n));
            gpu.run("surfaceCheck", (n + 255) / 256, 1, MemoryUtil.memAllocInt(Math.max(1, n * SurfaceTap.FIELDS)).put(0, tap.calls, 0, n * SurfaceTap.FIELDS), n,
               MemoryUtil.memAllocDouble(256).put(0, tap.secondary), MemoryUtil.memAllocFloat(tap.strataDepth.length).put(0, tap.strataDepth),
               MemoryUtil.memAllocInt(tap.strataState.length).put(0, tap.strataState), MemoryUtil.memAllocInt(256).put(0, tap.strataCount), SurfaceTap.STRATA_MAX, dnd, dnp,
               dout);
            IntBuffer out = MemoryUtil.memAllocInt(Math.max(1, n));
            gpu.download(dout, out);
            int differ = 0;
            String first = null;
            for (int i = 0; i < n; i++) {
               if (out.get(i) != tap.results[i]) {
                  differ++;
                  if (first == null) {
                     int at = i * SurfaceTap.FIELDS;
                     first = String.format(Locale.ROOT, "block (%d, %d, %d) stone above %d below %d, water %d, depth %d, biome %d, cold %d, steep %d, min surface %d: game %s, gpu %s",
                        tap.calls[at], tap.calls[at + 1], tap.calls[at + 2], tap.calls[at + 3], tap.calls[at + 4], tap.calls[at + 5], tap.calls[at + 6], tap.calls[at + 7],
                        tap.calls[at + 8], tap.calls[at + 9], tap.calls[at + 10], codegen.stateAt(tap.results[i]), codegen.stateAt(out.get(i)));
                  }
               }
            }

            MemoryUtil.memFree(out);
            try {
               if (inputs == null) {
                  inputs = new Inputs(new SurfaceInputs(system, biomes, tap.zoomSeed));
               }

               inputs.check(tap, chunks);
            } catch (DensityCodegen.Unsupported unsupported) {
               com.wgaccel.WgaChat.warnOff("WGA GPU surface input check off: {} has no GPU version", unsupported.kind);
            } catch (Throwable failure) {
               WorldgenAccelerator.LOG.warn("WGA GPU surface input check failed: {}", failure.toString());
            }

            try {
               if (strata == null && tap.strataSource != null) {
                  strata = new Strata(new StrataGpu(tap.strataSource, codegen));
               }

               if (strata != null) {
                  strata.check(tap, chunks, codegen);
                  if (inputs != null) {
                     if (loop == null) {
                        loop = new Loop(codegen, inputs, biomes);
                     }

                     loop.check(tap, strata, chunks, codegen);
                  }
               }
            } catch (DensityCodegen.Unsupported | NoiseCodegen.Unsupported unsupported) {
               com.wgaccel.WgaChat.warnOff("WGA GPU strata check off: {} has no GPU version", unsupported.getMessage());
               strata = null;
            } catch (Throwable failure) {
               WorldgenAccelerator.LOG.warn("WGA GPU strata check failed: {}", failure.toString());
            }

            chunks++;
            calls += n;
            different += differ;
            if (logged++ < 4 || differ > 0) {
               WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface rule check: chunk [%d, %d]: %,d rule calls: %s; GPU %.2f ms (with copies)",
                  tap.chunkX(), tap.chunkZ(), n, differ == 0 ? "IDENTICAL" : differ + " DIFFER, first " + first, (System.nanoTime() - start) / 1.0e6));
            }
         }

         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface rule check: %d chunks, %,d rule calls: %s", chunks, calls,
            different == 0 ? "IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", different)));
         if (inputs != null) {
            inputs.total(chunks);
            inputs.gpu.close();
         }

         if (loop != null) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
               "WGA GPU surface loop check: %d chunks compared, %,d blocks: %s; %,d set by the rules; %d chunks left to the CPU (eroded badlands or frozen ocean columns, or unsure strata)",
               loop.chunks, loop.blocks, loop.different == 0 ? "IDENTICAL" : loop.different + " DIFFER, first " + loop.first, loop.changed, loop.skipped));
            loop.gpu.close();
         }

         if (strata != null) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU strata check: %d chunks, %,d columns compared: %s; %,d unsure (left to the CPU)", chunks,
               strata.columns, strata.different == 0 ? "IDENTICAL" : strata.different + " DIFFER, first " + strata.first, strata.unsure));
            strata.gpu.close();
         }
      }
   }

   /**
    * G3.3c: the whole column loop on the GPU ({@link SurfaceCodegen#loopSource}) from the blocks before it, every input
    * from the GPU (the strata from {@link StrataGpu}), and the chunk the game made compared block for block: "WGA GPU
    * surface loop check".
    */
   private static final class Loop {
      final Gpu gpu;
      final long nd;
      final long np;
      final long ind;
      final long inp;
      final long sd;
      final long sp;
      final long temperature;
      final long modifier;
      long chunks;
      long blocks;
      long different;
      long changed;
      long skipped;
      String first;

      Loop(SurfaceCodegen codegen, Inputs inputs, Registry<Biome> biomes) {
         String source = codegen.loopSource(inputs.code, net.minecraft.world.level.dimension.DimensionType.WAY_BELOW_MIN_Y,
            id(biomes, net.minecraft.world.level.biome.Biomes.ERODED_BADLANDS), id(biomes, net.minecraft.world.level.biome.Biomes.FROZEN_OCEAN),
            id(biomes, net.minecraft.world.level.biome.Biomes.DEEP_FROZEN_OCEAN));
         try {
            Files.writeString(Platform.logs().resolve("wgaccel-surface-loop.cu"), source, StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }

         long start = System.nanoTime();
         this.gpu = new Gpu(source, "surface_loop.cu");
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface loop check: compiled and loaded in %.0f ms", (System.nanoTime() - start) / 1.0e6));
         this.nd = this.gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, codegen.noiseData().length)).put(0, codegen.noiseData()));
         this.np = this.gpu.upload(MemoryUtil.memAllocInt(Math.max(1, codegen.permutations().length)).put(0, codegen.permutations()));
         SurfaceInputs code = inputs.code;
         this.ind = this.gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, code.noiseData().length)).put(0, code.noiseData()));
         this.inp = this.gpu.upload(MemoryUtil.memAllocInt(Math.max(1, code.permutations().length)).put(0, code.permutations()));
         this.sd = this.gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, code.simplexData().length)).put(0, code.simplexData()));
         this.sp = this.gpu.upload(MemoryUtil.memAllocInt(Math.max(1, code.simplexPermutations().length)).put(0, code.simplexPermutations()));
         this.temperature = this.gpu.upload(MemoryUtil.memAllocFloat(code.temperature().length).put(0, code.temperature()));
         this.modifier = this.gpu.upload(MemoryUtil.memAllocInt(code.modifier().length).put(0, code.modifier()));
      }

      private static int id(Registry<Biome> biomes, net.minecraft.resources.ResourceKey<Biome> key) {
         Biome biome = biomes.get(key);
         return biome == null ? -100 : biomes.getId(biome);
      }

      void check(SurfaceTap.Tap tap, Strata strata, int index, SurfaceCodegen states) {
         if (tap.before == null || tap.after == null || strata.lastUnsure) {
            this.skipped++;
            return;
         }

         int n = 256 * tap.height;
         int max = strata.code.maxLayers();
         long dout = this.gpu.allocate(4L * n);
         long dspecial = this.gpu.allocate(4L * 256);
         long start = System.nanoTime();
         this.gpu.run("surfaceLoop", 1, 1, MemoryUtil.memAlloc(n).put(0, tap.kinds), tap.minY, tap.height, MemoryUtil.memAllocInt(256).put(0, tap.tops),
            tap.chunkX() * 16, tap.chunkZ() * 16, MemoryUtil.memAllocInt(256).put(0, tap.heights), MemoryUtil.memAllocInt(256).put(0, tap.minBase),
            MemoryUtil.memAllocFloat(256 * max).put(0, strata.lastDepth), MemoryUtil.memAllocInt(256 * max).put(0, strata.lastState),
            MemoryUtil.memAllocInt(256).put(0, strata.lastCount), max, MemoryUtil.memAllocInt(tap.quarts.length).put(0, tap.quarts),
            MemoryUtil.memAllocInt(6).put(0, tap.grid), tap.zoomSeed, this.temperature, this.modifier, this.sd, this.sp, this.ind, this.inp, this.nd, this.np, dout,
            dspecial);
         double gpuMs = (System.nanoTime() - start) / 1.0e6;
         IntBuffer out = MemoryUtil.memAllocInt(n);
         this.gpu.download(dout, out);
         IntBuffer special = MemoryUtil.memAllocInt(256);
         this.gpu.download(dspecial, special);
         boolean skip = false;
         for (int t = 0; t < 256; t++) {
            skip |= special.get(t) != 0;
         }

         if (skip) {
            this.skipped++;
            MemoryUtil.memFree(out);
            MemoryUtil.memFree(special);
            return;
         }

         int differ = 0;
         int set = 0;
         String firstHere = null;
         for (int i = 0; i < n; i++) {
            int code = out.get(i);
            net.minecraft.world.level.block.state.BlockState expected = code >= 0 ? states.stateAt(code) : tap.before[i];
            set += code >= 0 ? 1 : 0;
            if (expected != tap.after[i]) {
               differ++;
               if (firstHere == null) {
                  int t = i / tap.height;
                  firstHere = String.format(Locale.ROOT, "block (%d, %d, %d): game %s, gpu %s (before %s)", tap.chunkX() * 16 + t / 16, tap.minY + i % tap.height,
                     tap.chunkZ() * 16 + t % 16, tap.after[i], expected, tap.before[i]);
               }
            }
         }

         MemoryUtil.memFree(out);
         MemoryUtil.memFree(special);
         this.chunks++;
         this.blocks += n;
         this.changed += set;
         this.different += differ;
         if (this.first == null) {
            this.first = firstHere;
         }

         if (index < 4 || differ > 0) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
               "WGA GPU surface loop check: chunk [%d, %d]: %,d blocks, %,d set by the rules: %s; GPU %.2f ms (one thread per column)", tap.chunkX(), tap.chunkZ(), n,
               set, differ == 0 ? "IDENTICAL" : differ + " DIFFER, first " + firstHere, gpuMs));
         }
      }
   }

   /** G3.3b: FreeTerraForged's strata layers per column ({@link StrataGpu}) against the buffers its rule made in the game. */
   private static final class Strata {
      final StrataGpu code;
      final Gpu gpu;
      long columns;
      long different;
      long unsure;
      String first;
      float[] lastDepth;
      int[] lastState;
      int[] lastCount;
      boolean lastUnsure;

      Strata(StrataGpu code) {
         this.code = code;
         try {
            Files.writeString(Platform.logs().resolve("wgaccel-strata.cu"), code.source(), StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }

         long start = System.nanoTime();
         this.gpu = new Gpu(code.source(), "strata.cu");
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU strata check: up to %d layers per column, compiled and loaded in %.0f ms", code.maxLayers(),
            (System.nanoTime() - start) / 1.0e6));
      }

      void check(SurfaceTap.Tap tap, int index, SurfaceCodegen states) {
         int max = this.code.maxLayers();
         long depth = this.gpu.allocate(4L * 256 * max);
         long state = this.gpu.allocate(4L * 256 * max);
         long count = this.gpu.allocate(4L * 256);
         long unsure = this.gpu.allocate(4L * 256);
         this.gpu.run("strataColumns", 1, 1, MemoryUtil.memAllocInt(256).put(0, tap.heights), tap.chunkX() * 16, tap.chunkZ() * 16, depth, state, count, unsure);
         java.nio.FloatBuffer d = MemoryUtil.memAllocFloat(256 * max);
         IntBuffer s = MemoryUtil.memAllocInt(256 * max);
         IntBuffer c = MemoryUtil.memAllocInt(256);
         IntBuffer u = MemoryUtil.memAllocInt(256);
         this.gpu.download(depth, d);
         this.gpu.download(state, s);
         this.gpu.download(count, c);
         this.gpu.download(unsure, u);
         this.lastDepth = new float[256 * max];
         this.lastState = new int[256 * max];
         this.lastCount = new int[256];
         d.get(0, this.lastDepth);
         s.get(0, this.lastState);
         c.get(0, this.lastCount);
         this.lastUnsure = false;
         for (int t = 0; t < 256; t++) {
            this.lastUnsure |= u.get(t) != 0;
         }

         int chunkDiffer = 0;
         int chunkColumns = 0;
         for (int t = 0; t < 256; t++) {
            int game = tap.strataCount[t];
            if (game <= 0) {
               continue;
            }

            if (u.get(t) != 0) {
               this.unsure++;
               continue;
            }

            chunkColumns++;
            boolean same = c.get(t) == game;
            for (int i = 0; same && i < game; i++) {
               same = Float.floatToRawIntBits(d.get(t * max + i)) == Float.floatToRawIntBits(tap.strataDepth[t * SurfaceTap.STRATA_MAX + i])
                  && s.get(t * max + i) == tap.strataState[t * SurfaceTap.STRATA_MAX + i];
            }

            if (!same) {
               chunkDiffer++;
               if (this.first == null) {
                  this.first = String.format(Locale.ROOT, "column (%d, %d): game %d layers, top depth %s %s; gpu %d layers, top depth %s %s", tap.chunkX() * 16 + t / 16,
                     tap.chunkZ() * 16 + t % 16, game, tap.strataDepth[t * SurfaceTap.STRATA_MAX], states.stateAt(tap.strataState[t * SurfaceTap.STRATA_MAX]), c.get(t),
                     d.get(t * max), states.stateAt(s.get(t * max)));
               }
            }
         }

         MemoryUtil.memFree(d);
         MemoryUtil.memFree(s);
         MemoryUtil.memFree(c);
         MemoryUtil.memFree(u);
         this.columns += chunkColumns;
         this.different += chunkDiffer;
         if (index < 4 || chunkDiffer > 0) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU strata check: chunk [%d, %d]: %d columns: %s", tap.chunkX(), tap.chunkZ(), chunkColumns,
               chunkDiffer == 0 ? "IDENTICAL" : chunkDiffer + " DIFFER"));
         }
      }
   }

   /**
    * G3.3a: the rule calls' inputs worked out on the GPU ({@link SurfaceInputs}): biome, cold, steep and surface depth per
    * call, the secondary per call against the column's; "WGA GPU surface input check".
    */
   private static final class Inputs {
      final SurfaceInputs code;
      final Gpu gpu;
      final long nd;
      final long np;
      final long sd;
      final long sp;
      final long temperature;
      final long modifier;
      final long[] differ = new long[5];
      final String[] first = new String[5];
      long calls;

      Inputs(SurfaceInputs code) {
         this.code = code;
         try {
            Files.writeString(Platform.logs().resolve("wgaccel-surface-inputs.cu"), code.source(), StandardCharsets.UTF_8);
         } catch (Exception ignored) {
         }

         long start = System.nanoTime();
         this.gpu = new Gpu(code.source(), "surface_inputs.cu");
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface input check: compiled and loaded in %.0f ms", (System.nanoTime() - start) / 1.0e6));
         this.nd = this.gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, code.noiseData().length)).put(0, code.noiseData()));
         this.np = this.gpu.upload(MemoryUtil.memAllocInt(Math.max(1, code.permutations().length)).put(0, code.permutations()));
         this.sd = this.gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, code.simplexData().length)).put(0, code.simplexData()));
         this.sp = this.gpu.upload(MemoryUtil.memAllocInt(Math.max(1, code.simplexPermutations().length)).put(0, code.simplexPermutations()));
         this.temperature = this.gpu.upload(MemoryUtil.memAllocFloat(code.temperature().length).put(0, code.temperature()));
         this.modifier = this.gpu.upload(MemoryUtil.memAllocInt(code.modifier().length).put(0, code.modifier()));
      }

      void check(SurfaceTap.Tap tap, int index) {
         int n = tap.count;
         long dout = this.gpu.allocate(16L * Math.max(1, n));
         long dsec = this.gpu.allocate(8L * Math.max(1, n));
         this.gpu.run("surfaceInputs", (n + 255) / 256, 1, MemoryUtil.memAllocInt(Math.max(1, n * SurfaceTap.FIELDS)).put(0, tap.calls, 0, n * SurfaceTap.FIELDS), n,
            MemoryUtil.memAllocInt(tap.quarts.length).put(0, tap.quarts), MemoryUtil.memAllocInt(6).put(0, tap.grid), tap.zoomSeed,
            MemoryUtil.memAllocInt(256).put(0, tap.heights), this.temperature, this.modifier, this.sd, this.sp, this.nd, this.np, dout, dsec);
         IntBuffer out = MemoryUtil.memAllocInt(Math.max(1, n * 4));
         this.gpu.download(dout, out);
         java.nio.DoubleBuffer sec = MemoryUtil.memAllocDouble(Math.max(1, n));
         this.gpu.download(dsec, sec);
         String[] names = {"biome", "cold", "steep", "surface depth", "secondary"};
         int[] fields = {7, 8, 9, 6};
         long[] chunkDiffer = new long[5];
         for (int i = 0; i < n; i++) {
            int at = i * SurfaceTap.FIELDS;
            for (int f = 0; f < 4; f++) {
               int game = tap.calls[at + fields[f]];
               int gpuValue = out.get(i * 4 + f);
               if (game != gpuValue) {
                  chunkDiffer[f]++;
                  if (this.first[f] == null) {
                     this.first[f] = String.format(Locale.ROOT, "%s at (%d, %d, %d): game %d, gpu %d", names[f], tap.calls[at], tap.calls[at + 1], tap.calls[at + 2], game, gpuValue);
                  }
               }
            }

            double game = tap.secondary[tap.calls[at + 11]];
            double gpuValue = sec.get(i);
            if (Double.doubleToRawLongBits(game) != Double.doubleToRawLongBits(gpuValue)) {
               chunkDiffer[4]++;
               if (this.first[4] == null) {
                  this.first[4] = String.format(Locale.ROOT, "secondary at (%d, %d): game %s, gpu %s", tap.calls[at], tap.calls[at + 2], game, gpuValue);
               }
            }
         }

         MemoryUtil.memFree(out);
         MemoryUtil.memFree(sec);
         this.calls += n;
         StringBuilder line = new StringBuilder();
         for (int f = 0; f < 5; f++) {
            this.differ[f] += chunkDiffer[f];
            line.append(f == 0 ? "" : ", ").append(names[f]).append(' ').append(chunkDiffer[f] == 0 ? "IDENTICAL" : chunkDiffer[f] + " DIFFER");
         }

         if (index < 4 || chunkDiffer[0] + chunkDiffer[1] + chunkDiffer[2] + chunkDiffer[3] + chunkDiffer[4] > 0) {
            WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface input check: chunk [%d, %d]: %,d rule calls: %s", tap.chunkX(), tap.chunkZ(), n, line));
         }
      }

      void total(int chunks) {
         String[] names = {"biome", "cold", "steep", "surface depth", "secondary"};
         StringBuilder line = new StringBuilder();
         for (int f = 0; f < 5; f++) {
            line.append(f == 0 ? "" : "; ").append(names[f]).append(' ').append(this.differ[f] == 0 ? "IDENTICAL" : this.differ[f] + " DIFFER, first " + this.first[f]);
         }

         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT, "WGA GPU surface input check: %d chunks, %,d rule calls: %s", chunks, this.calls, line));
      }
   }
}
