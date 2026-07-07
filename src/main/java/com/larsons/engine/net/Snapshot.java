package com.larsons.engine.net;

import com.larsons.engine.entity.EntityView;
import com.larsons.engine.sim.PlayerState;

import java.util.List;

/**
 * One server {@code state} broadcast as received by the client: the server
 * tick, every player's state, the replicated world entities (mobs, dropped
 * items, and projectiles in flight — the server simulates, clients render),
 * the world's time of day (drives the lighting pass so night falls for
 * everyone together), and the local arrival time (used to interpolate remote
 * players between the two most recent snapshots).
 */
public record Snapshot(long tick, List<PlayerState> players, List<EntityView> mobs,
                       List<EntityView> items, List<EntityView> shots,
                       double timeOfDay, long receivedNanos) {

    public Snapshot(long tick, List<PlayerState> players, long receivedNanos) {
        this(tick, players, List.of(), List.of(), List.of(), 0.25, receivedNanos);
    }

    /** The state for a player id, or {@code null} if not in this snapshot. */
    public PlayerState player(int id) {
        for (PlayerState p : players) {
            if (p.id == id) return p;
        }
        return null;
    }
}
