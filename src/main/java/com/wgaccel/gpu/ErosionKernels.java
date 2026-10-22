package com.wgaccel.gpu;

/**
 * CUDA C for FreeTerraForged's tile erosion filter ({@code densityfunction.tile.filter.Erosion.apply}), one kernel per
 * pass over a tile of n x n cells, written operation for operation like its Java: the modifier, three cliff
 * relaxation passes (each reads the previous pass, as FTF copies the whole array between passes), the zone dilation,
 * the minmod slopes, the per-cell gully ("phacelle") erosion and the land-turned-water fix. FTF's double exp, cos, sin
 * and pow go through java_cast_checked: a cell where one might round differently from Java's is marked unsure, keeps
 * its state from before the per-cell step, and is redone on the CPU ({@link ErosionFilterGpu}).
 * <p>
 * Parameters P[]: 0 scaleBlocks, 1 worldHeight, 2 strength, 3 gullyWeight, 4 detail, 5 lacunarity, 6 gain,
 * 7 phacelleScale, 8 phacelleOffset, 9 phacelleNormalization, 10 roundingMin, 11 roundingMax, 12 slopeOnsetBase,
 * 13 slopeOnsetRidge, 14 slopeOnsetOctave, 15 modifier min, 16 modifier max, 17 modifier range, 18 water, 19 ground,
 * 20 minRelief, 21 invRange.
 */
public final class ErosionKernels {
   /**
    * FreeTerraForged's NoiseUtil.SIN table (4,096 entries, its static initialiser's own Java), for the 1.0.0013 filter's
    * NoiseUtil.sin / cos; made here so the module needs no FreeTerraForged ({@link ErosionFilterGpu} compares it with
    * FreeTerraForged's own table before using it).
    */
   public static final float[] SIN = sinTable();
   public static final float RAD_TO_INDEX = 4096 / 6.2831855f;

