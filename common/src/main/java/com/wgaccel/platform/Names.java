package com.wgaccel.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Minecraft's classes, fields and methods by their Mojang names, whatever names the game runs with. The mod reads
 * Minecraft's objects by name (the GPU code generators walk density functions, surface rules and noises field by field
 * and choose code by class name), and NeoForge runs Minecraft with Mojang's names, but a Fabric game runs it with
 * Fabric's own (intermediary: DensityFunctions$Ap2 is class_6916$class_6917 there, NoiseRouter's barrierNoise comp_414).
 * Fabric's jar carries the table between the two (wgaccel-names.tsv.gz, made from Loom's mappings when it is built);
 * with it every name read here is turned into Mojang's, and every Mojang name looked up into the game's. Without it
 * (NeoForge, Fabric's development runs, the offline checks) each call here is the plain reflection call it stands for.
 */
public final class Names {
   private static final String TABLE = "/wgaccel-names.tsv.gz";
   /** Whether the game runs with names other than Mojang's (the table is there). */
   public static final boolean REMAPPED;
   /** Runtime class name (dots) to Mojang's, and back. */
   private static final Map<String, String> CLASSES = new HashMap<>();
   private static final Map<String, String> CLASSES_BACK = new HashMap<>();
   /** Runtime field and method name (field_, method_, comp_: each one names one member) to Mojang's. */
   private static final Map<String, String> MEMBERS = new HashMap<>();
   /** The few runtime member names two Mojang names share, by owner: "class_1#field_2". */
   private static final Map<String, String> OWNED = new HashMap<>();
   private static final Pattern RUNTIME_CLASS = Pattern.compile("net\\.minecraft\\.class_\\d+(?:\\$class_\\d+)*");
   private static final ClassValue<String> CLASS_NAMES = new ClassValue<>() {
      @Override
      protected String computeValue(Class<?> type) {
         return translate(type.getName());
      }
   };
   private static final ClassValue<String> SIMPLE_NAMES = new ClassValue<>() {
      @Override
      protected String computeValue(Class<?> type) {
         if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
         }

         String name = className(type);
         if (name.equals(type.getName()) || type.isAnonymousClass()) {
            return type.getSimpleName();
         }

         String simple = name.substring(name.lastIndexOf('.') + 1);
         return type.isHidden() || type.getEnclosingClass() == null ? simple : simple.substring(simple.lastIndexOf('$') + 1);
      }
   };

   static {
      boolean loaded = false;
      try (InputStream in = Names.class.getResourceAsStream(TABLE)) {
         if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
               String[] parts = line.split("\t");
               switch (parts[0]) {
                  case "c" -> {
                     String runtime = parts[1].replace('/', '.');
                     String mojang = parts[2].replace('/', '.');
                     CLASSES.put(runtime, mojang);
                     CLASSES_BACK.put(mojang, runtime);
                  }
                  case "m" -> MEMBERS.put(parts[1], parts[2]);
                  case "o" -> OWNED.put(parts[1].replace('/', '.') + "#" + parts[2], parts[3]);
                  default -> {
                  }
               }
            }

            loaded = true;
         }
      } catch (IOException | RuntimeException unreadable) {
         // Treated as no table: every name as the game has it.
      }

      REMAPPED = loaded;
   }

   private Names() {
   }

   /** Every runtime class name in the text turned into Mojang's ("net.minecraft.class_6916$class_6917$1" too). */
   private static String translate(String text) {
      if (!REMAPPED || text.indexOf("class_") < 0) {
         return text;
      }

      Matcher matcher = RUNTIME_CLASS.matcher(text);
      StringBuilder out = new StringBuilder();
      while (matcher.find()) {
         matcher.appendReplacement(out, Matcher.quoteReplacement(CLASSES.getOrDefault(matcher.group(), matcher.group())));
      }

      return matcher.appendTail(out).toString();
   }

   /** The class's name as Class.getName() gives it with Mojang's names. */
   public static String className(Class<?> type) {
      return REMAPPED ? CLASS_NAMES.get(type) : type.getName();
   }

   /** The class's name as Class.getSimpleName() gives it with Mojang's names. */
   public static String simpleName(Class<?> type) {
      return REMAPPED ? SIMPLE_NAMES.get(type) : type.getSimpleName();
   }

   /** A field's or method's Mojang name. */
   public static String name(Member member) {
      return REMAPPED ? memberName(member.getDeclaringClass(), member.getName()) : member.getName();
   }

   /** A record component's Mojang name. */
   public static String name(RecordComponent component) {
      return REMAPPED ? memberName(component.getDeclaringRecord(), component.getName()) : component.getName();
   }

   private static String memberName(Class<?> owner, String name) {
      if (!name.startsWith("field_") && !name.startsWith("method_") && !name.startsWith("comp_")) {
         return name;
      }

      String owned = OWNED.isEmpty() ? null : OWNED.get(owner.getName() + "#" + name);
      return owned != null ? owned : MEMBERS.getOrDefault(name, name);
   }

   /** Class.forName by the Mojang name. */
   public static Class<?> forName(String name) throws ClassNotFoundException {
      return Class.forName(runtimeClass(name));
   }

   /** Class.forName(name, initialize, loader) by the Mojang name. */
   public static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
      return Class.forName(runtimeClass(name), initialize, loader);
   }

   private static String runtimeClass(String name) {
      return REMAPPED ? CLASSES_BACK.getOrDefault(name, name) : name;
   }

   /** type.getDeclaredField by the Mojang name. */
   public static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
      if (REMAPPED) {
         for (Field field : type.getDeclaredFields()) {
            if (name(field).equals(name)) {
               return field;
            }
         }
      }

      return type.getDeclaredField(name);
   }

   /** type.getField (public, inherited too) by the Mojang name. */
   public static Field field(Class<?> type, String name) throws NoSuchFieldException {
      if (REMAPPED) {
         for (Field field : type.getFields()) {
            if (name(field).equals(name)) {
               return field;
            }
         }
      }

      return type.getField(name);
   }

   /** type.getDeclaredMethod by the Mojang name. */
   public static Method declaredMethod(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
      if (REMAPPED) {
         Method found = pick(type.getDeclaredMethods(), name, parameters);
         if (found != null) {
            return found;
         }
      }

      return type.getDeclaredMethod(name, parameters);
   }

   /** type.getMethod (public, inherited too) by the Mojang name. */
   public static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
      if (REMAPPED) {
         Method found = pick(type.getMethods(), name, parameters);
         if (found != null) {
            return found;
         }
      }

      return type.getMethod(name, parameters);
   }

   /** The method of that Mojang name and those parameters, a bridge only when there is nothing else (as getMethod chooses). */
   private static Method pick(Method[] methods, String name, Class<?>[] parameters) {
      Method bridge = null;
      for (Method method : methods) {
         if (Arrays.equals(method.getParameterTypes(), parameters) && name(method).equals(name)) {
            if (!method.isBridge()) {
               return method;
            }

            bridge = method;
         }
      }

      return bridge;
   }
}
