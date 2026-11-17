package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.gen.DensityTap;
import com.wgaccel.platform.Names;
import com.wgaccel.platform.Platform;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU step G2.3 (config gpuMode CHECK): whole chunks' final density on the GPU as vanilla's NoiseChunk fills them (the
 * interpolators' cell corners, then every block: corners blended with lerp3, FlatCache at the quarter column and y 0),
 * compared bit for bit with the density the game itself worked out for the same chunks ({@link DensityTap}: the first
 * {@link #CHUNKS} chunks without structures or blending generated after world start; needs the check mixins, which
 * load only when gpuMode is CHECK at game start). Logged as "WGA GPU density chunk
 * check". The generated source is saved as logs/wgaccel-density-chunk.cu. Changes nothing in the world.
 */
final class DensityChunkCheck {
   private static final int CHUNKS = 160;

   private DensityChunkCheck() {
   }

   private static void logStates(boolean veins, long solid, long oreDifferent, long[] veinCounts, int chunks) {
      WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
         "WGA GPU block state check (aquifer, then ore veins %s), %d chunks: %,d blocks: %s (game: default %,d, copper ore %,d, raw copper %,d, granite %,d, deepslate iron ore %,d, raw iron %,d, tuff %,d, air %,d, water %,d, lava %,d, other %,d)",
         veins ? "on" : "off in this world", chunks, solid, oreDifferent == 0 ? "IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", oreDifferent), veinCounts[0],
         veinCounts[1], veinCounts[2], veinCounts[3], veinCounts[4], veinCounts[5], veinCounts[6], veinCounts[7], veinCounts[8], veinCounts[9], veinCounts[10]));
   }

   private static java.nio.IntBuffer ints(int[] values) {
      return MemoryUtil.memAllocInt(Math.max(1, values.length)).put(0, values);
   }

   private static Object field(Object target, String name) throws ReflectiveOperationException {
      java.lang.reflect.Field field = Names.declaredField(target.getClass(), name);
      field.setAccessible(true);
      return field.get(target);
   }

   private static boolean same(double expected, double actual) {
      return Double.isNaN(expected) ? Double.isNaN(actual) : Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual);
   }

   /** On the density check's thread: makes and compiles the chunk code, starts the tap, and checks the chunks it sends. */
   static void run(Object finalDensity, Object veinToggle, Object veinRidged, Object veinGap, Object barrierNoise, Object oreRandom, boolean veins)
         throws Exception {
      DensityCodegen codegen = new DensityCodegen();
      String root = codegen.function(finalDensity, DensityCodegen.Mode.BLOCK);
      String step = codegen.function(finalDensity, DensityCodegen.Mode.BLOCK_STEP);
      String toggle = codegen.function(veinToggle, DensityCodegen.Mode.BLOCK_STEP);
      String ridged = codegen.function(veinRidged, DensityCodegen.Mode.BLOCK_STEP);
      String gap = codegen.function(veinGap, DensityCodegen.Mode.BLOCK_STEP);
      String barrier = codegen.function(barrierNoise, DensityCodegen.Mode.BLOCK_STEP);
      long seedLo = (Long)field(oreRandom, "seedLo");
      long seedHi = (Long)field(oreRandom, "seedHi");
      List<String> corners = new ArrayList<>();
      for (Object input : codegen.interpolatorInputs()) {
         corners.add(codegen.function(input, DensityCodegen.Mode.CORNER));
      }

      String source = codegen.chunkSource(root, step, corners) + DensityCodegen.statesKernel(toggle, ridged, gap, barrier);
      try {
         Files.writeString(Platform.logs().resolve("wgaccel-density-chunk.cu"), source, StandardCharsets.UTF_8);
      } catch (Exception ignored) {
      }

      double[] noiseData = codegen.noiseData();
      int[] permutations = codegen.permutations();
      long compileStart = System.nanoTime();
      try (Gpu gpu = new Gpu(source, "density_chunk.cu")) {
         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU density chunk check: final density with %d interpolators and %d CellSampler inputs, CUDA source %,d KB, compiled and loaded in %.0f ms; waiting for %d chunks",
            corners.size(), codegen.cellSamplers().size(), source.length() / 1024, (System.nanoTime() - compileStart) / 1.0e6, CHUNKS));
         long dnd = gpu.upload(MemoryUtil.memAllocDouble(Math.max(1, noiseData.length)).put(0, noiseData));
         long dnp = gpu.upload(MemoryUtil.memAllocInt(Math.max(1, permutations.length)).put(0, permutations));
         LinkedBlockingQueue<DensityTap.Tap> taps = new LinkedBlockingQueue<>();
         DensityTap.enable(codegen.cellSamplers(), CHUNKS, taps::add);
         long blocks = 0;
         long different = 0;
         long differentStep = 0;
         long solid = 0;
         long oreDifferent = 0;
         long[] veinCounts = new long[11];
         int chunks = 0;
         int logged = 0;
         while (chunks < CHUNKS) {
            DensityTap.Tap tap = taps.poll(30, TimeUnit.MINUTES);
            if (tap == null) {
               break;
            }

            long start = System.nanoTime();
            int nx = 16 / tap.cellWidth() + 1;
            int ny = tap.cellCountY() + 1;
            int per = nx * nx * ny;
            int interpolators = corners.size();
            int height = tap.cellCountY() * tap.cellHeight();
            DoubleBuffer cells = MemoryUtil.memAllocDouble(tap.cells().length).put(0, tap.cells());
            DoubleBuffer out = MemoryUtil.memAllocDouble(256 * height);
            DoubleBuffer outStep = MemoryUtil.memAllocDouble(256 * height);
            try {
               long dcells = gpu.upload(cells);
               long dcorners = gpu.allocate(8L * Math.max(1, interpolators * per));
               long dout = gpu.allocate(8L * 256 * height);
               long doutStep = gpu.allocate(8L * 256 * height);
               long dcodes = gpu.allocate(4L * 256 * height);
               gpu.run("densityCorners", (interpolators * per + 255) / 256, 1, dcells, dnd, dnp, tap.chunkX(), tap.chunkZ(), tap.cellMinY(), tap.cellCountY(),
                  tap.cellWidth(), tap.cellHeight(), interpolators, dcorners);
               gpu.run("densityBlocks", (256 * height + 255) / 256, 1, dcells, dnd, dnp, tap.chunkX(), tap.chunkZ(), tap.cellMinY(), tap.cellCountY(),
                  tap.cellWidth(), tap.cellHeight(), dcorners, per, dout);
               gpu.run("densityBlocksStep", (256 * height + 255) / 256, 1, dcells, dnd, dnp, tap.chunkX(), tap.chunkZ(), tap.cellMinY(), tap.cellCountY(),
                  tap.cellWidth(), tap.cellHeight(), dcorners, per, doutStep);
               DensityTap.Aquifers aq = tap.aquifer();
               gpu.run("densityStates", (256 * height + 255) / 256, 1, dcells, dnd, dnp, tap.chunkX(), tap.chunkZ(), tap.cellMinY(), tap.cellCountY(),
                  tap.cellWidth(), tap.cellHeight(), dcorners, per, doutStep, seedLo, seedHi, veins ? 1 : 0, ints(aq.grid()), ints(aq.loc()), ints(aq.status()),
                  ints(aq.lava()), aq.sea(), aq.seaType(), aq.enabled() ? 1 : 0, dcodes);
               gpu.download(dout, out);
               gpu.download(doutStep, outStep);
               java.nio.IntBuffer codes = MemoryUtil.memAllocInt(256 * height);
               gpu.download(dcodes, codes);
               String firstOre = null;
               int oreDiffer = 0;
               for (int i = 0; i < 256 * height; i++) {
                  if (!tap.filled()[i]) {
                     continue;
                  }

                  solid++;
                  int game = tap.states()[i];
                  int gpuCode = codes.get(i);
                  if (game >= 0 && game < veinCounts.length) {
                     veinCounts[game]++;
                  }

                  if (game != gpuCode) {
                     oreDiffer++;
                     if (firstOre == null) {
                        int ly = i / 256;
                        int lz = (i - ly * 256) / 16;
                        int lx = i - ly * 256 - lz * 16;
                        firstOre = String.format(Locale.ROOT, "block (%d, %d, %d): game %d, gpu %d", tap.chunkX() + lx, tap.cellMinY() * tap.cellHeight() + ly,
                           tap.chunkZ() + lz, game, gpuCode);
                     }
                  }
               }

               oreDifferent += oreDiffer;
               if (chunks % 16 == 15) {
                  logStates(veins, solid, oreDifferent, veinCounts, chunks + 1);
               }
               MemoryUtil.memFree(codes);
               if (oreDiffer > 0 && oreDifferent == oreDiffer) {
                  WorldgenAccelerator.LOG.warn("WGA GPU block state check: chunk [{}, {}]: {} blocks differ, first {}", tap.chunkX() >> 4, tap.chunkZ() >> 4, oreDiffer,
                     firstOre);
               }
               double gpuMs = (System.nanoTime() - start) / 1.0e6;
               int compared = 0;
               int differ = 0;
               int differStep = 0;
               String first = null;
               String firstStep = null;
               for (int i = 0; i < 256 * height; i++) {
                  if (!tap.filled()[i]) {
                     continue;
                  }

                  compared++;
                  double expected = tap.density()[i];
                  double actual = out.get(i);
                  double actualStep = outStep.get(i);
                  int ly = i / 256;
                  int lz = (i - ly * 256) / 16;
                  int lx = i - ly * 256 - lz * 16;
                  if (!same(expected, actual)) {
                     differ++;
                     if (first == null) {
                        first = String.format(Locale.ROOT, "block (%d, %d, %d): game %s, gpu %s", tap.chunkX() + lx, tap.cellMinY() * tap.cellHeight() + ly,
                           tap.chunkZ() + lz, expected, actual);
                     }
                  }

                  if (!same(expected, actualStep)) {
                     differStep++;
                     if (firstStep == null) {
                        firstStep = String.format(Locale.ROOT, "block (%d, %d, %d): game %s, gpu %s", tap.chunkX() + lx, tap.cellMinY() * tap.cellHeight() + ly,
                           tap.chunkZ() + lz, expected, actualStep);
                     }
                  }
               }

               chunks++;
               blocks += compared;
               different += differ;
               differentStep += differStep;
               if (logged++ < 4) {
                  WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
                     "WGA GPU density chunk check: chunk [%d, %d] (%d cell layers): %,d blocks; interpolated with lerp3 (x, y, z): %s; step by step (y, x, z): %s; GPU %.2f ms (with copies)",
                     tap.chunkX() >> 4, tap.chunkZ() >> 4, tap.cellCountY(), compared, differ == 0 ? "BIT-IDENTICAL" : differ + " DIFFER, first " + first,
                     differStep == 0 ? "BIT-IDENTICAL" : differStep + " DIFFER, first " + firstStep, gpuMs));
               }
            } finally {
               MemoryUtil.memFree(cells);
               MemoryUtil.memFree(out);
               MemoryUtil.memFree(outStep);
            }
         }

         WorldgenAccelerator.LOG.info(String.format(Locale.ROOT,
            "WGA GPU density chunk check: %d chunks, %,d blocks: lerp3 (x, y, z) %s; step by step (y, x, z) %s (%d chunks skipped: structures or blending)",
            chunks, blocks, different == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", different),
            differentStep == 0 ? "BIT-IDENTICAL" : String.format(Locale.ROOT, "%,d DIFFER", differentStep), DensityTap.skipped()));
         logStates(veins, solid, oreDifferent, veinCounts, chunks);

      }
   }
}
