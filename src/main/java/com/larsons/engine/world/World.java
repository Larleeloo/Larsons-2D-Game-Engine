package com.larsons.engine.world;

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
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;

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

    public final Level level;
    public final MobRegistry mobTypes;
    public final ItemRegistry itemTypes;
    public final ProjectileRegistry projectileTypes;

    private final List<Mob> mobs = new ArrayList<>();
    private final List<DroppedItem> items = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Impact> impacts = new ArrayList<>();
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

        blockChanges.addAll(liquids.step(level, gravityOn, dt));

        if (profile.mobsEnabled) {
            Iterator<Mob> it = mobs.iterator();
            List<Mob> died = null;
            while (it.hasNext()) {
                Mob m = it.next();
                m.step(level, players, projectiles, gravityOn, profile.combatEnabled, dt);
                Block hazard = hazardAt(m.x + m.def.size() / 2, m.y + m.def.size() / 2);
                if (hazard != null) m.environmentDamage(hazard.damage() * dt);
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

        if (!projectiles.isEmpty()) {
            stepProjectiles(dt, gravityOn, players, profile);
        }

        // Players: hazard blocks burn, clamp health, respawn on death (at a
        // painted multiplayer spawn point when the level has them).
        double size = profile.playerSize;
        for (PlayerState p : players) {
            Block hazard = hazardAt(p.x + size / 2, p.y + size / 2);
            if (hazard != null) p.health -= hazard.damage() * dt;
            if (p.health > PlayerState.MAX_HEALTH) p.health = PlayerState.MAX_HEALTH;
            if (p.health <= 0) {
                if (deathListener != null) deathListener.onDeath(p, p.x, p.y);
                p.health = PlayerState.MAX_HEALTH;
                p.stamina = PlayerState.MAX_STAMINA;
                p.mana = PlayerState.MAX_MANA;
                double[] spawn = respawnProvider != null
                        ? respawnProvider.respawnPoint(p.id) : level.spawnPointFor(p.id);
                p.x = spawn[0];
                p.y = spawn[1];
                p.vy = 0;
                playerDeaths++;
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
            killsByPlayers++;
        }
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
        Projectile p = new Projectile(nextEntityId++, def, shooter.id,
                shooter.x, shooter.y,
                dx / len * def.speed(), dy / len * def.speed());
        if (held.damage() > 0) p.damage = held.damage();
        projectiles.add(p);
        return p;
    }

    /**
     * Advance projectiles: flight (arcing under gravity in side-scroll),
     * mob hits (combat only — with combat off they're decorative physics),
     * player hits when a {@link PvpRule} allows them, terrain impacts,
     * explosions, and recoverable drops.
     */
    private void stepProjectiles(double dt, boolean gravityOn,
                                 List<PlayerState> players, GameProfile profile) {
        double playerRadius = profile.playerSize * 0.45;
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile p = it.next();
            boolean landed = p.step(level, gravityOn, dt);

            if (!p.dead() && profile.combatEnabled && profile.mobsEnabled) {
                Mob hit = mobAt(p.x, p.y, p.def.radius());
                if (hit != null) {
                    p.kill();
                    impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                    if (p.def.explosionRadius() > 0) {
                        explode(p, players, profile);
                    } else if (hit.damage(p.damage, p.x - p.vx)) {
                        mobs.remove(hit);
                        dropMobLoot(hit);
                        killsByPlayers++;
                    }
                    it.remove();
                    continue;
                }
            }
            if (!p.dead() && profile.combatEnabled && pvpRule != null) {
                PlayerState hit = hittablePlayerAt(players, p.ownerId,
                        p.x, p.y, p.def.radius() + playerRadius, profile);
                if (hit != null) {
                    p.kill();
                    impacts.add(new Impact(p.def.key(), p.x, p.y, p.def.explosionRadius() > 0));
                    if (p.def.explosionRadius() > 0) {
                        explode(p, players, profile);
                    } else {
                        hit.health -= p.damage;
                        pvpRule.damaged(p.ownerId, hit);
                    }
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
                            .toss(-p.vx * 0.1, -160);
                }
                it.remove();
            }
        }
    }

    /** The nearest player the projectile owner may hurt within a hit circle. */
    private PlayerState hittablePlayerAt(List<PlayerState> players, int ownerId,
                                         double x, double y, double radius,
                                         GameProfile profile) {
        double half = profile.playerSize / 2.0;
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState pl : players) {
            if (pl.id == ownerId || pl.health <= 0) continue;
            if (!pvpRule.canHurt(ownerId, pl)) continue;
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
        List<Mob> died = null;
        for (Mob m : mobs) {
            if (m.dead()) continue;
            double d = Math.hypot(m.x + m.def.size() / 2 - p.x, m.y + m.def.size() / 2 - p.y);
            if (d > radius + m.def.size() / 2) continue;
            double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
            if (m.damage(p.damage * falloff, p.x)) {
                if (died == null) died = new ArrayList<>();
                died.add(m);
            }
        }
        if (died != null) {
            for (Mob m : died) {
                mobs.remove(m);
                dropMobLoot(m);
                killsByPlayers++;
            }
        }
        if (pvpRule != null) {
            double half = profile.playerSize / 2.0;
            for (PlayerState pl : players) {
                if (pl.id == p.ownerId || pl.health <= 0) continue;
                if (!pvpRule.canHurt(p.ownerId, pl)) continue;
                double d = Math.hypot(pl.x + half - p.x, pl.y + half - p.y);
                if (d > radius + half) continue;
                double falloff = 1.0 - Math.min(1, d / radius) * 0.75;
                pl.health -= p.damage * falloff;
                pvpRule.damaged(p.ownerId, pl);
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
                    .toss(((col + row) % 2 == 0 ? 60 : -60), -260);
        }
        if (stored != null && withDrops) {
            int i = 0;
            for (ItemStack s : stored) {
                DroppedItem drop = spawnItem(s.key, s.count,
                        col * ts + ts / 2 - DroppedItem.SIZE / 2,
                        row * ts + ts / 2 - DroppedItem.SIZE / 2);
                if (drop != null) drop.toss((i++ % 3 - 1) * 90, -220);
            }
        }
        return b;
    }

    /** Place block {@code id} at (col,row) if the cell is empty (or liquid). */
    public boolean placeBlock(int col, int row, int id) {
        if (level.tileAt(col, row) != 0 && level.liquidAt(col, row) == null) return false;
        return level.setTile(col, row, id);
    }

    private void dropMobLoot(Mob m) {
        // Skeletons drop arrows (the ammo economy for bows); everything else
        // drops by temperament as before.
        String loot = "skeleton".equals(m.def.key()) ? "arrow"
                : switch (m.def.temperament()) {
            case PASSIVE -> "cooked_meat";
            case NEUTRAL -> "leather";
            case HOSTILE -> "coal";
        };
        int count = "arrow".equals(loot) ? 3 : 1;
        if (itemTypes.get(loot) != null) {
            spawnItem(loot, count, m.x + m.def.size() / 2, m.y + m.def.size() / 2)
                    .toss(0, -200);
        }
    }

    /**
     * Apply a food item's effects: heal directly, restore stamina alongside
     * (hearty meals get you moving again), and rare-or-better delicacies also
     * restore mana.
     */
    public static void applyFood(PlayerState p, ItemDef def) {
        p.health = Math.min(PlayerState.MAX_HEALTH, p.health + def.heal());
        p.stamina = Math.min(PlayerState.MAX_STAMINA, p.stamina + def.heal() * 0.6);
        if (def.rarity().ordinal() >= ItemDef.Rarity.RARE.ordinal()) {
            p.mana = Math.min(PlayerState.MAX_MANA, p.mana + def.heal() * 0.5);
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
                if (item != null) item.toss((i++ % 3 - 1) * 80, -220);
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
