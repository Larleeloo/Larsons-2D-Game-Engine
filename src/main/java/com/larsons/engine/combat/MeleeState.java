package com.larsons.engine.combat;

import com.larsons.engine.combat.MeleeProfile.Move;

/**
 * One fighter's melee state machine: which {@link MeleeAction} is running, how
 * far through it they are, and what each of their moves is still cooling down
 * from. Players and mobs both own one, and both advance it with the same
 * {@link #step} — so a parry costs a mob exactly what it costs a player, and
 * the authoritative server, the client's prediction and single-player all
 * agree about who was mid-swing when a blow landed.
 *
 * <p>Every action runs the fighting-game shape — <b>wind-up → active →
 * recovery</b> — with the timings coming from the held object's
 * {@link MeleeProfile}:
 *
 * <pre>
 *   WINDUP   committed, nothing has happened yet (the tell)
 *   ACTIVE   the move is doing its work: the hit lands, the parry catches,
 *            the dash carries, the guard is up
 *   RECOVER  the tail you are stuck in before you can act again
 * </pre>
 *
 * <p>{@link MeleeAction#SHIELD} is the exception, and deliberately so: it is
 * <em>held</em>, so its active window lasts for as long as the button does
 * ({@link #holdShield}). Starting an attack out of a raised guard drops the
 * guard on the spot — that is what makes a shield bash feel like one instead
 * of like a queued command.
 *
 * <p>This class is pure simulation: no rendering, no audio, no world access.
 * What a strike actually <em>hits</em> is resolved by
 * {@link com.larsons.engine.world.World} when {@link #pollStrike()} reports
 * the active window opening, which is exactly once per move.
 */
public final class MeleeState {

    /** Where in a move the fighter is. */
    public enum Phase { IDLE, WINDUP, ACTIVE, RECOVER }

    /** Seconds a fighter is staggered for by having a blow parried. */
    public static final double PARRY_STAGGER = 0.55;

    /** Seconds a successful parry glows for (presentation only). */
    public static final double PARRY_FLASH = 0.3;

    private MeleeAction action = MeleeAction.NONE;
    private Phase phase = Phase.IDLE;
    private double phaseTime;
    private double actionTime;
    private double duration;          // the running one-shot's total length
    private double phaseLen;          // the current phase's length

    private final double[] cooldowns = new double[MeleeAction.values().length];

    private boolean shieldHeld;
    private double staggerTime;
    private double parryFlash;

    // One-shot events, drained by whoever is watching this tick.
    private MeleeAction begun = MeleeAction.NONE;
    private MeleeAction ended = MeleeAction.NONE;
    private boolean strikePending;
    private boolean travelPending;
    private boolean connected;

    public MeleeAction action() {
        return action;
    }

    public Phase phase() {
        return phase;
    }

    /** Seconds since the running move began (0 when idle). */
    public double actionTime() {
        return actionTime;
    }

    /**
     * How far through the running move the fighter is, 0..1 — what picks the
     * frame of its sheet, so a swing's animation plays exactly once across the
     * swing however long that weapon takes.
     *
     * <p>A held stance maps its raise onto the first half and its drop onto
     * the second, sitting at the midpoint for as long as it is held: draw a
     * shield sheet as "coming up, up, going down" and it reads correctly at
     * any hold length.
     */
    public double progress() {
        if (action == MeleeAction.NONE) return 0;
        if (action.held()) {
            double through = phaseLen <= 0 ? 1 : clamp01(phaseTime / phaseLen);
            return switch (phase) {
                case WINDUP -> 0.5 * through;
                case RECOVER -> 0.5 + 0.5 * through;
                default -> 0.5;
            };
        }
        return duration <= 0 ? 1 : clamp01(actionTime / duration);
    }

    /** The animation state name of the running move ({@code ""} when idle). */
    public String animationState() {
        return action == MeleeAction.NONE ? "" : action.key();
    }

    /** Whether a one-shot move is running (a held guard does not count). */
    public boolean busy() {
        return action != MeleeAction.NONE && !action.held();
    }

    /** Whether the guard is actually up (not still rising, not dropping). */
    public boolean guarding() {
        return action == MeleeAction.SHIELD && phase == Phase.ACTIVE;
    }

    /** Whether a parry window is open right now. */
    public boolean parrying() {
        return action == MeleeAction.PARRY && phase == Phase.ACTIVE;
    }

