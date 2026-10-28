package net.minecraft.resources;

/**
 * Compile time only, never in the jar: FreeTerraForged's 1.21.1 classes, which the shared FTF layer is compiled
 * against on every version, name 1.21.1's ResourceLocation (26.1's Identifier). FreeTerraForged is never in a 26.1
 * game, so that code never runs there.
 */
public final class ResourceLocation {
   private ResourceLocation() {
   }
}
