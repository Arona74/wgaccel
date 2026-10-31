# Worldgen Accelerator

Faster world generation for Minecraft 1.21.1 and 26.1.2 (NeoForge and Fabric), built first for **FreeTerraForged**
(1.21.1) and, since 0.60.0, **Conquest ReTerraForged** (Fabric 26.1.2). The world comes out the same, block for block:
every speed-up is checked against Minecraft's and the terrain mod's own results while you play, and a part whose
answer differs turns itself off and the game carries on with the normal code.

On the test PC it made Chunky pregeneration with FreeTerraForged **3.1x faster** (radius 1000: 2:23 -> 0:46), and
**4.3x faster** on a PC with an AMD Radeon RX 9070 XT (4:20 -> 1:00). See
[Conquest ReTerraForged](#conquest-reterraforged-fabric-2612) for that mod.

> [!WARNING]
> **This mod is AI-generated.** All of its code, these instructions and its config descriptions were written by
> Claude (an AI made by Anthropic), directed and tested in game by one person.
> It changes how chunks are generated, so **back up your worlds**, try it on a copy first, and report problems with your
> log (`logs/latest.log`). The self-checks catch a wrong result and turn that part off, but no check catches everything.

> [!NOTE]
> **The GPU parts run through OpenCL, on NVIDIA, AMD and Intel GPUs, with nothing to install but the GPU's driver.**
> The mod uses the OpenCL compiler every GPU driver has. NVIDIA's CUDA (with the CUDA Toolkit) and AMD's HIP (with the
> HIP SDK on Windows, ROCm on Linux) can be chosen instead in the settings screen; on Linux with ROCm installed HIP is
> taken by itself (see [Linux](#linux)). Intel integrated graphics are used only when chosen
> there: the one measured was slower than its laptop's CPU. Without a GPU OpenCL can use, the mod still runs with its CPU
> improvements. When you join a world, a chat line names the GPU and the API in use (`gpuTestedWarning = false` turns it
> off).
>
> On Minecraft 26.1.2 the GPU parts run through OpenCL only: CUDA and HIP aren't available there yet (the game's
> LWJGL 3.4 has no CUDA module), and a CUDA or HIP choice falls back to OpenCL.

> [!IMPORTANT]
> **Not together with C2ME's OpenCL module** (`c2me-opts-accel-opencl`). Both move world generation to the graphics
> card, in different ways. When it is installed, Worldgen Accelerator turns itself off completely (no changes to the
> game at all) and says so in the log and in chat. Use one or the other.

## Contents

- [What it does](#what-it-does)
- [What runs on the CPU and what on the GPU](#what-runs-on-the-cpu-and-what-on-the-gpu)
- [Measured gains](#measured-gains)
- [Conquest ReTerraForged (Fabric 26.1.2)](#conquest-reterraforged-fabric-2612)
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
| Biomes | climate sampling, the biome table search, FreeTerraForged's biome decoration (cave biomes, rivers); off when another mod changes Minecraft's climate sampling (Conquest of Arborea does) | faster biome choice: FreeTerraForged's repeated look-ups reused (`fasterBiomeChoice`) |
| 3D terrain (noise fill) | density, aquifers (Conquest ReTerraForged's too), ore veins, structure terrain (Beardifier) | fill only up to the ground (`fillToGround`), the terrain's rough heights that aquifers ask for shared by a world's chunks (`fastSurfaceLevels`, 26.1.2) |
| Surface | the surface rules (grass, sand, snow, FreeTerraForged's strata, Conquest ReTerraForged's beach and sea) | direct block reads (`fastSurfaceReads`) |
| Structures | | jigsaw connection points cached per template (`fastJigsaw`) |
| Carvers | | biomes read from the chunk (`carverBiomesFromChunk`; FreeTerraForged worlds only: in vanilla the chunk's biome differs from the carvers' own in about 0.04% of chunks) |
| Features | | ores without repeated chunk look-ups, and ores above the ground without trying every column (`fastOres`), neighbour chunks and worldgen heights kept per region (`fastRegionChunks`, `fastRegionHeights`), Conquest ReTerraForged's landform answers remembered (`fastConquestLandforms`), Conquest of Arborea's forest cells kept (`fastArboreaCells`), FreeTerraForged's placement scopes (`ftfFastPlacementScopes`); on the GPU: Lithos's rock noise (`gpuLithosNoise`, 26.1.2) |
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
| Vanilla 1.21.1 overworld, radius 2000 (63,001 chunks), with `gpuAreaStep` on (it turns the GPU on in vanilla) | 2:15 | 1:22 | 1.65x |
| Vanilla 1.21.1 overworld, radius 2000, default settings (CPU parts only in vanilla) | 2:15 | 1:58 | 1.14x |

Every check in these runs was identical. Your PC, mods and settings will give other numbers.

**Vanilla 26.1.2** (the AMD desktop below: Ryzen 7 7800X3D, RX 9070 XT through OpenCL; Fabric dedicated server, C2ME
0.4.0-alpha.0.65 with 12 threads, seed 0, radius 2000, 63,001 chunks):

| Setup | Time | Speed-up |
| --- | --- | --- |
| Without Worldgen Accelerator | 2:23 | 1.00x |
| CPU improvements and GPU surface only (the earlier defaults) | 2:23 | 1.00x |
| + GPU noise fill (now the default; 3 runs: 1:41, 1:41, 1:40) | **1:41** | **1.42x** |
| + GPU biomes (`gpuBiomesVanilla`) | 1:41 | 1.42x |
| The 4 x 4 area step (`gpuAreaStep`, off by default; 2 runs: 1:35, 1:33) | 1:34 | 1.52x |

The GPU surface made no difference either way in vanilla (with the GPU fill: 1:41 on, 1:37 and 1:45 off). Every
GPU check was identical.

0.61.0's CPU parts, same setup (whole runs on this PC vary by several seconds, so the steps' own times are given):

| Part | Before | After |
| --- | --- | --- |
| Noise fill step with the GPU fill (`fastSurfaceLevels`: aquifers' rough heights shared by the chunks) | 2.7-2.8 ms per chunk | 1.66 ms per chunk (GPU look-ahead inputs 0.97 -> 0.26 ms) |
| Features step (`fastOres`: ores above the ground) | 3.75 ms per chunk | 3.35 ms per chunk (`ore_iron_upper` 0.20 -> 0.12 ms, `ore_coal_upper` 0.10 -> 0.05 ms a call) |
| Radius 2000 | 1:23, 1:23 (0.60.0's jar: 1:25, 1:28, 1:25) | 1:17-1:21 |

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

## Conquest ReTerraForged (Fabric 26.1.2)

Conquest ReTerraForged 0.1.0 with the Conquest Reforged mods (Conquest of Arborea, Lithos, Conquest of the Birds,
ArdaGrass). Its world type turns the GPU noise fill on by itself (`gpuNoiseFillVanilla` is not needed); the GPU
surface handles its beach and sea conditions. The GPU biomes stay off: Conquest of Arborea replaces Minecraft's
temperature and humidity with its own climate, which the GPU's climate does not follow.

**Measured** on the AMD desktop above (Ryzen 7 7800X3D, RX 9070 XT through OpenCL), Windows 11, Fabric 26.1.2, Java 25, 8 GB heap,
C2ME 0.4.0-alpha.0.65 with 12 threads, ScalableLux. Chunky radius 1000 (16,129 chunks), a new world each time:

| Setup | Time | Noise fill per chunk |
| --- | --- | --- |
| CPU improvements and GPU surface, noise fill on the CPU (2 runs) | 2:38, 2:40 | 15.7-17.2 ms |
| + GPU noise fill | **2:20** | **1.9 ms** |

The new CPU improvements, measured per part:

| Part | Before | After |
| --- | --- | --- |
| Conquest of Arborea's `tree_forest` (`fastConquestLandforms`) | 46 ms per chunk | 8.5-14 ms per chunk |
| Lithos's `strata` (`fastRegionChunks`) | 4.92 ms per call | 3.64 ms per call (test server) |
| Lithos's four features (`fastRegionHeights`, and `fastRegionChunks` without per-call allocation) | 11.2 ms per chunk | 9.7 ms per chunk (test server, 2 runs; all features 23.5 -> 21.6-21.9 ms) |
| Lithos's rock noise on the GPU (`gpuLithosNoise`): `strata` | 6.2-6.3 ms per call | 3.8-3.9 ms per call (test server, 2 runs; r1000 0:54-0:55 -> 0:50-0:51) |
| Conquest of Arborea's `tree_forest` (`fastArboreaCells`) | 6.74 ms per call | 4.83 ms per call (test server at 425, 2089; r1000 1:44 -> 1:37-1:42) |
| Region chunk look-ups decided once per region, last chunk kept (`fastRegionChunks`) | all features 26.9-29.1 ms per chunk | 24.3-24.5 ms per chunk (test server at 425, 2089, 2 runs each; r1000 1:37-1:42 -> 1:28-1:29) |
| Conquest's surface, scree and river answers remembered too (`fastConquestLandforms`): Arborea's `beach_gradient` | 1.06-1.08 ms per call | 0.35 ms per call (test server at 425, 2089, 2 runs each) |
| Conquest's walled town search, the same 3 attempts (`fastJigsaw`) | 8.47 s | 7.02 s |

Every check in these runs was identical. A run without the mod has not been measured. Most of the time left is the
features of other mods (Lithos, Conquest of Arborea, the deep dark's sculk, about 68% of the work per chunk), which
run on the CPU as they are. Conquest's walled towns never start in 0.1.0 (the town's datapack names jigsaw pools
that don't exist; reported to its developers), and their search still takes most of the structure time.

## Supported versions and mods

| | Version | Notes |
| --- | --- | --- |
| Minecraft | 1.21.1 and 26.1.2 | one jar per loader and Minecraft version: `wgaccel-<loader>-<version>+mc<Minecraft version>.jar` |
| NeoForge (1.21.1) | 21.1.x (tested 21.1.249 to 21.1.264) | `wgaccel-neoforge-<version>+mc1.21.1.jar` |
| NeoForge (26.1.2) | 26.1.x (built against 26.1.2.124) | `wgaccel-neoforge-<version>+mc26.1.2.jar`; tested in game |
| Fabric (1.21.1) | Fabric Loader 0.16 or later with Fabric API (tested Loader 0.19.5, Fabric API 0.116.17) | `wgaccel-fabric-<version>+mc1.21.1.jar`; since 0.50.0, the same mod and checks as on NeoForge (tested with FreeTerraForged 1.0.0014's Fabric build and C2ME 0.4.0-alpha.0.30, area step too). ModMenu (optional) gives it a Config button |
| Fabric (26.1.2) | Fabric Loader 0.20.1 or later with Fabric API (tested Fabric API 0.155.3) | `wgaccel-fabric-<version>+mc26.1.2.jar`; tested with Conquest ReTerraForged and in vanilla worlds, with and without C2ME |
| FreeTerraForged | **1.0.0014 from the `1.21.1_unstable` branch** (24 Oct 2026, with the `deleteTiles` work merged); 1.0.0013 and 1.0.0010 | Minecraft 1.21.1 only. 1.0.0014 (24 Oct build) checked; its earlier 23 Oct build and 1.0.0013 work too. 1.0.0010 (tiles) gets the GPU tile parts; 1.0.0013 and later have no tiles. When FreeTerraForged fixes one of these slow paths itself, the mod's fix turns itself off. |
| Conquest ReTerraForged | 0.1.0 (Fabric 26.1.2) with Conquest Reforged, Conquest of Arborea, Lithos, Conquest of the Birds, ArdaGrass | works, exact; GPU noise fill and surface, CPU improvements for its trees and Lithos (see [above](#conquest-reterraforged-fabric-2612)); GPU biomes off (Conquest of Arborea's climate) |
| C2ME | 0.4.0-alpha.0.123 on 1.21.1 (recommended), 0.4.0-alpha.0.65 on 26.1.2 | 19 threads on the test PC; the 4 x 4 area step (`gpuAreaStep`) needs it |
| C2ME OpenCL module | | **not together**: Worldgen Accelerator turns itself off. On Fabric 1.21.1 the module also crashes FreeTerraForged worlds on its own (it can't compile FreeTerraForged's terrain); keep the main C2ME jar only |
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
| Vanilla worlds (no FreeTerraForged) | | on 26.1.2 the GPU noise fill is on by default (r2000 2:23 -> 1:41); on 1.21.1 the CPU parts help (2:15 -> 1:58) and the GPU parts are off by default (alone they were slower), with `gpuAreaStep` on they go on and it was 1:22 |

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

1. Install NeoForge, or Fabric Loader with Fabric API, for Minecraft 1.21.1 or 26.1.2, then the terrain mod
   (FreeTerraForged on 1.21.1, Conquest ReTerraForged on Fabric 26.1.2) and C2ME, Lithium and ScalableLux if you like.
   On Fabric, ModMenu is optional: it adds the mod's settings button to the mods list.
2. Put the jar for your loader and Minecraft version into the instance's `mods` folder, for example
   `wgaccel-neoforge-<version>+mc1.21.1.jar` or `wgaccel-fabric-<version>+mc26.1.2.jar`. That's all for the CPU
   improvements.
3. **For the GPU parts:** update your GPU's driver (NVIDIA, AMD Adrenalin or Intel). That's all: the mod builds its
   GPU programs with the driver's own OpenCL compiler.
4. Start the game and open a world. The log (`logs/latest.log`) should show `WGA GPU backend: OpenCL`,
   `WGA GPU self-test: BIT-IDENTICAL` and lines like `WGA GPU noise fill: ready`. If the GPU can't be used it says
   `WGA GPU unavailable: <why>` and the mod carries on with the CPU part.

**Which API:** OpenCL by default. It needs no SDK and ran about as fast as the vendors' own APIs on the cards measured
(an RTX 5060 laptop: 0:34 through OpenCL, 0:32 through CUDA; an RX 9070 XT: 0:46 through OpenCL, the same as HIP).
CUDA and HIP are used only when chosen under Manual in the settings screen (below), but for HIP on Linux with ROCm
(see [Linux](#linux)), and need a compiler of their own:

- **CUDA (NVIDIA):** the **NVIDIA CUDA Toolkit** 12.x or 13.x from https://developer.nvidia.com/cuda-toolkit-archive
  (Windows, x86_64, "exe (network)", the default "Express" install). Only its **NVRTC** part (`nvrtc64_*.dll`) is used.
  A GTX 10xx needs 12.x (CUDA 13 dropped it); an RTX 50xx needs 12.8 or newer. Restart the launcher afterwards: one
  that was open during the install doesn't see the new `CUDA_PATH`.
- **HIP (AMD, Windows):** the **AMD HIP SDK for Windows** 6.4 or newer from
  https://www.amd.com/en/developer/resources/rocm-hub/hip-sdk.html. Only its **hipRTC** part (`hiprtc*.dll`) is used.
  Restart the launcher afterwards.
- **HIP (AMD, Linux):** ROCm; see [Linux](#linux).

**When the GPU doesn't help:** the GPU parts pay off on a GPU with enough compute power beside the CPU. On a small or
old one, the CPU alone can be faster:

- a Radeon 610M (RDNA 2, 2 compute units) was slower than its laptop's CPU, while a Radeon 680M (RDNA 2, 12 units)
  made its laptop 1.6x faster; the Intel Arc iGPU of an Ultra 9 275HX laptop took 1:49 where the CPU alone took 0:42.
  The automatic choice skips both kinds (fewer than 8 compute units, Intel integrated graphics);
- older generations, such as NVIDIA's GTX 10xx and earlier or AMD's Radeons before RDNA 2 (RX 5000 and older), are
  likely to gain little or lose: they are still taken automatically, so compare a Chunky run with **GPU improvements**
  off in the settings screen, and leave them off if the CPU alone is faster. (The mod does not use ray-tracing units:
  what counts is the GPU's general compute power.)

**Which GPU:** on a PC with more than one (a desktop Ryzen's own small Radeon beside the graphics card, a laptop's
Intel graphics beside an NVIDIA card, for example) the mod takes a separate card first, then the one with the most
compute units. An AMD integrated GPU (a laptop's Radeon 680M, for example) is taken when it is the only one; Intel
integrated graphics, and any GPU with fewer than 8 compute units (AMD's CUs, NVIDIA's SMs), only when you choose them.
To see or change it, open the **settings screen**: the mod's logo button in
Options (left of the field of view, or under Distant Horizons' button), or the mods list (Config on NeoForge, the
mod's settings button in ModMenu on Fabric). Its tabs:

- **Config:** Automatic, or Manual: then you choose the API (OpenCL, CUDA or HIP) and the GPU. It lists every
  graphics adapter of the PC with what the mod can do with it and which one this game uses. The GPU is chosen once per
  game: a choice made before opening a world applies to it, a later one after restarting the game (the screen says so).
  The choice is saved as `gpuBackend` and `gpuDevice` in the config; `-Dwgaccel.gpuBackend=cuda`, `hip` or `opencl` on
  Java's command line still wins. Below it, **General**: the preset, Chunky's chunks in flight, the memory report and
  the GPU chat line. Then **Debug info level** sets how much the mod writes to the log: Verbose
  (every line, for a bug report), Normal (the default, about ten times fewer lines) or Silent (warnings and errors
  only); it applies at once.
- **GPU improvements** and **CPU improvements:** every improvement with an on/off switch and what it does, and the
  switch for the whole section. A switch is saved in `overrides` (`[general]` in the config) and wins over the preset;
  **Defaults** puts the tab back to the preset's values. Changes apply from the next world you open.
- **Advanced:** the GPU's checks and tuning (`[gpu.tuning]`), in groups: checks, queue and batches, streams and
  threads, look-ahead, surface, other. Switches, mode buttons, number boxes (red while out of range) and list boxes
  (entries separated by `;`). For tests and benchmarks: the defaults are the measured best and every value gives the
  same world. **Defaults** puts the tab back.
- **System info:** the CPU, memory, GPUs and their drivers, Java, Minecraft, the mod loader (NeoForge, or Fabric
  Loader and Fabric API), the worldgen mods and the GPU compilers the mod found. **Copy** puts it on the clipboard for a bug report.
- **Benchmark:** performance tests run from the game, coming in a later version (the tab is greyed for now).

With CUDA chosen, the mod looks for `nvrtc64_*.dll` in `CUDA_PATH` and every `CUDA_PATH_V*` (`bin` and `bin/x64`), then in
`C:/Program Files/NVIDIA GPU Computing Toolkit/CUDA/v*` (newest first), then on `PATH`. To point it somewhere else,
start Java with `-Dwgaccel.nvrtc=C:/path/to/nvrtc64_120_0.dll`. With HIP it looks for `hiprtc*.dll` in the `bin`
folder of `HIP_PATH` and every other `HIP_PATH*`, then in `C:/Program Files/AMD/ROCm/*/bin` (newest first), then on
`PATH`; `-Dwgaccel.hiprtc=C:/path/to/hiprtc0604.dll` points it somewhere else.

### Linux

Tested on Linux Mint 22 (Ubuntu 24.04 base), kernel 7.0, with a Radeon 680M (Ryzen 7 PRO 6850H): r1000 in 1:29 through
HIP against 2:26 on the CPU alone, every check identical.

- **OpenCL** loads `libOpenCL.so.1` (the ICD loader, package `ocl-icd-libopencl1`) by itself; the development package
  is not needed. `-Dorg.lwjgl.opencl.libname=<path>` points it somewhere else.
- **Mesa's OpenCL (rusticl, clover) is not used automatically:** on the 680M (Mesa 25.2) it compiled the kernels for
  over 3 minutes and then stalled. Chosen by hand it needs `RUSTICL_ENABLE=radeonsi` and `RUSTICL_FEATURES=fp64`.
- **AMD: install ROCm.** Which API is faster depends on the GPU's generation:
  - RDNA 2 integrated (Radeon 680M): HIP, by far (ROCm's OpenCL was 10-15x slower on the same GPU);
  - RDNA 3 and 4 (RX 7900 XTX, RX 9070 XT): the same either way (a 7900 XTX: 3:22 through HIP, 3:25 through OpenCL);
  - RDNA 1 (RX 5000): an RX 5700 XT was faster through OpenCL than through HIP (measured on Windows; ROCm does not
    list RDNA 1 either).

  With ROCm installed, the automatic choice takes HIP on the AMD GPU, but OpenCL on RDNA 1, and OpenCL when the GPU it
  would otherwise take (a separate card first, then the most compute units) is another vendor's: an NVIDIA card beside
  a Ryzen's own Radeon stays on OpenCL, while an RX 7900 XTX beside an RTX 3060 goes to HIP. Steps (Ubuntu 24.04 and
  Mint 22):
  1. Add AMD's ROCm apt repository (https://rocm.docs.amd.com, "Quick start installation", version 6.4 or newer) and
     install `hip-runtime-amd` and `rocm-opencl-runtime` (the automatic choice looks at ROCm's OpenCL to tell which
     GPU HIP should take; without it, choose HIP in the settings screen).
  2. `sudo usermod -aG render,video $USER`, then **log out and back in** (or restart): until then `/dev/kfd` is
     closed to the game and ROCm finds no GPU.
  3. Integrated Radeons that ROCm does not list (680M and 660M: gfx1035) need `HSA_OVERRIDE_GFX_VERSION=10.3.0` in
     the game's environment (in Prism: the instance's Settings, Environment variables). Graphics cards ROCm supports
     need nothing.

  The mod looks for `libamdhip64.so` and `libhiprtc.so` in `ROCM_PATH/lib`, `/opt/rocm/lib` and `/opt/rocm-x.y.z/lib`
  (newest first); `-Dwgaccel.hip=<path>` and `-Dwgaccel.hiprtc=<path>` point it somewhere else. The first world takes
  about 30 s longer to open on the 680M while the GPU programs are built. If no GPU is taken, the `WGA GPU backend`
  line in the log says what the search found and what is missing.

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
   Maven Central, `org.lwjgl:lwjgl:3.3.3`). Minecraft 26.1.2 uses LWJGL **3.4.1**: the same steps with `3.4.1` in
   every name and path below.
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

**Java:** Minecraft 26.1.2 needs Java 25. On 1.21.1, Java 21 (its own) works. The radius 1000 measurements above were made on Java 25 (the AMD
laptop's on Java 21); the difference between Java 21 and 25 for this mod **has not been measured**.

## Config

`config/wgaccel-common.toml`, made the first time the game starts. Every setting has a description, and the main ones
say how much they gained on the test PC.

| Section | What's in it |
| --- | --- |
| top | `preset`: `AUTO` (default: `CLIENT` in the game, `SERVER` on a dedicated server), `CLIENT`, `SERVER` (the measured best for each), or `CUSTOM` (every setting as written in the file) |
| `[general]` | `chunkyMaxWorkingCount` (Chunky's chunks in flight; 0 = automatic from the heap and the world's height), `memoryReport` (off; logs what holds the memory, for a bug report), `debugInfoLevel` (`VERBOSE` every log line, for a bug report; `NORMAL`, the default, each kind of line the first time and every 10th time after; `SILENT` warnings and errors only; also on the settings screen's Config tab), `overrides` (the improvements switched in the settings screen, as `key=value`; they win over the preset) |
| `[cpu]` | **`cpuImprovements`** (all CPU improvements on or off), then each one: `fillToGround`, `tileCacheTiles`, `fasterBiomeChoice`, `fastOres`, `fastSurfaceReads`, `carverBiomesFromChunk`, `ftfFastPlacementScopes`, `ftfSharedChunkCells`, `fastChunkCompression`, `fastJigsaw`, `fastRegionChunks`, `fastRegionHeights`, `fastArboreaCells` (26.1.2), `fastConquestLandforms` (26.1.2), `fastSurfaceLevels` (26.1.2), `fastBlockPredicates` (off: it was slower) |
| `[gpu]` | **`gpuImprovements`** (all GPU improvements on or off: off, the card is never touched), `gpuTestedWarning` (the chat line naming the GPU and API in use), `gpuBackend` and `gpuDevice` (which GPU: AUTO and -1 = automatic; set by the settings screen's Config tab), then each GPU step: `gpuTileNoise`, `gpuErosionFilter`, `gpuCliffRelaxation`, `gpuNoiseFill`, `gpuLookAhead`, `gpuStructures`, `gpuBiomes`, `gpuBiomesAhead`, `gpuBiomeDecoration`, `gpuSurface`, `gpuSurfaceAhead`, `gpuLithosNoise` (26.1.2, with Lithos), the dimension and vanilla switches (`gpuNoiseFillVanilla` is not needed in Conquest ReTerraForged worlds), `gpuAreaStep` |
| `[gpu.tuning]` | how GPU work is queued and batched (queue limits, batch sizes, threads, priorities). The defaults are the measured best. Also two checking tools: `gpuFillCheckEvery` (64; 1 compares every GPU chunk with the normal fill, slow) and `gpuFillDebugChunks` (chunks always compared and saved for a bug report). |

The GPU steps take `OFF`, `CHECK` (the GPU and the CPU both do the work and every result is compared: slower, for
testing) or `ON` (the GPU's result is used; every 64th is still compared). With `CLIENT` or `SERVER` the values in the
file are kept but not used; switch single improvements in the settings screen (whatever the preset), or set
`preset = "CUSTOM"` to use every value written in the file. The section switches, `overrides`,
`gpuTestedWarning`, `gpuBackend`, `gpuDevice`, `gpuStreamPool`, `fastBlockPredicates`, `gpuAreaStep` and `gpuNoiseFillCpuDimensions` always follow
the file.

`/wgaccel` in game shows what world generation did since the world opened (or since `/wgaccel reset`): chunks per
second, time per step, what the GPU did and every check's result. `/wgaccel profile [seconds]` (60 by default) samples
the generation threads while chunks generate and logs where their time goes: by mod, by method, and what they wait on.

## Building from source

The build needs a Java 25 JDK (26.1.2 is compiled for Java 25, 1.21.1 for Java 21). Into `libs/` go the jars it
compiles against:

- FreeTerraForged 1.0.0010 as `libs/freeterraforged-1.0.0010.jar` (or `-PftfJar=<path>`) and 1.0.0014 as
  `libs/freeterraforged-1.0.0014.jar` (or `-Pftf13Jar=<path>`); both versions compile against them;
- No Man's Land as `libs/nomansland-1.6.2.jar`;
- C2ME's inner jars (from the C2ME jar's `META-INF/jars/`): `base-<version>-all`, `rewrites-chunk-system-<version>`,
  `rxjava-*` and `reactive-streams-*`, into `libs/c2me/` for 1.21.1 and `libs/c2me-26.1.2/` for 26.1.2.

Then `gradlew build` makes all four jars in `build/libs/` (`wgaccel-<loader>-<version>+mc<Minecraft version>.jar`);
`gradlew -Pmc=26.1.2 build` builds one Minecraft version.

The shared sources are in `common/` (the whole mod, with Mojang's names), `neoforge/` and `fabric/` (each loader's
entry point). What differs between Minecraft versions is in `versions/<mc>/` (`platform/Mc`, version-specific mixins
in `wgaccel.mc.mixins.json`, the client screens, the CUDA facade), with each version's settings in
`versions/<mc>/gradle.properties`. Built with Architectury's Gradle plugins (build time only).
`gradlew -Pmc=<mc> :<mc>:fabric:prodServer` runs the Fabric jar in Fabric's production server; `-PprodMods=<folder>`
adds other mods' jars and `-PprodLwjgl` puts LWJGL on its class path, so the GPU parts run.

The offline GPU checks (`gradlew :1.21.1:common:cudaCompileCheck` and the other `cuda*` tasks, 1.21.1 only) need the CUDA Toolkit, and some read
files the mod saves into the game's logs folder: pass `-Plogs=<instance>/logs`. On an AMD card, `gradlew hipCheck`
is the self-test (plus the fixed kernels compiled with hipRTC), and the `cuda*` checks run through HIP with
`-PgpuBackend=hip`; they need the HIP SDK.

## Licence

MIT (see `LICENSE`): use, change and share it freely. Which parts follow Minecraft's and FreeTerraForged's own
algorithms, and what's bundled: `docs/CODE-ORIGINS.md`.
