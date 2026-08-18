package com.larsons.engine.graphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a level lets its camera stand — the creator's answer to "how is this
 * meant to be looked at".
 *
 * <p><b>Why a level gets a say at all.</b> The camera has three controls a
 * player can move: eight headings, a tilt that runs from flat on the floor to
 * straight down, and the first/third-person cycle. That freedom is right for a
 * sandbox and wrong for a great many levels somebody has actually composed. A
 * side-on puzzle laid out to be read as a cut through the world is nonsense
 * from overhead; a room built to be entered from the south shows a player the
 * back of every wall if they turn round; a jump measured by eye at one tilt is
 * a different jump at another. Every one of those is a level the engine could
 * not express before, and none of them is served by taking the freedom away
 * from every other level.
 *
 * <p>So the lock is per level, and it is a <em>set of permissions</em> rather
 * than a mode. Each axis is restricted on its own and each defaults to
 * unrestricted, which is what {@link #free()} is and what every level saved
 * before this existed loads as:
 *
 * <ul>
 *   <li><b>Headings</b> — any subset of the eight compass points. One point is
 *       a fixed camera; two opposite points let a player look at a corridor
 *       from either end; all eight is the default. A heading a level does not
 *       allow is skipped by {@link Camera#turn}, so the rotate keys walk
 *       between what is left rather than sticking.</li>
 *   <li><b>Tilt</b> — a range in degrees, plus an optional step. A range with
 *       its ends equal is a fixed tilt. A step turns the range into exact
 *       angles: {@code 0–90} stepped by {@code 15} is the seven angles a
 *       creator can compose against, and nothing in between.</li>
 *   <li><b>Viewpoints</b> — which of the {@link Viewpoint} stops the F5 cycle
 *       may reach. {@link Viewpoint#PLAN} is always one of them, because it is
 *       the view every level is authored through and the one the others fall
 *       back to; a level with the solid views switched off simply never leaves
 *       it.</li>
 * </ul>
 *
 * <p><b>It is a constraint on the camera, not on the simulation.</b> Nothing
 * here is networked and nothing here changes what a body can do — two players
 * in one locked level may still be at different allowed headings, exactly as
 * they may at different allowed zooms. It is the same kind of setting as
 * {@code minZoom}/{@code maxZoom}, which is why it lives beside them on the
 * level's own {@code GameProfile}.
 */
public final class CameraLock {

    /** How many compass points a camera can rest at. */
    public static final int HEADINGS = 8;

    /** Every heading allowed — the default. */
    public static final int ALL_HEADINGS = (1 << HEADINGS) - 1;

    /**
     * The compass points, in heading order (0 is north, and each step is an
     * eighth of a turn clockwise). These are the names written to a level file
     * and shown in the settings form, so they are the vocabulary a creator
     * reads rather than an index.
     */
    public static final String[] HEADING_KEYS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

    /** The same, spelled out for a settings row. */
    public static final String[] HEADING_LABELS = {
            "North", "North-East", "East", "South-East",
            "South", "South-West", "West", "North-West"
    };

    /** The stable file names of the {@link Viewpoint} stops, in ordinal order. */
    public static final String[] VIEWPOINT_KEYS = {"plan", "first", "third", "third_front"};

    private int headings = ALL_HEADINGS;
    private double minPitchDegrees = Math.toDegrees(Camera.MIN_PITCH);
    private double maxPitchDegrees = Math.toDegrees(Camera.MAX_PITCH);
    private double pitchStepDegrees;
    private int viewpoints = allViewpoints();

    /** A lock that restricts nothing — what a level has until it says otherwise. */
    public static CameraLock free() {
        return new CameraLock();
    }

    private static int allViewpoints() {
        return (1 << Viewpoint.values().length) - 1;
    }

    /** Whether this lock leaves every control where it found it. */
    public boolean isFree() {
        return headings == ALL_HEADINGS
                && minPitchDegrees <= Math.toDegrees(Camera.MIN_PITCH)
                && maxPitchDegrees >= Math.toDegrees(Camera.MAX_PITCH)
                && pitchStepDegrees <= 0
                && viewpoints == allViewpoints();
    }

    public CameraLock copy() {
        return fromMap(toMap());
    }

    /**
     * Put every axis back to unrestricted, <em>in place</em>.
     *
     * <p>In place rather than by handing back a fresh lock, because the
     * settings form's rows are bound to this object: replacing it would leave
     * every toggle editing a lock nothing reads any more, and the screen would
     * go on showing the old answers while the level had a new one.
     */
    public void reset() {
        headings = ALL_HEADINGS;
        minPitchDegrees = Math.toDegrees(Camera.MIN_PITCH);
        maxPitchDegrees = Math.toDegrees(Camera.MAX_PITCH);
        pitchStepDegrees = 0;
        viewpoints = allViewpoints();
    }

    // --- headings ---------------------------------------------------------------

    /** Whether the camera may rest at compass point {@code heading} (0–7). */
    public boolean allowsHeading(int heading) {
        return (headings & (1 << Math.floorMod(heading, HEADINGS))) != 0;
    }

    /** Allow or forbid one compass point; forbidding the last one is refused. */
    public void setHeadingAllowed(int heading, boolean allowed) {
        int bit = 1 << Math.floorMod(heading, HEADINGS);
        if (allowed) {
            headings |= bit;
        } else if ((headings & ~bit) != 0) {
            // A camera has to be able to point somewhere. Refusing the last one
            // rather than silently re-opening all eight is what keeps a creator
            // clicking through the list from ending up with the opposite of
            // what the last click said.
            headings &= ~bit;
        }
    }

    /** How many of the eight compass points this level allows. */
    public int headingCount() {
        return Integer.bitCount(headings & ALL_HEADINGS);
    }

    /** Whether the camera is pinned to a single heading. */
    public boolean headingLocked() {
        return headingCount() <= 1;
    }

    /**
     * The heading {@code step} compass points on from {@code from} in the
     * direction of travel, skipping any this level does not allow. Returns
     * {@code from} when nothing else is allowed, which is what pins a fixed
     * camera without the rotate keys having to know about locks.
     */
    public int nextHeading(int from, int step) {
        int dir = step >= 0 ? 1 : -1;
        int h = Math.floorMod(from, HEADINGS);
        for (int i = 1; i <= HEADINGS; i++) {
            int candidate = Math.floorMod(h + dir * i, HEADINGS);
            if (allowsHeading(candidate)) return candidate;
        }
        return h;
    }

    /**
     * The allowed heading nearest {@code heading}, for placing a camera at an
     * angle the level may since have stopped allowing — an authored heading a
     * creator later locked out, or a door arriving from a level with different
     * rules. Ties go clockwise.
     */
    public int nearestHeading(int heading) {
        int h = Math.floorMod(heading, HEADINGS);
        if (allowsHeading(h)) return h;
        for (int d = 1; d <= HEADINGS / 2; d++) {
            if (allowsHeading(h + d)) return Math.floorMod(h + d, HEADINGS);
            if (allowsHeading(h - d)) return Math.floorMod(h - d, HEADINGS);
        }
        return h;
    }

    // --- tilt -------------------------------------------------------------------

    public double minPitchDegrees() { return minPitchDegrees; }

    public double maxPitchDegrees() { return maxPitchDegrees; }

    public double pitchStepDegrees() { return pitchStepDegrees; }

    public void setMinPitchDegrees(double degrees) {
        minPitchDegrees = clampDegrees(degrees);
        if (maxPitchDegrees < minPitchDegrees) maxPitchDegrees = minPitchDegrees;
    }

    public void setMaxPitchDegrees(double degrees) {
        maxPitchDegrees = clampDegrees(degrees);
        if (minPitchDegrees > maxPitchDegrees) minPitchDegrees = maxPitchDegrees;
    }

    /** Snap the tilt to multiples of this many degrees; {@code 0} leaves it free. */
    public void setPitchStepDegrees(double degrees) {
        pitchStepDegrees = Math.max(0, Math.min(90, degrees));
    }

    /** Whether the tilt is pinned to a single angle. */
    public boolean pitchLocked() {
        return maxPitchDegrees - minPitchDegrees < 1e-9;
    }

    /**
     * {@code radians} brought inside this lock: clamped to the allowed range,
     * then snapped to the step if there is one.
     *
     * <p>The step's grid starts at the range's low end rather than at zero, so
     * the lowest allowed angle is always one a player can actually rest at —
     * a range of 30–90 stepped by 20 gives 30/50/70/90, not 40/60/80 with the
     * bottom of the range unreachable.
     */
    public double clampPitch(double radians) {
        double lo = Math.toRadians(minPitchDegrees);
        double hi = Math.toRadians(maxPitchDegrees);
        double v = Math.max(lo, Math.min(hi, radians));
        if (pitchStepDegrees > 0) {
            double step = Math.toRadians(pitchStepDegrees);
            v = lo + Math.round((v - lo) / step) * step;
            v = Math.max(lo, Math.min(hi, v));
        }
        return v;
    }

    private static double clampDegrees(double d) {
        return Math.max(Math.toDegrees(Camera.MIN_PITCH),
                Math.min(Math.toDegrees(Camera.MAX_PITCH), d));
    }

    // --- viewpoints -------------------------------------------------------------

    /** Whether the F5 cycle may reach {@code v}. {@link Viewpoint#PLAN} always may. */
    public boolean allows(Viewpoint v) {
        if (v == null || v == Viewpoint.PLAN) return true;
        return (viewpoints & (1 << v.ordinal())) != 0;
    }

    /** Allow or forbid one stop of the F5 cycle; the plan view cannot be forbidden. */
    public void setAllowed(Viewpoint v, boolean allowed) {
        if (v == null || v == Viewpoint.PLAN) return;
        int bit = 1 << v.ordinal();
        if (allowed) {
            viewpoints |= bit;
        } else {
            viewpoints &= ~bit;
        }
    }

    /** Whether any solid view is reachable at all. */
    public boolean allowsAnySolidView() {
        for (Viewpoint v : Viewpoint.values()) {
            if (v.solid() && allows(v)) return true;
        }
        return false;
    }

    // --- serialization ----------------------------------------------------------

    /**
     * Written as names rather than as the bitmasks it is held in: a level file
     * is something a person reads, and {@code ["n","s"]} says what {@code 17}
     * does not. Absent keys mean unrestricted, so a lock that restricts nothing
     * writes {@code {}} and an older reader ignoring the whole block gets
     * exactly the behaviour the block asked for.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (headings != ALL_HEADINGS) {
            List<String> keys = new ArrayList<>();
            for (int h = 0; h < HEADINGS; h++) {
                if (allowsHeading(h)) keys.add(HEADING_KEYS[h]);
            }
            m.put("headings", keys);
        }
        if (minPitchDegrees > Math.toDegrees(Camera.MIN_PITCH)) {
            m.put("minPitch", minPitchDegrees);
        }
        if (maxPitchDegrees < Math.toDegrees(Camera.MAX_PITCH)) {
            m.put("maxPitch", maxPitchDegrees);
        }
        if (pitchStepDegrees > 0) m.put("pitchStep", pitchStepDegrees);
        if (viewpoints != allViewpoints()) {
            List<String> keys = new ArrayList<>();
            for (Viewpoint v : Viewpoint.values()) {
                if (allows(v)) keys.add(VIEWPOINT_KEYS[v.ordinal()]);
            }
            m.put("views", keys);
        }
        return m;
    }

    public static CameraLock fromMap(Map<String, Object> m) {
        CameraLock lock = new CameraLock();
        if (m == null) return lock;
        if (m.get("headings") instanceof Iterable<?> list) {
            lock.headings = 0;
            for (Object o : list) {
                int h = indexOf(HEADING_KEYS, String.valueOf(o));
                if (h >= 0) lock.headings |= 1 << h;
            }
            // A file that allows nothing is a file that means nothing; a camera
            // must be able to point somewhere.
            if (lock.headings == 0) lock.headings = ALL_HEADINGS;
        }
        if (m.get("minPitch") instanceof Number n) lock.minPitchDegrees = clampDegrees(n.doubleValue());
        if (m.get("maxPitch") instanceof Number n) lock.maxPitchDegrees = clampDegrees(n.doubleValue());
        if (m.get("pitchStep") instanceof Number n) lock.setPitchStepDegrees(n.doubleValue());
        if (m.get("views") instanceof Iterable<?> list) {
            lock.viewpoints = 0;
            for (Object o : list) {
                int i = indexOf(VIEWPOINT_KEYS, String.valueOf(o));
                if (i >= 0 && i < Viewpoint.values().length) lock.viewpoints |= 1 << i;
            }
        }
        lock.normalize();
        return lock;
    }

    /** Put an edited lock back into a state the camera can obey. */
    public void normalize() {
        if ((headings & ALL_HEADINGS) == 0) headings = ALL_HEADINGS;
        headings &= ALL_HEADINGS;
        minPitchDegrees = clampDegrees(minPitchDegrees);
        maxPitchDegrees = clampDegrees(maxPitchDegrees);
        if (maxPitchDegrees < minPitchDegrees) {
            double swap = minPitchDegrees;
            minPitchDegrees = maxPitchDegrees;
            maxPitchDegrees = swap;
        }
        pitchStepDegrees = Math.max(0, Math.min(90, pitchStepDegrees));
        // The plan view is not optional; see the class note.
        viewpoints |= 1 << Viewpoint.PLAN.ordinal();
    }

    private static int indexOf(String[] keys, String value) {
        String v = value == null ? "" : value.trim().toLowerCase();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(v)) return i;
        }
        return -1;
    }

    /** A one-line summary for a HUD or a settings note. */
    public String describe() {
        if (isFree()) return "free";
        List<String> parts = new ArrayList<>(3);
        if (headings != ALL_HEADINGS) {
            StringBuilder sb = new StringBuilder();
            for (int h = 0; h < HEADINGS; h++) {
                if (!allowsHeading(h)) continue;
                if (sb.length() > 0) sb.append('/');
                sb.append(HEADING_KEYS[h].toUpperCase());
            }
            parts.add("facing " + sb);
        }
        if (pitchLocked()) {
            parts.add("tilt " + (int) Math.round(minPitchDegrees) + "°");
        } else if (minPitchDegrees > 0 || maxPitchDegrees < 90 || pitchStepDegrees > 0) {
            String range = (int) Math.round(minPitchDegrees) + "–"
                    + (int) Math.round(maxPitchDegrees) + "°";
            parts.add(pitchStepDegrees > 0
                    ? "tilt " + range + " by " + (int) Math.round(pitchStepDegrees)
                    : "tilt " + range);
        }
        if (!allowsAnySolidView()) {
            parts.add("plan view only");
        } else if (viewpoints != allViewpoints()) {
            parts.add("some views off");
        }
        return String.join(", ", parts);
    }
}
