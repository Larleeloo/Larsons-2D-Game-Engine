package com.larsons.engine.graphics;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The player character's sprite, shared by every place a player renders —
 * the play scene ("load level" play), creative mode's play-test, and remote
 * players online — so the character looks identical everywhere.
 *
 * <p>The default look is the procedural walk sheet drawn <em>per facing</em>
 * ({@link DirectionalSprites}). Like mobs, the player is reskinnable <em>per
 * action state</em> via the {@link Skins} system, and each state may further
 * be drawn <em>per direction</em>:
 *
 * <pre>
 *   player/walk          one sheet for walking, whichever way you face
 *   player/walk/e        walking east (right); mirrored for west
 *   player/walk/ne       walking north-east (isometric / top-down)
 *   character/rogue/walk the Rogue character profile's walk sheet
 * </pre>
 *
 * <p>{@link #directionalFrame} resolves the state and direction playing right
 * now, falling back through: this direction &rarr; its mirror twin (drawn
 * flipped) &rarr; the state's non-directional sheet &rarr; related states
 * (run&rarr;walk, fall&rarr;jump, swim&rarr;walk, everything&rarr;idle)
 * &rarr; the same chain for the default player when a character profile has
 * no art of its own &rarr; the legacy single {@value #SKIN_KEY} key &rarr; the
 * pre-generated directional art. So one assigned sheet is enough to reskin the
 * whole character, and a creator can hand-draw all eight directions if they
 * want to.
 */
public final class PlayerSprites {

    /** Legacy single-sheet skin key (still honoured as the last fallback). */
    public static final String SKIN_KEY = "player";

    /** Texture-key namespace of a named character profile's sheets. */
    public static final String CHARACTER_KEY = "character";

    /**
     * The player's skinnable action states, in the order the Player Skin…
     * dialog cycles them: standing still, walking, sprinting, rising in a
     * jump, falling, swimming in a liquid, and then the five melee moves
     * ({@link #COMBAT_STATES}).
     */
    public static final List<String> ACTION_STATES =
            List.of("idle", "walk", "run", "jump", "fall", "swim",
                    "swing", "parry", "lunge", "dash", "shield");

    /**
     * The melee moves' states — the tail of {@link #ACTION_STATES}. These are
     * <em>one-shot</em> animations: their sheet is played once across the
     * move ({@link #oneShot}) rather than looping in time, so a slow hammer
     * and a quick dagger each get the whole sheet in the time they take.
     *
     * <p>Mirrors {@code MeleeAction.states()}; the combat package owns the
     * moves themselves, this list is what the art side needs to know about
     * them.
     */
    public static final List<String> COMBAT_STATES =
            List.of("swing", "parry", "lunge", "dash", "shield");

    /** The default (unskinned) body colour of the local player. */
    public static final Color DEFAULT_BODY = new Color(70, 130, 220);

    /** Playback rate of the procedural walk cycle, sprite frames/sec. */
    public static final double WALK_FPS = 10;

    private PlayerSprites() {}

    /**
     * One resolved sprite: the image to draw and whether to flip it
     * horizontally first (a west-facing character drawn from east-facing art).
     */
    public record Frame(BufferedImage image, boolean mirrored) {}

    /** The {@link Skins} texture key of one action state's sheet. */
    public static String stateKey(String state) {
        return SKIN_KEY + "/" + state;
    }

    /** The texture key of one action state in one direction. */
    public static String stateKey(String state, Facing facing) {
        return facing == null ? stateKey(state) : stateKey(state) + "/" + facing.key();
    }

    /**
     * The texture key of a named character profile's action state, e.g.
     * {@code character/rogue/walk}. A blank character key means the default
     * player, whose sheets live under {@code player/…}.
     */
    public static String characterStateKey(String characterKey, String state) {
        return characterKey == null || characterKey.isBlank()
                ? stateKey(state)
                : CHARACTER_KEY + "/" + characterKey + "/" + state;
    }

    /** {@link #characterStateKey} for one direction. */
    public static String characterStateKey(String characterKey, String state, Facing facing) {
        String base = characterStateKey(characterKey, state);
        return facing == null ? base : base + "/" + facing.key();
    }

    /**
     * Classify what the player is doing right now into one of
     * {@link #ACTION_STATES}: swimming beats everything, airborne splits into
     * jump (rising) / fall (sinking), and on the ground it's run / walk /
     * idle. Top-down and isometric games report jump/fall from the plan-view
     * hop instead of gravity (see {@link PlayerState#z}).
     */
    public static String actionState(PlayerState s, Level level, GameProfile profile,
                                     Perspective perspective, boolean sprintHeld) {
        double size = s.hitSize(profile.playerSize);
        double ts = level.tileSize;
        boolean inLiquid = level.liquidAt(
                (int) Math.floor((s.x + size / 2.0) / ts),
                (int) Math.floor((s.y + size / 2.0) / ts)) != null;
        boolean sideScroll = perspective == Perspective.SIDE_SCROLL && profile.gravityEnabled;
        if (!sideScroll) {
            // Plan view: the hop's height is the airborne test, and its
            // vertical speed splits rising from falling.
            boolean airborne = s.z > 0.01;
            return actionState(!airborne, inLiquid, s.moving,
                    sprintHeld && s.moving, -s.vz);
        }
        boolean grounded = PlayerPhysics.onGround(level, s.x, s.y, size, size);
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
     * <p>This is the direction-agnostic form kept for callers that flip the
     * sprite themselves; {@link #directionalFrame} is what the scenes use.
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

    /**
     * The sprite for a character facing a direction: the most specific
     * assigned sheet, or the pre-generated directional walk art.
     *
     * @param characterKey the character profile's key ({@code ""}/{@code null}
     *                     for the default player)
     * @param state        one of {@link #ACTION_STATES}
     * @param facing       the direction the character faces
     * @param stateSeconds seconds since the state began (drives the frame)
     * @param size         the character's on-screen size, for generated art
     * @param body         the character's body colour, for generated art
     */
    public static Frame directionalFrame(String characterKey, String state, Facing facing,
                                         double stateSeconds, int size, Color body) {
        return directionalFrame(characterKey, state, facing, stateSeconds, 0, size, body);
    }

    /**
     * {@link #directionalFrame(String, String, Facing, double, int, Color)}
     * with a one-shot clock as well as a looping one.
     *
     * <p>A melee move's sheet plays exactly once across the move, so
     * {@code progress} (0..1 through the action) picks its frame; every other
     * state animates in time from {@code stateSeconds} as before. The chain
     * checks the candidate rather than the requested state, so a swing that
     * falls all the way back to the idle sheet still <em>idles</em> — it
     * doesn't freeze on the first frame.
     */
    public static Frame directionalFrame(String characterKey, String state, Facing facing,
                                         double stateSeconds, double progress,
                                         int size, Color body) {
        Facing dir = facing == null ? Facing.EAST : facing;
        boolean idle = "idle".equals(state);
        for (String candidate : fallbackChain(state)) {
            boolean once = oneShot(candidate);
            double t = once ? progress : (idle && !"idle".equals(candidate) ? 0 : stateSeconds);
            for (String base : characterChain(characterKey)) {
                // This exact direction, then the direction whose art it
                // mirrors, then the state's one-size-fits-all sheet.
                BufferedImage exact = read(directionKey(base, candidate, dir), t, once);
                if (exact != null) return new Frame(exact, false);
                if (dir.mirrored()) {
                    BufferedImage twin = read(
                            directionKey(base, candidate, dir.mirrorOf()), t, once);
                    if (twin != null) return new Frame(twin, true);
                }
                BufferedImage plain = read(stateKeyIn(base, candidate), t, once);
                if (plain != null) return new Frame(plain, dir.facingLeft());
            }
        }
        BufferedImage legacy = Skins.frame(SKIN_KEY, idle ? 0 : stateSeconds);
        if (legacy != null) return new Frame(legacy, dir.facingLeft());
        // Nothing assigned: the pre-generated per-direction walk cycle, frozen
        // on its first frame while idle. It is already drawn for this
        // direction, so the caller must not flip it.
        int frame = idle ? 0 : (int) Math.floor(stateSeconds * WALK_FPS);
        return new Frame(DirectionalSprites.frame(size, body, dir, frame), false);
    }

    /**
     * Whether a state's sheet plays once across the action (a melee move)
     * rather than looping in time (walking, swimming).
     */
    public static boolean oneShot(String state) {
        return COMBAT_STATES.contains(state);
    }

    private static BufferedImage read(String key, double t, boolean once) {
        return once ? Skins.progressFrame(key, t) : Skins.frame(key, t);
    }

    /**
     * The texture namespaces tried for a character, most specific first: its
     * own sheets, then the shared player ones — so a profile with no art of
     * its own still wears whatever the Player Skin… tool assigned.
     */
    private static List<String> characterChain(String characterKey) {
        List<String> chain = new ArrayList<>(2);
        if (characterKey != null && !characterKey.isBlank()) {
            chain.add(CHARACTER_KEY + "/" + characterKey);
        }
        chain.add(SKIN_KEY);
        return chain;
    }

    private static String stateKeyIn(String base, String state) {
        return base + "/" + state;
    }

    private static String directionKey(String base, String state, Facing facing) {
        return base + "/" + state + "/" + facing.key();
    }

    /** The state keys tried for {@code state}, most specific first. */
    private static String[] fallbackChain(String state) {
        return switch (state == null ? "" : state) {
            case "run" -> new String[]{"run", "walk", "idle"};
            case "jump" -> new String[]{"jump", "idle", "walk"};
            case "fall" -> new String[]{"fall", "jump", "idle", "walk"};
            case "swim" -> new String[]{"swim", "walk", "idle"};
            case "walk" -> new String[]{"walk", "idle"};
            // The melee moves. Each one ends at idle, which is what makes
            // combat art optional: a character with one standing sheet fights
            // in it until somebody draws the swing.
            case "swing" -> new String[]{"swing", "idle"};
            case "lunge" -> new String[]{"lunge", "swing", "idle"};
            case "parry" -> new String[]{"parry", "shield", "idle"};
            case "shield" -> new String[]{"shield", "parry", "idle"};
            case "dash" -> new String[]{"dash", "run", "walk", "idle"};
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
     * This is the direction-agnostic sheet; {@link DirectionalSprites} draws
     * the per-facing art the renderer actually uses.
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
