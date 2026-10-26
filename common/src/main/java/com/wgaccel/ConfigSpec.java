package com.wgaccel;

import com.wgaccel.platform.Platform;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's own config file (config/wgaccel-common.toml), the same on NeoForge and Fabric: a builder with the calls
 * NeoForge's ModConfigSpec had (comment, push, pop, define, defineInRange, defineEnum, defineListAllowEmpty) and a file in
 * the layout NeoForge wrote, so a file NeoForge wrote reads the same. Loaded on the first read of a value; a value missing,
 * of the wrong kind or out of its range is put right (a number clamped into its range, anything else its default) and
 * the file written again. The file is watched: edited while the game runs, it is read again.
 */
public final class ConfigSpec {
   /** The mod's logger by name, not WorldgenAccelerator.LOG: that class names game classes, and the config loads before them. */
   private static final Logger LOG = LoggerFactory.getLogger("Worldgen Accelerator");
   /** How often the watcher looks at the file's time (NeoForge's watcher saw a change within about a second too). */
   private static final long WATCH_MILLIS = 2000L;
   private final String fileName;
   /** The tables and values in the order they were defined: the file's order. */
   private final List<Object> nodes;
   private final List<ConfigValue<?>> values = new ArrayList<>();
   private volatile boolean loaded;
   private Path file;
   private long writtenTime;
   private Runnable onLoad = () -> {
   };

   private ConfigSpec(String fileName, List<Object> nodes) {
      this.fileName = fileName;
      this.nodes = nodes;
      for (Object node : nodes) {
         if (node instanceof ConfigValue<?> value) {
            value.spec = this;
            this.values.add(value);
         }
      }
   }

   /** Called after every load, the first and each one after an edit of the file (the settings' caches go stale). */
   public void onLoad(Runnable listener) {
      this.onLoad = listener;
   }

   private void ensureLoaded() {
      if (!this.loaded) {
         synchronized (this) {
            if (!this.loaded) {
               this.file = Platform.get().configDir().resolve(this.fileName);
               this.load();
               this.loaded = true;
               this.watch();
            }
         }
      }
   }

   /** Reads the file (missing: all defaults), puts wrong values right and writes the file again when anything was put right. */
   private synchronized void load() {
      Map<String, Object> read = new HashMap<>();
      boolean exists = Files.exists(this.file);
      if (exists) {
         try {
            read = Toml.read(Files.readString(this.file, StandardCharsets.UTF_8));
         } catch (IOException | RuntimeException failure) {
            LOG.warn("WGA config: {} could not be read ({}); the defaults are used and the file written again", this.file, failure.toString());
            read = new HashMap<>();
         }
      }

      List<String> corrected = new ArrayList<>();
      for (ConfigValue<?> value : this.values) {
         if (!value.accept(read.remove(value.dottedPath()))) {
            corrected.add(value.dottedPath());
         }
      }

      if (!exists || !corrected.isEmpty() || !read.isEmpty()) {
         if (exists) {
            LOG.warn("WGA config: {} put right (missing or wrong: {}; unknown, dropped: {})", this.fileName, corrected, read.keySet());
         }

         this.save();
      } else {
         this.writtenTime = this.fileTime();
      }

      this.onLoad.run();
   }

   /** Writes every value to the file, with its comments (atomic: a game stopped mid-write leaves the old file). */
   synchronized void save() {
      try {
         Files.createDirectories(this.file.getParent());
         Path temporary = this.file.resolveSibling(this.fileName + ".tmp");
         Files.writeString(temporary, this.text(), StandardCharsets.UTF_8);
         try {
            Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException notOnThisDisk) {
            Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
         }

         this.writtenTime = this.fileTime();
      } catch (IOException failure) {
         LOG.warn("WGA config: {} could not be written ({})", this.file, failure.toString());
      }
   }

   private long fileTime() {
      try {
         return Files.getLastModifiedTime(this.file).toMillis();
      } catch (IOException missing) {
         return 0L;
      }
   }

   /** A daemon thread reading the file again when its time changes and the change is not the mod's own write. */
   private void watch() {
      Thread watcher = new Thread(() -> {
         while (true) {
            try {
               Thread.sleep(WATCH_MILLIS);
            } catch (InterruptedException stop) {
               return;
            }

            synchronized (this) {
               long time = this.fileTime();
               if (time != 0L && time != this.writtenTime) {
                  LOG.info("WGA config: {} changed; read again", this.fileName);
                  this.load();
               }
            }
         }
      }, "WGA config watcher");
      watcher.setDaemon(true);
      watcher.start();
   }

