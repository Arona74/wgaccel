package com.wgaccel.stats;

/**
 * Everything the mod times while chunks generate.
 *
 * <ul>
 *   <li>{@link Kind#WORK}: the time a thread spends doing it, nothing else (the shares in the report come from these).</li>
 *   <li>{@link Kind#WAIT}: a chunk step that hands its work to another thread; the time runs from the request to the
 *       result, so it includes waiting in that thread's queue. A long wait shows a queue that cannot keep up.</li>
 *   <li>{@link Kind#SAVE}: writing chunks to disk (called for every save, not once per new chunk).</li>
 *   <li>{@link Kind#FTF}: FreeTerraForged's tiles (the 2D terrain of 8 x 8 chunks) and their filters (erosion).</li>
 *   <li>{@link Kind#MAIN}: work on the main (server) thread when a chunk becomes a full chunk: its entities joining
 *       the world, its block entities, and other mods' chunk-load listeners.</li>
 * </ul>
 */
public enum Stage {
   STRUCTURE_STARTS("structures", Kind.WORK),
   STRUCTURE_REFERENCES("structure refs", Kind.WORK),
   BIOME_FILL("biomes", Kind.WORK),
   NOISE_FILL("noise fill", Kind.WORK),
   SURFACE("surface", Kind.WORK),
   CARVERS("carvers", Kind.WORK),
   FEATURES("features", Kind.WORK),
   SPAWN("spawn", Kind.WORK),
   BIOMES("biomes", Kind.WAIT),
   NOISE("noise", Kind.WAIT),
   INIT_LIGHT("init light", Kind.WAIT),
   LIGHT("light", Kind.WAIT),
   FULL("full", Kind.WAIT),
   SERIALIZE("serialize", Kind.SAVE),
   REGION_WRITE("region write", Kind.SAVE),
   FTF_TILE("tiles", Kind.FTF),
   FTF_FILTERS("filters", Kind.FTF),
   FTF_GROUND("ground read (tile wait)", Kind.FTF),
   BIOME_SAMPLE("climate sampling", Kind.FTF),
   BIOME_SELECT("biome choosing", Kind.FTF),
   BIOME_SEARCH("climate table search", Kind.FTF),
   BIOME_DECORATE("FTF decoration", Kind.FTF),
   BIOME_COVERAGE("surface coverage", Kind.FTF),
   BIOME_LAYOUT("underground layout", Kind.FTF),
   BIOME_IDENTITY("identity resample", Kind.FTF),
   BIOME_ROLES("terrain roles", Kind.FTF),
   BIOME_LAYOUT_SEARCH("layout searches", Kind.FTF),
   BIOME_SAMPLE3D("3D regions", Kind.FTF),
   BIOME_STAGE("stage choice", Kind.FTF),
   FILL_CORNERS("cell corners", Kind.FTF),
   SURFACE_SYSTEM("surface system", Kind.FTF),
   SURFACE_BEFORE("before the rules", Kind.FTF),
   SURFACE_RULE_TREE("rule tree made", Kind.FTF),
   SURFACE_RULES("rules applied", Kind.FTF),
   ENTITIES("entities", Kind.MAIN),
   BLOCK_ENTITIES("block entities", Kind.MAIN),
   LOAD_EVENT("chunk-load listeners", Kind.MAIN);

   public final String label;
   public final Kind kind;

   Stage(String label, Kind kind) {
      this.label = label;
      this.kind = kind;
   }

   public enum Kind {
      WORK,
      WAIT,
      SAVE,
      FTF,
      MAIN
   }
}