   public static final String SOURCE = NoiseKernels.NOISE_UTIL + NoiseKernels.JAVA_MATH + sinSource() + """
      #define TAU 6.2831855f
      __device__ __forceinline__ float ero_modify(const float* P, float height, float em, float edge, float river) {
          float strengthModifier = 1.0f;
          if (em != 1.0f) {
              float alpha = ftf_map(edge, 0.0f, 0.15f, 0.15f);
              strengthModifier = ftf_lerp(1.0f, em, alpha);
          }
          if (river < 0.1f) strengthModifier *= ftf_map(river, 0.002f, 0.1f, 0.098f);
          float valueModifier = height > P[16] ? 1.0f : (height < P[15] ? 0.0f : (height - P[15]) / P[17]);
          return valueModifier * strengthModifier * 1.0f;
      }
      extern "C" __global__ void eroMods(int count, const float* h, const int* mask, const float* emod, const float* edge, const float* river,
                                         const float* P, float* mods, float* zone) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count) return;
          float c = h[i];
          mods[i] = mask[i] == 0 && isfinite(c) ? ero_modify(P, c, emod[i], edge[i], river[i]) : 0.0f;
          zone[i] = 0.0f;
      }
      extern "C" __global__ void eroRelax(int n, const float* h, const float* mods, const float* P, float* zone, float* next) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int zPrevRow = max(0, z - 1) * n;
          int zNextRow = min(n - 1, z + 1) * n;
          float c = h[idx];
          float m = mods[idx];
          if (m == 0.0f) {
              next[idx] = c;
              return;
          }
          float l = h[row + max(0, x - 1)];
          float r = h[row + min(n - 1, x + 1)];
          float d = h[zPrevRow + x];
          float u = h[zNextRow + x];
          float avg = 0.25f * (l + r + d + u);
          float hi = java_maxf(java_maxf(c, l), java_maxf(java_maxf(r, d), u));
          float lo = java_minf(java_minf(c, l), java_minf(java_minf(r, d), u));
          float t = ftf_clamp((hi - lo - P[20]) * P[21], 0.0f, 1.0f);
          float mask = t * t * (3.0f - 2.0f * t);
          if (mask > zone[idx]) zone[idx] = mask;
          float delta = (avg - c) * mask * 0.4f;
          next[idx] = c + m * delta;
      }
      extern "C" __global__ void eroRelaxApply(int count, const float* h, const float* mods, float* cellHeight, float* heightErosion) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count) return;
          float v = h[i];
          if (mods[i] != 0.0f && isfinite(v)) {
              heightErosion[i] = heightErosion[i] + (v - cellHeight[i]);
              cellHeight[i] = v;
          }
      }
      extern "C" __global__ void eroDilateRows(int n, const float* zone, float* tmp) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int last = n - 1;
          float best = 0.0f;
          for (int k = -3; k <= 3; k++) {
              int xx = min(last, max(0, x + k));
              float v = zone[row + xx] * (1.0f - (float)abs(k) * 0.25f);
              if (v > best) best = v;
          }
          tmp[idx] = best;
      }
      extern "C" __global__ void eroDilateCols(int n, const float* tmp, float* zone) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int last = n - 1;
          float best = 0.0f;
          for (int k = -3; k <= 3; k++) {
              int zz = min(last, max(0, z + k));
              float v = tmp[zz * n + x] * (1.0f - (float)abs(k) * 0.25f);
              if (v > best) best = v;
          }
          zone[idx] = best;
      }
      __device__ __forceinline__ float ero_minmod(float back, float fwd) {
          if (back * fwd <= 0.0f) return 0.0f;
          return fabsf(back) < fabsf(fwd) ? back : fwd;
      }
      extern "C" __global__ void eroSlopes(int n, const float* h, const float* P, float* slopeX, float* slopeZ) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int last = n - 1;
          float wh = P[1];
          float c = h[idx];
          if (x == 0) slopeX[idx] = (h[row + 1] - h[row]) * wh;
          else if (x == last) slopeX[idx] = (h[row + last] - h[row + last - 1]) * wh;
          else slopeX[idx] = ero_minmod(c - h[idx - 1], h[idx + 1] - c) * wh;
          if (z == 0) slopeZ[idx] = (h[row + n + x] - h[row + x]) * wh;
          else if (z == last) slopeZ[idx] = (h[row + x] - h[row - n + x]) * wh;
          else slopeZ[idx] = ero_minmod(c - h[row - n + x], h[row + n + x] - c) * wh;
      }
      __device__ __forceinline__ float ero_hash(int x, int z, int seed) {
          int n = (int)((unsigned int)x * 374761393u + (unsigned int)z * 668265263u + (unsigned int)seed * 144677219u);
          n = (int)((unsigned int)(n ^ (n >> 13)) * 1274126177u);
          return (float)((n ^ (n >> 16)) & 2147483647) / 2.1474836E9f - 0.5f;
      }
      __device__ __forceinline__ float ero_ease_out(float t) {
          float v = 1.0f - ftf_clamp(t, 0.0f, 1.0f);
          return 1.0f - v * v;
      }
      __device__ __forceinline__ float ero_pow_inv(float t, float power, int& u) {
          return 1.0f - ftf_pow(1.0f - ftf_clamp(t, 0.0f, 1.0f), power, u);
      }
      __device__ __forceinline__ float ero_smooth_start(float t, float smoothing) {
          return t >= smoothing ? t - 0.5f * smoothing : 0.5f * t * t / java_maxf(smoothing, 1.0E-5f);
      }
      // tableTrig (FreeTerraForged 1.0.0013): a cell point farther than 1.5 is skipped (its weight is never above 0) and
      // NoiseUtil's table sin / cos replace the double cos / sin; otherwise the 21 Oct filter's own.
      __device__ void ero_phacelle(float px, float pz, float normDirX, float normDirZ, float freq, float offset, float normalization, int seed,
                                   float* result, int& u, int tableTrig) {
          float sideDirX = -normDirZ * freq * TAU;
          float sideDirZ = normDirX * freq * TAU;
          offset *= TAU;
          int pIntX = __double2int_rz(floor((double)px));
          int pIntZ = __double2int_rz(floor((double)pz));
          float pFracX = px - (float)pIntX;
          float pFracZ = pz - (float)pIntZ;
          float phaseDirX = 0.0f;
          float phaseDirZ = 0.0f;
          float weightSum = 0.0f;
          for (int i = -1; i <= 2; i++) {
              for (int j = -1; j <= 2; j++) {
                  int cellX = pIntX + i;
                  int cellZ = pIntZ + j;
                  float hashX = ero_hash(cellX, cellZ, seed) * 0.5f;
                  float hashZ = ero_hash(cellX, cellZ, ftf_add(seed, 1013)) * 0.5f;
                  float vecX = pFracX - (float)i - hashX;
                  float vecZ = pFracZ - (float)j - hashZ;
                  float sqrDist = vecX * vecX + vecZ * vecZ;
                  if (tableTrig) {
                      if (sqrDist >= 2.25f) continue;
                      float weight = java_cast_checked(exp((double)(-sqrDist * 2.0f)), u) - 0.01111f;
                      if (weight <= 0.0f) continue;
                      weightSum += weight;
                      float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
                      phaseDirX += ero_sin(waveInput + 1.5708f) * weight;
                      phaseDirZ += ero_sin(waveInput) * weight;
                      continue;
                  }
                  float weight = java_maxf(0.0f, java_cast_checked(exp((double)(-sqrDist * 2.0f)), u) - 0.01111f);
                  weightSum += weight;
                  float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
                  phaseDirX += java_cast_checked(cos((double)waveInput), u) * weight;
                  phaseDirZ += java_cast_checked(sin((double)waveInput), u) * weight;
              }
          }
          float invWeight = 1.0f / java_maxf(weightSum, 1.0E-5f);
          float interpX = phaseDirX * invWeight;
          float interpZ = phaseDirZ * invWeight;
          float magnitude = (float)sqrt((double)(interpX * interpX + interpZ * interpZ));
          magnitude = java_maxf(1.0f - normalization, magnitude);
          result[0] = interpX / magnitude;
          result[1] = interpZ / magnitude;
          result[2] = sideDirX / (freq * TAU + 1.0E-5f);
          result[3] = sideDirZ / (freq * TAU + 1.0E-5f);
      }
      // The per-cell step: reads the state after the cliff relaxation (cellHeight, heightErosion, sediment) and writes
      // the result to the out arrays; an unsure cell gets its state unchanged there and unsure = 1.
      extern "C" __global__ void eroMain(int n, int seed, int octaves, int worldBlockX, int worldBlockZ, const float* P, const float* h,
                                         const float* slopeX, const float* slopeZ, const float* cliffZone, const int* emask, const float* emod,
                                         const float* edge, const float* river, const float* cellHeight, const float* heightErosion,
                                         const float* sediment, float* outHeight, float* outHeightErosion, float* outSediment, int* unsure) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int last = n - 1;
          float height = cellHeight[idx];
          float erosionSum = heightErosion[idx];
          float sed = sediment[idx];
          int u = 0;
          float baseHeight = h[idx];
          if (emask[idx] == 0 && isfinite(baseHeight)) {
              float curSlopeX = slopeX[idx];
              float curSlopeZ = slopeZ[idx];
              float slopeLen = (float)sqrt((double)(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1.0E-10f));
              float slopeT = ftf_clamp((slopeLen - 0.15f) / 0.45000002f, 0.0f, 1.0f);
              float flatGate = slopeT * slopeT * (3.0f - 2.0f * slopeT);
              float erosionFactor = flatGate <= 0.0f ? 0.0f : ero_modify(P, height, emod[idx], edge[idx], river[idx]);
              if (!(flatGate <= 0.0f) && erosionFactor != 0.0f) {
                  float worldX = (float)(worldBlockX + x);
                  float worldZ = (float)(worldBlockZ + z);
                  float zone = cliffZone[idx];
                  float heightFade = ftf_clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);
                  float currentStrength = P[2] * P[0];
                  float freq = 1.0f / (P[0] * P[7]);
                  float roundingForInput = ftf_lerp(P[10], P[11], ftf_clamp(heightFade + 0.5f, 0.0f, 1.0f));
                  float combiMask = ero_ease_out(ero_smooth_start(slopeLen * P[12], roundingForInput * P[12]));
                  float ridgeMapCombiMask = ero_ease_out(slopeLen * 0.01f * P[13]);
                  float ridgeMapFadeTarget = heightFade;
                  float gullySlopeX = curSlopeX;
                  float gullySlopeZ = curSlopeZ;
                  float accumulatedHeightDelta = 0.0f;
                  float roundingMult = 1.0f;
                  float fadeTarget = 0.0f;
                  float out[4];
                  for (int octave = 0; octave < octaves; octave++) {
                      float gullyLen = (float)sqrt((double)(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ));
                      float normGullyX = gullyLen > 1.0E-10f ? gullySlopeX / gullyLen : 0.0f;
                      float normGullyZ = gullyLen > 1.0E-10f ? gullySlopeZ / gullyLen : 0.0f;
                      ero_phacelle(worldX * freq, worldZ * freq, normGullyX, normGullyZ, P[7], P[8], P[9], ftf_add(seed, octave), out, u, 0);
                      float phacelleX = out[0];
                      float phacelleY = out[1];
                      float phacelleZ = out[2];
                      float phacelleW = out[3];
                      float sloping = fabsf(phacelleY);
                      float gradient = currentStrength * TAU * P[7] * freq;
                      float gullySign = java_signum(phacelleY);
                      gullySlopeX += gullySign * phacelleZ * gradient * P[3];
                      gullySlopeZ += gullySign * phacelleW * gradient * P[3];
                      float noiseTerm = phacelleX * P[3];
                      float fadedGullyVal = ftf_lerp(fadeTarget, noiseTerm, combiMask);
                      accumulatedHeightDelta += fadedGullyVal * currentStrength;
                      fadeTarget = fadedGullyVal;
                      float roundingOctave = ftf_lerp(P[10], P[11], ftf_clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                      float newMask = ero_ease_out(ero_smooth_start(sloping * P[12], roundingOctave * P[12]));
                      combiMask = ero_pow_inv(combiMask, P[4], u) * newMask;
                      ridgeMapFadeTarget = ftf_lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                      ridgeMapCombiMask *= ero_ease_out(sloping * P[14]);
                      currentStrength *= P[6];
                      freq *= P[5];
                      roundingMult *= 0.001f;
                  }
                  if (isfinite(accumulatedHeightDelta)) {
                      float sedimentUpdate = ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask) * (1.0f - zone);
                      sed = ftf_clamp(sed + sedimentUpdate, -1.0f, 1.0f);
                      float sedimentModifier = ftf_lerp(java_maxf(0.2f, 1.0f - sed), 1.0f, zone);
                      float capSlope = java_minf(slopeLen, 3.0f);
                      float maxDepth = 0.5f * capSlope * P[0];
                      float localLo = baseHeight;
                      float localHi = baseHeight;
                      if (zone > 0.0f) {
                          int zMin = max(0, z - 2);
                          int zMax = min(last, z + 2);
                          int xMin = max(0, x - 2);
                          int xMax = min(last, x + 2);
                          for (int wz = zMin; wz <= zMax; wz++) {
                              int wRow = wz * n;
                              for (int wx = xMin; wx <= xMax; wx++) {
                                  float v = h[wRow + wx];
                                  if (v < localLo) localLo = v;
                                  if (v > localHi) localHi = v;
                              }
                          }
                          float reliefBlocks = (localHi - localLo) * P[1];
                          float reliefDepth = java_minf(maxDepth, 0.35f * reliefBlocks);
                          maxDepth = ftf_lerp(maxDepth, reliefDepth, zone);
                      }
                      float gatedBlocks = ftf_clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
                      float deltaNormalized = gatedBlocks / P[1];
                      float change = erosionFactor * deltaNormalized;
                      if (zone > 0.0f) {
                          float bounded = ftf_clamp(baseHeight + change, localLo, localHi) - baseHeight;
                          change = ftf_lerp(change, bounded, zone);
                      }
                      erosionSum += change;
                      height += change;
                  }
              }
          }
          if (u) {
              outHeight[idx] = cellHeight[idx];
              outHeightErosion[idx] = heightErosion[idx];
              outSediment[idx] = sediment[idx];
          } else {
              outHeight[idx] = height;
              outHeightErosion[idx] = erosionSum;
              outSediment[idx] = sed;
          }
          unsure[idx] = u;
      }
      // ---- S6: FreeTerraForged's erosion filter of 21 Oct ("v2": no cliff relaxation inside; CliffRelaxation is a filter
      // of its own before it). Stencils as FTF's loops (the same order of float sums), then the per-cell step. ----
      extern "C" __global__ void ev2Envelope(int count, const float* h, float* env) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count) return;
          float v = h[i];
          env[i] = isfinite(v) ? v : __int_as_float(0xff800000);
      }
      // Separable square-window max or min (radius r, clamped at the tile edges): rows buf -> tmp, then columns tmp -> buf.
      extern "C" __global__ void ev2WindowRows(int n, int r, int isMax, const float* buf, float* tmp) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int lo = max(0, x - r);
          int hi = min(n - 1, x + r);
          float m = buf[row + lo];
          for (int k = lo + 1; k <= hi; k++) {
              float v = buf[row + k];
              if (isMax ? v > m : v < m) m = v;
          }
          tmp[idx] = m;
      }
      extern "C" __global__ void ev2WindowCols(int n, int r, int isMax, const float* tmp, float* buf) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int lo = max(0, z - r);
          int hi = min(n - 1, z + r);
          float m = tmp[lo * n + x];
          for (int k = lo + 1; k <= hi; k++) {
              float v = tmp[k * n + x];
              if (isMax ? v > m : v < m) m = v;
          }
          buf[idx] = m;
      }
      // Box blur: rows sum / count, then columns sum * (1 / count), as FTF writes them.
      extern "C" __global__ void ev2BlurRows(int n, int r, const float* buf, float* tmp) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int lo = max(0, x - r);
          int hi = min(n - 1, x + r);
          float sum = 0.0f;
          for (int k = lo; k <= hi; k++) sum += buf[row + k];
          tmp[idx] = sum / (float)(hi - lo + 1);
      }
      extern "C" __global__ void ev2BlurCols(int n, int r, const float* tmp, float* buf) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int lo = max(0, z - r);
          int hi = min(n - 1, z + r);
          float inv = 1.0f / (float)(hi - lo + 1);
          float sum = 0.0f;
          for (int k = lo; k <= hi; k++) sum += tmp[k * n + x];
          buf[idx] = sum * inv;
      }
      // The gate's slope length; non-finite -> 0.
      extern "C" __global__ void ev2Gate(int count, const float* slopeX, const float* slopeZ, float* gate) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count) return;
          float sx = slopeX[i];
          float sz = slopeZ[i];
          float len = (float)sqrt((double)(sx * sx + sz * sz + 1.0E-10f));
          gate[i] = isfinite(len) ? len : 0.0f;
      }
      // The per-cell step of the 21 Oct filter; an unsure cell gets its state unchanged and unsure = 1. The slope gate's
      // start and range, the depth cap per slope, the envelope margin and the trig are the filter's own (Variant).
      __device__ __forceinline__ void ev2_cell(int idx, int x, int z, int seed, int octaves, int worldBlockX, int worldBlockZ, const float* P, const float* h, const float* env,
                                         const float* slopeX, const float* slopeZ, const float* gate, const int* emask, const float* emod, const float* edge,
                                         const float* river, const float* heightErosion, const float* sediment, float* outHeight, float* outHeightErosion,
                                         float* outSediment, int* unsure, float slopeStart, float range, float depthPerSlope, float margin,
                                         int tableTrig) {
          float baseHeight = h[idx];
          float height = baseHeight;
          float erosionSum = heightErosion[idx];
          float sed = sediment[idx];
          int u = 0;
          if (emask[idx] == 0 && isfinite(baseHeight)) {
              float curSlopeX = slopeX[idx];
              float curSlopeZ = slopeZ[idx];
              float slopeLen = (float)sqrt((double)(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1.0E-10f));
              float heightFade = ftf_clamp((baseHeight - 0.5f) * 2.0f, -1.0f, 1.0f);
              float ridgeMapCombiMask = java_minf(0.98f, ero_ease_out(slopeLen * 0.01f * P[13]));
              float rawT = ftf_clamp((slopeLen - slopeStart) / range, 0.0f, 1.0f);
              if (rawT <= 0.0f) {
                  sed = ftf_clamp(sed + heightFade * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
              } else {
                  float smoothSlope = gate[idx];
                  float ampT = ftf_clamp((smoothSlope - slopeStart) / range, 0.0f, 1.0f);
                  float flatGate = ampT * ampT * (3.0f - 2.0f * ampT);
                  float erosionFactor = ero_modify(P, height, emod[idx], edge[idx], river[idx]);
                  if (erosionFactor != 0.0f) {
                      float worldX = (float)(worldBlockX + x);
                      float worldZ = (float)(worldBlockZ + z);
                      float currentStrength = P[2] * P[0];
                      float freq = 1.0f / (P[0] * P[7]);
                      float roundingForInput = ftf_lerp(P[10], P[11], ftf_clamp(heightFade + 0.5f, 0.0f, 1.0f));
                      float combiMask = ero_ease_out(ero_smooth_start(slopeLen * P[12], roundingForInput * P[12]));
                      float ridgeMapFadeTarget = heightFade;
                      float gullySlopeX = curSlopeX;
                      float gullySlopeZ = curSlopeZ;
                      float accumulatedHeightDelta = 0.0f;
                      float roundingMult = 1.0f;
                      float fadeTarget = 0.0f;
                      float out[4];
                      for (int octave = 0; octave < octaves; octave++) {
                          float gullyLen = (float)sqrt((double)(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ));
                          float normGullyX = gullyLen > 1.0E-10f ? gullySlopeX / gullyLen : 0.0f;
                          float normGullyZ = gullyLen > 1.0E-10f ? gullySlopeZ / gullyLen : 0.0f;
                          ero_phacelle(worldX * freq, worldZ * freq, normGullyX, normGullyZ, P[7], P[8], P[9], ftf_add(seed, octave), out, u, tableTrig);
                          float phacelleX = out[0];
                          float phacelleY = out[1];
                          float phacelleZ = out[2];
                          float phacelleW = out[3];
                          float sloping = fabsf(phacelleY);
                          float gradient = currentStrength * TAU * P[7] * freq;
                          float gullySign = java_signum(phacelleY);
                          gullySlopeX += gullySign * phacelleZ * gradient * P[3];
                          gullySlopeZ += gullySign * phacelleW * gradient * P[3];
                          float noiseTerm = phacelleX * P[3];
                          float fadedGullyVal = ftf_lerp(fadeTarget, noiseTerm, combiMask);
                          accumulatedHeightDelta += fadedGullyVal * currentStrength;
                          fadeTarget = fadedGullyVal;
                          float roundingOctave = ftf_lerp(P[10], P[11], ftf_clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                          float newMask = ero_ease_out(ero_smooth_start(sloping * P[12], roundingOctave * P[12]));
                          combiMask = ero_pow_inv(combiMask, P[4], u) * newMask;
                          ridgeMapFadeTarget = ftf_lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                          ridgeMapCombiMask = java_minf(0.98f, ridgeMapCombiMask * ero_ease_out(sloping * P[14]));
                          currentStrength *= P[6];
                          freq *= P[5];
                          roundingMult *= 0.001f;
                      }
                      if (isfinite(accumulatedHeightDelta)) {
                          sed = ftf_clamp(sed + ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
                          if (!(flatGate <= 0.0f)) {
                              float sedimentModifier = java_maxf(0.2f, 1.0f - sed);
                              float capSlope = java_minf(smoothSlope, 3.0f);
                              float maxDepth = depthPerSlope * capSlope * P[0];
                              float limitedBlocks = ftf_clamp(accumulatedHeightDelta * flatGate * sedimentModifier, -maxDepth, maxDepth);
                              if (limitedBlocks > 0.0f) {
                                  float headroom = (env[idx] - baseHeight) * P[1] + margin;
                                  limitedBlocks = limitedBlocks * headroom / (limitedBlocks + headroom);
                              }
                              float deltaNormalized = limitedBlocks / P[1];
                              float change = erosionFactor * deltaNormalized;
                              erosionSum += change;
                              height += change;
                          }
                      }
                  }
              }
          }
          if (u) {
              outHeight[idx] = baseHeight;
              outHeightErosion[idx] = heightErosion[idx];
              outSediment[idx] = sediment[idx];
          } else {
              outHeight[idx] = height;
              outHeightErosion[idx] = erosionSum;
              outSediment[idx] = sed;
          }
          unsure[idx] = u;
      }
      extern "C" __global__ void ev2Main(int n, int seed, int octaves, int worldBlockX, int worldBlockZ, const float* P, const float* h, const float* env,
                                         const float* slopeX, const float* slopeZ, const float* gate, const int* emask, const float* emod, const float* edge,
                                         const float* river, const float* heightErosion, const float* sediment, float* outHeight, float* outHeightErosion,
                                         float* outSediment, int* unsure, float slopeStart, float range, float depthPerSlope, float margin,
                                         int tableTrig) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          ev2_cell(idx, x, z, seed, octaves, worldBlockX, worldBlockZ, P, h, env, slopeX, slopeZ, gate, emask, emod, edge, river, heightErosion, sediment, outHeight, outHeightErosion, outSediment, unsure, slopeStart, range, depthPerSlope, margin, tableTrig);
      }
      // G6c: ev2Main over a batch of grids of n x n cells, one after another in every buffer; grid g's corner block is
      // (blockXs[g], blockZs[g]).
      extern "C" __global__ void evbMain(int grids, int n, int seed, int octaves, const int* blockXs, const int* blockZs, const float* P, const float* h, const float* env,
                                         const float* slopeX, const float* slopeZ, const float* gate, const int* emask, const float* emod, const float* edge,
                                         const float* river, const float* heightErosion, const float* sediment, float* outHeight, float* outHeightErosion,
                                         float* outSediment, int* unsure, float slopeStart, float range, float depthPerSlope, float margin,
                                         int tableTrig) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          int nn = n * n;
          if (idx >= grids * nn) return;
          int g = idx / nn;
          int li = idx - g * nn;
          int z = li / n;
          int x = li - z * n;
          int worldBlockX = blockXs[g];
          int worldBlockZ = blockZs[g];
          ev2_cell(idx, x, z, seed, octaves, worldBlockX, worldBlockZ, P, h, env, slopeX, slopeZ, gate, emask, emod, edge, river, heightErosion, sediment, outHeight, outHeightErosion, outSediment, unsure, slopeStart, range, depthPerSlope, margin, tableTrig);
      }
      // G6c: the 21 Oct filter's grid passes over a batch of grids of n x n cells (grid g at g * n * n in every buffer).
      #define EVB_CELL int idx = blockIdx.x * blockDim.x + threadIdx.x; int nn = n * n; if (idx >= grids * nn) return; \
          int base = idx - idx % nn; int li = idx - base; int z = li / n; int x = li - z * n;
      extern "C" __global__ void evbSlopes(int grids, int n, const float* hAll, const float* P, float* slopeX, float* slopeZ) {
          EVB_CELL
          const float* h = hAll + base;
          int row = z * n;
          int last = n - 1;
          float wh = P[1];
          float c = h[li];
          if (x == 0) slopeX[idx] = (h[row + 1] - h[row]) * wh;
          else if (x == last) slopeX[idx] = (h[row + last] - h[row + last - 1]) * wh;
          else slopeX[idx] = ero_minmod(c - h[li - 1], h[li + 1] - c) * wh;
          if (z == 0) slopeZ[idx] = (h[row + n + x] - h[row + x]) * wh;
          else if (z == last) slopeZ[idx] = (h[row + x] - h[row - n + x]) * wh;
          else slopeZ[idx] = ero_minmod(c - h[row - n + x], h[row + n + x] - c) * wh;
      }
      extern "C" __global__ void evbWindowRows(int grids, int n, int r, int isMax, const float* bufAll, float* tmp) {
          EVB_CELL
          const float* buf = bufAll + base;
          int row = z * n;
          int lo = max(0, x - r);
          int hi = min(n - 1, x + r);
          float m = buf[row + lo];
          for (int k = lo + 1; k <= hi; k++) {
              float v = buf[row + k];
              if (isMax ? v > m : v < m) m = v;
          }
          tmp[idx] = m;
      }
      extern "C" __global__ void evbWindowCols(int grids, int n, int r, int isMax, const float* tmpAll, float* buf) {
          EVB_CELL
          const float* tmp = tmpAll + base;
          int lo = max(0, z - r);
          int hi = min(n - 1, z + r);
          float m = tmp[lo * n + x];
          for (int k = lo + 1; k <= hi; k++) {
              float v = tmp[k * n + x];
              if (isMax ? v > m : v < m) m = v;
          }
          buf[idx] = m;
      }
      extern "C" __global__ void evbBlurRows(int grids, int n, int r, const float* bufAll, float* tmp) {
          EVB_CELL
          const float* buf = bufAll + base;
          int row = z * n;
          int lo = max(0, x - r);
          int hi = min(n - 1, x + r);
          float sum = 0.0f;
          for (int k = lo; k <= hi; k++) sum += buf[row + k];
          tmp[idx] = sum / (float)(hi - lo + 1);
      }
      extern "C" __global__ void evbBlurCols(int grids, int n, int r, const float* tmpAll, float* buf) {
          EVB_CELL
          const float* tmp = tmpAll + base;
          int lo = max(0, z - r);
          int hi = min(n - 1, z + r);
          float inv = 1.0f / (float)(hi - lo + 1);
          float sum = 0.0f;
          for (int k = lo; k <= hi; k++) sum += tmp[k * n + x];
          buf[idx] = sum * inv;
      }
      // ---- S6.2: FreeTerraForged's CliffRelaxation (21 Oct): floats only (no exp, cos or pow), so every cell exact. ----
      // P: 15 modifier min, 16 max, 17 range (the relaxation's own Modifier).
      extern "C" __global__ void cliffMods(int count, const float* h, const int* mask, const float* emod, const float* edge, const float* river, const float* P,
                                           float* mods) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count) return;
          float c = h[i];
          mods[i] = mask[i] != 0 || !isfinite(c) ? 0.0f : ero_modify(P, c, emod[i], edge[i], river[i]);
      }
      __device__ __forceinline__ float cliff_hash01(int x, int z, int seed) {
          int n = (int)((unsigned int)x * 374761393u + (unsigned int)z * 668265263u + (unsigned int)seed * 144677219u);
          n = (int)((unsigned int)(n ^ (n >> 13)) * 1274126177u);
          n ^= (n >> 16);
          return (float)(n & 2147483647) / (float)2147483647;
      }
      __device__ __forceinline__ float cliff_value(float px, float pz, int seed) {
          int xi = __double2int_rz(floor((double)px));
          int zi = __double2int_rz(floor((double)pz));
          float fx = px - (float)xi;
          float fz = pz - (float)zi;
          fx = fx * fx * (3.0f - 2.0f * fx);
          fz = fz * fz * (3.0f - 2.0f * fz);
          float v00 = cliff_hash01(xi, zi, seed);
          float v10 = cliff_hash01(xi + 1, zi, seed);
          float v01 = cliff_hash01(xi, zi + 1, seed);
          float v11 = cliff_hash01(xi + 1, zi + 1, seed);
          float a = v00 + fx * (v10 - v00);
          float b = v01 + fx * (v11 - v01);
          return a + fz * (b - a);
      }
      __device__ __forceinline__ float cliff_centered(float wx, float wz, float broadFreq, float detailFreq, int seed) {
          float broad = cliff_value(wx * broadFreq, wz * broadFreq, seed);
          float detail = cliff_value(wx * detailFreq, wz * detailFreq, ftf_add(seed, 101));
          float n = (broad + 0.35f * detail) / (1.0f + 0.35f);
          return (n - 0.5f) * 2.0f;
      }
      extern "C" __global__ void cliffVariation(int n, int seed, int worldBlockX, int worldBlockZ, float broadFreq, float detailFreq, const float* mods,
                                                float* rateMul, float* reliefMul) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          if (mods[idx] == 0.0f) {
              rateMul[idx] = 1.0f;
              reliefMul[idx] = 1.0f;
              return;
          }
          float wx = (float)(worldBlockX + x);
          float wz = (float)(worldBlockZ + z);
          rateMul[idx] = 1.0f + 0.5f * cliff_centered(wx, wz, broadFreq, detailFreq, seed);
          reliefMul[idx] = 1.0f + 0.35f * cliff_centered(wx, wz, broadFreq, detailFreq, ftf_add(seed, 6007));
      }
      extern "C" __global__ void cliffPass(int n, const float* h, const float* mods, const float* rateMul, const float* reliefMul, float minRelief,
                                           float reliefSpan, float* next) {
          int idx = blockIdx.x * blockDim.x + threadIdx.x;
          if (idx >= n * n) return;
          int z = idx / n;
          int x = idx - z * n;
          int row = z * n;
          int zPrevRow = max(0, z - 1) * n;
          int zNextRow = min(n - 1, z + 1) * n;
          float c = h[idx];
          float m = mods[idx];
          if (m == 0.0f) {
              next[idx] = c;
              return;
          }
          float l = h[row + max(0, x - 1)];
          float r = h[row + min(n - 1, x + 1)];
          float d = h[zPrevRow + x];
          float u = h[zNextRow + x];
          float avg = 0.25f * (l + r + d + u);
          float hi = java_maxf(java_maxf(c, l), java_maxf(java_maxf(r, d), u));
          float lo = java_minf(java_minf(c, l), java_minf(java_minf(r, d), u));
          float rm = reliefMul[idx];
          float t = ftf_clamp((hi - lo - minRelief * rm) / (reliefSpan * rm), 0.0f, 1.0f);
          float mask = t * t * (3.0f - 2.0f * t);
          float rate = java_minf(0.5f, 0.4f * rateMul[idx]);
          float delta = (avg - c) * mask * rate;
          next[idx] = c + m * delta;
      }
      // A cell that was land before the filter and is water after it is set to the ground level (unsure cells are done
      // on the CPU after their per-cell step).
      extern "C" __global__ void eroFinish(int count, const float* P, const float* pre, const int* unsure, float* outHeight, float* outHeightErosion) {
          int i = blockIdx.x * blockDim.x + threadIdx.x;
          if (i >= count || unsure[i]) return;
          if (pre[i] > P[18] && outHeight[i] <= P[18]) {
              outHeightErosion[i] = outHeightErosion[i] + (P[19] - outHeight[i]);
              outHeight[i] = P[19];
          }
      }
      """;