   /** The file's text, as NeoForge laid it out: a tab per table level, "#" comments, a blank line before each table. */
   private String text() {
      StringBuilder out = new StringBuilder();
      for (Object node : this.nodes) {
         if (node instanceof Table table) {
            out.append('\n');
            String indent = "\t".repeat(table.path.size() - 1);
            for (String line : table.comment) {
               out.append(indent).append('#').append(line).append('\n');
            }

            out.append(indent).append('[').append(String.join(".", table.path)).append("]\n");
         } else {
            ConfigValue<?> value = (ConfigValue<?>)node;
            String indent = "\t".repeat(value.path.size() - 1);
            for (String line : value.comment) {
               out.append(indent).append('#').append(line).append('\n');
            }

            for (String line : value.notes()) {
               out.append(indent).append(line).append('\n');
            }

            out.append(indent).append(value.path.get(value.path.size() - 1)).append(" = ").append(Toml.write(value.value)).append('\n');
         }
      }

      return out.toString();
   }

   public static final class Builder {
      private final List<Object> nodes = new ArrayList<>();
      private final List<String> path = new ArrayList<>();
      private String[] comment = new String[0];

      /** The comment of the next value or table. */
      public Builder comment(String... lines) {
         this.comment = lines;
         return this;
      }

      /** Starts a table ([name], or [outer.name] inside another), with the comment given just before. */
      public Builder push(String name) {
         this.path.add(name);
         this.nodes.add(new Table(List.copyOf(this.path), this.takeComment()));
         return this;
      }

      public Builder pop() {
         this.path.remove(this.path.size() - 1);
         return this;
      }

      public <T> ConfigValue<T> define(String key, T defaultValue) {
         return this.add(new ConfigValue<>(this.keyPath(key), this.takeComment(), defaultValue));
      }

      public ConfigValue<Integer> defineInRange(String key, int defaultValue, int min, int max) {
         return this.add(new Ranged<>(this.keyPath(key), this.takeComment(), defaultValue, min, max));
      }

      public ConfigValue<Double> defineInRange(String key, double defaultValue, double min, double max) {
         return this.add(new Ranged<>(this.keyPath(key), this.takeComment(), defaultValue, min, max));
      }

      public <E extends Enum<E>> EnumValue<E> defineEnum(String key, E defaultValue) {
         return this.add(new EnumValue<>(this.keyPath(key), this.takeComment(), defaultValue));
      }

      /** A list (of strings here); the whole list is its default when an element fails the check. */
      public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String key, List<? extends T> defaultValue, Supplier<T> newElement, Predicate<Object> check) {
         return this.add(new ListValue<>(this.keyPath(key), this.takeComment(), defaultValue, check));
      }

      public ConfigSpec build() {
         return new ConfigSpec("wgaccel-common.toml", List.copyOf(this.nodes));
      }

      private <V extends ConfigValue<?>> V add(V value) {
         this.nodes.add(value);
         return value;
      }

      private List<String> keyPath(String key) {
         List<String> path = new ArrayList<>(this.path);
         path.add(key);
         return List.copyOf(path);
      }

