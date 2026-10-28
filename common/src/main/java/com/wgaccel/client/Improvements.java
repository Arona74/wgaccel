package com.wgaccel.client;

import com.wgaccel.Config;
import com.wgaccel.Setting;
import java.util.List;

/**
 * The improvements the settings screen lists, each with a short name and a description for players (the config file's
 * comments are for benchmarking), and the improvement it needs, if any (listed under it).
 */
final class Improvements {
   record Improvement(Setting<?> setting, String name, String description, Setting<?> needs) {
   }

   static final List<Improvement> GPU = List.of(
      new Improvement(Config.GPU_NOISE_FILL, "Noise fill",
         "The 3D terrain (every block's density, water, lava and ore veins) worked out on the GPU. Chunks with old-world blending stay on the CPU.", null),
      new Improvement(Config.GPU_LOOK_AHEAD, "Noise fill ahead",
         "Chunks' noise fills sent to the GPU in batches before Minecraft asks for them, so they are ready when their step comes.", Config.GPU_NOISE_FILL),
      new Improvement(Config.GPU_STRUCTURES, "Structure terrain",
         "Chunks where villages, ancient cities and other structures shape the terrain fill on the GPU too.", Config.GPU_NOISE_FILL),
      new Improvement(Config.GPU_NOISE_FILL_OTHER_DIMENSIONS, "Other dimensions",
         "The GPU fill in modded dimensions too (the Nether and the End stay on the CPU, which was as fast there: config gpuNoiseFillCpuDimensions).",
         Config.GPU_NOISE_FILL),
      new Improvement(Config.GPU_NOISE_FILL_VANILLA, "Worlds without FreeTerraForged",
         "The GPU fill in vanilla and other worlds. On by default on Minecraft 26.1.2 (1.4x faster in vanilla on the test PC), off on 1.21.1 (slower there). Conquest ReTerraForged worlds always use it.", Config.GPU_NOISE_FILL),
      new Improvement(Config.GPU_SURFACE, "Surface",
         "The surface (grass, dirt, sand, deepslate, rock layers) placed from the GPU. Always on the CPU with Intel graphics.", null),
      new Improvement(Config.GPU_SURFACE_AHEAD, "Surface ahead",
         "A chunk's surface worked out on the GPU right after its noise fill, so it is ready when its step comes.", Config.GPU_SURFACE),
      new Improvement(Config.GPU_BIOMES, "Biomes",
         "The biome choice (each cell's climate and the search of the climate table) on the GPU.", null),
      new Improvement(Config.GPU_BIOMES_AHEAD, "Biomes ahead",
         "The biome work done ahead of the chunk's biome step; a chunk whose result is not ready in time is worked out on the CPU.", Config.GPU_BIOMES),
      new Improvement(Config.GPU_BIOME_DECORATION, "Biome decoration",
         "FreeTerraForged's cave biomes and rivers on the GPU too, in the same run as the biome choice.", Config.GPU_BIOMES),
      new Improvement(Config.GPU_BIOMES_VANILLA, "Worlds without FreeTerraForged",
         "The GPU biomes in vanilla and other worlds. Off by default: the CPU was faster there on the test PC.", Config.GPU_BIOMES),
      new Improvement(Config.GPU_LITHOS_NOISE, "Lithos noise",
         "Minecraft 26.1.2 with Lithos: its rock layers' noise worked out on the GPU for each chunk before Lithos asks for it.", null),
      new Improvement(Config.GPU_TILE_NOISE, "Terrain noise",
         "FreeTerraForged's terrain noise (mostly the mountains' erosion noise) worked out on the GPU for many columns at once.", null),
      new Improvement(Config.GPU_EROSION_FILTER, "Erosion filter",
         "FreeTerraForged's erosion filter on the GPU; the few cells it cannot be sure of are redone on the CPU.", null),
      new Improvement(Config.GPU_CLIFF_RELAXATION, "Cliff relaxation",
         "FreeTerraForged's cliff filter on the GPU. Off by default: exact, but slower than the CPU in tests.", Config.GPU_EROSION_FILTER),
      new Improvement(Config.GPU_AREA_STEP, "4 x 4 area step (in development)",
         "With C2ME: the GPU work of a 4 x 4 chunk area done once for its 16 chunks. Also turns on the GPU fill and biomes in worlds without FreeTerraForged.",
         null));