   private ErosionKernels() {
   }

   /** NoiseUtil's static initialiser: SIN_BITS 12, radFull 6.2831855f, then the exact values at 0, 90, 180 and 270 degrees. */
   private static float[] sinTable() {
      int count = 4096;
      int mask = count - 1;
      float radFull = 6.2831855f;
      float degToIndex = count / 360.0f;
      float[] sin = new float[count];
      for (int i = 0; i < count; i++) {
         sin[i] = (float)Math.sin((i + 0.5f) / count * radFull);
      }

      for (int i = 0; i < 360; i += 90) {
         sin[(int)(i * degToIndex) & mask] = (float)Math.sin(i * 3.141592653589793 / 180.0);
      }

      return sin;
   }

   /** The table as a device array, and NoiseUtil.sin: SIN[(int)(r * radToIndex) & SIN_MASK], (int) of a float as Java's (NaN 0, saturating). */
   private static String sinSource() {
      StringBuilder out = new StringBuilder("__device__ const float ERO_SIN[4096] = {");
      for (int i = 0; i < SIN.length; i++) {
         // Hexadecimal float literals: exact, and constant (a device array's initialiser cannot call __int_as_float).
         out.append(i == 0 ? "" : ",").append(i % 8 == 0 ? "\n" : "").append(Float.toHexString(SIN[i])).append('f');
      }

      return out.append("};\n__device__ __forceinline__ float ero_sin(float r) {\n    float v = r * ")
         .append(Float.toHexString(RAD_TO_INDEX))
         .append("f;\n    int i = isnan(v) ? 0 : v >= 2147483647.0f ? 2147483647 : v <= -2147483648.0f ? (-2147483647 - 1) : (int)v;\n")
         .append("    return ERO_SIN[i & 4095];\n}\n").toString();
   }

