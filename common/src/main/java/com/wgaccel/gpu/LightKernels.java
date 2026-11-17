package com.wgaccel.gpu;

/**
 * Clean-room CUDA C (and OpenCL-translatable via {@link OpenClSource}) kernels for chunk BlockLight and SkyLight across
 * a 3x3 chunk halo.
 * <p>
 * Layout:
 * <ul>
 *   <li>Volume dimensions: {@code 48 x (activeSecCount * 16) x 48} in {@code (y * 48 + z) * 48 + x} order.
 *   <li>Chunk offset {@code (1, 1)} ({@code x = 16..31, z = 16..31}) is the center chunk being lit.
 *   <li>Voxel light packed in one byte: {@code (sky << 4) | block}.
 *   <li>Direction indices for {@code stateShapes[state * 6 + dir]}: {@code 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST}
 *       so {@code opposite(d) = d ^ 1}.
 * </ul>
 */
public final class LightKernels {
   public static final String SOURCE = """
      extern "C" __global__ void lightInit(
            const int* __restrict__ sectionMode,
            const unsigned short* __restrict__ denseStates,
            const unsigned int* __restrict__ stateMeta,
            const unsigned short* __restrict__ stateShapes,
            const unsigned int* __restrict__ shapeMatrix,
            int shapeWords,
            int activeSecCount,
            int hasSkyLight,
            int airStateId,
            unsigned short* __restrict__ devStates,
            unsigned char* __restrict__ devLightA) {
         int x = (int)(blockIdx.x * blockDim.x + threadIdx.x);
         int z = (int)(blockIdx.y * blockDim.y + threadIdx.y);
         if (x >= 48 || z >= 48) {
            return;
         }

         int cxOff = x >> 4;
         int czOff = z >> 4;
         int lx = x & 15;
         int lz = z & 15;
         int chunkIdx = czOff * 3 + cxOff;
         int colBase = chunkIdx * activeSecCount;
         int colPlane = z * 48 + x;

         int prevState = airStateId;
         int skyLit = hasSkyLight != 0 ? 1 : 0;

         for (int sy = activeSecCount - 1; sy >= 0; sy--) {
            int entry = sectionMode[colBase + sy];
            int yBase = sy << 4;
            if (entry < 0) {
               int stateId = -1 - entry;
               unsigned int meta = stateMeta[stateId];
               int blk = (int)(meta & 15u);
               if (skyLit != 0) {
                  unsigned int pMeta = stateMeta[prevState];
                  bool occ = (meta & 0x100u) != 0u;
                  if (!occ && (((pMeta | meta) & 0x200u) != 0u)) {
                     unsigned int sA = (unsigned int)stateShapes[(unsigned int)prevState * 6u + 0u];
                     unsigned int sB = (unsigned int)stateShapes[(unsigned int)stateId * 6u + 1u];
                     occ = ((shapeMatrix[sA * (unsigned int)shapeWords + (sB >> 5)] >> (sB & 31u)) & 1u) != 0u;
                  }
                  if (occ) {
                     skyLit = 0;
                  }
               }
               int topSky = skyLit != 0 ? 15 : 0;
               unsigned char topPacked = (unsigned char)((topSky << 4) | blk);
               int idx15 = (yBase + 15) * 2304 + colPlane;
               devStates[idx15] = (unsigned short)stateId;
               devLightA[idx15] = topPacked;

               // Second block downward transitions within identical uniform block state
               if (skyLit != 0) {
                  bool occSelf = (meta & 0x100u) != 0u;
                  if (!occSelf && ((meta & 0x200u) != 0u)) {
                     unsigned int sA = (unsigned int)stateShapes[(unsigned int)stateId * 6u + 0u];
                     unsigned int sB = (unsigned int)stateShapes[(unsigned int)stateId * 6u + 1u];
                     occSelf = ((shapeMatrix[sA * (unsigned int)shapeWords + (sB >> 5)] >> (sB & 31u)) & 1u) != 0u;
                  }
                  if (occSelf) {
                     skyLit = 0;
                  }
               }
               unsigned char restPacked = (unsigned char)(((skyLit != 0 ? 15 : 0) << 4) | blk);
               for (int ly = 14; ly >= 0; ly--) {
                  int idx = (yBase + ly) * 2304 + colPlane;
                  devStates[idx] = (unsigned short)stateId;
                  devLightA[idx] = restPacked;
               }
               prevState = stateId;
            } else {
               int denseBase = entry * 4096 + (lz << 4) + lx;
               for (int ly = 15; ly >= 0; ly--) {
                  int stateId = (int)denseStates[denseBase + (ly << 8)];
                  unsigned int meta = stateMeta[stateId];
                  if (skyLit != 0) {
                     bool occ = (meta & 0x100u) != 0u;
                     if (!occ && (((stateMeta[prevState] | meta) & 0x200u) != 0u)) {
                        unsigned int sA = (unsigned int)stateShapes[(unsigned int)prevState * 6u + 0u];
                        unsigned int sB = (unsigned int)stateShapes[(unsigned int)stateId * 6u + 1u];
                        occ = ((shapeMatrix[sA * (unsigned int)shapeWords + (sB >> 5)] >> (sB & 31u)) & 1u) != 0u;
                     }
                     if (occ) {
                        skyLit = 0;
                     }
                  }
                  int idx = (yBase + ly) * 2304 + colPlane;
                  devStates[idx] = (unsigned short)stateId;
                  devLightA[idx] = (unsigned char)(((skyLit != 0 ? 15 : 0) << 4) | (int)(meta & 15u));
                  prevState = stateId;
               }
            }
         }
      }

      extern "C" __global__ void lightRelax(
            const unsigned short* __restrict__ devStates,
            const unsigned char* __restrict__ devLightIn,
            unsigned char* __restrict__ devLightOut,
            const unsigned int* __restrict__ stateMeta,
            const unsigned short* __restrict__ stateShapes,
            const unsigned int* __restrict__ shapeMatrix,
            int shapeWords,
            int activeSecCount,
            int hasSkyLight,
            int airStateId,
            int opaqueStateId,
            int* __restrict__ devChanged) {
         __shared__ unsigned char s_light[5832];
         __shared__ unsigned short s_state[5832];
         __shared__ unsigned int s_blkWork;
         __shared__ unsigned int s_skyGt1;
         __shared__ unsigned int s_intSkyLt15;

         int cxOff = (int)blockIdx.x;
         int sy = (int)blockIdx.y;
         int czOff = (int)blockIdx.z;
         int lx = (int)threadIdx.x;
         int lz = (int)threadIdx.z;
         int tid = (lz << 4) | lx;
         if (tid == 0) {
            s_blkWork = 0u;
            s_skyGt1 = 0u;
            s_intSkyLt15 = 0u;
         }
         __syncthreads();

         int baseX = cxOff << 4;
         int baseY = sy << 4;
         int baseZ = czOff << 4;
         int activeHeight = activeSecCount << 4;

         unsigned int lBlk = 0u;
         unsigned int lSky = 0u;
         unsigned int lIntLt15 = 0u;
         for (int t = tid; t < 5832; t += 256) {
            int hy = t / 324;
            int rem = t - hy * 324;
            int hz = rem / 18;
            int hx = rem - hz * 18;
            int gx = baseX + hx - 1;
            int gy = baseY + hy - 1;
            int gz = baseZ + hz - 1;

            unsigned char lv;
            unsigned short st;
            if (gx < 0 || gx >= 48 || gz < 0 || gz >= 48) {
               lv = (unsigned char)0;
               st = (unsigned short)airStateId;
            } else if (gy >= activeHeight) {
               lv = (unsigned char)((hasSkyLight != 0 ? 15 : 0) << 4);
               st = (unsigned short)airStateId;
            } else if (gy < 0) {
               lv = (unsigned char)0;
               st = (unsigned short)opaqueStateId;
            } else {
               int gIdx = gy * 2304 + gz * 48 + gx;
               lv = devLightIn[gIdx];
               st = devStates[gIdx];
            }
            s_light[t] = lv;
            s_state[t] = st;
            if ((lv & 15u) > 1u) {
               lBlk = 1u;
            }
            unsigned int skyV = (unsigned int)(lv >> 4);
            if (skyV > 1u) {
               lSky = 1u;
            }
            if (skyV < 15u && hx >= 1 && hx <= 16 && hy >= 1 && hy <= 16 && hz >= 1 && hz <= 16) {
               lIntLt15 = 1u;
            }
         }
         if (lBlk != 0u) s_blkWork = 1u;
         if (lSky != 0u) s_skyGt1 = 1u;
         if (lIntLt15 != 0u) s_intSkyLt15 = 1u;
         __syncthreads();

         // Fast skip: no BlockLight > 1 in tile+halo AND (no SkyLight > 1 in tile+halo OR interior already all 15)
         int colPlane = (lz + 1) * 18 + (lx + 1);
         if (s_blkWork == 0u && (s_skyGt1 == 0u || s_intSkyLt15 == 0u)) {
            int gx = baseX + lx;
            int gz = baseZ + lz;
            int gPlane = gz * 48 + gx;
            for (int ly = 0; ly < 16; ly++) {
               devLightOut[(baseY + ly) * 2304 + gPlane] = s_light[(ly + 1) * 324 + colPlane];
            }
            return;
         }

         unsigned short mySt[16];
         unsigned char myOp[16];
         unsigned char myHasSh[16];
         unsigned char nextL[16];
         for (int ly = 0; ly < 16; ly++) {
            int sIdx = (ly + 1) * 324 + colPlane;
            unsigned short st = s_state[sIdx];
            unsigned int meta = stateMeta[st];
            mySt[ly] = st;
            myOp[ly] = (unsigned char)((meta >> 4) & 15u);
            myHasSh[ly] = (unsigned char)((meta & 0x200u) != 0u ? 1 : 0);
         }

         unsigned int anyTileChange = 0u;
         for (int sweep = 0; sweep < 6; sweep++) {
            unsigned int sweepChange = 0u;
            for (int ly = 0; ly < 16; ly++) {
               int sIdx = (ly + 1) * 324 + colPlane;
               unsigned char cur = s_light[sIdx];
               int bestSky = (int)(cur >> 4);
               int bestBlk = (int)(cur & 15u);
               int op = (int)myOp[ly];
               if (bestSky < 15 || bestBlk < 15) {
                  int dstSt = (int)mySt[ly];
                  int dstHasSh = (int)myHasSh[ly];
                  // Check 6 neighbors: 0=DOWN(-324), 1=UP(+324), 2=NORTH(-18), 3=SOUTH(+18), 4=WEST(-1), 5=EAST(+1)
                  int nOffsets[6] = { -324, 324, -18, 18, -1, 1 };
                  for (int d = 0; d < 6; d++) {
                     int nIdx = sIdx + nOffsets[d];
                     unsigned char nL = s_light[nIdx];
                     int cSky = (int)(nL >> 4) - op;
                     int cBlk = (int)(nL & 15u) - op;
                     if (cSky > bestSky || cBlk > bestBlk) {
                        bool occ = false;
                        unsigned short srcSt = s_state[nIdx];
                        if (dstHasSh != 0 || (stateMeta[srcSt] & 0x200u) != 0u) {
                           unsigned int shSrc = (unsigned int)stateShapes[(unsigned int)srcSt * 6u + (unsigned int)(d ^ 1)];
                           unsigned int shDst = (unsigned int)stateShapes[(unsigned int)dstSt * 6u + (unsigned int)d];
                           occ = ((shapeMatrix[shSrc * (unsigned int)shapeWords + (shDst >> 5)] >> (shDst & 31u)) & 1u) != 0u;
                        }
                        if (!occ) {
                           if (cSky > bestSky) bestSky = cSky;
                           if (cBlk > bestBlk) bestBlk = cBlk;
                        }
                     }
                  }
               }
               unsigned char nxt = (unsigned char)((bestSky << 4) | bestBlk);
               nextL[ly] = nxt;
               if (nxt != cur) {
                  sweepChange = 1u;
                  anyTileChange = 1u;
               }
            }
            if (sweep == 5) {
               int gx = baseX + lx;
               int gz = baseZ + lz;
               int gPlane = gz * 48 + gx;
               for (int ly = 0; ly < 16; ly++) {
                  devLightOut[(baseY + ly) * 2304 + gPlane] = nextL[ly];
               }
            } else {
               __syncthreads();
               for (int ly = 0; ly < 16; ly++) {
                  s_light[(ly + 1) * 324 + colPlane] = nextL[ly];
               }
               __syncthreads();
            }
            // Note: do NOT break out per-thread; only check s_tileFlags if optimizing further
            (void)sweepChange;
         }
         if (anyTileChange != 0u) {
            *devChanged = 1;
         }
      }

      extern "C" __global__ void lightPack(
            const unsigned char* __restrict__ devLight,
            int activeSecCount,
            unsigned char* __restrict__ outBlock,
            unsigned char* __restrict__ outSky,
            int* __restrict__ outFlags) {
         __shared__ int s_blkOr[256];
         __shared__ int s_skyOr[256];
         __shared__ int s_skyAnd[256];

         int sy = (int)blockIdx.x;
         if (sy >= activeSecCount) {
            return;
         }
         int tid = (int)threadIdx.x;
         int ly = tid >> 4;
         int lz = tid & 15;
         int y = (sy << 4) | ly;
         int rowBase = y * 2304 + (16 + lz) * 48 + 16;
         int secOutBase = sy * 2048 + (ly << 7) + (lz << 3);

         int bOr = 0;
         int sOr = 0;
         int sAnd = 15;
         for (int xp = 0; xp < 8; xp++) {
            unsigned int l0 = (unsigned int)devLight[rowBase + (xp << 1)];
            unsigned int l1 = (unsigned int)devLight[rowBase + (xp << 1) + 1];
            unsigned int b0 = l0 & 15u;
            unsigned int b1 = l1 & 15u;
            unsigned int s0 = l0 >> 4;
            unsigned int s1 = l1 >> 4;
            outBlock[secOutBase + xp] = (unsigned char)((b1 << 4) | b0);
            outSky[secOutBase + xp] = (unsigned char)((s1 << 4) | s0);
            bOr |= (int)(b0 | b1);
            sOr |= (int)(s0 | s1);
            sAnd &= (int)(s0 & s1);
         }
         s_blkOr[tid] = bOr;
         s_skyOr[tid] = sOr;
         s_skyAnd[tid] = sAnd;
         __syncthreads();

         for (int stride = 128; stride > 0; stride >>= 1) {
            if (tid < stride) {
               s_blkOr[tid] |= s_blkOr[tid + stride];
               s_skyOr[tid] |= s_skyOr[tid + stride];
               s_skyAnd[tid] &= s_skyAnd[tid + stride];
            }
            __syncthreads();
         }
         if (tid == 0) {
            int flags = 0;
            if (s_blkOr[0] != 0) flags |= 1;
            if (s_skyOr[0] != 0) flags |= 2;
            if (s_skyAnd[0] != 15) flags |= 4;
            outFlags[sy] = flags;
         }
      }
      """;

   private LightKernels() {
   }
}
