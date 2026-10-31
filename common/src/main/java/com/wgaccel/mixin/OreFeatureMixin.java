package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.wgaccel.gen.FastOres;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** S8 ores: the same answers as the game's calls, without a chunk lookup per column or a height range per block ({@link FastOres}). */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
   @WrapOperation(method = "place", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/WorldGenLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
   private int wgaccel$height(WorldGenLevel level, Heightmap.Types type, int x, int z, Operation<Integer> original,
         @Share("wgaccelChunks") LocalRef<FastOres.Chunks> chunks, @Share("wgaccelBelow") LocalIntRef below,
         @Local(index = 22) int minX, @Local(index = 23) int y, @Local(index = 24) int minZ, @Local(index = 25) int size) {
      if (level instanceof WorldGenRegion region && FastOres.on()) {
         FastOres.Chunks call = chunks.get();
         if (call == null || call.region() != region) {
            call = new FastOres.Chunks(region);
            chunks.set(call);
         }

         // The box's first column: whether no column can be high enough (2: every column then answers below y).
         if (below.get() == 0) {
            below.set(type == Heightmap.Types.OCEAN_FLOOR_WG && FastOres.belowAll(call, minX, minX + size, minZ, minZ + size, y) ? 2 : 1);
            if (below.get() == 2 && FastOres.sample()) {
               boolean none = true;
               for (int cx = minX; cx <= minX + size && none; cx++) {
                  for (int cz = minZ; cz <= minZ + size && none; cz++) {
                     none = y > original.call(level, type, cx, cz);
                  }
               }

               if (FastOres.verify(1, none ? 1 : 0) == 0) {
                  below.set(1);
               }
            }
         }

         if (below.get() == 2) {
            return Integer.MIN_VALUE;
         }

         int height = FastOres.height(call, type, x, z);
         return FastOres.sample() ? FastOres.verify(height, original.call(level, type, x, z)) : height;
      }

      return original.call(level, type, x, z);
   }

   /** Asked for every block of an ore: a redirect (a @WrapOperation made an Operation per call: 44% of doPlace, 31 Oct 2026). */
   @Redirect(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;isOutsideBuildHeight(I)Z"))
   private boolean wgaccel$outside(WorldGenLevel level, int y) {
      return FastOres.on() ? FastOres.outside(level, y) : level.isOutsideBuildHeight(y);
   }
}