   /**
    * S6: the 21 Oct filter's passes over one tile whose inputs are in the buffers: the envelope (window max, radius 3)
    * into relaxA, the minmod slopes, the gate (slope length, window min then box blur, radius 2) into zone, the per-cell
    * step and the waterline fix. cellHeight gets the heights it started from. Returns the envelope's buffer.
    */
   public static long runV2(Gpu gpu, Buffers d, int n, int seed, int octaves, int blockX, int blockZ) {
      return runV2(gpu, d, n, seed, octaves, blockX, blockZ, Variant.OCT_21);
   }

   /**
    * The 21 Oct filter's constants that FreeTerraForged 1.0.0013 (deleteTiles) changed, and its trig: the slope gate's
    * start and full slope (the range as the filter makes it, Math.max(1e-5f, full - start)), the depth cap per slope,
    * the envelope margin, and NoiseUtil's table sin / cos instead of Math's.
    */
   public record Variant(float slopeStart, float slopeFull, float depthPerSlope, float margin, boolean tableTrig) {
      public static final Variant OCT_21 = new Variant(0.15f, 0.6f, 0.5f, 0.5f, false);
      public static final Variant FTF_1_0_0013 = new Variant(0.05f, 0.15f, 0.08f, 1.5f, true);

      public float range() {
         return Math.max(1.0E-5f, this.slopeFull - this.slopeStart);
      }
   }

