package com.larsons.engine.autobattler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One player's server-side state in an auto-battler match: life total, economy
 * (gold / level / XP / streak), the bench, the fielded board, the current shop
 * offering, and unequipped items. All mutation happens on the server tick
 * thread via {@link AutoGame}; the client only ever sees this through the
 * {@code you} (private) and {@code players} (public) wire maps.
 */
public final class AutoPlayer {

    public static final int BENCH_SIZE = 9;
    public static final int SHOP_SIZE = 5;
    public static final int MAX_LEVEL = 9;
    /** XP needed to reach the next level, indexed by current level. */
    public static final int[] XP_TO_NEXT = {0, 2, 6, 10, 20, 32, 50, 66, 80};

    public final int id;
    public final String name;
    public final boolean bot;

    /** False once a human's connection dropped; their board plays on. */
    public boolean connected = true;
    public boolean alive = true;
    /** Final placement, assigned on elimination (1 = winner). */
    public int place;

    public int hp;
    public int gold;
    public int level;
    public int xp;
    /** Positive = win streak, negative = loss streak. */
    public int streak;

    /** All owned units; each knows whether it's benched or on the board. */
    public final List<UnitInstance> units = new ArrayList<>();
    /** Current shop offering: unit keys, {@code null} = empty/bought slot. */
    public final String[] shop = new String[SHOP_SIZE];
    /** Unequipped item keys (components + combined). */
    public final List<String> items = new ArrayList<>();

    /** Result of the player's most recent combat (for income win bonus). */
    public boolean wonLastRound;

    public AutoPlayer(int id, String name, boolean bot) {
        this.id = id;
        this.name = name;
        this.bot = bot;
    }

    // --- roster queries ---------------------------------------------------------

    public UnitInstance find(int unitId) {
        for (UnitInstance u : units) if (u.id == unitId) return u;
        return null;
    }

    public UnitInstance benchAt(int slot) {
        for (UnitInstance u : units) if (u.bench == slot) return u;
        return null;
    }

    public UnitInstance boardAt(int col, int row) {
        for (UnitInstance u : units) {
            if (u.onBoard() && u.col == col && u.row == row) return u;
        }
        return null;
    }

    public List<UnitInstance> boardUnits() {
        List<UnitInstance> out = new ArrayList<>();
        for (UnitInstance u : units) if (u.onBoard()) out.add(u);
        return out;
    }

    public List<UnitInstance> benchUnits() {
        List<UnitInstance> out = new ArrayList<>();
        for (UnitInstance u : units) if (!u.onBoard()) out.add(u);
        return out;
    }

    public int boardCount() {
        return boardUnits().size();
    }

    /** First free bench slot, or -1 when the bench is full. */
    public int freeBenchSlot() {
        for (int s = 0; s < BENCH_SIZE; s++) {
            if (benchAt(s) == null) return s;
        }
        return -1;
    }

    /** Units on the board a player is allowed = their level. */
    public int boardCap() {
        return level;
    }

    // --- economy ------------------------------------------------------------------

    /** XP needed to go from the current level to the next (0 at max level). */
    public int xpToNext() {
        return level >= MAX_LEVEL ? 0 : XP_TO_NEXT[level];
    }

    public void gainXp(int amount) {
        if (level >= MAX_LEVEL) return;
        xp += amount;
        while (level < MAX_LEVEL && xp >= xpToNext()) {
            xp -= xpToNext();
            level++;
        }
        if (level >= MAX_LEVEL) xp = 0;
    }

    /** Round income: base + interest + streak bonus + win bonus. */
    public int income() {
        int interest = Math.min(5, gold / 10);
        int streakLen = Math.abs(streak);
        int streakBonus = streakLen >= 6 ? 3 : streakLen >= 4 ? 2 : streakLen >= 2 ? 1 : 0;
        return 5 + interest + streakBonus + (wonLastRound ? 1 : 0);
    }

    // --- wire maps ---------------------------------------------------------------

    /** The private state pushed to this player as a {@code you} message body. */
    public Map<String, Object> toYouMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gold", gold);
        m.put("hp", hp);
        m.put("lvl", level);
        m.put("xp", xp);
        m.put("need", xpToNext());
        m.put("cap", boardCap());
        m.put("streak", streak);
        m.put("alive", alive);
        List<Object> bench = new ArrayList<>();
        List<Object> board = new ArrayList<>();
        for (UnitInstance u : units) {
            (u.onBoard() ? board : bench).add(u.toMap());
        }
        m.put("bench", bench);
        m.put("board", board);
        List<Object> shopList = new ArrayList<>(SHOP_SIZE);
        for (String key : shop) shopList.add(key);
        m.put("shop", shopList);
        m.put("items", new ArrayList<Object>(items));
        return m;
    }

    /** The public standings row every player sees. */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("hp", Math.max(0, hp));
        m.put("lvl", level);
        m.put("alive", alive);
        m.put("streak", streak);
        m.put("bot", bot);
        if (place > 0) m.put("place", place);
        return m;
    }
}
