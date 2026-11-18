package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.wgaccel.Config;
import com.wgaccel.gen.FastNoiseInterpolator;
import com.wgaccel.gen.FillToGround;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Plan item B1: a chunk's noise chunk is made only as tall as its terrain needs ({@link FillToGround}), and fast indexed interpolation. */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
   @Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
   @Shadow @Final private List<?> cellCaches;
   @Shadow @Final private int cellNoiseMinY;
   @Shadow @Final private int firstCellZ;
   @Shadow @Final private int cellWidth;
   @Shadow @Final private int cellHeight;
   @Shadow private boolean fillingCell;
   @Shadow private int cellStartBlockX;
   @Shadow private int cellStartBlockY;
   @Shadow private int cellStartBlockZ;
   @Shadow private int inCellX;
   @Shadow private int inCellY;
   @Shadow private int inCellZ;
   @Shadow private long interpolationCounter;
   @Shadow private long arrayInterpolationCounter;

   @Unique
   private FastNoiseInterpolator[] wgaccel$interpArray;
   @Unique
   private FastNoiseInterpolator.CellCache[] wgaccel$cacheArray;

   @Unique
   private FastNoiseInterpolator[] wgaccel$interps() {
      FastNoiseInterpolator[] arr = this.wgaccel$interpArray;
      int n = this.interpolators.size();
      if (arr == null || arr.length != n) {
         arr = new FastNoiseInterpolator[n];
         for (int i = 0; i < n; i++) {
            arr[i] = (FastNoiseInterpolator)(Object)this.interpolators.get(i);
         }
         this.wgaccel$interpArray = arr;
      }
      return arr;
   }

   @Unique
   private FastNoiseInterpolator.CellCache[] wgaccel$caches() {
      FastNoiseInterpolator.CellCache[] arr = this.wgaccel$cacheArray;
      int n = this.cellCaches.size();
      if (arr == null || arr.length != n) {
         arr = new FastNoiseInterpolator.CellCache[n];
         for (int i = 0; i < n; i++) {
            arr[i] = (FastNoiseInterpolator.CellCache)this.cellCaches.get(i);
         }
         this.wgaccel$cacheArray = arr;
      }
      return arr;
   }

   @WrapMethod(method = "selectCellYZ", require = 0)
   private void wgaccel$selectCellYZ(int cellY, int cellZ, Operation<Void> original) {
      if (!Config.FAST_NOISE_INTERPOLATION.get()) {
         original.call(cellY, cellZ);
         return;
      }

      FastNoiseInterpolator[] interps = this.wgaccel$interps();
      for (int i = 0; i < interps.length; i++) {
         interps[i].wgaccel$selectCellYZ(cellY, cellZ);
      }

      this.fillingCell = true;
      this.cellStartBlockY = (cellY + this.cellNoiseMinY) * this.cellHeight;
      this.cellStartBlockZ = (this.firstCellZ + cellZ) * this.cellWidth;
      this.arrayInterpolationCounter++;

      FastNoiseInterpolator.CellCache[] caches = this.wgaccel$caches();
      DensityFunction.ContextProvider provider = (DensityFunction.ContextProvider)(Object)this;
      for (int i = 0; i < caches.length; i++) {
         caches[i].wgaccel$fill(provider);
      }

      this.arrayInterpolationCounter++;
      this.fillingCell = false;
   }

   @WrapMethod(method = "updateForY", require = 0)
   private void wgaccel$updateForY(int y, double deltaY, Operation<Void> original) {
      if (!Config.FAST_NOISE_INTERPOLATION.get()) {
         original.call(y, deltaY);
         return;
      }

      this.inCellY = y - this.cellStartBlockY;
      FastNoiseInterpolator[] interps = this.wgaccel$interps();
      for (int i = 0; i < interps.length; i++) {
         interps[i].wgaccel$updateForY(deltaY);
      }
   }

   @WrapMethod(method = "updateForX", require = 0)
   private void wgaccel$updateForX(int x, double deltaX, Operation<Void> original) {
      if (!Config.FAST_NOISE_INTERPOLATION.get()) {
         original.call(x, deltaX);
         return;
      }

      this.inCellX = x - this.cellStartBlockX;
      FastNoiseInterpolator[] interps = this.wgaccel$interps();
      for (int i = 0; i < interps.length; i++) {
         interps[i].wgaccel$updateForX(deltaX);
      }
   }

   @WrapMethod(method = "updateForZ", require = 0)
   private void wgaccel$updateForZ(int z, double deltaZ, Operation<Void> original) {
      if (!Config.FAST_NOISE_INTERPOLATION.get()) {
         original.call(z, deltaZ);
         return;
      }

      this.inCellZ = z - this.cellStartBlockZ;
      this.interpolationCounter++;
      FastNoiseInterpolator[] interps = this.wgaccel$interps();
      for (int i = 0; i < interps.length; i++) {
         interps[i].wgaccel$updateForZ(deltaZ);
      }
   }
   @WrapOperation(method = "forChunk", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/NoiseSettings;clampToHeightAccessor(Lnet/minecraft/world/level/LevelHeightAccessor;)Lnet/minecraft/world/level/levelgen/NoiseSettings;"))
   private static NoiseSettings wgaccel$fillToGround(NoiseSettings settings, LevelHeightAccessor height, Operation<NoiseSettings> original,
         @Local(argsOnly = true) ChunkAccess chunk, @Local(argsOnly = true) RandomState random,
         @Local(argsOnly = true) DensityFunctions.BeardifierOrMarker beardifier, @Local(argsOnly = true) NoiseGeneratorSettings generatorSettings,
         @Local(argsOnly = true) Blender blender) {
      return FillToGround.trim(original.call(settings, height), chunk, random, beardifier, generatorSettings, blender);
   }

   /** The aquifer of a cut noise chunk keeps the full height (carvers use it up there; {@link FillToGround#aquiferRange}). */
   @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/Aquifer;create(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/NoiseRouter;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;IILnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;)Lnet/minecraft/world/level/levelgen/Aquifer;"))
   private Aquifer wgaccel$fullAquifer(NoiseChunk noise, ChunkPos pos, NoiseRouter router, PositionalRandomFactory random, int minY, int height,
         Aquifer.FluidPicker picker, Operation<Aquifer> original) {
      int[] range = FillToGround.aquiferRange(minY, height);
      return original.call(noise, pos, router, random, range[0], range[1], picker);
   }

   /** The chunk's climate sampler, noted for its per-column temperature and humidity ({@link com.wgaccel.gen.ClimateColumns}). */
   @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
   private void wgaccel$sampler(NoiseRouter router, java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> spawnTarget,
         CallbackInfoReturnable<net.minecraft.world.level.biome.Climate.Sampler> info) {
      com.wgaccel.gen.ClimateColumns.made(info.getReturnValue(), router);
   }
}
