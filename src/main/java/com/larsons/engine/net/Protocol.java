package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire protocol for online play (requirement #3): newline-delimited
 * compact JSON over TCP, using the engine's own {@link Json} — so networking,
 * like everything else, needs nothing beyond the JDK and stays trivially
 * debuggable ({@code telnet <host> <port>} shows the whole conversation).
 *
 * <p>Message flow (Minecraft-style direct connect — the client dials
 * {@code host:port}):
 * <pre>
 *   client -> server   {"t":"join","v":1,"name":"Larson"}
 *   server -> client   {"t":"welcome","id":1,"tickRate":60,"profile":{...},"level":"&lt;level json&gt;"}
 *                      (or {"t":"error","msg":"..."} and the connection closes)
 *   client -> server   {"t":"input","s":42,"l":false,"r":true,"u":false,"d":false}   (each tick)
 *   server -> client   {"t":"state","tick":1234,"players":[{...},{...}]}             (snapshots)
 *   server -> client   {"t":"info","msg":"Larson joined"}                            (events)
 *   client -> server   {"t":"ping","p":123456}    ->    {"t":"pong","p":123456}
 * </pre>
 *
 * <p>The server is authoritative: clients send only input commands, the server
 * runs {@link com.larsons.engine.sim.PlayerPhysics} at a fixed tick and
 * broadcasts state snapshots — the seam the engine's fixed-timestep loop was
 * designed around. The welcome message carries the host's full game type and
 * level, so joining clients play exactly the world the host configured without
 * having the files locally.
 */
public final class Protocol {

    /** Protocol version; bumped on incompatible changes. */
    public static final int VERSION = 1;

    /** Default server port (the engine's "25565"). */
    public static final int DEFAULT_PORT = 7777;

    /** Sanity cap on a single message line, to shrug off garbage input. */
    public static final int MAX_LINE_LENGTH = 256 * 1024;

    public static final int MAX_NAME_LENGTH = 24;

    private Protocol() {}

    public static String encode(Map<String, Object> message) {
        return Json.stringifyCompact(message);
    }

    /** Parse one message line; returns {@code null} if it isn't a JSON object. */
    public static Map<String, Object> decode(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE_LENGTH) return null;
        try {
            Object parsed = Json.parse(line);
            return parsed instanceof Map<?, ?> ? Json.asObject(parsed) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String type(Map<String, Object> message) {
        return message.get("t") instanceof String s ? s : "";
    }

    // --- message builders ------------------------------------------------------

    public static String join(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "join");
        m.put("v", VERSION);
        m.put("name", sanitizeName(name));
        return encode(m);
    }

    public static String welcome(int id, int tickRate, GameProfile profile, String levelJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "welcome");
        m.put("id", id);
        m.put("tickRate", tickRate);
        m.put("profile", profile.toMap());
        m.put("level", levelJson);
        return encode(m);
    }

    public static String error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "error");
        m.put("msg", message);
        return encode(m);
    }

    public static String input(PlayerInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "input");
        m.putAll(in.toMap());
        return encode(m);
    }

    public static String state(long tick, List<PlayerState> players) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "state");
        m.put("tick", tick);
        List<Object> list = new ArrayList<>(players.size());
        for (PlayerState p : players) list.add(p.toMap());
        m.put("players", list);
        return encode(m);
    }

    public static String info(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "info");
        m.put("msg", message);
        return encode(m);
    }

    public static String ping(long payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "ping");
        m.put("p", payload);
        return encode(m);
    }

    public static String pong(long payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "pong");
        m.put("p", payload);
        return encode(m);
    }

    // --- helpers ---------------------------------------------------------------

    public static String sanitizeName(String name) {
        String n = name == null ? "" : name.strip().replaceAll("[\\p{Cntrl}]", "");
        if (n.isEmpty()) n = "Player";
        return n.length() > MAX_NAME_LENGTH ? n.substring(0, MAX_NAME_LENGTH) : n;
    }

    /**
     * Parse a Minecraft-style server address: {@code host} or {@code host:port}.
     * Returns {@code [host, portString]} with the default port filled in.
     */
    public static String[] splitAddress(String address) {
        String a = address == null ? "" : address.strip();
        if (a.isEmpty()) a = "localhost";
        int colon = a.lastIndexOf(':');
        if (colon > 0 && colon == a.indexOf(':')) { // exactly one ':' -> host:port
            return new String[]{a.substring(0, colon), a.substring(colon + 1)};
        }
        return new String[]{a, Integer.toString(DEFAULT_PORT)};
    }
}
