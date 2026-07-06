package com.larsons.engine.net;

import com.larsons.engine.sim.PlayerState;

import java.util.List;

/**
 * One server {@code state} broadcast as received by the client: the server
 * tick, every player's state, and the local arrival time (used to interpolate
 * remote players between the two most recent snapshots).
 */
public record Snapshot(long tick, List<PlayerState> players, long receivedNanos) {

    /** The state for a player id, or {@code null} if not in this snapshot. */
    public PlayerState player(int id) {
        for (PlayerState p : players) {
            if (p.id == id) return p;
        }
        return null;
    }
}
