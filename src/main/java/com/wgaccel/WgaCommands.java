package com.wgaccel;

import com.mojang.brigadier.context.CommandContext;
import com.wgaccel.stats.Reporter;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wgaccel}: the run's summary in the chat and the log. {@code /wgaccel reset}: logs that summary and starts a
 * new run (do this just before {@code /chunky start} for a benchmark).
 */
public final class WgaCommands {
   private WgaCommands() {
   }

   public static void register(RegisterCommandsEvent event) {
      event.getDispatcher().register(Commands.literal("wgaccel")
         .executes(context -> show(context, Reporter.summary()))
         .then(Commands.literal("reset").executes(context -> {
            List<String> before = Reporter.reset(Reporter.timeLabel());
            before.forEach(WorldgenAccelerator.LOG::info);
            context.getSource().sendSuccess(() -> Component.literal("Worldgen Accelerator: new measurement started (the last one is in the log)"), false);
            return 1;
         })));
   }

   private static int show(CommandContext<CommandSourceStack> context, List<String> lines) {
      for (String line : lines) {
         WorldgenAccelerator.LOG.info(line);
         context.getSource().sendSuccess(() -> Component.literal(line), false);
      }

      return 1;
   }
}