   public static long runV2(Gpu gpu, Buffers d, int n, int seed, int octaves, int blockX, int blockZ, Variant v) {
      int count = n * n;
      int grid = (count + 255) / 256;
      gpu.copy(d.height, d.cellHeight, 4L * count);
      gpu.run("ev2Envelope", grid, 1, count, d.height, d.relaxA);
      gpu.run("ev2WindowRows", grid, 1, n, 3, 1, d.relaxA, d.tmp);
      gpu.run("ev2WindowCols", grid, 1, n, 3, 1, d.tmp, d.relaxA);
      gpu.run("eroSlopes", grid, 1, n, d.height, d.params, d.slopeX, d.slopeZ);
      gpu.run("ev2Gate", grid, 1, count, d.slopeX, d.slopeZ, d.zone);
      gpu.run("ev2WindowRows", grid, 1, n, 2, 0, d.zone, d.tmp);
      gpu.run("ev2WindowCols", grid, 1, n, 2, 0, d.tmp, d.zone);
      gpu.run("ev2BlurRows", grid, 1, n, 2, d.zone, d.tmp);
      gpu.run("ev2BlurCols", grid, 1, n, 2, d.tmp, d.zone);
      gpu.run("ev2Main", grid, 1, n, seed, octaves, blockX, blockZ, d.params, d.height, d.relaxA, d.slopeX, d.slopeZ, d.zone, d.mask, d.modifier, d.edge,
         d.river, d.heightErosion, d.sediment, d.outHeight, d.outHeightErosion, d.outSediment, d.unsure, v.slopeStart(), v.range(), v.depthPerSlope(),
         v.margin(), v.tableTrig() ? 1 : 0);
      gpu.run("eroFinish", grid, 1, count, d.params, d.height, d.unsure, d.outHeight, d.outHeightErosion);
      return d.relaxA;
   }

