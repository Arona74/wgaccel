package com.wgaccel.stats;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** One block of lines at world start: the machine, Java, the mods that matter for chunk speed and the overworld's height. */
public final class Environment {
   /** Mods that change how fast chunks generate, by mod id. */
   private static final String[] MODS = {
      "freeterraforged", "c2me", "c2me_opts_accel_opencl", "scalablelux", "zfastnoise", "lithium", "ferritecore",
      "structure_layout_optimizer", "moonrise", "noisium", "chunky", "voxy", "distanthorizons", "sodium", "iris",
      "settlements2"
   };
   private static final Pattern PARALLELISM = Pattern.compile("^\\s*globalExecutorParallelism\\s*=\\s*(.+)$");

   private Environment() {
   }

   public static void log(MinecraftServer server) {
      Runtime runtime = Runtime.getRuntime();
      log(String.format(Locale.ROOT, "WGA Worldgen Accelerator %s: measuring (P0, P0b) and fill-to-ground (B1) %s", version(),
         Config.FILL_TO_GROUND.getAsBoolean() ? "on" : "off (config fillToGround)"));
      log(String.format(Locale.ROOT, "WGA machine: %d CPU threads, %s; Java %s (%s); heap max %.1f GB",
         runtime.availableProcessors(), System.getProperty("os.name"), System.getProperty("java.version"),
         System.getProperty("java.vm.name"), runtime.maxMemory() / 1.073741824e9));
      List<String> flags = new ArrayList<>();
      for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
         if (argument.startsWith("-XX:") || argument.startsWith("-Xm") || argument.startsWith("-Dchunky")) {
            flags.add(argument);
         }
      }

      log("WGA JVM flags: " + (flags.isEmpty() ? "none" : String.join(" ", flags)));
      List<String> mods = new ArrayList<>();
      for (String id : MODS) {
         ModList.get().getModContainerById(id).ifPresent(container -> mods.add(id + " " + container.getModInfo().getVersion()));
      }

      log("WGA mods: " + (mods.isEmpty() ? "none of the ones that matter" : String.join(", ", mods)));
      String parallelism = c2meParallelism();
      if (parallelism != null) {
         log("WGA C2ME globalExecutorParallelism = " + parallelism);
         // C2ME's default gave 7 workers on 16 threads, busy only ~60% (waiting on each other), the CPU 11 of 16 busy;
         // 12 made the same run 1:09 -> 0:56 (23 Oct 2026, 7800X3D + RX 9070 XT, FreeTerraForged 1.0.0013, r1000).
         if (parallelism.replace("\"", "").equalsIgnoreCase("default") && runtime.availableProcessors() >= 8) {
            log(String.format(Locale.ROOT, "WGA hint: C2ME uses its default number of workers; with the GPU parts taking work off the CPU, more are"
               + " usually faster for pregeneration (globalExecutorParallelism in config/c2me.toml, e.g. %d on this %d-thread CPU; see the README)",
               Math.max(1, runtime.availableProcessors() * 3 / 4), runtime.availableProcessors()));
         }
      }

      ServerLevel overworld = server.overworld();
      ChunkGenerator generator = overworld.getChunkSource().getGenerator();
      String noise = "";
      if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
         NoiseSettings settings = noiseGenerator.generatorSettings().value().noiseSettings();
         noise = String.format(Locale.ROOT, "; noise from y %d, %d tall, cells %d x %d; sea level %d",
            settings.minY(), settings.height(), settings.getCellWidth(), settings.getCellHeight(), noiseGenerator.getSeaLevel());
      }

      log(String.format(Locale.ROOT, "WGA overworld: %s, build height y %d to %d%s; seed %d",
         generator.getClass().getSimpleName(), overworld.getMinBuildHeight(), overworld.getMaxBuildHeight(), noise, overworld.getSeed()));
   }

   private static String version() {
      return ModList.get().getModContainerById(WorldgenAccelerator.MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
   }

   /** C2ME's globalExecutorParallelism as its config file has it ("default" or a number), or null without C2ME's config. */
   public static String c2meParallelism() {
      Path file = FMLPaths.CONFIGDIR.get().resolve("c2me.toml");
      if (!Files.isRegularFile(file)) {
         return null;
      }

      try {
         for (String line : Files.readAllLines(file)) {
            Matcher matcher = PARALLELISM.matcher(line);
            if (matcher.matches()) {
               return matcher.group(1).trim();
            }
         }
      } catch (IOException ignored) {
      }

      return null;
   }

   private static void log(String line) {
      WorldgenAccelerator.LOG.info(line);
   }
}
