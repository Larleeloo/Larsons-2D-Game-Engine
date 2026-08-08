package com.larsons.engine.entity;

import com.larsons.engine.combat.MeleeAction;
import com.larsons.engine.combat.MeleeProfile;
import com.larsons.engine.combat.MeleeProfiles;
import com.larsons.engine.combat.MeleeState;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PerspectiveSpace;
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
 *   <li><b>Level format</b> — in a side-scrolling level a mob is a platform
 *       walker steering one axis under gravity (fliers hold an altitude); in
 *       the plan-view formats (top-down, isometric) every species walks the
 *       whole plane instead, wandering to 2D destinations and chasing, fleeing
 *       and bursting along both axes.</li>
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
 * <p><b>Melee moves.</b> A mob fights with the same five-move set a player
 * has ({@link MeleeAction}), on the same {@link MeleeState} machine and the
 * same {@link MeleeProfile} timings — with its species' {@link MobDef#weapon()}
 * when it carries one, and with claws sized to the animal when it doesn't. So
 * an attack is a real wind-up → strike → recovery rather than an instant
 * subtraction; a hostile chasing from too far away <em>lunges</em>; an
 * incoming shot is answered with a <em>dash</em> rather than a hop; the
 * SHIELD-stance species raise a real guard; and a mob with a weapon can
 * <em>parry</em> a player's swing outright and leave them staggered. All of it
 * is deterministic and seeded per mob, so the server and every client agree
 * about it.
 *
 * <p><b>Species abilities</b> ({@link MobDef.Ability}) extend the machine
 * without changing it: ranged species fire their {@link MobDef#projectile()}
 * from the ATTACK state, leapers and chargers add burst movement in CHASE,
 * wraiths blink, necromancers queue summons, trolls regenerate, vampires
 * lifesteal, and golems cycle a shield stance. Side effects that need the
 * world (spawning the projectile or minion, blink FX) are queued here and
 * drained by {@code World.step}, so the mob itself stays a pure simulation.
 *
 * <p><b>Elemental statuses</b>: burn and poison tick damage over time, chill
 * multiplies movement speed down — applied by elemental projectile hits (see
 * {@code World}), replicated to clients as {@link #statusBits()}.
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
    private static final double KNOCKBACK = 12;          // world px shoved per hit

    // Navigation tuning.
    private static final double JUMP_VELOCITY = 430;     // clears ~1.5 tiles
    private static final double DODGE_RANGE = 130;       // px: projectile awareness
    private static final double DODGE_COOLDOWN = 0.8;    // seconds between dodges
    private static final double SWIM_RISE = 260;         // px/sec^2 upward stroke
    private static final double SWIM_SINK = 180;         // px/sec^2 passive sink

    // Ability tuning.
    private static final double RANGED_COOLDOWN = 2.1;   // seconds between shots
    private static final double LEAP_COOLDOWN = 2.4;
    private static final double CHARGE_COOLDOWN = 3.2;
    private static final double CHARGE_WINDUP = 0.5;     // stand still, then dash
    private static final double TELEPORT_COOLDOWN = 3.6;
    private static final double SUMMON_COOLDOWN = 8.0;
    private static final double REGEN_RATE = 2.5;        // hp/sec while alive
    private static final double SHIELD_DOWN = 4.0;       // vulnerable seconds
    private static final double SHIELD_UP = 1.6;         // invulnerable seconds

    // Elemental status tuning (see World's elemental hits).
    private static final double BURN_DPS = 4.0;
    private static final double POISON_DPS = 3.0;
    private static final double CHILL_SPEED = 0.45;      // speed multiplier

    // Melee tuning (see com.larsons.engine.combat).
    /** Chance an armed mob answers a player's swing with a parry. */
    private static final double PARRY_CHANCE_ARMED = 0.22;
    /** …and an unarmed one, which has only its forearms to catch with. */
    private static final double PARRY_CHANCE_BARE = 0.06;
    /** Seconds between a mob's attempts to catch a blow. */
    private static final double PARRY_COOLDOWN = 1.8;
    /** How much further than its attack range a mob will lunge to close. */
    private static final double LUNGE_REACH_FACTOR = 2.4;

    // Status bits replicated in snapshots (statusBits()).
    public static final int STATUS_BURNING = 1;
    public static final int STATUS_CHILLED = 2;
    public static final int STATUS_POISONED = 4;
    public static final int STATUS_SHIELDED = 8;

    public final int id;
    public final MobDef def;
    public double x, y;        // top-left, world px
    /**
     * Where this mob stood one fixed step ago, for the renderer only. See
     * {@link com.larsons.engine.sim.StepInterpolation}; captured for every mob
     * in one place, at the top of {@code World.step}.
     */
    public double prevX, prevY;
    public double vy;
    public boolean facingLeft;
    /**
     * The compass direction this mob faces, which picks its directional
     * sprite. Kept in step with {@link #facingLeft} (which stays the wire's
     * compact form and the fallback for mirrored art).
     */
    public Facing facing = Facing.EAST;
    public double health;
    public AIState state = AIState.IDLE;

    // Elemental status timers (seconds left); applied by World on elemental hits.
    public double burnTime;
    public double chillTime;
    public double poisonTime;

    /**
     * This mob's melee moves — the same machine a player runs, on the timings
     * of whatever its species fights with. Public because the world resolves
     * its strikes and the scenes read the animation state off it; it is still
     * advanced only by {@link #step}.
     */
    public final MeleeState melee = new MeleeState();
    /** The timings of what this species fights with, resolved once. */
    private final MeleeProfile meleeProfile;
    /** Cooldown until this mob may try to catch a blow again. */
    private double parryTimer;
    /** The player a strike is aimed at, remembered from wind-up to landing. */
    private PlayerState strikeTarget;

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

    // Ability working state.
    private double abilityTimer;    // cooldown until the ability may fire again
    private double burstDx, burstDy; // leap/charge movement burst (px/sec)
    private double burstTime;       // seconds left on the burst
    private double windupTime;      // charge stands still while > 0
    private double shieldClock;     // SHIELD stance cycle position

    // Side effects queued for the World to resolve after this step.
    private double[] pendingShot;   // {aimX, aimY} of a ranged attack, or null
    private boolean pendingSummon;
    private double[] pendingBlinkFx; // {oldX, oldY} of a teleport, or null

    public Mob(int id, MobDef def, double x, double y) {
        this.id = id;
        this.def = def;
        this.x = x;
        this.y = y;
        this.health = def.maxHealth();
        this.rng = new Random(0x9E3779B9L * (id + 1) + def.key().hashCode());
        this.idleFor = 0.5 + rng.nextDouble() * 2.0;
        this.abilityTimer = 0.5 + rng.nextDouble() * 1.5; // stagger first casts
        this.meleeProfile = MeleeProfiles.forMob(def);
    }

    /** The timings this mob fights on — its weapon's, or its species' claws. */
    public MeleeProfile meleeProfile() {
        return meleeProfile;
    }

    /** The item key this mob fights with ({@code ""} = bare). */
    public String weaponKey() {
        return def.weapon() == null ? "" : def.weapon();
    }

    /** The melee animation state this mob is in ({@code ""} = not mid-move). */
    public String meleeAction() {
        return melee.animationState();
    }

    /** How far through that move it is, 0..1 — picks the frame of its sheet. */
    public double meleeProgress() {
        return melee.progress();
    }

    public boolean dead() {
        return state == AIState.DEAD;
    }

    /** True while the mob should render with a hurt tint. */
    public boolean hurting() {
        return hurtTimer > 0;
    }

    /** True while the SHIELD stance is up: damage is shrugged off. */
    public boolean shielded() {
        return def.ability() == MobDef.Ability.SHIELD && shieldUpPhase();
    }

    /** Where the SHIELD species' guard cycle currently is. */
    private boolean shieldUpPhase() {
        return shieldClock % (SHIELD_DOWN + SHIELD_UP) >= SHIELD_DOWN;
    }

    /**
     * Offer this mob the chance to catch an incoming melee blow. Armed
     * species catch far more often than bare ones, and a guard species more
     * often again; a mob already committed to a move of its own can't. A
     * caught blow deals nothing and leaves whoever threw it staggered — the
     * caller applies that, since only it knows who swung.
     *
     * <p>The roll comes from the mob's own seeded RNG, so the authoritative
     * server decides it and every client renders the same answer.
     */
    public boolean tryParry() {
        if (dead() || parryTimer > 0) return false;
        if (!meleeProfile.has(MeleeAction.PARRY)) return false;
        double chance = def.armed() ? PARRY_CHANCE_ARMED : PARRY_CHANCE_BARE;
        if (def.ability() == MobDef.Ability.SHIELD) chance *= 2;
        if (rng.nextDouble() >= chance) return false;
        if (!melee.begin(MeleeAction.PARRY, meleeProfile, Double.MAX_VALUE)) return false;
        parryTimer = PARRY_COOLDOWN;
        melee.markConnected();
        return true;
    }

    /** Replicated status flags (burn/chill/poison tint, shield glow). */
    public int statusBits() {
        int bits = 0;
        if (burnTime > 0) bits |= STATUS_BURNING;
        if (chillTime > 0) bits |= STATUS_CHILLED;
        if (poisonTime > 0) bits |= STATUS_POISONED;
        if (shielded()) bits |= STATUS_SHIELDED;
        return bits;
    }

    /**
     * Apply damage with a side-scroller's knockback — shoved along x, away
     * from {@code fromX}. The shorthand for hits that have no second
     * coordinate to give.
     */
    public boolean damage(double amount, double fromX) {
        return damage(amount, fromX, 0, PerspectiveSpace.SIDE_VIEW);
    }

    /**
     * Apply damage with knockback away from (fromX, fromY), shoved along the
     * axes {@code space} actually gives this mob. A side-scroller can only
     * shove sideways and pop the mob up the screen; on a plane a hit from the
     * north knocks it <em>south</em>, along the full vector away from whatever
     * struck it, because there the screen is the floor and every direction on
     * it is a direction to be knocked in.
     *
     * <p>Returns true if this killed it.
     *
     * <p>Note that a mob's {@link MeleeAction#DASH} does <em>not</em> grant the
     * invulnerability frames a player's does. A mob's dodge reflex fires at
     * every shot that comes near it (see the projectile awareness in
     * {@link #step}), so frames on it would make every ranged weapon in the
     * game bounce off every mob. A mob dodges by actually getting out of the
     * way; the frames are the player's reward for spending a cooldown
     * deliberately.
     */
    public boolean damage(double amount, double fromX, double fromY,
                          PerspectiveSpace space) {
        if (dead()) return false;
        if (shielded()) {
            hurtTimer = HURT_FLASH * 0.5; // a clank, not a wound
            melee.markConnected();        // the guard rang
            return false;
        }
        health -= amount;
        hurtTimer = HURT_FLASH;
        double half = def.size() / 2;
        if (space.hasElevation()) {
            double dx = x + half - fromX, dy = y + half - fromY;
            double len = Math.hypot(dx, dy);
            if (len < 0.001) {
                dx = 1;
                dy = 0;
                len = 1;
            }
            x += dx / len * KNOCKBACK;
            y += dy / len * KNOCKBACK;
        } else {
            if (!def.flying()) vy = -220; // knock up a touch
            x += (x + half < fromX ? -KNOCKBACK : KNOCKBACK);
        }
        if (health <= 0) {
            health = 0;
            state = AIState.DEAD;
            melee.cancel();
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

    // --- pending side effects (drained by World.step) ---------------------------

    /** The ranged attack queued this tick as {aimX, aimY}, or {@code null}. */
    public double[] pollRangedShot() {
        double[] shot = pendingShot;
        pendingShot = null;
        return shot;
    }

    /** Whether a SUMMON was queued this tick (World spawns the minion). */
    public boolean pollSummon() {
        boolean s = pendingSummon;
        pendingSummon = false;
        return s;
    }

    /** The old position of a teleport this tick (blink FX), or {@code null}. */
    public double[] pollBlinkFx() {
        double[] fx = pendingBlinkFx;
        pendingBlinkFx = null;
        return fx;
    }

    // --- render interpolation (see com.larsons.engine.sim.StepInterpolation) ------

    /** Remember where this mob is, immediately before a fixed step moves it. */
    public void beginStep() {
        prevX = x;
        prevY = y;
    }

    /** Where to draw this mob, {@code alpha} of the way through the last step. */
    public double renderX(double alpha) {
        return com.larsons.engine.sim.StepInterpolation.at(prevX, x, alpha);
    }

    public double renderY(double alpha) {
        return com.larsons.engine.sim.StepInterpolation.at(prevY, y, alpha);
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
        if (abilityTimer > 0) abilityTimer -= dt;
        if (burstTime > 0) burstTime -= dt;
        if (windupTime > 0) windupTime -= dt;
        if (parryTimer > 0) parryTimer -= dt;
        if (def.ability() == MobDef.Ability.SHIELD) shieldClock += dt;

        // The melee machine, on the same timings and the same rules a player's
        // runs. A SHIELD-stance species holds its guard through the "up" half
        // of its cycle; everything else keeps its hands free. A lunge that
        // reaches its active window throws the mob forward using the same
        // movement burst LEAP and CHARGE use.
        melee.holdShield(def.ability() == MobDef.Ability.SHIELD && shieldUpPhase(),
                meleeProfile, Double.MAX_VALUE);
        melee.step(meleeProfile, dt);
        if (melee.pollTravelStart() && melee.action() == MeleeAction.LUNGE) {
            MeleeProfile.Move lunge = meleeProfile.move(MeleeAction.LUNGE);
            burstTime = lunge.active();
            burstDx = (facingLeft ? -1 : 1) * lunge.burstSpeed();
            burstDy = gravityOn ? 0 : facing.dy() * lunge.burstSpeed();
        }
        if (melee.pollStrike()) resolveStrike(level, combatOn);

        tickStatuses(dt);
        if (dead()) return; // burn/poison finished it this tick

        if (def.ability() == MobDef.Ability.REGEN && health < def.maxHealth()) {
            health = Math.min(def.maxHealth(), health + REGEN_RATE * dt);
        }

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
        // Format-specific AI: side-scroll mobs (gravityOn) are platform
        // walkers that only steer horizontally; plan-view mobs (planar)
        // navigate the whole plane, so they wander to 2D targets and chase /
        // flee along both axes. A plan view has no altitude to fly at, so
        // flying species navigate the plane there like everything else.
        boolean planar = !gravityOn;
        double ts = level.tileSize;
        double cx = x + def.size() / 2, cy = y + def.size() / 2;
        double pcx = nearest == null ? cx : nearest.x + ts / 2;
        double pcy = nearest == null ? cy : nearest.y + ts / 2;
        double dx = 0, dyPlanar = 0;
        double speedFactor = chillTime > 0 ? CHILL_SPEED : 1.0;
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
                    chaseAbilities(level, nearest, dist, gravityOn, planar, ts);
                    // Closing the last stretch with a committed thrust rather
                    // than a walk. The move's own cooldown paces it.
                    if (combatOn && !def.ranged() && dist > def.attackRange()
                            && dist < def.attackRange() * LUNGE_REACH_FACTOR) {
                        facingLeft = pcx < cx;
                        if (melee.begin(MeleeAction.LUNGE, meleeProfile, Double.MAX_VALUE)) {
                            strikeTarget = nearest;
                        }
                    }
                }
            }
            case ATTACK -> {
                if (nearest != null && attackTimer <= 0 && dist <= def.attackRange() * 1.2) {
                    if (def.ranged()) {
                        // Ranged species loose their projectile at the player
                        // instead of striking — the World spawns and owns it.
                        if (combatOn) {
                            attackTimer = RANGED_COOLDOWN + rng.nextDouble() * 0.6;
                            pendingShot = new double[]{pcx, pcy};
                            facingLeft = pcx < cx;
                        }
                    } else if (combatOn) {
                        // A real swing: wind-up, strike, recovery. The damage
                        // lands when the hit window opens (resolveStrike), not
                        // the instant the AI decided to attack.
                        facingLeft = pcx < cx;
                        if (melee.begin(MeleeAction.SWING, meleeProfile, Double.MAX_VALUE)) {
                            attackTimer = ATTACK_COOLDOWN;
                            strikeTarget = nearest;
                        }
                    }
                }
                // Summoners keep the ritual going even while pinned in melee.
                if (def.ability() == MobDef.Ability.SUMMON && abilityTimer <= 0) {
                    abilityTimer = SUMMON_COOLDOWN;
                    pendingSummon = true;
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

        // A charging windup roots the mob; then the leap/charge burst
        // overrides steering (the windup is checked first — the burst timer
        // is pre-loaded to cover windup + dash).
        if (windupTime > 0) {
            dx = 0;
            dyPlanar = 0;
        } else if (burstTime > 0) {
            dx = burstDx;
            if (planar) dyPlanar = burstDy;
        }
        // A mob whose blow was caught is reeling: it neither advances nor acts
        // until it has found its feet again.
        if (melee.staggered()) {
            dx = 0;
            dyPlanar = 0;
        }
        dx *= speedFactor;
        dyPlanar *= speedFactor;

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
                // The sidestep it always had, now played as the DASH move —
                // its animation and its sound state — and lasting as long as
                // the move does. Evasion here is displacement, not frames
                // (see damage): the mob really does get out of the way.
                if (melee.begin(MeleeAction.DASH, meleeProfile, Double.MAX_VALUE)) {
                    MeleeProfile.Move d = meleeProfile.move(MeleeAction.DASH);
                    dodgeTime = d.windup() + d.active();
                }
                if (!def.flying() && gravityOn
                        && PlayerPhysics.onGround(level, x, y, size, size)) {
                    vy = -JUMP_VELOCITY * 0.8;
                }
            }
        }
        if (dodgeTime > 0) dx = dodgeDx * def.speed() * 1.4 * speedFactor;

        // --- hazard sense: don't walk into lava/acid/spikes (unless fleeing) ---
        // Each axis is checked where it is actually steering: a platform
        // walker only steps sideways, but a plan-view mob can walk into a lava
        // pool going "up" the screen just as easily.
        if (state != AIState.FLEE && dodgeTime <= 0) {
            double probe = size / 2 + ts * 0.6;
            if (dx != 0 && hazardous(level, x + size / 2 + Math.signum(dx) * probe,
                    y + size * 0.5)) {
                if (state == AIState.WANDER) pickWanderTarget(level);
                dx = 0;
            }
            if (dyPlanar != 0 && hazardous(level, x + size / 2,
                    y + size / 2 + Math.signum(dyPlanar) * probe)) {
                if (state == AIState.WANDER) pickWanderTarget(level);
                dyPlanar = 0;
            }
        }

        if (dx != 0) facingLeft = dx < 0;
        // The compass direction the mob is heading, which picks the directional
        // sprite that draws it: left/right on a platform, all eight on a plane
        // (so a slime walking north-east is drawn from behind, like the player).
        facing = Facing.of(dx, planar ? dyPlanar : 0,
                planar ? Perspective.TOP_DOWN : Perspective.SIDE_SCROLL, facing);

        // --- movement & collision (the same AABB rules as PlayerPhysics) ---
        boolean inLiquid = level.liquidAt((int) Math.floor((x + size / 2) / ts),
                (int) Math.floor((y + size / 2) / ts)) != null;

        double nx = PlayerPhysics.slideX(level, x, y, size, size, dx * dt);
        boolean blockedSideways = dx != 0 && nx == x;
        boolean movedShort = dx != 0 && Math.abs(nx - x) < Math.abs(dx * dt) - 0.0001;
        x = nx;

        if (planar) {
            // Plan view (top-down / isometric): walk the second axis too, with
            // the same wall collision the first axis gets. This is where every
            // species ends up in these formats — there is no height to fly at
            // and no floor to fall to when the screen shows the ground.
            double ny = PlayerPhysics.slideY(level, x, y, size, size, dyPlanar * dt);
            boolean blockedY = dyPlanar != 0 && ny == y;
            y = ny;
            if ((blockedSideways || blockedY) && state == AIState.WANDER) {
                pickWanderTarget(level); // walled in: pick somewhere else
            }
        } else if (def.flying()) {
            // Fliers bob toward their target's height (or hover), still
            // respecting ceilings/floors.
            double targetY = nearest != null && (state == AIState.CHASE || state == AIState.ATTACK)
                    ? nearest.y : y + Math.sin(stateTime * 2.5) * 12 * dt;
            double dy = Math.signum(targetY - y)
                    * Math.min(Math.abs(targetY - y), def.speed() * 0.7 * speedFactor * dt);
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
        } else {
            // Side-scroll ground walker: gravity, landings, and jump smarts.
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
        }

        // Clamp to level bounds.
        x = Math.max(0, Math.min(x, level.width * (double) level.tileSize - size));
        y = Math.max(0, Math.min(y, level.height * (double) level.tileSize - size));
    }

    /**
     * Land the swing (or lunge) whose hit window has just opened. The target
     * remembered at wind-up takes the blow if it is still inside the weapon's
     * reach — step out of a telegraphed swing and it misses, which is the
     * whole reason attacks have a wind-up at all.
     *
     * <p>The blow goes through {@link PlayerState#takeBlow}, so a raised guard
     * soaks it and a dash avoids it; a blow caught by an open parry window
     * leaves this mob staggered instead.
     */
    private void resolveStrike(Level level, boolean combatOn) {
        PlayerState target = strikeTarget;
        strikeTarget = null;
        if (!combatOn || target == null || dead()) return;
        double half = level.tileSize / 2.0;
        double d = Math.hypot((target.x + half) - (x + def.size() / 2),
                (target.y + half) - (y + def.size() / 2));
        if (d > meleeProfile.reach() + half) return; // they got out of the way
        double dmg = def.damage() * meleeProfile.move(melee.action()).damageScale();
        boolean parried = target.parrying;
        double dealt = target.takeBlow(dmg);
        if (parried) {
            melee.stagger(MeleeState.PARRY_STAGGER);
        } else if (dealt > 0) {
            melee.markConnected();
            if (def.ability() == MobDef.Ability.LIFESTEAL) {
                health = Math.min(def.maxHealth(), health + dealt * 0.5);
            }
        }
    }

    /** Burn and poison tick damage; chill only slows (handled where speed is read). */
    private void tickStatuses(double dt) {
        if (burnTime > 0) {
            burnTime -= dt;
            health -= BURN_DPS * dt;
        }
        if (poisonTime > 0) {
            poisonTime -= dt;
            health -= POISON_DPS * dt;
        }
        if (chillTime > 0) chillTime -= dt;
        if (health <= 0) {
            health = 0;
            state = AIState.DEAD;
            melee.cancel();
        }
    }

    /** Movement abilities that trigger while chasing: LEAP, CHARGE, TELEPORT. */
    private void chaseAbilities(Level level, PlayerState target, double dist,
                                boolean gravityOn, boolean planar, double ts) {
        if (abilityTimer > 0) return;
        double size = def.size();
        double towardX = Math.signum(target.x - x);
        switch (def.ability()) {
            case LEAP -> {
                boolean grounded = !gravityOn
                        || PlayerPhysics.onGround(level, x, y, size, size);
                if (dist > def.attackRange() * 1.3 && dist < def.detectRange() * 0.7
                        && grounded) {
                    abilityTimer = LEAP_COOLDOWN + rng.nextDouble();
                    burstTime = 0.45;
                    burstDx = towardX * def.speed() * 1.9;
                    burstDy = planar ? Math.signum(target.y - y) * def.speed() * 1.9 : 0;
                    if (gravityOn && !def.flying()) vy = -JUMP_VELOCITY * 1.05;
                }
            }
            case CHARGE -> {
                if (dist > def.attackRange() * 1.4 && dist < def.detectRange() * 0.9
                        && burstTime <= 0 && windupTime <= 0) {
                    // Wind up rooted, then dash; the dash itself is queued by
                    // letting windup expire into the burst below.
                    windupTime = CHARGE_WINDUP;
                    abilityTimer = CHARGE_COOLDOWN + rng.nextDouble();
                    burstTime = CHARGE_WINDUP + 0.55; // starts after the windup
                    burstDx = towardX * def.speed() * 3.0;
                    burstDy = planar ? Math.signum(target.y - y) * def.speed() * 3.0 : 0;
                    facingLeft = towardX < 0;
                }
            }
            case TELEPORT -> {
                if (dist > def.attackRange() * 1.5) {
                    double destX = target.x + (rng.nextBoolean() ? 1 : -1) * ts * 1.5;
                    double destY = target.y;
                    destX = Math.max(0, Math.min(destX,
                            level.width * (double) level.tileSize - size));
                    destY = Math.max(0, Math.min(destY,
                            level.height * (double) level.tileSize - size));
                    if (!blocked(level, destX, destY, size)) {
                        abilityTimer = TELEPORT_COOLDOWN + rng.nextDouble();
                        pendingBlinkFx = new double[]{x + size / 2, y + size / 2};
                        x = destX;
                        y = destY;
                        vy = 0;
                    } else {
                        abilityTimer = 0.5; // blocked destination: retry soon
                    }
                }
            }
            case SUMMON -> {
                abilityTimer = SUMMON_COOLDOWN;
                pendingSummon = true;
            }
            default -> { /* other abilities don't trigger from CHASE */ }
        }
    }

    /** Whether a body of {@code size} at (wx, wy) would overlap solid tiles. */
    private static boolean blocked(Level level, double wx, double wy, double size) {
        double ts = level.tileSize;
        for (double ox : new double[]{1, size - 1}) {
            for (double oy : new double[]{1, size - 1}) {
                if (level.solidAt((int) Math.floor((wx + ox) / ts),
                        (int) Math.floor((wy + oy) / ts))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The nearest projectile flying toward this mob within awareness range. */
    private Projectile incomingThreat(List<Projectile> projectiles) {
        double cx = x + def.size() / 2, cy = y + def.size() / 2;
        for (Projectile p : projectiles) {
            if (p.dead()) continue;
            if (p.ownerId < 0) continue; // don't dodge our own side's volleys
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
        int bits = statusBits();
        if (bits != 0) m.put("e", bits); // absent when clean, like input flags
        // Absent for plain east/west facings, which the "f" flag already says.
        if (facing != Facing.EAST && facing != Facing.WEST) m.put("d", facing.key());
        // The melee move it is mid-way through, so clients draw the wind-up
        // and the strike rather than an instant hit. Absent while idle.
        String move = melee.animationState();
        if (!move.isEmpty()) {
            m.put("ma", move);
            m.put("mg", melee.progress());
        }
        if (def.armed()) m.put("w", def.weapon());
        return m;
    }
}