   /**
    * S6.2: FreeTerraForged's CliffRelaxation over one tile whose height, mask, modifier, edge and river are in the
    * buffers and its modifier's min, max, range in params[15..17]: mods, the lateral variation (tmp: rate, zone:
    * relief), three Jacobi passes. Returns the buffer holding the relaxed heights (relaxA or relaxB).
    */
   public static long runCliff(Gpu gpu, Buffers d, int n, int seed, int blockX, int blockZ, float broadFreq, float detailFreq, float minRelief, float reliefSpan) {
      int count = n * n;
      int grid = (count + 255) / 256;
      gpu.copy(d.height, d.relaxA, 4L * count);
      gpu.run("cliffMods", grid, 1, count, d.height, d.mask, d.modifier, d.edge, d.river, d.params, d.mods);
      gpu.run("cliffVariation", grid, 1, n, seed, blockX, blockZ, broadFreq, detailFreq, d.mods, d.tmp, d.zone);
      long from = d.relaxA;
      long to = d.relaxB;
      for (int pass = 0; pass < 3; pass++) {
         gpu.run("cliffPass", grid, 1, n, from, d.mods, d.tmp, d.zone, minRelief, reliefSpan, to);
         long swap = from;
         from = to;
         to = swap;
      }

      return from;
   }

