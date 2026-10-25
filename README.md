# Worldgen Accelerator

Faster world generation for Minecraft 1.21.1 (NeoForge and Fabric), built first for **FreeTerraForged**. The world comes out the
same, block for block: every speed-up is checked against Minecraft's and FreeTerraForged's own results while you
play, and a part whose answer differs turns itself off and the game carries on with the normal code.

On the test PC it made Chunky pregeneration with FreeTerraForged **3.1x faster** (radius 1000: 2:23 -> 0:46), and
**4.3x faster** on a PC with an AMD Radeon RX 9070 XT (4:20 -> 1:00).

> [!WARNING]
> **This mod is AI-generated.** All of its code, these instructions and its config descriptions were written by
> Claude (an AI made by Anthropic), directed and tested in game by one person.
> It changes how chunks are generated, so **back up your worlds**, try it on a copy first, and report problems with your
> log (`logs/latest.log`). The self-checks catch a wrong result and turn that part off, but no check catches everything.

> [!NOTE]
> **The GPU parts run through OpenCL, on NVIDIA, AMD and Intel GPUs, with nothing to install but the GPU's driver.**
> The mod uses the OpenCL compiler every GPU driver has. NVIDIA's CUDA (with the CUDA Toolkit) and AMD's HIP (with the
> HIP SDK, Windows) can be chosen instead in the settings screen. Intel integrated graphics are used only when chosen
> there: the one measured was slower than its laptop's CPU. Without a GPU OpenCL can use, the mod still runs with its CPU
> improvements. When you join a world, a chat line names the GPU and the API in use (`gpuTestedWarning = false` turns it
> off).

> [!IMPORTANT]
> **Not together with C2ME's OpenCL module** (`c2me-opts-accel-opencl`). Both move world generation to the graphics
> card, in different ways. When it is installed, Worldgen Accelerator turns itself off completely (no changes to the
> game at all) and says so in the log and in chat. Use one or the other.

## Contents

