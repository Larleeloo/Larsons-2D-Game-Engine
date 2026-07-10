package com.larsons.engine.entity;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A live mob: one entity simulated with the AI state machine ported from the
 * Side-Scroller engine's {@code MobEntity}:
 *
 * <pre>
 *   IDLE → WANDER            after a random idle time
 *   WANDER → IDLE            after reaching the wander destination
 *   IDLE/WANDER → CHASE      a player enters detection range (hostiles)
 *   CHASE → ATTACK           player in attack range
 *   ATTACK → CHASE           attack done, player still in range
 *   CHASE → WANDER           player escaped (1.5× detect range)
 *   any → FLEE               passive mob was hurt
 *   any → DEAD               health reached zero
 * </pre>
 *
 * <p>Like {@link com.larsons.engine.sim.PlayerPhysics}, {@link #step} is a
 * deterministic function of (state, level, players, dt) — randomness comes
 * from a per-mob seeded RNG — so the same code runs in single-player, the
 * creative editor's play-test, and the authoritative multiplayer server
 * (clients just render what snapshots say).
 */
public final class Mob {

    public enum AIState { IDLE, WANDER, CHASE, ATTACK, FLEE, DEAD }

    private static final double GRAVITY = 1500;
    /** How far past detect range a chased player must get to be dropped. */
    private static final double LOSE_FACTOR = 1.5;
    private static final double ATTACK_COOLDOWN = 1.0;   // seconds between hits
    private static final double HURT_FLASH = 0.25;       // seconds of hurt tint

    public final int id;
    public final MobDef def;
    public double x, y;        // top-left, world px
    public double vy;
    public boolean facingLeft;
    public double health;
    public AIState state = AIState.IDLE;

    // AI working state (server/simulation side only; not replicated).
    private final Random rng;
    private double stateTime;
    private double idleFor = 1.0;
    private double wanderTargetX;
    private double attackTimer;
    private double hurtTimer;

    public Mob(int id, MobDef def, double x, double y) {
        this.id = id;
        this.def = def;
        this.x = x;
        this.y = y;
        this.health = def.maxHealth();
        this.rng = new Random(0x9E3779B9L * (id + 1) + def.key().hashCode());
        this.idleFor = 0.5 + rng.nextDouble() * 2.0;
    }

    public boolean dead() {
        return state == AIState.DEAD;
    }

    /** True while the mob should render with a hurt tint. */
    public boolean hurting() {
        return hurtTimer > 0;
    }

    /** Apply damage with knockback away from (fromX). Returns true if this killed it. */
    public boolean damage(double amount, double fromX) {
        if (dead()) return false;
        health -= amount;
        hurtTimer = HURT_FLASH;
        if (!def.flying()) vy = -220; // knock up a touch
        x += (x + def.size() / 2 < fromX ? -12 : 12);
        if (health <= 0) {
            health = 0;
            state = AIState.DEAD;
            return true;
        }
        // Passives run; neutrals and hostiles turn on the attacker.
        if (def.temperament() == MobDef.Temperament.PASSIVE) {
            changeState(AIState.FLEE);
        } else if (state != AIState.CHASE && state != AIState.ATTACK) {
            changeState(AIState.CHASE);
        }
        return false;
    }

    /**
     * Continuous environmental damage (lava, spikes) — no knockback, no
     * aggro change, just a hurt tint and eventual death.
     */
    public void environmentDamage(double amount) {
        if (dead() || amount <= 0) return;
        health -= amount;
        hurtTimer = HURT_FLASH;
        if (health <= 0) {
            health = 0;
            state = AIState.DEAD;
        }
    }

    /**
     * Advance one tick. {@code gravityOn} mirrors the profile's gravity toggle
     * (top-down game types walk mobs on a plane instead); with {@code combatOn}
     * false, hostiles behave like ambient wildlife — no chasing, no damage.
     * Attacks that land subtract from the hit player's health directly.
     */
    public void step(Level level, List<PlayerState> players, boolean gravityOn,
                     boolean combatOn, double dt) {
        if (dead()) return;
        stateTime += dt;
        if (hurtTimer > 0) hurtTimer -= dt;
        if (attackTimer > 0) attackTimer -= dt;

        PlayerState nearest = nearestPlayer(players);
        double dist = nearest == null ? Double.MAX_VALUE : distanceTo(nearest);

        // --- transitions (ported state machine) ---
        boolean aggressive = def.temperament() == MobDef.Temperament.HOSTILE
                || (def.temperament() == MobDef.Temperament.NEUTRAL && state == AIState.CHASE)
                || (def.temperament() == MobDef.Temperament.NEUTRAL && state == AIState.ATTACK);
        if (!combatOn && (state == AIState.CHASE || state == AIState.ATTACK)) {
            changeState(AIState.WANDER);
            pickWanderTarget(level);
        }
        if (combatOn && def.temperament() == MobDef.Temperament.HOSTILE && nearest != null
                && dist <= def.detectRange()
                && state != AIState.CHASE && state != AIState.ATTACK && state != AIState.FLEE) {
            changeState(AIState.CHASE);
        }
        if (state == AIState.CHASE && dist > def.detectRange() * LOSE_FACTOR) {
            changeState(AIState.WANDER);
            pickWanderTarget(level);
        }
        if (aggressive && state == AIState.CHASE && dist <= def.attackRange()) {
            changeState(AIState.ATTACK);
        }
        if (state == AIState.ATTACK && dist > def.attackRange() * 1.2) {
            changeState(AIState.CHASE);
        }
        if (state == AIState.FLEE && stateTime > 3.0) {
            changeState(AIState.IDLE);
            idleFor = 0.5 + rng.nextDouble() * 1.5;
        }
        if (state == AIState.IDLE && stateTime >= idleFor) {
            changeState(AIState.WANDER);
            pickWanderTarget(level);
        }

        // --- behaviour ---
        double dx = 0;
        switch (state) {
            case WANDER -> {
                dx = Math.signum(wanderTargetX - x) * def.speed() * 0.5;
                if (Math.abs(wanderTargetX - x) < 4) {
                    changeState(AIState.IDLE);
                    idleFor = 0.5 + rng.nextDouble() * 2.5;
                }
            }
            case CHASE -> {
                if (nearest != null) dx = Math.signum(nearest.x - x) * def.speed();
            }
            case ATTACK -> {
                if (nearest != null && attackTimer <= 0 && dist <= def.attackRange() * 1.2) {
                    attackTimer = ATTACK_COOLDOWN;
                    nearest.health -= def.damage();
                }
            }
            case FLEE -> {
                if (nearest != null) dx = Math.signum(x - nearest.x) * def.speed();
            }
            default -> { /* IDLE / DEAD: stand still */ }
        }

        if (dx != 0) facingLeft = dx < 0;

        // --- movement & collision (same tile rules as PlayerPhysics) ---
        double size = def.size();
        double ts = level.tileSize;
        double nx = x + dx * dt;
        double probeY = y + size * 0.5;
        int aheadCol = (int) Math.floor((nx + (dx > 0 ? size : 0)) / ts);
        if (!level.solidAt(aheadCol, (int) Math.floor(probeY / ts))) {
            x = nx;
        } else if (state == AIState.WANDER) {
            pickWanderTarget(level); // walked into a wall: pick a new direction
        }

        if (def.flying()) {
            // Fliers bob toward their target's height (or hover).
            double targetY = nearest != null && (state == AIState.CHASE || state == AIState.ATTACK)
                    ? nearest.y : y + Math.sin(stateTime * 2.5) * 12 * dt;
            y += Math.signum(targetY - y) * Math.min(Math.abs(targetY - y), def.speed() * 0.7 * dt);
        } else if (gravityOn) {
            boolean grounded = solid(level, x + size / 2, y + size + 1, ts);
            if (grounded && vy >= 0) {
                vy = 0;
            } else {
                vy += GRAVITY * dt;
            }
            y += vy * dt;
            if (vy > 0 && solid(level, x + size / 2, y + size, ts)) {
                y = Math.floor((y + size) / ts) * ts - size;
                vy = 0;
            }
        }

        // Clamp to level bounds.
        x = Math.max(0, Math.min(x, level.width * (double) level.tileSize - size));
        y = Math.max(0, Math.min(y, level.height * (double) level.tileSize - size));
    }

    private static boolean solid(Level level, double wx, double wy, double ts) {
        return level.solidAt((int) Math.floor(wx / ts), (int) Math.floor(wy / ts));
    }

    private void changeState(AIState next) {
        state = next;
        stateTime = 0;
    }

    private void pickWanderTarget(Level level) {
        double range = 5 * level.tileSize;
        wanderTargetX = Math.max(0, Math.min(level.width * (double) level.tileSize - def.size(),
                x + (rng.nextDouble() * 2 - 1) * range));
    }

    private PlayerState nearestPlayer(List<PlayerState> players) {
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState p : players) {
            double d = Math.hypot(p.x - x, p.y - y);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private double distanceTo(PlayerState p) {
        return Math.hypot(p.x - x, p.y - y);
    }

    // --- wire form (what snapshots carry) --------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", def.key());
        m.put("x", x);
        m.put("y", y);
        m.put("f", facingLeft);
        m.put("h", health);
        m.put("s", state.ordinal());
        return m;
    }
}
