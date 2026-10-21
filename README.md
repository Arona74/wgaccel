# Worldgen Accelerator

Much faster world generation with FreeTerraForged on NeoForge 1.21.1, the same world block for block. Every GPU result
is checked against the CPU's (a share of them all the time while playing); any difference turns that GPU part off and
the game carries on with the normal code.

> **WARNING: the GPU parts work only on NVIDIA graphics cards (CUDA).** On AMD or Intel cards, or without the CUDA
> parts below, the mod still runs, but only its CPU improvements apply (fillToGround, tileCacheTiles,
> fasterBiomeChoice, fastOres, gpuCarverBiomes, ...); every GPU setting then does nothing.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.x, FreeTerraForged. C2ME is supported (and recommended).
- For the GPU parts: an NVIDIA card and driver, and **NVRTC** from the NVIDIA CUDA Toolkit 12.x. Installing the toolkit
  sets `CUDA_PATH`, where the mod finds `nvrtc64_*.dll`; or start Java with
  `-Dwgaccel.nvrtc=C:/path/to/nvrtc64_120_0.dll`.

## Client installation

Put the jar into the instance's `mods` folder. The game already has LWJGL, so nothing else is needed.

## Dedicated server installation

A dedicated server does not load LWJGL, which the mod uses to reach CUDA, so it needs two extra jars:

1. Put this mod's jar (and FreeTerraForged, C2ME, ...) into the server's `mods` folder.
2. Copy `lwjgl-3.3.3.jar` and `lwjgl-3.3.3-natives-windows.jar` from a client's `libraries/org/lwjgl/lwjgl/3.3.3/`
   to the server's `libraries/org/lwjgl/lwjgl/3.3.3/`.
3. Open `libraries/net/neoforged/neoforge/<version>/win_args.txt` (keep a copy first: reinstalling NeoForge rewrites
   it) and add both jars to the end of the `-DlegacyClassPath=` list, separated by `;`:
   `...;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar;libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar`
   (on Linux: `unix_args.txt`, the `natives-linux` jar and `:` as the separator).
4. Start the server. The log shows `WGA GPU self-test: BIT-IDENTICAL` when the GPU works, or
   `WGA GPU unavailable: LWJGL is not loaded` when steps 2-3 are missing.

## Config (`config/wgaccel-common.toml`)

The first setting, `preset`, picks the values every other setting takes:

| preset | values |
| --- | --- |
| `AUTO` (default) | `CLIENT` in the game, `SERVER` on a dedicated server |
| `CLIENT` | the values found best on a client (the card also draws the game) |
| `SERVER` | the values found best on a dedicated server (the card free) |
| `CUSTOM` | every setting as written in the file |

With `CLIENT` or `SERVER` the values written in the file are kept but not used; switch to `CUSTOM` to use them. The
presets turn the `profileRuns` diagnostic off.

## Building

Put a FreeTerraForged 1.0.0010 jar into `libs/` as `libs/freeterraforged-1.0.0010.jar` (or pass `-PftfJar=<path>`),
then run `gradlew build`; the mod is `build/libs/wgaccel-<version>.jar`. The offline GPU checks (`gradlew
cudaCompileCheck` and the other `cuda*` tasks) need the CUDA Toolkit, and some read files the mod saves into the game's
logs folder: pass `-Plogs=<instance>/logs`.

## Licence

MIT (see `LICENSE`): use, change and share it freely.

## Licences of what the mod builds on

See `docs/CODE-ORIGINS.md`: which parts follow Minecraft's and FreeTerraForged's own algorithms, and what is bundled.
