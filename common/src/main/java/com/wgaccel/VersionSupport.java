package com.wgaccel;

import com.wgaccel.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Checks installed FreeTerraForged, terrain, and biome mods against WGAccel's verified compatibility list
 * and logs/displays warnings when an untested version or unlisted biome/terrain mod is active.
 */
public final class VersionSupport {
   public enum Category {
      FTF("FreeTerraForged"),
      TERRAIN("terrain mod"),
      BIOME("biome mod");

      final String label;

      Category(String label) {
         this.label = label;
      }
   }

   private record SupportedMod(
         String modId,
         String displayName,
         Category category,
         String minVersion,
         String maxVersion,
         String displayRange) {
   }

   private record UnsupportedMod(String modId, String displayName, Category category) {
   }

   private static final List<SupportedMod> SUPPORTED = List.of(
      new SupportedMod("freeterraforged", "FreeTerraForged", Category.FTF, "1.0.0010", "1.0.0032", "1.0.0010 - 1.0.0032"),
      new SupportedMod("reterraforged", "Conquest ReTerraForged", Category.TERRAIN, "0.1.0", "0.1.99", "0.1.0"),
      new SupportedMod("roadweaver", "RoadWeaver", Category.TERRAIN, "2.3.0", "2.3.99", "2.3.2"),
      new SupportedMod("wwoo", "William Wythers' Overhauled Overworld (WWOO)", Category.BIOME, "2.6.0", "2.6.99", "2.6.7"),
      new SupportedMod("natures_spirit", "Nature's Spirit", Category.BIOME, "2.3.0", "2.3.99", "2.3.1 - 2.3.2"),
      new SupportedMod("biomesoplenty", "Biomes O' Plenty", Category.BIOME, "21.1.0.0", "21.1.0.99", "21.1.0.14"),
      new SupportedMod("regions_unexplored", "Regions Unexplored", Category.BIOME, "0.6.0", "0.6.99", "0.6.2"),
      new SupportedMod("terralith", "Terralith", Category.BIOME, "2.6.0", "2.6.99", "2.6.2"),
      new SupportedMod("mr_still_life", "Still Life (FTF edition)", Category.BIOME, "0.3.0", "0.3.99", "0.3-ftf"),
      new SupportedMod("still_life", "Still Life", Category.BIOME, "0.3.0", "0.3.99", "0.3 - 0.3.5"),
      new SupportedMod("stilllife", "Still Life", Category.BIOME, "0.3.0", "0.3.99", "0.3 - 0.3.5"),
      new SupportedMod("stilllife_ftf_bridge", "Still Life x FreeTerraForged Bridge", Category.BIOME, "3.3.0", "3.3.99", "3.3.0"),
      new SupportedMod("nomansland", "No Man's Land", Category.BIOME, "1.6.0", "1.6.99", "1.6.2"),
      new SupportedMod("terrablender", "TerraBlender", Category.BIOME, "4.1.0.0", "4.1.0.99", "4.1.0.8"),
      new SupportedMod("lithostitched", "Lithostitched", Category.BIOME, "1.8.0", "1.8.99", "1.8.0 - 1.8.1"),
      new SupportedMod("biolith", "Biolith", Category.BIOME, "3.0.0", "3.0.99", "3.0.14"),
      new SupportedMod("vanillabackport", "VanillaBackport", Category.BIOME, "1.2.0", "1.2.99", "1.2.1 - 1.2.2")
   );

   private static final List<UnsupportedMod> UNLISTED = List.of(
      new UnsupportedMod("tectonic", "Tectonic", Category.TERRAIN),
      new UnsupportedMod("terratonic", "Terratonic", Category.TERRAIN),
      new UnsupportedMod("bigglobe", "Big Globe", Category.TERRAIN),
      new UnsupportedMod("jjthunder", "JJThunder", Category.TERRAIN),
      new UnsupportedMod("jjt_the_to_the_max", "JJThunder To The Max", Category.TERRAIN),
      new UnsupportedMod("cascades", "Cascades", Category.TERRAIN),
      new UnsupportedMod("continents", "Continents", Category.TERRAIN),
      new UnsupportedMod("larion", "Larion", Category.TERRAIN),
      new UnsupportedMod("modern_beta", "Modern Beta", Category.TERRAIN),
      new UnsupportedMod("moderner_beta", "Moderner Beta", Category.TERRAIN),
      new UnsupportedMod("amplified_nether", "Amplified Nether", Category.TERRAIN),
      new UnsupportedMod("incendium", "Incendium", Category.TERRAIN),
      new UnsupportedMod("nullscape", "Nullscape", Category.TERRAIN),
      new UnsupportedMod("biomeswevegone", "Oh The Biomes We've Gone", Category.BIOME),
      new UnsupportedMod("byg", "Oh The Biomes You'll Go", Category.BIOME),
      new UnsupportedMod("atmospheric", "Atmospheric", Category.BIOME),
      new UnsupportedMod("autumnity", "Autumnity", Category.BIOME),
      new UnsupportedMod("environmental", "Environmental", Category.BIOME),
      new UnsupportedMod("upgrade_aquatic", "Upgrade Aquatic", Category.BIOME),
      new UnsupportedMod("abundance", "Abundance", Category.BIOME),
      new UnsupportedMod("bayou_blues", "Bayou Blues", Category.BIOME),
      new UnsupportedMod("endergetic", "The Endergetic Expansion", Category.BIOME),
      new UnsupportedMod("promenade", "Promenade", Category.BIOME),
      new UnsupportedMod("traverse", "Traverse", Category.BIOME),
      new UnsupportedMod("terrestria", "Terrestria", Category.BIOME),
      new UnsupportedMod("wilderwild", "Wilder Wild", Category.BIOME),
      new UnsupportedMod("geophilic", "Geophilic", Category.BIOME),
      new UnsupportedMod("bloomingnature", "BloomingNature", Category.BIOME),
      new UnsupportedMod("beachparty", "Beachparty", Category.BIOME),
      new UnsupportedMod("meadow", "Meadow", Category.BIOME),
      new UnsupportedMod("nyctophobia", "Nyctophobia", Category.BIOME),
      new UnsupportedMod("alexscaves", "Alex's Caves", Category.BIOME)
   );

