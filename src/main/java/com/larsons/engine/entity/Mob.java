package com.larsons.engine.entity;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;

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
 * <p><b>Navigation intelligence</b> layered onto the ported state machine:
 * <ul>
 *   <li><b>Jumping</b> — a grounded walker blocked by a low wall (or facing a
 *       gap while chasing) hops it, so terrain no longer strands pursuit.</li>
 *   <li><b>Liquids</b> — submerged mobs swim: buoyancy replaces gravity and
 *       they stroke toward the surface (or their target's height); mobs
 *       refuse to walk into liquids or blocks that would burn them (lava,
 *       acid, spikes) unless already fleeing for their lives.</li>
 *   <li><b>Projectiles</b> — an incoming shot aimed their way triggers an
 *       evasive hop/dash (with a cooldown, so volleys still land).</li>
 * </ul>
 *
 * <p>Movement resolves through the same AABB helpers players use
 * ({@link PlayerPhysics#slideX}/{@code slideY}), so mobs collide with walls,
 * ceilings and floors under identical rules.
 *
 * <p>Like {@link PlayerPhysics}, {@link #step} is a deterministic function of
 * (state, level, players, projectiles, dt) — randomness comes from a per-mob
 * seeded RNG — so the same code runs in single-player, the creative editor's
 * play-test, and the authoritative multiplayer server (clients just render
 * what snapshots say).
 */
public final class Mob {

    public enum AIState { IDLE, WANDER, CHASE, ATTACK, FLEE, DEAD }

    private static final double GRAVITY = 1500;
    /** How far past detect range a chased player must get to be dropped. */
    private static final double LOSE_FACTOR = 1.5;
    private static final double ATTACK_COOLDOWN = 1.0;   // seconds between hits
    private static final double HURT_FLASH = 0.25;       // seconds of hurt tint

    // Navigation tuning.
    private static final double JUMP_VELOCITY = 430;     // clears ~1.5 tiles
    private static final double DODGE_RANGE = 130;       // px: projectile awareness
    private static final double DODGE_COOLDOWN = 0.8;    // seconds between dodges
    private static final double SWIM_RISE = 260;         // px/sec^2 upward stroke
    private static final double SWIM_SINK = 180;         // px/sec^2 passive sink

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
    private double wanderTargetY;   // planar (top-down / isometric) wandering
    private double attackTimer;
    private double hurtTimer;
    private double dodgeTimer;   // cooldown until the next projectile dodge
    private double dodgeDx;      // evasive burst applied while > 0 (sign = dir)
    private double dodgeTime;    // seconds left on the evasive burst

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

    /** Pre-projectile-awareness signature, for callers without a live world. */
    public void step(Level level, List<PlayerState> players, boolean gravityOn,
                     boolean combatOn, double dt) {
        step(level, players, List.of(), gravityOn, combatOn, dt);
    }

    /**
     * Advance one tick. {@code gravityOn} mirrors the profile's gravity toggle
     * (top-down game types walk mobs on a plane instead); with {@code combatOn}
     * false, hostiles behave like ambient wildlife — no chasing, no damage.
     * Attacks that land subtract from the hit player's health directly.
     * {@code projectiles} feeds the dodge reflex — pass the world's live list.
     */
    public void step(Level level, List<PlayerState> players, List<Projectile> projectiles,
                     boolean gravityOn, boolean combatOn, double dt) {
        if (dead()) return;
        stateTime += dt;
        if (hurtTimer > 0) hurtTimer -= dt;
        if (attackTimer > 0) attackTimer -= dt;
        if (dodgeTimer > 0) dodgeTimer -= dt;
        if (dodgeTime > 0) dodgeTime -= dt;

        PlayerState nearest = nearestPlayer(players);
        // Distances are edge-aware: measured centre-to-centre minus this mob's
        // body radius, so big mobs whose bodies already touch the player count
        // as in range instead of chasing a top-left point they can never reach.
        double dist = nearest == null ? Double.MAX_VALUE : distanceTo(nearest, level);

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
        // Perspective-specific AI: side-scroll mobs (gravityOn) are platform
        // walkers that only steer horizontally; top-down / isometric mobs
        // (planar) navigate the whole plane, so they wander to 2D targets and
        // chase / flee along both axes.
        boolean planar = !gravityOn && !def.flying();
        double ts = level.tileSize;
        double cx = x + def.size() / 2, cy = y + def.size() / 2;
        double pcx = nearest == null ? cx : nearest.x + ts / 2;
        double pcy = nearest == null ? cy : nearest.y + ts / 2;
        double dx = 0, dyPlanar = 0;
        switch (state) {
            case WANDER -> {
                dx = Math.signum(wanderTargetX - x) * def.speed() * 0.5;
                if (planar) dyPlanar = Math.signum(wanderTargetY - y) * def.speed() * 0.5;
                boolean arrived = Math.abs(wanderTargetX - x) < 4
                        && (!planar || Math.abs(wanderTargetY - y) < 4);
                if (arrived) {
                    changeState(AIState.IDLE);
                    idleFor = 0.5 + rng.nextDouble() * 2.5;
                }
            }
            case CHASE -> {
                if (nearest != null) {
                    dx = steer(pcx - cx) * def.speed();
                    if (planar) dyPlanar = steer(pcy - cy) * def.speed();
                }
            }
            case ATTACK -> {
                if (nearest != null && attackTimer <= 0 && dist <= def.attackRange() * 1.2) {
                    attackTimer = ATTACK_COOLDOWN;
                    nearest.health -= def.damage();
                }
            }
            case FLEE -> {
                if (nearest != null) {
                    dx = steer(cx - pcx) * def.speed();
                    if (planar) dyPlanar = steer(cy - pcy) * def.speed();
                }
            }
            default -> { /* IDLE / DEAD: stand still */ }
        }

        // --- projectile awareness: sidestep incoming shots ---
        double size = def.size();
        if (dodgeTimer <= 0 && !projectiles.isEmpty()) {
            Projectile threat = incomingThreat(projectiles);
            if (threat != null) {
                dodgeTimer = DODGE_COOLDOWN;
                dodgeTime = 0.22;
                // Dash away from the shot's line of travel; hop if standing.
                dodgeDx = threat.vy == 0 && threat.vx == 0 ? 1
                        : Math.signum(threat.vx == 0 ? x - threat.x : -threat.vy * Math.signum(threat.vx));
                if (dodgeDx == 0) dodgeDx = rng.nextBoolean() ? 1 : -1;
                if (!def.flying() && gravityOn
                        && PlayerPhysics.onGround(level, x, y, size, size)) {
                    vy = -JUMP_VELOCITY * 0.8;
                }
            }
        }
        if (dodgeTime > 0) dx = dodgeDx * def.speed() * 1.4;

        // --- hazard sense: don't walk into lava/acid/spikes (unless fleeing) ---
        if (dx != 0 && state != AIState.FLEE && dodgeTime <= 0
                && hazardous(level, x + size / 2 + Math.signum(dx) * (size / 2 + ts * 0.6),
                y + size * 0.5)) {
            if (state == AIState.WANDER) pickWanderTarget(level);
            dx = 0;
        }

        if (dx != 0) facingLeft = dx < 0;

        // --- movement & collision (the same AABB rules as PlayerPhysics) ---
        boolean inLiquid = level.liquidAt((int) Math.floor((x + size / 2) / ts),
                (int) Math.floor((y + size / 2) / ts)) != null;

        double nx = PlayerPhysics.slideX(level, x, y, size, size, dx * dt);
        boolean blockedSideways = dx != 0 && nx == x;
        boolean movedShort = dx != 0 && Math.abs(nx - x) < Math.abs(dx * dt) - 0.0001;
        x = nx;

        if (def.flying()) {
            // Fliers bob toward their target's height (or hover), still
            // respecting ceilings/floors.
            double targetY = nearest != null && (state == AIState.CHASE || state == AIState.ATTACK)
                    ? nearest.y : y + Math.sin(stateTime * 2.5) * 12 * dt;
            double dy = Math.signum(targetY - y)
                    * Math.min(Math.abs(targetY - y), def.speed() * 0.7 * dt);
            y = PlayerPhysics.slideY(level, x, y, size, size, dy);
        } else if (inLiquid && gravityOn) {
            // Swimming: buoyancy instead of gravity. Stroke upward toward the
            // surface (or the target when it's above), sink gently otherwise.
            boolean wantsUp = nearest != null
                    && (state == AIState.CHASE || state == AIState.FLEE)
                    ? nearest.y < y : true;
            vy += (wantsUp ? -SWIM_RISE : SWIM_SINK) * dt;
            vy = Math.max(-160, Math.min(120, vy));
            double ny = PlayerPhysics.slideY(level, x, y, size, size, vy * dt);
            if (ny != y + vy * dt) vy = 0;
            y = ny;
        } else if (gravityOn) {
            boolean grounded = PlayerPhysics.onGround(level, x, y, size, size);
            if (grounded && vy >= 0) vy = 0;

            // Jump intelligence: hop low walls, and leap gaps while chasing.
            if (grounded && dx != 0) {
                boolean gapAhead = state == AIState.CHASE && nearest != null
                        && nearest.y <= y + ts
                        && !groundAhead(level, dx, size, ts);
                if ((blockedSideways || movedShort) && canJumpClear(level, dx, size, ts)) {
                    vy = -JUMP_VELOCITY;
                } else if (gapAhead) {
                    vy = -JUMP_VELOCITY * 0.85;
                } else if ((blockedSideways || movedShort) && state == AIState.WANDER) {
                    pickWanderTarget(level); // unjumpable wall: turn around
                }
            }

            if (!grounded || vy < 0) vy += GRAVITY * dt;
            double dy = vy * dt;
            double ny = PlayerPhysics.slideY(level, x, y, size, size, dy);
            if (ny != y + dy) vy = 0; // landed / hit a ceiling
            y = ny;
        } else {
            // Planar (top-down / isometric): walk the second axis too, with
            // the same wall collision the first axis gets.
            double ny = PlayerPhysics.slideY(level, x, y, size, size, dyPlanar * dt);
            boolean blockedY = dyPlanar != 0 && ny == y;
            y = ny;
            if ((blockedSideways || blockedY) && state == AIState.WANDER) {
                pickWanderTarget(level); // walled in: pick somewhere else
            }
        }

        // Clamp to level bounds.
        x = Math.max(0, Math.min(x, level.width * (double) level.tileSize - size));
        y = Math.max(0, Math.min(y, level.height * (double) level.tileSize - size));
    }

    /** The nearest projectile flying toward this mob within awareness range. */
    private Projectile incomingThreat(List<Projectile> projectiles) {
        double cx = x + def.size() / 2, cy = y + def.size() / 2;
        for (Projectile p : projectiles) {
            if (p.dead()) continue;
            double dxp = cx - p.x, dyp = cy - p.y;
            double dist = Math.hypot(dxp, dyp);
            if (dist > DODGE_RANGE || dist < 0.001) continue;
            double speed = Math.hypot(p.vx, p.vy);
            if (speed < 1) continue;
            // Heading roughly at us? (velocity within ~30° of the line to the mob)
            double dot = (p.vx * dxp + p.vy * dyp) / (speed * dist);
            if (dot > 0.85) return p;
        }
        return null;
    }

    /** Whether the tile at a world point would hurt to touch (liquid or hazard). */
    private static boolean hazardous(Level level, double wx, double wy) {
        Block b = level.blockAt((int) Math.floor(wx / level.tileSize),
                (int) Math.floor(wy / level.tileSize));
        return b != null && b.damage() > 0;
    }

    /** Is there floor within a two-tile drop just ahead in direction {@code dx}? */
    private boolean groundAhead(Level level, double dx, double size, double ts) {
        double aheadX = x + size / 2 + Math.signum(dx) * (size / 2 + ts * 0.5);
        int col = (int) Math.floor(aheadX / ts);
        int row = (int) Math.floor((y + size) / ts);
        for (int r = row; r <= row + 2; r++) {
            if (level.solidAt(col, r)) return true;
        }
        return false;
    }

    /**
     * Whether the wall ahead is low enough to jump: solid at body height but
     * clear one and two tiles above it.
     */
    private boolean canJumpClear(Level level, double dx, double size, double ts) {
        double aheadX = x + size / 2 + Math.signum(dx) * (size / 2 + ts * 0.5);
        int col = (int) Math.floor(aheadX / ts);
        int footRow = (int) Math.floor((y + size - 1) / ts);
        int headRow = (int) Math.floor(y / ts);
        // Clearance above both the obstacle and this mob's head.
        return !level.solidAt(col, footRow - 1)
                && !level.solidAt(col, footRow - 2)
                && !level.solidAt((int) Math.floor((x + size / 2) / ts), headRow - 1)
                && !level.solidAt((int) Math.floor((x + size / 2) / ts), headRow - 2);
    }

    private void changeState(AIState next) {
        state = next;
        stateTime = 0;
    }

    private void pickWanderTarget(Level level) {
        double range = 5 * level.tileSize;
        wanderTargetX = Math.max(0, Math.min(level.width * (double) level.tileSize - def.size(),
                x + (rng.nextDouble() * 2 - 1) * range));
        wanderTargetY = Math.max(0, Math.min(level.height * (double) level.tileSize - def.size(),
                y + (rng.nextDouble() * 2 - 1) * range));
    }

    /** Directional steering with a small deadzone so mobs don't jitter in place. */
    private static double steer(double d) {
        return Math.abs(d) < 2 ? 0 : Math.signum(d);
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

    /**
     * Edge-aware distance to a player: centre-to-centre, minus this mob's body
     * radius — so a 96-px bear standing against the player registers ~0, not
     * the 96 px its top-left corner is away.
     */
    private double distanceTo(PlayerState p, Level level) {
        double half = level.tileSize / 2.0; // players are one tile square
        double d = Math.hypot((p.x + half) - (x + def.size() / 2),
                (p.y + half) - (y + def.size() / 2));
        return Math.max(0, d - def.size() / 2);
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
