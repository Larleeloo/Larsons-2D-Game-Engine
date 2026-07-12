package com.larsons.engine.deckbuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Council of Six's rules, headlessly through {@link DeckGame}: the card and
 * location registries, agent placement and blocking, market buys, reveals,
 * the leader passives, the round-end conflict and territory majorities, and
 * a complete bot game to a winner — the same capturing-sink seam the
 * auto-battler's tests use.
 */
@Timeout(60)
class DeckGameTest {

    /** A sink that discards everything (rules tests assert on state). */
    private static DeckGame.Sink silentSink() {
        return new DeckGame.Sink() {
            @Override
            public void toPlayer(int playerId, Map<String, Object> message) {}

            @Override
            public void toAll(Map<String, Object> message) {}
        };
    }

    private static DeckGame.Config quickConfig(long seed) {
        DeckGame.Config cfg = new DeckGame.Config();
        cfg.seed = seed;
        cfg.turnSeconds = 0.2;
        cfg.postSeconds = 0.05;
        cfg.botActionSeconds = 0.01;
        return cfg;
    }

    // --- registries -----------------------------------------------------------------

    @Test
    void registriesAreWellFormed() {
        assertEquals(10, Cards.starterDeck().size(), "the starter deck is 10 cards");
        for (String key : Cards.starterDeck()) {
            CardDef def = Cards.get(key);
            assertNotNull(def, key + " is registered");
            assertEquals(0, def.cost, "starters are never market cards");
            assertFalse(def.icons.isEmpty(), key + " can go somewhere");
        }
        assertTrue(Cards.marketPool().size() >= 20, "a real market deck");
        for (String key : Cards.marketPool()) {
            CardDef def = Cards.get(key);
            assertNotNull(def);
            assertTrue(def.cost > 0, key + " has a price");
        }
        assertEquals(8, Locations.all().size(), "eight board locations");
        for (LocationIcon icon : LocationIcon.values()) {
            long n = Locations.all().stream().filter(l -> l.icon == icon).count();
            assertEquals(2, n, "two locations per icon family");
        }
    }

    // --- lobby / start ----------------------------------------------------------------

    @Test
    void lobbyLeaderPicksAreExclusiveAndAutoAssigned() {
        DeckGame game = new DeckGame(quickConfig(1), silentSink());
        DeckPlayer a = game.addPlayer("Larson", false);
        DeckPlayer b = game.addPlayer("Dustin", false);
        game.addPlayer("Bot", true);

        assertTrue(game.pickLeader(a.id, Leader.LARSON));
        assertFalse(game.pickLeader(b.id, Leader.LARSON), "leaders are exclusive");
        assertTrue(game.pickLeader(b.id, Leader.DUSTIN));

        assertTrue(game.start(a.id));
        assertEquals(DeckGame.Phase.PLAY, game.phase());
        assertEquals(1, game.round());
        for (DeckPlayer p : game.players()) {
            assertNotNull(p.leader, p.name + " got a leader");
        }
        assertEquals(DeckGame.MARKET_SIZE, game.market().size());
        assertEquals(a, game.turnPlayer(), "round 1 starts with the first seat");
    }

    // --- leader passives ---------------------------------------------------------------

    @Test
    void roundStartPassivesApply() {
        DeckGame.Config cfg = quickConfig(7);
        cfg.startGold = 4;
        DeckGame game = new DeckGame(cfg, silentSink());
        DeckPlayer larson = game.addPlayer("L", false);
        DeckPlayer kris = game.addPlayer("K", false);
        DeckPlayer dustin = game.addPlayer("D", false);
        DeckPlayer eric = game.addPlayer("E", false);
        game.pickLeader(larson.id, Leader.LARSON);
        game.pickLeader(kris.id, Leader.KRIS);
        game.pickLeader(dustin.id, Leader.DUSTIN);
        game.pickLeader(eric.id, Leader.ERIC);
        assertTrue(game.start(larson.id));

        assertEquals(5, larson.gold, "Larson hosts: +1 gold");
        assertEquals(5, kris.gold, "Kris earns interest: +1 per 4 gold");
        assertEquals(4, eric.gold, "no round-start gold passive for Eric");
        assertEquals(6, dustin.hand.size(), "Dustin draws a 6-card hand");
        assertEquals(5, eric.hand.size(), "everyone else draws 5");
    }

