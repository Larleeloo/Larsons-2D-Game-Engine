package com.larsons.engine.graphics;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The player character's sprite, shared by every place a player renders —
 * the play scene ("load level" play), creative mode's play-test, and remote
 * players online — so the character looks identical everywhere.
 *
 * <p>The default look is the procedural 4-frame walk sheet (body, head,
 * alternating legs). Like mobs, the player is reskinnable <em>per action
 * state</em> via the {@link Skins} system: creative mode's Tools &rarr;
 * Player Skin… assigns one sprite sheet (with its own frame size, count, and
 * fps) to each of {@link #ACTION_STATES} under texture keys
 * {@code player/<state>}. {@link #frame} resolves the state playing right
 * now, falling back through related states (run&rarr;walk, fall&rarr;jump,
 * swim&rarr;walk, everything&rarr;idle) so one assigned sheet is enough to
 * reskin the whole character, then to the legacy single {@value #SKIN_KEY}
 * key, then to the procedural art.
 */
public final class PlayerSprites {

    /** Legacy single-sheet skin key (still honoured as the last fallback). */
    public static final String SKIN_KEY = "player";

    /**
     * The player's skinnable action states, in the order the Player Skin…
     * dialog cycles them: standing still, walking, sprinting, rising in a
     * jump, falling, and swimming in a liquid.
     */
    public static final List<String> ACTION_STATES =
            List.of("idle", "walk", "run", "jump", "fall", "swim");

    /** The default (unskinned) body colour of the local player. */
    public static final Color DEFAULT_BODY = new Color(70, 130, 220);

    /** Playback rate of the procedural walk cycle, sprite frames/sec. */
    public static final double WALK_FPS = 10;

    private PlayerSprites() {}

    /** The {@link Skins} texture key of one action state's sheet. */
    public static String stateKey(String state) {
        return SKIN_KEY + "/" + state;
    }

    /**
     * Classify what the player is doing right now into one of
     * {@link #ACTION_STATES}: swimming beats everything, airborne splits into
     * jump (rising) / fall (sinking), and on the ground it's run / walk /
     * idle. Top-down and gravity-free games never report jump or fall.
     */
    public static String actionState(PlayerState s, Level level, GameProfile profile,
                                     Perspective perspective, boolean sprintHeld) {
        double size = profile.playerSize;
        double ts = level.tileSize;
        boolean inLiquid = level.liquidAt(
                (int) Math.floor((s.x + size / 2.0) / ts),
                (int) Math.floor((s.y + size / 2.0) / ts)) != null;
        boolean sideScroll = perspective == Perspective.SIDE_SCROLL && profile.gravityEnabled;
        boolean grounded = !sideScroll
                || PlayerPhysics.onGround(level, s.x, s.y, size, size);
        return actionState(grounded, inLiquid, s.moving, sprintHeld && s.moving, s.vy);
    }

    /** {@link #actionState(PlayerState, Level, GameProfile, Perspective, boolean)}, from raw facts. */
    public static String actionState(boolean grounded, boolean inLiquid, boolean moving,
                                     boolean sprinting, double vy) {
        if (inLiquid) return "swim";
        if (!grounded) return vy < 0 ? "jump" : "fall";
        if (!moving) return "idle";
        return sprinting ? "run" : "walk";
    }

    /**
     * The frame to draw right now for {@code state}, at {@code stateSeconds}
     * into that state (reset the clock when the state changes so every
     * animation starts at its first frame).
     *
     * <p>Resolution order: the state's own {@code player/<state>} sheet, its
     * nearest assigned relatives (run&rarr;walk, fall&rarr;jump,
     * swim&rarr;walk), the {@code player/idle} sheet, the legacy single
     * {@code player} sheet, and finally the procedural walk animation. When
     * an <em>idle</em> player borrows a moving state's sheet (or the legacy
     * one), it freezes on frame 0 instead of walking in place.
     */
    public static BufferedImage frame(String state, Animation walkAnim, double stateSeconds) {
        for (String candidate : fallbackChain(state)) {
            double t = "idle".equals(state) && !"idle".equals(candidate) ? 0 : stateSeconds;
            BufferedImage img = Skins.frame(stateKey(candidate), t);
            if (img != null) return img;
        }
        BufferedImage legacy = Skins.frame(SKIN_KEY,
                "idle".equals(state) ? 0 : stateSeconds);
        if (legacy != null) return legacy;
        return walkAnim != null ? walkAnim.current() : null;
    }

    /** The state keys tried for {@code state}, most specific first. */
    private static String[] fallbackChain(String state) {
        return switch (state == null ? "" : state) {
            case "run" -> new String[]{"run", "walk", "idle"};
            case "jump" -> new String[]{"jump", "idle", "walk"};
            case "fall" -> new String[]{"fall", "jump", "idle", "walk"};
            case "swim" -> new String[]{"swim", "walk", "idle"};
            case "walk" -> new String[]{"walk", "idle"};
            // Idle borrows the walk sheet when it's all there is — frozen on
            // frame 0 by frame()'s idle rule, so nobody walks in place.
            default -> new String[]{"idle", "walk"};
        };
    }

    /** A looping walk animation over {@link #sheet}, at {@link #WALK_FPS}. */
    public static Animation walkAnimation(int size, Color body) {
        int s = Math.max(8, size);
        return SpriteSheet.fromImage(sheet(s, body), s, s).animation(WALK_FPS, true);
    }

    /**
     * The procedural 4-frame walk sheet: rounded body in {@code body}, skin
     * tone head, dark legs alternating spread, with a small bob on odd frames.
     */
    public static BufferedImage sheet(int size, Color body) {
        int frames = 4;
        BufferedImage img = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int f = 0; f < frames; f++) {
            int ox = f * size;
            int bob = (f % 2 == 0) ? 0 : Math.max(1, size / 16);
            g.setColor(body);
            g.fillRoundRect(ox + size / 4, size / 4 + bob, size / 2, size / 2, size / 6, size / 6);
            g.setColor(new Color(245, 210, 170));
            g.fillOval(ox + size / 3, size / 8 + bob, size / 3, size / 3);
            g.setColor(new Color(40, 40, 60));
            int legW = Math.max(2, size / 10);
            int legY = size * 3 / 4 + bob;
            int spread = (f % 2 == 0) ? size / 12 : size / 6;
            g.fillRect(ox + size / 2 - spread - legW, legY, legW, size / 5);
            g.fillRect(ox + size / 2 + spread, legY, legW, size / 5);
        }
        g.dispose();
        return img;
    }
}
