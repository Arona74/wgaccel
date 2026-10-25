package com.wgaccel;

import com.wgaccel.gen.DensityInventory;
import com.wgaccel.gen.GpuNoiseFill;
import com.wgaccel.gpu.GpuSelfTest;
import com.wgaccel.stats.Environment;
import com.wgaccel.stats.Reporter;
import com.wgaccel.stats.Stage;
import com.wgaccel.stats.Stats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worldgen Accelerator: faster chunk generation with FreeTerraForged (the plan is in the project's PLAN.md). The same
 * on NeoForge and Fabric: each loader's entry point (neoforge/, fabric/) calls {@link #start()} and passes its events
 * on to the methods here.
 */
public final class WorldgenAccelerator {
   public static final String MOD_ID = "wgaccel";
   /** The mod's log, at the config's debugInfoLevel ({@link LevelLogger}). */
   public static final Logger LOG = new LevelLogger(LoggerFactory.getLogger("Worldgen Accelerator"));

   /** When this thread's chunk-load event began (set by the first listener, read by the last). */
   private static final ThreadLocal<long[]> LOAD_START = ThreadLocal.withInitial(() -> new long[1]);

   /** The running server (started to stopping), for the chat messages ({@link WgaChat}). */
   private static volatile MinecraftServer server;

   private WorldgenAccelerator() {
   }

   /**
    * From the loader's entry point: false when the mod is off (C2ME's OpenCL module installed); the entry point then
    * passes on only the player logins, for {@link #offMessage}.
    */
   public static boolean start() {
      if (Conflicts.openCl()) {
         // The user, 2026-10-22: with C2ME's OpenCL module the whole mod is off (its mixins too: the mixin plugins ask the same).
         LOG.warn("WGA Worldgen Accelerator is OFF: C2ME's OpenCL module ({}) is installed; remove one of the two", Conflicts.C2ME_OPENCL);
         return false;
      }

      GpuSelfTest.cacheDirectory = com.wgaccel.platform.Platform.get().gameDir().resolve("wgaccel-gpu-cache");
      return true;
   }

   /** With the mod off: why, to each player joining. */
   public static void offMessage(Player player) {
      player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Worldgen Accelerator] Off: C2ME's OpenCL module is installed too. "
         + "The two do the same work in different ways; remove one of them.").withStyle(net.minecraft.ChatFormatting.YELLOW));
   }

   /** The running server, or null between worlds. */
   public static MinecraftServer server() {
      return server;
   }

   /** Before every other mod's chunk-load listener (NeoForge: priority HIGHEST; Fabric: a phase before the default one). */
   public static void onChunkLoadFirst(LevelAccessor level) {
      if (!level.isClientSide()) {
         LOAD_START.get()[0] = System.nanoTime();
      }
   }

   /** Every other mod's chunk-load listener ran between the first and the last listener (main thread). */
   public static void onChunkLoadLast(LevelAccessor level) {
      long start = LOAD_START.get()[0];
      if (!level.isClientSide() && start != 0L) {
         Stats.record(Stage.LOAD_EVENT, System.nanoTime() - start);
         LOAD_START.get()[0] = 0L;
      }
   }

   /**
    * S1: Chunky's chunks in flight (config chunkyMaxWorkingCount), set before Chunky first reads it (its GenerationTask
    * reads the system property once, when its first task starts). A value given on the command line is kept.
    */
   private static void chunkyWorkingCount(ServerLevel level) {
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
   public static void onLevelLoad(ServerLevel level) {
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
      com.wgaccel.gpu.GpuDevice.configuredBackend = Config.GPU_BACKEND.get().name();
      com.wgaccel.gpu.GpuStreams.priorities = Config.GPU_STREAM_PRIORITIES.get();
      com.wgaccel.gpu.GpuStreams.erosionStream = Config.GPU_EROSION_PRIORITY.get();
      com.wgaccel.gpu.GpuStreams.serviceThreads = Config.GPU_SERVICE_THREADS.get();
      com.wgaccel.gpu.GpuStreams.pool = Config.GPU_STREAM_POOL.get();
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
      com.wgaccel.ftf.FtfRawBlocks.prepare(level);
      try {
         com.wgaccel.gpu.GpuPrepare.await(180_000L);
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
      }

      LOG.info("WGA GPU ready before the spawn area in {} ms: {}", (System.nanoTime() - start) / 1_000_000, com.wgaccel.gpu.GpuPrepare.status());
   }

   /** A line for each player joining a world: the GPU and the API (compiler) the GPU parts use. Config gpuTestedWarning. */
   public static void onPlayerLoggedIn(Player player) {
      if (!Config.GPU_TESTED_WARNING.get() || Config.GPU_MODE.get() == Config.GpuMode.OFF || !com.wgaccel.gpu.Lwjgl.PRESENT) {
         return;
      }

      String name = com.wgaccel.gpu.GpuDevice.chosenName;
      String text = name == null || !com.wgaccel.gpu.GpuDevice.running ? "no GPU in use (the CPU improvements only; see Options, the WGA button)"
         : "GPU: " + name + ", through " + com.wgaccel.gpu.GpuBackend.name();
      player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Worldgen Accelerator] " + text).withStyle(net.minecraft.ChatFormatting.GRAY));
   }

   public static void onServerStarted(MinecraftServer started) {
      server = started;
      Environment.log(started);
      DensityInventory.log(started.overworld());
      com.wgaccel.gen.BiomePlanInventory.log(started.overworld());
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
         for (String line : com.wgaccel.gpu.GpuBackend.OPENCL ? com.wgaccel.gpu.OpenClSelfTest.run()
            : com.wgaccel.gpu.GpuBackend.HIP ? com.wgaccel.gpu.HipSelfTest.run() : GpuSelfTest.run()) {
            LOG.info("WGA GPU self-test: {}", line);
         }
      } catch (Throwable failure) {
         LOG.warn("WGA GPU unavailable: {}", com.wgaccel.gpu.GpuSelfTest.describe(failure));
      }
   }

   public static void onServerStopping() {
      Reporter.stop();
      server = null;
   }
}
