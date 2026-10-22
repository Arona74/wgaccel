package com.wgaccel.mixin.c2me;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the C2ME chunk-system mixins (the 4 x 4 area step) only when C2ME's chunk system module is installed. */
public final class C2meMixinPlugin implements IMixinConfigPlugin {
   private boolean present;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         this.present = LoadingModList.get().getModFileById("c2me_rewrites_chunk_system") != null;
      } catch (RuntimeException | LinkageError failure) {
         this.present = false;
      }
   }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      return this.present && !com.wgaccel.Conflicts.openCl();
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
