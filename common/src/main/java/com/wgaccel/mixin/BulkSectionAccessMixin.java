package com.wgaccel.mixin;

import com.wgaccel.gen.FastOres;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * S8 ores: getSection's section index and count from the level's range read once ({@link FastOres}); the same answers.
 * Redirects, not @WrapOperation: those make an Operation object per call (asked for every block of an ore; with
 * DebugNonSafepoints, 31 Oct 2026, vanilla 26.1.2: 6% of doPlace).
 */
@Mixin(BulkSectionAccess.class)
public abstract class BulkSectionAccessMixin {
   @Redirect(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionIndex(I)I"))
   private int wgaccel$sectionIndex(LevelAccessor level, int y) {
      return FastOres.on() ? FastOres.sectionIndex(level, y) : level.getSectionIndex(y);
   }

   @Redirect(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionsCount()I"))
   private int wgaccel$sectionsCount(LevelAccessor level) {
      return FastOres.on() ? FastOres.sectionsCount(level) : level.getSectionsCount();
   }
}
