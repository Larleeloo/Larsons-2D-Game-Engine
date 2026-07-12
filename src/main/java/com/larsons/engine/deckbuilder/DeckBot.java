package com.larsons.engine.deckbuilder;

import java.util.List;

/**
 * A simple computer opponent for Council of Six, run on the server tick
 * thread whenever it holds the turn. Bots fill lobbies below 6 players and
 * make solo testing possible. One call performs one action — play the best
 * card/location pair it can see, reveal, buy the priciest affordable market
 * card, or pass — so bot turns stay watchable at the configured action pace.
 */
final class DeckBot {

    private DeckBot() {}

    /** Take one action; called about twice a second while the bot has the turn. */
    static void act(DeckGame game, DeckPlayer p) {
        // 1. An agent turn: the best-scoring legal (card, location) pair.
        if (!p.revealed && p.agentsLeft > 0 && !p.hand.isEmpty()) {
            int bestCard = -1;
            LocationDef bestLoc = null;
            double bestScore = 0;
            for (int c = 0; c < p.hand.size(); c++) {
                CardDef card = Cards.get(p.hand.get(c));
                if (card == null) continue;
                for (LocationDef loc : Locations.all()) {
                    if (!card.icons.contains(loc.icon)) continue;
                    if (!game.occupantsOf(loc.key).isEmpty()) continue;
                    if (p.gold < loc.costGold || p.lore < loc.costLore) continue;
                    double score = score(card, loc);
                    if (score > bestScore) {
                        bestScore = score;
                        bestCard = c;
                        bestLoc = loc;
                    }
                }
            }
            if (bestLoc != null) {
                Territory target = pickTerritory(game, p);
                if (game.playCard(p, bestCard, bestLoc.key, target)) return;
            }
        }

        // 2. Out of useful placements: reveal (turns hand into gold + might).
        if (!p.revealed) {
            game.reveal(p);
            return;
        }

        // 3. Shopping spree: priciest affordable card, one per action.
        List<String> market = game.market();
        int pick = -1;
        int pickCost = -1;
        for (int i = 0; i < market.size(); i++) {
            CardDef card = Cards.get(market.get(i));
            if (card == null) continue;
            int price = Math.max(0, card.cost - (p.bazaarDiscount ? 1 : 0));
            if (price <= p.gold && card.cost > pickCost) {
                pick = i;
                pickCost = card.cost;
            }
        }
        if (pick >= 0 && game.buy(p, pick)) return;

        // 4. Nothing left to do.
        game.done(p);
    }

    /** Weigh a placement's combined gains (VP far above all, then troops). */
    private static double score(CardDef card, LocationDef loc) {
        double vp = loc.gainVp + card.playVp;
        double troops = loc.gainTroops + card.playTroops;
        double might = loc.gainMight + card.playMight;
        double gold = loc.gainGold + card.playGold - loc.costGold;
        double lore = loc.gainLore + card.playLore - loc.costLore;
        double draw = loc.gainDraw + card.playDraw;
        return vp * 10 + troops * 3 + might * 1.6 + gold * 1.2 + lore + draw
                + (loc.bazaarDiscount ? 0.5 : 0);
    }

    /**
     * Deploy where it swings a majority: the territory with the smallest
     * non-negative gap to the current leader, else reinforce the largest own
     * garrison.
     */
    private static Territory pickTerritory(DeckGame game, DeckPlayer p) {
        Territory best = Territory.HIGHLANDS;
        int bestGap = Integer.MAX_VALUE;
        for (Territory t : Territory.values()) {
            int own = p.troopsIn(t);
            int top = 0;
            for (DeckPlayer other : game.players()) {
                if (other != p) top = Math.max(top, other.troopsIn(t));
            }
            int gap = top - own; // <=0 means already winning there
            if (gap < bestGap) {
                bestGap = gap;
                best = t;
            }
        }
        return best;
    }
}
