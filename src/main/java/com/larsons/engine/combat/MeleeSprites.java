package com.larsons.engine.combat;

import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * What a fighter and the object in their hands <em>look like</em> mid-move.
 *
 * <p>Two sheets are resolved here, and they are independent:
 *
 * <ol>
 *   <li><b>The wielder.</b> A held object may bring its own full-body art for
 *       whoever is holding it — {@code wield/<item>/<state>} — so a Frostmourne
 *       can have a two-handed overhead swing while a dagger flicks, on the same
 *       character. Nothing has to exist: the chain falls back to the item's
 *       plain {@code wield/<item>} sheet ("holding it, idle"), then to the
 *       fighter's own {@code player/…} / {@code character/…} / {@code mob/…}
 *       art, and finally to the engine's procedural character — which is why
 *       adding melee combat changed how nothing looks until somebody draws
 *       something.</li>
 *   <li><b>The object.</b> {@code item/<item>/<state>} is the sword itself,
 *       drawn in hand and swept through the arc, falling back to the item's
 *       plain icon ({@code item/<item>}) when a creator hasn't animated it.</li>
 * </ol>
 *
 * <p>Both chains follow the engine's usual rule — most specific first, one
 * segment dropped at a time, with the west-facing directions mirroring their
 * eastern twin — and both are driven by {@link MeleeState#progress()} rather
 * than by wall-clock seconds, so an action's sheet plays exactly once across
 * the action.
 *
 * <pre>
 *   wield/iron_sword/swing/e   the player swinging an Iron Sword, facing east
 *   wield/iron_sword/swing     …whichever way they face
 *   wield/iron_sword           …just holding it (the idle fallback)
 *   item/iron_sword/swing      the blade's own swing sheet
 *   item/iron_sword            the blade's icon (the idle fallback)
 * </pre>
 */
public final class MeleeSprites {

    /** Texture-key namespace of the "fighter holding this object" sheets. */
    public static final String WIELD_KEY = "wield";

    /** The state a wield sheet falls back to when the move has no art. */
    public static final String IDLE_STATE = "idle";

    private MeleeSprites() {}

    /** The wielder-sheet states a creator may draw, idle first. */
    public static List<String> wieldStates() {
        List<String> out = new ArrayList<>();
        out.add(IDLE_STATE);
        out.addAll(MeleeAction.states());
        return out;
    }

    /** {@code wield/<item>/<state>} — the fighter holding an object, mid-state. */
    public static String wieldKey(String itemKey, String state) {
        return WIELD_KEY + "/" + itemKey + "/" + state;
    }

    /** {@link #wieldKey(String, String)} for one direction. */
    public static String wieldKey(String itemKey, String state, Facing facing) {
        String base = wieldKey(itemKey, state);
        return facing == null ? base : base + "/" + facing.key();
    }

    /** {@code item/<item>/<state>} — the object itself, mid-state. */
    public static String heldKey(String itemKey, String state) {
        return "item/" + itemKey + "/" + state;
    }

    /**
     * The frame of the fighter's body: the held object's own art for this
     * state if it has any, and the fighter's own art otherwise.
     *
     * @param characterKey the character profile being played ({@code ""} = default)
     * @param itemKey      what is in their hands ({@code ""}/null = empty-handed)
     * @param state        the action state — a movement one ({@code walk}) or a
     *                     melee one ({@code swing})
     * @param facing       the direction they face
     * @param seconds      seconds in the state, for looping movement animations
     * @param progress     0..1 through a melee move, for one-shot animations
     *                     (ignored for movement states)
     * @param size         the character's on-screen size, for generated art
     * @param body         the character's body colour, for generated art
     */
    public static PlayerSprites.Frame playerFrame(String characterKey, String itemKey,
                                                  String state, Facing facing,
                                                  double seconds, double progress,
                                                  int size, Color body) {
        return playerFrame(characterKey, itemKey, state, facing, seconds, progress,
                size, body, false);
    }

    /**
     * {@link #playerFrame(String, String, String, Facing, double, double, int, Color)}
     * with the pool the camera's tilt has chosen — see {@code PlayerSprites}.
     *
     * <p><b>A wield sheet still wins, in either view.</b> It is art somebody
     * drew for this object in this hand, and the alternative — dropping it the
     * moment the camera passes 75° — would take a creator's own work away from
     * them at an angle they may not have thought to check. The fighter's own
     * pool is what changes underneath it, which is the case that matters:
     * nothing ships with wield art, so the overhead view a player actually meets
     * is the overhead one.
     */
    public static PlayerSprites.Frame playerFrame(String characterKey, String itemKey,
                                                  String state, Facing facing,
                                                  double seconds, double progress,
                                                  int size, Color body, boolean overhead) {
        PlayerSprites.Frame wielded = wieldFrame(itemKey, state, facing, seconds, progress);
        if (wielded != null) return wielded;
        return PlayerSprites.directionalFrame(characterKey, state, facing,
                seconds, progress, size, body, overhead);
    }

    /**
     * The frame of a mob's body, with the same held-object override players
     * get — an orc issued a Battle Axe swings the axe's sheet. Returns
     * {@code null} when neither the weapon nor the species has art for this
     * state, which is the caller's cue to draw the procedural critter.
     */
    public static PlayerSprites.Frame mobFrame(String mobKey, String itemKey,
                                               String state, Facing facing,
                                               double seconds, double progress) {
        PlayerSprites.Frame wielded = wieldFrame(itemKey, state, facing, seconds, progress);
        if (wielded != null) return wielded;
        Facing dir = facing == null ? Facing.EAST : facing;
        String base = "mob/" + mobKey;
        for (String candidate : stateChain(state)) {
            double t = timeFor(candidate, seconds, progress);
            boolean oneShot = isMeleeState(candidate);
            BufferedImage exact = read(base + "/" + candidate + "/" + dir.key(), t, oneShot);
            if (exact != null) return new PlayerSprites.Frame(exact, false);
            if (dir.mirrored()) {
                BufferedImage twin = read(base + "/" + candidate + "/" + dir.mirrorOf().key(),
                        t, oneShot);
                if (twin != null) return new PlayerSprites.Frame(twin, true);
            }
            BufferedImage plain = read(base + "/" + candidate, t, oneShot);
            if (plain != null) return new PlayerSprites.Frame(plain, dir.facingLeft());
        }
        return null;
    }

    /**
     * The held object's own sheet for this state, or {@code null} when the
     * item has no art at all (the caller draws its procedural icon).
     */
    public static BufferedImage heldFrame(String itemKey, String state,
                                          double seconds, double progress) {
        if (itemKey == null || itemKey.isBlank()) return null;
        for (String candidate : stateChain(state)) {
            BufferedImage img = read(heldKey(itemKey, candidate),
                    timeFor(candidate, seconds, progress), isMeleeState(candidate));
            if (img != null) return img;
        }
        // The plain icon: an item with no animated states at all still shows
        // up in the fighter's hand.
        return Skins.frame("item/" + itemKey, seconds);
    }

    /**
     * Where the object in hand sits this frame, relative to the fighter's
     * centre — in fractions of their body size, so it scales with zoom and
     * with the game type's player size.
     *
     * <p>The motion is deliberately simple and shared by every scene that
     * draws a fighter: a swing sweeps the object through the profile's arc, a
     * lunge shoves it out along the facing, a parry holds it up across the
     * body, a dash tucks it in, and a raised guard plants it in front.
     *
     * @param offsetX  sideways offset along the facing, in body widths
     * @param offsetY  vertical offset, in body heights (negative is up)
     * @param angle    rotation in radians, applied about the object's centre
     * @param scale    size of the object as a fraction of the body
     */
    public record Hold(double offsetX, double offsetY, double angle, double scale) {}

    /** {@link Hold} for a fighter mid-{@code action}, {@code progress} through it. */
    public static Hold hold(MeleeAction action, MeleeProfile profile, double progress) {
        double arc = Math.toRadians(profile == null ? 100 : profile.arc());
        double t = progress < 0 ? 0 : Math.min(1, progress);
        return switch (action) {
            // The blade travels from behind the shoulder to past the knee.
            case SWING -> new Hold(0.30 + 0.28 * Math.sin(Math.PI * t), -0.14,
                    -arc / 2 + arc * t, 0.85);
            // A thrust: out along the facing, held level.
            case LUNGE -> new Hold(0.34 + 0.42 * bump(t), -0.06, 0.05, 0.95);
            // Held up across the body, edge out, barely moving.
            case PARRY -> new Hold(0.30, -0.26, -1.15, 0.8);
            // Tucked in while the fighter rolls out of the way.
            case DASH -> new Hold(0.12, 0.04, -0.9, 0.7);
            // Planted in front — the guard stance.
            case SHIELD -> new Hold(0.38, -0.10, 0.0, 1.0);
            default -> new Hold(0.26, -0.02, -0.5, 0.75);
        };
    }

    /** Whether {@code state} names one of the melee moves (a one-shot animation). */
    public static boolean isMeleeState(String state) {
        return MeleeAction.byKey(state) != MeleeAction.NONE;
    }

    /**
     * The wield chain: the held object's art for the fighter's body, most
     * specific first, or {@code null} when the object brings none.
     */
    private static PlayerSprites.Frame wieldFrame(String itemKey, String state, Facing facing,
                                                  double seconds, double progress) {
        if (itemKey == null || itemKey.isBlank()) return null;
        Facing dir = facing == null ? Facing.EAST : facing;
        for (String candidate : stateChain(state)) {
            double t = timeFor(candidate, seconds, progress);
            boolean oneShot = isMeleeState(candidate);
            BufferedImage exact = read(wieldKey(itemKey, candidate, dir), t, oneShot);
            if (exact != null) return new PlayerSprites.Frame(exact, false);
            if (dir.mirrored()) {
                BufferedImage twin = read(wieldKey(itemKey, candidate, dir.mirrorOf()),
                        t, oneShot);
                if (twin != null) return new PlayerSprites.Frame(twin, true);
            }
            BufferedImage plain = read(wieldKey(itemKey, candidate), t, oneShot);
            if (plain != null) return new PlayerSprites.Frame(plain, dir.facingLeft());
        }
        return null;
    }

    /**
     * The states tried for {@code state}, most specific first — every chain
     * ending at {@code idle}, which is the promise this whole system makes:
     * one sheet is enough, everything else is an upgrade.
     */
    public static List<String> stateChain(String state) {
        String s = state == null ? "" : state;
        return switch (s) {
            // A lunge is a swing with commitment; a bash is a swing.
            case "lunge" -> List.of("lunge", "swing", "attack", IDLE_STATE);
            case "swing" -> List.of("swing", "attack", IDLE_STATE);
            // A parry is the guard, taken actively.
            case "parry" -> List.of("parry", "shield", IDLE_STATE);
            case "shield" -> List.of("shield", "parry", IDLE_STATE);
            // Footwork borrows the run cycle before it gives up.
            case "dash" -> List.of("dash", "run", "walk", IDLE_STATE);
            case "" -> List.of(IDLE_STATE);
            case IDLE_STATE -> List.of(IDLE_STATE);
            default -> List.of(s, IDLE_STATE);
        };
    }

    /** A melee state plays once across the move; everything else loops in time. */
    private static double timeFor(String state, double seconds, double progress) {
        return isMeleeState(state) ? progress : seconds;
    }

    private static BufferedImage read(String key, double t, boolean oneShot) {
        return oneShot ? Skins.progressFrame(key, t) : Skins.frame(key, t);
    }

    /** A 0 → 1 → 0 push: a thrust that goes out and comes back. */
    private static double bump(double t) {
        return Math.sin(Math.PI * (t < 0 ? 0 : Math.min(1, t)));
    }
}
