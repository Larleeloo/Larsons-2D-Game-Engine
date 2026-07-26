package com.larsons.engine.world;

import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.entity.Vehicle;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The live world: a {@link Level} plus everything simulated inside it — mobs
 * (ported AI), dropped items (bounce + pickup), projectiles (arrows, thrown
 * rocks, magic — arcs, hits, explosions, recoverable drops), block
 * mining/placing with drops, and the day/night clock the lighting system reads.
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

    /** Soft cap on live mobs, so summoners can't flood the simulation. */
    private static final int MOB_CAP = 80;

    public final Level level;
    public final MobRegistry mobTypes;
    public final ItemRegistry itemTypes;
    public final ProjectileRegistry projectileTypes;
    public final VehicleRegistry vehicleTypes;

    private final List<Mob> mobs = new ArrayList<>();
    private final List<DroppedItem> items = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Vehicle> vehicles = new ArrayList<>();
    private final List<Impact> impacts = new ArrayList<>();
    /** Deferred area blasts (DEATH_BURST chains) resolved after the mob loop. */
    private final ArrayDeque<double[]> pendingBursts = new ArrayDeque<>();
    /** Deferred SUMMON spawns — the mob list can't grow mid-iteration. */
    private final List<PendingSummon> pendingSummons = new ArrayList<>();

    private record PendingSummon(String key, double x, double y) {}
    private int killsByPlayers; // mobs killed by player damage since last poll
    private int playerDeaths;   // player respawns since last poll (stat tracking)
    private final LiquidSim liquids = new LiquidSim();
    private final List<LiquidSim.Change> blockChanges = new ArrayList<>();
    private int nextEntityId = 1;

    /** Time of day in [0,1): 0 = dawn, 0.25 = noon, 0.5 = dusk, 0.75 = midnight. */
    private double timeOfDay = 0.15;

    /** Fired when a player picks an item up (add to their inventory, play a sound…). */
    public interface PickupListener {
        void onPickup(PlayerState player, String itemKey, int count);
    }

    /**
     * Player-versus-player rules (mini games set one): whether {@code attacker}
     * may hurt {@code victim}, and a report whenever PvP damage lands so kills
     * can be attributed. With no rule installed players never hurt each other,
     * which is the engine's long-standing default.
     */
    public interface PvpRule {
        boolean canHurt(int attackerId, PlayerState victim);

        /** Called after PvP damage is applied (kill attribution). */
        default void damaged(int attackerId, PlayerState victim) {}
    }

    /** Fired when a player dies, before they respawn (drop a carried flag…). */
    public interface DeathListener {
        void onDeath(PlayerState player, double deathX, double deathY);
    }

    /** Supplies a player's respawn point (mini games send teams home). */
    public interface RespawnProvider {
        double[] respawnPoint(int playerId);
    }

    /**
     * A projectile impact this tick — where feedback happens (particles, sfx).
     * The scene polls these offline; the server polls and broadcasts them as
     * {@code fx} messages so every client sees the same hit.
     */
    public record Impact(String key, double x, double y, boolean explosion) {}

    private PickupListener pickupListener;
    private PvpRule pvpRule;
    private DeathListener deathListener;
    private RespawnProvider respawnProvider;

    public World(Level level) {
        this(level, MobRegistry.standard(), ItemRegistry.standard());
    }

    public World(Level level, MobRegistry mobTypes, ItemRegistry itemTypes) {
        this.level = level;
        this.mobTypes = mobTypes;
        this.itemTypes = itemTypes;
        this.projectileTypes = ProjectileRegistry.standard();
        this.vehicleTypes = VehicleRegistry.standard();
    }

    /**
     * Consumes one {@code key} item from a player's inventory, wherever that
     * inventory lives — the scene's local one offline, the connection's
     * server-side one online. Lets simulation-owned effects (the Phoenix
     * Feather's death-cheat) spend items without the World holding
     * inventories itself.
     */
    public interface ItemConsumer {
        boolean consumeOne(PlayerState player, String key);
    }

    private ItemConsumer itemConsumer;

    public void setItemConsumer(ItemConsumer c) {
        this.itemConsumer = c;
    }

    public void setPickupListener(PickupListener l) {
        this.pickupListener = l;
    }

    /** Enable PvP: projectiles and explosions hit players per this rule. */
    public void setPvpRule(PvpRule rule) {
        this.pvpRule = rule;
    }

    public void setDeathListener(DeathListener l) {
        this.deathListener = l;
    }

    /** Override where dead players respawn (default: the level's spawns). */
    public void setRespawnProvider(RespawnProvider p) {
        this.respawnProvider = p;
    }

    public List<Mob> mobs() {
        return mobs;
    }

    public List<DroppedItem> items() {
        return items;
    }

    public List<Projectile> projectiles() {
        return projectiles;
    }

    public List<Vehicle> vehicles() {
        return vehicles;
    }

    /** The live vehicle with this entity id, or {@code null}. */
    public Vehicle vehicle(int id) {
        for (Vehicle v : vehicles) {
            if (v.id == id) return v;
        }
        return null;
    }

    /** Mobs killed by player damage since the last call (stat tracking). */
    public int pollKills() {
        int n = killsByPlayers;
        killsByPlayers = 0;
        return n;
    }

    /** Player deaths (respawns) since the last call (stat tracking). */
    public int pollDeaths() {
        int n = playerDeaths;
        playerDeaths = 0;
        return n;
    }

    /** Drain the impacts since the last call (feedback: particles, sfx, fx messages). */
    public List<Impact> pollImpacts() {
        if (impacts.isEmpty()) return List.of();
        List<Impact> out = new ArrayList<>(impacts);
        impacts.clear();
        return out;
    }

    /**
     * Drain tile changes the simulation made on its own (liquid flow). The
     * multiplayer server broadcasts these as authoritative block events so
     * clients' levels stay in sync.
     */
    public List<LiquidSim.Change> pollBlockChanges() {
        if (blockChanges.isEmpty()) return List.of();
        List<LiquidSim.Change> out = new ArrayList<>(blockChanges);
        blockChanges.clear();
        return out;
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

    /** Spawn the mobs/items/vehicles a level's entity list declares (feature-gated). */
    public void populateFromLevel(GameProfile profile) {
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case "mob" -> {
                    if (profile.mobsEnabled) spawnMob(e.type, e.x, e.y);
                }
                case "item" -> {
                    if (profile.itemsEnabled) spawnItem(e.type, 1, e.x, e.y);
                }
                case "vehicle" -> spawnVehicle(e.type, e.x, e.y);
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

    public Vehicle spawnVehicle(String key, double x, double y) {
        VehicleDef def = vehicleTypes.get(key);
        if (def == null) return null;
        Vehicle v = new Vehicle(nextEntityId++, def, x, y);
        vehicles.add(v);
        return v;
    }

    /**
     * Whether drops arc under gravity here, or scatter across the plane: it
     * follows the level's format, so a mined block in a top-down or isometric
     * level throws its drop sideways instead of "up".
     */
    private boolean tossGravity() {
        return level.format().gravity();
    }

    /** Remove a mob, dropped item, or vehicle by entity id (creative erase, online too). */
    public boolean removeEntity(int id) {
        return mobs.removeIf(m -> m.id == id) || items.removeIf(i -> i.id == id)
                || vehicles.removeIf(v -> v.id == id);
    }

    /** Advance everything one tick. Dead mobs drop loot and are removed. */
    public void step(double dt, List<PlayerState> players, GameProfile profile) {
        if (profile.dayNightCycle && profile.lightingEnabled) {
            timeOfDay += dt / DAY_LENGTH;
            timeOfDay -= Math.floor(timeOfDay);
        }

        // Gravity is a property of the level's format: only the
        // side-scroller has a "down" for things to fall toward.
        boolean gravityOn = profile.gravityEnabled && level.format().gravity();

        blockChanges.addAll(liquids.step(level, gravityOn, dt));

        if (profile.mobsEnabled) {
            Iterator<Mob> it = mobs.iterator();
            List<Mob> died = null;
            while (it.hasNext()) {
                Mob m = it.next();
                m.step(level, players, projectiles, gravityOn, profile.combatEnabled, dt);
                drainMobActions(m, profile);
                Block hazard = hazardAt(m.x + m.def.size() / 2, m.y + m.def.size() / 2);
                if (hazard != null) m.environmentDamage(hazard.damage() * dt);
                if (m.dead()) {
                    if (died == null) died = new ArrayList<>();
                    died.add(m);
                    it.remove();
                }
            }
            if (died != null) {
                for (Mob m : died) handleMobDeath(m, profile.itemsEnabled, profile);
            }
            if (!pendingSummons.isEmpty()) {
                for (PendingSummon s : pendingSummons) {
                    Mob minion = spawnMob(s.key(), s.x(), s.y());
                    if (minion != null) {
                        impacts.add(new Impact("summon",
                                minion.x + minion.def.size() / 2,
                                minion.y + minion.def.size() / 2, false));
                    }
                }
                pendingSummons.clear();
            }
            resolvePendingBursts(players, profile);
        }

        stepVehicles(dt, gravityOn, players, profile);

        if (profile.itemsEnabled) {
            Iterator<DroppedItem> it = items.iterator();
            while (it.hasNext()) {
                DroppedItem item = it.next();
                item.step(level, gravityOn, dt);
                if (item.pickupDelay > 0) continue;
                // Base vacuum range, extended per player by the Magnet Charm.
                PlayerState taker = nearestPickerUpper(players, item.x, item.y);
                if (taker != null) {
                    if (pickupListener != null) {
                        pickupListener.onPickup(taker, item.key, item.count);
                    }
                    it.remove();
                }
            }
        }

        if (!projectiles.isEmpty()) {
            stepProjectiles(dt, gravityOn, players, profile);
        }

        // Players: hazard blocks burn, clamp health, respawn on death (at a
        // painted multiplayer spawn point when the level has them). A carried
        // Phoenix Feather burns up instead: the player revives in place.
        double size = profile.playerSize;
        for (PlayerState p : players) {
            // Ultimates: charge the meter, keep a running one's effects live,
            // and resolve whatever a sustained ability does each tick.
            Ultimates.charge(p, dt);
            Ultimates.applyActiveEffects(p);
            stepSustainedUltimate(p, dt, profile);

            Block hazard = hazardAt(p.x + size / 2, p.y + size / 2);
            // A plan-view hop lifts the character clear of the floor, so lava
            // and spikes can be jumped over exactly as they can be in a
            // side-scroller.
            if (hazard != null && !p.hopping()) p.hurt(hazard.damage() * dt);
            if (p.health > p.maxHealth) p.health = p.maxHealth;
            if (p.health <= 0) {
                if (profile.itemsEnabled && itemConsumer != null
                        && itemConsumer.consumeOne(p, "phoenix_feather")) {
                    p.health = p.maxHealth * 0.5;
                    p.stamina = p.maxStamina;
                    impacts.add(new Impact("revive", p.x + size / 2, p.y + size / 2, false));
                    continue;
                }
                if (deathListener != null) deathListener.onDeath(p, p.x, p.y);
                dismount(p); // death unseats: the mount stays where it fell
                p.restore();
                Ultimates.clearEffects(p);
                double[] spawn = respawnProvider != null
                        ? respawnProvider.respawnPoint(p.id) : level.spawnPointFor(p.id);
                p.x = spawn[0];
                p.y = spawn[1];
                p.vy = 0;
                p.z = 0;
                p.vz = 0;
                playerDeaths++;
            }
        }
    }

    // --- mob abilities: side effects drained after each mob's step ------------------

    /** Resolve the actions a mob queued this tick: ranged shots, summons, blinks. */
    private void drainMobActions(Mob m, GameProfile profile) {
        double[] shot = m.pollRangedShot();
        if (shot != null && profile.projectilesEnabled && m.def.projectile() != null) {
            ProjectileDef def = projectileTypes.get(m.def.projectile());
            if (def != null) {
                double cx = m.x + m.def.size() / 2, cy = m.y + m.def.size() / 2;
                double dx = shot[0] - cx, dy = shot[1] - cy;
                double len = Math.max(0.001, Math.hypot(dx, dy));
                // Mob-owned shots carry a negative owner id: never dodged by
                // their own side, never hitting mobs, always hitting players.
                Projectile p = new Projectile(nextEntityId++, def, -m.id,
                        cx, cy, dx / len * def.speed(), dy / len * def.speed());
                if (m.def.damage() > 0) p.damage = m.def.damage();
                projectiles.add(p);
            }
        }
        if (m.pollSummon() && m.def.abilityArg() != null && mobs.size() < MOB_CAP) {
            // Deferred: the mob list is mid-iteration; spawn after the loop.
            double ts = level.tileSize;
            pendingSummons.add(new PendingSummon(m.def.abilityArg(),
                    m.x + (m.id % 2 == 0 ? ts : -ts), m.y));
        }
        double[] blink = m.pollBlinkFx();
        if (blink != null) {
            impacts.add(new Impact("blink", blink[0], blink[1], false));
            impacts.add(new Impact("blink", m.x + m.def.size() / 2,
                    m.y + m.def.size() / 2, false));
        }
    }

    /**
     * One dead mob's aftermath: loot, and the species' death trick — SPLIT
     * breaks it into two children, DEATH_BURST queues an area blast (deferred,
     * so chains of exploders resolve without re-entering the mob loop).
     */
    private void handleMobDeath(Mob m, boolean withLoot, GameProfile profile) {
        double cx = m.x + m.def.size() / 2, cy = m.y + m.def.size() / 2;
        if (withLoot) dropMobLoot(m);
        switch (m.def.ability()) {
            case SPLIT -> {
                // A null profile (direct melee-kill path) counts as all-enabled.
                if (m.def.abilityArg() != null
                        && (profile == null || profile.mobsEnabled)
                        && mobs.size() < MOB_CAP) {
                    spawnMob(m.def.abilityArg(), m.x - m.def.size() * 0.3, m.y);
                    spawnMob(m.def.abilityArg(), m.x + m.def.size() * 0.3, m.y);
                    impacts.add(new Impact("summon", cx, cy, false));
                }
            }
            case DEATH_BURST -> {
                double radius = Math.max(48, m.def.size() * 1.6);
                pendingBursts.add(new double[]{cx, cy, radius, m.def.damage() * 2});
                String fxKey = m.def.abilityArg() != null ? m.def.abilityArg() : "fireball";
                impacts.add(new Impact(fxKey, cx, cy, true));
            }
            default -> { /* most species just die */ }
        }
    }

    /**
     * Resolve queued death blasts. A blast may kill further exploders, whose
     * own blasts join the queue — the classic chain reaction, bounded by the
     * mob population itself.
     */
    private void resolvePendingBursts(List<PlayerState> players, GameProfile profile) {
        while (!pendingBursts.isEmpty()) {
            double[] burst = pendingBursts.poll();
            if (!profile.combatEnabled) continue;
            double bx = burst[0], by = burst[1], radius = burst[2], dmg = burst[3];
            List<Mob> killed = null;
            for (Mob m : mobs) {
                if (m.dead()) continue;
                double d = Math.hypot(m.x + m.def.size() / 2 - bx,
                        m.y + m.def.size() / 2 - by);
                if (d > radius + m.def.size() / 2) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                if (m.damage(dmg * falloff, bx)) {
                    if (killed == null) killed = new ArrayList<>();
                    killed.add(m);
                }
            }
            if (killed != null) {
                for (Mob m : killed) {
                    mobs.remove(m);
                    handleMobDeath(m, profile.itemsEnabled, profile);
                }
            }
            // Mob-sourced blasts hurt every player caught in them.
            double half = profile.playerSize / 2.0;
            for (PlayerState pl : players) {
                double d = Math.hypot(pl.x + half - bx, pl.y + half - by);
                if (d > radius + half) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                pl.hurt(dmg * falloff);
            }
        }
    }

    /** The damaging block at a world point (lava, spikes…), or {@code null}. */
    private Block hazardAt(double wx, double wy) {
        double ts = level.tileSize;
        Block b = level.blockAt((int) Math.floor(wx / ts), (int) Math.floor(wy / ts));
        return b != null && b.damage() > 0 ? b : null;
    }

    /**
     * Resolve a melee swing from {@code attacker} toward (aimX, aimY): the
     * nearest living mob within {@link #ATTACK_REACH} of the reach point takes
     * {@code damage}. Returns the mob hit, or {@code null} on a whiff.
     */
    public Mob playerAttack(PlayerState attacker, double aimX, double aimY, double damage) {
        // A running ultimate (Overdrive) multiplies what the swing lands for.
        damage *= attacker.ultDamageFactor;
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
        if (best != null) {
            // Damage dealt is what fills an ultimate meter fastest, exactly
            // like the shooter it is borrowed from.
            Ultimates.chargeFromDamage(attacker, damage);
            if (best.damage(damage, px)) {
                mobs.remove(best);
                handleMobDeath(best, true, null);
                killsByPlayers++;
            }
        }
        return best;
    }

    /**
     * Swing at an empty vehicle instead: within reach of the aim point it
     * packs back up into its source item, so mounts are never stranded.
     * Returns the packed vehicle, or {@code null} (nothing there / ridden).
     */
    public Vehicle packUpVehicle(double aimX, double aimY, boolean withDrop) {
        Vehicle best = null;
        double bestD = Double.MAX_VALUE;
        for (Vehicle v : vehicles) {
            if (v.ridden()) continue;
            double d = Math.hypot(v.x + v.def.size() / 2 - aimX,
                    v.y + v.def.size() / 2 - aimY);
            if (d < v.def.size() / 2 + 24 && d < bestD) {
                bestD = d;
                best = v;
            }
        }
        if (best == null) return null;
        vehicles.remove(best);
        if (withDrop && best.def.sourceItem() != null
                && itemTypes.get(best.def.sourceItem()) != null) {
            spawnItem(best.def.sourceItem(), 1,
                    best.x + best.def.size() / 2 - DroppedItem.SIZE / 2,
                    best.y + best.def.size() / 2 - DroppedItem.SIZE / 2)
                    .toss(0, -180, tossGravity());
        }
        impacts.add(new Impact("summon", best.x + best.def.size() / 2,
                best.y + best.def.size() / 2, false));
        return best;
    }

    /**
     * Fire what {@code shooter} is holding toward (aimX, aimY): a ranged
     * weapon launches its projectile (consuming ammo from {@code inv} when the
     * weapon needs it), a throwable throws one of itself. Returns the spawned
     * {@link Projectile}, or {@code null} when the selected item doesn't shoot
     * or the ammo ran out — callers fall back to a melee swing.
     *
     * <p>This is the ranged half of the combat seam: the same method resolves
     * clicks in single-player and {@code attack} inputs on the authoritative
     * server, so shots can't be fabricated client-side.
     */
    public Projectile playerShoot(PlayerState shooter, Inventory inv,
                                  double aimX, double aimY) {
        ItemDef held = inv.selectedDef();
        if (held == null || held.projectile() == null) return null;
        ProjectileDef def = projectileTypes.get(held.projectile());
        if (def == null) return null;
        if (held.ammo() != null && inv.remove(held.ammo(), 1) < 1) return null;
        // Ammo-less ranged weapons are magic: casting costs mana instead.
        if (held.ammo() == null && held.category() == ItemDef.Category.RANGED_WEAPON) {
            double cost = manaCost(held);
            if (shooter.mana < cost) return null;
            shooter.mana -= cost;
        }

        double dx = aimX - shooter.x, dy = aimY - shooter.y;
        double len = Math.hypot(dx, dy);
        if (len < 0.001) {
            dx = shooter.facingLeft ? -1 : 1;
            dy = 0;
            len = 1;
        }

        // Salvo weapons fire patterns instead of a single bolt: the Meteor
        // Staff calls its projectiles down from the sky above the aim point,
        // the Scatter Bow fans three arrows.
        if ("meteor_staff".equals(held.key())) {
            Projectile first = null;
            for (int i = -1; i <= 1; i++) {
                double sx = aimX + i * 46;
                double sy = Math.max(8, aimY - 280);
                double mdx = aimX - sx, mdy = aimY - sy;
                double mlen = Math.max(0.001, Math.hypot(mdx, mdy));
                Projectile p = new Projectile(nextEntityId++, def, shooter.id,
                        sx, sy, mdx / mlen * def.speed(), mdy / mlen * def.speed());
                if (held.damage() > 0) p.damage = held.damage();
                p.damage *= shooter.ultDamageFactor;
                projectiles.add(p);
                if (first == null) first = p;
            }
            return first;
        }
        int shots = "scatter_bow".equals(held.key()) ? 3 : 1;
        Projectile first = null;
        for (int i = 0; i < shots; i++) {
            double spread = shots == 1 ? 0 : (i - (shots - 1) / 2.0) * Math.toRadians(9);
            double angle = Math.atan2(dy, dx) + spread;
            Projectile p = new Projectile(nextEntityId++, def, shooter.id,
                    shooter.x, shooter.y,
                    Math.cos(angle) * def.speed(), Math.sin(angle) * def.speed());
            if (held.damage() > 0) p.damage = held.damage();
            // A running ultimate (Overdrive) empowers shots like it does swings.
            p.damage *= shooter.ultDamageFactor;
            projectiles.add(p);
            if (first == null) first = p;
        }
        return first;
    }

    /**
     * Fire a ridden vehicle's armament (a war dragon's fireball) toward the
     * aim point. Free but cooldown-limited; the shot is owned by the rider,
     * so PvP rules and kill credit apply exactly as if they'd cast it.
     * Returns the projectile, or {@code null} (unarmed / still cooling down).
     */
    public Projectile vehicleShoot(Vehicle v, PlayerState rider,
                                   double aimX, double aimY) {
        ProjectileDef def = v.def.projectile() == null
                ? null : projectileTypes.get(v.def.projectile());
        if (def == null || !v.tryFire()) return null;
        double cx = v.x + v.def.size() / 2, cy = v.y + v.def.size() / 2;
        double dx = aimX - cx, dy = aimY - cy;
        double len = Math.max(0.001, Math.hypot(dx, dy));
        Projectile p = new Projectile(nextEntityId++, def, rider.id,
                cx, cy, dx / len * def.speed(), dy / len * def.speed());
        projectiles.add(p);
        return p;
    }

    /**
     * Advance projectiles: flight (arcing under gravity in side-scroll),
     * mob hits (combat only — with combat off they're decorative physics),
     * player hits when a {@link PvpRule} allows them, terrain impacts,
     * explosions, elemental statuses, terrain shatter, and recoverable drops.
     *
     * <p>Mob-owned shots (negative owner id) mirror the rules: they never hit
     * mobs, and they hit <em>any</em> player — no {@link PvpRule} needed,
     * exactly like a mob's melee strike.
     */
    private void stepProjectiles(double dt, boolean gravityOn,
                                 List<PlayerState> players, GameProfile profile) {
        double playerRadius = profile.playerSize * 0.45;
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile p = it.next();
            boolean landed = p.step(level, gravityOn, dt);
            boolean mobShot = p.ownerId < 0;

            if (!p.dead() && !mobShot && profile.combatEnabled && profile.mobsEnabled) {
                Mob hit = mobAt(p.x, p.y, p.def.radius());
                if (hit != null) {
                    p.kill();
                    impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                    if (p.def.explosionRadius() > 0) {
                        explode(p, players, profile);
                    } else {
                        applyElementToMob(p, hit, profile);
                        // Ranged damage charges the shooter's ultimate too.
                        Ultimates.chargeFromDamage(playerById(players, p.ownerId), p.damage);
                        if (hit.damage(p.damage, p.x - p.vx)) {
                            mobs.remove(hit);
                            handleMobDeath(hit, true, profile);
                            killsByPlayers++;
                        }
                    }
                    resolveImpactEffects(p, players, profile);
                    it.remove();
                    continue;
                }
            }
            if (!p.dead() && profile.combatEnabled && (mobShot || pvpRule != null)) {
                PlayerState hit = hittablePlayerAt(players, p.ownerId,
                        p.x, p.y, p.def.radius() + playerRadius, profile);
                if (hit != null) {
                    p.kill();
                    impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                    if (p.def.explosionRadius() > 0) {
                        explode(p, players, profile);
                    } else {
                        hit.hurt(p.damage);
                        if (p.def.element() == ProjectileDef.Element.ICE) {
                            hit.stamina = 0; // webs and frost sap the sprint
                        }
                        if (!mobShot && pvpRule != null) pvpRule.damaged(p.ownerId, hit);
                    }
                    resolveImpactEffects(p, players, profile);
                    it.remove();
                    continue;
                }
            }
            if (landed) {
                impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                if (p.def.explosionRadius() > 0 && profile.combatEnabled) {
                    explode(p, players, profile);
                } else if (p.def.dropItem() != null && itemTypes.get(p.def.dropItem()) != null
                        && profile.itemsEnabled) {
                    // Physical projectiles land as recoverable items (ported
                    // throwable-recovery behaviour).
                    spawnItem(p.def.dropItem(), 1,
                            p.x - DroppedItem.SIZE / 2, p.y - DroppedItem.SIZE / 2)
                            .toss(-p.vx * 0.1, -160, tossGravity());
                }
                resolveImpactEffects(p, players, profile);
                it.remove();
            }
        }
    }

    /**
     * Impact effects every resolution path shares: terrain-shattering blasts
     * (bombs, harvest orbs — honouring the block-editing toggle) and the Warp
     * Staff's owner-teleport to wherever its bolt struck.
     */
    private void resolveImpactEffects(Projectile p, List<PlayerState> players,
                                      GameProfile profile) {
        if (p.def.breakRadius() > 0 && profile.blockEditingEnabled) {
            breakTerrain(p.x, p.y, p.def.breakRadius(), profile.itemsEnabled);
        }
        if ("warp_bolt".equals(p.def.key()) && p.ownerId >= 0) {
            for (PlayerState pl : players) {
                if (pl.id != p.ownerId) continue;
                double ts = level.tileSize;
                pl.x = Math.max(0, Math.min(p.x - profile.playerSize / 2,
                        level.width * ts - profile.playerSize));
                pl.y = Math.max(0, Math.min(p.y - profile.playerSize,
                        level.height * ts - profile.playerSize));
                pl.vy = 0;
                impacts.add(new Impact("warp", pl.x + profile.playerSize / 2,
                        pl.y + profile.playerSize / 2, false));
                break;
            }
        }
    }

    private void applyElementToMob(Projectile p, Mob hit, GameProfile profile) {
        applyElementToMob(p, hit, profile, true);
    }

    /**
     * Apply a projectile's elemental status to the mob it struck.
     * {@code allowChain} is false inside area-damage loops (a chain would
     * mutate the mob list mid-iteration).
     */
    private void applyElementToMob(Projectile p, Mob hit, GameProfile profile,
                                   boolean allowChain) {
        switch (p.def.element()) {
            case FIRE -> hit.burnTime = Math.max(hit.burnTime, 3.0);
            case ICE -> hit.chillTime = Math.max(hit.chillTime, 2.5);
            case POISON -> hit.poisonTime = Math.max(hit.poisonTime, 4.0);
            case LIGHTNING -> {
                if (!allowChain) return;
                // Chain to the nearest other mob in arc range for 60% damage.
                Mob chained = null;
                double bestD = 110;
                for (Mob m : mobs) {
                    if (m == hit || m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.size() / 2 - p.x,
                            m.y + m.def.size() / 2 - p.y);
                    if (d < bestD) {
                        bestD = d;
                        chained = m;
                    }
                }
                if (chained != null) {
                    impacts.add(new Impact("chain", chained.x + chained.def.size() / 2,
                            chained.y + chained.def.size() / 2, false));
                    if (chained.damage(p.damage * 0.6, p.x)) {
                        mobs.remove(chained);
                        handleMobDeath(chained, true, profile);
                        if (p.ownerId >= 0) killsByPlayers++;
                    }
                }
            }
            default -> { /* ARCANE, VOID, EARTH, NONE: no status */ }
        }
    }

    /**
     * Shatter every mineable block within {@code radius} px of a world point:
     * each pops its drop (when items are on) and is recorded as an
     * authoritative block change, so online servers broadcast the crater to
     * every client exactly like liquid flow. Liquids are left alone (blasts
     * don't dig water).
     */
    public int breakTerrain(double wx, double wy, double radius, boolean withDrops) {
        double ts = level.tileSize;
        int c0 = Math.max(0, (int) Math.floor((wx - radius) / ts));
        int c1 = Math.min(level.width - 1, (int) Math.floor((wx + radius) / ts));
        int r0 = Math.max(0, (int) Math.floor((wy - radius) / ts));
        int r1 = Math.min(level.height - 1, (int) Math.floor((wy + radius) / ts));
        int broken = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                double d = Math.hypot((c + 0.5) * ts - wx, (r + 0.5) * ts - wy);
                if (d > radius) continue;
                Block b = level.blockAt(c, r);
                if (b == null || b.liquid()) continue;
                if (mineBlock(c, r, withDrops) != null) {
                    blockChanges.add(new LiquidSim.Change(c, r, 0));
                    broken++;
                }
            }
        }
        return broken;
    }

    /**
     * The nearest player the projectile owner may hurt within a hit circle.
     * Mob-owned shots (negative owner) may hurt anyone; player-owned shots go
     * through the {@link PvpRule}.
     */
    private PlayerState hittablePlayerAt(List<PlayerState> players, int ownerId,
                                         double x, double y, double radius,
                                         GameProfile profile) {
        double half = profile.playerSize / 2.0;
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState pl : players) {
            if (pl.id == ownerId || pl.health <= 0) continue;
            if (ownerId >= 0 && (pvpRule == null || !pvpRule.canHurt(ownerId, pl))) continue;
            double d = Math.hypot(pl.x + half - x, pl.y + half - y);
            if (d < radius && d < bestD) {
                bestD = d;
                best = pl;
            }
        }
        return best;
    }

    /** Area damage with linear falloff (100% at the centre, 25% at the edge). */
    private void explode(Projectile p, List<PlayerState> players, GameProfile profile) {
        double radius = p.def.explosionRadius();
        boolean mobShot = p.ownerId < 0;
        List<Mob> died = null;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            // A mob's own blast never wounds the species that made it — a
            // pyromancer doesn't rout its own warband — but everyone else's do.
            if (mobShot && m.id == -p.ownerId) continue;
            double d = Math.hypot(m.x + m.def.size() / 2 - p.x, m.y + m.def.size() / 2 - p.y);
            if (d > radius + m.def.size() / 2) continue;
            double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
            applyElementToMob(p, m, profile, false);
            if (m.damage(p.damage * falloff, p.x)) {
                if (died == null) died = new ArrayList<>();
                died.add(m);
            }
        }
        if (died != null) {
            for (Mob m : died) {
                mobs.remove(m);
                handleMobDeath(m, true, profile);
                if (!mobShot) killsByPlayers++;
            }
        }
        if (mobShot || pvpRule != null) {
            double half = profile.playerSize / 2.0;
            for (PlayerState pl : players) {
                if (pl.id == p.ownerId || pl.health <= 0) continue;
                if (!mobShot && !pvpRule.canHurt(p.ownerId, pl)) continue;
                double d = Math.hypot(pl.x + half - p.x, pl.y + half - p.y);
                if (d > radius + half) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                pl.hurt(p.damage * falloff);
                if (!mobShot) pvpRule.damaged(p.ownerId, pl);
            }
        }
    }

    /** The nearest living mob whose body overlaps a circle at (x, y). */
    private Mob mobAt(double x, double y, double radius) {
        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            double d = Math.hypot(m.x + m.def.size() / 2 - x, m.y + m.def.size() / 2 - y);
            if (d < m.def.size() / 2 + radius && d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /** Mana cost of casting an ammo-less ranged weapon (staves). */
    public static double manaCost(ItemDef held) {
        return Math.max(8, held.damage() * 1.2);
    }

    // --- relic actives ([F]-activated accessories) --------------------------------

    /** Mana cost of an [F]-activated relic, or {@code null} (not an active relic). */
    public static Double relicManaCost(String itemKey) {
        return switch (itemKey == null ? "" : itemKey) {
            case "nova_crystal" -> 30.0;
            case "tremor_totem" -> 25.0;
            default -> null;
        };
    }

    /**
     * Activate a relic the player is holding: the Nova Crystal detonates an
     * arcane blast around them, the Tremor Totem shatters the surrounding
     * terrain into drops. Resolved by the same World everywhere, so relics
     * behave identically offline and on the authoritative server. Returns
     * whether the relic fired (mana and feature toggles permitting) — the
     * relic itself is never consumed.
     */
    public boolean useRelic(PlayerState p, String itemKey, GameProfile profile) {
        Double cost = relicManaCost(itemKey);
        if (cost == null || p.mana < cost) return false;
        double cx = p.x + profile.playerSize / 2, cy = p.y + profile.playerSize / 2;
        switch (itemKey) {
            case "nova_crystal" -> {
                if (!profile.combatEnabled) return false;
                p.mana -= cost;
                double radius = 130;
                List<Mob> died = null;
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.size() / 2 - cx,
                            m.y + m.def.size() / 2 - cy);
                    if (d > radius + m.def.size() / 2) continue;
                    double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                    if (m.damage(22 * falloff, cx)) {
                        if (died == null) died = new ArrayList<>();
                        died.add(m);
                    }
                }
                if (died != null) {
                    for (Mob m : died) {
                        mobs.remove(m);
                        handleMobDeath(m, profile.itemsEnabled, profile);
                        killsByPlayers++;
                    }
                }
                impacts.add(new Impact("nova", cx, cy, true));
                return true;
            }
            case "tremor_totem" -> {
                if (!profile.blockEditingEnabled) return false;
                p.mana -= cost;
                breakTerrain(cx, cy, level.tileSize * 2.6, profile.itemsEnabled);
                impacts.add(new Impact("tremor", cx, cy, true));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // --- ultimate abilities --------------------------------------------------------

    /**
     * Fire a player's charged {@link Ultimate} toward (aimX, aimY). Resolved
     * here — in the one authoritative simulation — so an ultimate behaves
     * identically in single-player, the creative play-test, and on the
     * dedicated server, and so a client can never fabricate one.
     *
     * <p>Every ability is written without reference to a gravity axis, so the
     * same code produces the same fight in a side-scroller, a top-down map,
     * and an isometric one: blasts are radial, the strike and the volley aim
     * at a point, and the buffs are self-targeted.
     *
     * <p>Returns whether it fired. Firing spends the whole meter and starts
     * the effect timer for sustained abilities ({@link Ultimates#applyActiveEffects}
     * keeps the modifiers live from there).
     */
    public boolean useUltimate(PlayerState p, double aimX, double aimY, GameProfile profile) {
        Ultimate u = Ultimates.of(p);
        if (u == null || !Ultimates.ready(p)) return false;
        double half = profile.playerSize / 2.0;
        double cx = p.x + half, cy = p.y + half;
        // Offensive abilities are combat; the terrain-wrecking one is block
        // editing. A game type with those off keeps its meter rather than
        // burning it on nothing.
        boolean offensive = switch (u.kind()) {
            case NOVA, BLINK_STRIKE, METEOR_VOLLEY, TIME_DILATION, LIFE_SIPHON,
                 EARTHSHATTER -> true;
            default -> false;
        };
        if (offensive && !profile.combatEnabled) return false;

        switch (u.kind()) {
            case OVERDRIVE, BULWARK, LIFE_SIPHON -> { /* sustained; started below */ }
            case NOVA -> {
                blastMobs(cx, cy, u.radius(), u.power(), profile);
                impacts.add(new Impact("ultimate_nova", cx, cy, true));
            }
            case BLINK_STRIKE -> {
                // Dash toward the aim, capped at the ability's reach, and cut
                // down everything along the corridor travelled.
                double dx = aimX - cx, dy = aimY - cy;
                double len = Math.hypot(dx, dy);
                double reach = Math.min(len, u.radius());
                double ux = len < 0.001 ? (p.facingLeft ? -1 : 1) : dx / len;
                double uy = len < 0.001 ? 0 : dy / len;
                impacts.add(new Impact("ultimate_blink", cx, cy, false));
                int steps = Math.max(1, (int) Math.ceil(reach / (level.tileSize * 0.5)));
                double lastX = p.x, lastY = p.y;
                for (int i = 1; i <= steps; i++) {
                    double t = reach * i / steps;
                    double nx = clampX(p.x + ux * t, profile.playerSize);
                    double ny = clampY(p.y + uy * t, profile.playerSize);
                    // Stop at the first wall rather than teleporting through it.
                    if (blockedBody(nx, ny, profile.playerSize)) break;
                    lastX = nx;
                    lastY = ny;
                    blastMobs(nx + half, ny + half, level.tileSize * 0.9,
                            u.power(), profile);
                }
                p.x = lastX;
                p.y = lastY;
                p.vy = 0;
                impacts.add(new Impact("ultimate_blink", p.x + half, p.y + half, false));
            }
            case METEOR_VOLLEY -> {
                if (!profile.projectilesEnabled) return false;
                ProjectileDef def = projectileTypes.get("meteor");
                if (def == null) def = projectileTypes.get("fireball");
                if (def == null) return false;
                int shots = Math.max(1, (int) Math.round(u.power()));
                for (int i = 0; i < shots; i++) {
                    // Fanned around the aim point, launched from outside it so
                    // they converge — the same pattern in every perspective.
                    double spread = (i - (shots - 1) / 2.0) * level.tileSize * 1.2;
                    double sx = aimX + spread;
                    double sy = aimY - u.radius();
                    double mdx = aimX - sx, mdy = aimY - sy;
                    double mlen = Math.max(0.001, Math.hypot(mdx, mdy));
                    projectiles.add(new Projectile(nextEntityId++, def, p.id, sx, sy,
                            mdx / mlen * def.speed(), mdy / mlen * def.speed()));
                }
                impacts.add(new Impact("ultimate_volley", aimX, aimY, false));
            }
            case TIME_DILATION -> {
                // Chill is the engine's existing slow, so a dilated mob reads
                // the same as an ice-hit one and needs no new status bit.
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.size() / 2 - cx,
                            m.y + m.def.size() / 2 - cy);
                    if (d <= u.radius() + m.def.size() / 2) {
                        m.chillTime = Math.max(m.chillTime, u.duration());
                    }
                }
                impacts.add(new Impact("ultimate_dilation", cx, cy, true));
            }
            case EARTHSHATTER -> {
                blastMobs(cx, cy, u.radius(), u.power(), profile);
                // Knock survivors outward: a shove along the vector away from
                // the caster, which reads as a shockwave in any perspective.
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double mx = m.x + m.def.size() / 2, my = m.y + m.def.size() / 2;
                    double d = Math.hypot(mx - cx, my - cy);
                    if (d > u.radius() + m.def.size() / 2 || d < 0.001) continue;
                    double push = level.tileSize * 1.4 * (1 - Math.min(1, d / u.radius()));
                    m.x = clampX(m.x + (mx - cx) / d * push, m.def.size());
                    m.y = clampY(m.y + (my - cy) / d * push, m.def.size());
                }
                if (profile.blockEditingEnabled) {
                    breakTerrain(cx, cy, level.tileSize * 2.2, profile.itemsEnabled);
                }
                impacts.add(new Impact("ultimate_shatter", cx, cy, true));
            }
        }

        p.ultCharge = 0;
        if (u.sustained()) {
            p.ultActive = u.duration();
            p.ultActiveKey = u.key();
            Ultimates.applyActiveEffects(p);
            impacts.add(new Impact("ultimate_" + u.key(), cx, cy, false));
        }
        return true;
    }

    /**
     * The per-tick half of a sustained ultimate — the part that keeps
     * happening while the timer runs, rather than the modifiers
     * {@link Ultimates#applyActiveEffects} sets on the player.
     */
    private void stepSustainedUltimate(PlayerState p, double dt, GameProfile profile) {
        if (p.ultActive <= 0) return;
        Ultimate u = Ultimates.get(p.ultActiveKey);
        if (u == null) return;
        double half = profile.playerSize / 2.0;
        double cx = p.x + half, cy = p.y + half;
        switch (u.kind()) {
            case BULWARK -> // Regenerate alongside the damage soak.
                    p.health = Math.min(p.maxHealth, p.health + u.power() * 12 * dt);
            case LIFE_SIPHON -> {
                if (!profile.combatEnabled) return;
                double drained = 0;
                List<Mob> died = null;
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.size() / 2 - cx,
                            m.y + m.def.size() / 2 - cy);
                    if (d > u.radius() + m.def.size() / 2) continue;
                    double tick = u.power() * dt;
                    drained += tick;
                    if (m.damage(tick, cx)) {
                        if (died == null) died = new ArrayList<>();
                        died.add(m);
                    }
                }
                if (died != null) {
                    for (Mob m : died) {
                        mobs.remove(m);
                        handleMobDeath(m, profile.itemsEnabled, profile);
                        killsByPlayers++;
                    }
                }
                if (drained > 0) {
                    p.health = Math.min(p.maxHealth, p.health + drained);
                    Ultimates.chargeFromDamage(p, drained);
                }
            }
            default -> { /* the rest are pure modifiers on the player */ }
        }
    }

    /**
     * Damage every living mob inside a circle with distance falloff, clearing
     * the dead out and crediting the kills. The shared body of the radial
     * ultimates (and the shape the relic nova already used).
     */
    private void blastMobs(double cx, double cy, double radius, double damage,
                           GameProfile profile) {
        if (radius <= 0 || damage <= 0) return;
        List<Mob> died = null;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            double d = Math.hypot(m.x + m.def.size() / 2 - cx,
                    m.y + m.def.size() / 2 - cy);
            if (d > radius + m.def.size() / 2) continue;
            double falloff = 1.0 - Math.min(1, d / radius) * 0.6;
            if (m.damage(damage * falloff, cx)) {
                if (died == null) died = new ArrayList<>();
                died.add(m);
            }
        }
        if (died != null) {
            for (Mob m : died) {
                mobs.remove(m);
                handleMobDeath(m, profile.itemsEnabled, profile);
                killsByPlayers++;
            }
        }
    }

    /** Whether a body of {@code size} at (x, y) overlaps solid terrain. */
    private boolean blockedBody(double x, double y, double size) {
        double ts = level.tileSize;
        int c0 = (int) Math.floor(x / ts);
        int c1 = (int) Math.floor((x + size - 0.001) / ts);
        int r0 = (int) Math.floor(y / ts);
        int r1 = (int) Math.floor((y + size - 0.001) / ts);
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                if (level.solidAt(c, r)) return true;
            }
        }
        return false;
    }

    private double clampX(double x, double size) {
        return Math.max(0, Math.min(x, level.width * (double) level.tileSize - size));
    }

    private double clampY(double y, double size) {
        return Math.max(0, Math.min(y, level.height * (double) level.tileSize - size));
    }

    // --- vehicles & mounts ---------------------------------------------------------

    /** How close a player must stand to mount a vehicle, in world px. */
    public static final double MOUNT_RANGE = 70;

    /** The vehicle a player could mount from (px, py), or {@code null}. */
    public Vehicle mountableNear(double px, double py) {
        Vehicle best = null;
        double bestD = MOUNT_RANGE;
        for (Vehicle v : vehicles) {
            if (v.ridden()) continue;
            double d = Math.hypot(v.x + v.def.size() / 2 - px,
                    v.y + v.def.size() / 2 - py);
            if (d <= bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    /**
     * Seat a player on a vehicle. Validates range and that the saddle is
     * free, so online clients can't teleport onto a mount across the map.
     */
    public boolean mount(PlayerState p, int vehicleId, GameProfile profile) {
        Vehicle v = vehicle(vehicleId);
        if (v == null || v.ridden() || p.riding >= 0) return false;
        double d = Math.hypot(v.x + v.def.size() / 2 - (p.x + profile.playerSize / 2),
                v.y + v.def.size() / 2 - (p.y + profile.playerSize / 2));
        if (d > MOUNT_RANGE * 1.5) return false;
        v.riderId = p.id;
        p.riding = v.id;
        v.seat(p, profile.playerSize);
        impacts.add(new Impact("mount", v.x + v.def.size() / 2, v.y, false));
        return true;
    }

    /** Unseat a player from whatever they're riding. Returns the vehicle left. */
    public Vehicle dismount(PlayerState p) {
        if (p.riding < 0) return null;
        Vehicle v = vehicle(p.riding);
        p.riding = -1;
        if (v != null && v.riderId == p.id) {
            v.riderId = -1;
            p.y = v.y - 4; // step off onto (roughly) the saddle height
            p.vy = 0;
        }
        return v;
    }

    /**
     * Drive a ridden vehicle with its rider's input for one tick, then lock
     * the rider to the saddle. The drill kind additionally grinds through the
     * terrain it's driven into (block-editing toggle permitting) — broken
     * blocks pop drops and broadcast like any other authoritative edit.
     * Called by the scene offline and by the server's player step online,
     * <em>instead of</em> {@code PlayerPhysics.step} for that player.
     */
    public void driveVehicle(Vehicle v, PlayerState rider, PlayerInput in,
                             GameProfile profile, double dt) {
        boolean gravityOn = profile.gravityEnabled && level.format().gravity();
        if (v.def.kind() == VehicleDef.Kind.DRILL && profile.blockEditingEnabled) {
            drillTerrain(v, in, profile, dt);
        }
        v.stepDriven(level, in, gravityOn, dt);
        v.seat(rider, profile.playerSize);
    }

    /** Grind the tiles the drill is driving into (ahead, and below when held). */
    private void drillTerrain(Vehicle v, PlayerInput in, GameProfile profile, double dt) {
        double ts = level.tileSize;
        double size = v.def.size();
        boolean withDrops = profile.itemsEnabled;
        if (in.left || in.right) {
            double aheadX = in.left ? v.x - ts * 0.4 : v.x + size + ts * 0.4;
            int col = (int) Math.floor(aheadX / ts);
            int rTop = (int) Math.floor((v.y + 2) / ts);
            int rBot = (int) Math.floor((v.y + size - 2) / ts);
            for (int r = rTop; r <= rBot; r++) drillTile(col, r, withDrops);
        }
        if (in.down) {
            int row = (int) Math.floor((v.y + size + ts * 0.4) / ts);
            int cL = (int) Math.floor((v.x + 2) / ts);
            int cR = (int) Math.floor((v.x + size - 2) / ts);
            for (int c = cL; c <= cR; c++) drillTile(c, row, withDrops);
        }
    }

    private void drillTile(int col, int row, boolean withDrops) {
        Block b = level.blockAt(col, row);
        if (b == null || b.liquid()) return;
        if (mineBlock(col, row, withDrops) != null) {
            blockChanges.add(new LiquidSim.Change(col, row, 0));
            impacts.add(new Impact("tremor", (col + 0.5) * level.tileSize,
                    (row + 0.5) * level.tileSize, false));
        }
    }

    /**
     * Per-tick vehicle upkeep inside {@link #step}: idle physics for empty
     * (or abandoned) vehicles, and ramming damage for armoured mounts driven
     * into mobs at speed.
     */
    private void stepVehicles(double dt, boolean gravityOn,
                              List<PlayerState> players, GameProfile profile) {
        for (Vehicle v : vehicles) {
            boolean driven = v.consumeDriven();
            if (!driven) {
                // A rider who vanished (disconnect) leaves the saddle empty.
                if (v.ridden() && playerById(players, v.riderId) == null) v.riderId = -1;
                v.stepIdle(level, gravityOn, dt);
            }
            if (driven && v.def.contactDamage() > 0 && profile.combatEnabled
                    && profile.mobsEnabled
                    && Math.abs(v.vx) > v.def.speed() * 0.5) {
                Mob hit = mobAt(v.x + v.def.size() / 2, v.y + v.def.size() / 2,
                        v.def.size() * 0.55);
                if (hit != null && v.tryRam()) {
                    impacts.add(new Impact("chain", hit.x + hit.def.size() / 2,
                            hit.y + hit.def.size() / 2, false));
                    if (hit.damage(v.def.contactDamage(), v.x)) {
                        mobs.remove(hit);
                        handleMobDeath(hit, profile.itemsEnabled, profile);
                        killsByPlayers++;
                    }
                }
            }
        }
    }

    private static PlayerState playerById(List<PlayerState> players, int id) {
        for (PlayerState p : players) {
            if (p.id == id) return p;
        }
        return null;
    }

    // --- block durability (hold-to-mine) -----------------------------------------

    private int mineCol = Integer.MIN_VALUE, mineRow = Integer.MIN_VALUE;
    private double mineProgress;

    /**
     * Advance a hold-to-mine stroke on (col,row) by {@code dt}. Progress
     * accumulates against the block's {@link Block#hardness()}; a held tool
     * whose {@link ItemDef#toolClass()} matches the block's preferred
     * {@link Block#tool()} multiplies speed by its {@link ItemDef#toolPower()}.
     * Switching cells restarts progress. Returns the block just broken, or
     * {@code null} while still chipping away.
     */
    public Block continueMining(int col, int row, ItemDef held, boolean withDrops, double dt) {
        Block b = level.blockAt(col, row);
        if (b == null || b.liquid()) {
            // Liquids can't be mined away — displace them by placing a block
            // over them instead (see placeBlock).
            cancelMining();
            return null;
        }
        if (col != mineCol || row != mineRow) {
            mineCol = col;
            mineRow = row;
            mineProgress = 0;
        }
        double hardness = b.hardness();
        if (hardness <= 0) {
            cancelMining();
            return mineBlock(col, row, withDrops);
        }
        double power = held != null && held.toolClass() != null
                && held.toolClass().equals(b.tool()) ? held.toolPower() : 1.0;
        mineProgress += dt * power / hardness;
        if (mineProgress < 1) return null;
        cancelMining();
        return mineBlock(col, row, withDrops);
    }

    /** Stop the current mining stroke (mouse released / aim moved away). */
    public void cancelMining() {
        mineCol = mineRow = Integer.MIN_VALUE;
        mineProgress = 0;
    }

    /** Mining progress [0,1) on the current cell, for the crack overlay. */
    public double miningProgress() {
        return mineProgress;
    }

    /** The cell being mined as {col,row}, or {@code null} when idle. */
    public int[] miningCell() {
        return mineCol == Integer.MIN_VALUE ? null : new int[]{mineCol, mineRow};
    }

    /**
     * Mine the block at (col,row). When {@code withDrops}, the block's drop
     * item pops out with a little kick (side-scroller behaviour); creative
     * painting passes {@code false}. Returns the mined block, or {@code null}.
     */
    public Block mineBlock(int col, int row, boolean withDrops) {
        Block b = level.blockAt(col, row);
        if (b == null || b.liquid()) return null; // liquids aren't minable
        // Storage blocks spill their second inventory when broken.
        List<ItemStack> stored = b.container() ? level.removeContainer(col, row) : null;
        if (!level.setTile(col, row, 0)) return null;
        double ts = level.tileSize;
        if (withDrops && b.drops() != null && itemTypes.get(b.drops()) != null) {
            spawnItem(b.drops(), 1,
                    col * ts + ts / 2 - DroppedItem.SIZE / 2,
                    row * ts + ts / 2 - DroppedItem.SIZE / 2)
                    .toss(((col + row) % 2 == 0 ? 60 : -60), -260, tossGravity());
        }
        if (stored != null && withDrops) {
            int i = 0;
            for (ItemStack s : stored) {
                DroppedItem drop = spawnItem(s.key, s.count,
                        col * ts + ts / 2 - DroppedItem.SIZE / 2,
                        row * ts + ts / 2 - DroppedItem.SIZE / 2);
                if (drop != null) drop.toss((i++ % 3 - 1) * 90, -220, tossGravity());
            }
        }
        return b;
    }

    /** Place block {@code id} at (col,row) if the cell is empty (or liquid). */
    public boolean placeBlock(int col, int row, int id) {
        if (level.tileAt(col, row) != 0 && level.liquidAt(col, row) == null) return false;
        return level.setTile(col, row, id);
    }

    /** Species-specific loot (elemental essences, trinkets); key -> {item, count}. */
    private static final Map<String, Object[]> SPECIES_LOOT = Map.ofEntries(
            Map.entry("skeleton", new Object[]{"arrow", 3}),
            Map.entry("skeleton_archer", new Object[]{"arrow", 4}),
            Map.entry("goblin_slinger", new Object[]{"rock", 3}),
            Map.entry("dark_ranger", new Object[]{"throwing_knife", 2}),
            Map.entry("fire_imp", new Object[]{"fire_essence", 1}),
            Map.entry("pyromancer", new Object[]{"fire_essence", 2}),
            Map.entry("phoenix", new Object[]{"phoenix_feather", 1}),
            Map.entry("ice_witch", new Object[]{"frost_essence", 1}),
            Map.entry("frost_revenant", new Object[]{"frost_essence", 2}),
            Map.entry("yeti", new Object[]{"frost_essence", 1}),
            Map.entry("storm_caller", new Object[]{"storm_essence", 1}),
            Map.entry("venom_spitter", new Object[]{"venom_gland", 1}),
            Map.entry("sand_scorpion", new Object[]{"venom_gland", 1}),
            Map.entry("plague_rat", new Object[]{"venom_gland", 1}),
            Map.entry("shadow_wraith", new Object[]{"shadow_essence", 1}),
            Map.entry("banshee", new Object[]{"shadow_essence", 1}),
            Map.entry("necromancer", new Object[]{"scroll", 1}),
            Map.entry("void_stalker", new Object[]{"void_shard", 1}),
            Map.entry("stone_golem", new Object[]{"stone", 3}),
            Map.entry("treant", new Object[]{"oak_log", 2}),
            Map.entry("vampire", new Object[]{"ruby", 1}),
            Map.entry("ancient_dragon", new Object[]{"dragon_egg", 1}),
            Map.entry("ember_wisp", new Object[]{"fire_essence", 1}),
            Map.entry("firefly", new Object[]{"torch", 1}));

    private void dropMobLoot(Mob m) {
        // Species with signature loot drop it (the essence economy for
        // elemental crafting); everything else drops by temperament as before.
        Object[] special = SPECIES_LOOT.get(m.def.key());
        String loot;
        int count;
        if (special != null && itemTypes.get((String) special[0]) != null) {
            loot = (String) special[0];
            count = (Integer) special[1];
        } else {
            loot = switch (m.def.temperament()) {
                case PASSIVE -> "cooked_meat";
                case NEUTRAL -> "leather";
                case HOSTILE -> "coal";
            };
            count = 1;
        }
        if (itemTypes.get(loot) != null) {
            spawnItem(loot, count, m.x + m.def.size() / 2, m.y + m.def.size() / 2)
                    .toss(0, -200, tossGravity());
        }
    }

    /**
     * Apply a food item's effects: heal directly, restore stamina alongside
     * (hearty meals get you moving again), and rare-or-better delicacies also
     * restore mana.
     */
    public static void applyFood(PlayerState p, ItemDef def) {
        p.health = Math.min(p.maxHealth, p.health + def.heal());
        p.stamina = Math.min(p.maxStamina, p.stamina + def.heal() * 0.6);
        if (def.rarity().ordinal() >= ItemDef.Rarity.RARE.ordinal()) {
            p.mana = Math.min(p.maxMana, p.mana + def.heal() * 0.5);
        }
    }

    // --- destructible decorations (trees, rocks…) ---------------------------------

    /** Chop progress per painted decoration, cleared when one breaks. */
    private final Map<Level.EntitySpawn, Integer> decorHits = new HashMap<>();

    public enum ChopResult { NONE, HIT, BROKEN }

    /**
     * Swing at the harvestable decoration under (aimX, aimY), if any: trees,
     * rocks and the like carry an optional hitbox and break down into
     * resources (logs + leaves for trees) after a few hits — an axe chops
     * twice as fast. Purely-ornamental shapes are ignored.
     */
    public ChopResult chopDecor(double aimX, double aimY, boolean axe, boolean withDrops) {
        DecorRegistry registry = DecorRegistry.standard();
        Level.EntitySpawn best = null;
        Decor bestDef = null;
        double bestD = Double.MAX_VALUE;
        for (Level.EntitySpawn e : level.entities) {
            if (!"decor_bg".equals(e.kind) && !"decor_fg".equals(e.kind)) continue;
            Decor def = registry.get(e.type);
            if (def == null || harvestDrops(def.shape()) == null) continue;
            double h = def.sizeTiles() * level.tileSize;
            // Decor sprites anchor at their bottom-centre.
            double d = Math.hypot(aimX - e.x, aimY - (e.y - h / 2));
            if (d <= h / 2 + 10 && d < bestD) {
                bestD = d;
                best = e;
                bestDef = def;
            }
        }
        if (best == null) return ChopResult.NONE;
        int needed = 2 + (int) bestDef.sizeTiles();
        int hits = decorHits.merge(best, axe ? 2 : 1, Integer::sum);
        if (hits < needed) return ChopResult.HIT;
        decorHits.remove(best);
        level.entities.remove(best);
        if (withDrops) {
            double h = bestDef.sizeTiles() * level.tileSize;
            int i = 0;
            for (String drop : harvestDrops(bestDef.shape())) {
                if (itemTypes.get(drop) == null) continue;
                DroppedItem item = spawnItem(drop, 1,
                        best.x - DroppedItem.SIZE / 2, best.y - h * 0.5);
                if (item != null) item.toss((i++ % 3 - 1) * 80, -220, tossGravity());
            }
        }
        return ChopResult.BROKEN;
    }

    /** What a decoration shape breaks down into, or {@code null} = not harvestable. */
    private static String[] harvestDrops(Decor.Shape shape) {
        return switch (shape) {
            case TREE, PINE -> new String[]{"oak_log", "oak_log", "leaves", "leaves"};
            case DEAD_TREE -> new String[]{"oak_log", "stick"};
            case LOG -> new String[]{"oak_log", "oak_log"};
            case ROCK, STONES, STALAGMITE -> new String[]{"stone", "stone"};
            case BUSH -> new String[]{"stick", "leaves"};
            case MUSHROOM -> new String[]{"mushroom"};
            case CACTUS -> new String[]{"cactus"};
            case CRYSTAL -> new String[]{"crystal"};
        };
    }

    /**
     * The player a dropped item should fly to: nearest one within the base
     * pickup range, extended per player by a carried Magnet Charm
     * ({@code PlayerState.pickupBonus}).
     */
    private static PlayerState nearestPickerUpper(List<PlayerState> players,
                                                  double x, double y) {
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState p : players) {
            double d = Math.hypot(p.x - x, p.y - y);
            if (d <= PICKUP_RANGE + p.pickupBonus && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
