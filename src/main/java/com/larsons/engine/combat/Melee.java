package com.larsons.engine.combat;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.sim.PlayerState;

/**
 * The glue between a player's {@link PlayerState} and their
 * {@link MeleeState}: starting a move (and paying for it), advancing the
 * machine, and mirroring the stance it produces back onto the state so the
 * damage funnel, the physics step, the renderer and the snapshot all read one
 * place.
 *
 * <p>Everything here is deterministic and side-effect-free beyond the two
 * objects it is handed, which is what lets the identical calls run in
 * single-player, in the creative play-test, in client-side prediction and on
 * the authoritative server — the same rule the rest of the simulation follows.
 *
 * <p>What a strike <em>hits</em> is not decided here: the world resolves that
 * when {@link MeleeState#pollStrike()} reports the hit window opening (see
 * {@code World.meleeStrike}).
 */
public final class Melee {

    private Melee() {}

    /** The profile for whatever a player is holding (null hands = fists). */
    public static MeleeProfile profileOf(ItemDef held) {
        return MeleeProfiles.of(held);
    }

    /**
     * Start {@code action}, paying its stamina out of {@code s}. Returns
     * whether it started — a move on cooldown, unaffordable, unsupported by
     * the held object, or interrupted by a stagger simply doesn't.
     */
    public static boolean start(PlayerState s, MeleeState melee, MeleeProfile profile,
                                MeleeAction action) {
        if (s == null || melee == null || action == null) return false;
        // A running ultimate that makes sprinting free makes fighting free too.
        double stamina = s.ultTireless ? Double.MAX_VALUE : s.stamina;
        if (!melee.begin(action, profile, stamina)) return false;
        if (!s.ultTireless) {
            s.stamina = Math.max(0, s.stamina - MeleeState.cost(action, profile));
        }
        return true;
    }

    /**
     * Advance one tick: hold or drop the guard, step the machine, launch a
     * committed burst if one just started, and mirror the resulting stance
     * onto the player state.
     *
     * @param heldKey    the item key in hand ({@code ""} = empty-handed)
     * @param shieldHeld whether the guard key is down this tick
     * @param planar     whether the level walks a plane (top-down / isometric),
     *                   where a burst travels in two axes rather than one
     */
    public static void step(PlayerState s, MeleeState melee, MeleeProfile profile,
                            String heldKey, boolean shieldHeld, boolean planar,
                            double dt) {
        if (s == null || melee == null) return;
        double stamina = s.ultTireless ? Double.MAX_VALUE : s.stamina;
        melee.holdShield(shieldHeld, profile, stamina);
        melee.step(profile, dt);
        if (melee.pollTravelStart()) {
            MeleeProfile.Move m = profile.move(melee.action());
            launch(s, m.burstSpeed(), m.active(), planar);
        }
        mirror(s, melee, profile, heldKey);
    }

    /**
     * Copy the melee stance onto the player state — what the damage funnel,
     * the physics step and the wire read. Called by {@link #step}; exposed for
     * the places that step the machine themselves.
     */
    public static void mirror(PlayerState s, MeleeState melee, MeleeProfile profile,
                              String heldKey) {
        s.meleeAction = melee.animationState();
        s.meleeProgress = melee.progress();
        s.heldKey = heldKey == null ? "" : heldKey;
        s.guarding = melee.guarding();
        s.guardFraction = profile == null ? 0 : profile.blockFraction();
        s.parrying = melee.parrying();
        s.invulnerable = melee.invulnerable();
    }

    /** Clear the stance (death, respawn, leaving a level). */
    public static void clear(PlayerState s, MeleeState melee) {
        if (melee != null) melee.reset();
        if (s == null) return;
        s.meleeAction = "";
        s.meleeProgress = 0;
        s.guarding = false;
        s.guardFraction = 0;
        s.parrying = false;
        s.invulnerable = false;
        s.dashVx = 0;
        s.dashVy = 0;
        s.dashTime = 0;
    }

    /**
     * The damage a move lands: the wielder's base melee damage scaled by the
     * move ({@link MeleeProfile.Move#damageScale}) — a lunge is worth two
     * swings, a shield bash is worth half of one.
     */
    public static double damage(double base, MeleeProfile profile, MeleeAction action) {
        return base * (profile == null ? 1 : profile.move(action).damageScale());
    }

    /**
     * The point a strike lands: from the fighter's centre toward the aim,
     * capped at the profile's reach. Shared by the world's hit resolution and
     * by the scenes' swing arc, so what is drawn is what is hit.
     *
     * @return {@code {x, y}} in world coordinates
     */
    public static double[] strikePoint(double centreX, double centreY,
                                       double aimX, double aimY, double reach) {
        double dx = aimX - centreX, dy = aimY - centreY;
        double len = Math.hypot(dx, dy);
        if (len <= reach || len < 0.0001) return new double[]{aimX, aimY};
        return new double[]{centreX + dx / len * reach, centreY + dy / len * reach};
    }

    /**
     * Throw the fighter into a committed burst along the way they face. A
     * side-scroller only travels sideways; on a plane the burst follows the
     * full compass heading, normalized so a diagonal isn't faster.
     */
    private static void launch(PlayerState s, double speed, double seconds, boolean planar) {
        if (speed <= 0 || seconds <= 0) return;
        Facing dir = s.facing == null ? Facing.side(s.facingLeft) : s.facing;
        double dx = dir.dx();
        double dy = planar ? dir.dy() : 0;
        if (dx == 0 && dy == 0) dx = s.facingLeft ? -1 : 1;
        double len = Math.hypot(dx, dy);
        s.dashVx = dx / len * speed;
        s.dashVy = dy / len * speed;
        // Exactly the move's active window: the fighter travels for as long as
        // the move is doing its work, and no longer.
        s.dashTime = seconds;
    }
}