    @Test
    void ericCommitsBonusMightToTheConflict() {
        DeckGame game = new DeckGame(quickConfig(11), silentSink());
        DeckPlayer eric = game.addPlayer("E", false);
        DeckPlayer other = game.addPlayer("O", false);
        game.pickLeader(eric.id, Leader.ERIC);
        game.pickLeader(other.id, Leader.LARSON);
        assertTrue(game.start(eric.id));

        // Empty hands make the reveal contribute exactly nothing.
        eric.hand.clear();
        other.hand.clear();
        assertTrue(game.done(eric));
        assertTrue(game.done(other));

        assertEquals(DeckGame.Phase.POST, game.phase(), "the round resolved");
        assertEquals(1, eric.might, "the Warlord's +1 arrives at the conflict");
        assertEquals(0, other.might);
        assertEquals(1, eric.vp, "1 might vs 0 wins round 1's conflict (1 VP)");
    }

    @Test
    void mattDeploysABonusTroopOncePerRound() {
        DeckGame game = new DeckGame(quickConfig(13), silentSink());
        DeckPlayer matt = game.addPlayer("M", false);
        DeckPlayer other = game.addPlayer("O", false);
        game.pickLeader(matt.id, Leader.MATT);
        game.pickLeader(other.id, Leader.BELLA);
        assertTrue(game.start(matt.id));

        matt.hand.clear();
        matt.hand.add("rally_the_crew"); // military icon, no troops of its own
        assertTrue(game.playCard(matt, 0, "war_camp", Territory.RIVERLANDS));
        assertEquals(2, matt.troopsIn(Territory.RIVERLANDS),
                "war camp deploys 1 + the Tactician's bonus");

        // His second deployment the same round is normal-sized.
        game.done(other);
        matt.hand.add("rally_the_crew");
        matt.gold = 5; // the Mercenary Hall's entry fee
        assertTrue(game.playCard(matt, 0, "mercenary_hall", Territory.RIVERLANDS));
        assertEquals(3, matt.troopsIn(Territory.RIVERLANDS));
    }

    @Test
    void bellaMayCrashOneOccupiedLocationPerRound() {
        DeckGame game = new DeckGame(quickConfig(17), silentSink());
        DeckPlayer other = game.addPlayer("O", false);
        DeckPlayer bella = game.addPlayer("B", false);
        game.pickLeader(other.id, Leader.ERIC);
        game.pickLeader(bella.id, Leader.BELLA);
        assertTrue(game.start(other.id));

        other.hand.clear();
        other.hand.add("snack_run");
        assertTrue(game.playCard(other, 0, "the_mines", null));
        assertEquals(bella, game.turnPlayer());

        bella.hand.clear();
        bella.hand.add("snack_run");
        bella.hand.add("loyal_friend");
        assertTrue(bella.envoyReady);
        assertTrue(game.playCard(bella, 0, "the_mines", null),
                "the Envoy may place on an occupied location");
        assertFalse(bella.envoyReady);
        assertEquals(List.of(other.id, bella.id), game.occupantsOf("the_mines"));

        // Only once per round: her next agent can't crash a second time.
        game.done(other);
        assertFalse(game.playCard(bella, 0, "the_mines", null));
    }

    // --- core turn actions ----------------------------------------------------------

    @Test
    void playingACardPlacesAnAgentAndBlocksTheLocation() {
        DeckGame game = new DeckGame(quickConfig(19), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.ERIC);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        int goldBefore = a.gold;
        a.hand.clear();
        a.hand.add("snack_run"); // play: +2 gold, economy icon

        assertFalse(game.playCard(a, 0, "library", null), "icon must match");
        assertFalse(game.playCard(b, 0, "the_mines", null), "not b's turn");

        assertTrue(game.playCard(a, 0, "the_mines", null));
        assertEquals(goldBefore + 2 + 2, a.gold, "mines +2 and card +2 stack");
        assertEquals(1, a.agentsLeft);
        assertTrue(a.hand.isEmpty());
        assertEquals(List.of("snack_run"), a.played);
        assertEquals(List.of(a.id), game.occupantsOf("the_mines"));
        assertEquals(b, game.turnPlayer(), "playing a card passes the turn");

        b.hand.clear();
        b.hand.add("snack_run");
        assertFalse(game.playCard(b, 0, "the_mines", null),
                "an occupied location blocks everyone else");
    }

