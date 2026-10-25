package com.wgaccel.mixin.check;

import com.wgaccel.platform.Platform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the check mixins (taps on the game's own worldgen values, for comparing the GPU's) only when
 * config/wgaccel-common.toml says gpuMode = "CHECK". Mixins load before mod configs, so the file is read here directly;
 * other runs carry none of their per-block cost.
 */
public final class CheckMixinPlugin implements IMixinConfigPlugin {
   private boolean check;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         Path config = Platform.get().configDir().resolve("wgaccel-common.toml");
         this.check = Files.exists(config) && Files.readAllLines(config, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .anyMatch(line -> line.replace(" ", "").equals("gpuMode=\"CHECK\""));
      } catch (Exception | LinkageError failure) {
         this.check = false;
      }
   }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      return this.check && !com.wgaccel.Conflicts.openCl();
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
