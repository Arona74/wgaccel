package com.wgaccel.gpu;

import java.io.InputStream;
import java.util.Properties;

/**
 * Whether LWJGL's core (Minecraft's own copy, which the shaded OpenCL and CUDA bindings call) is loaded, at the version
 * they were built for. The client always has it; a dedicated server only with wgaccel-lwjgl-server-<version>.jar in its
 * mods folder (or LWJGL added to its class path by hand). Without it every GPU feature stays off and the work runs on
 * the CPU as usual. No LWJGL classes are named here, so this class loads either way.
 */
public final class Lwjgl {
   /** The LWJGL version the jar's OpenCL and CUDA classes were built against (lwjgl_version, versions/<mc>/gradle.properties). */
   public static final String EXPECTED = expected();
   /** The loaded core's version (major.minor.revision), or null when there is none. */
   public static final String LOADED = loaded();
   public static final boolean PRESENT = LOADED != null && (EXPECTED == null || EXPECTED.equals(LOADED));

   private Lwjgl() {
   }

   private static String expected() {
      try (InputStream in = Lwjgl.class.getResourceAsStream("/wgaccel-lwjgl.properties")) {
         if (in == null) {
            return null;
         }
         Properties properties = new Properties();
         properties.load(in);
         return properties.getProperty("version");
      } catch (Exception e) {
         return null;
      }
   }

   private static String loaded() {
      try {
         Class<?> version = Class.forName("org.lwjgl.Version", false, Lwjgl.class.getClassLoader());
         return version.getField("VERSION_MAJOR").get(null) + "." + version.getField("VERSION_MINOR").get(null) + "."
            + version.getField("VERSION_REVISION").get(null);
      } catch (ClassNotFoundException | LinkageError missing) {
         return null;
      } catch (ReflectiveOperationException e) {
         return null;
      }
   }

   public static final String MISSING = missing();

   private static String missing() {
      String version = EXPECTED == null ? "<version>" : EXPECTED;
      if (LOADED != null) {
         return "LWJGL " + LOADED + " is loaded but this build of Worldgen Accelerator needs LWJGL " + version
            + " (on a dedicated server: use wgaccel-lwjgl-server-" + version + ".jar from the release page in mods/; see the README)";
      }
      return "LWJGL is not loaded (a dedicated server has none: put wgaccel-lwjgl-server-" + version
         + ".jar from the release page in the server's mods/ folder, never on a client; see the README)";
   }
}