   /** Device buffers for tiles of up to this many cells; inputs are copied into the first eight before {@link #run}. */
   public static final class Buffers {
      public static final int PARAMS = 22;
      public final int cells;
      public final long params;
      public final long height;
      public final long mask;
      public final long modifier;
      public final long edge;
      public final long river;
      public final long heightErosion;
      public final long sediment;
      public final long cellHeight;
      public final long relaxA;
      public final long relaxB;
      public final long mods;
      public final long zone;
      public final long tmp;
      public final long slopeX;
      public final long slopeZ;
      public final long outHeight;
      public final long outHeightErosion;
      public final long outSediment;
      public final long unsure;

      public Buffers(Gpu gpu, int cells) {
         this.cells = cells;
         long bytes = 4L * cells;
         this.params = gpu.allocate(4L * PARAMS);
         this.height = gpu.allocate(bytes);
         this.mask = gpu.allocate(bytes);
         this.modifier = gpu.allocate(bytes);
         this.edge = gpu.allocate(bytes);
         this.river = gpu.allocate(bytes);
         this.heightErosion = gpu.allocate(bytes);
         this.sediment = gpu.allocate(bytes);
         this.cellHeight = gpu.allocate(bytes);
         this.relaxA = gpu.allocate(bytes);
         this.relaxB = gpu.allocate(bytes);
         this.mods = gpu.allocate(bytes);
         this.zone = gpu.allocate(bytes);
         this.tmp = gpu.allocate(bytes);
         this.slopeX = gpu.allocate(bytes);
         this.slopeZ = gpu.allocate(bytes);
         this.outHeight = gpu.allocate(bytes);
         this.outHeightErosion = gpu.allocate(bytes);
         this.outSediment = gpu.allocate(bytes);
         this.unsure = gpu.allocate(bytes);
      }
   }

