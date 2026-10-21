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
   public static final Setting<Integer> CHUNKY_MAX_WORKING_COUNT;
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
   public static final Setting<Boolean> PROFILE_RUNS;
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
         .comment("Worldgen Accelerator: much faster world generation with FreeTerraForged, the same world block for block.",
            "",
            "WARNING: the GPU parts work ONLY on NVIDIA graphics cards (CUDA). On other cards, or without the CUDA parts below,",
            "the mod still runs but only its CPU improvements apply (fillToGround, tileCacheTiles, fasterBiomeChoice, fastOres,",
            "gpuCarverBiomes...); every GPU setting then does nothing.",
            "",
            "Needed for the GPU parts (client and server): an NVIDIA driver, and NVRTC from the NVIDIA CUDA Toolkit 12.x (install",
            "the toolkit: it sets CUDA_PATH, where the mod finds nvrtc64_*.dll; or start Java with",
            "-Dwgaccel.nvrtc=C:/path/to/nvrtc64_120_0.dll).",
            "",
            "Dedicated server installation (a client has LWJGL already; a dedicated server does not):",
            "  1. Put this mod's jar (and FreeTerraForged, C2ME...) into the server's mods folder.",
            "  2. Copy lwjgl-3.3.3.jar and lwjgl-3.3.3-natives-windows.jar (from a client's libraries folder,",
            "     libraries/org/lwjgl/lwjgl/3.3.3/) to the server's libraries/org/lwjgl/lwjgl/3.3.3/.",
            "  3. In libraries/net/neoforged/neoforge/<version>/win_args.txt, add both jars to the end of -DlegacyClassPath",
            "     (separated by ';'); keep a copy of the file first (reinstalling NeoForge rewrites it).",
            "  4. Start the server: the log shows \"WGA GPU self-test: BIT-IDENTICAL\" when the GPU works, or",
            "     \"WGA GPU unavailable: LWJGL is not loaded\" if step 2-3 is missing.",
            "",
            "preset: which values every setting below takes.",
            "  AUTO   (default) CLIENT in the game, SERVER on a dedicated server.",
            "  CLIENT the values found best on a client (the card also draws the game).",
            "  SERVER the values found best on a dedicated server (the card free).",
            "  CUSTOM every setting as written in this file.",
            "With CLIENT or SERVER the values written below are kept but not used (switch to CUSTOM to use them).",
            "The presets turn profileRuns off (a diagnostic).")
         .defineEnum("preset", Preset.AUTO);
      FILL_TO_GROUND = Setting.of(builder
         .comment("Fill FreeTerraForged's 3D terrain only up to the chunk's highest ground, sea level and structures,",
            "instead of the whole configured height (the air above is left as air). The same blocks either way;",
            "every chunk is checked and any chunk whose blocks reach the cut is logged as a warning.")
         .define("fillToGround", true), true, true);
      TILE_CACHE_TILES = Setting.of(builder
         .comment("How many of FreeTerraForged's terrain tiles (8 x 8 chunks each, about 4 to 6 MB) stay cached.",
            "FreeTerraForged keeps 4 to 32, fewer than a pregeneration front spans, so tiles get made again and again.",
            "The tiles are the same either way; 0 keeps FreeTerraForged's own size.")
         .defineInRange("tileCacheTiles", 256, 0, 4096), 256, 256);
      FASTER_BIOME_CHOICE = Setting.of(builder
         .comment("Skip FreeTerraForged's repeated work while choosing biomes: its terrain-role step reads each column's",
            "terrain from the tile once per chunk instead of once per 4-block cell, and reuses the coverage check it just",
            "made. The same biomes; every 64th reuse is checked against the normal answer.")
         .define("fasterBiomeChoice", true), true, true);
      GPU_MODE = Setting.of(builder
         .comment("The GPU (CUDA through LWJGL and NVRTC, NVIDIA only). OFF: never touched. SELFTEST: when a world starts,",
            "compile and run a small kernel and check its maths is bit-identical to Java's, then log the result; world",
            "generation itself does not use the GPU yet. CHECK: the self-test, plus FreeTerraForged's noise worked out on the GPU",
            "and compared bit for bit with its own results at world load (logged as WGA GPU noise check).")
         .defineEnum("gpuMode", GpuMode.CHECK), GpuMode.SELFTEST, GpuMode.SELFTEST);
      GPU_SURFACE_MAPS_AFTER = Setting.of(builder
         .comment("With gpuSurface ON: the surface writer works each column's heightmaps out once after its writes (the highest block",
            "each map counts, as Minecraft's per-block updates end up) instead of updating them for every block. Checked chunks",
            "verify the rule against Minecraft's own updates; a difference turns this off. false: per-block updates.")
         .define("gpuSurfaceMapsAfter", true), true, true);
      GPU_TILE_NOISE = Setting.of(builder
         .comment("FreeTerraForged's tile terrain noise (the terrain populators' noise trees, mostly the mountains' erosion noise) worked",
            "out on the GPU for a whole tile at once; a populator asked at a column takes the GPU's values (unsure ones stay on the",
            "CPU). OFF: never. CHECK: made on the GPU, but FreeTerraForged works every column out too and they are compared. ON: the",
            "GPU's values are used; every 64th use is compared (a difference turns it off).")
         .defineEnum("gpuTileNoise", GpuFilterMode.ON), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_EROSION_FILTER = Setting.of(builder
         .comment("FreeTerraForged's tile erosion filter (about two thirds of each terrain tile's time) on the GPU. OFF: on",
            "the CPU as usual. CHECK: the GPU works every tile out, FreeTerraForged's own filter still runs and every cell",
            "is compared bit for bit (logged as WGA GPU erosion filter check; no speedup). ON: the GPU's result is used;",
            "the few cells it cannot be sure of are redone on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuErosionFilter", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_NOISE_FILL = Setting.of(builder
         .comment("The 3D noise fill (every block's density, aquifer water and lava, ore veins) on the GPU. OFF: on the CPU as",
            "usual. CHECK: the GPU works every chunk out and writes it into copies, the CPU fills the chunk as usual, and the",
            "sections (byte for byte), heightmaps and fluid marks are compared (logged as WGA GPU noise fill check; slower).",
            "ON: the GPU's blocks are written into the chunk instead; every 64th chunk is still checked. Chunks with structure",
            "terrain or old-world blending stay on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuNoiseFill", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_SURFACE = Setting.of(builder
         .comment("The surface (grass, dirt, sand, deepslate, rock layers: the surface rules) of the overworld from the GPU. OFF: never.",
            "CHECK: when the world loads the rule tree is made into GPU code; every chunk's surface is worked out on the GPU too and",
            "compared block for block with the game's (logged as WGA GPU surface), nothing written; the first 16 chunks also get",
            "the older per-rule checks. ON: the GPU's blocks are written instead; every 64th chunk is still checked. Chunks with",
            "eroded badlands or frozen ocean columns stay on the CPU. Any GPU trouble switches it back to the CPU.")
         .defineEnum("gpuSurface", GpuFilterMode.CHECK), GpuFilterMode.ON, GpuFilterMode.ON);
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
      GPU_CARVER_BIOMES = Setting.of(builder
         .comment("The carvers' biome of each chunk within 8 read from the chunk when it already holds its biomes, instead of asking the",
            "biome source again (30% of the carvers' time with FreeTerraForged). Every 64th read is compared; a difference turns",
            "it off. The same world. Since 0.9.66.")
         .define("gpuCarverBiomes", true), true, true);
      FAST_ORES = Setting.of(builder
         .comment("Ores (and other features' section access) without a chunk lookup per column of an ore's box or the level's height",
            "range asked per block: the same answers from a cached chunk and the range read once (ores were 7.5% of the CPU).",
            "Every 256th answer is compared with the game's call; a difference turns it off. The same world. Since 0.9.67.")
         .define("fastOres", true), true, true);
      CHUNKY_MAX_WORKING_COUNT = Setting.of(builder
         .comment("How many chunks Chunky keeps in flight while pregenerating (its chunky.maxWorkingCount; Chunky's own default is 50,",
            "which leaves the generation threads and the GPU waiting). 0: automatic (512 with more than 10 GB of memory, else 192).",
            "-1: leave Chunky's setting alone. A value set on the command line (-Dchunky.maxWorkingCount) always wins.",
            "Takes effect for a pregeneration started after the world has loaded.")
         .defineInRange("chunkyMaxWorkingCount", 0, -1, 65536), 0, 0);
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
      GPU_CLIFF_RELAXATION = Setting.of(builder
         .comment("FreeTerraForged's CliffRelaxation filter (its FTF of 21 Oct and later) on the GPU, with gpuErosionFilter's mode. Off by",
            "default: bit-identical, but in the run of 0.9.58 it took 27 ms per call on the GPU (waiting its turn on the GPU",
            "thread) against FreeTerraForged's 3.7 ms on the CPU.")
         .define("gpuCliffRelaxation", false), false, false);
      GPU_SERVICE_THREADS = Setting.of(builder
         .comment("How many threads run FreeTerraForged's tile filters on the GPU (each tile its own, on its own stream). Was 1 until",
            "0.9.60 (tiles waited their turn: 29 ms per erosion tile for ~1.7 ms of kernels). Read when the GPU starts (the",
            "first world of a game). The same tiles either way.")
         .defineInRange("gpuServiceThreads", 3, 1, 8), 3, 3);
      GPU_STREAM_PRIORITIES = Setting.of(builder
         .comment("GPU work a generation thread waits for runs at the card's highest priority, the look-ahead's (nobody waits yet)",
            "at its lowest (the same blocks either way; only the order the card runs them in changes).")
         .define("gpuStreamPriorities", true), true, true);
      PROFILE_RUNS = Setting.of(builder
         .comment("Benchmark runs measured further: a Java Flight Recorder CPU profile from /wgaccel reset to /wgaccel (saved in",
            "wgaccel-profiles), and the card's busy share from nvidia-smi in the reports.")
         .define("profileRuns", true), false, false);
      GPU_SURFACE_KINDS_FROM_CODES = Setting.of(builder
         .comment("The surface's inputs (which blocks are air, fluid, the default block) from the noise fill's codes instead of",
            "reading the chunk's blocks. Off: 0.9.53's codes check found a block the codes call stone but air in the chunk",
            "(1 chunk of 234), so the codes are not always the chunk's blocks when the surface starts.")
         .define("gpuSurfaceKindsFromCodes", false), false, false);
      GPU_LOOK_AHEAD = Setting.of(builder
         .comment("Noise fills worked out ahead of Minecraft's requests. Each new chunk is noted at its structure references step;",
            "two threads make its fill's inputs from a stand-in chunk (its own noise chunk, aquifer and FreeTerraForged tile",
            "stage, the same inputs as the real chunk's) and send up to 64 chunks to the GPU in one launch; at its noise step the",
            "chunk takes the ready blocks (chunks with structure terrain or blending, or not ready yet, go the usual way). OFF:",
            "never. CHECK: nothing ahead; for every 8th chunk the GPU fills, its inputs are also made the stand-in way and",
            "compared with the real chunk's. ON: the look-ahead (needs gpuNoiseFill ON; every 64th chunk is still checked).")
         .defineEnum("gpuLookAhead", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_LOOK_AHEAD_CHUNKS = Setting.of(builder
         .comment("With gpuLookAhead ON: how many finished chunks' blocks may wait in memory for their noise step (about 120 KB",
            "each); past that the oldest are dropped.")
         .defineInRange("gpuLookAheadChunks", 2048, 64, 65536), 2048, 2048);
      GPU_BIOMES = Setting.of(builder
         .comment("The biome step on the GPU (its climate and climate-table search so far; FreeTerraForged's decoration stays on the",
            "CPU). OFF: never. CHECK: in the first 16 overworld chunks every cell's climate and search are worked out again on the",
            "GPU and compared with the game's (logged as WGA GPU biome check). ON: each chunk's climate and search come from the",
            "GPU; every 64th chunk the CPU runs too and the results are compared (logged as WGA GPU biomes).")
         .defineEnum("gpuBiomes", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_BIOMES_AHEAD = Setting.of(builder
         .comment("With gpuBiomes ON: the biome step's GPU work done ahead. Each new chunk is noticed at its structure starts; three",
            "threads make its inputs from a stand-in chunk and run it on the GPU, and its biome step takes the ready result (one",
            "not ready in time is worked out on the CPU, so no chunk waits for the GPU). false: each chunk runs on the GPU at its",
            "biome step and waits for it (run V). Every 64th chunk is still checked against the CPU.")
         .define("gpuBiomesAhead", false), true, true);
      GPU_BIOME_DECORATION = Setting.of(builder
         .comment("With gpuBiomes ON: FreeTerraForged's biome decoration (cave biomes and rivers) on the GPU too, in the same run as",
            "the climate and search (cells needing a cave region's stage choice, and ties, stay the CPU's). OFF: the CPU decorates.",
            "CHECK: the GPU decorates every cell too and every chunk compares it with the CPU's (logged with WGA GPU biomes). ON:",
            "the GPU's decorated biomes are used; every 64th chunk is still compared.")
         .defineEnum("gpuBiomeDecoration", GpuFilterMode.OFF), GpuFilterMode.ON, GpuFilterMode.ON);
      GPU_SURFACE_AHEAD = Setting.of(builder
         .comment("With gpuSurface ON: a chunk's surface worked out on the GPU right after its noise fill (from the fill's blocks and",
            "the recorded biomes of the chunk and its neighbours), so its surface step takes the ready blocks instead of waiting",
            "for the card or doing it on the CPU. Only chunks filled from the GPU whose neighbours' biomes are known; the rest go",
            "the usual way. Every 64th chunk's inputs are also made the usual way and compared.")
         .define("gpuSurfaceAhead", false), false, false);
      GPU_BULK_SECTIONS = Setting.of(builder
         .comment("S3: sections the noise fill covers completely, and sections the surface changes for the most part, are made at",
            "once from their blocks (palette and packed data in one go) instead of block by block. The same blocks either way.")
         .define("gpuBulkSections", true), true, true);
      GPU_AREA_BATCHES = Setting.of(builder
         .comment("S5: the look-ahead (fill and biomes) takes whole aligned 4 x 4 chunk areas at once when it notices a chunk,",
            "so the GPU gets fewer, bigger launches. The same blocks either way (an area chunk is only used if it is the same).")
         .define("gpuAreaBatches", true), true, true);
      GPU_STRUCTURES = Setting.of(builder
         .comment("S7: chunks with structure terrain (villages, ancient cities, trail ruins...) fill on the GPU too: the",
            "Beardifier's pieces and junctions are worked out on the GPU as Minecraft (and C2ME) sums them.")
         .define("gpuStructures", true), true, true);
      GPU_CUBIN_CACHE = Setting.of(builder
         .comment("S2: the GPU programs are kept on disk also as the driver's finished machine code (cubin), so a world loads them",
            "without the driver translating them again (the same code the driver would make).")
         .define("gpuCubinCache", true), true, true);
      SPEC = builder.build();
   }

   private Config() {
   }
}
