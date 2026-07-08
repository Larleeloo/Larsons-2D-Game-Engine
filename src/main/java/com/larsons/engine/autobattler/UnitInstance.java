package com.larsons.engine.autobattler;

import com.larsons.engine.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A unit a player owns: a {@link UnitDef} reference plus star level, held
 * items, and where it sits — a bench slot, or a cell on the player's half of
 * the board. Wire-serializable ({@link #toMap()}/{@link #fromMap}) because the
 * server replicates each player's roster to them in {@code you} messages.
 */
public final class UnitInstance {

    public static final int MAX_ITEMS = 3;

    public final int id;
    public final String key;
    public int star = 1;
    /** Item keys held (components + combined), at most {@value MAX_ITEMS}. */
    public final List<String> items = new ArrayList<>();

    /** Bench slot index, or -1 when fielded on the board. */
    public int bench = -1;
    /** Board cell, or -1/-1 when benched. Rows 4-7 are the player's half. */
    public int col = -1, row = -1;

    public UnitInstance(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public UnitDef def() {
        return AutoUnits.get(key);
    }

    public boolean onBoard() {
        return bench < 0;
    }

    public void placeBench(int slot) {
        bench = slot;
        col = -1;
        row = -1;
    }

    public void placeBoard(int c, int r) {
        bench = -1;
        col = c;
        row = r;
    }

    /** A deep copy (for ghost boards, which must not share mutable state). */
    public UnitInstance copy() {
        UnitInstance u = new UnitInstance(id, key);
        u.star = star;
        u.items.addAll(items);
        u.bench = bench;
        u.col = col;
        u.row = row;
        return u;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", key);
        m.put("s", star);
        m.put("b", bench);
        m.put("c", col);
        m.put("r", row);
        if (!items.isEmpty()) m.put("it", new ArrayList<Object>(items));
        return m;
    }

    public static UnitInstance fromMap(Map<String, Object> m) {
        UnitInstance u = new UnitInstance(intOf(m.get("id")), str(m.get("k")));
        u.star = Math.max(1, intOf(m.get("s")));
        u.bench = m.containsKey("b") ? intOf(m.get("b")) : -1;
        u.col = m.containsKey("c") ? intOf(m.get("c")) : -1;
        u.row = m.containsKey("r") ? intOf(m.get("r")) : -1;
        if (m.get("it") instanceof List<?> list) {
            for (Object o : Json.asArray(list)) {
                if (o instanceof String s) u.items.add(s);
            }
        }
        return u;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }
}
