package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.platform.Names;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * T1: a tile's table registered when it starts and dropped when it is done. T1d: before FreeTerraForged's batches start,
 * the whole tile is walked through Heightmap.applyTerrain as a dry pass on our own workers (the GPU's populators only
 * note what they would be asked), the GPU makes exactly those in one job, and only then are the batches started (the
 * tile's start is not held up: it all runs on our dispatcher). Each batch holds the table (config gpuTileNoise).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator")
public abstract class TileNoiseGeneratorMixin {
   private static volatile java.lang.reflect.Field wgaccel$heightmap;
   private static volatile java.lang.reflect.Field wgaccel$size;
   private static volatile java.lang.reflect.Field wgaccel$batchSize;
   private static final ThreadLocal<Cell> wgaccel$scratch = ThreadLocal.withInitial(Cell::new);

   private void wgaccel$fields() throws ReflectiveOperationException {
      if (wgaccel$batchSize == null) {
         java.lang.reflect.Field heightmap = Names.declaredField(this.getClass(), "heightmap");
         heightmap.setAccessible(true);
         java.lang.reflect.Field size = Names.declaredField(this.getClass(), "tileSizeChunks");
         size.setAccessible(true);
         java.lang.reflect.Field batch = Names.declaredField(this.getClass(), "batchSize");
         batch.setAccessible(true);
         wgaccel$heightmap = heightmap;
         wgaccel$size = size;
         wgaccel$batchSize = batch;
      }
   }

   private int wgaccel$chunks() throws ReflectiveOperationException {
      Object size = wgaccel$size.get(this);
      return (Integer)Names.method(size.getClass(), "total").invoke(size);
   }

   /** T1d: one dispatcher per tile at a time, and the dry-pass workers the dispatchers split each tile across. */
   private static final java.util.concurrent.ExecutorService wgaccel$dispatch = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
      Thread thread = new Thread(r, "WGA tile noise dispatch");
      thread.setDaemon(true);
      return thread;
   });
   private static final java.util.concurrent.ExecutorService wgaccel$dry = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
      Thread thread = new Thread(r, "WGA tile noise dry pass");
      thread.setDaemon(true);
      return thread;
   });

   @WrapMethod(method = "generateTile(Letcodehome/freeterraforged/world/worldgen/densityfunction/tile/Tile;)Ljava/util/concurrent/CompletableFuture;")
   private CompletableFuture<Tile> wgaccel$tileNoise(Tile tile, Operation<CompletableFuture<Tile>> original) {
      com.wgaccel.gpu.TileNoiseGpu.trace("tile start reached (tile noise " + (com.wgaccel.gen.TileNoise.on() ? "on" : "off") + ")");
      com.wgaccel.gpu.TileNoiseGpu.Table table = null;
      if (com.wgaccel.gen.TileNoise.on()) {
         try {
            this.wgaccel$fields();
            Tile.Chunk first = tile.getChunkWriter(0, 0);
            table = com.wgaccel.gpu.TileNoiseGpu.begin(tile, wgaccel$heightmap.get(this), first.getBlockX(), first.getBlockZ(), this.wgaccel$chunks() * 16);
         } catch (Throwable failure) {
            com.wgaccel.gpu.TileNoiseGpu.disable("tile setup: " + failure);
         }
      }

      if (table == null) {
         return original.call(tile);
      }

      // The whole tile's dry pass (split over the workers) and one GPU job, then FreeTerraForged's own batches.
      com.wgaccel.gpu.TileNoiseGpu.Table made = table;
      CompletableFuture<Tile> future = CompletableFuture.runAsync(() -> this.wgaccel$prepare(tile, made), wgaccel$dispatch)
         .handle((v, failure) -> {
            if (failure != null) {
               com.wgaccel.gpu.TileNoiseGpu.disable("tile dry pass: " + failure);
            }

            return v;
         })
         .thenCompose(v -> original.call(tile));
      future.whenComplete((result, throwable) -> com.wgaccel.gpu.TileNoiseGpu.end(tile));
      return future;
   }

   /** The tile's dry pass in slices of chunk rows on the workers, the needs merged, then the GPU makes them. */
   private void wgaccel$prepare(Tile tile, com.wgaccel.gpu.TileNoiseGpu.Table table) {
      try {
         long start = System.nanoTime();
         Heightmap heightmap = (Heightmap)wgaccel$heightmap.get(this);
         int total = this.wgaccel$chunks();
         java.util.List<java.util.concurrent.Future<IntArrayList[]>> parts = new java.util.ArrayList<>();
         for (int cZ = 0; cZ < total; cZ++) {
            int row = cZ;
            parts.add(wgaccel$dry.submit(() -> {
               com.wgaccel.gen.TileNoise.batch(table);
               com.wgaccel.gen.TileNoise.record(true);
               try {
                  Cell cell = wgaccel$scratch.get();
                  for (int cX = 0; cX < total; cX++) {
                     Tile.Chunk chunk = tile.getChunkWriter(cX, row);
                     for (int dz = 0; dz < 16; dz++) {
                        for (int dx = 0; dx < 16; dx++) {
                           heightmap.applyTerrain(cell.reset(), chunk.getBlockX() + dx, chunk.getBlockZ() + dz);
                        }
                     }
                  }
               } finally {
                  IntArrayList[] needs = com.wgaccel.gen.TileNoise.record(false);
                  com.wgaccel.gen.TileNoise.batch(null);
                  return needs;
               }
            }));
         }

         IntArrayList[] all = new IntArrayList[table.populators()];
         for (java.util.concurrent.Future<IntArrayList[]> part : parts) {
            IntArrayList[] needs = part.get();
            if (needs == null) {
               continue;
            }

            for (int n = 0; n < needs.length; n++) {
               if (needs[n] != null) {
                  if (all[n] == null) {
                     all[n] = new IntArrayList();
                  }

                  all[n].addAll(needs[n]);
               }
            }
         }

         com.wgaccel.gpu.TileNoiseGpu.DRY_NANOS.add(System.nanoTime() - start);
         com.wgaccel.gpu.TileNoiseGpu.fill(table, all);
      } catch (Throwable failure) {
         com.wgaccel.gpu.TileNoiseGpu.disable("tile dry pass: " + failure);
      }
   }

   @WrapMethod(method = "lambda$generateTile$3(IILetcodehome/freeterraforged/world/worldgen/densityfunction/tile/Tile;)V")
   private void wgaccel$batch(int chunkX, int chunkZ, Tile tile, Operation<Void> original) {
      com.wgaccel.gpu.TileNoiseGpu.Table table = com.wgaccel.gen.TileNoise.on() ? com.wgaccel.gpu.TileNoiseGpu.tableFor(tile) : null;
      com.wgaccel.gen.TileNoise.batch(table);
      try {
         original.call(chunkX, chunkZ, tile);
      } finally {
         com.wgaccel.gen.TileNoise.batch(null);
      }
   }
}
