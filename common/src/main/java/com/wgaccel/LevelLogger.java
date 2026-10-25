package com.wgaccel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * The mod's log (WorldgenAccelerator.LOG) at the config's debugInfoLevel: VERBOSE every line; NORMAL each kind of info
 * line (its text up to the first digit: "WGA GPU tile noise: ", "WGA " for the 10 s report) the first time and every 10th
 * time after, so one-off lines all stay and repeating ones come about ten times less; SILENT warnings and errors only.
 * Warnings and errors always go through.
 */
final class LevelLogger extends AbstractLogger {
   private static final int EVERY = 10;
   private final transient Logger delegate;
   private final Map<String, AtomicLong> seen = new ConcurrentHashMap<>();

   LevelLogger(Logger delegate) {
      this.delegate = delegate;
      this.name = delegate.getName();
   }

   private static Config.DebugInfoLevel level() {
      try {
         Setting<Config.DebugInfoLevel> setting = Config.DEBUG_INFO_LEVEL;
         // Null while Config itself is still being set up (a line logged from its own start).
         return setting == null ? Config.DebugInfoLevel.VERBOSE : setting.get();
      } catch (RuntimeException | LinkageError early) {
         return Config.DebugInfoLevel.VERBOSE;
      }
   }

   private boolean below(boolean enabled) {
      return enabled && level() != Config.DebugInfoLevel.SILENT;
   }

   @Override
   public boolean isTraceEnabled() {
      return this.below(this.delegate.isTraceEnabled());
   }

   @Override
   public boolean isTraceEnabled(Marker marker) {
      return this.below(this.delegate.isTraceEnabled(marker));
   }

   @Override
   public boolean isDebugEnabled() {
      return this.below(this.delegate.isDebugEnabled());
   }

   @Override
   public boolean isDebugEnabled(Marker marker) {
      return this.below(this.delegate.isDebugEnabled(marker));
   }

   @Override
   public boolean isInfoEnabled() {
      return this.below(this.delegate.isInfoEnabled());
   }

   @Override
   public boolean isInfoEnabled(Marker marker) {
      return this.below(this.delegate.isInfoEnabled(marker));
   }

   @Override
   public boolean isWarnEnabled() {
      return this.delegate.isWarnEnabled();
   }

   @Override
   public boolean isWarnEnabled(Marker marker) {
      return this.delegate.isWarnEnabled(marker);
   }

   @Override
   public boolean isErrorEnabled() {
      return this.delegate.isErrorEnabled();
   }

   @Override
   public boolean isErrorEnabled(Marker marker) {
      return this.delegate.isErrorEnabled(marker);
   }

   @Override
   protected String getFullyQualifiedCallerName() {
      return null;
   }

   @Override
   protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments, Throwable throwable) {
      if (level.toInt() < Level.WARN.toInt() && !this.wanted(pattern, arguments)) {
         return;
      }

      Object[] args = arguments == null ? new Object[0] : arguments;
      if (throwable != null) {
         args = java.util.Arrays.copyOf(args, args.length + 1);
         args[args.length - 1] = throwable;
      }

      switch (level) {
         case TRACE -> this.delegate.trace(marker, pattern, args);
         case DEBUG -> this.delegate.debug(marker, pattern, args);
         case INFO -> this.delegate.info(marker, pattern, args);
         case WARN -> this.delegate.warn(marker, pattern, args);
         case ERROR -> this.delegate.error(marker, pattern, args);
      }
   }

   /** Below warnings: whether this line is logged at the level in force. */
   private boolean wanted(String pattern, Object[] arguments) {
      Config.DebugInfoLevel level = level();
      if (level == Config.DebugInfoLevel.VERBOSE) {
         return true;
      }

      if (level == Config.DebugInfoLevel.SILENT) {
         return false;
      }

      String text = arguments == null || arguments.length == 0 ? pattern : MessageFormatter.basicArrayFormat(pattern, arguments);
      if (text == null) {
         return true;
      }

      int end = 0;
      while (end < text.length() && end < 60 && !Character.isDigit(text.charAt(end))) {
         end++;
      }

      if (this.seen.size() > 4096) {
         // Kinds made of one-off texts without digits: started over rather than kept forever.
         this.seen.clear();
      }

      long count = this.seen.computeIfAbsent(text.substring(0, end), kind -> new AtomicLong()).incrementAndGet();
      return count == 1 || count % EVERY == 0;
   }
}
