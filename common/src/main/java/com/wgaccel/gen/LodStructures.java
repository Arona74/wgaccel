package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Structures in the Voxy LODs (voxyStructures). Where they start: the game's own structure_starts step
 * (ChunkGenerator.createStructures: its placement grids, biome checks and jigsaw layouts, any mod's structures with them)
 * run on a stand-in chunk of each chunk, so the starts are the ones the real world gets from the seed (kept by chunk). What
 * they put in a LOD chunk: the pieces of the starts within {@link #REACH} chunks that reach into it, placed by their own
 * code on the stand-in level ({@link FeatureSandbox#place}). Underground structures are left out (nothing of them shows).
 */
final class LodStructures {
   /** How far a start's pieces may reach, in chunks (the game's structure references reach as far). */
   static final int REACH = 8;
   private static final StructureStart[] NONE = new StructureStart[0];
   private final ServerLevel level;
   private final Registry<Structure> registry;
   private final FeatureSandbox sandbox;
   /** The starts by chunk (only chunks that have some), and the chunks looked at. */
   private final Map<Long, StructureStart[]> starts = new ConcurrentHashMap<>();
   private final java.util.Set<Long> looked = ConcurrentHashMap.newKeySet();
   private volatile boolean failed;
   /** Each structure's step and index in it (the game's decoration random), worked out once. */
   private volatile Map<Structure, int[]> order;

   /** Chunks looked at, their time (ns); per structure: starts, size, pieces, height; placing: chunks, blocks, time. */
   private final AtomicLong chunks = new AtomicLong();
   private final LongAdder nanos = new LongAdder();
   private final Map<String, Found> found = new ConcurrentHashMap<>();
   private final AtomicLong placedChunks = new AtomicLong();
   private final LongAdder placedBlocks = new LongAdder();
   private final LongAdder placeNanos = new LongAdder();

   private static final class Found {
      final String step;
      final LongAdder count = new LongAdder();
      final LongAdder wide = new LongAdder();
      final LongAdder pieces = new LongAdder();
      final java.util.concurrent.atomic.AtomicInteger low = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
      final java.util.concurrent.atomic.AtomicInteger high = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

      Found(String step) {
         this.step = step;
      }
   }

   /** A block a structure set in the chunk: position, state. */
   interface Sink {
      void set(int x, int y, int z, BlockState state);
   }

   LodStructures(ServerLevel level, FeatureSandbox sandbox) {
      this.level = level;
      this.registry = com.wgaccel.platform.Mc.registry(level.registryAccess(), Registries.STRUCTURE);
      this.sandbox = sandbox;
   }

   /** The starts in chunk (cx, cz), from the seed (worked out once). */
   StructureStart[] starts(int cx, int cz) {
      long key = com.wgaccel.platform.Mc.key(cx, cz);
      StructureStart[] here = this.starts.get(key);
      if (here != null || this.failed || this.looked.contains(key)) {
         return here != null ? here : NONE;
      }

      long started = System.nanoTime();
      ChunkAccess chunk = com.wgaccel.platform.Mc.emptyChunk(new ChunkPos(cx, cz), this.level, this.level.registryAccess());
      try {
         com.wgaccel.platform.Mc.createStructures(this.level, chunk);
      } catch (RuntimeException | LinkageError failure) {
         this.failed = true;
         WorldgenAccelerator.LOG.warn("WGA Voxy LODs: the game's structure starts failed on a stand-in chunk; no structures in the LODs from now on", failure);
         return NONE;
      }

      here = chunk.getAllStarts().values().stream().filter(StructureStart::isValid).toArray(StructureStart[]::new);
      this.nanos.add(System.nanoTime() - started);
      if (here.length > 0) {
         // The first thread's starts stand (another may have looked at the same time).
         StructureStart[] before = this.starts.putIfAbsent(key, here);
         if (before != null) {
            return before;
         }
      }

      if (!this.looked.add(key)) {
         return here.length > 0 ? here : this.starts.getOrDefault(key, NONE);
      }

      for (StructureStart start : here) {
         this.count(start);
      }

      long n = this.chunks.incrementAndGet();
      if (n == 2000 || n % 20000 == 0) {
         this.log(n);
      }

      return here.length > 0 ? here : NONE;
   }

   /**
    * The structures' blocks in chunk (cx, cz) to sink (its own columns only), placed on the stand-in level read from
    * columns; lowest: the chunk's lowest ground (starts wholly under it, but those that find their height when placed,
    * are left out). Returns the blocks set.
    */
   int place(int cx, int cz, int lowest, FeatureSandbox.Columns columns, Sink sink) {
      if (this.failed) {
         return 0;
      }

      int x0 = cx << 4;
      int z0 = cz << 4;
      List<StructureStart> list = new ArrayList<>();
      for (int dx = -REACH; dx <= REACH; dx++) {
         for (int dz = -REACH; dz <= REACH; dz++) {
            for (StructureStart start : this.starts(cx + dx, cz + dz)) {
               if (this.shows(start, x0, z0, lowest)) {
                  list.add(start);
               }
            }
         }
      }

      if (list.isEmpty()) {
         return 0;
      }

      long started = System.nanoTime();
      Map<Structure, int[]> order = this.order();
      list.sort(java.util.Comparator.comparingLong(start -> {
         int[] o = order.getOrDefault(start.getStructure(), new int[]{0, 0});
         return (long)o[0] << 32 | o[1];
      }));
      int n = list.size();
      int[] steps = new int[n];
      int[] indices = new int[n];
      String[] names = new String[n];
      for (int i = 0; i < n; i++) {
         Structure structure = list.get(i).getStructure();
         int[] o = order.getOrDefault(structure, new int[]{structure.step().ordinal(), 0});
         steps[i] = o[0];
         indices[i] = o[1];
         names[i] = String.valueOf(com.wgaccel.platform.Mc.name(this.registry, structure));
      }

      int[] count = {0};
      this.sandbox.place(list, steps, indices, names, cx, cz, columns, (x, y, z, state, r) -> {
         if (x >> 4 == cx && z >> 4 == cz) {
            sink.set(x, y, z, state);
            count[0]++;
         }
      });
      this.placeNanos.add(System.nanoTime() - started);
      this.placedBlocks.add(count[0]);
      this.placedChunks.incrementAndGet();
      return count[0];
   }

   /** Whether start may show in the chunk at (x0, z0): a piece reaches into its columns, not an underground structure. */
   private boolean shows(StructureStart start, int x0, int z0, int lowest) {
      GenerationStep.Decoration step = start.getStructure().step();
      if (step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES || step == GenerationStep.Decoration.STRONGHOLDS
         || step == GenerationStep.Decoration.UNDERGROUND_DECORATION) {
         return false;
      }

      BoundingBox box = start.getBoundingBox();
      if (box.maxX() < x0 || box.minX() > x0 + 15 || box.maxZ() < z0 || box.minZ() > z0 + 15) {
         return false;
      }

      boolean scattered = false;
      boolean reaches = false;
      for (StructurePiece piece : start.getPieces()) {
         BoundingBox b = piece.getBoundingBox();
         if (b.maxX() >= x0 && b.minX() <= x0 + 15 && b.maxZ() >= z0 && b.minZ() <= z0 + 15) {
            reaches = true;
            // Temples, igloos, huts: their height is found when placed (from y 64 until then).
            scattered |= piece instanceof net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
         }
      }

      return reaches && (scattered || box.maxY() >= lowest - LodBuilder.GROUND_DEPTH);
   }

   /** Each structure's step and its index among the structures of that step (registry order, as the game's decoration). */
   private Map<Structure, int[]> order() {
      Map<Structure, int[]> order = this.order;
      if (order == null) {
         order = new java.util.IdentityHashMap<>();
         int[] next = new int[GenerationStep.Decoration.values().length];
         // In the order the generator's decoration goes through them (FreeTerraForged's plan has its own).
         List<Structure> planned = FeatureIndex.structures(this.level.getChunkSource().getGenerator());
         for (Structure structure : planned != null ? planned : this.registry.stream().toList()) {
            int step = structure.step().ordinal();
            order.putIfAbsent(structure, new int[]{step, next[step]++});
         }

         this.order = order;
      }

      return order;
   }

   private void count(StructureStart start) {
      Structure structure = start.getStructure();
      String id = com.wgaccel.platform.Mc.name(this.registry, structure);
      Found f = this.found.computeIfAbsent(id == null ? "?" : id,
         k -> new Found(structure.step().name().toLowerCase(Locale.ROOT)));
      BoundingBox box = start.getBoundingBox();
      f.count.increment();
      f.pieces.add(start.getPieces().size());
      f.wide.add(Math.max((box.maxX() >> 4) - (box.minX() >> 4) + 1, (box.maxZ() >> 4) - (box.minZ() >> 4) + 1));
      f.low.accumulateAndGet(box.minY(), Math::min);
      f.high.accumulateAndGet(box.maxY(), Math::max);
   }

   private void log(long n) {
      StringBuilder line = new StringBuilder();
      long total = 0;
      for (Map.Entry<String, Found> e : this.found.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().count.sum(), a.getValue().count.sum())).toList()) {
         Found f = e.getValue();
         long c = f.count.sum();
         total += c;
         line.append(line.length() == 0 ? "" : "; ").append(e.getKey()).append(String.format(Locale.ROOT,
            " %d (%s, %.1f chunks across, %.1f pieces, y %d to %d)", c, f.step, f.wide.sum() / (double)c, f.pieces.sum() / (double)c,
            f.low.get(), f.high.get()));
      }

      long placed = this.placedChunks.get();
      WorldgenAccelerator.LOG.info("WGA Voxy LOD structures: {} chunks looked at ({} ms each), {} starts; placed in {} LOD chunks ({} ms, {} blocks"
         + " each): {}", n, String.format(Locale.ROOT, "%.3f", this.nanos.sum() / 1.0e6 / n), total, placed,
         String.format(Locale.ROOT, "%.2f", this.placeNanos.sum() / 1.0e6 / Math.max(1, placed)),
         String.format(Locale.ROOT, "%.0f", this.placedBlocks.sum() / (double)Math.max(1, placed)), line.length() == 0 ? "none" : line);
   }
}
