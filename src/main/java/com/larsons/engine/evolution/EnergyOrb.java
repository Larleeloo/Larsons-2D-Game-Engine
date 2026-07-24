package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A drop of food suspended in the gel. The player seeds these by hand (the
 * feeding tool), the shop sells more, and dead bodies and digested waste
 * recycle back into them — which is what lets a well-adapted dish keep running
 * long after the first hundred orbs are gone.
 */
public final class EnergyOrb {

    /** What kind of meal this is. */
    public enum Kind {
        /** Plain sugar: anything can eat it. */
        SIMPLE("Simple energy", new Color(245, 225, 130), 24),
        /**
         * Long-chain energy worth far more, but only digestible by strands that
         * evolved {@link Ability#COMPLEX_DIGESTION} — a selective pressure the
         * player can buy and apply deliberately.
         */
        COMPLEX("Complex energy", new Color(180, 235, 200), 60);

        private final String displayName;
        private final Color color;
        private final double defaultEnergy;

        Kind(String displayName, Color color, double defaultEnergy) {
            this.displayName = displayName;
            this.color = color;
            this.defaultEnergy = defaultEnergy;
        }

        public String displayName() { return displayName; }

        public Color color() { return color; }

        public double defaultEnergy() { return defaultEnergy; }
    }

    public final Kind kind;
    public double x, y;
    public double energy;

    public EnergyOrb(Kind kind, double x, double y) {
        this(kind, x, y, kind.defaultEnergy());
    }

    public EnergyOrb(Kind kind, double x, double y, double energy) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.energy = energy;
    }

    /** Drawn radius, so a half-eaten orb visibly shrinks. */
    public double radius() {
        return 2.0 + 2.6 * Math.sqrt(Math.max(0, energy) / Math.max(1, kind.defaultEnergy()));
    }

    public boolean spent() { return energy <= 0.01; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind.name());
        m.put("x", x);
        m.put("y", y);
        m.put("energy", energy);
        return m;
    }

    public static EnergyOrb fromMap(Map<String, Object> m) {
        Kind kind = Kind.SIMPLE;
        Object k = m.get("kind");
        if (k != null) {
            try {
                kind = Kind.valueOf(String.valueOf(k));
            } catch (IllegalArgumentException ignored) {
                // an orb kind from a newer save: fall back to plain sugar
            }
        }
        return new EnergyOrb(kind,
                num(m.get("x")), num(m.get("y")),
                m.containsKey("energy") ? num(m.get("energy")) : kind.defaultEnergy());
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
