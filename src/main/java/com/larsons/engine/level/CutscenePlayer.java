package com.larsons.engine.level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one {@link Cutscene}'s step script: advance it with the frame delta
 * and it walks the steps in order — placing actors, gliding them between
 * points (their <em>walk</em> animation state playing), switching animation
 * states, showing dialogue captions (the <em>talk</em> state playing while
 * someone speaks), pausing, and panning the camera.
 *
 * <p>The player is headless — pure timing and positions, no rendering — so
 * scenes query it ({@link #actors()}, {@link #caption()}, {@link #cameraX()})
 * and draw with their own camera, and tests drive it deterministically.
 * The camera is director-controlled for the whole cutscene: it holds where
 * playback started until a CAMERA step pans it.
 */
public final class CutscenePlayer {

    /** Default caption hold when a SAY step doesn't give a duration. */
    public static final double DEFAULT_SAY_SECONDS = 2.5;

    /** A live actor: where it is and which animation state is playing. */
    public static final class ActorView {
        public final Cutscene.Actor def;
        public boolean visible;
        public double x, y;              // feet-center anchor, world px
        public String state = "idle";
        public double stateTime;         // seconds in the current state
        public boolean facingLeft;

        ActorView(Cutscene.Actor def) {
            this.def = def;
        }

        void setState(String next) {
            if (next == null || next.isBlank()) next = "idle";
            if (!next.equals(state)) {
                state = next;
                stateTime = 0;
            }
        }
    }

    private final Cutscene cutscene;
    private final Map<String, ActorView> cast = new LinkedHashMap<>();

    private int stepIndex;
    private double stepTime;      // seconds into the current (timed) step
    private double time;          // seconds since playback started
    private boolean finished;

    private String caption = "";
    private String speaker = "";
    private String speakerRestoreState; // state to restore after a SAY's talk

    // MOVE bookkeeping.
    private double moveFromX, moveFromY;
    private String moveRestoreState;

    // Camera: holds at the start point until CAMERA steps pan it.
    private double camX, camY;
    private double camFromX, camFromY, camToX, camToY;

    public CutscenePlayer(Cutscene cutscene, double startCamX, double startCamY) {
        this.cutscene = cutscene;
        this.camX = camToX = startCamX;
        this.camY = camToY = startCamY;
        for (Cutscene.Actor a : cutscene.actors) {
            cast.put(a.key, new ActorView(a));
        }
        beginStep(); // run any leading instant steps immediately
    }

    public Cutscene cutscene() {
        return cutscene;
    }

    public boolean finished() {
        return finished;
    }

    /** Seconds since playback started (drives letterbox easing and skins). */
    public double time() {
        return time;
    }

    /** The caption currently on screen, or an empty string. */
    public String caption() {
        return caption;
    }

    /** Display name of whoever is speaking the caption, or an empty string. */
    public String speaker() {
        return speaker;
    }

    public double cameraX() {
        return camX;
    }

    public double cameraY() {
        return camY;
    }

    /** The live cast, in declaration order (render visible ones). */
    public List<ActorView> actors() {
        return new ArrayList<>(cast.values());
    }

    /** The live view of one actor, or {@code null} for an unknown key. */
    public ActorView actor(String key) {
        return cast.get(key);
    }

    /** Advance playback. Call once per update tick with the frame delta. */
    public void update(double dt) {
        if (finished) return;
        time += dt;
        for (ActorView a : cast.values()) a.stateTime += dt;

        stepTime += dt;
        Cutscene.Step step = current();
        double duration = step == null ? 0 : timedDuration(step);
        while (!finished && stepTime >= duration) {
            endStep();
            stepTime -= duration;
            stepIndex++;
            beginStep();
            step = current();
            duration = step == null ? 0 : timedDuration(step);
        }
        if (!finished && step != null && step.op() == Cutscene.Op.MOVE) {
            ActorView a = cast.get(step.actor());
            if (a != null && duration > 0) {
                double t = Math.min(1, stepTime / duration);
                a.x = moveFromX + (step.x() - moveFromX) * t;
                a.y = moveFromY + (step.y() - moveFromY) * t;
            }
        }
        if (!finished && step != null && step.op() == Cutscene.Op.CAMERA && duration > 0) {
            double t = Math.min(1, stepTime / duration);
            camX = camFromX + (camToX - camFromX) * t;
            camY = camFromY + (camToY - camFromY) * t;
        }
    }

    /** Jump to the end: every remaining step applies its final effect at once. */
    public void skip() {
        while (!finished) {
            endStep();
            stepIndex++;
            beginStep();
        }
    }

    private Cutscene.Step current() {
        return stepIndex >= 0 && stepIndex < cutscene.steps.size()
                ? cutscene.steps.get(stepIndex) : null;
    }

    /** How long the current step blocks the script (0 = instant). */
    private static double timedDuration(Cutscene.Step step) {
        return switch (step.op()) {
            case SAY -> step.seconds() > 0 ? step.seconds() : DEFAULT_SAY_SECONDS;
            case MOVE, WAIT, CAMERA, ANIM -> step.seconds();
            case SHOW, HIDE -> 0;
        };
    }

    /** Apply the entering step's immediate effect. */
    private void beginStep() {
        Cutscene.Step step = current();
        if (step == null) {
            finished = true;
            caption = "";
            speaker = "";
            return;
        }
        ActorView a = cast.get(step.actor());
        switch (step.op()) {
            case SHOW -> {
                if (a != null) {
                    a.visible = true;
                    a.x = step.x();
                    a.y = step.y();
                    a.setState(step.text().isBlank() ? "idle" : step.text().trim());
                }
            }
            case HIDE -> {
                if (a != null) a.visible = false;
            }
            case SAY -> {
                caption = step.text();
                speaker = a != null ? a.def.name : "";
                speakerRestoreState = null;
                if (a != null && a.def.states.containsKey("talk")) {
                    speakerRestoreState = a.state;
                    a.setState("talk");
                }
            }
            case MOVE -> {
                moveRestoreState = null;
                if (a != null) {
                    moveFromX = a.x;
                    moveFromY = a.y;
                    if (step.x() != a.x) a.facingLeft = step.x() < a.x;
                    if (a.def.states.containsKey("walk")) {
                        moveRestoreState = a.state;
                        a.setState("walk");
                    }
                }
            }
            case ANIM -> {
                if (a != null) a.setState(step.text().trim());
            }
            case CAMERA -> {
                camFromX = camX;
                camFromY = camY;
                camToX = step.x();
                camToY = step.y();
            }
            case WAIT -> { /* just time passing */ }
        }
    }

    /** Apply the leaving step's final effect (also what skipping fast-forwards). */
    private void endStep() {
        Cutscene.Step step = current();
        if (step == null) return;
        ActorView a = cast.get(step.actor());
        switch (step.op()) {
            case SAY -> {
                caption = "";
                speaker = "";
                if (a != null && speakerRestoreState != null) {
                    a.setState(speakerRestoreState);
                }
                speakerRestoreState = null;
            }
            case MOVE -> {
                if (a != null) {
                    a.x = step.x();
                    a.y = step.y();
                    if (moveRestoreState != null) a.setState(moveRestoreState);
                }
                moveRestoreState = null;
            }
            case CAMERA -> {
                camX = camToX;
                camY = camToY;
            }
            default -> { /* SHOW/HIDE/ANIM/WAIT already applied on begin */ }
        }
    }
}
