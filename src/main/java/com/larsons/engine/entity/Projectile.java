package com.larsons.engine.entity;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PerspectiveSpace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A projectile in flight — an arrow, thrown rock, or magic bolt. Ported from
 * the Side-Scroller engine's {@code ProjectileEntity}, reduced to pure
 * simulation state in this engine's style: the {@link ProjectileDef} (via its
 * key) carries all behaviour, rendering is the scene's job, and
 * {@link #step} is a deterministic function of (state, level, dt) so the same
 * code runs in single-player and on the authoritative multiplayer server
 * (clients just render what snapshots say).
 *
 * <p><b>Height.</b> A shot flies on the level's own axes
 * ({@link PerspectiveSpace}). In a side-scroller that is the familiar arc:
 * {@code gravityFactor} bends its path down the screen. On a plane the world
 * is the floor, so an ordinary shot skims flat across it and only things
 * genuinely <em>above</em> the ground carry a height — {@link #z}, which
 * {@link #fromSky} launches and gravity brings back down onto the tile the
 * caster aimed at. While a shot is {@link #airborne()} it is over the level
 * rather than in it: it clears walls and passes over heads, and it lands the
 * moment its height reaches the floor.
 */
public final class Projectile {

    /** Matches the gravity constant mobs and dropped items fall with. */
    private static final double GRAVITY = 1500;

    public final int id;
    public final ProjectileDef def;
    /** Player id that fired it (its owner is never hit by it). */
    public final int ownerId;
    public double x, y;        // centre, world px
    /**
     * Height over the floor in a plan-view level (world px). Always 0 in a
     * side-scroller, whose world plane already contains the vertical.
     */
    public double z;
    public double vx, vy, vz;
    /** Damage dealt on hit: the firing weapon's, or the def's default. */
    public double damage;

    private double life;
    private boolean dead;

    public Projectile(int id, ProjectileDef def, int ownerId,
                      double x, double y, double vx, double vy) {
        this.id = id;
        this.def = def;
        this.ownerId = ownerId;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.damage = def.damage();
        this.life = def.lifetime();
    }

    /**
     * A shot called down out of the sky in a plan-view level: it starts
     * {@code height} above (startX, startY) and falls under gravity, with the
     * horizontal velocity that puts it on (targetX, targetY) at the instant it
     * touches the floor. That is what makes a meteor land <em>on</em> the tile
     * it was aimed at, instead of flying in from the north because "above the
     * aim point" was read as a smaller y.
     *
     * <p>Only meaningful where {@link PerspectiveSpace#hasElevation()}; a side
     * view has no height to fall from, and callers there keep spawning the
     * salvo up the screen as they always did.
     */
    public static Projectile fromSky(int id, ProjectileDef def, int ownerId,
                                     double startX, double startY,
                                     double targetX, double targetY,
                                     double height) {
        double descent = def.speed();
        double fall = fallTime(height, descent);
        Projectile p = new Projectile(id, def, ownerId, startX, startY,
                (targetX - startX) / fall, (targetY - startY) / fall);
        p.z = Math.max(0, height);
        p.vz = -descent;
        return p;
    }

    /**
     * Seconds for something dropped from {@code height} at {@code speed} to
     * reach the floor under the engine's gravity — the positive root of
     * {@code ½gt² + vt − h = 0}. Never zero, so callers can divide by it.
     */
    public static double fallTime(double height, double speed) {
        double h = Math.max(0, height);
        double t = (-speed + Math.sqrt(speed * speed + 2 * GRAVITY * h)) / GRAVITY;
        return Math.max(0.001, t);
    }

    public boolean dead() {
        return dead;
    }

    public void kill() {
        dead = true;
    }

    /** Whether this shot is still above the floor, and so over the level. */
    public boolean airborne() {
        return z > 0;
    }

    /**
     * Advance one tick in {@code space}. Returns {@code true} if the
     * projectile just impacted terrain (a solid tile, the level edge, the
     * floor it was falling toward) or timed out — the caller (the
     * {@link com.larsons.engine.world.World}) resolves what happens next
     * (explosion, recoverable drop) before removing it.
     *
     * <p>{@code gravityOn} is the game type's gravity toggle and only bends
     * side-scrolling arcs. A plan-view shot's descent is the level format's
     * own physics, not an option: a meteor hanging in the sky forever because
     * a game type turned gravity off would be a bug, not a setting.
     */
    public boolean step(Level level, PerspectiveSpace space, boolean gravityOn, double dt) {
        if (dead) return false;
        if ((life -= dt) <= 0) {
            dead = true;
            return true;
        }
        boolean landing = false;
        if (space.hasElevation()) {
            if (z > 0 || vz != 0) {
                vz -= space.gravity() * dt;
                z += vz * dt;
                if (z <= 0) {
                    // Touchdown: the shot has arrived at the floor it was
                    // aimed at, which is this space's version of "hit the
                    // ground" — resolve it as an impact right here.
                    z = 0;
                    vz = 0;
                    landing = true;
                }
            }
        } else if (gravityOn && def.gravityFactor() > 0) {
            vy += GRAVITY * def.gravityFactor() * dt;
        }
        x += vx * dt;
        y += vy * dt;

        double ts = level.tileSize;
        if (x < 0 || y < 0 || x > level.width * ts || y > level.height * ts) {
            dead = true;
            return true;
        }
        if (landing) {
            dead = true;
            return true;
        }
        // A shot still in the air passes over walls: it is above the level,
        // not in it.
        if (!airborne()
                && level.solidAt((int) Math.floor(x / ts), (int) Math.floor(y / ts))) {
            dead = true;
            return true;
        }
        return false;
    }

    // --- wire form (what snapshots carry) --------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", def.key());
        m.put("x", x);
        m.put("y", y);
        m.put("vx", vx);
        m.put("vy", vy);
        // Absent on the wire while a shot is on the floor, which is all of
        // them in a side-scroller — old clients read a missing height as 0.
        if (z != 0) m.put("z", z);
        return m;
    }
}
