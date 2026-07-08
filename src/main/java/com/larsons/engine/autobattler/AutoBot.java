package com.larsons.engine.autobattler;

import java.util.List;
import java.util.Random;

/**
 * A simple computer opponent for the auto-battler, run on the server tick
 * thread during planning. Bots fill lobbies below 10 players and make solo
 * testing possible: they buy (preferring pairs so they hit 2-stars), level up
 * with spare gold, field their strongest bench units in a melee-front /
 * ranged-back formation, and slap items on their carries.
 */
final class AutoBot {

    private AutoBot() {}

    /** One planning pass; called about once a second while planning lasts. */
    static void act(AutoGame game, AutoPlayer p, Random rng) {
        // Level up when the bank allows it (keep ~20 gold for interest).
        while (p.gold >= 24 && p.level < 8) {
            if (!game.buyXp(p)) break;
        }

        // Buy: first anything that pairs with owned copies, then whatever fits.
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < p.shop.length; slot++) {
                String key = p.shop[slot];
                if (key == null) continue;
                UnitDef def = AutoUnits.get(key);
                if (def == null || p.gold < def.cost || p.freeBenchSlot() < 0) continue;
                boolean pairs = countOwned(p, key) > 0;
                if (pass == 0 ? pairs : true) {
                    game.buy(p, slot);
                }
            }
        }

        // A rich bot rerolls looking for upgrades.
        if (p.gold > 40 && p.freeBenchSlot() >= 0) {
            game.reroll(p);
        }

        field(game, p);
        equipItems(game, p);
    }

    private static int countOwned(AutoPlayer p, String key) {
        int n = 0;
        for (UnitInstance u : p.units) if (u.key.equals(key)) n++;
        return n;
    }

    /** Fill the board up to the cap with the strongest benched units. */
    private static void field(AutoGame game, AutoPlayer p) {
        while (p.boardCount() < p.boardCap()) {
            UnitInstance best = null;
            double bestValue = -1;
            for (UnitInstance u : p.benchUnits()) {
                double value = u.def().cost * UnitDef.starMultiplier(u.star);
                if (value > bestValue) {
                    bestValue = value;
                    best = u;
                }
            }
            if (best == null) return;
            int[] cell = findCell(p, best.def().melee());
            if (cell == null || !game.moveToBoard(p, best.id, cell[0], cell[1])) return;
        }
    }

    /** Melee wants the front of the player's half (row 4), ranged the back (row 7). */
    private static int[] findCell(AutoPlayer p, boolean melee) {
        int[] rows = melee ? new int[]{4, 5, 6, 7} : new int[]{7, 6, 5, 4};
        int[] cols = {3, 4, 2, 5, 1, 6, 0, 7}; // centre-out
        for (int r : rows) {
            for (int c : cols) {
                if (p.boardAt(c, r) == null) return new int[]{c, r};
            }
        }
        return null;
    }

    /** Give loose items to fielded units with free slots (carries first). */
    private static void equipItems(AutoGame game, AutoPlayer p) {
        List<UnitInstance> board = p.boardUnits();
        if (board.isEmpty()) return;
        board.sort((a, b) -> Double.compare(
                b.def().cost * UnitDef.starMultiplier(b.star),
                a.def().cost * UnitDef.starMultiplier(a.star)));
        int guard = 0;
        while (!p.items.isEmpty() && guard++ < 12) {
            boolean equipped = false;
            for (UnitInstance u : board) {
                if (u.items.size() < UnitInstance.MAX_ITEMS && game.equip(p, 0, u.id)) {
                    equipped = true;
                    break;
                }
            }
            if (!equipped) return;
        }
    }
}
