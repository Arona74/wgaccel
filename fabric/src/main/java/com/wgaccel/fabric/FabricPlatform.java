package com.wgaccel.fabric;

import com.wgaccel.platform.Platform;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * {@link Platform} on Fabric: Fabric Loader knows every mod from the start, mixin plugins included. The mod asks by
 * NeoForge's ids, which cannot have dashes: C2ME's modules are c2me_rewrites_chunk_system there and
 * c2me-rewrites-chunk-system here, so an id with underscores is also looked up with dashes.
 */
public final class FabricPlatform implements Platform {
   @Override
   public boolean isModLoaded(String id) {
      return this.container(id).isPresent();
   }

   @Override
   public String modVersion(String id) {
      return this.container(id).map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
   }

   private Optional<ModContainer> container(String id) {
      Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(id);
      return container.isPresent() || id.indexOf('_') < 0 ? container : FabricLoader.getInstance().getModContainer(id.replace('_', '-'));
   }

   @Override
   public boolean modAtLeast(String id, String version) {
      Version installed = this.container(id).map(container -> container.getMetadata().getVersion()).orElse(null);
      try {
         return installed != null && installed.compareTo(Version.parse(version)) >= 0;
      } catch (VersionParsingException unreadable) {
         return false;
      }
   }

   @Override
   public Path gameDir() {
      return FabricLoader.getInstance().getGameDir();
   }

   @Override
   public Path configDir() {
      return FabricLoader.getInstance().getConfigDir();
   }

   @Override
   public boolean isDedicatedServer() {
      return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
   }

   @Override
   public String loader() {
      return "Fabric";
   }

   @Override
   public String loaderVersion() {
      return this.modVersion("fabricloader");
   }
}
