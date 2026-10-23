package com.wgaccel;

import net.neoforged.neoforge.common.ModConfigSpec;

/** The mod's switches (config/wgaccel-common.toml), so every speedup can be benchmarked off and on in one instance. */
public final class Config {
   public static final ModConfigSpec SPEC;
   public static final Setting<Boolean> FILL_TO_GROUND;
   public static final Setting<Integer> TILE_CACHE_TILES;
   public static final Setting<Boolean> FASTER_BIOME_CHOICE;
   public static final Setting<GpuMode> GPU_MODE;
   public static final Setting<GpuFilterMode> GPU_EROSION_FILTER;
   public static final Setting<GpuFilterMode> GPU_NOISE_FILL;
   public static final Setting<GpuFilterMode> GPU_SURFACE;
   public static final Setting<Integer> GPU_QUEUE_LIMIT;
   public static final Setting<GpuFilterMode> GPU_TILE_NOISE;
   public static final Setting<Boolean> GPU_SURFACE_MAPS_AFTER;
   public static final Setting<Boolean> GPU_QUEUE_ADAPTIVE;
   public static final Setting<Double> GPU_QUEUE_OPEN_BELOW;
   public static final Setting<Integer> GPU_DEVICE_CONNECTIONS;
   public static final Setting<Boolean> GPU_CARVER_BIOMES;
   public static final Setting<Boolean> FAST_ORES;
   public static final Setting<Boolean> FAST_SURFACE_READS;
   public static final Setting<Boolean> CPU_IMPROVEMENTS;
   public static final Setting<Boolean> GPU_IMPROVEMENTS;
   public static final Setting<Boolean> FTF_FAST_PLACEMENT_SCOPES;
   public static final Setting<Boolean> FTF_SHARED_CHUNK_CELLS;
   public static final Setting<Integer> FTF_RAW_CHUNK_CACHE;
   public static final Setting<Boolean> FTF_FAST_RIVERS;
   public static final Setting<Boolean> FAST_BLOCK_PREDICATES;
   public static final Setting<Integer> CHUNKY_MAX_WORKING_COUNT;
   public static final Setting<Boolean> MEMORY_REPORT;
   public static final Setting<Integer> GPU_BATCH_CHUNKS;
   public static final Setting<Integer> GPU_SURFACE_BATCH_CHUNKS;
   public static final Setting<Integer> GPU_SURFACE_BATCH_THREADS;
   public static final Setting<Boolean> GPU_SURFACE_WARP_PREPASS;
   public static final Setting<Boolean> GPU_SURFACE_SMALL_BLOCKS;
   public static final Setting<Boolean> GPU_EROSION_PRIORITY;
   public static final Setting<Boolean> GPU_BIOMES_AHEAD_AT_REFERENCES;
   public static final Setting<Boolean> GPU_CLIFF_RELAXATION;
   public static final Setting<Integer> GPU_SERVICE_THREADS;
   public static final Setting<Boolean> GPU_STREAM_PRIORITIES;
   public static final Setting<Boolean> GPU_NOISE_FILL_VANILLA;
   public static final Setting<Boolean> GPU_NOISE_FILL_OTHER_DIMENSIONS;
   public static final Setting<java.util.List<? extends String>> GPU_NOISE_FILL_CPU_DIMENSIONS;
   public static final Setting<Boolean> GPU_BIOMES_VANILLA;
   public static final Setting<Boolean> GPU_AREA_STEP;
   public static final Setting<Boolean> GPU_TESTED_WARNING;
   public static final Setting<Integer> GPU_DEVICE;
   public static final Setting<Boolean> GPU_SURFACE_KINDS_FROM_CODES;
   public static final Setting<GpuFilterMode> GPU_LOOK_AHEAD;
   public static final Setting<Integer> GPU_LOOK_AHEAD_CHUNKS;
   public static final Setting<GpuFilterMode> GPU_BIOMES;
   public static final Setting<Boolean> GPU_BIOMES_AHEAD;
   public static final Setting<GpuFilterMode> GPU_BIOME_DECORATION;
   public static final Setting<Boolean> GPU_SURFACE_AHEAD;
   public static final Setting<Boolean> GPU_BULK_SECTIONS;
   public static final Setting<Boolean> GPU_AREA_BATCHES;
   public static final Setting<Boolean> GPU_STRUCTURES;
   public static final Setting<Boolean> GPU_CUBIN_CACHE;
   public static final Setting<Integer> GPU_FILL_CHECK_EVERY;
   public static final Setting<java.util.List<? extends String>> GPU_FILL_DEBUG_CHUNKS;

   public enum GpuMode {
      OFF,
      SELFTEST,
      CHECK
   }

   public enum GpuFilterMode {
      OFF,
      CHECK,
      ON
   }

