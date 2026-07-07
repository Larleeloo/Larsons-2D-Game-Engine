package com.larsons.engine.world;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The live world: a {@link Level} plus everything simulated inside it — mobs
 * (ported AI), dropped items (bounce + pickup), block mining/placing with
 * drops, and the day/night clock the lighting system reads.
 *
 * <p>This is the "simulation seam" the engine's netcode was designed around,
 * extended beyond players: exactly one {@code World} is authoritative at a
 * time (the local one in single-player and creative play-test; the server's
 * in multiplayer, with clients rendering replicated entity state), so all
 * modes share this code and can't drift apart.
 */
public final class World {

    /** How close a player must be for a dropped item to fly to them. */
    private static final double PICKUP_RANGE = 40;
    /** Melee swing reach, from the player centre toward the aim point. */
    public static final double ATTACK_REACH = 70;
    /** Base melee damage with an empty hand; weapons add {@link ItemDef#damage()}. */
    public static final double FIST_DAMAGE = 4;
    /** Day length in seconds when the day/night cycle is on. */
    public static final double DAY_LENGTH = 120;

    public final Level level;
    public final MobRegistry mobTypes;
    public final ItemRegistry itemTypes;

    private final List<Mob> mobs = new ArrayList<>();
    private final List<DroppedItem> items = new ArrayList<>();
    private int nextEntityId = 1;

    /** Time of day in [0,1): 0 = dawn, 0.25 = noon, 0.5 = dusk, 0.75 = midnight. */
    private double timeOfDay = 0.15;

    /** Fired when a player picks an item up (add to their inventory, play a sound…). */
    public interface PickupListener {
        void onPickup(PlayerState player, String itemKey, int count);
    }

    private PickupListener pickupListener;

    public World(Level level) {
        this(level, MobRegistry.standard(), ItemRegistry.standard());
    }

    public World(Level level, MobRegistry mobTypes, ItemRegistry itemTypes) {
        this.level = level;
        this.mobTypes = mobTypes;
        this.itemTypes = itemTypes;
    }

    public void setPickupListener(PickupListener l) {
        this.pickupListener = l;
    }

    public List<Mob> mobs() {
        return mobs;
    }

    public List<DroppedItem> items() {
        return items;
    }

    public double timeOfDay() {
        return timeOfDay;
    }

    public void setTimeOfDay(double t) {
        timeOfDay = t - Math.floor(t);
    }

    /**
     * Darkness in [0,1] for the current time of day: 0 through the day, easing
     * up to {@code nightDarkness} at midnight. With the cycle disabled the
     * night toggle decides directly.
     */
    public double darkness(GameProfile p) {
        return darknessFor(timeOfDay, p);
    }

    /**
     * Same curve for an arbitrary time of day — multiplayer clients use this
     * with the snapshot's time so night falls simultaneously for everyone.
     */
    public static double darknessFor(double timeOfDay, GameProfile p) {
        if (!p.lightingEnabled) return 0;
        if (!p.dayNightCycle) return p.nightMode ? p.nightDarkness : 0;
        // Smooth day->night curve: full light at noon (0.25), darkest at midnight (0.75).
        double angle = (timeOfDay - 0.25) * 2 * Math.PI;
        double t = (1 - Math.cos(angle)) / 2; // 0 at noon, 1 at midnight
        return t * t * p.nightDarkness;
    }

