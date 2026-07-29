package com.larsons.engine.combat;

import com.larsons.engine.audio.SoundKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * What a melee move sounds like: the ordered list of sound keys one event
 * tries, most specific first, for {@link com.larsons.engine.audio.Sounds#playFirst}.
 *
 * <p>The order is the engine's usual one — <b>the object, then the fighter,
 * then the generic action</b> — so a creator can voice as much or as little as
 * they like and never hear silence where they used to hear something:
 *
 * <pre>
 *   item/iron_sword/swing        the blade's own sound, if the pack has one
 *   character/rogue/swing        …else this character swinging anything
 *   player/swing                 …else any player swinging anything
 *   player/attack                …else the generic attack the engine always had
 * </pre>
 *
 * <p>Every one of those files is optional and every one of them defaults to
 * silence, exactly like the rest of {@link SoundKeys} — this only decides
 * which names are asked for, and in what order.
 */
public final class MeleeSounds {

    private MeleeSounds() {}

    /** Keys for a player starting {@code action} while holding {@code itemKey}. */
    public static String[] playerStart(String characterKey, String itemKey,
                                       MeleeAction action) {
        return player(characterKey, itemKey, action.startSound(), legacyStart(action));
    }

    /** Keys for a player's {@code action} connecting — a hit, a catch, a block. */
    public static String[] playerHit(String characterKey, String itemKey,
                                     MeleeAction action) {
        return player(characterKey, itemKey, action.hitSound(), legacyHit(action));
    }

    /** Keys for a player lowering a held stance ({@code shield_down}). */
    public static String[] playerEnd(String characterKey, String itemKey,
                                     MeleeAction action) {
        return player(characterKey, itemKey, action.endSound(), "");
    }

    /** Keys for a mob starting {@code action} with whatever it carries. */
    public static String[] mobStart(String mobKey, String itemKey, MeleeAction action) {
        return mob(mobKey, itemKey, action.startSound(), legacyStart(action));
    }

    /** Keys for a mob's {@code action} connecting. */
    public static String[] mobHit(String mobKey, String itemKey, MeleeAction action) {
        return mob(mobKey, itemKey, action.hitSound(), legacyHit(action));
    }

    /**
     * The full candidate list for a player: the held object's take on the
     * state, this character's, the plain player's, then the generic action
     * the engine had before melee moves existed.
     */
    public static String[] player(String characterKey, String itemKey,
                                  String state, String legacyState) {
        if (state == null || state.isEmpty()) return new String[0];
        List<String> keys = new ArrayList<>(4);
        addItem(keys, itemKey, state);
        String character = SoundKeys.character(characterKey, state);
        String plain = SoundKeys.player(state);
        if (!character.equals(plain)) keys.add(character);
        keys.add(plain);
        if (legacyState != null && !legacyState.isEmpty()) {
            String legacy = SoundKeys.character(characterKey, legacyState);
            if (!legacy.equals(SoundKeys.player(legacyState))) keys.add(legacy);
            keys.add(SoundKeys.player(legacyState));
        }
        return keys.toArray(new String[0]);
    }

    /** The mob twin of {@link #player}: the object, the species, the species' attack. */
    public static String[] mob(String mobKey, String itemKey,
                               String state, String legacyState) {
        if (state == null || state.isEmpty()) return new String[0];
        List<String> keys = new ArrayList<>(3);
        addItem(keys, itemKey, state);
        keys.add(SoundKeys.mob(mobKey, state));
        if (legacyState != null && !legacyState.isEmpty()) {
            keys.add(SoundKeys.mob(mobKey, legacyState));
        }
        return keys.toArray(new String[0]);
    }

    /** What a pack with no melee files at all still plays as a move begins. */
    private static String legacyStart(MeleeAction action) {
        return action.offensive() ? "attack" : "";
    }

    /** …and as one connects: every connect is an impact of some sort. */
    private static String legacyHit(MeleeAction action) {
        return action.hitSound().isEmpty() ? "" : "attack_hit";
    }

    private static void addItem(List<String> keys, String itemKey, String state) {
        if (itemKey != null && !itemKey.isBlank()) {
            keys.add(SoundKeys.item(itemKey, state));
        }
    }
}
