package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Times FreeTerraForged's tiles (its 2D terrain, 8 x 8 chunks plus a border) from the request to the finished tile:
 * terrain noise, rivers and climate on its own thread pool, then the filters. The world-creation preview's zoomed tiles
 * are not counted.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator")
public abstract class TileGeneratorMixin {
   @WrapMethod(method = "generate(II)Ljava/util/concurrent/CompletableFuture;")
   private CompletableFuture<Object> wgaccel$generate(int tileX, int tileZ, Operation<CompletableFuture<Object>> original) {
      Stats.tileMade(tileX, tileZ);
      return Stats.timeFuture(Stage.FTF_TILE, () -> original.call(tileX, tileZ));
   }
}
