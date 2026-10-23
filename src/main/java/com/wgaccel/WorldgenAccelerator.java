package com.wgaccel;

import com.wgaccel.gen.DensityInventory;
import com.wgaccel.gen.GpuNoiseFill;
import com.wgaccel.gpu.GpuSelfTest;
import com.wgaccel.stats.Environment;
import com.wgaccel.stats.Reporter;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worldgen Accelerator: faster chunk generation with FreeTerraForged (the plan is in the project's PLAN.md). This
 * first version only measures: every generation stage is timed and the totals go to the log every 10 seconds.
 */
@Mod(WorldgenAccelerator.MOD_ID)
public final class WorldgenAccelerator {
   public static final String MOD_ID = "wgaccel";
   public static final Logger LOG = LoggerFactory.getLogger("Worldgen Accelerator");

   /** When this thread's chunk-load event began (set by the first listener, read by the last). */
   private static final ThreadLocal<long[]> LOAD_START = ThreadLocal.withInitial(() -> new long[1]);

   public WorldgenAccelerator(IEventBus modBus, ModContainer container) {
      container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
      if (Conflicts.openCl()) {
         // The user, 2026-10-22: with C2ME's OpenCL module the whole mod is off (its mixins too: the mixin plugins ask the same).
         LOG.warn("WGA Worldgen Accelerator is OFF: C2ME's OpenCL module ({}) is installed; remove one of the two", Conflicts.C2ME_OPENCL);
         NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> event.getEntity()
            .sendSystemMessage(net.minecraft.network.chat.Component.literal("[Worldgen Accelerator] Off: C2ME's OpenCL module is installed too. "
               + "The two do the same work in different ways; remove one of them.").withStyle(net.minecraft.ChatFormatting.YELLOW)));
         return;
      }

      modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) -> Setting.invalidate());
      modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> Setting.invalidate());
      GpuSelfTest.cacheDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("wgaccel-gpu-cache");
      NeoForge.EVENT_BUS.addListener(WorldgenAccelerator::onLevelLoad);
      NeoForge.EVENT_BUS.addListener(WorldgenAccelerator::onServerStarted);
      NeoForge.EVENT_BUS.addListener(WorldgenAccelerator::onServerStopping);
      NeoForge.EVENT_BUS.addListener(WorldgenAccelerator::onPlayerLoggedIn);
      NeoForge.EVENT_BUS.addListener(WgaCommands::register);
      NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WorldgenAccelerator::onChunkLoadFirst);
      NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorldgenAccelerator::onChunkLoadLast);
   }

   private static void onChunkLoadFirst(ChunkEvent.Load event) {
      if (!event.getLevel().isClientSide()) {
         LOAD_START.get()[0] = System.nanoTime();
      }
   }

   /** Every other mod's chunk-load listener ran between the first and the last listener (main thread). */
   private static void onChunkLoadLast(ChunkEvent.Load event) {
      long start = LOAD_START.get()[0];
      if (!event.getLevel().isClientSide() && start != 0L) {
         Stats.record(Stage.LOAD_EVENT, System.nanoTime() - start);
         LOAD_START.get()[0] = 0L;
      }
   }

   /**
    * S1: Chunky's chunks in flight (config chunkyMaxWorkingCount), set before Chunky first reads it (its GenerationTask
    * reads the system property once, when its first task starts). A value given on the command line is kept.
    */
   private static void chunkyWorkingCount(net.minecraft.server.level.ServerLevel level) {
      int wanted = Config.CHUNKY_MAX_WORKING_COUNT.get();
      if (wanted < 0 || System.getProperty("chunky.maxWorkingCount") != null) {
         LOG.info("WGA Chunky: chunks in flight left as they are ({})", System.getProperty("chunky.maxWorkingCount", "Chunky's default, 50"));
         return;
      }

      int count = wanted > 0 ? wanted : automaticWorkingCount(Runtime.getRuntime().maxMemory(), level.getSectionsCount());
      System.setProperty("chunky.maxWorkingCount", Integer.toString(count));
      LOG.info("WGA Chunky: {} chunks in flight (chunky.maxWorkingCount)", count);
   }

   /**
    * chunkyMaxWorkingCount 0: as many chunks in flight as the heap holds. Each chunk in flight keeps about 220 chunks
    * loaded (its neighbours at earlier steps; 0.11.4's memory report: 512 in flight, ~112,000 chunks, about 2.4 KB per
    * section with light and palettes), so the loaded chunks are kept under about 45% of the heap: a 16 GB heap and a
    * 768-block FreeTerraForged world get ~300 (512 filled it and slowed a radius 2000 run from 430 to 170 chunks/s), a
    * vanilla-height world or a bigger heap 512. Between 64 and 512, in steps of 32.
    */
   static int automaticWorkingCount(long maxHeap, int sections) {
      double perChunk = Math.max(1, sections) * 2400.0;
      double count = 0.45 * maxHeap / (220.0 * perChunk);
      return Math.max(64, Math.min(512, (int)(count / 32) * 32));
   }

   /**
    * The overworld just made, before its spawn area is generated: the GPU programs for this world are made now, in
    * parallel (the erosion filter's, the noise fill's, the surface's), and the server waits for them, so even the spawn area uses the
    * GPU; the world-loading screen shows their progress ({@link com.wgaccel.gpu.GpuPrepare}). Compiled code is kept on
    * disk, so the same world loads them again at once.
    */
   private static void onLevelLoad(net.neoforged.neoforge.event.level.LevelEvent.Load event) {
      if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
         return;
      }

      if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
         // C2.5: loaded after the overworld (whose start resets the others).
         GpuNoiseFill.startOther(level);
         return;
      }

      chunkyWorkingCount(level);
      com.wgaccel.gpu.SurfaceCheck.reset(level.getChunkSource().randomState());
      com.wgaccel.gpu.BiomeCheck.reset();
      com.wgaccel.gpu.GpuPrepare.reset();
      GpuSelfTest.cubinCache = Config.GPU_CUBIN_CACHE.get();
      // Before the GPU is first used below (it is chosen then, once per game).
      com.wgaccel.gpu.GpuDevice.configured = Config.GPU_DEVICE.get();
      com.wgaccel.gpu.GpuStreams.priorities = Config.GPU_STREAM_PRIORITIES.get();
      com.wgaccel.gpu.GpuStreams.erosionStream = Config.GPU_EROSION_PRIORITY.get();
      com.wgaccel.gpu.GpuStreams.serviceThreads = Config.GPU_SERVICE_THREADS.get();
      com.wgaccel.gpu.GpuEnv.deviceConnections(Config.GPU_DEVICE_CONNECTIONS.get());
      LOG.info("WGA GPU hardware queues (CUDA_DEVICE_MAX_CONNECTIONS): {}", com.wgaccel.gpu.GpuEnv.state);
      if (Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      long start = System.nanoTime();
      if (Config.GPU_EROSION_FILTER.get() != Config.GpuFilterMode.OFF) {
         com.wgaccel.gpu.GpuService.warmUp();
      }

      com.wgaccel.gpu.GpuQueue.configure(Config.GPU_QUEUE_LIMIT.get(), Config.GPU_QUEUE_ADAPTIVE.get(), Config.GPU_QUEUE_OPEN_BELOW.get());
      GpuNoiseFill.start(level);
      com.wgaccel.gen.GpuSurface.start(level);
      com.wgaccel.gen.SurfaceAhead.start();
      com.wgaccel.gen.GpuBiomes.start(level);
      try {
         com.wgaccel.gpu.GpuPrepare.await(180_000L);
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
      }

      LOG.info("WGA GPU ready before the spawn area in {} ms: {}", (System.nanoTime() - start) / 1_000_000, com.wgaccel.gpu.GpuPrepare.status());
   }

   /**
    * The GPU warning for each player joining a world (the user, 2026-10-22): the GPU parts are tested on an RTX 3080
    * only and may not work, or may crash, on older or newer cards. Config gpuTestedWarning.
    */
   private static void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
      if (!Config.GPU_TESTED_WARNING.get() || Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      String tested;
      if (com.wgaccel.gpu.GpuBackend.HIP) {
         // The AMD route (HIP): checked on a Radeon 680M (gfx1035) and a Radeon RX 9070 XT (gfx1201) only.
         String arch = com.wgaccel.gpu.HipSelfTest.architecture;
         String card = arch == null ? "" : " (this one: " + com.wgaccel.gpu.HipSelfTest.deviceName + ", " + arch + ")";
         tested = "The GPU parts on AMD cards (HIP) are tested only on a Radeon RX 9070 XT and a Radeon 680M. On other cards" + card;
      } else {
         int capability = GpuSelfTest.deviceCapability;
         String card = capability == 0 ? "" : String.format(java.util.Locale.ROOT, " (this one: %s, compute %d.%d)", GpuSelfTest.deviceName, capability / 10,
            capability % 10);
         tested = "The GPU parts are tested only on an NVIDIA RTX 3080. On other cards" + card;
      }

      event.getEntity().sendSystemMessage(net.minecraft.network.chat.Component.literal("[Worldgen Accelerator] " + tested
         + " they may not work or may crash the game; every GPU result is checked against the CPU's and a part "
         + "that differs turns itself off. Turn this message off with gpuTestedWarning = false (config/wgaccel-common.toml, [gpu]).").withStyle(net.minecraft.ChatFormatting.YELLOW));
   }

   private static void onServerStarted(ServerStartedEvent event) {
      Environment.log(event.getServer());
      DensityInventory.log(event.getServer().overworld());
      com.wgaccel.gen.BiomePlanInventory.log(event.getServer().overworld());
      Reporter.start();
      com.wgaccel.stats.MemoryWatch.reset();
      if (Config.GPU_MODE.get() != Config.GpuMode.OFF && !com.wgaccel.gpu.Lwjgl.PRESENT) {
         LOG.warn("WGA GPU unavailable: {}; everything runs on the CPU", com.wgaccel.gpu.Lwjgl.MISSING);
      } else if (Config.GPU_MODE.get() != Config.GpuMode.OFF) {
         Thread test = new Thread(WorldgenAccelerator::gpuSelfTest, "WGA GPU self-test");
         test.setDaemon(true);
         test.start();
      }
   }

   /** Never fails the game: a missing driver, CUDA Toolkit or library only logs that the GPU is unavailable. */
   private static void gpuSelfTest() {
      try {
         LOG.info("WGA GPU backend: {} ({})", com.wgaccel.gpu.GpuBackend.name(), com.wgaccel.gpu.GpuBackend.reason);
         for (String line : com.wgaccel.gpu.GpuBackend.HIP ? com.wgaccel.gpu.HipSelfTest.run() : GpuSelfTest.run()) {
            LOG.info("WGA GPU self-test: {}", line);
         }
      } catch (Throwable failure) {
         LOG.warn("WGA GPU unavailable: {}", com.wgaccel.gpu.GpuSelfTest.describe(failure));
      }
   }

   private static void onServerStopping(ServerStoppingEvent event) {
      Reporter.stop();
   }
}
