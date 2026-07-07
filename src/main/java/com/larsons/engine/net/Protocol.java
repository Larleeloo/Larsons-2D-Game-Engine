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
 *   server -> client   {"t":"state","tick":1234,"players":[...],"mobs":[...],
 *                       "items":[...],"time":0.42}                                   (snapshots)
 *   server -> client   {"t":"info","msg":"Larson joined"}                            (events)
 *   client -> server   {"t":"ping","p":123456}    ->    {"t":"pong","p":123456}
 *
 *   // world editing (creative painting + play-mode mining/placing):
 *   client -> server   {"t":"edit","c":col,"r":row,"b":blockId,"m":"paint"|"play"}
 *   server -> all      {"t":"block","c":col,"r":row,"b":blockId}   (authoritative result)
 *   client -> server   {"t":"paint","k":"mob"|"item","e":"zombie","x":..,"y":..}
 *   client -> server   {"t":"erase","id":entityId}
 *
 *   // inventory actions (the server owns each player's inventory; it answers
 *   // every request with an {"t":"inv","items":[...]} push):
 *   client -> server   {"t":"invmove","a":fromSlot,"b":toSlot}    (move/merge/swap)
 *   client -> server   {"t":"invdrop","i":slot,"n":count}         (drop into the world)
 *   client -> server   {"t":"use","i":slot}                       (eat food / drink potion)
 *
 *   // projectile impact feedback (particles + sfx on every client):
 *   server -> all      {"t":"fx","k":"arrow","x":..,"y":..,"e":true|false}
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
    public static final int VERSION = 3;

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
        return state(tick, players, List.of(), List.of(), List.of(), 0.25);
    }

    /**
     * A full snapshot: players plus replicated world entities (mobs, dropped
     * items, projectiles in flight — already in wire-map form from
     * {@code Mob.toMap()} etc.) and the time of day driving the clients'
     * lighting.
     */
    public static String state(long tick, List<PlayerState> players,
                               List<Map<String, Object>> mobs,
                               List<Map<String, Object>> items,
                               List<Map<String, Object>> shots,
                               double timeOfDay) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "state");
        m.put("tick", tick);
        List<Object> list = new ArrayList<>(players.size());
        for (PlayerState p : players) list.add(p.toMap());
        m.put("players", list);
        if (!mobs.isEmpty()) m.put("mobs", new ArrayList<Object>(mobs));
        if (!items.isEmpty()) m.put("items", new ArrayList<Object>(items));
        if (!shots.isEmpty()) m.put("shots", new ArrayList<Object>(shots));
        m.put("time", timeOfDay);
        return encode(m);
    }

    /** Client asks to change a block; {@code mode} is "paint" or "play". */
    public static String blockEdit(int col, int row, int blockId, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "edit");
        m.put("c", col);
        m.put("r", row);
        m.put("b", blockId);
        m.put("m", mode);
        return encode(m);
    }

    /** Server tells everyone a block changed (the authoritative result). */
    public static String blockSet(int col, int row, int blockId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "block");
        m.put("c", col);
        m.put("r", row);
        m.put("b", blockId);
        return encode(m);
    }

    /** Client paints an entity (creative mode): kind "mob" or "item". */
    public static String entityPaint(String kind, String type, double x, double y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "paint");
        m.put("k", kind);
        m.put("e", type);
        m.put("x", x);
        m.put("y", y);
        return encode(m);
    }

    /** Client erases a painted entity by id (creative mode). */
    public static String entityErase(int entityId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "erase");
        m.put("id", entityId);
        return encode(m);
    }

    /** Client asks to move/merge/swap an inventory stack between two slots. */
    public static String invMove(int fromSlot, int toSlot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "invmove");
        m.put("a", fromSlot);
        m.put("b", toSlot);
        return encode(m);
    }

    /** Client asks to drop {@code count} items from a slot into the world. */
    public static String invDrop(int slot, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "invdrop");
        m.put("i", slot);
        m.put("n", count);
        return encode(m);
    }

    /** Client asks to consume the item in a slot (eat food, drink a potion). */
    public static String useItem(int slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "use");
        m.put("i", slot);
        return encode(m);
    }

    /** Server tells everyone a projectile impacted (feedback: particles, sfx). */
    public static String fx(String projectileKey, double x, double y, boolean explosion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "fx");
        m.put("k", projectileKey);
        m.put("x", x);
        m.put("y", y);
        m.put("e", explosion);
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
