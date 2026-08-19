package com.larsons.engine.minigame;

import java.awt.Color;
import java.util.List;

/**
 * The engine's mini games — the small, complete games that sit beside the world
 * engine rather than inside a level.
 *
 * <p><b>This is the backend the launch screen's buttons are drawn from.</b>
 * Each entry names the game, the scene that opens it, and the texture key its
 * button art is looked up under ({@link #textureKey()}); a texture pack that
 * drops a sheet in at that key redresses the button without a line of code
 * changing, and the built-in art is generated from {@link #accent()} when no
 * pack supplies one. Adding a mini game here is what puts it on the launch
 * screen, in the pack's key list, and in the texture dialog — the alternative
 * was three names hard-coded in a menu and three pictures nobody could replace.
 *
 * <p>Not to be confused with {@link MiniGameConfig.Mode}, which is a rule set a
 * <em>level</em> plays under (capture the flag, escort). These are separate
 * games with their own scenes, their own lobbies and their own controls.
 */
public enum StandaloneGame {

    AUTO_BATTLER("auto_battler", "Auto Battler", "2–10 players online",
            "autolobby", new Color(226, 158, 66)),
    DECK_BUILDER("deckbuilder", "Council of Six", "deckbuilder · 2–6 online",
            "decklobby", new Color(120, 150, 232)),
    EVOLUTION("evolution", "Evolution", "artificial life simulator",
            "evolutionlobby", new Color(112, 205, 140));

    /** How many states a button sheet holds; see {@link #textureKey()}. */
    public static final int BUTTON_STATES = 3;

    private final String key;
    private final String title;
    private final String tagline;
    private final String scene;
    private final Color accent;

    StandaloneGame(String key, String title, String tagline, String scene, Color accent) {
        this.key = key;
        this.title = title;
        this.tagline = tagline;
        this.scene = scene;
        this.accent = accent;
    }

    /** Stable identifier — what the texture key and any saved file use. */
    public String key() { return key; }

    /** What a player sees this game called. */
    public String title() { return title; }

    /** One line under the title: what kind of game it is. */
    public String tagline() { return tagline; }

    /** The scene name that opens it (its lobby). */
    public String scene() { return scene; }

    /** The colour the generated button art is built from. */
    public Color accent() { return accent; }

    /**
     * The texture key of this game's button sheet.
     *
     * <p>The sheet is read as {@value #BUTTON_STATES} <em>states</em> laid out
     * left to right — resting, pointed at, pressed — rather than as an
     * animation, so it is sliced by
     * {@link com.larsons.engine.graphics.Skins#stateFrame} at the index the
     * button is in rather than by a clock.
     */
    public String textureKey() { return "ui/minigame/" + key; }

    /** Every mini game, in the order the launch screen shows them. */
    public static List<StandaloneGame> all() { return List.of(values()); }
}