      private List<String> takeComment() {
         List<String> lines = List.of(this.comment);
         this.comment = new String[0];
         return lines;
      }
   }

   private record Table(List<String> path, List<String> comment) {
   }

   /** One value in the file; read with {@link #get()} (the first read loads the file). */
   public static class ConfigValue<T> implements Supplier<T> {
      final List<String> path;
      final List<String> comment;
      private final T defaultValue;
      private volatile T value;
      private ConfigSpec spec;

      ConfigValue(List<String> path, List<String> comment, T defaultValue) {
         this.path = path;
         this.comment = comment;
         this.defaultValue = defaultValue;
         this.value = defaultValue;
      }

      @Override
      public T get() {
         this.spec.ensureLoaded();
         return this.value;
      }

      /** Changes the value (written to the file by {@link #save()}). */
      public void set(T value) {
         this.spec.ensureLoaded();
         this.value = value;
      }

      public void save() {
         this.spec.save();
      }

      public T getDefault() {
         return this.defaultValue;
      }

      /** A number's lowest allowed value (the settings screen's boxes), or null for other values. */
      public Object min() {
         return null;
      }

      /** A number's highest allowed value, or null for other values. */
      public Object max() {
         return null;
      }

      /** Its table names and its key: [gpu, tuning, gpuQueueOpenBelow]. */
      public List<String> getPath() {
         return this.path;
      }

      String dottedPath() {
         return String.join(".", this.path);
      }

      /** The lines NeoForge wrote after the comment ("# Default: 0", "# Range: -1 ~ 65536"). */
      List<String> notes() {
         return List.of();
      }

      /** Takes the file's value (null: not in the file); false when it was missing or wrong and was put right. */
      boolean accept(Object raw) {
         T converted = raw == null ? null : this.convert(raw);
         this.store(converted);
         return converted != null && converted.equals(raw);
      }

      /** Keeps a value already converted (null: the default). */
      void store(T converted) {
         this.value = converted != null ? converted : this.defaultValue;
      }

      /** The file's value as this value's type, or null when it is not one. */
      @SuppressWarnings("unchecked")
      T convert(Object raw) {
         return this.defaultValue.getClass().isInstance(raw) ? (T)raw : null;
      }
   }

   /** An int or a double with a range; a number outside it is clamped into it. */
   private static final class Ranged<N extends Number & Comparable<N>> extends ConfigValue<N> {
      private final N min;
      private final N max;

      Ranged(List<String> path, List<String> comment, N defaultValue, N min, N max) {
         super(path, comment, defaultValue);
         this.min = min;
         this.max = max;
      }

      @Override
      List<String> notes() {
         return List.of("# Default: " + Toml.write(this.getDefault()), "# Range: " + Toml.write(this.min) + " ~ " + Toml.write(this.max));
      }

      @Override
      public Object min() {
         return this.min;
      }

      @Override
      public Object max() {
         return this.max;
      }

      @Override
      boolean accept(Object raw) {
         N number = raw == null ? null : this.convert(raw);
         if (number == null) {
            this.store(null);
            return false;
         }

         N clamped = number.compareTo(this.min) < 0 ? this.min : number.compareTo(this.max) > 0 ? this.max : number;
         this.store(clamped);
         return clamped == number && (this.getDefault() instanceof Double ? raw instanceof Double : raw instanceof Long);
      }

      @Override
      @SuppressWarnings("unchecked")
      N convert(Object raw) {
         if (this.getDefault() instanceof Integer) {
            return raw instanceof Long whole && whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? (N)Integer.valueOf(whole.intValue()) : null;
         }

         return raw instanceof Number number ? (N)Double.valueOf(number.doubleValue()) : null;
      }
   }

   /** An enum, written as its name in quotes; read whatever its case. */
   public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {
      EnumValue(List<String> path, List<String> comment, E defaultValue) {
         super(path, comment, defaultValue);
      }

      @Override
      List<String> notes() {
         List<String> names = new ArrayList<>();
         for (E constant : this.getDefault().getDeclaringClass().getEnumConstants()) {
            names.add(constant.name());
         }

         return List.of("#Allowed Values: " + String.join(", ", names));
      }

      @Override
      boolean accept(Object raw) {
         E constant = raw == null ? null : this.convert(raw);
         this.store(constant);
         return constant != null && constant.name().equals(raw);
      }

      @Override
      E convert(Object raw) {
         if (raw instanceof String text) {
            for (E constant : this.getDefault().getDeclaringClass().getEnumConstants()) {
               if (constant.name().equalsIgnoreCase(text)) {
                  return constant;
               }
            }
         }

         return null;
      }
   }

   private static final class ListValue<T> extends ConfigValue<List<? extends T>> {
      private final Predicate<Object> check;

      ListValue(List<String> path, List<String> comment, List<? extends T> defaultValue, Predicate<Object> check) {
         super(path, comment, defaultValue);
         this.check = check;
      }

      @Override
      @SuppressWarnings("unchecked")
      List<? extends T> convert(Object raw) {
         if (!(raw instanceof List<?> list)) {
            return null;
         }

         for (Object element : list) {
            if (!this.check.test(element)) {
               return null;
            }
         }

         return List.copyOf((List<? extends T>)list);
      }
   }

   /** The part of TOML the file uses: tables, keys, strings, booleans, numbers and arrays of them. */
   static final class Toml {
      private Toml() {
      }

      /** Every value by its dotted path ("gpu.tuning.gpuQueueOpenBelow"). Throws on text it cannot read. */
      static Map<String, Object> read(String text) {
         if (text.startsWith("﻿")) {
            // A byte order mark (PowerShell's utf8): not part of the file's content.
            text = text.substring(1);
         }

         Map<String, Object> values = new LinkedHashMap<>();
         String table = "";
         String[] lines = text.split("\r?\n|\r", -1);
         for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
               continue;
            }

            if (line.startsWith("[")) {
               int end = line.indexOf(']');
               if (end < 0) {
                  throw new IllegalArgumentException("line " + (i + 1) + ": a table without its ]");
               }

               table = line.substring(1, end).strip();
               continue;
            }

            int equals = line.indexOf('=');
            if (equals <= 0) {
               throw new IllegalArgumentException("line " + (i + 1) + ": not key = value");
            }

            String key = unquote(line.substring(0, equals).strip());
            StringBuilder value = new StringBuilder(line.substring(equals + 1));
            // An array written over several lines: the next lines until its brackets close.
            while (!closed(value) && i + 1 < lines.length) {
               value.append('\n').append(lines[++i]);
            }

            Reader reader = new Reader(value.toString());
            Object parsed = reader.value();
            reader.end();
            values.put(table.isEmpty() ? key : table + "." + key, parsed);
         }

         return values;
      }

      private static String unquote(String key) {
         return key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'")) ? key.substring(1, key.length() - 1) : key;
      }

      /** Whether every [ in the text (outside strings and comments) is closed. */
      private static boolean closed(CharSequence text) {
         int depth = 0;
         char quote = 0;
         for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
               if (c == '\\' && quote == '"') {
                  i++;
               } else if (c == quote) {
                  quote = 0;
               }
            } else if (c == '"' || c == '\'') {
               quote = c;
            } else if (c == '#') {
               while (i < text.length() && text.charAt(i) != '\n') {
                  i++;
               }
            } else if (c == '[') {
               depth++;
            } else if (c == ']') {
               depth--;
            }
         }

         return depth <= 0;
      }

      static String write(Object value) {
         if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
               switch (c) {
                  case '"' -> out.append("\\\"");
                  case '\\' -> out.append("\\\\");
                  case '\n' -> out.append("\\n");
                  case '\t' -> out.append("\\t");
                  case '\r' -> out.append("\\r");
                  default -> {
                     if (c < 0x20) {
                        out.append(String.format("\\u%04X", (int)c));
                     } else {
                        out.append(c);
                     }
                  }
               }
            }

            return out.append('"').toString();
         } else if (value instanceof Enum<?> constant) {
            return write(constant.name());
         } else if (value instanceof Double number) {
            String plain = BigDecimal.valueOf(number).toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
         } else if (value instanceof List<?> list) {
            List<String> elements = new ArrayList<>();
            for (Object element : list) {
               elements.add(write(element));
            }

            return "[" + String.join(", ", elements) + "]";
         }

         return String.valueOf(value);
      }

      /** One value from its text: a string (Java String), true/false, a whole number (Long), a decimal (Double), an array (List). */
      private static final class Reader {
         private final String text;
         private int at;

         Reader(String text) {
            this.text = text;
         }

         Object value() {
            this.skip();
            if (this.at >= this.text.length()) {
               throw new IllegalArgumentException("a key without a value");
            }

            char c = this.text.charAt(this.at);
            if (c == '"' || c == '\'') {
               return this.string(c);
            } else if (c == '[') {
               this.at++;
               List<Object> list = new ArrayList<>();
               while (true) {
                  this.skip();
                  if (this.peek() == ']') {
                     this.at++;
                     return list;
                  }

                  list.add(this.value());
                  this.skip();
                  char next = this.peek();
                  this.at++;
                  if (next == ']') {
                     return list;
                  } else if (next != ',') {
                     throw new IllegalArgumentException("an array element not followed by , or ]");
                  }
               }
            }

            int start = this.at;
            while (this.at < this.text.length() && ",]#".indexOf(this.text.charAt(this.at)) < 0 && !Character.isWhitespace(this.text.charAt(this.at))) {
               this.at++;
            }

            String word = this.text.substring(start, this.at).replace("_", "");
            if (word.equals("true") || word.equals("false")) {
               return Boolean.valueOf(word);
            }

            try {
               return Long.valueOf(word.startsWith("+") ? word.substring(1) : word);
            } catch (NumberFormatException notWhole) {
               return Double.valueOf(word);
            }
         }

         private String string(char quote) {
            StringBuilder out = new StringBuilder();
            this.at++;
            while (this.at < this.text.length()) {
               char c = this.text.charAt(this.at++);
               if (c == quote) {
                  return out.toString();
               } else if (c == '\\' && quote == '"') {
                  char escaped = this.text.charAt(this.at++);
                  switch (escaped) {
                     case 'n' -> out.append('\n');
                     case 't' -> out.append('\t');
                     case 'r' -> out.append('\r');
                     case 'b' -> out.append('\b');
                     case 'f' -> out.append('\f');
                     case 'u' -> {
                        out.append((char)Integer.parseInt(this.text.substring(this.at, this.at + 4), 16));
                        this.at += 4;
                     }
                     default -> out.append(escaped);
                  }
               } else {
                  out.append(c);
               }
            }

            throw new IllegalArgumentException("a string without its closing quote");
         }

         /** Past spaces, line ends and comments. */
         private void skip() {
            while (this.at < this.text.length()) {
               char c = this.text.charAt(this.at);
               if (c == '#') {
                  while (this.at < this.text.length() && this.text.charAt(this.at) != '\n') {
                     this.at++;
                  }
               } else if (Character.isWhitespace(c)) {
                  this.at++;
               } else {
                  return;
               }
            }
         }

         private char peek() {
            if (this.at >= this.text.length()) {
               throw new IllegalArgumentException("an array without its ]");
            }

            return this.text.charAt(this.at);
         }

         /** Only a comment may follow the value on its line. */
         void end() {
            this.skip();
            if (this.at < this.text.length()) {
               throw new IllegalArgumentException("text after the value: " + this.text.substring(this.at).strip());
            }
         }
      }
   }
}
