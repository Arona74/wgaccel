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

   /** The value written in the config file. */
   public ModConfigSpec.ConfigValue<T> raw() {
      return this.raw;
   }
}