    @Test
    void buyingRefillsTheMarketAndDiscountApplies() {
        DeckGame game = new DeckGame(quickConfig(23), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.ERIC);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        a.gold = 10;
        String key = game.market().get(0);
        int cost = Cards.get(key).cost;
        assertTrue(game.buy(a, 0));
        assertEquals(10 - cost, a.gold);
        assertTrue(a.discard.contains(key), "bought cards go to the discard pile");
        assertEquals(DeckGame.MARKET_SIZE, game.market().size(), "the slot refilled");
        assertFalse(game.buy(b, 0), "you only shop on your turn");

        // The Grand Bazaar discounts the rest of the round.
        a.hand.clear();
        a.hand.add("loyal_friend");
        assertTrue(game.playCard(a, 0, "grand_bazaar", null));
        assertTrue(a.bazaarDiscount);
        game.done(b); // back to a
        a.gold = 30;
        String key2 = game.market().get(0);
        int cost2 = Cards.get(key2).cost;
        assertTrue(game.buy(a, 0));
        assertEquals(30 - (cost2 - 1), a.gold, "bazaar knocks 1 gold off");
    }

    @Test
    void highCouncilConvertsLoreIntoVictoryPoints() {
        DeckGame game = new DeckGame(quickConfig(29), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.ERIC);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        a.hand.clear();
        a.hand.add("clever_plan"); // council icon, +1 lore on play
        a.lore = 1;
        assertFalse(game.playCard(a, 0, "high_council", null), "2 lore required");
        a.lore = 2;
        assertTrue(game.playCard(a, 0, "high_council", null));
        assertEquals(1, a.vp);
        assertEquals(1, a.lore, "paid 2, the card's +1 came back");
    }

    @Test
    void revealTurnsTheHandIntoGoldAndMight() {
        DeckGame game = new DeckGame(quickConfig(31), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.LARSON);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        a.hand.clear();
        a.hand.add("loyal_friend");   // reveal: +1 gold
        a.hand.add("rally_the_crew"); // reveal: +1 might
        int goldBefore = a.gold;
        assertTrue(game.reveal(a));
        assertTrue(a.revealed);
        assertEquals(goldBefore + 1, a.gold);
        assertEquals(1, a.might);
        assertEquals(a, game.turnPlayer(), "the turn continues for shopping");
        assertTrue(game.done(a));
        assertEquals(b, game.turnPlayer());
    }

    // --- round resolution ---------------------------------------------------------------

    @Test
    void conflictAndTerritoryMajoritiesScoreAtRoundEnd() {
        DeckGame game = new DeckGame(quickConfig(37), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.LARSON);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        a.hand.clear();
        a.hand.add("rally_the_crew");
        assertTrue(game.playCard(a, 0, "war_camp", Territory.EMBER_WASTES));
        assertEquals(1, a.troopsIn(Territory.EMBER_WASTES));
        assertEquals(2, a.might, "war camp +1 and the card +1");

        b.hand.clear();
        assertTrue(game.done(b));
        assertTrue(game.done(a)); // empty hand: reveal adds nothing

        assertEquals(DeckGame.Phase.POST, game.phase());
        // Round 1 conflict: 1 VP + 1 gold to the top might; majority: +1 VP.
        assertEquals(2, a.vp, "conflict VP + territory majority VP");
        assertEquals(0, b.vp);

        // The next round starts fresh: hands redrawn, agents back, board clear.
        double dt = 1.0 / 30;
        int guard = 300;
        while (game.phase() == DeckGame.Phase.POST && guard-- > 0) game.tick(dt);
        assertEquals(DeckGame.Phase.PLAY, game.phase());
        assertEquals(2, game.round());
        assertEquals(0, a.might, "conflict might resets");
        assertEquals(1, a.troopsIn(Territory.EMBER_WASTES), "troops persist");
        assertEquals(5, a.hand.size());
        assertEquals(DeckGame.AGENTS_PER_ROUND, a.agentsLeft);
        assertTrue(game.occupantsOf("war_camp").isEmpty(), "agents come home");
        assertEquals(b, game.turnPlayer(), "the first player token rotates");
    }

