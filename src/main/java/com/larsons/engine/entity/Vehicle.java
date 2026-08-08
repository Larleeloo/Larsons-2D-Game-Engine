package com.larsons.engine.entity;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A live vehicle/mount in the world. Pure deterministic simulation state in
 * the engine's usual style: the {@link VehicleDef} carries all behaviour,
 * rendering is the scene's job, and stepping is a pure function of (state,
 * input, level, dt) — so the same code drives single-player, client-side
 * prediction while riding online, and the authoritative multiplayer server
 * (which replicates vehicles in snapshots like mobs).
 *
 * <p>Two step modes: {@link #stepDriven} applies a rider's {@link PlayerInput}
 * under the def's movement model (gallop/fly/float/drill), while
 * {@link #stepIdle} settles an empty vehicle — ground mounts drop and stand,
 * fliers sink gently to the floor, boats bob up to the surface of whatever
 * liquid they're in. Collision goes through the shared AABB helpers
 * ({@link PlayerPhysics#slideX}/{@code slideY}) so vehicles obey exactly the
 * wall/floor/ceiling rules players and mobs do.
 */
public final class Vehicle {

    private static final double GRAVITY = 1500;
    private static final double ACCEL_FACTOR = 5.0;   // reach top speed in ~0.2 s
    private static final double IDLE_FRICTION = 6.0;  // empty vehicles coast to rest
    private static final double BUOYANCY = 2600;      // boat upward accel submerged
    private static final double SHOT_COOLDOWN = 0.6;  // armed vehicles (war dragon)

    public final int id;
    public final VehicleDef def;
    public double x, y;        // top-left, world px
    public double vx, vy;
    /**
     * Where this vehicle was one fixed step ago, for the renderer only. See
     * {@link com.larsons.engine.sim.StepInterpolation}; captured for every
     * vehicle in one place, at the top of {@code World.step}.
     */
    public double prevX, prevY;
    public boolean facingLeft;
    /** Riding player id, or -1 when empty. */
    public int riderId = -1;

    private double shotTimer;  // cooldown left on the vehicle's armament
    private double ramTimer;   // cooldown left on contact damage
    private boolean drivenThisTick;

    public Vehicle(int id, VehicleDef def, double x, double y) {
        this.id = id;
        this.def = def;
        this.x = x;
        this.y = y;
    }

    public boolean ridden() {
        return riderId >= 0;
    }

    // --- render interpolation (see com.larsons.engine.sim.StepInterpolation) ------

    /** Remember where this vehicle is, immediately before a fixed step moves it. */
    public void beginStep() {
        prevX = x;
        prevY = y;
    }

    /** Where to draw this vehicle, {@code alpha} of the way through the last step. */
    public double renderX(double alpha) {
        return com.larsons.engine.sim.StepInterpolation.at(prevX, x, alpha);
    }

    public double renderY(double alpha) {
        return com.larsons.engine.sim.StepInterpolation.at(prevY, y, alpha);
    }

    /** Whether the armament may fire now (and restart the cooldown if so). */
    public boolean tryFire() {
        if (def.projectile() == null || shotTimer > 0) return false;
        shotTimer = SHOT_COOLDOWN;
        return true;
    }

    /** Whether a ram hit may land now (and restart its cooldown if so). */
    public boolean tryRam() {
        if (def.contactDamage() <= 0 || ramTimer > 0) return false;
        ramTimer = 0.4;
        return true;
    }

    /** Consumed by {@code World.step}: true when a rider drove it this tick. */
    public boolean consumeDriven() {
        boolean d = drivenThisTick;
        drivenThisTick = false;
        return d;
    }

    /** Where the rider sits: their top-left position while mounted. */
    public double seatX(double playerSize) {
        return x + (def.size() - playerSize) / 2;
    }

    public double seatY(double playerSize) {
        return y - playerSize * 0.55;
    }

    /** Lock a rider to the saddle after a step (position + presentation). */
    public void seat(PlayerState rider, double playerSize) {
        rider.x = seatX(playerSize);
        rider.y = seatY(playerSize);
        rider.vy = 0;
        rider.airJumpsUsed = 0;
        rider.facingLeft = facingLeft;
        rider.moving = Math.abs(vx) > 1 || Math.abs(vy) > 1;
    }

    /**
     * Advance one ridden tick under {@code in}. {@code gravityOn} mirrors the
     * profile's gravity toggle: without it (top-down worlds) every kind
     * steers the plane freely like a flier.
     */
    public void stepDriven(Level level, PlayerInput in, boolean gravityOn, double dt) {
        drivenThisTick = true;
        tickCooldowns(dt);
        double size = def.size();
        double target = (in.left ? -1 : 0) + (in.right ? 1 : 0);
        boolean planar = !gravityOn;

        VehicleDef.Kind kind = planar ? VehicleDef.Kind.FLYING : def.kind();
        switch (kind) {
            case GROUND, DRILL -> {
                boolean grounded = PlayerPhysics.onGround(level, x, y, size, size);
                double top = def.speed();
                vx = approach(vx, target * top, top * ACCEL_FACTOR * dt);
                if (grounded && vy >= 0) {
                    vy = 0;
                    // Space vaults the mount, same as it does on foot; up is
                    // left free to mean "up" (steering a flier, swimming).
                    if (in.jump) vy = -def.jump();
                } else {
                    vy += GRAVITY * dt;
                }
            }
            case FLYING -> {
                vx = approach(vx, target * def.speed(), def.speed() * ACCEL_FACTOR * dt);
                double vTarget = (in.up ? -1 : 0) + (in.down ? 1 : 0);
                vy = approach(vy, vTarget * def.speed() * 0.85,
                        def.speed() * ACCEL_FACTOR * dt);
            }
            case BOAT -> {
                boolean inLiquid = liquidAtCentre(level);
                double top = def.speed() * (inLiquid ? 1 : 0.3);
                vx = approach(vx, target * top, top * ACCEL_FACTOR * dt);
                if (inLiquid) {
                    // Buoyancy pushes the hull up until it rides the surface.
                    boolean deckClear = level.liquidAt(
                            (int) Math.floor((x + size / 2) / level.tileSize),
                            (int) Math.floor((y + size * 0.2) / level.tileSize)) == null;
                    vy += (deckClear ? GRAVITY * 0.2 : -BUOYANCY) * dt;
                    vy = Math.max(-220, Math.min(180, vy));
                } else {
                    vy += GRAVITY * dt;
                }
            }
        }
        move(level, dt);
        if (target != 0) facingLeft = target < 0;
    }

    /** Settle an empty vehicle: coast, drop/bob/sink to rest. */
    public void stepIdle(Level level, boolean gravityOn, double dt) {
        tickCooldowns(dt);
        double size = def.size();
        vx = approach(vx, 0, Math.abs(vx) * IDLE_FRICTION * dt + 20 * dt);
        if (!gravityOn) {
            vy = approach(vy, 0, Math.abs(vy) * IDLE_FRICTION * dt + 20 * dt);
        } else if (def.kind() == VehicleDef.Kind.BOAT && liquidAtCentre(level)) {
            boolean deckClear = level.liquidAt(
                    (int) Math.floor((x + size / 2) / level.tileSize),
                    (int) Math.floor((y + size * 0.2) / level.tileSize)) == null;
            vy += (deckClear ? GRAVITY * 0.2 : -BUOYANCY) * dt;
            vy = Math.max(-160, Math.min(140, vy));
        } else if (def.kind() == VehicleDef.Kind.FLYING) {
            // Parked fliers sink gently until they touch down.
            vy = PlayerPhysics.onGround(level, x, y, size, size) ? 0 : 70;
        } else {
            boolean grounded = PlayerPhysics.onGround(level, x, y, size, size);
            if (grounded && vy >= 0) vy = 0;
            else vy += GRAVITY * dt;
        }
        move(level, dt);
    }

    private void tickCooldowns(double dt) {
        if (shotTimer > 0) shotTimer -= dt;
        if (ramTimer > 0) ramTimer -= dt;
    }

    private void move(Level level, double dt) {
        double size = def.size();
        double nx = PlayerPhysics.slideX(level, x, y, size, size, vx * dt);
        if (nx == x && vx != 0) vx = 0; // hit a wall
        x = nx;
        double ny = PlayerPhysics.slideY(level, x, y, size, size, vy * dt);
        if (ny != y + vy * dt) vy = 0; // landed / bonked
        y = ny;
        x = Math.max(0, Math.min(x, level.width * (double) level.tileSize - size));
        y = Math.max(0, Math.min(y, level.height * (double) level.tileSize - size));
    }

    private boolean liquidAtCentre(Level level) {
        double size = def.size();
        return level.liquidAt((int) Math.floor((x + size / 2) / level.tileSize),
                (int) Math.floor((y + size / 2) / level.tileSize)) != null;
    }

    private static double approach(double v, double target, double maxDelta) {
        if (v < target) return Math.min(target, v + maxDelta);
        if (v > target) return Math.max(target, v - maxDelta);
        return v;
    }

    // --- wire form (what snapshots carry) --------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", def.key());
        m.put("x", x);
        m.put("y", y);
        m.put("f", facingLeft);
        if (riderId >= 0) m.put("p", riderId); // absent when empty
        return m;
    }
}
