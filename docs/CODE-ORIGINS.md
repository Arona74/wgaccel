# Where the mod's code comes from (audit of 2026-10-21, 0.9.78)

No source file was copied in from another project: there are no foreign file headers, authors or copyright lines in
`src/`, and every file was written for this mod. But the mod has to give **exactly** the same world, so much of the GPU
code follows other code operation for operation, in CUDA C. A rewrite can change how the code is written, but not what
it computes: any other algorithm changes the world. Bundled: one library.

## 1. Bundled library (shipped inside the jar)

| What | Licence | What the licence asks | State |
| --- | --- | --- | --- |
| LWJGL `lwjgl-cuda` 3.3.3 (classes `org/lwjgl/cuda/**`) | BSD 3-clause | keep its copyright notice and licence with the binary | notice added in 0.9.78: `META-INF/licenses/LWJGL.txt` |

## 2. Code that follows FreeTerraForged (MIT; dags, Won-Ton, racoonman2, psiber, squinch, from TerraForged and ReTerraForged)

MIT lets anyone use, change and ship the code, closed or open, as long as its copyright and licence notice go with
"all copies or substantial portions". Our CUDA versions are translations of its Java, so 0.9.78 ships FTF's MIT notice
(`META-INF/licenses/FreeTerraForged.txt`).

- `gpu/NoiseKernels.java`, `gpu/NoiseCodegen.java`: FTF's noise modules (NoiseUtil, simplex, cellular, ridges,
  warps, curves...) as CUDA.
- `gpu/ErosionKernels.java`, `gpu/ErosionFilterGpu.java` (also a Java copy of the per-cell step for the cells the
  GPU is unsure of), `gpu/CliffRelaxationGpu.java`: FTF's tile erosion filter, the gully ("phacelle") erosion and
  CliffRelaxation.
- `gpu/StrataGpu.java`: StrataRule's layer depths. `gpu/BiomeDecoration.java`, the biome kernels: FTF's biome
  selection and decoration tables. `gpu/TileNoiseGpu.java`: the terrain populators' sums.

## 3. Code that follows Minecraft (Mojang; closed source, the Minecraft EULA)

Every Minecraft mod works with Mojang's code; mods may change the game but may not ship Mojang's own code. We ship no
Mojang class or file. Our GPU code re-expresses some of its algorithms in CUDA so the results are bit-identical:

- `gpu/VanillaKernels.java`: ImprovedNoise, PerlinNoise, NormalNoise. `gpu/SurfaceInputs.java`: SimplexNoise,
  PerlinSimplexNoise, the biome zoom and temperature.
- `gpu/DensityCodegen.java`: the density functions, splines, NoiseBasedAquifer, ore veins, the Beardifier.
- `gpu/SurfaceCodegen.java`, `gpu/SurfaceGpu.java`: the surface rules. `gpu/BiomeGpu.java`: the climate search.
- `gen/GpuSurface.java`: the heightmap rule. `gen/FastOres.java` answers the same questions as OreFeature with fewer
  lookups (no copied code).

This is the same practice as other performance mods (C2ME, Lithium and others re-implement vanilla's algorithms).
The risk is low, but it is the one part that cannot be "rewritten differently": the algorithm must stay the same.

## 4. Code that follows C2ME (MIT; ishland and contributors)

- `gpu/DensityCodegen.java` `fa_c2me`: C2ME's aquifer rewrite (`MixinAquiferSamplerImpl`: refreshDistPosIdx,
  applyPost, getFinalBlockState, its packed candidate order), translated to CUDA so worlds made with C2ME stay
  identical. MIT, the same as FTF: 0.9.78 ships C2ME's MIT notice (`META-INF/licenses/C2ME.txt`).
- The rest only calls into C2ME or reads its classes (no code from it).

## 5. Looked at for ideas only (nothing taken)

- C2ME's OpenCL add-on (All Rights Reserved): read for ideas when planning; none of its code or kernels is used.
- ByePregen (LGPL-3.0): read and tested in 2026-10 (no gain, removed); nothing from it is in the mod.

## Choices for the user

- The mod itself is under the MIT licence (`LICENSE`, since 2026-10-21): anyone may use, change and share it. The MIT
  and BSD notices above stay in the jar.
- Check the copyright lines in `src/main/resources/META-INF/licenses/` against each project's own LICENSE file on
  GitHub before publishing (written from the mods' metadata).