    /** Whether an evasive dash is carrying the fighter (and cannot be hit). */
    public boolean dashing() {
        return action == MeleeAction.DASH && phase == Phase.ACTIVE;
    }

    /** Whether the fighter is untouchable this instant (dash i-frames). */
    public boolean invulnerable() {
        return dashing();
    }

    /** Whether an offensive move's hit window is open. */
    public boolean striking() {
        return action.offensive() && phase == Phase.ACTIVE;
    }

    /** Whether a travelling move (lunge, dash) is carrying the fighter. */
    public boolean travelling() {
        return action.travels() && phase == Phase.ACTIVE;
    }

    /** Whether a parried blow has left this fighter reeling. */
    public boolean staggered() {
        return staggerTime > 0;
    }

    /** Seconds left on the glow of a parry that caught something. */
    public double parryFlash() {
        return parryFlash;
    }

    /** Seconds until {@code a} may be used again (0 = ready). */
    public double cooldown(MeleeAction a) {
        return a == null ? 0 : cooldowns[a.ordinal()];
    }

    /** Cooldown as a 0..1 fraction of the move's own cooldown, for the HUD. */
    public double cooldownFraction(MeleeAction a, MeleeProfile profile) {
        double full = profile == null ? 0 : profile.move(a).cooldown();
        return full <= 0 ? 0 : clamp01(cooldown(a) / full);
    }

    /**
     * Whether {@code a} could be started right now with {@code stamina} in the
     * tank: the profile has it, it is off cooldown, the fighter isn't
     * committed to something else or staggered, and it is affordable.
     */
    public boolean ready(MeleeAction a, MeleeProfile profile, double stamina) {
        if (a == null || a == MeleeAction.NONE || profile == null) return false;
        if (!profile.has(a)) return false;
        if (busy() || staggered()) return false;
        if (cooldowns[a.ordinal()] > 0) return false;
        return stamina >= profile.move(a).stamina();
    }

    /**
     * Start {@code a}, if it can be started. Returns whether it did — the
     * caller spends {@link #cost} out of the fighter's stamina on a true.
     *
     * <p>Starting anything out of a raised guard drops the guard first.
     */
    public boolean begin(MeleeAction a, MeleeProfile profile, double stamina) {
        if (!ready(a, profile, stamina)) return false;
        if (a == MeleeAction.SHIELD && action == MeleeAction.SHIELD) return false;
        Move m = profile.move(a);
        action = a;
        duration = a.held() ? 0 : m.duration();
        actionTime = 0;
        phaseTime = 0;
        cooldowns[a.ordinal()] = m.cooldown();
        begun = a;
        connected = false;
        strikePending = false;
        travelPending = false;
        if (m.windup() > 0) {
            phase = Phase.WINDUP;
            phaseLen = m.windup();
        } else {
            enterActive(m);
        }
        return true;
    }

    /** What starting {@code a} with this profile costs in stamina. */
    public static double cost(MeleeAction a, MeleeProfile profile) {
        return profile == null ? 0 : profile.move(a).stamina();
    }

    /**
     * Hold or release the guard stance — the "shield ready" button. Raising
     * takes the stance's wind-up; releasing sends it into recovery, so a guard
     * cannot be flicked on and off for free.
     */
    public void holdShield(boolean held, MeleeProfile profile, double stamina) {
        shieldHeld = held;
        if (held) {
            if (action == MeleeAction.NONE) begin(MeleeAction.SHIELD, profile, stamina);
        } else if (action == MeleeAction.SHIELD && phase != Phase.RECOVER) {
            phase = Phase.RECOVER;
            phaseTime = 0;
            phaseLen = profile == null ? 0 : profile.move(MeleeAction.SHIELD).recover();
        }
    }

    /** Whether the guard button is being held (even while the stance rises). */
    public boolean shieldHeld() {
        return shieldHeld;
    }

    /** Abandon whatever is running (death, a scene change, a level swap). */
    public void cancel() {
        action = MeleeAction.NONE;
        phase = Phase.IDLE;
        actionTime = 0;
        phaseTime = 0;
        duration = 0;
        phaseLen = 0;
        shieldHeld = false;
        strikePending = false;
        travelPending = false;
        connected = false;
        begun = MeleeAction.NONE;
        ended = MeleeAction.NONE;
    }

    /** Wipe everything, cooldowns included (a respawn). */
    public void reset() {
        cancel();
        java.util.Arrays.fill(cooldowns, 0);
        staggerTime = 0;
        parryFlash = 0;
    }

