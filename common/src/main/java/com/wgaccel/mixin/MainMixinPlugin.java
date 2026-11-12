package com.wgaccel.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies Worldgen Accelerator's own mixins unless C2ME's OpenCL module is installed ({@link com.wgaccel.Conflicts}). */
public final class MainMixinPlugin implements IMixinConfigPlugin {
   /**
    * ByePregen installed: it overwrites DiskFeature.place, and Mixin refuses (a crash at start, require = 0 or not) to
    * inject into a method another mod's mixin merged, so our redirect inside it stays out (a user's log, 12 Nov 2026,
    * ByePregen 1.2.0.0).
    */
   private boolean byePregen;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         this.byePregen = com.wgaccel.platform.Platform.get().isModLoaded("byepregen");
      } catch (RuntimeException | LinkageError failure) {
         this.byePregen = false;
      }
   }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      if (this.byePregen && mixinClassName.endsWith(".DiskFeatureMixin")) {
         return false;
      }

      return !com.wgaccel.Conflicts.openCl();
   }

   @Override
   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   @Override
   public List<String> getMixins() {
      return null;
   }

   @Override
   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   @Override
   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }
}
