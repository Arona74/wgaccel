package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.wgaccel.gen.FastOres;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S8 ores: the same answers as the game's calls, without a chunk lookup per column or a height range per block ({@link FastOres}). */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
   @WrapOperation(method = "place", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/WorldGenLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
   private int wgaccel$height(WorldGenLevel level, Heightmap.Types type, int x, int z, Operation<Integer> original,
         @Share("wgaccelChunks") LocalRef<FastOres.Chunks> chunks) {
      if (level instanceof WorldGenRegion region && FastOres.on()) {
         FastOres.Chunks call = chunks.get();
         if (call == null || call.region() != region) {
            call = new FastOres.Chunks(region);
            chunks.set(call);
         }

         int height = FastOres.height(call, type, x, z);
         return FastOres.sample() ? FastOres.verify(height, original.call(level, type, x, z)) : height;
      }

      return original.call(level, type, x, z);
   }

   @WrapOperation(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;isOutsideBuildHeight(I)Z"))
   private boolean wgaccel$outside(WorldGenLevel level, int y, Operation<Boolean> original) {
      if (FastOres.on()) {
         boolean outside = FastOres.outside(level, y);
         return FastOres.sample() ? FastOres.verify(outside ? 1 : 0, original.call(level, y) ? 1 : 0) != 0 : outside;
      }

      return original.call(level, y);
   }
}