   /** Which values the settings below take: see the preset's comment in the config file. */
   public enum Preset {
      AUTO,
      CLIENT,
      SERVER,
      CUSTOM
   }

   public static final ModConfigSpec.EnumValue<Preset> PRESET;

   /** The preset in force: AUTO is SERVER on a dedicated server, CLIENT in the game. */
   public static Preset preset() {
      Preset preset = PRESET.get();
      if (preset != Preset.AUTO) {
         return preset;
      }

      return net.neoforged.fml.loading.FMLEnvironment.dist.isDedicatedServer() ? Preset.SERVER : Preset.CLIENT;
   }

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      PRESET = builder
         .comment("Worldgen Accelerator: faster world generation, the same world block for block. AI-generated (written by Claude, an",
            "AI, under a person's direction): back up your worlds and report problems.",
            "",
            "Sections: [general]; [cpu] the CPU improvements (any PC); [gpu] the GPU improvements (NVIDIA cards with CUDA, or AMD",
            "cards on Windows with HIP);",
            "[gpu.tuning] how the GPU work is queued (the defaults are the measured best). cpuImprovements and gpuImprovements",
            "turn a whole section on or off.",
            "",
            "Every \"Gain\" below was measured on the test PC: Ryzen 9 7900X (24 threads), RTX 3080 10 GB, 32 GB DDR5, Java 25,",
            "16 GB heap, C2ME (19 threads), Chunky radius 1000 (16,129 chunks) with FreeTerraForged 1.0.0010 unless it says",
            "otherwise; each gain is over the run before it, the whole chain 2:23 -> 0:46 (3.1x). Your PC and mods will differ.",
            "",
            "Needed for the GPU parts, NVIDIA: a GTX 10xx or newer, its driver, and NVRTC from the NVIDIA CUDA Toolkit 12.x (the",
            "toolkit sets CUDA_PATH, where the mod finds nvrtc64_*.dll; or start Java with -Dwgaccel.nvrtc=<path to the dll>).",
            "AMD (Windows): the Adrenalin driver (its HIP runtime) and hipRTC from the AMD HIP SDK 6.4 or newer (found",
            "through HIP_PATH or C:/Program Files/AMD/ROCm; or -Dwgaccel.hiprtc=<path to hiprtc*.dll>). The mod uses CUDA when",
            "NVIDIA's driver sees a card, else HIP; -Dwgaccel.gpuBackend=cuda or hip chooses.",
            "Without them the mod still runs, with only the CPU section. A dedicated server also needs LWJGL: see the README.",
            "",
            "With C2ME's OpenCL module installed this mod turns itself off (the two do the same work).",
            "",
            "preset: which values the settings below take.",
            "  AUTO   (default) CLIENT in the game, SERVER on a dedicated server.",
            "  CLIENT the values found best on a client (the card also draws the game).",
            "  SERVER the values found best on a dedicated server (the card free).",
            "  CUSTOM every setting as written in this file.",
            "With CLIENT or SERVER the values written below are kept but not used (switch to CUSTOM to use them); the section",
            "switches, gpuTestedWarning, fastBlockPredicates, gpuAreaStep and gpuNoiseFillCpuDimensions always follow this file.")
         .defineEnum("preset", Preset.AUTO);
      builder.comment("General settings.").push("general");
      CHUNKY_MAX_WORKING_COUNT = Setting.of(builder
         .comment("How many chunks Chunky keeps in flight while pregenerating (its chunky.maxWorkingCount; Chunky's own default is 50,",
            "which leaves the generation threads and the GPU waiting). 0: automatic, from the heap and the world's height (each chunk in",
            "flight keeps ~220 chunks loaded; kept under ~45% of the heap: 16 GB with a 768-block FreeTerraForged world ~300, a",
            "vanilla-height world or a bigger heap 512; 512 on 16 GB filled the heap in a radius 2000 run and slowed it).",
            "-1: leave Chunky's setting alone. A value set on the command line (-Dchunky.maxWorkingCount) always wins.",
            "Takes effect for a pregeneration started after the world has loaded.")
         .defineInRange("chunkyMaxWorkingCount", 0, -1, 65536), 0, 0);
      MEMORY_REPORT = Setting.custom(builder
         .comment("For a bug report about memory: when the heap first passes 80% and 95% of its maximum, log what holds it (a class",
            "histogram after a full collection, a pause of about a second, and the mod's cache sizes). Off by default.")
         .define("memoryReport", false));
      builder.pop();
      builder.comment("CPU improvements: exact speed-ups of FreeTerraForged's and Minecraft's own CPU work (no GPU needed; any PC).").push("cpu");
      CPU_IMPROVEMENTS = Setting.custom(builder
         .comment("All CPU improvements on or off at once (false: every setting in this section is off, whatever it says).")
         .define("cpuImprovements", true));
      FILL_TO_GROUND = Setting.of(builder
         .comment("Gain: Chunky r1000 with FreeTerraForged 2:23 -> 1:55 (1.24x).",
            "Fill FreeTerraForged's 3D terrain only up to the chunk's highest ground, sea level and structures,",
            "instead of the whole configured height (the air above is left as air). The same blocks either way;",
            "every chunk is checked and any chunk whose blocks reach the cut is logged as a warning.")
         .define("fillToGround", true), true, true).under(CPU_IMPROVEMENTS, false);
      TILE_CACHE_TILES = Setting.of(builder
         .comment("Gain: 1:48 -> 1:28 (1.23x) with FreeTerraForged 1.0.0010 (no effect on 1.0.0013, which has no tiles).",
            "How many of FreeTerraForged's terrain tiles (8 x 8 chunks each, about 4 to 6 MB) stay cached.",
            "FreeTerraForged keeps 4 to 32, fewer than a pregeneration front spans, so tiles get made again and again.",
            "The tiles are the same either way; 0 keeps FreeTerraForged's own size.")
         .defineInRange("tileCacheTiles", 256, 0, 4096), 256, 256).under(CPU_IMPROVEMENTS, 0);
      FASTER_BIOME_CHOICE = Setting.of(builder
         .comment("Gain: 1:28 -> 1:19 with its cave-biome stage memo (1.11x).",
            "Skip FreeTerraForged's repeated work while choosing biomes: its terrain-role step reads each column's",
            "terrain from the tile once per chunk instead of once per 4-block cell, and reuses the coverage check it just",
            "made. The same biomes; every 64th reuse is checked against the normal answer.")
         .define("fasterBiomeChoice", true), true, true).under(CPU_IMPROVEMENTS, false);
      FAST_ORES = Setting.of(builder
         .comment("Gain: ores were 7.5% of the CPU; vanilla r2000 1:27 -> 1:22 after its 0.9.91 fix.",
            "Ores (and other features' section access) without a chunk lookup per column of an ore's box or the level's height",
            "range asked per block: the same answers from a cached chunk and the range read once (ores were 7.5% of the CPU).",
            "Every 256th answer is compared with the game's call; a difference turns it off. The same world. Since 0.9.67.")
         .define("fastOres", true), true, true).under(CPU_IMPROVEMENTS, false);
      FAST_SURFACE_READS = Setting.of(builder
         .comment("Gain: vanilla's surface block reads were ~5% of the CPU (0.9.91 profile).",
            "Minecraft's surface step reads every block of every column through the chunk's getBlockState (height checks and",
            "section look-ups each time); here the column reads its section directly. About 1 in 256 reads is compared with the",
            "game's own; a difference turns it off. The same world. Since 0.9.94.")
         .define("fastSurfaceReads", true), true, true).under(CPU_IMPROVEMENTS, false);
      GPU_CARVER_BIOMES = Setting.of(builder
         .comment("Gain: carvers 1.7-2.0 -> 1.19 ms per chunk (30% of the carvers' time was these biome look-ups).",
            "The carvers' biome of each chunk within 8 read from the chunk when it already holds its biomes, instead of asking the",
            "biome source again (30% of the carvers' time with FreeTerraForged). Every 64th read is compared; a difference turns",
            "it off. The same world. Since 0.9.66.")
         .define("carverBiomesFromChunk", true), true, true).under(CPU_IMPROVEMENTS, false);
      FTF_FAST_PLACEMENT_SCOPES = Setting.of(builder
         .comment("Gain: with Still Life 74.4 s -> 71.2 s (4%).",
            "FreeTerraForged's per-placement scopes (its surface-feature rescue and chunk-local placement) end with",
            "ThreadLocal.set(null) instead of remove(): the same to everything that reads them, without a slow look-up at the next",
            "placement (~15% of feature time with Still Life). Checked to be plain ThreadLocals first. The same world. Since 0.10.9.")
         .define("ftfFastPlacementScopes", true), true, true).under(CPU_IMPROVEMENTS, false);
      FTF_SHARED_CHUNK_CELLS = Setting.of(builder
         .comment("Gain: FreeTerraForged 1.0.0013 (deleteTiles) r1000 60-90 chunks/s -> 1:53 (152 chunks/s).",
            "FreeTerraForged 1.0.0013 and later (no tiles): a chunk's cells (its heightmap and terrain filters) worked out once and",
            "shared by the look-aheads' stand-in chunks and the chunk itself, instead of once each (three times per chunk: 28% of",
            "the CPU); and FreeTerraForged's minimum surface heights (its cache is per thread) shared by all threads (~23%). Checked",
            "copies are worked out again and compared; a difference turns it off. Each part turns itself off when FreeTerraForged",
            "no longer has the slow path (a cache of its own; see the log at the first chunk). The same world. Since 0.10.21.")
         .define("ftfSharedChunkCells", true), true, true).under(CPU_IMPROVEMENTS, false);
      FTF_RAW_CHUNK_CACHE = Setting.of(builder
         .comment("FreeTerraForged 1.0.0013 and later (no tiles): how many chunks' raw heightmap cells (before its terrain filters, about",
            "47 KB a chunk) its RawChunkCache keeps. It keeps 1,024, fewer than a pregeneration front spans: the 23 Oct 2026 profile",
            "(r1000, 15,680 chunks) had 22,745 misses, about 6,000 of them chunks made again after being dropped (~4% of the CPU).",
            "The cells are the same either way; 0 keeps FreeTerraForged's own size.")
         .defineInRange("ftfRawChunkCache", 4096, 0, 65536), 4096, 4096).under(CPU_IMPROVEMENTS, 0);
      FTF_FAST_RIVERS = Setting.of(builder
         .comment("FreeTerraForged's river carving without the noise of rivers too far away to touch a column: its carver samples",
            "four noises for every river of a network before finding the column out of reach (~9% of all CPU in a 1.0.0013",
            "profile). A safe upper bound of the reach is worked out first; about 1 skip in 2,048 is carved anyway and compared, and a",
            "difference turns this off. The same world (FreeTerraForged 1.0.0010 and 1.0.0013).")
         .define("ftfFastRivers", true), true, true).under(CPU_IMPROVEMENTS, false);
      FAST_BLOCK_PREDICATES = Setting.custom(builder
         .comment("Block predicates (placement filters, rule-based block choices) read each block once per evaluation: a block read",
            "again in the same evaluation comes from that evaluation's own reads (nothing writes blocks while a predicate runs).",
            "About 1 in 256 such answers is compared with the world; a difference turns it off. The same world. Since 0.10.10.",
            "Off by default: in the Still Life A/B (2026-10-22) it was slower (78.6 s on, 72.9 s off).",
            "The presets leave this one alone (being measured): always the value written here.")
         .define("fastBlockPredicates", false)).under(CPU_IMPROVEMENTS, false);
      builder.pop();
      builder.comment("GPU improvements: world generation work done on an NVIDIA card (CUDA) or an AMD card (HIP, Windows). Every GPU",
         "result is checked against the CPU's now and then; a part that differs turns itself off.").push("gpu");
      GPU_IMPROVEMENTS = Setting.custom(builder
         .comment("All GPU improvements on or off at once (false: the GPU is never touched; the CPU section still applies).")
         .define("gpuImprovements", true));
      GPU_TESTED_WARNING = Setting.custom(builder
         .comment("A chat message to each player joining a world: the GPU parts are tested only on an NVIDIA RTX 3080 (and, through HIP,",
            "an AMD Radeon RX 9070 XT and Radeon 680M) and may not work, or may crash the game, on other cards (NVIDIA GTX 10xx and newer are supported).",
            "false: no message.")
         .define("gpuTestedWarning", true));
      GPU_DEVICE = Setting.custom(builder
         .comment("Which GPU to use when the PC has more than one, as the log's \"WGA GPU self-test: device\" line lists them (0, 1, ...).",
            "-1: automatic, the card with the most compute units, a separate card before an integrated one (a desktop Ryzen's own",
            "small Radeon beside the graphics card is skipped). Read when the GPU is first used: a change takes a restart of the game.")
         .defineInRange("gpuDevice", -1, -1, 15));
      GPU_MODE = Setting.of(builder
         .comment("The GPU (NVIDIA: CUDA and NVRTC; AMD on Windows: HIP and hipRTC; both through LWJGL). OFF: never touched. SELFTEST: when a world starts,",
            "compile and run a small kernel and check its maths is bit-identical to Java's, then log the result; world",
            "generation itself does not use the GPU yet. CHECK: the self-test, plus FreeTerraForged's noise worked out on the GPU",
            "and compared bit for bit with its own results at world load (logged as WGA GPU noise check).")
         .defineEnum("gpuMode", GpuMode.CHECK), GpuMode.SELFTEST, GpuMode.SELFTEST).under(GPU_IMPROVEMENTS, GpuMode.OFF);
      GPU_TILE_NOISE = Setting.of(builder
         .comment("Gain: 0:51 -> 0:46 (the best client time, 3.1x the 2:23 start; a dedicated server 0:47).",
            "FreeTerraForged's tile terrain noise (the terrain populators' noise trees, mostly the mountains' erosion noise) worked",
            "out on the GPU for a whole tile at once; a populator asked at a column takes the GPU's values (unsure ones stay on the",
            "CPU). OFF: never. CHECK: made on the GPU, but FreeTerraForged works every column out too and they are compared. ON: the",
            "GPU's values are used; every 64th use is compared (a difference turns it off).")
         .defineEnum("gpuTileNoise", GpuFilterMode.ON), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_EROSION_FILTER = Setting.of(builder
         .comment("Gain: 1:19 -> 1:13 (1.08x); with the tile noise on the GPU, part of 0:51 -> 0:46.",
            "FreeTerraForged's tile erosion filter (about two thirds of each terrain tile's time) on the GPU. OFF: on",
            "the CPU as usual. CHECK: the GPU works every tile out, FreeTerraForged's own filter still runs and every cell",
            "is compared bit for bit (logged as WGA GPU erosion filter check; no speedup). ON: the GPU's result is used;",
            "the few cells it cannot be sure of are redone on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuErosionFilter", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_CLIFF_RELAXATION = Setting.of(builder
         .comment("FreeTerraForged's CliffRelaxation filter (its FTF of 21 Oct and later) on the GPU, with gpuErosionFilter's mode. Off by",
            "default: bit-identical, but in the run of 0.9.58 it took 27 ms per call on the GPU (waiting its turn on the GPU",
            "thread) against FreeTerraForged's 3.7 ms on the CPU.")
         .define("gpuCliffRelaxation", false), false, false).under(GPU_IMPROVEMENTS, false);
      GPU_NOISE_FILL = Setting.of(builder
         .comment("Gain: 1:13 -> 1:07 (1.09x), more with the look-ahead.",
            "The 3D noise fill (every block's density, aquifer water and lava, ore veins) on the GPU. OFF: on the CPU as",
            "usual. CHECK: the GPU works every chunk out and writes it into copies, the CPU fills the chunk as usual, and the",
            "sections (byte for byte), heightmaps and fluid marks are compared (logged as WGA GPU noise fill check; slower).",
            "ON: the GPU's blocks are written into the chunk instead; every 64th chunk is still checked. Chunks with structure",
            "terrain or old-world blending stay on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuNoiseFill", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_LOOK_AHEAD = Setting.of(builder
         .comment("Gain: 1:06 -> 1:01 (with gpuBiomesAheadAtReferences).",
            "Noise fills worked out ahead of Minecraft's requests. Each new chunk is noted at its structure references step;",
            "two threads make its fill's inputs from a stand-in chunk (its own noise chunk, aquifer and FreeTerraForged tile",
            "stage, the same inputs as the real chunk's) and send up to 64 chunks to the GPU in one launch; at its noise step the",
            "chunk takes the ready blocks (chunks with structure terrain or blending, or not ready yet, go the usual way). OFF:",
            "never. CHECK: nothing ahead; for every 8th chunk the GPU fills, its inputs are also made the stand-in way and",
            "compared with the real chunk's. ON: the look-ahead (needs gpuNoiseFill ON; every 64th chunk is still checked).")
         .defineEnum("gpuLookAhead", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_STRUCTURES = Setting.of(builder
         .comment("S7: chunks with structure terrain (villages, ancient cities, trail ruins...) fill on the GPU too: the",
            "Beardifier's pieces and junctions are worked out on the GPU as Minecraft (and C2ME) sums them.")
         .define("gpuStructures", true), true, true).under(GPU_IMPROVEMENTS, false);
      GPU_BIOMES = Setting.of(builder
         .comment("Gain: with gpuBiomesAhead and gpuBiomeDecoration 1:01 -> 0:59.",
            "The biome step on the GPU (its climate and climate-table search so far; FreeTerraForged's decoration stays on the",
            "CPU). OFF: never. CHECK: in the first 16 overworld chunks every cell's climate and search are worked out again on the",
            "GPU and compared with the game's (logged as WGA GPU biome check). ON: each chunk's climate and search come from the",
            "GPU; every 64th chunk the CPU runs too and the results are compared (logged as WGA GPU biomes).")
         .defineEnum("gpuBiomes", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_BIOMES_AHEAD = Setting.of(builder
         .comment("With gpuBiomes ON: the biome step's GPU work done ahead. Each new chunk is noticed at its structure starts; three",
            "threads make its inputs from a stand-in chunk and run it on the GPU, and its biome step takes the ready result (one",
            "not ready in time is worked out on the CPU, so no chunk waits for the GPU). false: each chunk runs on the GPU at its",
            "biome step and waits for it (run V). Every 64th chunk is still checked against the CPU.")
         .define("gpuBiomesAhead", false), true, true).under(GPU_IMPROVEMENTS, false);
      GPU_BIOME_DECORATION = Setting.of(builder
         .comment("With gpuBiomes ON: FreeTerraForged's biome decoration (cave biomes and rivers) on the GPU too, in the same run as",
            "the climate and search (cells needing a cave region's stage choice, and ties, stay the CPU's). OFF: the CPU decorates.",
            "CHECK: the GPU decorates every cell too and every chunk compares it with the CPU's (logged with WGA GPU biomes). ON:",
            "the GPU's decorated biomes are used; every 64th chunk is still compared.")
         .defineEnum("gpuBiomeDecoration", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_SURFACE = Setting.of(builder
         .comment("Gain: 1:07 -> 1:06 alone, the base for the surface look-ahead and the later 0:46.",
            "The surface (grass, dirt, sand, deepslate, rock layers: the surface rules) of the overworld from the GPU. OFF: never.",
            "CHECK: when the world loads the rule tree is made into GPU code; every chunk's surface is worked out on the GPU too and",
            "compared block for block with the game's (logged as WGA GPU surface), nothing written; the first 16 chunks also get",
            "the older per-rule checks. ON: the GPU's blocks are written instead; every 64th chunk is still checked. Chunks with",
            "eroded badlands or frozen ocean columns stay on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuSurface", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON).under(GPU_IMPROVEMENTS, GpuFilterMode.OFF);
      GPU_SURFACE_AHEAD = Setting.of(builder
         .comment("With gpuSurface ON: a chunk's surface worked out on the GPU right after its noise fill (from the fill's blocks and",
            "the recorded biomes of the chunk and its neighbours), so its surface step takes the ready blocks instead of waiting",
            "for the card or doing it on the CPU. Only chunks filled from the GPU whose neighbours' biomes are known; the rest go",
            "the usual way. Every 64th chunk's inputs are also made the usual way and compared.")
         .define("gpuSurfaceAhead", false), false, false).under(GPU_IMPROVEMENTS, false);
      GPU_NOISE_FILL_OTHER_DIMENSIONS = Setting.of(builder
         .comment("The GPU noise fill in the other dimensions with a noise generator (the Nether, the End, modded ones), each with its",
            "own GPU code made when it loads; every 64th chunk checked as in the overworld. A dimension whose terrain has something",
            "the GPU does not know stays on the CPU (a chat warning names it).")
         .define("gpuNoiseFillOtherDimensions", true), true, true).under(GPU_IMPROVEMENTS, false);
      GPU_NOISE_FILL_CPU_DIMENSIONS = Setting.custom(builder
         .comment("Dimensions whose noise fill stays on the CPU even with gpuNoiseFillOtherDimensions on. The Nether and the End by",
            "default: C2ME's CPU fill was as fast or faster there on the test PC (End radius 2000: 0:48 on the GPU against 0:31;",
            "Nether radius 1000 with biome mods: 0:30 against 0:23).")
         .defineListAllowEmpty("gpuNoiseFillCpuDimensions", java.util.List.of("minecraft:the_nether", "minecraft:the_end"), () -> "minecraft:the_end",
            value -> value instanceof String));
      GPU_NOISE_FILL_VANILLA = Setting.of(builder
         .comment("Measured: vanilla r2000 2:15 without the mod, 2:29 with the GPU fill (slower, so off).",
            "The GPU noise fill in worlds that are not FreeTerraForged's (vanilla and others). Off by default: in vanilla, C2ME's",
            "compiled CPU fill is already fast and the GPU fill made the overworld slower on the test PC (radius 2000: 2:15",
            "without this mod, 2:29 with the GPU fill). FreeTerraForged worlds always use it (gpuNoiseFill).")
         .define("gpuNoiseFillVanilla", false), false, false).under(GPU_IMPROVEMENTS, false);
      GPU_BIOMES_VANILLA = Setting.of(builder
         .comment("Measured: vanilla r2000 2:13 with, 1:58 without (slower, so off).",
            "The GPU biomes in worlds that are not FreeTerraForged's (vanilla's MultiNoiseBiomeSource). Off by default: exact,",
            "but the overworld was slower with it on the test PC (radius 2000: 2:13 against 1:58; vanilla's biome step is",
            "cheap and chunks waited on the look-ahead). FreeTerraForged worlds always use it (gpuBiomes).")
         .define("gpuBiomesVanilla", false), false, false).under(GPU_IMPROVEMENTS, false);
      GPU_AREA_STEP = Setting.custom(builder
         .comment("Gain: FreeTerraForged 1.0.0010 r1000 0:46 -> 0:44; vanilla r2000 1:58 -> 1:53. Off by default (in development).",
            "The 4 x 4 area step (with C2ME): every chunk's biome step waits for its aligned 4 x 4 area's GPU work, done once",
            "for the 16 chunks, so their GPU results are ready when their steps come. On, it also turns on the GPU fill and biomes in",
            "worlds that are not FreeTerraForged's (gpuNoiseFillVanilla, gpuBiomesVanilla). In development: off by default.")
         .define("gpuAreaStep", false)).under(GPU_IMPROVEMENTS, false);
      builder.comment("GPU tuning: how the GPU work is queued and batched. The defaults are the measured best on the test PC; every value",
         "gives the same world.").push("tuning");
      GPU_SURFACE_MAPS_AFTER = Setting.of(builder
         .comment("With gpuSurface ON: the surface writer works each column's heightmaps out once after its writes (the highest block",
            "each map counts, as Minecraft's per-block updates end up) instead of updating them for every block. Checked chunks",
            "verify the rule against Minecraft's own updates; a difference turns this off. false: per-block updates.")
         .define("gpuSurfaceMapsAfter", true), true, true);
      GPU_QUEUE_ADAPTIVE = Setting.of(builder
         .comment("With a gpuQueueLimit above 0: the limit follows the measured times. The surface step times both ways of doing a",
            "chunk (the GPU's, and the CPU's when the card was busy); while the GPU way is clearly faster and chunks still went to",
            "the CPU, one more place opens, and when the GPU way is as slow as the CPU's, one closes (2 to 32). gpuQueueLimit is",
            "where it starts. false: the limit stays as set.")
         .define("gpuQueueAdaptive", true), true, true);
      GPU_QUEUE_OPEN_BELOW = Setting.of(builder
         .comment("With gpuQueueAdaptive: one more place opens while the GPU way takes less than this share of the CPU way (and chunks",
            "still went to the CPU). Was 0.7 until 0.9.63 (0.9.60's run stopped at 15 places with the GPU way at 0.72 of the CPU's",
            "and 22% of the chunks' surfaces on the CPU). The same blocks either way.")
         .defineInRange("gpuQueueOpenBelow", 0.9, 0.5, 1.0), 0.9, 0.9);
      GPU_DEVICE_CONNECTIONS = Setting.of(builder
         .comment("How many hardware work queues the card gives our GPU streams (CUDA_DEVICE_MAX_CONNECTIONS, set for the game before",
            "the driver starts; 0: the driver's 8). With 8, streams share queues and a waited-for launch can sit behind another",
            "stream's work whatever its priority: offline 10 ms with 8 against 0.08 ms with 32. Takes effect at the game's start",
            "(the first world). The same results either way. Since 0.9.64.")
         .defineInRange("gpuDeviceConnections", 32, 0, 32), 32, 32);
      GPU_QUEUE_LIMIT = Setting.of(builder
         .comment("With gpuNoiseFill or gpuSurface ON: how many chunks may be on the GPU at once. A chunk that finds the card busy",
            "does that step on the CPU instead (the same blocks either way), so the generation threads never stand waiting for a",
            "card that is behind (it also draws the game). The noise fill goes first; the surface only takes a place while one",
            "more stays free. 0: no limit (every chunk waits for the GPU).")
         .defineInRange("gpuQueueLimit", 4, 0, 256), 4, 4);
      GPU_BATCH_CHUNKS = Setting.of(builder
         .comment("The noise fill on the GPU in batches: the chunks the generation threads ask for at about the same time go to the",
            "card in one launch, at most this many (the same blocks as one at a time). While more than twice this many wait, a",
            "chunk fills on the CPU instead. 0: one chunk per launch (then gpuQueueLimit applies to the fill too).")
         .defineInRange("gpuBatchChunks", 16, 0, 256), 0, 0);
      GPU_SURFACE_BATCH_CHUNKS = Setting.of(builder
         .comment("The surface on the GPU in batches, as gpuBatchChunks does for the noise fill: the surfaces asked for at about the",
            "same time go to the card in one launch, at most this many (the same blocks as one at a time; gpuQueueLimit still",
            "decides how many may wait). 0: one chunk per launch.")
         .defineInRange("gpuSurfaceBatchChunks", 16, 0, 256), 16, 16);
      GPU_SURFACE_BATCH_THREADS = Setting.of(builder
         .comment("With gpuSurfaceBatchChunks above 0: how many threads gather and launch the surface batches (more: a chunk waits less",
            "for its batch to start; the same blocks either way). Was 2 until 0.9.56.")
         .defineInRange("gpuSurfaceBatchThreads", 4, 1, 16), 4, 4);
      GPU_SURFACE_WARP_PREPASS = Setting.of(builder
         .comment("The surface's column walk (stone above and below each block, water above it) by 32 GPU threads per column instead of",
            "one (shorter batches on the card; the same results). Since 0.9.61.")
         .define("gpuSurfaceWarpPrepass", true), true, true);
      GPU_SURFACE_SMALL_BLOCKS = Setting.of(builder
         .comment("The surface's strata kernels in blocks of 32 GPU threads instead of 256 and 128 (spread over more of the card; the",
            "same results). Since 0.9.62.")
         .define("gpuSurfaceSmallBlocks", true), true, true);
      GPU_EROSION_PRIORITY = Setting.of(builder
         .comment("FreeTerraForged's tile erosion filter on the GPU on its own stream of the card's highest priority (the tile threads",
            "and the chunks behind them wait for each tile), instead of the legacy stream, which the card serves last. The same",
            "tiles either way. Read when the GPU starts (the first world of a game).")
         .define("gpuErosionPriority", true), true, true);
      GPU_BIOMES_AHEAD_AT_REFERENCES = Setting.of(builder
         .comment("The biome look-ahead notices a chunk at its structure references (only chunks that are going to be generated get",
            "there; it goes to the front of the queue) instead of its structure starts (which also run in a ring of chunks that",
            "never get further: ~29% of the look-ahead's GPU work unused in 0.9.55's run), at the price of less time ahead. The same",
            "biomes either way.")
         .define("gpuBiomesAheadAtReferences", true), true, true);
      GPU_SERVICE_THREADS = Setting.of(builder
         .comment("How many threads run FreeTerraForged's tile filters on the GPU (each tile its own, on its own stream). Was 1 until",
            "0.9.60 (tiles waited their turn: 29 ms per erosion tile for ~1.7 ms of kernels). Read when the GPU starts (the",
            "first world of a game). The same tiles either way.")
         .defineInRange("gpuServiceThreads", 3, 1, 8), 3, 3);
      GPU_STREAM_PRIORITIES = Setting.of(builder
         .comment("GPU work a generation thread waits for runs at the card's highest priority, the look-ahead's (nobody waits yet)",
            "at its lowest (the same blocks either way; only the order the card runs them in changes).")
         .define("gpuStreamPriorities", true), true, true);
      GPU_SURFACE_KINDS_FROM_CODES = Setting.of(builder
         .comment("The surface's inputs (which blocks are air, fluid, the default block) from the noise fill's codes instead of",
            "reading the chunk's blocks. Off: 0.9.53's codes check found a block the codes call stone but air in the chunk",
            "(1 chunk of 234), so the codes are not always the chunk's blocks when the surface starts.")
         .define("gpuSurfaceKindsFromCodes", false), false, false);
      GPU_LOOK_AHEAD_CHUNKS = Setting.of(builder
         .comment("With gpuLookAhead ON: how many finished chunks' blocks may wait in memory for their noise step (about 120 KB",
            "each); past that the oldest are dropped.")
         .defineInRange("gpuLookAheadChunks", 2048, 64, 65536), 2048, 2048);
      GPU_BULK_SECTIONS = Setting.of(builder
         .comment("S3: sections the noise fill covers completely, and sections the surface changes for the most part, are made at",
            "once from their blocks (palette and packed data in one go) instead of block by block. The same blocks either way.")
         .define("gpuBulkSections", true), true, true);
      GPU_AREA_BATCHES = Setting.of(builder
         .comment("S5: the look-ahead (fill and biomes) takes whole aligned 4 x 4 chunk areas at once when it notices a chunk,",
            "so the GPU gets fewer, bigger launches. The same blocks either way (an area chunk is only used if it is the same).")
         .define("gpuAreaBatches", true), true, true);
      GPU_CUBIN_CACHE = Setting.of(builder
         .comment("S2: the GPU programs are kept on disk also as the driver's finished machine code (cubin), so a world loads them",
            "without the driver translating them again (the same code the driver would make).")
         .define("gpuCubinCache", true), true, true);
      GPU_FILL_CHECK_EVERY = Setting.custom(builder
         .comment("Every how many GPU noise fills one is also done the normal way and compared (64: about 1.5%; 1: every chunk, much",
            "slower, for finding a difference). The first difference turns the GPU fill off.")
         .defineInRange("gpuFillCheckEvery", 64, 1, 4096));
      GPU_FILL_DEBUG_CHUNKS = Setting.custom(builder
         .comment("Chunks whose GPU noise fill is always checked, diagnosed in the log and saved (logs/wgaccel-noise-fill-sample-1NN.bin)",
            "with the game's own blocks, as \"x,z\" chunk coordinates (as the log's chunk [x, z]). Empty: none.")
         .defineListAllowEmpty("gpuFillDebugChunks", java.util.List.of(), () -> "0,0", value -> value instanceof String));
      builder.pop();
      builder.pop();
      SPEC = builder.build();
   }

   private Config() {
   }
}