    /**
     * Advance one tick against the profile of whatever is <em>currently</em>
     * held — swapping weapons mid-swing finishes the swing on the new timings,
     * which is both simpler and less exploitable than freezing the old ones.
     */
    public void step(MeleeProfile profile, double dt) {
        for (int i = 0; i < cooldowns.length; i++) {
            if (cooldowns[i] > 0) cooldowns[i] = Math.max(0, cooldowns[i] - dt);
        }
        if (staggerTime > 0) staggerTime = Math.max(0, staggerTime - dt);
        if (parryFlash > 0) parryFlash = Math.max(0, parryFlash - dt);
        if (action == MeleeAction.NONE) return;

        Move m = profile == null ? Move.NONE : profile.move(action);
        actionTime += dt;
        phaseTime += dt;
        switch (phase) {
            case WINDUP -> {
                if (phaseTime >= m.windup()) {
                    phaseTime -= m.windup();
                    enterActive(m);
                }
            }
            case ACTIVE -> {
                // A held stance stays open until the button lets go; the
                // release itself is what sends it into recovery.
                if (action.held()) {
                    if (!shieldHeld) {
                        phase = Phase.RECOVER;
                        phaseTime = 0;
                        phaseLen = m.recover();
                    }
                } else if (phaseTime >= m.active()) {
                    phaseTime -= m.active();
                    phase = Phase.RECOVER;
                    phaseLen = m.recover();
                }
            }
            case RECOVER -> {
                if (phaseTime >= m.recover()) {
                    ended = action;
                    action = MeleeAction.NONE;
                    phase = Phase.IDLE;
                    phaseTime = 0;
                    actionTime = 0;
                    duration = 0;
                    phaseLen = 0;
                }
            }
            default -> { /* IDLE with an action set can't happen */ }
        }
    }

    /**
     * The move that started this tick, drained ({@link MeleeAction#NONE} when
     * none did) — the cue for the move's own start sound.
     */
    public MeleeAction pollBegun() {
        MeleeAction a = begun;
        begun = MeleeAction.NONE;
        return a;
    }

    /**
     * The move that finished this tick, drained — the cue for a held stance's
     * release sound ({@code shield_down}).
     */
    public MeleeAction pollEnded() {
        MeleeAction a = ended;
        ended = MeleeAction.NONE;
        return a;
    }

    /**
     * Whether an offensive move's hit window opened this tick, drained. This
     * fires exactly once per swing, and is what the world resolves damage on —
     * so a single swing can never land twice however the frame rate wobbles.
     */
    public boolean pollStrike() {
        boolean s = strikePending;
        strikePending = false;
        return s;
    }

    /**
     * Report that the running move connected — a swing that hit, a parry that
     * caught, a raised shield that ate a blow. Drives the connect sound and
     * the parry glow.
     */
    public void markConnected() {
        connected = true;
        if (action == MeleeAction.PARRY || action == MeleeAction.SHIELD) {
            parryFlash = PARRY_FLASH;
        }
    }

    /** Whether the running move connected since this was last asked. */
    public boolean pollConnected() {
        boolean c = connected;
        connected = false;
        return c;
    }

    /**
     * Light the guard up without reporting a connect. For blows the fighter
     * <em>received</em> and their stance stopped: the feedback is played by
     * whoever noticed (they know who was hit), and only the glow belongs here.
     */
    public void flashGuard() {
        parryFlash = PARRY_FLASH;
    }

    /**
     * Knock this fighter off balance — what having a blow parried costs, and
     * what a shield bash inflicts. A staggered fighter cannot start anything.
     */
    public void stagger(double seconds) {
        staggerTime = Math.max(staggerTime, Math.max(0, seconds));
        if (busy()) {
            // The parried swing is over; the recovery is the stagger.
            ended = action;
            action = MeleeAction.NONE;
            phase = Phase.IDLE;
            phaseTime = 0;
            actionTime = 0;
            phaseLen = 0;
            strikePending = false;
            travelPending = false;
        }
    }

    /**
     * Whether a travelling move's burst should be launched this tick, drained.
     * Fires once, as the active window opens — a lunge commits at the end of
     * its wind-up, not at the moment the key was pressed.
     */
    public boolean pollTravelStart() {
        boolean t = travelPending;
        travelPending = false;
        return t;
    }

    private void enterActive(Move m) {
        phase = Phase.ACTIVE;
        phaseLen = m.active();
        strikePending = action.offensive();
        travelPending = action.travels();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }
}
