package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.FastOres;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S8 ores: getSection's section index and count from the level's range read once ({@link FastOres}); the same answers. */
@Mixin(BulkSectionAccess.class)
public abstract class BulkSectionAccessMixin {
   @WrapOperation(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionIndex(I)I"))
   private int wgaccel$sectionIndex(LevelAccessor level, int y, Operation<Integer> original) {
      if (FastOres.on()) {
         int index = FastOres.sectionIndex(level, y);
         return FastOres.sample() ? FastOres.verify(index, original.call(level, y)) : index;
      }

      return original.call(level, y);
   }

   @WrapOperation(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionsCount()I"))
   private int wgaccel$sectionsCount(LevelAccessor level, Operation<Integer> original) {
      if (FastOres.on()) {
         int count = FastOres.sectionsCount(level);
         return FastOres.sample() ? FastOres.verify(count, original.call(level)) : count;
      }

      return original.call(level);
   }
}
