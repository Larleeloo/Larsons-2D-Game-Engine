package com.larsons.engine.world;

import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.combat.Melee;
import com.larsons.engine.combat.MeleeAction;
import com.larsons.engine.combat.MeleeProfile;
import com.larsons.engine.combat.MeleeProfiles;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.profile.FrameProfiler;
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
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
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

    /**
     * How far above the floor a plan-view sky strike starts. The same distance
     * a side-scrolling salvo spawns up the screen, so a meteor takes about as
     * long to arrive in every format.
     */
    private static final double SKY_HEIGHT = 280;

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
    public record Impact(String key, double x, double y, boolean explosion) {

        /**
         * Prefix marking an impact as an ultimate ability's, followed by the
         * ability's own key ({@code ultimate_meteor_volley}). Feedback code
         * matches on this rather than on a list of effect kinds, so a new
         * ability is named and heard without anything downstream changing.
         */
        public static final String ULTIMATE_PREFIX = "ultimate_";

        /** The ability key when this is an ultimate's impact, else {@code ""}. */
        public String ultimateKey() {
            return key.startsWith(ULTIMATE_PREFIX)
                    ? key.substring(ULTIMATE_PREFIX.length()) : "";
        }
    }

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

    /**
     * The physical space this world simulates in — which axis is up, and so
     * where "called down from the sky" spawns and which way a fall goes. Read
     * from the level every time it is asked for, because a door can move play
     * from a side-scrolling dungeon into a top-down overworld mid-session.
     */
    public PerspectiveSpace space() {
        return PerspectiveSpace.of(level.format());
    }

    /** Remove a mob, dropped item, or vehicle by entity id (creative erase, online too). */
    public boolean removeEntity(int id) {
        return mobs.removeIf(m -> m.id == id) || items.removeIf(i -> i.id == id)
                || vehicles.removeIf(v -> v.id == id);
    }

    /**
     * Where the simulation reports its own phases, or {@code null} when nobody
     * is measuring — the headless server, and every test.
     *
     * <p><b>SIM_PLAN S1.</b> The scene has had a six-way breakdown since B0 and
     * this had none, so six profiles could say the update stage spiked to 15-21
     * ms and not one of them could say what in it spiked. That asymmetry is the
     * reason Job B could be planned from measurements and SIM_PLAN could not
     * start at all.
     */
    private FrameProfiler profiler;

    /** Report this simulation's phase timings here. */
    public void setProfiler(FrameProfiler profiler) {
        this.profiler = profiler;
    }

    /**
     * Time one named phase of the step into {@link FrameProfiler.Stage#UPDATE}.
     *
     * <p>Phases do not have to add up to the stage: naming the ones worth
     * naming and leaving the rest as the remainder is what lets a suspect be
     * eliminated without first accounting for everything.
     */
    private void phase(String name, Runnable work) {
        FrameProfiler p = profiler;
        if (p == null) {
            work.run();
            return;
        }
        long started = p.begin();
        try {
            work.run();
        } finally {
            p.recordSection(FrameProfiler.Stage.UPDATE, name, started);
        }
    }

    /**
     * Remember where every body in this world currently is, so a renderer can
     * draw the next frame between this step and the one before it rather than on
     * top of whichever finished last — see
     * {@link com.larsons.engine.sim.StepInterpolation}.
     *
     * <p>Called by {@link #step} so that no caller can forget it, and public
     * because a scene that <em>skips</em> a step (paused, a cutscene running, a
     * character choice open) has to record the standstill itself: the blend runs
     * on every frame whether or not the simulation did, and two positions left
     * straddling a step that was never taken would show as the world sliding back
     * and forth while nothing is happening.
     *
     * <p>Costs four field copies per body and allocates nothing, which is why it
     * is unconditional rather than gated on whether anything is going to move.
     */
    public void beginStep() {
        for (Mob m : mobs) m.beginStep();
        for (DroppedItem i : items) i.beginStep();
        for (Projectile p : projectiles) p.beginStep();
        for (Vehicle v : vehicles) v.beginStep();
        stepCaptured = true;
    }

    /**
     * Whether {@link #beginStep()} has already run for the tick {@link #step}
     * is about to take, so that {@code step} does not take a second capture.
     *
     * <p><b>This latch is load-bearing and the bug without it is a quiet one.</b>
     * A scene captures at the top of its tick — it has to, so that a tick it
     * skips still holds the picture still — and then does work that moves bodies
     * <em>before</em> calling {@code step}: driving a ridden vehicle is the case
     * that exists. A second capture inside {@code step} would overwrite "where it
     * was at the top of the tick" with "where the drive already took it", so the
     * blend would span only the part of the tick that happened after {@code step}
     * was called. The ridden vehicle would then sit a fraction of a step away from
     * the rider glued to its saddle, which is a shimmer between two things that
     * are supposed to be one thing.
     */
    private boolean stepCaptured;

    /**
     * Advance everything one tick. Dead mobs drop loot and are removed.
     *
     * <p>Opens with {@link #beginStep()}, which is where the set of bodies is
     * known and so the only place a capture cannot miss one. Bodies the profile
     * has switched off are captured anyway — they are not stepped, so their
     * remembered position equals their current one and the blend is a no-op on
     * them.
     *
     * <p>The {@code players} are deliberately <em>not</em> captured there. They
     * are advanced by their owning scene before this is called (locally) or by
     * the server's own input pass, so capturing them now would remember a
     * position they have already left.
     */
    public void step(double dt, List<PlayerState> players, GameProfile profile) {
        // Only if the caller has not already taken this tick's capture; see the
        // note on stepCaptured for why a second one is wrong rather than merely
        // redundant. Cleared at the end, so the next tick captures again.
        if (!stepCaptured) beginStep();
        if (profile.dayNightCycle && profile.lightingEnabled) {
            timeOfDay += dt / DAY_LENGTH;
            timeOfDay -= Math.floor(timeOfDay);
        }

        // Gravity is a property of the level's format: only the
        // side-scroller has a "down" for things to fall toward.
        boolean gravityOn = profile.gravityEnabled && level.format().gravity();

        phase("liquids", () -> blockChanges.addAll(liquids.step(level, gravityOn, dt)));

        if (profile.mobsEnabled) phase("mobs", () -> stepMobs(dt, players, gravityOn, profile));

        phase("vehicles", () -> stepVehicles(dt, gravityOn, players, profile));

        if (profile.itemsEnabled) phase("items", () -> stepItems(players, gravityOn, dt));

        if (!projectiles.isEmpty()) {
            phase("projectiles", () -> stepProjectiles(dt, gravityOn, players, profile));
        }
        stepRest(dt, players, profile, gravityOn);
        stepCaptured = false; // the next tick takes its own capture
    }

    private void stepMobs(double dt, List<PlayerState> players, boolean gravityOn,
                          GameProfile profile) {
        {
            Iterator<Mob> it = mobs.iterator();
            List<Mob> died = null;
            while (it.hasNext()) {
                Mob m = it.next();
                m.step(level, players, projectiles, gravityOn, profile.combatEnabled, dt);
                drainMobActions(m, profile);
                Block hazard = hazardAt(m.x + m.def.hitbox() / 2, m.y + m.def.hitbox() / 2);
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
                                minion.x + minion.def.hitbox() / 2,
                                minion.y + minion.def.hitbox() / 2, false));
                    }
                }
                pendingSummons.clear();
            }
            resolvePendingBursts(players, profile);
        }
    }

    private void stepItems(List<PlayerState> players, boolean gravityOn, double dt) {

        {
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

    }

    private void stepRest(double dt, List<PlayerState> players, GameProfile profile,
                          boolean gravityOn) {
        // Players: hazard blocks burn, clamp health, respawn on death (at a
        // painted multiplayer spawn point when the level has them). A carried
        // Phoenix Feather burns up instead: the player revives in place.
        for (PlayerState p : players) {
            double size = body(p, profile);
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
                double cx = m.x + m.def.hitbox() / 2, cy = m.y + m.def.hitbox() / 2;
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
            impacts.add(new Impact("blink", m.x + m.def.hitbox() / 2,
                    m.y + m.def.hitbox() / 2, false));
        }
    }

    /**
     * One dead mob's aftermath: loot, and the species' death trick — SPLIT
     * breaks it into two children, DEATH_BURST queues an area blast (deferred,
     * so chains of exploders resolve without re-entering the mob loop).
     */
    private void handleMobDeath(Mob m, boolean withLoot, GameProfile profile) {
        double cx = m.x + m.def.hitbox() / 2, cy = m.y + m.def.hitbox() / 2;
        if (withLoot) dropMobLoot(m);
        switch (m.def.ability()) {
            case SPLIT -> {
                // A null profile (direct melee-kill path) counts as all-enabled.
                if (m.def.abilityArg() != null
                        && (profile == null || profile.mobsEnabled)
                        && mobs.size() < MOB_CAP) {
                    spawnMob(m.def.abilityArg(), m.x - m.def.hitbox() * 0.3, m.y);
                    spawnMob(m.def.abilityArg(), m.x + m.def.hitbox() * 0.3, m.y);
                    impacts.add(new Impact("summon", cx, cy, false));
                }
            }
            case DEATH_BURST -> {
                double radius = Math.max(48, m.def.hitbox() * 1.6);
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
                double d = Math.hypot(m.x + m.def.hitbox() / 2 - bx,
                        m.y + m.def.hitbox() / 2 - by);
                if (d > radius + m.def.hitbox() / 2) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                if (m.damage(dmg * falloff, bx, by, space())) {
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
            for (PlayerState pl : players) {
                double half = body(pl, profile) / 2.0;
                double d = Math.hypot(pl.x + half - bx, pl.y + half - by);
                if (d > radius + half) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                pl.takeBlow(dmg * falloff);
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
     *
     * <p>The bare-hands shape, kept for callers with no weapon profile to
     * hand; {@link #meleeStrike} is what the melee moves resolve through.
     */
    public Mob playerAttack(PlayerState attacker, double aimX, double aimY, double damage) {
        return meleeStrike(attacker, MeleeProfiles.fists(), MeleeAction.SWING,
                aimX, aimY, damage).mob();
    }

    /**
     * What a melee strike did: the mob it reached (or {@code null} on a
     * whiff), whether that mob <em>caught</em> the blow, and the damage that
     * actually landed. The caller uses this to pick the sound, the particles,
     * and — on a parry — to stagger the attacker.
     */
    public record MeleeHit(Mob mob, boolean parried, double damage) {
        /** Whether the strike reached anything at all. */
        public boolean hit() {
            return mob != null;
        }

        /** Whether the mob it reached died of it. */
        public boolean killed() {
            return mob != null && mob.dead();
        }

        static final MeleeHit MISS = new MeleeHit(null, false, 0);
    }

    /**
     * Resolve one melee strike with a weapon's own reach and arc: the nearest
     * living mob inside the strike's cone, from the attacker's centre toward
     * the aim, takes {@code damage} scaled by the move.
     *
     * <p>Two things a bare swing didn't do happen here. An armed mob gets the
     * chance to <em>catch</em> the blow ({@link Mob#tryParry()}) — the strike
     * deals nothing and the attacker is left staggered — and a strike that
     * lands adds the profile's knockback on top of the usual shove.
     *
     * <p>Like everything else in this class it is the one implementation: the
     * play scene, the creative play-test and the authoritative server all
     * resolve strikes through it, so a swing cannot mean different things in
     * different modes.
     */
    public MeleeHit meleeStrike(PlayerState attacker, MeleeProfile profile,
                                MeleeAction action, double aimX, double aimY,
                                double damage) {
        // A running ultimate (Overdrive) multiplies what the swing lands for.
        damage *= attacker.ultDamageFactor;
        double reach = profile == null ? ATTACK_REACH : profile.reach();
        double half = level.tileSize / 2.0;
        double cx = attacker.x + half, cy = attacker.y + half;
        double[] hit = Melee.strikePoint(cx, cy, aimX, aimY, reach);

        // The cone the strike sweeps: everything within the weapon's arc of
        // the aim direction, which is why a hammer catches a crowd and a spear
        // catches whatever is directly in front of it.
        double aimAngle = Math.atan2(hit[1] - cy, hit[0] - cx);
        double halfArc = Math.toRadians((profile == null ? 120 : profile.arc()) / 2.0);

        Mob best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            double mx = m.x + m.def.hitbox() / 2, my = m.y + m.def.hitbox() / 2;
            double d = Math.hypot(mx - hit[0], my - hit[1]);
            if (d >= m.def.hitbox() / 2 + 24 || d >= bestD) continue;
            // You cannot hit what is standing on the roof above you, nor what
            // is in the street below — the melee half of S2's reach.
            if (!withinArmsReach(m.z, attacker.z)) continue;
            // Anything already touching the fighter is in the arc by
            // definition; only reaching out has a direction to miss in.
            double toMob = Math.hypot(mx - cx, my - cy);
            if (toMob > m.def.hitbox() / 2 + 4
                    && angleBetween(aimAngle, Math.atan2(my - cy, mx - cx)) > halfArc) {
                continue;
            }
            bestD = d;
            best = m;
        }
        if (best == null) return MeleeHit.MISS;

        if (best.tryParry()) {
            // Caught. The blade rings off the guard and the swing is wasted.
            impacts.add(new Impact("parry", best.x + best.def.hitbox() / 2,
                    best.y + best.def.hitbox() / 2, false));
            return new MeleeHit(best, true, 0);
        }
        // Damage dealt is what fills an ultimate meter fastest, exactly
        // like the shooter it is borrowed from.
        Ultimates.chargeFromDamage(attacker, damage);
        double before = best.health;
        boolean killed = best.damage(damage, cx, cy, space());
        double dealt = Math.max(0, before - best.health);
        if (dealt > 0 && profile != null && profile.knockback() > 0) {
            shove(best, cx, cy, profile.knockback());
        }
        if (killed) {
            mobs.remove(best);
            handleMobDeath(best, true, null);
            killsByPlayers++;
        }
        return new MeleeHit(best, false, dealt);
    }

    /**
     * Resolve an open parry window against the shots in the air: anything
     * inside the guard's reach in front of {@code defender} is batted back the
     * way it came and turned against whoever fired it. Returns how many were
     * turned, so the caller can ring the parry.
     *
     * <p>This is the reward for timing a parry against a ranged attacker,
     * which a shield can only soak.
     */
    public int parryProjectiles(PlayerState defender, MeleeProfile profile) {
        double reach = (profile == null ? ATTACK_REACH : profile.reach()) * 1.2;
        double half = level.tileSize / 2.0;
        double cx = defender.x + half, cy = defender.y + half;
        int turned = 0;
        for (Projectile p : projectiles) {
            if (p.dead() || p.ownerId >= 0) continue; // our own volleys fly on
            if (Math.hypot(p.x - cx, p.y - cy) > reach) continue;
            // Straight back at the shooter, and now owned by the parrier — so
            // it hurts mobs on the way home.
            p.vx = -p.vx;
            p.vy = -p.vy;
            p.ownerId = defender.id;
            turned++;
            impacts.add(new Impact("parry", p.x, p.y, false));
        }
        return turned;
    }

    /** An extra shove away from (fromX, fromY), along whatever axes exist here. */
    private void shove(Mob m, double fromX, double fromY, double amount) {
        double half = m.def.hitbox() / 2;
        double dx = m.x + half - fromX, dy = m.y + half - fromY;
        double len = Math.hypot(dx, dy);
        if (len < 0.001) {
            dx = 1;
            dy = 0;
            len = 1;
        }
        m.x += dx / len * amount;
        if (space().hasElevation()) m.y += dy / len * amount;
        m.x = Math.max(0, Math.min(m.x, level.width * (double) level.tileSize - m.def.hitbox()));
        m.y = Math.max(0, Math.min(m.y, level.height * (double) level.tileSize - m.def.hitbox()));
    }

    /** The smallest angle between two headings, in radians. */
    private static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2);
        return d > Math.PI ? Math.PI * 2 - d : d;
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
            for (int i = 0; i < 3; i++) {
                Projectile p = skyStrike(def, shooter.id, aimX, aimY, i, 3, 46, SKY_HEIGHT);
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
     * One shot of a salvo called down onto (aimX, aimY) — the Meteor Staff's
     * three, the Meteor Volley ultimate's five. Where "down" comes <em>from</em>
     * is the level format's business, which is the whole point of routing both
     * callers through here:
     *
     * <ul>
     *   <li><b>Side-scroller</b> — the sky is up the screen. The salvo spawns
     *       {@code height} above the aim point, fanned along x by
     *       {@code spacing}, and dives at it. Unchanged.</li>
     *   <li><b>Top-down / isometric</b> — the screen is the floor, so there is
     *       no "above" on it. The salvo spawns {@code height} up the elevation
     *       axis, ringed around the aim point at {@code spacing}, and falls
     *       onto the tile the caster picked. Previously it spawned a screen's
     *       worth of pixels <em>north</em> of the target and flew in sideways
     *       along the ground, which is what a warped side-scrolling sky gets
     *       you.</li>
     * </ul>
     */
    private Projectile skyStrike(ProjectileDef def, int ownerId,
                                 double aimX, double aimY,
                                 int index, int shots, double spacing, double height) {
        if (space().hasElevation()) {
            double angle = shots <= 1 ? 0 : index * (Math.PI * 2 / shots);
            double radius = shots <= 1 ? 0 : spacing;
            return Projectile.fromSky(nextEntityId++, def, ownerId,
                    clampX(aimX + Math.cos(angle) * radius, 0),
                    clampY(aimY + Math.sin(angle) * radius, 0),
                    aimX, aimY, height);
        }
        double sx = aimX + (index - (shots - 1) / 2.0) * spacing;
        double sy = Math.max(8, aimY - height);
        double dx = aimX - sx, dy = aimY - sy;
        double len = Math.max(0.001, Math.hypot(dx, dy));
        return new Projectile(nextEntityId++, def, ownerId, sx, sy,
                dx / len * def.speed(), dy / len * def.speed());
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
        PerspectiveSpace space = space();
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile p = it.next();
            boolean landed = p.step(level, space, gravityOn, dt);
            boolean mobShot = p.ownerId < 0;
            // A shot still falling out of the sky is above everyone's head: it
            // strikes when it reaches the floor (where z is back to 0 and the
            // usual resolution below runs), not on the way past. Reaching the
            // end of its life up there fizzles instead.
            if (p.airborne()) {
                if (landed) {
                    impacts.add(new Impact(p.def.key(), p.x, p.y,
                            p.def.explosionRadius() > 0));
                    it.remove();
                }
                continue;
            }

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
                        if (hit.damage(p.damage, p.x - p.vx, p.y - p.vy, space())) {
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
                        p.x, p.y, p.def.radius(), profile);
                if (hit != null) {
                    p.kill();
                    impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                    if (p.def.explosionRadius() > 0) {
                        explode(p, players, profile);
                    } else {
                        hit.takeBlow(p.damage);
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
                double body = body(pl, profile);
                pl.x = Math.max(0, Math.min(p.x - body / 2,
                        level.width * ts - body));
                pl.y = Math.max(0, Math.min(p.y - body,
                        level.height * ts - body));
                pl.vy = 0;
                impacts.add(new Impact("warp", pl.x + body(pl, profile) / 2,
                        pl.y + body(pl, profile) / 2, false));
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
                    double d = Math.hypot(m.x + m.def.hitbox() / 2 - p.x,
                            m.y + m.def.hitbox() / 2 - p.y);
                    if (d < bestD) {
                        bestD = d;
                        chained = m;
                    }
                }
                if (chained != null) {
                    impacts.add(new Impact("chain", chained.x + chained.def.hitbox() / 2,
                            chained.y + chained.def.hitbox() / 2, false));
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
                // A blast takes the top of the stack, like a tool does.
                Block b = level.topBlockAt(c, r);
                if (b == null || b.liquid()) continue;
                int layer = mineLayer(c, r);
                if (mineBlock(c, r, withDrops) != null) {
                    blockChanges.add(new LiquidSim.Change(c, r, 0, layer));
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
    /**
     * The player a shot of {@code radius} at (x,y) strikes, if any.
     *
     * <p>{@code radius} is the <em>shot's</em> reach; each body adds its own,
     * because how much of the world a player fills is now theirs to say and
     * one radius for everybody would have a giant shot at like a sparrow.
     */
    private PlayerState hittablePlayerAt(List<PlayerState> players, int ownerId,
                                         double x, double y, double radius,
                                         GameProfile profile) {
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState pl : players) {
            if (pl.id == ownerId || pl.health <= 0) continue;
            if (ownerId >= 0 && (pvpRule == null || !pvpRule.canHurt(ownerId, pl))) continue;
            double body = body(pl, profile);
            double half = body / 2.0;
            double d = Math.hypot(pl.x + half - x, pl.y + half - y);
            if (d < radius + body * 0.45 && d < bestD) {
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
            double d = Math.hypot(m.x + m.def.hitbox() / 2 - p.x, m.y + m.def.hitbox() / 2 - p.y);
            if (d > radius + m.def.hitbox() / 2) continue;
            // A blast on the street does not reach the roof. This check was
            // deferred out of W7 rather than written against a constant zero:
            // mobs had no height then, so it would have read like a rule and
            // done nothing (HEIGHT_PLAN.md S2).
            if (!withinArmsReach(m.z, p.z)) continue;
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
            for (PlayerState pl : players) {
                if (pl.id == p.ownerId || pl.health <= 0) continue;
                double half = body(pl, profile) / 2.0;
                if (!mobShot && !pvpRule.canHurt(p.ownerId, pl)) continue;
                double d = Math.hypot(pl.x + half - p.x, pl.y + half - p.y);
                if (d > radius + half) continue;
                // A blast at ground level does not reach the roof, and one on
                // the roof does not rain on the street.
                if (!withinArmsReach(pl.z, p.z)) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                pl.takeBlow(p.damage * falloff);
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
            double d = Math.hypot(m.x + m.def.hitbox() / 2 - x, m.y + m.def.hitbox() / 2 - y);
            if (d < m.def.hitbox() / 2 + radius && d < bestD) {
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
        double cx = p.x + body(p, profile) / 2, cy = p.y + body(p, profile) / 2;
        switch (itemKey) {
            case "nova_crystal" -> {
                if (!profile.combatEnabled) return false;
                p.mana -= cost;
                double radius = 130;
                List<Mob> died = null;
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.hitbox() / 2 - cx,
                            m.y + m.def.hitbox() / 2 - cy);
                    if (d > radius + m.def.hitbox() / 2) continue;
                    double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                    if (m.damage(22 * falloff, cx, cy, space())) {
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
        double half = body(p, profile) / 2.0;
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
                impacts.add(new Impact("ultimate_" + u.key(), cx, cy, true));
            }
            case BLINK_STRIKE -> {
                // Dash toward the aim, capped at the ability's reach, and cut
                // down everything along the corridor travelled.
                double dx = aimX - cx, dy = aimY - cy;
                double len = Math.hypot(dx, dy);
                double reach = Math.min(len, u.radius());
                double ux = len < 0.001 ? (p.facingLeft ? -1 : 1) : dx / len;
                double uy = len < 0.001 ? 0 : dy / len;
                impacts.add(new Impact("ultimate_" + u.key(), cx, cy, false));
                int steps = Math.max(1, (int) Math.ceil(reach / (level.tileSize * 0.5)));
                double lastX = p.x, lastY = p.y;
                for (int i = 1; i <= steps; i++) {
                    double t = reach * i / steps;
                    double nx = clampX(p.x + ux * t, body(p, profile));
                    double ny = clampY(p.y + uy * t, body(p, profile));
                    // Stop at the first wall rather than teleporting through it.
                    if (blockedBody(nx, ny, body(p, profile))) break;
                    lastX = nx;
                    lastY = ny;
                    blastMobs(nx + half, ny + half, level.tileSize * 0.9,
                            u.power(), profile);
                }
                p.x = lastX;
                p.y = lastY;
                p.vy = 0;
                impacts.add(new Impact("ultimate_" + u.key(), p.x + half, p.y + half, false));
            }
            case METEOR_VOLLEY -> {
                if (!profile.projectilesEnabled) return false;
                ProjectileDef def = projectileTypes.get("meteor");
                if (def == null) def = projectileTypes.get("fireball");
                if (def == null) return false;
                int shots = Math.max(1, (int) Math.round(u.power()));
                for (int i = 0; i < shots; i++) {
                    // Fanned around the aim point and launched from outside it
                    // so they converge on it — a line up the screen in a
                    // side-scroller, a ring on the floor overhead on a plane.
                    projectiles.add(skyStrike(def, p.id, aimX, aimY,
                            i, shots, level.tileSize * 1.2, u.radius()));
                }
                impacts.add(new Impact("ultimate_" + u.key(), aimX, aimY, false));
            }
            case TIME_DILATION -> {
                // Chill is the engine's existing slow, so a dilated mob reads
                // the same as an ice-hit one and needs no new status bit.
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double d = Math.hypot(m.x + m.def.hitbox() / 2 - cx,
                            m.y + m.def.hitbox() / 2 - cy);
                    if (d <= u.radius() + m.def.hitbox() / 2) {
                        m.chillTime = Math.max(m.chillTime, u.duration());
                    }
                }
                impacts.add(new Impact("ultimate_" + u.key(), cx, cy, true));
            }
            case EARTHSHATTER -> {
                blastMobs(cx, cy, u.radius(), u.power(), profile);
                // Knock survivors outward: a shove along the vector away from
                // the caster, which reads as a shockwave in any perspective.
                for (Mob m : mobs) {
                    if (m.dead()) continue;
                    double mx = m.x + m.def.hitbox() / 2, my = m.y + m.def.hitbox() / 2;
                    double d = Math.hypot(mx - cx, my - cy);
                    if (d > u.radius() + m.def.hitbox() / 2 || d < 0.001) continue;
                    double push = level.tileSize * 1.4 * (1 - Math.min(1, d / u.radius()));
                    m.x = clampX(m.x + (mx - cx) / d * push, m.def.hitbox());
                    m.y = clampY(m.y + (my - cy) / d * push, m.def.hitbox());
                }
                if (profile.blockEditingEnabled) {
                    breakTerrain(cx, cy, level.tileSize * 2.2, profile.itemsEnabled);
                }
                impacts.add(new Impact("ultimate_" + u.key(), cx, cy, true));
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
        double half = body(p, profile) / 2.0;
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
                    double d = Math.hypot(m.x + m.def.hitbox() / 2 - cx,
                            m.y + m.def.hitbox() / 2 - cy);
                    if (d > u.radius() + m.def.hitbox() / 2) continue;
                    double tick = u.power() * dt;
                    drained += tick;
                    if (m.damage(tick, cx, cy, space())) {
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
            double d = Math.hypot(m.x + m.def.hitbox() / 2 - cx,
                    m.y + m.def.hitbox() / 2 - cy);
            if (d > radius + m.def.hitbox() / 2) continue;
            double falloff = 1.0 - Math.min(1, d / radius) * 0.6;
            if (m.damage(damage * falloff, cx, cy, space())) {
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

    /**
     * How much floor a player occupies, world pixels: their character's own
     * footprint, or the game type's default for a body that never chose one.
     *
     * <p>Everything in here that asks where a player <em>is</em> — a blast's
     * reach, a shot's target, where a mount seats them, where a warp sets them
     * down — asks this, and never how large they are drawn. See
     * {@link com.larsons.engine.sim.ActorSize}.
     */
    private static double body(PlayerState p, GameProfile profile) {
        return p.hitSize(profile.playerSize);
    }

    /**
     * Whether a body of {@code size} at (x, y) is standing in solid terrain —
     * the same shape movement collides with, so a blink cannot land somewhere
     * a walk could not have reached. On a plane that is the ground under the
     * feet rather than the whole box, which is the difference between a dash
     * stopping against a wall and stopping a body-length short of one.
     */
    private boolean blockedBody(double x, double y, double size) {
        return space().hasElevation()
                ? PlayerPhysics.footBlocked(level, x, y, size)
                : PlayerPhysics.blocked(level, x, y, size, size);
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
        double d = Math.hypot(v.x + v.def.size() / 2 - (p.x + body(p, profile) / 2),
                v.y + v.def.size() / 2 - (p.y + body(p, profile) / 2));
        if (d > MOUNT_RANGE * 1.5) return false;
        v.riderId = p.id;
        p.riding = v.id;
        v.seat(p, body(p, profile));
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
        v.seat(rider, body(rider, profile));
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
        Block b = level.topBlockAt(col, row);
        if (b == null || b.liquid()) return;
        int layer = mineLayer(col, row);
        if (mineBlock(col, row, withDrops) != null) {
            blockChanges.add(new LiquidSim.Change(col, row, 0, layer));
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
                    impacts.add(new Impact("chain", hit.x + hit.def.hitbox() / 2,
                            hit.y + hit.def.hitbox() / 2, false));
                    if (hit.damage(v.def.contactDamage(), v.x, v.y, space())) {
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
        Block b = level.topBlockAt(col, row);
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
     * Which layer of (col,row) a tool bites into: the stacked block when one is
     * standing there, else the floor. A stack comes apart from the top down, so
     * mining a wall in a plan view turns it into a path, and mining that path
     * leaves the hole nobody can cross.
     */
    public int mineLayer(int col, int row) {
        return level.tileAt(col, row, Level.LAYER_UPPER) > 0
                ? Level.LAYER_UPPER : Level.LAYER_GROUND;
    }

    /**
     * Mine the block at (col,row) — the stacked one first, where a level
     * stacks. When {@code withDrops}, the block's drop item pops out with a
     * little kick (side-scroller behaviour); creative painting passes
     * {@code false}. Returns the mined block, or {@code null}.
     */
    public Block mineBlock(int col, int row, boolean withDrops) {
        int layer = mineLayer(col, row);
        Block b = level.blocks.get(level.tileAt(col, row, layer));
        if (b == null || b.liquid()) return null; // liquids aren't minable
        // Storage blocks spill their second inventory when broken.
        List<ItemStack> stored = b.container() ? level.removeContainer(col, row) : null;
        if (!level.setTile(col, row, layer, 0)) return null;
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

    /**
     * Place block {@code id} at (col,row) if there is room for it. A stack is
     * built from the bottom up: a hole is floored first, and only a cell that
     * already has a floor gets a block stood on it. Liquids are displaced
     * either way, which is how a pool is filled in.
     */
    public boolean placeBlock(int col, int row, int id) {
        int layer = level.placeLayer(col, row);
        if (layer < 0) return false;
        return level.setTile(col, row, layer, id);
    }

    /** {@link Level#placeLayer} — the layer a placed block would land in. */
    public int placeLayer(int col, int row) {
        return level.placeLayer(col, row);
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
            spawnItem(loot, count, m.x + m.def.hitbox() / 2, m.y + m.def.hitbox() / 2)
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
     * What a swing at a decoration did, and to what. The {@link Decor} comes
     * back with the result so a caller that wants to react to <em>which</em>
     * tree was hit — to play its own chopping sound — doesn't have to scan
     * the level's decorations a second time to find out.
     *
     * @param result what the swing achieved
     * @param decor  the decoration hit, or {@code null} when nothing was
     */
    public record Chop(ChopResult result, Decor decor) {
        /** "The swing hit nothing" — what a miss returns. */
        public static final Chop NONE = new Chop(ChopResult.NONE, null);

        /** Whether the swing connected with anything at all. */
        public boolean hit() {
            return result != ChopResult.NONE;
        }

        public boolean broken() {
            return result == ChopResult.BROKEN;
        }
    }

    /**
     * Swing at the harvestable decoration under (aimX, aimY), if any: trees,
     * rocks and the like carry an optional hitbox and break down into
     * resources (logs + leaves for trees) after a few hits — an axe chops
     * twice as fast. Purely-ornamental shapes are ignored.
     */
    public Chop chopDecor(double aimX, double aimY, boolean axe, boolean withDrops) {
        DecorRegistry registry = DecorRegistry.standard();
        Level.EntitySpawn best = harvestableAt(aimX, aimY);
        Decor bestDef = best == null ? null : registry.get(best.type);
        if (best == null || bestDef == null) return Chop.NONE;
        int needed = 2 + (int) bestDef.sizeTiles();
        int hits = decorHits.merge(best, axe ? 2 : 1, Integer::sum);
        if (hits < needed) return new Chop(ChopResult.HIT, bestDef);
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
        return new Chop(ChopResult.BROKEN, bestDef);
    }

    /**
     * The harvestable decoration under an aim point, or {@code null} when
     * there is none. Split out of {@link #chopDecor} so callers that only
     * want to <em>know</em> what is there — the play scene, to play that
     * tree's own chopping sound — don't have to swing at it.
     */
    private Level.EntitySpawn harvestableAt(double aimX, double aimY) {
        DecorRegistry registry = DecorRegistry.standard();
        Level.EntitySpawn best = null;
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
            }
        }
        return best;
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
    private PlayerState nearestPickerUpper(List<PlayerState> players,
                                           double x, double y) {
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        double itemZ = restingZ(x, y);
        for (PlayerState p : players) {
            double d = Math.hypot(p.x - x, p.y - y);
            if (d > PICKUP_RANGE + p.pickupBonus || d >= bestD) continue;
            if (!withinArmsReach(p.z, itemZ)) continue;
            bestD = d;
            best = p;
        }
        return best;
    }

    // --- reach, once there is a height to reach across --------------------------

    /**
     * How far apart two things may be along the height axis and still touch:
     * one block, which is a body's own reach up or down.
     *
     * <p>Below this everything is as it was, because everything was at the same
     * height. Above it a player on a roof cannot be hit from the street, cannot
     * pick up what is lying in it, and cannot open a chest down there — which
     * is the first rule players will find, and the first they would file a bug
     * about if it were missing.
     */
    private boolean withinArmsReach(double aZ, double bZ) {
        if (!level.verticality()) return true;
        return Math.abs(aZ - bZ) <= level.blockHeight() + 1;
    }

    /**
     * The height something lying on the floor at (x,y) rests at — the top of
     * whatever column is under it.
     */
    public double restingZ(double x, double y) {
        if (!level.verticality()) return 0;
        int col = (int) Math.floor(x / level.tileSize);
        int row = (int) Math.floor(y / level.tileSize);
        int support = level.supportHeight(col, row);
        return support <= 0 ? 0 : level.surfaceZ(support);
    }

    /**
     * Whether {@code p} can reach the cell (col,row) to mine it, place against
     * it, or open what is standing in it. Distance on the plane was the whole
     * question while everything was at one height; now the column's top has to
     * be within arm's reach of the player's feet as well.
     */
    public boolean canReachCell(PlayerState p, int col, int row) {
        int support = level.supportHeight(col, row);
        double top = support <= 0 ? 0 : level.surfaceZ(support);
        return withinArmsReach(p.z, top);
    }
}
