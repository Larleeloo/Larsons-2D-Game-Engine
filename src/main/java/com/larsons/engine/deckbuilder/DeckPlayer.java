package com.larsons.engine.deckbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One player's server-side state in Council of Six: their deck / hand /
 * discard piles, resources, committed conflict might, troops on each
 * territory, and the per-round flags their leader passive and turn structure
 * need. Owned by {@link DeckGame} on the server tick thread; what clients see
 * of it goes out through {@link DeckProto#you} (private) and the public
 * standings rows.
 */
public final class DeckPlayer {

    public final int id;
    public final String name;
    public final boolean bot;

    public Leader leader; // null until picked / auto-assigned at start
    public boolean connected = true;

    // Resources. "might" is what's committed to this round's conflict.
    public int gold;
    public int lore;
    public int might;
    public int vp;

    // The deckbuilding piles (card keys; the deck's end is the top).
    public final List<String> deck = new ArrayList<>();
    public final List<String> hand = new ArrayList<>();
    public final List<String> discard = new ArrayList<>();
    /** Cards played this round; discarded at round end. */
    public final List<String> played = new ArrayList<>();

    /** Persistent troops per territory (Inis-style presence). */
    public final Map<Territory, Integer> troops = new EnumMap<>(Territory.class);

    // Per-round turn state.
    public int agentsLeft;
    public boolean revealed;
    /** Bella: whether the once-per-round occupied placement is still available. */
    public boolean envoyReady;
    /** Matt: whether this round's bonus troop has been claimed. */
    public boolean tacticianUsed;
    /** Grand Bazaar: buys cost 1 less for the rest of the round. */
    public boolean bazaarDiscount;

    public DeckPlayer(int id, String name, boolean bot) {
        this.id = id;
        this.name = name;
        this.bot = bot;
    }

    /** Build and shuffle the starting deck (called once at game start). */
    public void setupDeck(Random rng) {
        deck.clear();
        hand.clear();
        discard.clear();
        played.clear();
        deck.addAll(Cards.starterDeck());
        Collections.shuffle(deck, rng);
    }

    /** Draw {@code n} cards, reshuffling the discard pile when the deck runs dry. */
    public void draw(int n, Random rng) {
        for (int i = 0; i < n; i++) {
            if (deck.isEmpty()) {
                if (discard.isEmpty()) return;
                deck.addAll(discard);
                discard.clear();
                Collections.shuffle(deck, rng);
            }
            hand.add(deck.remove(deck.size() - 1));
        }
    }

    /** Cards drawn at round start: Dustin's passive reads one extra. */
    public int handSize() {
        return leader == Leader.DUSTIN ? 6 : 5;
    }

    public int troopsIn(Territory t) {
        return troops.getOrDefault(t, 0);
    }

    public int troopsTotal() {
        int n = 0;
        for (int v : troops.values()) n += v;
        return n;
    }

    public void addTroops(Territory t, int n) {
        troops.merge(t, n, Integer::sum);
    }

    /** The private {@code you} payload replicated only to this player. */
    public Map<String, Object> toYouMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gold", gold);
        m.put("lore", lore);
        m.put("might", might);
        m.put("vp", vp);
        m.put("hand", new ArrayList<Object>(hand));
        m.put("deck", deck.size());
        m.put("disc", discard.size());
        m.put("agents", agentsLeft);
        m.put("rev", revealed);
        m.put("envoy", envoyReady);
        m.put("disco", bazaarDiscount);
        if (leader != null) m.put("leader", leader.name());
        return m;
    }
}
