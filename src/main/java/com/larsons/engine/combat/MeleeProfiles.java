package com.larsons.engine.combat;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which {@link MeleeProfile} a held object fights with — the melee twin of
 * {@link com.larsons.engine.entity.ItemRegistry}.
 *
 * <p>Nothing has to be registered for combat to work. Every item already in
 * the game <em>derives</em> a profile from what it plainly is: a
 * {@code battle_axe} chops, a {@code throwing_knife} flicks, a
 * {@code diamond_pickaxe} swings like the tool it is, a staff jabs with its
 * pommel, and anything that isn't a weapon at all is swung like a fist. That
 * is what makes the whole feature work on existing content and on custom items
 * a creator adds with "+ New Item…" without them touching anything.
 *
 * <p>Two levels of override sit on top, for creators who want more:
 * <ol>
 *   <li>{@link #setStyle} — "this item fights like a spear", which keeps the
 *       preset timings; and</li>
 *   <li>{@link #register} — a whole hand-written {@link MeleeProfile} for one
 *       item, when the preset isn't the answer.</li>
 * </ol>
 *
 * <p>Resolved profiles are cached per key, so the game loop and the
 * authoritative server both read them for free; {@link #reset} drops the cache
 * (a game type switching its custom content away).
 */
public final class MeleeProfiles {

    /** The item key bare hands fight under — an empty hand is still a weapon. */
    public static final String FIST_KEY = "";

    private static final Map<String, MeleeProfile> REGISTERED = new ConcurrentHashMap<>();
    private static final Map<String, MeleeStyle> STYLES = new ConcurrentHashMap<>();
    private static final Map<String, MeleeProfile> CACHE = new ConcurrentHashMap<>();

    private static final MeleeProfile FISTS = MeleeStyle.FIST.profile(FIST_KEY);

    private MeleeProfiles() {}

    /** How an empty hand fights. */
    public static MeleeProfile fists() {
        return FISTS;
    }

    /**
     * The profile for what a fighter is holding. A {@code null} item — nothing
     * in hand, or a game type with items switched off — is {@link #fists()}.
     */
    public static MeleeProfile of(ItemDef held) {
        if (held == null) return FISTS;
        MeleeProfile cached = CACHE.get(held.key());
        if (cached != null) return cached;
        MeleeProfile resolved = resolve(held);
        CACHE.put(held.key(), resolved);
        return resolved;
    }

    /**
     * The profile for an item named by key, looked up in the standard
     * registry. Used where only the key travelled (a snapshot, a mob's
     * {@link MobDef#weapon()}).
     */
    public static MeleeProfile ofKey(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) return FISTS;
        MeleeProfile cached = CACHE.get(itemKey);
        if (cached != null) return cached;
        ItemDef def = ItemRegistry.standard().get(itemKey);
        if (def != null) return of(def);
        // An item key nothing knows about (a level from a game type whose
        // custom content isn't loaded): swing it like a plain sword rather
        // than dropping the fighter's weapon on the floor.
        MeleeProfile guessed = styleFor(itemKey, null).profile(itemKey);
        CACHE.put(itemKey, guessed);
        return guessed;
    }

    /**
     * How a mob fights: with the weapon its species carries when it has one,
     * and otherwise with claws and teeth sized to the animal — a bear swings
     * like a hammer, a rat like a dagger. Reach follows the species' own
     * {@link MobDef#attackRange()}, so a dragon's bite lands where its AI says
     * it should.
     */
    public static MeleeProfile forMob(MobDef def) {
        if (def == null) return FISTS;
        MeleeProfile base = def.weapon() != null && !def.weapon().isBlank()
                ? ofKey(def.weapon())
                : naturalStyle(def).profile("mob/" + def.key());
        double reach = Math.max(base.reach(), def.attackRange() + def.size() * 0.5);
        return base.reach() >= reach ? base : base.withReach(reach);
    }

    /** Force {@code itemKey} to fight with a preset's timings. */
    public static void setStyle(String itemKey, MeleeStyle style) {
        if (itemKey == null || itemKey.isBlank() || style == null) return;
        STYLES.put(itemKey, style);
        CACHE.remove(itemKey);
    }

    /** The style assigned to {@code itemKey}, or {@code null} when derived. */
    public static MeleeStyle assignedStyle(String itemKey) {
        return itemKey == null ? null : STYLES.get(itemKey);
    }

    /** Give one item a hand-written profile, replacing whatever it derived. */
    public static void register(MeleeProfile profile) {
        if (profile == null || profile.key().isBlank()) return;
        REGISTERED.put(profile.key(), profile);
        CACHE.remove(profile.key());
    }

    /** Forget every override and every cached profile (a game type switching). */
    public static void reset() {
        REGISTERED.clear();
        STYLES.clear();
        CACHE.clear();
    }

    /** Drop the cached profile for one key, so it re-derives on next use. */
    public static void forget(String itemKey) {
        if (itemKey != null) CACHE.remove(itemKey);
    }

    /** The style an item fights with: assigned if it was, derived otherwise. */
    public static MeleeStyle styleOf(ItemDef def) {
        if (def == null) return MeleeStyle.FIST;
        MeleeStyle assigned = STYLES.get(def.key());
        return assigned != null ? assigned : styleFor(def.key(), def);
    }

    private static MeleeProfile resolve(ItemDef def) {
        MeleeProfile registered = REGISTERED.get(def.key());
        if (registered != null) return registered;
        return styleOf(def).profile(def.key(), def.rarity().ordinal());
    }

    /**
     * What an item plainly is, read off its category and its name. Names are
     * checked before categories so a "Bone Dagger" registered as a plain
     * WEAPON still flicks instead of swinging like a broadsword.
     */
    private static MeleeStyle styleFor(String key, ItemDef def) {
        String name = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (def != null) name = name + " " + def.name().toLowerCase(Locale.ROOT);
        if (contains(name, "shield", "buckler", "aegis", "bulwark")) return MeleeStyle.SHIELD;
        if (contains(name, "dagger", "knife", "shiv", "dirk", "claw")) return MeleeStyle.DAGGER;
        if (contains(name, "hammer", "maul", "mace", "club", "cudgel", "breaker")) {
            return MeleeStyle.HAMMER;
        }
        if (contains(name, "spear", "lance", "pike", "halberd", "glaive", "trident")) {
            return MeleeStyle.SPEAR;
        }
        if (contains(name, "axe", "cleaver", "hatchet")) {
            // "Pickaxe" is a tool, not a battle axe — its tool class says so.
            boolean pick = def != null && "pickaxe".equals(def.toolClass());
            return pick ? MeleeStyle.TOOL : MeleeStyle.AXE;
        }
        if (contains(name, "sword", "blade", "sabre", "saber", "katana", "scimitar")) {
            return MeleeStyle.SWORD;
        }
        if (def == null) return MeleeStyle.SWORD;
        return switch (def.category()) {
            case WEAPON -> MeleeStyle.SWORD;
            case RANGED_WEAPON -> MeleeStyle.STAFF;
            case TOOL -> MeleeStyle.TOOL;
            case ARMOR -> MeleeStyle.SHIELD;
            // A rock, an apple, a stack of dirt: you are swinging your arm.
            default -> MeleeStyle.FIST;
        };
    }

    /** How a species fights with nothing in its hands. */
    private static MeleeStyle naturalStyle(MobDef def) {
        if (def.ranged()) return MeleeStyle.STAFF;   // a caster's staff-jab up close
        if (def.size() >= 44) return MeleeStyle.HAMMER;
        if (def.speed() >= 120) return MeleeStyle.DAGGER;
        return MeleeStyle.FIST;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
