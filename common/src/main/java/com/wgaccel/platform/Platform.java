package com.wgaccel.platform;

import java.nio.file.Path;

/**
 * What the mod asks of its mod loader (NeoForge or Fabric), answered by the loader's own module (neoforge/, fabric/).
 * Usable from the mixin plugins on, before any mixin applies. Outside the game (the offline checks) there is no loader:
 * {@link #get()} throws, as NeoForge's ModList and FMLPaths did there.
 */
public interface Platform {
   /** Whether a mod is installed, by its mod id. */
   boolean isModLoaded(String id);

   /** An installed mod's version as its loader shows it, or null when it is not installed. */
   String modVersion(String id);

   /** Whether a mod is installed at this version or a later one (FreeTerraForged's 1.0.0013 and later). */
   boolean modAtLeast(String id, String version);

   /** The game instance's folder (logs/, wgaccel-gpu-cache/). */
   Path gameDir();

   /** The instance's config folder (wgaccel-common.toml, c2me.toml). */
   Path configDir();

   boolean isDedicatedServer();

   /** The loader's name for the logs and the settings screen ("NeoForge", "Fabric"). */
   String loader();

   /** The loader's own version (NeoForge's, or Fabric Loader's), or null when it does not say. */
   String loaderVersion();

   /** The loader as the screens show it: "NeoForge 21.1.249", "Fabric 0.19.3, Fabric API 0.116.17+1.21.1". */
   default String describeLoader() {
      String version = this.loaderVersion();
      String api = this.modVersion("fabric-api");
      return this.loader() + (version != null ? " " + version : "") + (api != null ? ", Fabric API " + api : "");
   }

   /** The loader's implementation; throws outside the game. */
   static Platform get() {
      Platform platform = Holder.instance;
      if (platform == null) {
         platform = Holder.find();
      }

      return platform;
   }

   /** Shorthand for {@code get().gameDir().resolve("logs")}: where the mod's sources and samples are saved. */
   static Path logs() {
      return get().gameDir().resolve("logs");
   }

   final class Holder {
      private static final String[] IMPLEMENTATIONS = {"com.wgaccel.neoforge.NeoForgePlatform", "com.wgaccel.fabric.FabricPlatform"};
      private static volatile Platform instance;

      private Holder() {
      }

      private static synchronized Platform find() {
         if (instance != null) {
            return instance;
         }

         for (String name : IMPLEMENTATIONS) {
            try {
               instance = (Platform)Class.forName(name).getDeclaredConstructor().newInstance();
               return instance;
            } catch (ReflectiveOperationException | LinkageError absent) {
               // The other loader's class, or the loader's own classes missing (outside the game).
            }
         }

         throw new IllegalStateException("no mod loader (outside the game)");
      }
   }
}
