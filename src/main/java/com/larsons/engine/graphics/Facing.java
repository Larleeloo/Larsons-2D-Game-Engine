package com.larsons.engine.graphics;

/**
 * The direction a character is facing, as one of eight compass points — what
 * picks the sprite an animation draws.
 *
 * <p>A side-scrolling game only ever faces {@link #EAST} or {@link #WEST}
 * ({@link #side}); the plan-view formats (top-down and isometric) use all
 * eight ({@link #of}), so a character walking north-east is drawn from
 * behind-and-to-the-side rather than mirrored from a single profile.
 *
 * <p>Art is authored for the "canonical" directions that face east or
 * straight up/down ({@link #canonical}); the three west-facing ones are drawn
 * by mirroring their eastern twin ({@link #mirrorOf}) unless the texture pack
 * supplies a sheet of their own. That is what makes a right-facing player show
 * their right arm in front and their left behind, and the reverse when facing
 * left, from a single set of sheets — while still letting a creator hand-draw
 * every direction if they want to.
 */
public enum Facing {

    EAST("e", "East", 1, 0),
    NORTH_EAST("ne", "North-East", 1, -1),
    NORTH("n", "North", 0, -1),
    NORTH_WEST("nw", "North-West", -1, -1),
    WEST("w", "West", -1, 0),
    SOUTH_WEST("sw", "South-West", -1, 1),
    SOUTH("s", "South", 0, 1),
    SOUTH_EAST("se", "South-East", 1, 1);

    /** Texture-key segment: {@code player/walk/ne}. */
    private final String key;
    private final String label;
    /** Unit-ish step in screen space (+x east, +y south). */
    private final int dx, dy;

    Facing(String key, String label, int dx, int dy) {
        this.key = key;
        this.label = label;
        this.dx = dx;
        this.dy = dy;
    }

    public String key() { return key; }

    public String label() { return label; }

    public int dx() { return dx; }

    public int dy() { return dy; }

    /** The two directions a side-scroller uses. */
    public static Facing side(boolean facingLeft) {
        return facingLeft ? WEST : EAST;
    }

    /** Whether this direction points left (its art is a mirrored east one). */
    public boolean facingLeft() {
        return dx < 0;
    }

    /**
     * The direction whose art this one mirrors: east-facing and pure
     * north/south directions are their own answer (they are drawn directly),
     * the west-facing ones name their eastern twin.
     */
    public Facing mirrorOf() {
        return switch (this) {
            case WEST -> EAST;
            case NORTH_WEST -> NORTH_EAST;
            case SOUTH_WEST -> SOUTH_EAST;
            default -> this;
        };
    }

    /** Whether this direction is drawn by flipping {@link #mirrorOf}'s art. */
    public boolean mirrored() {
        return mirrorOf() != this;
    }

    /** The directions procedural art is drawn for (the rest mirror these). */
    public static Facing[] canonical() {
        return new Facing[]{EAST, NORTH_EAST, NORTH, SOUTH, SOUTH_EAST};
    }

    /**
     * The compass point a movement vector points at, snapped to the nearest of
     * the eight. A zero-length vector keeps {@code fallback} — a character
     * that stops moving goes on facing the way it was.
     */
    public static Facing of(double dx, double dy, Facing fallback) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return fallback;
        // Octants around the circle: atan2 gives [-pi, pi], and rounding the
        // eighth-turns lands on the compass point nearest the heading.
        double turns = Math.atan2(dy, dx) / (Math.PI / 4.0);
        int octant = (int) Math.round(turns);
        // Screen +y is south, and the enum is ordered counter-clockwise from
        // east, so a positive octant walks the list backwards.
        int idx = ((-octant) % 8 + 8) % 8;
        return values()[idx];
    }

    /**
     * The facing a movement vector implies in a given perspective: a
     * side-scroller only distinguishes left from right (vertical movement is
     * jumping, not turning), the plan-view formats use all eight.
     */
    public static Facing of(double dx, double dy, Perspective perspective, Facing fallback) {
        if (perspective == Perspective.SIDE_SCROLL) {
            if (dx > 1e-6) return EAST;
            if (dx < -1e-6) return WEST;
            return fallback == null ? EAST : Facing.side(fallback.facingLeft());
        }
        return of(dx, dy, fallback == null ? SOUTH : fallback);
    }

    /** Parse a {@link #key()} back into a direction, or {@code null}. */
    public static Facing byKey(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase();
        for (Facing f : values()) {
            if (f.key.equals(k)) return f;
        }
        return null;
    }
}
