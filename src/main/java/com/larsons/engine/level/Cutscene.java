package com.larsons.engine.level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One map-maker-scripted cutscene, saved with the level: a <em>trigger</em>
 * (walk into a zone, press E at a marker, or the level starting), a cast of
 * <em>actors</em> — sprite-sheet characters with named <b>animation states</b>
 * (idle / walk / talk / anything), each state its own sheet slice with frame
 * size, count, fps, and loop flag — and a sequential <em>step script</em>
 * (show, say, move, switch animation state, wait, pan the camera, hide).
 *
 * <p>Cutscenes are pure data, edited in the creative mode's Cutscenes dialog
 * and painted into the level as trigger markers; {@link CutsceneDirector}
 * watches the triggers during play/play-test and {@link CutscenePlayer} runs
 * the script. Everything serializes with the level JSON, so cutscenes travel
 * with the level file like stat rules and containers do.
 */
public final class Cutscene {

    /** What starts the cutscene. */
    public enum Trigger {
        /** The player walks within {@link #radiusTiles} of the marker. */
        ZONE,
        /** The player presses E within {@link #radiusTiles} of the marker. */
        INTERACT,
        /** Plays as soon as the level starts (no marker needed). */
        LEVEL_START
    }

    /** One script operation. */
    public enum Op {
        /** Place {@code actor} at (x,y), starting in state {@code text} (default idle). */
        SHOW,
        /** Caption {@code text} spoken by {@code actor} for {@code seconds} (talk state plays). */
        SAY,
        /** Glide {@code actor} to (x,y) over {@code seconds} (walk state plays). */
        MOVE,
        /** Switch {@code actor} to animation state {@code text}; holds {@code seconds} if &gt; 0. */
        ANIM,
        /** Pause the script for {@code seconds}. */
        WAIT,
        /** Pan the camera to (x,y) over {@code seconds} (0 = snap). */
        CAMERA,
        /** Remove {@code actor} from the scene. */
        HIDE
    }

    public String key;
    public String name = "Cutscene";
    public Trigger trigger = Trigger.ZONE;
    /** Trigger marker position, world px (unused for LEVEL_START). */
    public double x, y;
    /** Trigger radius around the marker, in tiles. */
    public double radiusTiles = 2;
    /** Play once per run, or re-arm when the player leaves the zone. */
    public boolean once = true;
    public final List<Actor> actors = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();

    public Cutscene(String key, String name) {
        this.key = key == null || key.isBlank() ? "cutscene" : key;
        if (name != null && !name.isBlank()) this.name = name.trim();
    }

    /** The actor with {@code key}, or {@code null}. */
    public Actor actor(String key) {
        if (key == null) return null;
        for (Actor a : actors) {
            if (key.equals(a.key)) return a;
        }
        return null;
    }

    /**
     * A cutscene character: a set of named animation states, each sliced from
     * its own sprite sheet. Actors without a (working) sheet for the active
     * state draw as a procedural placeholder figure, so a missing PNG never
     * breaks the scene — the same philosophy as the {@code Skins} system.
     */
    public static final class Actor {
        public String key;
        public String name;
        /** Drawn size, world px (square, feet-anchored like mobs). */
        public int sizePx = 48;
        /** Animation states by name: idle, walk, talk, or anything custom. */
        public final Map<String, SheetAnim> states = new LinkedHashMap<>();

        public Actor(String key, String name, int sizePx) {
            this.key = key == null || key.isBlank() ? "actor" : key;
            this.name = name == null || name.isBlank() ? this.key : name.trim();
            this.sizePx = Math.max(8, sizePx);
        }

        /** The state's animation, falling back to idle, else any defined state. */
        public SheetAnim state(String state) {
            SheetAnim anim = states.get(state);
            if (anim == null) anim = states.get("idle");
            if (anim == null && !states.isEmpty()) {
                anim = states.values().iterator().next();
            }
            return anim;
        }
    }

