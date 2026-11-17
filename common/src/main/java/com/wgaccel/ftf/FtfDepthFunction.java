package com.wgaccel.ftf;

/**
 * Bridge to FreeTerraForged's {@code ClimateDepthFunction} vertical bounds so {@link com.wgaccel.gen.ClimateColumns}
 * can evaluate cached surface heights without referencing FTF types outside {@code mixin.ftf}.
 */
public interface FtfDepthFunction {
   int wgaccel$worldDepth();

   int wgaccel$worldHeight();
}
