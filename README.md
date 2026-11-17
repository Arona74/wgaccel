# Worldgen Accelerator (`0.64.1`)

> [!NOTE]
> **Attribution:** Originally created by **`agneht26-dotcom`** (`v0.9.78`–`v0.11.5`), expanded by **Arona** up through `0.63.3` / `0.63.4`, and continued here from `0.63.4-lab.1` through **`0.64.1`** for **Minecraft 1.21.1 (NeoForge)** with **FreeTerraForged** (`1.0.0010`–`1.0.0032`), **C2ME**, and **OpenCL** as the default GPU backend.

Faster chunk generation for **Minecraft 1.21.1 NeoForge** — built primarily for **FreeTerraForged** and popular worldgen/biome mods. Worlds generate **block-for-block identical** to vanilla/FreeTerraForged: every acceleration runs periodic live checks against the standard path and automatically disables any component that differs.

> [!WARNING]
> **AI-generated mod & authorship:** Worldgen Accelerator was originally created by **`agneht26-dotcom`** with **Claude** (`v0.9.78`–`v0.11.5`), later expanded by **Arona** up through **`0.63.3` / `0.63.4`**, and is now continued by **`agneht26-dotcom`** from **`0.63.4-lab.1`** through **`0.64.0`** and the current **`0.64.1`** using **Claude and Gemini**.
>
> **What `0.64.1` is and does:** An OpenCL-default **Minecraft 1.21.1 (NeoForge)** build for **FreeTerraForged** (`1.0.0010`–`1.0.0032`) with **C2ME** that accelerates biome selection, 3D noise fill, surface rules, ores/disks/carvers, and Chunky pregeneration on GPU and CPU while staying block-for-block identical via live self-checks (adding OpenCL surface optimizations, Still Life/Terralith/Nature's Spirit/tall-world speedups, reloaded look-ahead cell alignment, and `[version_support]` checks). Always **back up your worlds** before testing, try it on a copy first, and include `logs/latest.log` with any bug reports.

> [!NOTE]
> **OpenCL is the default GPU backend (`gpuBackend = OPENCL`) and is faster than CUDA.** OpenCL received the latest performance optimizations and works out of the box through your NVIDIA, AMD, or Intel GPU driver with no extra SDK required. CUDA and HIP remain selectable manually in Config. When you join a world, chat logs the active GPU and backend (`gpuTestedWarning = false` hides this).

> [!IMPORTANT]
> **Do not use alongside C2ME's OpenCL module (`c2me-opts-accel-opencl`).** Both move worldgen to the GPU in conflicting ways. If both are installed, Worldgen Accelerator disables itself automatically. Keep only the main C2ME jar.

## Contents

- [What it does](#what-it-does)
- [What changed from Arona's upstream](#what-changed-from-aronas-upstream)
- [What runs on the CPU and what on the GPU](#what-runs-on-the-cpu-and-what-on-the-gpu)
- [Benchmarks (`0.64.1` OpenCL)](#benchmarks-0641-opencl)
- [Supported versions and mods](#supported-versions-and-mods)
- [Dimensions](#dimensions)
- [Installation](#installation)
- [Maxing pregeneration CPS](#maxing-pregeneration-cps)
- [Config](#config)
- [Building from source](#building-from-source)
- [Licence](#licence)

## What it does

Generating a chunk runs through several stages: biomes, 3D noise fill (stone, aquifers, ore veins), surface rules (grass, sand, snow, rock strata), carvers, and features (ores, disks, trees, sculk, structures). FreeTerraForged adds heightmaps, erosion, rivers, and cliffs on top. Worldgen Accelerator speeds this pipeline up in two ways:

1. **On the CPU:** Removes repeated work and allocations in Minecraft, C2ME, FreeTerraForged, and biome mods — such as skipping air above terrain (`fillToGround`), hoisting column climate/terrain reads (`fastClimateColumns`, `fasterBiomeChoice`), compiling `BlockPredicate` rules/disk loops (`DiskColumns`, `FastPredicates.RulePlan`), region chunk/section caches (`RegionSectionCache`, `FastOreDoPlace`, `SculkSpreaderCursorMixin`), fast carvers, pooled region deflate streams, and automatic Chunky in-flight tuning.
2. **On the GPU (OpenCL default):** Translates heavy chunk and tile math (noise fill, aquifers, ore veins, Beardifier structure terrain, surface rule trees, erosion/cliff filters, and tile noise) into GPU programs generated dynamically from your world's datapacks and biome mods, caching compiled binaries in `wgaccel-gpu-cache` so subsequent world loads start immediately. While C2ME workers handle CPU stages, GPU **look-ahead** precomputes upcoming chunks asynchronously ahead of the noise step.

**Live parity checks:** Every 64th chunk (and sampled biome/ore/disk checks) is computed on the CPU too and verified against the GPU/fast path. Any mismatch disables only that optimization automatically and logs details. *(The GPU surface check logs race warnings only, since cross-chunk feature writes in mods like Still Life can race even without WGAccel.)*

## What changed from Arona's upstream

Forked from Arona's `0.63.3` (incorporating `0.63.4` fixes) with the following scope and technical changes:

- **Minecraft `1.21.1` NeoForge only (no newer MC versions or Fabric):** Releases and testing target **Minecraft 1.21.1 NeoForge** exclusively. Newer Minecraft versions (`26.1.2+`), Fabric builds, and Conquest ReTerraForged are **not** supported or maintained here.
- **OpenCL is now the default GPU backend (`gpuBackend = OPENCL`):** OpenCL received the latest GPU kernel optimizations (including workgroup shared-memory surface prepass scans) and is faster than CUDA on this fork; CUDA and HIP remain manual fallbacks without the newer OpenCL-specific speedups.
- **FreeTerraForged `1.0.0025`–`1.0.0032` parity & fixes:** Version-gated erosion `weirdness` formulas (`v25`–`v27`), river noise fade skips (`FtfRiverSkip`), `experimentalMountainFades` support, `NoiseBasedChunkGeneratorMixin` (`priority = 500`) alignment inner to FTF, and `FtfCellsGround.clampWrapCells` look-ahead cell-range alignment for reloaded chunks and `afterBiomes` structure chunks (`other cell range -> 0`).
- **Biome-mod, feature & tall-terrain optimizations (`0.64.x`):**
  - **Still Life:** `ClimateColumns` + `StillLifeClimateBridge.apply` caching, whole-disk `DiskColumns` batching, and compiled `FastPredicates.RulePlan` indexed dispatch (`182.30 -> 370.61 CPS`).
  - **Terralith, Nature's Spirit & tall worlds:** `UndergroundBiomeBanding` `lastResult` tie parity, C2ME `CompiledDensityFunction`-wrapped `ClimateDepthFunction` column caching, `NaturesSpiritPillars`, `SculkSpreaderCursorMixin`, `BiomeRoles` column resets, `SectionBuilder` single-pass packing, and `GpuSurface` changed-section skipping.
- **Diagnostics & version guardrails:** Added `[version_support]` warnings for untested worldgen mod versions or standalone Lithosphere, moved WGAccel logs under `logs/wgaccel/`, fixed `GpuNoiseFill` `preparedUsed` stat counting, and fixed Windows NVRTC library loading when CUDA is selected.

## What runs on the CPU and what on the GPU

| Stage | GPU (OpenCL default; CUDA/HIP optional) | CPU |
| --- | --- | --- |
| **FTF tiles / cells** | Tile noise & erosion/cliff filters (`1.0.0010` tiled builds) | Larger tile/region caches (`tileCacheTiles`, `FtfRegionsAhead`), river noise fade skips (`FtfRiverSkip`) |
| **Biomes** | Biome climate & decoration kernels (`gpuBiomes` off by default on tileless FTF where CPU is faster) | Flattened R-tree search (`ClimateSearch`), column climate + Still Life bridge cache (`ClimateColumns`), reused column terrain/roles/underground banding (`BiomeFill`) |
| **3D noise fill** | Final density, aquifers, ore veins, structure Beardifier (`GpuNoiseFill` + aligned `clampWrapCells` look-ahead) | Cut fill height above ground (`FillToGround`), indexed interpolator loops (`FastNoiseInterpolator`) |
| **Surface** | Batched OpenCL surface rule trees & prepass column warps (`GpuSurface` + `SurfaceCodegen`) | Direct section block/biome reads (`RegionSectionCache`), Nature's Spirit pillar fast-path (`NaturesSpiritPillars`) |
| **Features & Carvers** | — | Hoisted `OreFeature` spans (`FastOreDoPlace`), whole-box `DiskFeature` batching (`DiskColumns`), compiled predicate/placement streams (`FastPredicates`, `PlacementStreams`), sculk & dripstone section scans, carver band skip (`WorldCarverMixin`) |
| **Structures & Saving** | — | Cached jigsaw connections (`fastJigsaw`), pooled `Deflater` region compression (`PooledDeflateStream`), automatic `chunkyMaxWorkingCount` |

## Benchmarks (`0.64.1` OpenCL)

Measured on **Minecraft 1.21.1 NeoForge**, **FreeTerraForged `1.0.0032`**, **C2ME `0.4.0-alpha.0.122`** (`19` worker threads), `16 GB` Generational ZGC, OpenCL default, Chunky `radius = 1000` (`16,129` chunks) fresh worlds unless noted:

| Setup | Time | Chunks/s (CPS) | Notes |
| --- | --- | --- | --- |
| **FreeTerraForged Core** | **0:26.4** | **611.43** | `0` `other cell range`, `740/808` biome-step structure chunks used, `176/176` checked `IDENTICAL` |
| **+ Biomes O' Plenty** | 0:34.9 | **462.57** | `176/176` `IDENTICAL`, `0` diffs |
| **+ Regions Unexplored** | 0:34.9 | **462.53** | `176/176` `IDENTICAL`, `0` biome diffs across `1.18M` checks |
| **+ Terralith** | 0:40.7 | **396.41** | `176/176` `IDENTICAL`, `0` diffs across `1.41M` biome checks |
| **+ Still Life (`0.3-ftf` + bridge `3.3.0`)** | 0:43.5 | **370.61** | `+103.3%` (`2.03x`) over `0.63.3` OpenCL (`182.30 CPS`); `6.31M` disk checks `IDENTICAL` |
| **4-in-1 (`WWOO + Nature's Spirit + BOP + RU`)**, `r=1000` / **`r=4000`** (`251,001` chunks) | 0:52.5 / 10:38.7 | **307.53** / **393.00** | `r=4000` peaks ~`450 CPS`; `0` diffs across `16.99M` biome checks |
| **Tall Mountains (`worldHeight = 2000`)**, `r=2000` (`63,001` chunks) | 2:38.7 | **397.00** | OpenCL (`+19.9 CPS` faster than prior CUDA `377.13 CPS`) |

## Supported versions and mods

WGAccel includes `[version_support]` checks (`warnUnsupportedVersions = true` by default) that warn in console and chat if an untested terrain/biome mod version is loaded.

| Mod | Verified Version(s) | Notes |
| --- | --- | --- |
| **Minecraft / NeoForge** | `1.21.1` (`21.1.x`) | `wgaccel-neoforge-<version>+mc1.21.1.jar` |
| **FreeTerraForged** | `1.0.0010` – `1.0.0032` | Full support including tileless chunk cells and `experimentalMountainFades` (`1.0.0027+`) |
| **C2ME** | `0.4.0-alpha.0.122` – `0.124` | Recommended. Do **not** install `c2me-opts-accel-opencl` |
| **Lithium / ScalableLux / FerriteCore** | `0.15.4` / `0.3.0-alpha.0.8` / `7.0.3` | Strongly recommended (`ScalableLux` prevents heavy chunk lighting stalls on servers) |
| **Chunky** | `1.4.23` | Supported; WGAccel tunes `chunky.maxWorkingCount` automatically when `0` |
| **Still Life + Bridge** | `0.3-ftf` + `stilllife_ftf_bridge 3.3.0` (`0.3`–`0.3.5`) | Exact parity (`ClimateColumns` bridge + `DiskColumns` + compiled `RulePlan`). Works without Lithosphere |
| **Terralith** | `2.6.2` | Exact parity (`NoiseFill` auto-adjusts for `ClimateDepthFunction`, GPU surface & fast biome search active) |
| **Biomes O' Plenty + TerraBlender** | `21.1.0.14` / `4.1.0.8` | Exact parity |
| **Regions Unexplored** | `0.6.2` | Exact parity |
| **WWOO (William Wythers')** | `2.6.7` | Exact parity |
| **Nature's Spirit + Lithostitched** | `2.3.1`–`2.3.2` / `1.8.0`–`1.8.1` | Exact parity (`NaturesSpiritPillars` accelerates pillar biomes) |
| **No Man's Land + Biolith** | `1.6.2` / `3.0.14` | Exact parity |
| **RoadWeaver** | `2.3.2` | Exact parity (road cut-and-fill chunks fall back cleanly to CPU; shaded LWJGL avoids clashes) |
| **VanillaBackport / Streams Reflowing / Worldgen Patches** | `1.2.1`–`1.2.2` / `2.15.2` / `1.1.0` | Compatible |
| **Shaders / Client Performance (Iris, Sodium, Voxy, DH)** | Works in gameplay | **Disable shaders, Voxy, and Distant Horizons while pregenerating** for max CPS |

## Dimensions

| Dimension | CPU improvements | GPU improvements |
| --- | --- | --- |
| **Overworld** | All active | All active |
| **Nether / The End** | Ores, carvers, surface reads, compression | Noise fill off by default (C2ME CPU is faster in Nether/End; configurable via `gpuNoiseFillCpuDimensions`) |
| **Modded dimensions** | General CPU passes | Controlled via `gpuNoiseFillOtherDimensions` (auto-falls back to CPU if unsupported) |

## Installation

### Client
1. Install **NeoForge 1.21.1** along with **FreeTerraForged**, **C2ME**, **Lithium**, **ScalableLux**, and **FerriteCore**.
2. Drop `wgaccel-neoforge-<version>+mc1.21.1.jar` into `mods/`.
3. Keep your GPU driver up to date (NVIDIA, AMD Adrenalin, or Intel). OpenCL works with **no extra SDK**.
4. Join a world and check `logs/latest.log` for `WGA GPU backend: OpenCL`, `WGA GPU self-test: BIT-IDENTICAL`, and `WGA GPU noise fill: ready`.

### Dedicated server
Dedicated servers do **not** include LWJGL core (`org.lwjgl.system`), which **all GPU backends (including the OpenCL default, CUDA, and HIP)** require. For OpenCL on a server you only need an up-to-date GPU driver plus the two `lwjgl-3.3.3` jars below — **no CUDA Toolkit or HIP SDK is needed** unless you explicitly switch away from OpenCL. *(Without LWJGL, the server still starts cleanly and runs all CPU optimizations.)*
1. Put WGAccel (and FreeTerraForged, C2ME, Lithium, ScalableLux, FerriteCore) in the server's `mods/` folder.
2. Copy `lwjgl-3.3.3.jar` and `lwjgl-3.3.3-natives-windows.jar` (or `natives-linux`) from a client's `libraries/org/lwjgl/lwjgl/3.3.3/` into the server's `libraries/org/lwjgl/lwjgl/3.3.3/`.
3. Append both jars (separated by `;` on Windows or `:` on Linux) to `-DlegacyClassPath=` in `libraries/net/neoforged/neoforge/<version>/win_args.txt` (or `unix_args.txt`).
4. Check `logs/latest.log` for `WGA GPU backend: OpenCL` and `WGA GPU self-test: BIT-IDENTICAL`. Players joining the server do **not** need WGAccel installed.

<details>
<summary>Optional backends: CUDA, HIP, Linux ROCm & weak/integrated GPUs</summary>

- **OpenCL is faster than CUDA** on this branch and needs no SDK. To override manually, use the in-game Settings screen or `-Dwgaccel.gpuBackend=opencl|cuda|hip`:
  - **CUDA (NVIDIA):** Requires CUDA Toolkit `12.x` (`12.8+` for RTX 50xx) or `13.x` (`nvrtc64_*.dll`). Override via `-Dwgaccel.nvrtc=<path>`.
  - **HIP (AMD Windows / Linux ROCm):** Requires HIP SDK `6.4+` on Windows (`-Dwgaccel.hiprtc=<path>`) or ROCm (`hip-runtime-amd` + `rocm-opencl-runtime`, user in `render,video` groups; older integrated RDNA2 like 680M/660M may need `HSA_OVERRIDE_GFX_VERSION=10.3.0`).
- **GPU selection & weak GPUs:** Multi-GPU machines pick discrete GPUs automatically. Integrated Intel GPUs and GPUs under 8 compute units are skipped by default because CPUs are faster on them. Mesa `rusticl` is also skipped automatically (`RUSTICL_ENABLE=radeonsi` + `RUSTICL_FEATURES=fp64` only if forced).
- **In-game UI:** Open Settings via the WGAccel button next to FOV in Options (or NeoForge Mods → Config) to inspect System Info, toggle passes, or switch GPUs.

</details>

## Maxing pregeneration CPS

Pregeneration speed depends on keeping **CPU worker threads, OpenCL GPU queues, lighting, and disk I/O** completely uncontested:

### 1. Client rendering & camera tricks (biggest client boost)
- **Cap FPS to `60`** (or lower, e.g. `30–60`) in Video Settings so frame rendering doesn't steal GPU time from OpenCL noise/surface kernels.
- **Teleport high up and look straight at empty sky** (e.g. `/tp @s ~ 320 ~`, facing straight up `pitch -90`). Rendering empty sky leaves ~100% of the GPU for worldgen.
- **Turn off Shaders (Iris/Oculus), Voxy, and Distant Horizons** while pregenerating. They consume GPU compute/VRAM and trigger extra chunk reloads/re-fills during Chunky runs; turn them back on once done.
- **Set Render Distance and Simulation Distance to minimum** (`2`–`4` chunks) so client rendering/ticking stops pulling chunks away from Chunky.

### 2. C2ME & companion mods (`config/c2me.toml`)
- **Set `globalExecutorParallelism` to ~75% of your logical CPU threads** (e.g. `12` on 16T, `19` on 24T, `24` on 32T). Leaving `100%` of threads in C2ME starves WGAccel's GPU look-ahead, storage I/O, and OS scheduling; leaving C2ME on a low default under-utilizes the CPU.
- **Set `threadPoolPriorityPreset = "BELOW_NORMAL"`** in `config/c2me.toml` so worker threads stay fast without starving the main server tick/OS scheduler (and on Windows clients, `"BELOW_NORMAL"` avoids the power throttling that `"LOW"` can trigger).
- **Install `ScalableLux`, `Lithium`, and `FerriteCore`** (critical on dedicated servers — missing `ScalableLux` adds `250+ ms` of lighting waits per chunk).
- **Never install `c2me-opts-accel-opencl`** alongside Worldgen Accelerator.

### 3. Memory, JVM flags & Chunky handling

| Use | Recommended Heap (`-Xmx`) |
| --- | --- |
| Normal play | `6–8 GB` |
| Chunky pregeneration | `10–16 GB` (`16 GB` tested) |
| Heavy biome/structure packs or tall worlds (`1000+` height) | `16–24 GB` |

- **Leave `chunkyMaxWorkingCount = 0` (automatic)** in `config/wgaccel-common.toml` and **avoid large overrides like `-Dchunky.maxWorkingCount=2048`**. Too many chunks in flight bloats heap (~220 loaded neighbor chunks per in-flight chunk) and wrecks FreeTerraForged region/tile cache locality (`ground read` waits spike).
- **Use Generational ZGC** (e.g. `-Xmx16G -XX:+UseZGC -XX:+ZGenerational`, or `-XX:+UseCompactObjectHeaders` on Java 25; check start scripts for duplicate `-Xmx` flags) and close unrelated GPU-heavy apps.
- **Run Chunky in one uninterrupted pass** on a fresh area when possible rather than repeatedly stopping and resuming `[Chunky] Task continuing` over half-generated frontier chunks.

## Config

Generated at `config/wgaccel-common.toml`:
- `preset`: `AUTO` (picks `CLIENT` or `SERVER` automatically), `CLIENT`, `SERVER`, or `CUSTOM`.
- `[general]`: `chunkyMaxWorkingCount`, `memoryReport`, `debugInfoLevel` (`NORMAL` / `VERBOSE` / `SILENT`), `overrides`.
- `[version_support]`: `warnUnsupportedVersions` (`true` by default).
- `[cpu]` & `[gpu]`: Master switches (`cpuImprovements`, `gpuImprovements`) plus per-pass toggles (`fillToGround`, `tileCacheTiles`, `fasterBiomeChoice`, `fastClimateSearch`, `fastClimateColumns`, `fastOres`, `fastSurfaceReads`, `fastBlockPredicates`, `gpuBackend`, `gpuNoiseFill`, `gpuLookAhead`, `gpuSurface`, etc.). GPU steps accept `OFF`, `CHECK` (runs GPU + CPU and compares every chunk), or `ON` (uses GPU, checks every 64th chunk).
- `[gpu.tuning]`: Queue limits, batch sizes, threads, and check frequencies (`gpuFillCheckEvery = 64`).

In-game commands: `/wgaccel` (summary, step timings, CPS, GPU check counts) and `/wgaccel profile [seconds]` (60s thread sampler).

## Building from source

Requires **Java 21 JDK** (`1.21.1` NeoForge target). Place compile-time mod dependencies into `libs/`:
- `libs/freeterraforged-*.jar` (verified against `1.0.0032`)
- `libs/nomansland-1.6.2.jar`
- C2ME inner jars from C2ME's `META-INF/jars/` (`base-*-all`, `rewrites-chunk-system-*`, `rxjava-*`, `reactive-streams-*`) in `libs/c2me/`

Build the NeoForge `1.21.1` jar:
```bash
./gradlew -Pmc=1.21.1 :1.21.1:neoforge:build
```
Outputs to `versions/1.21.1/neoforge/build/libs/wgaccel-neoforge-<version>+mc1.21.1.jar`.

## Licence

MIT (see `LICENSE`). Clean-room algorithm parity and bundled third-party notes are documented in [docs/CODE-ORIGINS.md](docs/CODE-ORIGINS.md).