- [What it does](#what-it-does)
- [What runs on the CPU and what on the GPU](#what-runs-on-the-cpu-and-what-on-the-gpu)
- [Measured gains](#measured-gains)
- [Supported versions and mods](#supported-versions-and-mods)
- [Dimensions](#dimensions)
- [Installation (client)](#installation-client)
- [Installation (dedicated server)](#installation-dedicated-server)
- [Memory and Java](#memory-and-java)
- [Config](#config)
- [Building from source](#building-from-source)
- [Licence](#licence)

## What it does

Generating a chunk takes several steps: biomes, the 3D terrain ("noise fill": stone, air, water, lava, ore veins),
the surface (grass, sand, snow, rock layers), carvers, then features (trees, ores, plants). FreeTerraForged adds its
own terrain maps on top (heightmaps, erosion, rivers, cliffs). Worldgen Accelerator speeds these up in two ways:

1. **On the CPU**: it removes repeated or wasted work in Minecraft's and FreeTerraForged's code, such as filling
   terrain far above the ground, re-making FreeTerraForged's terrain tiles, looking chunks up again and again for ores,
   or working the same terrain out once per thread. These work on any PC.
2. **On the GPU**: whole steps are rewritten as GPU programs (written in CUDA C, translated to OpenCL C for OpenCL; CUDA
   and HIP compile them as they are) that do a chunk (or
   a FreeTerraForged tile) in one go on the graphics card, operation for operation the same maths as the Java code, so
   the results are bit-identical. The
   GPU programs are built when a world loads, from the world's own settings, datapacks and biome mods, so they follow
   whatever the world uses. A copy is kept on disk (`wgaccel-gpu-cache`), so a world loads them again at once.

While the GPU works on chunks ahead of time ("look-ahead"), the CPU does the steps that stay on the CPU, so both are
busy. If the card is busy (it also draws the game), a chunk simply goes the CPU way: nothing waits for the GPU.

**The checks.** Every 64th chunk (and every 256th ore or block read) is also worked out the normal way and compared;
the first difference turns that part off, with a warning in the log and in chat. That's how the mod stays exact with
mods it has never seen. One exception: the GPU surface check only reports differences in the log (`WGA GPU surface
check`), because mods whose features reach into a neighbouring chunk while its surface is being made (Still Life, for
one) cause the occasional difference there that isn't the GPU's (it happens without this mod too, as a race between
threads).

## What runs on the CPU and what on the GPU

| Step | GPU (NVIDIA, or AMD on Windows) | CPU |
| --- | --- | --- |
| FreeTerraForged's terrain tiles (1.0.0010) | the tile noise (terrain populators), the erosion filter | a bigger tile cache (`tileCacheTiles`) |
| FreeTerraForged's chunk cells (1.0.0013 "deleteTiles") | | made once per chunk instead of three times (`ftfSharedChunkCells`; earlier 1.0.0013 builds only, it turns itself off on later ones) |
| Biomes | climate sampling, the biome table search, FreeTerraForged's biome decoration (cave biomes, rivers) | faster biome choice: FreeTerraForged's repeated look-ups reused (`fasterBiomeChoice`) |
| 3D terrain (noise fill) | density, aquifers, ore veins, structure terrain (Beardifier) | fill only up to the ground (`fillToGround`) |
| Surface | the surface rules (grass, sand, snow, FreeTerraForged's strata) | direct block reads (`fastSurfaceReads`) |
| Carvers | | biomes read from the chunk (`carverBiomesFromChunk`) |
| Features | | ores without repeated chunk look-ups (`fastOres`), FreeTerraForged's placement scopes (`ftfFastPlacementScopes`) |
| Pregeneration | | more chunks in flight for Chunky (`chunkyMaxWorkingCount`) |

## Measured gains

**Test PC:** AMD Ryzen 9 7900X (12 cores, 24 threads), NVIDIA RTX 3080 10 GB, 32 GB DDR5, Gen 4 NVMe SSD, Windows 11,
Java 25, 16 GB heap (`-Xmx16G`, ZGC), C2ME 0.4.0-alpha.0.123 with 19 threads, Lithium, ScalableLux. Chunky
pregeneration, radius 1000 (16,129 chunks), a new world each time. Each row adds one change to the row above it.

**FreeTerraForged 1.0.0010 (unstable), overworld:**

| Change | Time | Chunks/s | Speed-up |
| --- | --- | --- | --- |
| Without Worldgen Accelerator | 2:23 | 113 | 1.00x |
| Fill only up to the ground | 1:55 | 140 | 1.24x |
| + ScalableLux (another mod) | 1:48 | 149 | 1.32x |
| + bigger FreeTerraForged tile cache | 1:28 | 183 | 1.63x |
| + faster biome choice | 1:19 | 204 | 1.81x |
| + GPU erosion filter | 1:13 | 221 | 1.96x |
| + GPU noise fill | 1:07 | 241 | 2.13x |
| + GPU surface | 1:06 | 244 | 2.17x |
| + GPU look-ahead | 1:01 | 264 | 2.34x |
| + GPU biomes | 0:59 | 273 | 2.42x |
| + GPU queue tuning, CPU fixes (ores, carvers) | 0:51 | 316 | 2.80x |
| + GPU tile noise | **0:46** | **351** | **3.1x** |

The same on a dedicated server (same PC, the card free): 0:47.

**Other setups (same PC):**

| Setup | Without | With | Speed-up |
| --- | --- | --- | --- |
| FreeTerraForged + Terralith + Biomes O' Plenty + Regions Unexplored, overworld r1000 | 2:49 | 0:57 | 2.9x |
| FreeTerraForged 1.0.0010 (22 Oct unstable, a 1,152-block world), overworld r2000 (63,001 chunks) | not measured | 3:14 (325 chunks/s) | |
| FreeTerraForged 1.0.0013 "deleteTiles" (an earlier build), overworld r1000: before and after the mod's fixes for its new chunk cells (with `gpuAreaStep` on) | 60-90 chunks/s | 1:53 (152 chunks/s) | about 2x |
| FreeTerraForged 1.0.0010 with the 4 x 4 area step (`gpuAreaStep`, off by default) | 0:46 | 0:44 | 1.05x |
| Vanilla overworld, radius 2000 (63,001 chunks), with `gpuAreaStep` on (it turns the GPU on in vanilla) | 2:15 | 1:22 | 1.65x |
| Vanilla overworld, radius 2000, default settings (CPU parts only in vanilla) | 2:15 | 1:58 | 1.14x |

Every check in these runs was identical. Your PC, mods and settings will give other numbers.

**AMD desktop (0.16.1, HIP):** AMD Ryzen 7 7800X3D (8 cores, 16 threads), AMD Radeon RX 9070 XT 16 GB, Windows 11,
Java 25, 16 GB heap (`-Xmx16G`, ZGC), AMD HIP SDK 6.4, NeoForge 21.1.264, C2ME 0.4.0-alpha.0.123 (its default
threads), Lithium, ScalableLux, Sodium. FreeTerraForged 1.0.0010, Chunky radius 1000 (16,129 chunks), a new world
each time:

| Setup | Time | Chunks/s | Speed-up |
| --- | --- | --- | --- |
| Without Worldgen Accelerator | 4:20 | 62 | 1.00x |
| CPU improvements only (`gpuImprovements = false`) | 2:31 | 107 | 1.72x |
| CPU and GPU improvements (4 runs: 1:02, 0:59, 0:59, 1:00) | **1:00** | **269** | **4.3x** |

The card made the CPU improvements alone 2.5x faster again. Every GPU check was identical.

**AMD laptop (0.16.0, HIP):** AMD Ryzen 9 6900HX (8 cores, 16 threads), AMD Radeon 680M (integrated, shares the
system memory), 32 GB DDR5, Windows 11, Java 21, 12 GB heap, AMD HIP SDK 6.4, C2ME 0.4.0-alpha.0.123 (its default 7
threads), Sodium, Iris. FreeTerraForged 1.0.0010, Chunky radius 300 (1,521 chunks), a new world each time. Short runs,
each measured once, so the times are rough:

| Setup | Time | Chunks/s | Speed-up |
| --- | --- | --- | --- |
| Without Worldgen Accelerator | 0:42 | 36 | 1.00x |
| CPU improvements only (`gpuImprovements = false`) | 0:28 | 54 | 1.50x |
| CPU and GPU improvements | **0:15** | **101** | **2.80x** |

Even a laptop's integrated graphics almost doubled the speed of the CPU improvements alone. Every check was identical.

## Supported versions and mods

| | Version | Notes |
| --- | --- | --- |
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.x (tested 21.1.249 to 21.1.264) | `wgaccel-neoforge-<version>.jar` |
| Fabric | Fabric Loader 0.16 or later with Fabric API (tested Loader 0.19.5, Fabric API 0.116.17) | `wgaccel-fabric-<version>.jar`; since 0.50.0, the same mod and checks as on NeoForge (tested with FreeTerraForged 1.0.0014's Fabric build and C2ME 0.4.0-alpha.0.30, area step too). ModMenu (optional) gives it a Config button |
| FreeTerraForged | **1.0.0014 from the `1.21.1_unstable` branch** (24 Oct 2026, with the `deleteTiles` work merged); 1.0.0013 and 1.0.0010 | 1.0.0014 (24 Oct build) checked; its earlier 23 Oct build and 1.0.0013 work too. 1.0.0010 (tiles) gets the GPU tile parts; 1.0.0013 and later have no tiles. When FreeTerraForged fixes one of these slow paths itself, the mod's fix turns itself off. |
| C2ME | 0.4.0-alpha.0.123 (recommended) | 19 threads on the test PC; the 4 x 4 area step (`gpuAreaStep`) needs it |
| C2ME OpenCL module | | **not together**: Worldgen Accelerator turns itself off |
| Lithium | 0.15.4 | works, recommended |
| ScalableLux | 0.3.0-alpha.0.8 | works, recommended (faster lighting) |
| Chunky | 1.4.23 | works; the mod raises its chunks in flight (`chunkyMaxWorkingCount`) |
| Terralith | 2.6.2 | works, exact |
| Biomes O' Plenty + TerraBlender | 21.1.0.14 / 4.1.0.8 | works, exact |
| Regions Unexplored | 0.6.2 | works, exact |
| Still Life | 0.3 (1.21.1 backport) with Lithosphere 1.8.2 and More Density Functions | works, exact |
| Railways Untold + Create | 1.2.2 / 6.0.10 (tested with FreeTerraForged: r1000 0:47) | compatible, exact; it places its stations and tracks outside chunk generation, and Create's ores are ordinary features |
| Create: Infinite Railways | 0.2.0 (with Railways Untold; r1000 0:51) | compatible, exact; its route planning runs on a thread of its own (one core busy, also while nothing generates) |
| RoadWeaver | 2.3.2 (tested with FreeTerraForged: r1000 0:47) | compatible, exact; chunks with a road's cut-and-fill (about 4%) fill on the CPU, and fill-to-ground keeps the road's embankments. Its own copy of LWJGL's OpenCL no longer clashes with the mod's (moved to `com.wgaccel.shaded.lwjgl` since 0.23.0) |
| Streams Reflowing | 2.15.2 (tested) | compatible, exact; no acceleration of its own work (its river feature runs on the CPU as it is) |
| Worldgen Patches | 1.1.0 | works; the GPU surface follows its steepness fix when it is installed |
| Nature's Spirit | 2.3.1 with Lithostitched 1.8.1 (tested with FreeTerraForged: r1000 1:25) | compatible, exact; chunks with its stone pillars (Sugi Forest, Stratified Desert and its dunes, about 4%) get their surface on the CPU, as vanilla's eroded badlands do. Its cliff and layer features (about 65,000 single blocks a chunk) are most of its cost and run on the CPU as they are |
| No Man's Land + Biolith | 1.6.2 / 3.0.14 (tested with FreeTerraForged; NeoForge only) | compatible, exact; its surface rules (biome tags, its y test, strata) run on the GPU too, with its steepness fix. Chunks around its Meeting Point altar fill on the CPU (it reshapes the terrain there). Faster on the CPU (r1000 2:06 -> 1:36 on the test PC): Biolith's sub-biome checks find their closest climate points through a tree instead of a scan of the whole table (`fasterBiomeChoice`), its every-layer features scan each column once, and its ore veins fill their noise once per layer and test the block they just read (`fastLayerPlacement`) |
| VanillaBackport | 1.2.1 (tested in a vanilla world) | compatible, exact; its sulfur caves' surface rules (3D noise conditions and noise gradients) run on the GPU too. It adds them to the vanilla overworld only, so FreeTerraForged worlds don't get them |
| Voxy, Sodium, Iris, FerriteCore, Structure Layout Optimizer | | work |
| Vanilla worlds (no FreeTerraForged) | | the CPU parts help (2:15 -> 1:58); the GPU parts are off by default in vanilla (alone they were slower); with `gpuAreaStep` on they go on and it was 1:22 |

Other mods that add biomes through datapacks usually work too: the GPU programs are built from the world's own
settings. If one doesn't, the checks turn the affected part off and the log says which.

## Dimensions

| Dimension | CPU improvements | GPU improvements |
| --- | --- | --- |
| Overworld | all | all |
| Nether | ores, surface reads, carvers and the other general ones | off by default: C2ME's CPU fill was faster there (r1000 with biome mods: 0:23 on the CPU, 0:30 on the GPU). Turn on by removing it from `gpuNoiseFillCpuDimensions`. |
| The End | the same as the Nether | off by default for the same reason (r2000: 0:31 on the CPU, 0:48 on the GPU) |
| Modded dimensions with a noise generator | the general ones | the GPU noise fill (`gpuNoiseFillOtherDimensions`); a dimension with terrain the GPU doesn't know stays on the CPU, and a chat message names it |

## Installation (client)

1. Install NeoForge 21.1.x, or Fabric Loader with Fabric API, for Minecraft 1.21.1, then FreeTerraForged (and C2ME,
   Lithium and ScalableLux if you like).
2. Put the jar for your loader into the instance's `mods` folder: `wgaccel-neoforge-<version>.jar` or
   `wgaccel-fabric-<version>.jar`. That's all for the CPU improvements.
3. **For the GPU parts:** update your GPU's driver (NVIDIA, AMD Adrenalin or Intel). That's all: the mod builds its
   GPU programs with the driver's own OpenCL compiler.
4. Start the game and open a world. The log (`logs/latest.log`) should show `WGA GPU backend: OpenCL`,
   `WGA GPU self-test: BIT-IDENTICAL` and lines like `WGA GPU noise fill: ready`. If the GPU can't be used it says
   `WGA GPU unavailable: <why>` and the mod carries on with the CPU part.

**Which API:** OpenCL by default. It needs no SDK and ran about as fast as the vendors' own APIs on the cards measured
(an RTX 5060 laptop: 0:34 through OpenCL, 0:32 through CUDA; an RX 9070 XT: 0:46 through OpenCL, the same as HIP).
CUDA and HIP are used only when chosen under Manual in the settings screen (below), and need a compiler of their own:

- **CUDA (NVIDIA):** the **NVIDIA CUDA Toolkit** 12.x or 13.x from https://developer.nvidia.com/cuda-toolkit-archive
  (Windows, x86_64, "exe (network)", the default "Express" install). Only its **NVRTC** part (`nvrtc64_*.dll`) is used.
  A GTX 10xx needs 12.x (CUDA 13 dropped it); an RTX 50xx needs 12.8 or newer. Restart the launcher afterwards: one
  that was open during the install doesn't see the new `CUDA_PATH`.
- **HIP (AMD, Windows):** the **AMD HIP SDK for Windows** 6.4 or newer from
  https://www.amd.com/en/developer/resources/rocm-hub/hip-sdk.html. Only its **hipRTC** part (`hiprtc*.dll`) is used.
  Restart the launcher afterwards.

**Which GPU:** on a PC with more than one (a desktop Ryzen's own small Radeon beside the graphics card, a laptop's
Intel graphics beside an NVIDIA card, for example) the mod takes a separate card first, then the one with the most
compute units. An AMD integrated GPU (a laptop's Radeon 680M, for example) is taken when it is the only one; Intel
integrated graphics only when you choose them (the Arc iGPU measured took 1:49 where its laptop's CPU alone took 0:42). To see or change it, open the **settings screen**: the mod's logo button in
Options (left of the field of view, or under Distant Horizons' button), or Config in the mods list. Its tabs:

- **Config:** Automatic, or Manual: then you choose the API (OpenCL, CUDA or HIP) and the GPU. It lists every
  graphics adapter of the PC with what the mod can do with it and which one this game uses. The GPU is chosen once per
  game: a choice made before opening a world applies to it, a later one after restarting the game (the screen says so).
  The choice is saved as `gpuBackend` and `gpuDevice` in the config; `-Dwgaccel.gpuBackend=cuda`, `hip` or `opencl` on
  Java's command line still wins.
- **GPU improvements** and **CPU improvements:** every improvement with an on/off switch and what it does, and the
  switch for the whole section. A switch is saved in `overrides` (`[general]` in the config) and wins over the preset;
  **Defaults** puts the tab back to the preset's values. Changes apply from the next world you open.
- **System info:** the CPU, memory, GPUs and their drivers, Java, Minecraft, NeoForge, the worldgen mods and the GPU
  compilers the mod found. **Copy** puts it on the clipboard for a bug report.
- **Benchmark:** performance tests run from the game, coming in a later version (the tab is greyed for now).

With CUDA chosen, the mod looks for `nvrtc64_*.dll` in `CUDA_PATH` and every `CUDA_PATH_V*` (`bin` and `bin/x64`), then in
`C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v*` (newest first), then on `PATH`. To point it somewhere else,
start Java with `-Dwgaccel.nvrtc=C:/path/to/nvrtc64_120_0.dll`. With HIP it looks for `hiprtc*.dll` in the `bin`
folder of `HIP_PATH` and every other `HIP_PATH*`, then in `C:/Program Files/AMD/ROCm/*/bin` (newest first), then on
`PATH`; `-Dwgaccel.hiprtc=C:/path/to/hiprtc0604.dll` points it somewhere else.

Through OpenCL and HIP the `/wgaccel` report leaves out the GPU time of each step (only CUDA times them: under HIP
GPU events made the results wrong on a Radeon 680M, and OpenCL's time nothing without a profiling queue). The work and
its results are the same.

The first world on a new GPU or driver takes longer to open while the GPU programs are built (the loading screen shows
it): from a few seconds to about a minute and a half (NVIDIA's OpenCL compiler on an RTX 5060 laptop). After that they
come from the disk cache (`wgaccel-gpu-cache`) and the GPU is ready in about a second.

## Installation (dedicated server)

A dedicated server needs a GPU driver as a client does (and the CUDA Toolkit or HIP SDK only when CUDA or HIP is
chosen, `gpuBackend` in the config), and LWJGL, which a client has and a server doesn't:

1. Put the mod's jar (and FreeTerraForged, C2ME, ...) into the server's `mods` folder (on Fabric, Fabric API too).
2. Copy `lwjgl-3.3.3.jar` and `lwjgl-3.3.3-natives-windows.jar` from a client's `libraries/org/lwjgl/lwjgl/3.3.3/`
   to the server's `libraries/org/lwjgl/lwjgl/3.3.3/` (on Linux, the `natives-linux` jar instead; both are also on
   Maven Central, `org.lwjgl:lwjgl:3.3.3`).
3. Add both jars to the server's Java class path:
   - **NeoForge:** open `libraries/net/neoforged/neoforge/<version>/win_args.txt` (keep a copy first: reinstalling
     NeoForge rewrites it) and add them to the end of the `-DlegacyClassPath=` list, separated by `;`:
     `...;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar`
     (on Linux: `unix_args.txt` and `:` as the separator).
   - **Fabric** (the server launcher jar from fabricmc.net): start it with `-cp` and its main class instead of `-jar`:
     ```
     java -Xmx8G -cp "fabric-server-mc.1.21.1-loader.0.19.3-launcher.1.1.0.jar;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar" net.fabricmc.installer.ServerLauncher nogui
     ```
     (the launcher's own file name as downloaded; on Linux `:` as the separator). Tested with Loom's production
     server, which starts the same launcher this way.
4. Start the server. The log shows `WGA GPU self-test: BIT-IDENTICAL` when the GPU works, or
   `WGA GPU unavailable: LWJGL is not loaded` when steps 2-3 are missing.

Without LWJGL a server starts all the same and runs the CPU improvements only.

Players joining the server don't need the mod: world generation happens on the server.

## Memory and Java

| Use | Suggested heap (`-Xmx`) |
| --- | --- |
| Playing, exploring | 6-8 GB |
| Pregenerating with Chunky | 10-16 GB (16 GB tested) |
| With big biome or structure mods on top | add 2-4 GB |

Most of the memory during pregeneration is the chunks Chunky keeps loaded: each chunk in flight keeps about 220 chunks
loaded (its neighbours at earlier steps), and a 768-block FreeTerraForged world's chunks are twice the size of vanilla's.
So the mod picks Chunky's chunks in flight from the heap and the world's height (`chunkyMaxWorkingCount = 0`,
automatic): about 300 with 16 GB and a 768-block world, 512 with a bigger heap or a vanilla-height world. The
FreeTerraForged tile cache (`tileCacheTiles = 256`) takes about 1.7 GB more. With too little heap the game slows down a
lot once it fills (the collector runs all the time): give it more, or lower `chunkyMaxWorkingCount`. ZGC (`-XX:+UseZGC -XX:+ZGenerational`) was used on
the test PC; leave the rest of the JVM flags as your launcher sets them. Graphics card memory: a few hundred MB.

**C2ME's workers:** C2ME's default number of worker threads leaves much of the CPU idle once the GPU parts take work
off it: on a 16-thread CPU it ran 7 workers, busy only about 60% of the time, with the CPU 11 of 16 busy. Setting
`globalExecutorParallelism = 12` in `config/c2me.toml` made the same pregeneration about 19% faster (RX 9070 XT
PC). About three quarters of your CPU threads is a good start; the log's
`WGA hint` line suggests a number when C2ME is on its default.

**Java:** Java 21 (Minecraft 1.21.1's own) works. The radius 1000 measurements above were made on Java 25 (the AMD
laptop's on Java 21); the difference between Java 21 and 25 for this mod **has not been measured**.

## Config

`config/wgaccel-common.toml`, made the first time the game starts. Every setting has a description, and the main ones
say how much they gained on the test PC.

| Section | What's in it |
| --- | --- |
| top | `preset`: `AUTO` (default: `CLIENT` in the game, `SERVER` on a dedicated server), `CLIENT`, `SERVER` (the measured best for each), or `CUSTOM` (every setting as written in the file) |
| `[general]` | `chunkyMaxWorkingCount` (Chunky's chunks in flight; 0 = automatic from the heap and the world's height), `memoryReport` (off; logs what holds the memory, for a bug report), `debugInfoLevel` (`VERBOSE` every log line, for a bug report; `NORMAL`, the default, each kind of line the first time and every 10th time after; `SILENT` warnings and errors only; also on the settings screen's Config tab), `overrides` (the improvements switched in the settings screen, as `key=value`; they win over the preset) |
| `[cpu]` | **`cpuImprovements`** (all CPU improvements on or off), then each one: `fillToGround`, `tileCacheTiles`, `fasterBiomeChoice`, `fastOres`, `fastSurfaceReads`, `carverBiomesFromChunk`, `ftfFastPlacementScopes`, `ftfSharedChunkCells`, `fastBlockPredicates` (off: it was slower) |
| `[gpu]` | **`gpuImprovements`** (all GPU improvements on or off: off, the card is never touched), `gpuTestedWarning` (the chat line naming the GPU and API in use), `gpuBackend` and `gpuDevice` (which GPU: AUTO and -1 = automatic; set by the settings screen's Config tab), then each GPU step: `gpuTileNoise`, `gpuErosionFilter`, `gpuCliffRelaxation`, `gpuNoiseFill`, `gpuLookAhead`, `gpuStructures`, `gpuBiomes`, `gpuBiomesAhead`, `gpuBiomeDecoration`, `gpuSurface`, `gpuSurfaceAhead`, the dimension and vanilla switches, `gpuAreaStep` |
| `[gpu.tuning]` | how GPU work is queued and batched (queue limits, batch sizes, threads, priorities). The defaults are the measured best. Also two checking tools: `gpuFillCheckEvery` (64; 1 compares every GPU chunk with the normal fill, slow) and `gpuFillDebugChunks` (chunks always compared and saved for a bug report). |

The GPU steps take `OFF`, `CHECK` (the GPU and the CPU both do the work and every result is compared: slower, for
testing) or `ON` (the GPU's result is used; every 64th is still compared). With `CLIENT` or `SERVER` the values in the
file are kept but not used; switch single improvements in the settings screen (whatever the preset), or set
`preset = "CUSTOM"` to use every value written in the file. The section switches, `overrides`,
`gpuTestedWarning`, `gpuBackend`, `gpuDevice`, `gpuStreamPool`, `fastBlockPredicates`, `gpuAreaStep` and `gpuNoiseFillCpuDimensions` always follow
the file.

`/wgaccel` in game shows what world generation did since the world opened (or since `/wgaccel reset`): chunks per
second, time per step, what the GPU did and every check's result.

## Building from source

Put a FreeTerraForged 1.0.0010 jar into `libs/` as `libs/freeterraforged-1.0.0010.jar` (or pass `-PftfJar=<path>`),
and into `libs/c2me/` the jars inside the C2ME jar's `META-INF/jars/` that the build compiles against:
`c2me-neoforge-base-<version>-all.jar`, `c2me-neoforge-rewrites-chunk-system-<version>.jar`, `rxjava-*.jar` and
`reactive-streams-*.jar`. Then run `gradlew build`; the mod is `build/libs/wgaccel-neoforge-<version>.jar`.

The sources are in `common/` (the whole mod, with Mojang's names), `neoforge/` and `fabric/` (each loader's entry point
and its jar), built with Architectury's Gradle plugins (build time only); the Fabric jar is
`build/libs/wgaccel-fabric-<version>.jar`. `gradlew :fabric:prodServer` runs it in Fabric's production server.

The offline GPU checks (`gradlew cudaCompileCheck` and the other `cuda*` tasks) need the CUDA Toolkit, and some read
files the mod saves into the game's logs folder: pass `-Plogs=<instance>/logs`. On an AMD card, `gradlew hipCheck`
is the self-test (plus the fixed kernels compiled with hipRTC), and the `cuda*` checks run through HIP with
`-PgpuBackend=hip`; they need the HIP SDK.

## Licence

MIT (see `LICENSE`): use, change and share it freely. Which parts follow Minecraft's and FreeTerraForged's own
algorithms, and what's bundled: `docs/CODE-ORIGINS.md`.
