package com.wgaccel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.wgaccel.stats.Reporter;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /wgaccel}: the run's summary in the chat and the log. {@code /wgaccel reset}: logs that summary and starts a
 * new run (do this just before {@code /chunky start} for a benchmark). {@code /wgaccel profile [seconds]}: where the
 * generation threads' time goes ({@link com.wgaccel.stats.WorkerProfile}).
 */
public final class WgaCommands {
   private WgaCommands() {
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      dispatcher.register(Commands.literal("wgaccel")
         .executes(context -> show(context, Reporter.summary()))
         .then(Commands.literal("reset").executes(context -> {
            List<String> before = Reporter.reset(Reporter.timeLabel());
            before.forEach(WorldgenAccelerator.LOG::info);
            context.getSource().sendSuccess(() -> Component.literal("Worldgen Accelerator: new measurement started (the last one is in the log)"), false);
            return 1;
         }))
         .then(Commands.literal("profile")
            .executes(context -> profile(context, 60))
            .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 600))
               .executes(context -> profile(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds")))))
         .then(Commands.literal("lod").then(Commands.literal("bench")
            .executes(context -> lodBench(context, 16, 2, 1))
            .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1024))
               .executes(context -> lodBench(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"), 2, 1))
               .then(Commands.argument("threads", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                  .executes(context -> lodBench(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"),
                     com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads"), 1))
                  .then(Commands.argument("stride", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                     .executes(context -> lodBench(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads"),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "stride")))))))
            .then(Commands.literal("dh")
               .executes(context -> lodDh(context, 4, 16, 4))
               .then(Commands.argument("detail", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 12))
                  .executes(context -> lodDh(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "detail"), 16, 4))
                  .then(Commands.argument("sections", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1024))
                     .executes(context -> lodDh(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "detail"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "sections"), 4))
                     .then(Commands.argument("threads", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 16))
                        .executes(context -> lodDh(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "detail"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "sections"),
                           com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads")))))))
            .then(Commands.literal("voxy")
               .executes(context -> lodVoxy(context, 64, 4, 1, 0))
               .then(Commands.literal("stop").executes(context -> com.wgaccel.gen.VoxyLodGenerator.stop(reporter(context)) ? 1 : 0))
               .then(Commands.literal("reset").executes(context -> {
                  int count = com.wgaccel.gen.VoxyAuto.reset();
                  reporter(context).accept(count < 0 ? "WGA Voxy LOD generator: the automatic generator is not running (Voxy not installed)"
                     : "WGA Voxy LOD generator: " + count + " tiles done forgotten; the automatic generator makes them again");
                  return count < 0 ? 0 : 1;
               }))
               .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 4096))
                  .executes(context -> lodVoxy(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"), 4, 1, 0))
                  .then(Commands.argument("threads", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 32))
                     .executes(context -> lodVoxy(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads"), 1, 0))
                     .then(Commands.argument("stride", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                        .executes(context -> lodVoxy(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads"),
                           com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "stride"), 0))
                        .then(Commands.argument("from", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4096))
                           .executes(context -> lodVoxy(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "threads"),
                              com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "stride"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "from"))))))))));
   }

   /** Report lines to the log (ALWAYS: they share their text up to the first digit) and to the caller. */
   private static java.util.function.Consumer<String> reporter(CommandContext<CommandSourceStack> context) {
      CommandSourceStack source = context.getSource();
      return line -> {
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, line);
         source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), false));
      };
   }

   /**
    * {@code /wgaccel lod voxy [radius] [threads] [stride] [from]}: Voxy's LODs of the square of chunks around the caller
    * (radius in chunks; with from, a ring: chunks nearer than from left out) made from the GPU columns ({@link
    * com.wgaccel.gen.VoxyLodGenerator}), one column every stride blocks (1-16; 32, 64, 128 or 256: one column every
    * stride / 16 chunks, the heights between interpolated); {@code /wgaccel lod voxy stop} stops it. With Voxy installed.
    */
   private static int lodVoxy(CommandContext<CommandSourceStack> context, int radius, int threads, int stride, int from) {
      CommandSourceStack source = context.getSource();
      java.util.function.Consumer<String> report = reporter(context);
      if (Integer.bitCount(stride) != 1) {
         report.accept("WGA Voxy LOD generator: the stride is a power of two: 1 to 16, or 32 to 256 (one column every stride / 16 chunks)");
         return 0;
      }

      return com.wgaccel.gen.VoxyLodGenerator.start(source.getLevel(), net.minecraft.util.Mth.floor(source.getPosition().x) >> 4,
         net.minecraft.util.Mth.floor(source.getPosition().z) >> 4, radius, from, threads, stride, report) ? 1 : 0;
   }

   /**
    * {@code /wgaccel lod dh [detail] [sections] [threads]}: Distant Horizons' LOD sections around the caller made by the
    * GPU LOD generator ({@link com.wgaccel.gen.DhLodGenerator}), each column checked as DH checks it; with DH installed.
    */
   private static int lodDh(CommandContext<CommandSourceStack> context, int detail, int sections, int threads) {
      CommandSourceStack source = context.getSource();
      net.minecraft.server.level.ServerLevel level = source.getLevel();
      java.util.function.Consumer<String> report = line -> {
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, line);
         level.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), false));
      };
      if (!com.wgaccel.platform.Platform.get().isModLoaded("distanthorizons")) {
         report.accept("WGA Distant Horizons bench: Distant Horizons is not installed");
         return 0;
      }

      return com.wgaccel.gen.DhLodGenerator.bench(level, net.minecraft.util.Mth.floor(source.getPosition().x) >> 4,
         net.minecraft.util.Mth.floor(source.getPosition().z) >> 4, detail, sections, threads, report) ? 1 : 0;
   }

   /**
    * {@code /wgaccel lod bench [radius] [threads] [stride]}: the LOD prototype around the caller, radius in chunks, one
    * column in stride x stride ({@link com.wgaccel.gen.LodColumns}).
    */
   private static int lodBench(CommandContext<CommandSourceStack> context, int radius, int threads, int stride) {
      CommandSourceStack source = context.getSource();
      net.minecraft.server.level.ServerLevel level = source.getLevel();
      int chunkX = net.minecraft.util.Mth.floor(source.getPosition().x) >> 4;
      int chunkZ = net.minecraft.util.Mth.floor(source.getPosition().z) >> 4;
      java.util.function.Consumer<String> report = line -> {
         // ALWAYS: the bench's lines share their text up to the first digit, which the logger otherwise keeps once.
         WorldgenAccelerator.LOG.info(LevelLogger.ALWAYS, line);
         level.getServer().execute(() -> source.sendSuccess(() -> Component.literal(line), false));
      };
      return com.wgaccel.gen.LodColumns.bench(level, chunkX, chunkZ, radius, threads, stride, report) ? 1 : 0;
   }

   private static int profile(CommandContext<CommandSourceStack> context, int seconds) {
      boolean started = com.wgaccel.stats.WorkerProfile.start(seconds);
      context.getSource().sendSuccess(() -> Component.literal(started
         ? "Worldgen Accelerator: profiling the generation threads for " + seconds + " s (the result goes to the log)"
         : "Worldgen Accelerator: a profile is already running"), false);
      return started ? 1 : 0;
   }

   private static int show(CommandContext<CommandSourceStack> context, List<String> lines) {
      for (String line : lines) {
         WorldgenAccelerator.LOG.info(line);
         context.getSource().sendSuccess(() -> Component.literal(line), false);
      }

      return 1;
   }
}