    /**
     * One animation state sliced from a sprite sheet: frames of
     * {@code frameWidth}&times;{@code frameHeight} px read left-to-right,
     * top-to-bottom, played at {@code fps} (0 = static). Looping states
     * (idle, walk) wrap; one-shot states (a death, a wave) clamp on the
     * last frame.
     */
    public record SheetAnim(String sheet, int frameWidth, int frameHeight,
                            int frameCount, double fps, boolean loop) {

        public SheetAnim {
            sheet = sheet == null ? "" : sheet.trim();
            frameWidth = Math.max(1, frameWidth);
            frameHeight = Math.max(1, frameHeight);
            frameCount = Math.max(1, frameCount);
            fps = Math.max(0, Math.min(120, fps));
        }

        /** The frame index playing at {@code seconds} into the state. */
        public int frameAt(double seconds) {
            if (fps <= 0 || frameCount <= 1) return 0;
            int raw = (int) Math.floor(Math.max(0, seconds) * fps);
            return loop ? raw % frameCount : Math.min(frameCount - 1, raw);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sheet", sheet);
            m.put("w", frameWidth);
            m.put("h", frameHeight);
            m.put("n", frameCount);
            m.put("fps", fps);
            m.put("loop", loop);
            return m;
        }

        public static SheetAnim fromMap(Map<?, ?> m) {
            return new SheetAnim(
                    m.get("sheet") instanceof String s ? s : "",
                    intOf(m.get("w"), 32), intOf(m.get("h"), 32),
                    intOf(m.get("n"), 1),
                    m.get("fps") instanceof Number n ? n.doubleValue() : 0,
                    !Boolean.FALSE.equals(m.get("loop")));
        }
    }

    /**
     * One sequential script step. Which fields matter depends on {@link Op}:
     * {@code actor} names the target, {@code text} carries dialogue (SAY),
     * an animation state name (SHOW/ANIM), (x,y) is a world-px point
     * (SHOW/MOVE/CAMERA), and {@code seconds} is the duration.
     */
    public record Step(Op op, String actor, String text, double x, double y, double seconds) {

        public Step {
            if (op == null) op = Op.WAIT;
            actor = actor == null ? "" : actor.trim();
            text = text == null ? "" : text;
            seconds = Math.max(0, seconds);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op.name());
            if (!actor.isEmpty()) m.put("actor", actor);
            if (!text.isEmpty()) m.put("text", text);
            m.put("x", x);
            m.put("y", y);
            m.put("s", seconds);
            return m;
        }

        public static Step fromMap(Map<?, ?> m) {
            Op op;
            try {
                op = Op.valueOf(String.valueOf(m.get("op")));
            } catch (IllegalArgumentException e) {
                op = Op.WAIT;
            }
            return new Step(op,
                    m.get("actor") instanceof String a ? a : "",
                    m.get("text") instanceof String t ? t : "",
                    doubleOf(m.get("x")), doubleOf(m.get("y")),
                    m.get("s") instanceof Number n ? n.doubleValue() : 0);
        }
    }

    // --- serialization --------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("trigger", trigger.name());
        m.put("x", x);
        m.put("y", y);
        m.put("radius", radiusTiles);
        m.put("once", once);
        List<Object> cast = new ArrayList<>(actors.size());
        for (Actor a : actors) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("key", a.key);
            am.put("name", a.name);
            am.put("size", a.sizePx);
            Map<String, Object> sm = new LinkedHashMap<>();
            for (Map.Entry<String, SheetAnim> e : a.states.entrySet()) {
                sm.put(e.getKey(), e.getValue().toMap());
            }
            am.put("states", sm);
            cast.add(am);
        }
        m.put("actors", cast);
        List<Object> script = new ArrayList<>(steps.size());
        for (Step s : steps) script.add(s.toMap());
        m.put("steps", script);
        return m;
    }

    public static Cutscene fromMap(Map<?, ?> m) {
        Cutscene cs = new Cutscene(
                m.get("key") instanceof String k ? k : "cutscene",
                m.get("name") instanceof String n ? n : "Cutscene");
        try {
            cs.trigger = Trigger.valueOf(String.valueOf(m.get("trigger")));
        } catch (IllegalArgumentException ignored) {
            // unknown trigger: keep the ZONE default
        }
        cs.x = doubleOf(m.get("x"));
        cs.y = doubleOf(m.get("y"));
        if (m.get("radius") instanceof Number n) cs.radiusTiles = Math.max(0.5, n.doubleValue());
        cs.once = !Boolean.FALSE.equals(m.get("once"));
        if (m.get("actors") instanceof List<?> cast) {
            for (Object o : cast) {
                if (!(o instanceof Map<?, ?> am)) continue;
                Actor a = new Actor(
                        am.get("key") instanceof String k ? k : "actor",
                        am.get("name") instanceof String n ? n : null,
                        intOf(am.get("size"), 48));
                if (am.get("states") instanceof Map<?, ?> sm) {
                    for (Map.Entry<?, ?> e : sm.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> anim) {
                            a.states.put(String.valueOf(e.getKey()), SheetAnim.fromMap(anim));
                        }
                    }
                }
                cs.actors.add(a);
            }
        }
        if (m.get("steps") instanceof List<?> script) {
            for (Object o : script) {
                if (o instanceof Map<?, ?> sm) cs.steps.add(Step.fromMap(sm));
            }
        }
        return cs;
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double doubleOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
