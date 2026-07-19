package com.larsons.engine.minigame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A client's read-only view of a running mini game, parsed from the server's
 * {@code mg} broadcasts ({@link MiniGame#toWireMap()}). Offline play builds
 * the same view from the local referee each frame, so the HUD renders from
 * one shape in both cases.
 */
public final class MiniGameView {

    /** A replicated CTF flag. */
    public record FlagView(int team, int state, double x, double y, int carrier) {}

    public MiniGameConfig.Mode mode = MiniGameConfig.Mode.NONE;
    public boolean ended;
    public int teams = 2;
    public boolean pvp = true;
    public int scoreLimit = 3;
    public int[] scores = new int[0];
    public Map<Integer, Integer> teamOf = new HashMap<>();
    public List<FlagView> flags = new ArrayList<>();
    public boolean hasPayload;
    public double payloadX, payloadY, payloadProgress, timeLeft;
    public int winner = -1;
    public double resetIn;

    public int teamOf(int playerId) {
        return teamOf.getOrDefault(playerId, -1);
    }

    public int score(int team) {
        return team >= 0 && team < scores.length ? scores[team] : 0;
    }

    public static MiniGameView fromMap(Map<String, Object> m) {
        MiniGameView v = new MiniGameView();
        if (m.get("mode") instanceof String s) {
            try {
                v.mode = MiniGameConfig.Mode.valueOf(s);
            } catch (IllegalArgumentException ignored) {
                return v; // unknown mode: render nothing rather than garbage
            }
        }
        v.ended = "ENDED".equals(m.get("phase"));
        if (m.get("teams") instanceof Number n) v.teams = n.intValue();
        if (m.get("pvp") instanceof Boolean b) v.pvp = b;
        if (m.get("limit") instanceof Number n) v.scoreLimit = n.intValue();
        if (m.get("scores") instanceof List<?> list) {
            v.scores = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Number n) v.scores[i] = n.intValue();
            }
        }
        if (m.get("pt") instanceof Map<?, ?> pt) {
            for (Map.Entry<?, ?> e : pt.entrySet()) {
                if (!(e.getValue() instanceof Number team)) continue;
                try {
                    v.teamOf.put(Integer.parseInt(String.valueOf(e.getKey())), team.intValue());
                } catch (NumberFormatException ignored) {
                    // malformed id: skip
                }
            }
        }
        if (m.get("flags") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> fm)) continue;
                v.flags.add(new FlagView(
                        intOf(fm.get("t")), intOf(fm.get("s")),
                        dblOf(fm.get("x")), dblOf(fm.get("y")), intOf(fm.get("c"))));
            }
        }
        if (m.get("pay") instanceof Map<?, ?> pay) {
            v.hasPayload = true;
            v.payloadX = dblOf(pay.get("x"));
            v.payloadY = dblOf(pay.get("y"));
            v.payloadProgress = dblOf(pay.get("pr"));
            v.timeLeft = dblOf(pay.get("tl"));
        }
        if (m.get("win") instanceof Number n) v.winner = n.intValue();
        if (m.get("reset") instanceof Number n) v.resetIn = n.doubleValue();
        return v;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }

    private static double dblOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
