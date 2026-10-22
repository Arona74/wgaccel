package com.wgaccel.mixin.ftf;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the FreeTerraForged mixins only when FreeTerraForged is installed. */
public final class FtfMixinPlugin implements IMixinConfigPlugin {
   private boolean present;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         this.present = LoadingModList.get().getModFileById("freeterraforged") != null;
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
