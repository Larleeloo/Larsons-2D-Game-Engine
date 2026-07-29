package com.larsons.engine.combat;

import java.util.EnumMap;
import java.util.Map;

/**
 * How one held object fights: its reach, the shape of its arc, what a raised
 * guard soaks, and the timing of every {@link MeleeAction} it can perform.
 *
 * <p>This is the data row behind melee combat, in the same spirit as
 * {@link com.larsons.engine.entity.ItemDef} and
 * {@link com.larsons.engine.entity.MobDef}: a dagger and a war hammer run the
 * identical state machine ({@link MeleeState}) and differ only in the numbers
 * here. {@link MeleeStyle} builds the presets, {@link MeleeProfiles} resolves
 * which one an item uses (and lets a game type override it).
 *
 * <p>An action the profile has no {@link Move} for simply cannot be performed —
 * that is how a pickaxe swings but never lunges, and how a shield parries
 * better than it strikes.
 *
 * @param key            the item key this describes ({@code ""} = bare hands)
 * @param style          the preset it was built from
 * @param reach          world px from the fighter's centre the strike carries
 * @param arc            width of the strike arc in degrees (also the drawn arc)
 * @param knockback      extra shove added to whatever the strike lands on
 * @param blockFraction  fraction of incoming damage a raised guard soaks, 0..1
 * @param moves          the actions this object can perform, and their timings
 */
public record MeleeProfile(String key, MeleeStyle style, double reach, double arc,
                           double knockback, double blockFraction,
                           Map<MeleeAction, Move> moves) {

    /**
     * One action's shape in time and what it costs.
     *
     * <p>The three phases are the fighting-game ones and they are what makes a
     * hammer feel like a hammer: {@code windup} is the tell (committed, no
     * damage yet), {@code active} is the window where the move does its work,
     * {@code recover} is the tail you are stuck in afterwards.
     *
     * @param windup      seconds of wind-up before the move does anything
     * @param active      seconds the move is doing its work (the hit window,
     *                    the parry window, the length of the burst)
     * @param recover     seconds of recovery after the active window closes
     * @param cooldown    seconds before the move may be used again, from the
     *                    moment it started
     * @param stamina     stamina the move costs to start
     * @param damageScale multiplier on the wielder's damage for this move
     * @param burstSpeed  px/sec the move carries the fighter (LUNGE, DASH)
     */
    public record Move(double windup, double active, double recover, double cooldown,
                       double stamina, double damageScale, double burstSpeed) {

        /** A move that does nothing, returned for actions a profile lacks. */
        public static final Move NONE = new Move(0, 0, 0, 0, 0, 0, 0);

        public Move {
            windup = Math.max(0, windup);
            active = Math.max(0, active);
            recover = Math.max(0, recover);
            cooldown = Math.max(0, cooldown);
            stamina = Math.max(0, stamina);
            damageScale = Math.max(0, damageScale);
            burstSpeed = Math.max(0, burstSpeed);
        }

        /** Total time the move occupies the fighter. */
        public double duration() {
            return windup + active + recover;
        }
    }

    public MeleeProfile {
        key = key == null ? "" : key;
        reach = Math.max(1, reach);
        arc = Math.max(5, Math.min(360, arc));
        knockback = Math.max(0, knockback);
        blockFraction = Math.max(0, Math.min(1, blockFraction));
        // Defensive copy: profiles are shared between the simulation and the
        // renderer, and a shared mutable map would be a fine way to desync them.
        Map<MeleeAction, Move> copy = new EnumMap<>(MeleeAction.class);
        if (moves != null) {
            for (Map.Entry<MeleeAction, Move> e : moves.entrySet()) {
                if (e.getKey() != null && e.getKey() != MeleeAction.NONE && e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
        }
        moves = copy;
    }

    /** Whether this object can perform {@code action} at all. */
    public boolean has(MeleeAction action) {
        return action != null && moves.containsKey(action);
    }

    /** {@code action}'s timing, or {@link Move#NONE} when it isn't in the set. */
    public Move move(MeleeAction action) {
        Move m = action == null ? null : moves.get(action);
        return m == null ? Move.NONE : m;
    }

    /** How long {@code action} takes end to end (0 when unsupported). */
    public double duration(MeleeAction action) {
        return move(action).duration();
    }

    /** This profile with a different block fraction (rarity scaling). */
    public MeleeProfile withBlockFraction(double fraction) {
        return new MeleeProfile(key, style, reach, arc, knockback, fraction, moves);
    }

    /** This profile with a different reach (a big mob's arms are longer). */
    public MeleeProfile withReach(double px) {
        return new MeleeProfile(key, style, px, arc, knockback, blockFraction, moves);
    }

    /** This profile attributed to another item key (a custom item reusing a preset). */
    public MeleeProfile withKey(String itemKey) {
        return new MeleeProfile(itemKey, style, reach, arc, knockback, blockFraction, moves);
    }
}
