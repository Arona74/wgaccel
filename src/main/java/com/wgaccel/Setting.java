package com.wgaccel;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * One config setting as the mod reads it: with the config's preset CLIENT or SERVER (or AUTO, which picks one by where
 * the game runs) the preset's value; with CUSTOM (or for a setting the presets leave alone) the value written in the
 * file. The presets never write the file, so switching back to CUSTOM finds the values written there unchanged.
 */
public final class Setting<T> implements Supplier<T>, BooleanSupplier, IntSupplier, DoubleSupplier {
   private final ModConfigSpec.ConfigValue<T> raw;
   private final T client;
   private final T server;
   /** The section switch this setting belongs to (cpuImprovements, gpuImprovements) and its value while that is off. */
   private Setting<Boolean> master;
   private T off;
   /** The value as last read, and the config generation it was read in (hot paths read settings per block or ore). */
   private volatile T cached;
   private volatile int cachedGeneration = -1;
   /** Raised when the config is loaded or reloaded (WorldgenAccelerator's ModConfigEvent listener): every cache is stale. */
   private static volatile int generation;

   /** The config was loaded or changed: settings read it again (0.9.88's profile: config reads 2.9% of the CPU). */
   public static void invalidate() {
      generation++;
   }

   private Setting(ModConfigSpec.ConfigValue<T> raw, T client, T server) {
      this.raw = raw;
      this.client = client;
      this.server = server;
   }

   /** A setting both presets give these values. */
   public static <T> Setting<T> of(ModConfigSpec.ConfigValue<T> raw, T client, T server) {
      return new Setting<>(raw, client, server);
   }

   /** A setting the presets leave alone (always the file's value). */
   public static <T> Setting<T> custom(ModConfigSpec.ConfigValue<T> raw) {
      return new Setting<>(raw, null, null);
   }

   @Override
   public T get() {
      int now = generation;
      if (this.cachedGeneration == now) {
         return this.cached;
      }

      T value = this.read();
      this.cached = value;
      this.cachedGeneration = now;
      return value;
   }

   private T read() {
      if (this.master != null && !this.master.get()) {
         return this.off;
      }

      Config.Preset preset = Config.preset();
      if (preset == Config.Preset.CUSTOM || this.client == null) {
         return this.raw.get();
      }

      return preset == Config.Preset.SERVER ? this.server : this.client;
   }

   @Override
   public boolean getAsBoolean() {
      return (Boolean)this.get();
   }

   @Override
   public int getAsInt() {
      return ((Number)this.get()).intValue();
   }

   @Override
   public double getAsDouble() {
      return ((Number)this.get()).doubleValue();
   }

   /** In a section with an on/off switch: while the switch is off, this setting reads as off. */
   Setting<T> under(Setting<Boolean> master, T off) {
      this.master = master;
      this.off = off;
      return this;
   }

   /** The value written in the config file. */
   public ModConfigSpec.ConfigValue<T> raw() {
      return this.raw;
   }

   /**
    * The value the preset gives (SERVER's with that preset, CLIENT's otherwise, CUSTOM included), or null for a setting
    * the presets leave alone: what the world-loading screen calls normal.
    */
   public T normal() {
      return Config.preset() == Config.Preset.SERVER ? this.server : this.client;
   }

   /** Whether this setting's section switch (cpuImprovements, gpuImprovements) is off, so it reads as off. */
   public boolean sectionOff() {
      return this.master != null && !this.master.get();
   }

   /** Its key in the config file (gpuNoiseFill). */
   public String key() {
      java.util.List<String> path = this.raw.getPath();
      return path.isEmpty() ? "?" : path.get(path.size() - 1);
   }
}