   private VersionSupport() {
   }

   /** Returns all warnings for unsupported FTF, terrain, or biome mod versions currently loaded. */
   public static List<String> collectWarnings() {
      Platform platform;
      try {
         platform = Platform.get();
      } catch (RuntimeException | LinkageError outsideGame) {
         return List.of();
      }

      List<String> warnings = new ArrayList<>();
      for (SupportedMod spec : SUPPORTED) {
         String installed = platform.modVersion(spec.modId());
         if (installed == null) {
            continue;
         }

         String status = checkRange(installed, spec.minVersion(), spec.maxVersion());
         if (status != null) {
            warnings.add(String.format(Locale.ROOT,
               "Unsupported %s version: %s %s (%s; verified: %s). WGAccel self-checks will disable any mismatched optimization.",
               spec.category().label, spec.displayName(), installed, status, spec.displayRange()));
         }
      }

      String lithosphere = platform.modVersion("lithosphere");
      if (lithosphere != null) {
         boolean hasStillLife = platform.isModLoaded("mr_still_life") || platform.isModLoaded("still_life") || platform.isModLoaded("stilllife");
         if (!hasStillLife) {
            warnings.add(String.format(Locale.ROOT,
               "Unsupported terrain mod setup: Lithosphere %s without Still Life is untested (verified: 1.8.2 with Still Life 0.3 - 0.3.5).",
               lithosphere));
         } else {
            String status = checkRange(lithosphere, "1.8.0", "1.8.99");
            if (status != null) {
               warnings.add(String.format(Locale.ROOT,
                  "Unsupported terrain mod version: Lithosphere %s (%s; verified: 1.8.2 with Still Life).",
                  lithosphere, status));
            }
         }
      }

      for (UnsupportedMod mod : UNLISTED) {
         String installed = platform.modVersion(mod.modId());
         if (installed != null) {
            warnings.add(String.format(Locale.ROOT,
               "Untested %s detected: %s %s is not on WGAccel's supported list; GPU/CPU self-checks remain active and will turn off incompatible parts.",
               mod.category().label, mod.displayName(), installed));
         }
      }

      return List.copyOf(warnings);
   }

   /** Logs warnings to server/client console on world start when {@code warnUnsupportedVersions} is enabled. */
   public static void logWarnings() {
      if (!Config.WARN_UNSUPPORTED_VERSIONS.getAsBoolean()) {
         return;
      }

      for (String warning : collectWarnings()) {
         WorldgenAccelerator.LOG.warn("WGA version support: {}", warning);
      }
   }

   /** Sends warnings to a joining player in yellow chat when {@code warnUnsupportedVersions} is enabled. */
   public static void warnPlayer(Player player) {
      if (!Config.WARN_UNSUPPORTED_VERSIONS.getAsBoolean()) {
         return;
      }

      for (String warning : collectWarnings()) {
         player.sendSystemMessage(Component.literal("[Worldgen Accelerator] " + warning).withStyle(ChatFormatting.YELLOW));
      }
   }

   /** Package-private for unit tests: returns null when installed is within [min, max] or non-numeric, else reason. */
   static String checkRange(String installed, String min, String max) {
      int[] instParts = parseNumericVersion(installed);
      int[] minParts = parseNumericVersion(min);
      int[] maxParts = parseNumericVersion(max);
      if (instParts == null || minParts == null || maxParts == null) {
         return null;
      }

      if (compare(instParts, minParts) < 0) {
         return "older than " + min;
      }
      if (compare(instParts, maxParts) > 0) {
         return "newer than " + max;
      }
      return null;
   }

   static int[] parseNumericVersion(String version) {
      if (version == null || version.isEmpty() || !Character.isDigit(version.charAt(0))) {
         return null;
      }

      int end = version.length();
      for (int i = 0; i < version.length(); i++) {
         char c = version.charAt(i);
         if (!Character.isDigit(c) && c != '.') {
            end = i;
            break;
         }
      }

      String numeric = version.substring(0, end);
      while (numeric.endsWith(".")) {
         numeric = numeric.substring(0, numeric.length() - 1);
      }
      if (numeric.isEmpty()) {
         return null;
      }

      String[] tokens = numeric.split("\\.");
      int[] parts = new int[tokens.length];
      for (int i = 0; i < tokens.length; i++) {
         if (tokens[i].isEmpty()) {
            return null;
         }
         try {
            parts[i] = Integer.parseInt(tokens[i]);
         } catch (NumberFormatException overflow) {
            return null;
         }
      }
      return parts;
   }

   private static int compare(int[] a, int[] b) {
      int n = Math.max(a.length, b.length);
      for (int i = 0; i < n; i++) {
         int av = i < a.length ? a[i] : 0;
         int bv = i < b.length ? b[i] : 0;
         if (av != bv) {
            return Integer.compare(av, bv);
         }
      }
      return 0;
   }
}
