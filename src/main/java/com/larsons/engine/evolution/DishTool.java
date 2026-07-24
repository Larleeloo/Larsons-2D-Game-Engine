package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool dropped into the gel for the organisms themselves to use.
 *
 * <p>Only a strand that unlocked {@link Ability#TOOL_USE} can pick one up, and
 * a tool carries a limited number of uses that burn down while it is held. When
 * the carrier dies the tool drops back into the dish with whatever is left, so
 * tools get passed around a colony — the design document's "tools that can be
 * transferred/shared and only have limited numbers of uses".
 */
public final class DishTool {

    /** What a held tool does for its carrier. */
    public enum Kind {
        FLAGELLUM("Flagellum", "Whips the carrier along 60% faster.",
                new Color(200, 220, 255)),
        SCALPEL("Scalpel", "Doubles the carrier's attack.",
                new Color(255, 170, 160)),
        SIEVE("Sieve", "Strains a meal properly: far less waste.",
                new Color(190, 235, 180)),
        LANTERN("Lantern", "Lights the carrier's surroundings.",
                new Color(255, 235, 160));

        private final String displayName;
        private final String description;
        private final Color color;

        Kind(String displayName, String description, Color color) {
            this.displayName = displayName;
            this.description = description;
            this.color = color;
        }

        public String displayName() { return displayName; }

        public String description() { return description; }

        public Color color() { return color; }
    }

    public final Kind kind;
    public double x, y;
    /** Seconds of use left; a held tool burns these down in real time. */
    public double uses;
    /** Organism id currently carrying it, or {@code -1} when lying in the gel. */
    public int carrierId = -1;

    public DishTool(Kind kind, double x, double y, double uses) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.uses = uses;
    }

    public boolean spent() { return uses <= 0; }

    public boolean held() { return carrierId >= 0; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("x", x);
        m.put("y", y);
        m.put("uses", uses);
        m.put("carrier", carrierId);
        return m;
    }

    public static DishTool fromMap(Map<String, Object> m) {
        Kind kind = Kind.FLAGELLUM;
        try {
            kind = Kind.valueOf(String.valueOf(m.get("kind")));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // unknown tool from a newer save: treat it as a flagellum
        }
        DishTool t = new DishTool(kind, num(m.get("x")), num(m.get("y")), num(m.get("uses")));
        t.carrierId = (int) num(m.get("carrier"));
        return t;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
