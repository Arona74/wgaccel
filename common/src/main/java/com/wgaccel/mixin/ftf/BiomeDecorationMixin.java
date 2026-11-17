package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.gen.ClimateSearch;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Underground layout of FTF's biome decoration: measures layout/search/identity timing and caches depth-uniform
 * background/fallback ParameterList lookups down each (quartX, quartZ) column ({@link BiomeFill}).
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding$Layout")
public abstract class BiomeDecorationMixin {
   @Unique
   private static final Map<Object, ThreadLocal<Object>> wgaccel$UNIFORM_DEPTH_LAST = Collections.synchronizedMap(new IdentityHashMap<>());

   @Unique
   private static final ThreadLocal<Object> wgaccel$NON_UNIFORM = new ThreadLocal<>();

   @Unique
   private static ThreadLocal<Object> wgaccel$uniformLastResult(BiomeFill.State memo, Climate.ParameterList<?> list) {
      if (memo.bgCheckedList == list) {
         return memo.bgCheckedUniform ? memo.bgCheckedLastResult : null;
      }

      ThreadLocal<Object> lastResult = wgaccel$UNIFORM_DEPTH_LAST.get(list);
      if (lastResult == null) {
         List<? extends com.mojang.datafixers.util.Pair<Climate.ParameterPoint, ?>> values = list.values();
         boolean uniform = !values.isEmpty();
         if (uniform) {
            Climate.Parameter first = values.get(0).getFirst().depth();
            long min = first.min();
            long max = first.max();
            for (int i = 1, n = values.size(); i < n; i++) {
               Climate.Parameter d = values.get(i).getFirst().depth();
               if (d.min() != min || d.max() != max) {
                  uniform = false;
                  break;
               }
            }
         }
         lastResult = uniform ? ClimateSearch.lastResultOf(list) : null;
         if (lastResult == null) {
            lastResult = wgaccel$NON_UNIFORM;
         }
         wgaccel$UNIFORM_DEPTH_LAST.put(list, lastResult);
      }

      memo.bgCheckedList = list;
      memo.bgCheckedUniform = lastResult != wgaccel$NON_UNIFORM;
      memo.bgCheckedLastResult = memo.bgCheckedUniform ? lastResult : null;
      return memo.bgCheckedLastResult;
   }

   @WrapMethod(method = "findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIIFLetcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$TargetSampler;)Ljava/lang/Object;")
   private Object wgaccel$layout(Climate.TargetPoint target, int quartX, int quartY, int quartZ, float coverage, UndergroundBiomeBanding.TargetSampler sampler,
         Operation<Object> original) {
      BiomeFill.State memo = BiomeFill.memo();
      if (memo != null) {
         memo.currentQuartX = quartX;
         memo.currentQuartZ = quartZ;
      }

      if (!BiomeFill.timingCells()) {
         return original.call(target, quartX, quartY, quartZ, coverage, sampler);
      }

      long start = System.nanoTime();
      try {
         return original.call(target, quartX, quartY, quartZ, coverage, (UndergroundBiomeBanding.TargetSampler)(x, y, z) -> {
            long begin = System.nanoTime();
            try {
               return sampler.sample(x, y, z);
            } finally {
               Stats.recordSampled(Stage.BIOME_IDENTITY, System.nanoTime() - begin, 16);
               BiomeFill.identity(x, y, z);
            }
         });
      } finally {
         Stats.recordSampled(Stage.BIOME_LAYOUT, System.nanoTime() - start, 16);
      }
   }

   /** Caches depth-uniform climate-table searches inside the underground layout per (list, quartX, quartZ, horiz). */
   @WrapOperation(method = "findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;IIIFLetcodehome/freeterraforged/world/worldgen/biome/UndergroundBiomeBanding$TargetSampler;)Ljava/lang/Object;",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Climate$ParameterList;findValue(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;"))
   private Object wgaccel$layoutSearch(Climate.ParameterList<?> list, Climate.TargetPoint target, Operation<Object> original) {
      boolean timed = BiomeFill.timingCells();
      long start = timed ? System.nanoTime() : 0L;
      try {
         BiomeFill.State memo = BiomeFill.memo();
         ThreadLocal<Object> lastResult = (memo != null && !memo.bgDisabled) ? wgaccel$uniformLastResult(memo, list) : null;
         if (lastResult != null) {
            int qx = memo.currentQuartX;
            int qz = memo.currentQuartZ;
            int slot = (qx & 3) << 2 | qz & 3;
            Object beforeLast = lastResult.get();
            if (memo.bgCached(list, slot, qx, qz, target, beforeLast)) {
               Object cached = memo.bgBiome[slot];
               if (memo.checkNow()) {
                  Object real = original.call(list, target);
                  boolean same = real == cached;
                  if (!same) {
                     memo.bgDisabled = true;
                  }
                  BiomeFill.checked(same, "underground background biome");
                  return real;
               }
               Object leaf = memo.bgLeaf[slot];
               if (beforeLast != leaf) {
                  lastResult.set(leaf);
               }
               return cached;
            }

            Object value = original.call(list, target);
            Object afterLeaf = lastResult.get();
            memo.rememberBg(list, slot, qx, qz, target, value, beforeLast, afterLeaf);
            return value;
         }

         return original.call(list, target);
      } finally {
         if (timed) {
            Stats.recordSampled(Stage.BIOME_LAYOUT_SEARCH, System.nanoTime() - start, 16);
         }
      }
   }
}
