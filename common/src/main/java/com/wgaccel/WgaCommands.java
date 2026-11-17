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
               .executes(context -> profile(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds"))))));
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
