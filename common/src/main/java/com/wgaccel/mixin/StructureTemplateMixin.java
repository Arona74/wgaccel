package com.wgaccel.mixin;

import com.wgaccel.gen.JigsawLists;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds the template's rotated jigsaw lists ({@link JigsawLists}), dropped whenever its blocks are replaced. */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements JigsawLists.Template {
   @Unique
   private volatile List<?>[] wgaccel$jigsaws;

   @Override
   public List<?>[] wgaccel$jigsaws() {
      return this.wgaccel$jigsaws;
   }

   @Override
   public void wgaccel$jigsaws(List<?>[] lists) {
      this.wgaccel$jigsaws = lists;
   }

   // Both clear the palettes and make new ones (a template read from its file, or saved by a structure block).
   @Inject(method = {"load", "fillFromWorld"}, at = @At("HEAD"))
   private void wgaccel$replaced(CallbackInfo info) {
      this.wgaccel$jigsaws = null;
   }
}
