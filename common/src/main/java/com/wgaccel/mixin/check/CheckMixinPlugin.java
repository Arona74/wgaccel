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
 * Applies the check mixins (taps on the game's own worldgen values, for comparing the GPU's) only when the config's
 * gpuMode is in effect CHECK. Mixins load before mod configs, so the file is read here directly, as the setting reads it:
 * the settings screen's override (general.overrides "gpuMode=CHECK") wins; else the file's gpuMode counts only with
 * preset = "CUSTOM" (the presets give SELFTEST); gpuImprovements = false turns it off. Until 0.61.4 the file's line
 * alone counted, and the file's default is CHECK: every default config carried the per-block taps (31 Oct 2026).
 * Other runs carry none of their per-block cost.
 */
public final class CheckMixinPlugin implements IMixinConfigPlugin {
   private boolean check;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         Path config = Platform.get().configDir().resolve("wgaccel-common.toml");
         this.check = Files.exists(config) && check(Files.readAllLines(config, StandardCharsets.UTF_8));
      } catch (Exception | LinkageError failure) {
         this.check = false;
      }
   }

   /** Whether these config lines make gpuMode CHECK in effect. */
   static boolean check(List<String> lines) {
      String preset = null;
      String mode = null;
      String overrides = "";
      boolean gpu = true;
      for (String line : lines) {
         String compact = line.trim().replace(" ", "");
         if (compact.startsWith("preset=")) {
            preset = compact.substring("preset=".length());
         } else if (compact.startsWith("gpuMode=")) {
            mode = compact.substring("gpuMode=".length());
         } else if (compact.startsWith("overrides=")) {
            overrides = compact.substring("overrides=".length());
         } else if (compact.equals("gpuImprovements=false")) {
            gpu = false;
         }
      }

      if (!gpu) {
         return false;
      }

      java.util.regex.Matcher chosen = java.util.regex.Pattern.compile("\"gpuMode=([A-Z]+)\"").matcher(overrides);
      if (chosen.find()) {
         return chosen.group(1).equals("CHECK");
      }

      return "\"CUSTOM\"".equals(preset) && "\"CHECK\"".equals(mode);
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