   static final List<Improvement> CPU = List.of(
      new Improvement(Config.FILL_TO_GROUND, "Fill to the ground",
         "FreeTerraForged's 3D terrain filled only up to the highest ground, sea level and structures instead of the whole world height (the air above stays air).",
         null),
      new Improvement(Config.FASTER_BIOME_CHOICE, "Faster biome choice", "Skips FreeTerraForged's repeated work while choosing biomes.", null),
      new Improvement(Config.FTF_SHARED_CHUNK_CELLS, "Shared chunk cells",
         "FreeTerraForged 1.0.0013 and later: a chunk's heightmap and terrain filters worked out once and shared instead of three times, and its minimum"
            + " surface heights shared by all threads.", null),
      new Improvement(Config.FTF_RAW_CHUNK_CACHE, "Bigger raw chunk cache",
         "FreeTerraForged 1.0.0013 and later: keeps more chunks' raw heightmaps (about 47 KB each) than its own 1,024, so fewer are made again.", null),
      new Improvement(Config.FTF_REGION_PREFETCH, "Region prefetch",
         "FreeTerraForged 1.0.0013 and later: a region's 36 chunks made on 4 threads at once instead of one after another.", null),
      new Improvement(Config.FAST_CHUNK_COMPRESSION, "Fast chunk compression",
         "Saved chunks compressed in large blocks at zlib's fastest level: less CPU while saving, region files somewhat larger, the same world.", null),
      new Improvement(Config.FTF_FAST_RIVERS, "Fast rivers", "FreeTerraForged's river carving skips the rivers too far away to touch a column.", null),
      new Improvement(Config.TILE_CACHE_TILES, "Bigger tile cache",
         "FreeTerraForged 1.0.0010 (tiles): keeps more of its terrain tiles (4 to 6 MB each) than its own 4 to 32, so they are not made again and again.",
         null),
      new Improvement(Config.FTF_FAST_PLACEMENT_SCOPES, "Fast placement scopes",
         "A quicker way to end FreeTerraForged's per-feature placement scopes (about 4% with Still Life).", null),
      new Improvement(Config.FAST_ORES, "Fast ores", "Ores placed without looking their chunk up again for every column.", null),
      new Improvement(Config.FAST_LAYER_PLACEMENT, "Fast every-layer placement",
         "Features placed on every ground layer (No Man's Land's roots, icicles, shelf mushrooms) scan each column once instead of once per layer;"
            + " No Man's Land's ore veins fill their noise once per layer.", null),
      new Improvement(Config.FAST_JIGSAW, "Fast jigsaw structures",
         "Villages and other jigsaw structures rotate each piece's connection blocks once instead of for every connection they try.", null),
      new Improvement(Config.FAST_CONQUEST_LANDFORMS, "Fast Conquest landforms",
         "Conquest ReTerraForged: each column's landform worked out once for the trees that ask for it again and again.", null),
      new Improvement(Config.FAST_REGION_CHUNKS, "Fast region chunks",
         "Features find the chunk of each block they read or write without looking it up again every time.", null),
      new Improvement(Config.FAST_REGION_HEIGHTS, "Fast region heights",
         "Features read the ground heights of the terrain (which no longer change at that step) once per column instead of every time.", null),
      new Improvement(Config.FAST_SURFACE_READS, "Fast surface reads", "Minecraft's surface step reads blocks straight from the chunk's sections.", null),
      new Improvement(Config.GPU_CARVER_BIOMES, "Carver biomes from the chunk",
         "Caves and canyons read nearby biomes from the chunks that already hold them instead of working them out again.", null),
      new Improvement(Config.FAST_BLOCK_PREDICATES, "Fast block predicates",
         "Placement filters read each block once per check. Off by default: slower in the Still Life test.", null));

   private Improvements() {
   }

   /** Whether a value means on: true, a mode other than OFF, a number other than 0. */
   static boolean on(Object value) {
      if (value instanceof Boolean on) {
         return on;
      } else if (value instanceof Number number) {
         return number.intValue() != 0;
      } else if (value instanceof Enum<?> mode) {
         return !mode.name().equals("OFF");
      }

      return false;
   }

   /** The value a toggle sets: off for an on value, else what on means (the preset's value, ON, or true). */
   @SuppressWarnings({"unchecked", "rawtypes"})
   static Object flipped(Setting<?> setting) {
      Object value = setting.chosen();
      if (value instanceof Boolean on) {
         return !on;
      } else if (value instanceof Integer number) {
         if (number != 0) {
            return 0;
         }

         Object normal = setting.normal();
         Object on = normal instanceof Integer known && known != 0 ? known : setting.fileDefault();
         return on instanceof Integer known && known != 0 ? known : 1;
      } else if (value instanceof Enum<?> mode) {
         return Enum.valueOf((Class)mode.getDeclaringClass(), on(value) ? "OFF" : "ON");
      }

      return value;
   }

   /** The toggle's label: ON, OFF, CHECK (set in the config file), or a cache's size. */
   static String label(Object value) {
      if (value instanceof Integer number) {
         return number == 0 ? "OFF" : String.format(java.util.Locale.ROOT, "%,d", number);
      } else if (value instanceof Enum<?> mode) {
         return mode.name();
      }

      return on(value) ? "ON" : "OFF";
   }

   @SuppressWarnings("unchecked")
   static void choose(Setting<?> setting, Object value) {
      ((Setting<Object>)setting).choose(value);
   }
}
