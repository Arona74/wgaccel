package com.wgaccel.client;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Voxy Server Side's client (VSS, or LOD Server Support: MIT, dev.vox.lss), read by reflection for the Voxy LOD panel
 * on a server: whether the server sends LODs, how fast they come, how far they reach, and why they do not. Sampled
 * twice a second (the panel and F3 ask every frame). Null when VSS is not installed, in singleplayer (VSS waits for
 * LAN there; our own generator's panel shows instead) and before joining a world.
 *
 * <p>Read from VSS 0.22: ClientStatus.latest() (the connection state and what holds it), ClientNetGlue.getRequestManager()
 * (the session's numbers) and Brand.clientCommand(). A getter VSS renames later leaves its part of the panel out.
 */
public final class VssStatus {
   /** What the panel says first. */
   public enum State {
      /** Columns are coming in or asked for. */
      RECEIVING,
      /** Connected, nothing asked for, the ring short of the LOD distance. */
      IDLE,
      /** Connected, the whole LOD distance confirmed. */
      UP_TO_DATE,
      /** Joining: the handshake not answered yet. */
      WAITING,
      /** This server sends no LODs (the reason in why). */
      NO_LODS
   }

   /**
    * One sample. Rates in columns a second; ring and distance in chunks (-1 when not known); held: the columns VSS's
    * cache says the client has (from earlier sessions too); session: the columns received since joining; heldAtJoin:
    * the ones known before joining. pending: columns asked for, not answered yet; requestRate: columns asked for a
    * second (answers "up to date" included). pingMs: the network ping; answerMs: ask to column (median, server work
    * included). why: the state's reason or hint; warning: a problem in red, or empty.
    */
   public record View(State state, String why, String warning, double receiveRate, double requestRate, int pending, int ring, int distance,
                      long held, long session, long heldAtJoin, double pingMs, double answerMs, String command) {
      /** The ring's share of the LOD distance's area (what is covered). */
      public double fraction() {
         if (this.state == State.UP_TO_DATE) {
            return 1.0;
         }

         if (this.ring < 0 || this.distance <= 0) {
            return 0.0;
         }

         double f = Math.min(1.0, (double)this.ring / this.distance);
         return f * f;
      }
   }

   /** A server without VSS (most servers): said in F3 only, no panel. */
   public static final String NO_VSS = "the server does not run VSS";
   private static final long SAMPLE_NANOS = 500_000_000L;
   /** Ping at which VSS's server may slow its sends (its ping_backstop setting). */
   private static final double HIGH_PING_MS = 150.0;
   private static volatile long sampledAt;
   private static volatile View sample;
   private static boolean missing;
   private static Method latest;
   private static Method requestManager;
   private static Method clientCommand;
   /** Rate-gate count at the last sample: a rise means VSS's client cap held requests back. */
   private static long lastRateGated = -1;
   private static Object lastManager;

   private VssStatus() {
   }

   /** The last sample, or null (no VSS, singleplayer, no world). */
   public static View current() {
      long now = System.nanoTime();
      if (sampledAt == 0L || now - sampledAt >= SAMPLE_NANOS) {
         sampledAt = now;
         try {
            sample = read();
         } catch (Throwable unknown) {
            // A VSS this was not written for: no panel rather than a broken HUD.
            sample = null;
         }
      }

      return sample;
   }

   private static boolean bind() {
      if (missing) {
         return false;
      }

      if (latest != null) {
         return true;
      }

      try {
         ClassLoader loader = VssStatus.class.getClassLoader();
         latest = Class.forName("dev.vox.lss.networking.client.ClientStatus", false, loader).getMethod("latest");
         requestManager = Class.forName("dev.vox.lss.networking.client.ClientNetGlue", false, loader).getMethod("getRequestManager");
         try {
            clientCommand = Class.forName("dev.vox.lss.common.Brand", false, loader).getMethod("clientCommand");
         } catch (ReflectiveOperationException old) {
            clientCommand = null;
         }

         return true;
      } catch (ReflectiveOperationException | LinkageError absent) {
         missing = true;
         return false;
      }
   }

   private static View read() throws ReflectiveOperationException {
      if (!bind()) {
         return null;
      }

      Object snapshot = latest.invoke(null);
      if (snapshot == null) {
         return null;
      }

      String discovery = String.valueOf(call(snapshot, "discovery"));
      if (discovery.equals("NOT_CONNECTED") || discovery.equals("LOCAL") || discovery.equals("null")) {
         lastManager = null;
         return null;
      }

      String command = "/" + (clientCommand != null ? clientCommand.invoke(null) : "vss");
      switch (discovery) {
         case "NO_CHANNEL":
            return idle(State.NO_LODS, NO_VSS, command);
         case "NO_ANSWER":
            return idle(State.NO_LODS, "the server's VSS does not answer", command);
         case "PROTOCOL_REJECTED":
            return idle(State.NO_LODS, "VSS versions differ: the server refused this client's protocol", command);
         case "SEND_FAILED":
            return idle(State.NO_LODS, "the VSS handshake could not be sent", command);
         case "AWAITING_NEGOTIATION":
            return idle(State.WAITING, "waiting for the server's VSS handshake", command);
         default:
            break;
      }

      if (!bool(snapshot, "receptionEnabled", true)) {
         return idle(State.NO_LODS, "receiving LODs is off in VSS's client settings", command);
      }

      if (!bool(snapshot, "consumerAvailable", true)) {
         return idle(State.NO_LODS, "nothing to show the LODs in (is Voxy installed?)", command);
      }

      if (!bool(snapshot, "serverEnabled", true)) {
         return idle(State.NO_LODS, "the server has its LODs turned off", command);
      }

      if (discovery.equals("DORMANT")) {
         return idle(State.IDLE, "connected, not asking (receiving off, or another LOD source chosen)", command);
      }

      Object manager = requestManager.invoke(null);
      if (manager == null) {
         return idle(State.WAITING, "waiting for the server's LOD settings", command);
      }

      if (manager != lastManager) {
         lastManager = manager;
         lastRateGated = -1;
      }

      double receive = number(manager, "getReceiveRate");
      double request = number(manager, "getRequestRate");
      int pending = (int)number(manager, "getPendingCount");
      int ring = (int)number(manager, "getConfirmedRing");
      int distance = (int)number(manager, "getEffectiveLodDistanceChunks");
      if (distance <= 0) {
         distance = (int)number(snapshot, "effectiveDistance");
      }

      long held = (long)number(manager, "getReceivedColumnCount");
      long session = (long)number(manager, "getTotalColumnsReceived");
      long heldAtJoin = held >= 0 && session >= 0 ? Math.max(0, held - session) : -1;
      // VSS's round trip is ask to column received: the server's work on the column included, not the network.
      double answerMs = number(manager, "getRttP50Ms");
      double ping = networkPing();
      long failures = (long)number(manager, "getTotalIngestFailures");
      long gated = (long)number(manager, "getRateGated");
      boolean capped = lastRateGated >= 0 && gated > lastRateGated;
      lastRateGated = gated;

      State state;
      String why;
      if (receive >= 0.5 || pending > 0) {
         state = State.RECEIVING;
         why = capped ? "limited by VSS's client rate cap"
            : receive < 1.0 && pending > 0 ? "waiting for the server's answers (its first LODs can take a minute)" : "";
      } else if (distance > 0 && ring >= distance) {
         state = State.UP_TO_DATE;
         why = "";
      } else {
         state = State.IDLE;
         why = capped ? "VSS's client rate cap holds the requests back" : "";
      }

      if (ping >= HIGH_PING_MS && state != State.UP_TO_DATE) {
         why = String.format(Locale.ROOT, "ping %.0f ms: the server may slow its sends (ping_backstop)", ping);
      }

      String warning = failures > 0 ? String.format(Locale.ROOT, "%,d columns Voxy did not take", failures) : "";
      return new View(state, why, warning, receive, request, pending, ring, distance, held, session, heldAtJoin, ping, answerMs, command);
   }

   private static View idle(State state, String why, String command) {
      return new View(state, why, "", 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, command);
   }

   /** The player's ping to the server as the player list has it (ms), or -1. */
   private static double networkPing() {
      try {
         net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
         if (minecraft.player == null || minecraft.getConnection() == null) {
            return -1;
         }

         net.minecraft.client.multiplayer.PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
         return info == null ? -1 : info.getLatency();
      } catch (RuntimeException | LinkageError unknown) {
         return -1;
      }
   }

   private static Object call(Object target, String name) {
      try {
         return target.getClass().getMethod(name).invoke(target);
      } catch (ReflectiveOperationException | RuntimeException unknown) {
         return null;
      }
   }

   private static boolean bool(Object target, String name, boolean otherwise) {
      return call(target, name) instanceof Boolean value ? value : otherwise;
   }

   /** A number getter's value, or -1 when VSS has no such getter. */
   private static double number(Object target, String name) {
      return call(target, name) instanceof Number value ? value.doubleValue() : -1;
   }
}
