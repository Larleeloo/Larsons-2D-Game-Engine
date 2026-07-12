package com.larsons.engine.deckbuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Council of Six card catalog: the 10-card starter deck every player
 * begins with, and the market pool the shared 5-card market row deals from.
 * Adding a card is one {@code register(...)} row — same data-driven shape as
 * the engine's block/mob/item registries.
 */
public final class Cards {

    private static final Map<String, CardDef> ALL = new LinkedHashMap<>();
    private static final List<String> STARTER_DECK = new ArrayList<>();
    private static final List<String> MARKET_POOL = new ArrayList<>();

    private Cards() {}

    private static final Set<LocationIcon> ANY = Set.of(LocationIcon.values());

    static {
        // --- starter deck (cost 0, 10 copies total per player) --------------------
        starter("loyal_friend", 4, new CardDef("loyal_friend", "Loyal Friend", 0,
                Set.of(LocationIcon.ECONOMY, LocationIcon.COUNCIL),
                1, 0, 0, 0, 0, 0, 1, 0,
                "Shows up every week, rain or shine."));
        starter("clever_plan", 2, new CardDef("clever_plan", "Clever Plan", 0,
                Set.of(LocationIcon.KNOWLEDGE, LocationIcon.COUNCIL),
                0, 1, 0, 0, 0, 0, 1, 0,
                "\"Okay, hear me out...\""));
        starter("rally_the_crew", 2, new CardDef("rally_the_crew", "Rally the Crew", 0,
                Set.of(LocationIcon.MILITARY),
                0, 0, 1, 0, 0, 0, 0, 1,
                "One text to the group chat and everyone answers."));
        starter("snack_run", 1, new CardDef("snack_run", "Snack Run", 0,
                Set.of(LocationIcon.ECONOMY),
                2, 0, 0, 0, 0, 0, 1, 0,
                "No game night survives without provisions."));
        starter("house_rules", 1, new CardDef("house_rules", "House Rules", 0,
                ANY,
                0, 0, 0, 1, 0, 0, 1, 0,
                "Goes anywhere, bends everything. Ask Larson."));

        // --- market pool (cost > 0; N copies each in the market deck) --------------
        market("spice_merchant", 3, new CardDef("spice_merchant", "Spice Merchant", 2,
                Set.of(LocationIcon.ECONOMY),
                2, 0, 0, 0, 0, 0, 2, 0,
                "The spice must flow."));
        market("smugglers_sloop", 3, new CardDef("smugglers_sloop", "Smuggler's Sloop", 2,
                Set.of(LocationIcon.ECONOMY, LocationIcon.MILITARY),
                1, 0, 1, 0, 0, 0, 1, 0,
                "Half cargo hold, half battering ram."));
        market("bard_of_the_vale", 3, new CardDef("bard_of_the_vale", "Bard of the Vale", 2,
                Set.of(LocationIcon.KNOWLEDGE, LocationIcon.COUNCIL),
                0, 1, 0, 1, 0, 0, 1, 0,
                "Every tavern knows her verses; every verse knows a secret."));
        market("druid_of_the_grove", 3, new CardDef("druid_of_the_grove", "Druid of the Grove", 3,
                Set.of(LocationIcon.KNOWLEDGE),
                0, 2, 0, 0, 0, 0, 1, 0,
                "The old ways remember what the new ways forgot."));
        market("shieldmaiden", 3, new CardDef("shieldmaiden", "Shieldmaiden", 3,
                Set.of(LocationIcon.MILITARY),
                0, 0, 2, 0, 0, 0, 0, 2,
                "She holds the line so the line holds you."));
        market("clan_chieftain", 3, new CardDef("clan_chieftain", "Clan Chieftain", 3,
                Set.of(LocationIcon.MILITARY, LocationIcon.COUNCIL),
                0, 0, 1, 0, 1, 0, 0, 2,
                "Where the chieftain plants a banner, clans follow."));
        market("war_banner", 2, new CardDef("war_banner", "War Banner", 3,
                Set.of(LocationIcon.MILITARY, LocationIcon.ECONOMY),
                1, 0, 1, 0, 1, 0, 1, 1,
                "Cloth and thread, worth a hundred swords."));
        market("relic_hunter", 2, new CardDef("relic_hunter", "Relic Hunter", 3,
                Set.of(LocationIcon.ECONOMY, LocationIcon.KNOWLEDGE),
                1, 1, 0, 0, 0, 0, 1, 0,
                "Digs up trouble, sells it at a profit."));
        market("counter_gambit", 2, new CardDef("counter_gambit", "Counter-Gambit", 4,
                Set.of(LocationIcon.KNOWLEDGE, LocationIcon.MILITARY),
                0, 1, 1, 1, 0, 0, 0, 2,
                "\"In response...\" — Dustin, every single week."));
        market("guild_banker", 2, new CardDef("guild_banker", "Guild Banker", 4,
                Set.of(LocationIcon.ECONOMY, LocationIcon.COUNCIL),
                3, 0, 0, 0, 0, 0, 2, 0,
                "Interest never sleeps."));
        market("lorekeeper", 2, new CardDef("lorekeeper", "Lorekeeper", 4,
                Set.of(LocationIcon.KNOWLEDGE, LocationIcon.COUNCIL),
                0, 2, 0, 1, 0, 0, 1, 0,
                "Reads the rulebook cover to cover. Twice."));
        market("high_emissary", 2, new CardDef("high_emissary", "High Emissary", 5,
                Set.of(LocationIcon.COUNCIL),
                0, 0, 0, 0, 0, 1, 1, 0,
                "A word in the right ear is worth a war."));
        market("sandworm_rider", 2, new CardDef("sandworm_rider", "Sandworm Rider", 5,
                Set.of(LocationIcon.MILITARY),
                0, 0, 3, 0, 1, 0, 0, 3,
                "Walk without rhythm and it won't attack. Probably."));
        market("dragon_of_the_peaks", 1, new CardDef("dragon_of_the_peaks", "Dragon of the Peaks", 6,
                Set.of(LocationIcon.MILITARY, LocationIcon.KNOWLEDGE),
                0, 1, 3, 0, 1, 0, 0, 3,
                "Some problems you negotiate with. This one negotiates back."));
    }

    private static void starter(String key, int copies, CardDef def) {
        ALL.put(key, def);
        for (int i = 0; i < copies; i++) STARTER_DECK.add(key);
    }

    private static void market(String key, int copies, CardDef def) {
        ALL.put(key, def);
        for (int i = 0; i < copies; i++) MARKET_POOL.add(key);
    }

    /** Look a card up by key; {@code null} when unknown. */
    public static CardDef get(String key) {
        return key == null ? null : ALL.get(key);
    }

    /** Every registered card, starters first, in registration order. */
    public static List<CardDef> all() {
        return List.copyOf(ALL.values());
    }

    /** A fresh copy of the 10-card starter deck (keys; caller shuffles). */
    public static List<String> starterDeck() {
        return new ArrayList<>(STARTER_DECK);
    }

    /** A fresh copy of the full market pool (keys; caller shuffles). */
    public static List<String> marketPool() {
        return new ArrayList<>(MARKET_POOL);
    }
}
