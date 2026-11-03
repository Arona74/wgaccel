package com.wgaccel.mixin.ftf;

import com.wgaccel.platform.Platform;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the FreeTerraForged mixins only when FreeTerraForged is installed. */
public final class FtfMixinPlugin implements IMixinConfigPlugin {
   private boolean present;
   /** FreeTerraForged 1.0.0024 or later (its continents' PlateUpliftBridge, which ContinentCoastMixin reads). */
   private boolean continents24;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         this.present = Platform.get().isModLoaded("freeterraforged");
         this.continents24 = this.present && Platform.get().modAtLeast("freeterraforged", "1.0.0024");
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
      if (mixinClassName.endsWith(".ContinentCoastMixin") && !this.continents24) {
         return false;
      }

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
