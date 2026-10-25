package com.wgaccel.neoforge;

import com.wgaccel.platform.Platform;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * {@link Platform} on NeoForge. Before the mods are made (the mixin plugins) only the loading mod list exists, so the
 * mods are looked up there until NeoForge's ModList is ready.
 */
public final class NeoForgePlatform implements Platform {
   @Override
   public boolean isModLoaded(String id) {
      ModList mods = ModList.get();
      return mods != null ? mods.isLoaded(id) : LoadingModList.get().getModFileById(id) != null;
   }

   @Override
   public String modVersion(String id) {
      ArtifactVersion version = this.version(id);
      return version != null ? version.toString() : null;
   }

   @Override
   public boolean modAtLeast(String id, String version) {
      ArtifactVersion installed = this.version(id);
      return installed != null && installed.compareTo(new DefaultArtifactVersion(version)) >= 0;
   }

   private ArtifactVersion version(String id) {
      ModList mods = ModList.get();
      if (mods != null) {
         return mods.getModContainerById(id).map(container -> container.getModInfo().getVersion()).orElse(null);
      }

      return LoadingModList.get().getMods().stream().filter(mod -> mod.getModId().equals(id)).findFirst().map(IModInfo::getVersion).orElse(null);
   }

   @Override
   public Path gameDir() {
      return FMLPaths.GAMEDIR.get();
   }

   @Override
   public Path configDir() {
      return FMLPaths.CONFIGDIR.get();
   }

   @Override
   public boolean isDedicatedServer() {
      return FMLEnvironment.dist.isDedicatedServer();
   }

   @Override
   public String loader() {
      return "NeoForge";
   }

   @Override
   public String loaderVersion() {
      return this.modVersion("neoforge");
   }
}