   /**
    * Runs the filter's passes over one tile of n x n cells whose inputs are in the buffers. After it: cellHeight,
    * heightErosion and sediment hold the state after the cliff relaxation, the out buffers the result, unsure the
    * cells left to the CPU; returns the buffer holding the relaxed heights.
    */
   public static long run(Gpu gpu, Buffers d, int n, int seed, int octaves, int blockX, int blockZ) {
      int count = n * n;
      int grid = (count + 255) / 256;
      long bytes = 4L * count;
      gpu.copy(d.height, d.cellHeight, bytes);
      gpu.copy(d.height, d.relaxA, bytes);
      gpu.run("eroMods", grid, 1, count, d.height, d.mask, d.modifier, d.edge, d.river, d.params, d.mods, d.zone);
      long from = d.relaxA;
      long to = d.relaxB;
      for (int pass = 0; pass < 3; pass++) {
         gpu.run("eroRelax", grid, 1, n, from, d.mods, d.params, d.zone, to);
         long swap = from;
         from = to;
         to = swap;
      }

      gpu.run("eroRelaxApply", grid, 1, count, from, d.mods, d.cellHeight, d.heightErosion);
      gpu.run("eroDilateRows", grid, 1, n, d.zone, d.tmp);
      gpu.run("eroDilateCols", grid, 1, n, d.tmp, d.zone);
      gpu.run("eroSlopes", grid, 1, n, from, d.params, d.slopeX, d.slopeZ);
      gpu.run("eroMain", grid, 1, n, seed, octaves, blockX, blockZ, d.params, from, d.slopeX, d.slopeZ, d.zone, d.mask, d.modifier, d.edge, d.river,
         d.cellHeight, d.heightErosion, d.sediment, d.outHeight, d.outHeightErosion, d.outSediment, d.unsure);
      gpu.run("eroFinish", grid, 1, count, d.params, d.height, d.unsure, d.outHeight, d.outHeightErosion);
      return from;
   }
}
