package com.larsons.engine.level;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Watches a level's {@link Cutscene} triggers during one play/play-test run
 * and owns the {@link CutscenePlayer} for whichever cutscene is running.
 *
 * <p>Each tick the scene calls {@link #advance} (drives the active playback)
 * and, when idle, {@link #checkTriggers} with the player's position:
 * LEVEL_START cutscenes fire on the first check, ZONE cutscenes when the
 * player enters their radius, INTERACT ones when E is pressed inside it.
 * {@code once} cutscenes play a single time per run; re-triggerable ZONE
 * cutscenes re-arm only after the player leaves the zone, so standing still
 * doesn't loop them.
 */
public final class CutsceneDirector {

    private final List<Cutscene> cutscenes;
    private final Set<String> played = new HashSet<>();
    private final Set<String> inside = new HashSet<>(); // zone re-arm tracking
    private CutscenePlayer active;

    public CutsceneDirector(List<Cutscene> cutscenes) {
        this.cutscenes = List.copyOf(cutscenes);
    }

    /** The running playback, or {@code null} when idle. */
    public CutscenePlayer active() {
        return active;
    }

    /** Advance the active playback; clears it once it finishes. */
    public void advance(double dt) {
        if (active == null) return;
        active.update(dt);
        if (active.finished()) active = null;
    }

    /** Skip the active playback to its end (its final effects still apply). */
    public void skip() {
        if (active == null) return;
        active.skip();
        active = null;
    }

    /**
     * Fire whichever trigger the player state satisfies (idle only). The
     * player point is their center in world px; the camera point is where
     * playback should hold the camera until a CAMERA step pans it. Returns
     * the started cutscene, or {@code null}.
     */
    public Cutscene checkTriggers(double px, double py, boolean interactPressed,
                                  double tileSize, double camX, double camY) {
        if (active != null) return null;
        Cutscene start = null;
        for (Cutscene cs : cutscenes) {
            boolean near = cs.trigger != Cutscene.Trigger.LEVEL_START
                    && Math.hypot(px - cs.x, py - cs.y) <= cs.radiusTiles * tileSize;
            boolean fires = switch (cs.trigger) {
                // LEVEL_START always plays once per run, whatever `once` says.
                case LEVEL_START -> !played.contains(cs.key);
                case ZONE -> near && !inside.contains(cs.key)
                        && (!cs.once || !played.contains(cs.key));
                case INTERACT -> near && interactPressed
                        && (!cs.once || !played.contains(cs.key));
            };
            // Zone occupancy updates for every cutscene, even while one is
            // pending, so leaving-and-returning is what re-arms them.
            if (cs.trigger == Cutscene.Trigger.ZONE) {
                if (near) inside.add(cs.key);
                else inside.remove(cs.key);
            }
            if (fires && start == null) start = cs;
        }
        if (start != null) {
            played.add(start.key);
            active = new CutscenePlayer(start, camX, camY);
        }
        return start;
    }
}
