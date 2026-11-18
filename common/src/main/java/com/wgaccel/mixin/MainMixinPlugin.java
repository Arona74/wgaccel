package com.wgaccel.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
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
   /** Lithium overwrites pack with its own optimized serializer; injecting into that overwrite is both redundant and invalid. */
   private boolean lithium;
   /** C2ME native math replaces getBiome with its native corner sampler. Keep that path instead of injecting into it. */
   private boolean nativeBiomeMath;
   /** ScalableLux replaces Vanilla LevelLightEngine/ThreadedLevelLightEngine entirely. */
   private boolean scalableLux;
   /** Nature's Spirit merges SurfaceBuilderMixin pillar methods into SurfaceSystem when installed. */
   private boolean naturesSpirit;
   /**
    * Only apply WorldGenRegionReadsMixin and its predicate scope mixins when fastBlockPredicates is enabled in
    * wgaccel-common.toml, avoiding CallbackInfoReturnable and Scope allocations on every getBlockState when off.
    */
   private boolean fastBlockPredicates;

   @Override
   public void onLoad(String mixinPackage) {
      try {
         this.byePregen = com.wgaccel.platform.Platform.get().isModLoaded("byepregen");
         this.lithium = com.wgaccel.platform.Platform.get().isModLoaded("lithium");
         this.nativeBiomeMath = com.wgaccel.platform.Platform.get().isModLoaded("c2me_opts_natives_math");
         this.scalableLux = com.wgaccel.platform.Platform.get().isModLoaded("scalablelux");
         this.naturesSpirit = com.wgaccel.platform.Platform.get().isModLoaded("natures_spirit");
         this.fastBlockPredicates = readFastBlockPredicatesSetting(com.wgaccel.platform.Platform.get().configDir());
      } catch (RuntimeException | LinkageError failure) {
         this.byePregen = false;
         this.lithium = false;
         this.nativeBiomeMath = false;
         this.scalableLux = false;
         this.naturesSpirit = false;
         this.fastBlockPredicates = false;
      }
   }

   private static boolean readFastBlockPredicatesSetting(Path configDir) {
      if (configDir == null) {
         return false;
      }

      Path toml = configDir.resolve("wgaccel-common.toml");
      if (!Files.isRegularFile(toml)) {
         return false;
      }

      try {
         boolean cpuImprovements = true;
         boolean customPreset = false;
         boolean fileFastBlockPredicates = false;
         boolean overrideEnabled = false;
         for (String raw : Files.readAllLines(toml)) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty() || line.startsWith("[")) {
               continue;
            }

            if (line.startsWith("overrides") && line.contains("fastBlockPredicates=true")) {
               overrideEnabled = true;
            } else if (line.startsWith("preset") && line.contains("\"CUSTOM\"")) {
               customPreset = true;
            } else if (line.startsWith("cpuImprovements") && line.endsWith("false")) {
               cpuImprovements = false;
            } else if (line.startsWith("fastBlockPredicates") && line.endsWith("true")) {
               fileFastBlockPredicates = true;
            }
         }

         return cpuImprovements && (overrideEnabled || (customPreset && fileFastBlockPredicates));
      } catch (java.io.IOException | SecurityException ignored) {
         return false;
      }
   }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      if (!this.fastBlockPredicates && (mixinClassName.endsWith(".WorldGenRegionReadsMixin")
            || mixinClassName.endsWith(".BlockPredicateFilterMixin")
            || mixinClassName.endsWith(".RuleBasedStateProviderMixin"))) {
         return false;
      }
      if (this.lithium && mixinClassName.endsWith(".PalettedContainerPackMixin")) return false;
      if (this.nativeBiomeMath && mixinClassName.endsWith(".BiomeZoomMixin")) return false;
      if (this.byePregen && mixinClassName.endsWith(".DiskFeatureMixin")) {
         return false;
      }
      if (!this.naturesSpirit && mixinClassName.endsWith(".NaturesSpiritSurfaceMixin")) {
         return false;
      }
      if (this.scalableLux && (mixinClassName.endsWith(".ThreadedLevelLightEngineGpuLightMixin")
            || mixinClassName.endsWith(".LevelLightEngineAccessor")
            || mixinClassName.endsWith(".LightEngineAccessor")
            || mixinClassName.endsWith(".LayerLightSectionStorageAccessor"))) {
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