    @Test
    void tiedConflictSplitsTheConsolationPrize() {
        DeckGame game = new DeckGame(quickConfig(41), silentSink());
        DeckPlayer a = game.addPlayer("A", false);
        DeckPlayer b = game.addPlayer("B", false);
        game.pickLeader(a.id, Leader.LARSON);
        game.pickLeader(b.id, Leader.KRIS);
        assertTrue(game.start(a.id));

        a.hand.clear();
        a.hand.add("rally_the_crew"); // reveal: +1 might
        b.hand.clear();
        b.hand.add("rally_the_crew");
        assertTrue(game.done(a));
        assertTrue(game.done(b));

        assertEquals(DeckGame.Phase.POST, game.phase());
        assertEquals(0, a.vp, "round 1's consolation carries no VP");
        assertEquals(0, b.vp);
        int consolationGold = game.conflictReward().secondGold();
        assertTrue(consolationGold > 0);
    }

    // --- full games -----------------------------------------------------------------------

    @Test
    void botsPlayAWholeGameToAWinner() {
        DeckGame.Config cfg = quickConfig(2026);
        AtomicInteger overMessages = new AtomicInteger();
        String[] winner = new String[1];
        DeckGame.Sink sink = new DeckGame.Sink() {
            @Override
            public void toPlayer(int playerId, Map<String, Object> message) {}

            @Override
            public void toAll(Map<String, Object> message) {
                if ("over".equals(DeckProto.type(message))) {
                    overMessages.incrementAndGet();
                    winner[0] = (String) message.get("winner");
                }
            }
        };

        DeckGame game = new DeckGame(cfg, sink);
        DeckPlayer host = game.addPlayer("Idle Human", false); // never acts
        game.addPlayer("Bot A", true);
        game.addPlayer("Bot B", true);
        game.addPlayer("Bot C", true);
        game.addPlayer("Bot D", true);
        game.addPlayer("Bot E", true);
        assertTrue(game.start(host.id));

        double dt = 1.0 / 30;
        int guard = 30 * 60 * 30; // half an hour of simulated time, tops
        while (game.phase() != DeckGame.Phase.OVER && guard-- > 0) {
            game.tick(dt);
        }

        assertEquals(DeckGame.Phase.OVER, game.phase(), "the game reached a winner");
        assertEquals(1, overMessages.get());
        assertNotNull(winner[0]);
        assertTrue(game.round() <= cfg.maxRounds, "the round cap holds");

        int maxVp = 0;
        for (DeckPlayer p : game.players()) maxVp = Math.max(maxVp, p.vp);
        assertEquals(maxVp, game.byName(winner[0]).vp, "the winner has the top score");
        assertTrue(maxVp > 0, "somebody actually scored");
        assertTrue(game.byName(winner[0]).bot, "an idle human never outscores bots");
    }

    @Test
    void leaverMidGameAutoPassesAndTheGameFinishes() {
        DeckGame.Config cfg = quickConfig(53);
        DeckGame game = new DeckGame(cfg, silentSink());
        DeckPlayer host = game.addPlayer("Host", false);
        game.addPlayer("Bot A", true);
        game.addPlayer("Bot B", true);
        assertTrue(game.start(host.id));
        assertEquals(host, game.turnPlayer());

        game.removePlayer(host.id);
        assertFalse(game.byId(host.id).connected);
        assertTrue(game.turnPlayer() == null || game.turnPlayer().bot,
                "the leaver's turn passed immediately");

        double dt = 1.0 / 30;
        int guard = 30 * 60 * 30;
        while (game.phase() != DeckGame.Phase.OVER && guard-- > 0) {
            game.tick(dt);
        }
        assertEquals(DeckGame.Phase.OVER, game.phase(),
                "the table plays on without the leaver");
    }
}