    /** Spawn the mobs/items a level's entity list declares (feature-gated). */
    public void populateFromLevel(GameProfile profile) {
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case "mob" -> {
                    if (profile.mobsEnabled) spawnMob(e.type, e.x, e.y);
                }
                case "item" -> {
                    if (profile.itemsEnabled) spawnItem(e.type, 1, e.x, e.y);
                }
                default -> { /* "entity" spawns (e.g. "player") are scene concerns */ }
            }
        }
    }

    public Mob spawnMob(String key, double x, double y) {
        MobDef def = mobTypes.get(key);
        if (def == null) return null;
        Mob mob = new Mob(nextEntityId++, def, x, y);
        mobs.add(mob);
        return mob;
    }

    public DroppedItem spawnItem(String key, int count, double x, double y) {
        if (itemTypes.get(key) == null) return null;
        DroppedItem item = new DroppedItem(nextEntityId++, key, count, x, y);
        items.add(item);
        return item;
    }

    /** Remove a mob or dropped item by entity id (creative erase, online too). */
    public boolean removeEntity(int id) {
        return mobs.removeIf(m -> m.id == id) || items.removeIf(i -> i.id == id);
    }

    /** Advance everything one tick. Dead mobs drop loot and are removed. */
    public void step(double dt, List<PlayerState> players, GameProfile profile) {
        if (profile.dayNightCycle && profile.lightingEnabled) {
            timeOfDay += dt / DAY_LENGTH;
            timeOfDay -= Math.floor(timeOfDay);
        }

        boolean gravityOn = profile.gravityEnabled
                && level.perspective == com.larsons.engine.graphics.Perspective.SIDE_SCROLL;

        if (profile.mobsEnabled) {
            Iterator<Mob> it = mobs.iterator();
            List<Mob> died = null;
            while (it.hasNext()) {
                Mob m = it.next();
                m.step(level, players, gravityOn, profile.combatEnabled, dt);
                if (m.dead()) {
                    if (died == null) died = new ArrayList<>();
                    died.add(m);
                    it.remove();
                }
            }
            if (died != null && profile.itemsEnabled) {
                for (Mob m : died) dropMobLoot(m);
            }
        }

        if (profile.itemsEnabled) {
            Iterator<DroppedItem> it = items.iterator();
            while (it.hasNext()) {
                DroppedItem item = it.next();
                item.step(level, gravityOn, dt);
                if (item.pickupDelay > 0) continue;
                PlayerState taker = nearestWithin(players, item.x, item.y, PICKUP_RANGE);
                if (taker != null) {
                    if (pickupListener != null) {
                        pickupListener.onPickup(taker, item.key, item.count);
                    }
                    it.remove();
                }
            }
        }

        // Players: clamp health, respawn on death.
        for (PlayerState p : players) {
            if (p.health > PlayerState.MAX_HEALTH) p.health = PlayerState.MAX_HEALTH;
            if (p.health <= 0) {
                p.health = PlayerState.MAX_HEALTH;
                p.x = level.spawnX;
                p.y = level.spawnY;
                p.vy = 0;
            }
        }
    }

    /**
     * Resolve a melee swing from {@code attacker} toward (aimX, aimY): the
     * nearest living mob within {@link #ATTACK_REACH} of the reach point takes
     * {@code damage}. Returns the mob hit, or {@code null} on a whiff.
     */
    public Mob playerAttack(PlayerState attacker, double aimX, double aimY, double damage) {
        double px = attacker.x, py = attacker.y;
        // Point of impact: from the player toward the aim, capped at reach.
        double dx = aimX - px, dy = aimY - py;
        double len = Math.hypot(dx, dy);
        double hitX = len > ATTACK_REACH ? px + dx / len * ATTACK_REACH : aimX;
        double hitY = len > ATTACK_REACH ? py + dy / len * ATTACK_REACH : aimY;

        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            double d = Math.hypot(m.x + m.def.size() / 2 - hitX, m.y + m.def.size() / 2 - hitY);
            if (d < m.def.size() / 2 + 24 && d < bestD) {
                bestD = d;
                best = m;
            }
        }
        if (best != null && best.damage(damage, px)) {
            mobs.remove(best);
            dropMobLoot(best);
        }
        return best;
    }

    /**
     * Mine the block at (col,row). When {@code withDrops}, the block's drop
     * item pops out with a little kick (side-scroller behaviour); creative
     * painting passes {@code false}. Returns the mined block, or {@code null}.
     */
    public Block mineBlock(int col, int row, boolean withDrops) {
        Block b = level.blockAt(col, row);
        if (b == null) return null;
        if (!level.setTile(col, row, 0)) return null;
        if (withDrops && b.drops() != null && itemTypes.get(b.drops()) != null) {
            double ts = level.tileSize;
            spawnItem(b.drops(), 1,
                    col * ts + ts / 2 - DroppedItem.SIZE / 2,
                    row * ts + ts / 2 - DroppedItem.SIZE / 2)
                    .toss(((col + row) % 2 == 0 ? 60 : -60), -260);
        }
        return b;
    }

    /** Place block {@code id} at (col,row) if the cell is empty. */
    public boolean placeBlock(int col, int row, int id) {
        if (level.tileAt(col, row) != 0) return false;
        return level.setTile(col, row, id);
    }

    private void dropMobLoot(Mob m) {
        String loot = switch (m.def.temperament()) {
            case PASSIVE -> "cooked_meat";
            case NEUTRAL -> "leather";
            case HOSTILE -> "coal";
        };
        if (itemTypes.get(loot) != null) {
            spawnItem(loot, 1, m.x + m.def.size() / 2, m.y + m.def.size() / 2)
                    .toss(0, -200);
        }
    }

    private static PlayerState nearestWithin(List<PlayerState> players,
                                             double x, double y, double range) {
        PlayerState best = null;
        double bestD = range;
        for (PlayerState p : players) {
            double d = Math.hypot(p.x - x, p.y - y);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
