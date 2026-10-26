package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.Setting;
import java.util.List;

/**
 * The settings screen's Advanced tab: the GPU's checks and tuning ([gpu.tuning] and a few [gpu] values), in groups, each
 * with a short name and a description for players. The defaults are the measured best; every value gives the same world.
 */
final class Advanced {
   record Entry(Setting<?> setting, String name, String description) {
   }

   record Group(String title, List<Entry> entries) {
   }

   static final List<Group> GROUPS = List.of(
      new Group("Checks", List.of(
         new Entry(Config.GPU_MODE, "GPU test at world load",
            "When a world opens: OFF none, SELFTEST a small GPU maths test compared with Java's (the default), CHECK also FreeTerraForged's noise"
               + " worked out on the GPU and compared (a slower world load)."),
         new Entry(Config.GPU_FILL_CHECK_EVERY, "Noise fill check interval",
            "Every how many GPU noise fills one is also done the normal way and compared (64: about 1.5%; 1: every chunk, much slower, to hunt a"
               + " difference). The first difference turns the GPU fill off."),
         new Entry(Config.GPU_FILL_DEBUG_CHUNKS, "Noise fill debug chunks",
            "Chunks whose GPU noise fill is always checked, diagnosed in the log and saved, as \"x,z\" chunk coordinates separated by \";\"."
               + " Empty: none."))),
      new Group("GPU queue and batches", List.of(
         new Entry(Config.GPU_QUEUE_LIMIT, "GPU queue places",
            "How many chunks may be on the GPU at once; a chunk that finds it full does that step on the CPU (the same blocks). 0: no limit."),
         new Entry(Config.GPU_QUEUE_ADAPTIVE, "Adaptive queue",
            "The queue's places follow the measured times (2 to 32), starting from the value above."),
         new Entry(Config.GPU_QUEUE_OPEN_BELOW, "Open a place below",
            "With the adaptive queue: one more place opens while the GPU way takes less than this share of the CPU way (0.5 to 1.0)."),
         new Entry(Config.GPU_BATCH_CHUNKS, "Noise fill batch",
            "Up to this many chunks' noise fills in one GPU launch. 0: one chunk per launch."),
         new Entry(Config.GPU_SURFACE_BATCH_CHUNKS, "Surface batch",
            "Up to this many chunks' surfaces in one GPU launch. 0: one chunk per launch."),
         new Entry(Config.GPU_SURFACE_BATCH_THREADS, "Surface batch threads",
            "Threads that gather and launch the surface batches (1 to 16): more, and a chunk waits less for its batch to start."))),
      new Group("Streams and threads", List.of(
         new Entry(Config.GPU_STREAM_PRIORITIES, "Stream priorities",
            "GPU work a generation thread waits for runs at the GPU's highest priority, the look-ahead's at its lowest."),
         new Entry(Config.GPU_EROSION_PRIORITY, "Erosion on a priority stream",
            "FreeTerraForged's tile erosion on its own highest-priority stream. Applies when the GPU starts (the first world of a game)."),
         new Entry(Config.GPU_SERVICE_THREADS, "Tile filter threads",
            "Threads running FreeTerraForged's tile filters on the GPU (1 to 8). Applies when the GPU starts."),
         new Entry(Config.GPU_STREAM_POOL, "HIP streams per priority",
            "AMD HIP only: how many GPU streams each priority shares (0: one per thread and part, which costs much more RAM). Applies when the GPU"
               + " starts."),
         new Entry(Config.GPU_DEVICE_CONNECTIONS, "CUDA hardware queues",
            "NVIDIA CUDA only: hardware work queues for the mod's streams (0: the driver's 8; up to 32). Applies when the game starts."))),
      new Group("Look-ahead", List.of(
         new Entry(Config.GPU_LOOK_AHEAD_CHUNKS, "Look-ahead chunks kept",
            "Finished chunks' blocks that may wait in memory for their noise step (about 120 KB each); past that the oldest are dropped."),
         new Entry(Config.GPU_AREA_BATCHES, "Look-ahead in 4 x 4 areas",
            "The look-ahead takes whole 4 x 4 chunk areas at once, so the GPU gets fewer, bigger launches."),
         new Entry(Config.GPU_BIOMES_AHEAD_AT_REFERENCES, "Biomes ahead from structure references",
            "The biome look-ahead notices a chunk a little later, but only chunks that are really going to be generated (less wasted GPU work)."))),
      new Group("Surface", List.of(
         new Entry(Config.GPU_SURFACE_MAPS_AFTER, "Heightmaps after the surface",
            "Each column's heightmaps worked out once after the GPU surface's writes instead of for every block."),
         new Entry(Config.GPU_SURFACE_WARP_PREPASS, "Column walk by 32 threads",
            "The surface's column walk (stone above and below, water above) by 32 GPU threads per column instead of one."),
         new Entry(Config.GPU_SURFACE_SMALL_BLOCKS, "Small rock-layer blocks",
            "The surface's rock-layer kernels in groups of 32 GPU threads instead of 256 and 128."),
         new Entry(Config.GPU_SURFACE_KINDS_FROM_CODES, "Surface inputs from fill codes",
            "The surface's inputs (air, fluid, stone) from the noise fill's codes instead of the chunk's blocks. Off by default: they were not always"
               + " the chunk's blocks."))),
      new Group("Other", List.of(
         new Entry(Config.GPU_BULK_SECTIONS, "Whole sections at once",
            "Sections the noise fill covers completely, or the surface changes for the most part, built in one go instead of block by block."),
         new Entry(Config.GPU_CUBIN_CACHE, "Keep compiled machine code",
            "CUDA: the GPU programs also kept on disk as the driver's finished machine code, so worlds load them without translating them again."),
         new Entry(Config.GPU_NOISE_FILL_CPU_DIMENSIONS, "Dimensions on the CPU",
            "Dimensions whose noise fill stays on the CPU even with Other dimensions on (GPU improvements tab), separated by \";\". The Nether and the"
               + " End by default: the CPU was as fast there."))));

   private Advanced() {
   }
}
