package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the expanded mob roster: ranged species that fire
 * projectiles at players, per-species abilities (summon, split, death burst,
 * teleport, regen, lifesteal, shield), elemental statuses (burn, chill,
 * poison, chained lightning), the species loot table, and the replicated
 * status bits — all pure World simulation, so what passes here behaves
 * identically on the authoritative multiplayer server.
 */
class MobExpansionTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("Mob Test");
        p.audioEnabled = false;
        return p;
    }

    /** A flat registry-mode level: 60 wide, 12 tall, solid dirt floor. */
    private static Level flatLevel() {
        Level lvl = Level.empty("Flat", 60, 12, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles[lvl.height - 1][c] = dirt;
        }
        return lvl;
    }

    private static void step(World world, GameProfile p, PlayerState player, double seconds) {
        for (int i = 0; i < (int) (seconds * 60); i++) {
            world.step(1.0 / 60, List.of(player), p);
        }
    }

    // --- registry sanity ---------------------------------------------------------

    @Test
    void expandedRosterIsRegisteredAndConsistent() {
        MobRegistry mobs = MobRegistry.standard();
        assertTrue(mobs.all().size() >= 45, "the roster should be greatly expanded, got "
                + mobs.all().size());
        ProjectileRegistry shots = ProjectileRegistry.standard();
        for (MobDef def : mobs.all()) {
            assertNotNull(EntitySprites.mob(def, 32), def.key() + " renders a sprite");
            if (def.projectile() != null) {
                assertNotNull(shots.get(def.projectile()),
                        def.key() + " fires a registered projectile");
            }
            if (def.ability() == MobDef.Ability.SUMMON
                    || def.ability() == MobDef.Ability.SPLIT) {
                assertNotNull(mobs.get(def.abilityArg()),
                        def.key() + "'s minion/child species must be registered");
            }
        }
        // A representative of each new archetype exists.
        for (String key : new String[]{"skeleton_archer", "ice_witch", "storm_caller",
                "necromancer", "giant_slime", "boomshroom", "troll", "vampire",
                "stone_golem", "shadow_wraith", "shadow_panther", "wild_boar",
                "ancient_dragon", "phoenix", "void_stalker"}) {
            assertNotNull(mobs.get(key), key + " should exist");
        }
    }

    // --- ranged mobs -------------------------------------------------------------

    @Test
    void rangedMobsFireProjectilesThatHurtPlayers() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false; // hold positions still: pure combat test
        world.spawnMob("skeleton_archer", 300, 100);
        PlayerState target = new PlayerState(1, "target", 160, 100);

        boolean mobShotSeen = false;
        for (int i = 0; i < 600 && target.health >= PlayerState.MAX_HEALTH; i++) {
            world.step(1.0 / 60, List.of(target), p);
            for (Projectile shot : world.projectiles()) {
                if (shot.ownerId < 0) mobShotSeen = true;
            }
            target.health = Math.max(target.health, 1); // keep it alive
        }
        assertTrue(mobShotSeen, "the archer looses arrows (owner id < 0)");
        assertTrue(target.health < PlayerState.MAX_HEALTH,
                "an arrow found its mark");
    }

    @Test
    void mobShotsNeverHitOtherMobs() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        world.spawnMob("skeleton_archer", 300, 100);
        Mob bystander = world.spawnMob("sheep", 230, 100); // right in the line of fire
        PlayerState target = new PlayerState(1, "target", 160, 100);

        for (int i = 0; i < 600; i++) {
            world.step(1.0 / 60, List.of(target), p);
            target.health = Math.max(target.health, 1);
        }
        assertEquals(bystander.def.maxHealth(), bystander.health, 0.001,
                "friendly fire is off for mob-owned shots");
    }

    // --- abilities ---------------------------------------------------------------

    @Test
    void necromancersSummonMinions() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        world.spawnMob("necromancer", 300, 100);
        PlayerState bait = new PlayerState(1, "bait", 220, 100);

        boolean summoned = false;
        for (int i = 0; i < 600 && !summoned; i++) {
            world.step(1.0 / 60, List.of(bait), p);
            bait.health = PlayerState.MAX_HEALTH; // shrug off shadow bolts
            for (World.Impact im : world.pollImpacts()) {
                if ("summon".equals(im.key())) summoned = true;
            }
        }
        assertTrue(summoned, "a summon fires within the first cooldown windows");
        assertTrue(world.mobs().stream().anyMatch(m -> "zombie".equals(m.def.key())),
                "the minion species joined the fight");
    }

    @Test
    void giantSlimesSplitWhenKilled() {
        World world = new World(flatLevel());
        world.spawnMob("giant_slime", 200, 300);
        PlayerState hunter = new PlayerState(1, "hunter", 190, 300);
        for (int i = 0; i < 30 && world.mobs().stream()
                .anyMatch(m -> "giant_slime".equals(m.def.key())); i++) {
            world.playerAttack(hunter, 220, 320, 30);
        }
        assertEquals(2, world.mobs().stream()
                        .filter(m -> "slime".equals(m.def.key())).count(),
                "death splits the giant into two ordinary slimes");
    }

    @Test
    void boomshroomsDetonateOnDeath() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        world.spawnMob("boomshroom", 210, 100);
        PlayerState victim = new PlayerState(1, "victim", 200, 100);

        for (int i = 0; i < 30 && !world.mobs().isEmpty(); i++) {
            world.playerAttack(victim, 220, 110, 30);
        }
        assertTrue(world.mobs().isEmpty(), "the boomshroom died");
        world.step(1.0 / 60, List.of(victim), p); // deferred burst resolves
        assertTrue(victim.health < PlayerState.MAX_HEALTH,
                "the death blast caught the killer standing next to it");
        assertTrue(world.pollImpacts().stream().anyMatch(World.Impact::explosion),
                "the blast is reported for FX");
    }

    @Test
    void wraithsBlinkTowardTheirPrey() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        Mob wraith = world.spawnMob("shadow_wraith", 400, 100);
        PlayerState prey = new PlayerState(1, "prey", 180, 100);

        boolean blinked = false;
        for (int i = 0; i < 600 && !blinked; i++) {
            world.step(1.0 / 60, List.of(prey), p);
            prey.health = PlayerState.MAX_HEALTH;
            for (World.Impact im : world.pollImpacts()) {
                if ("blink".equals(im.key())) blinked = true;
            }
        }
        assertTrue(blinked, "the wraith teleported (blink FX emitted)");
        assertTrue(Math.abs(wraith.x - prey.x) < 200,
                "the blink closed the distance");
    }

    @Test
    void trollsRegenerateAndVampiresLifesteal() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        Mob troll = world.spawnMob("troll", 200, 200);
        troll.damage(60, 0);
        double wounded = troll.health;
        step(world, p, new PlayerState(1, "far", 1500, 200), 4.0);
        assertTrue(troll.health > wounded + 5, "the troll knit its wounds closed");

        Mob vampire = world.spawnMob("vampire", 400, 320);
        vampire.damage(30, 0);
        double hungry = vampire.health;
        PlayerState meal = new PlayerState(2, "meal", 405, 320);
        for (int i = 0; i < 120 && meal.health >= PlayerState.MAX_HEALTH; i++) {
            world.step(1.0 / 60, List.of(meal), p);
        }
        assertTrue(meal.health < PlayerState.MAX_HEALTH, "the vampire fed");
        assertTrue(vampire.health > hungry, "feeding healed it");
    }

    @Test
    void golemsShrugOffDamageWhileShielded() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        Mob golem = world.spawnMob("stone_golem", 300, 100);
        assertFalse(golem.shielded(), "the stance starts down");
        golem.damage(10, 0);
        assertEquals(golem.def.maxHealth() - 10, golem.health, 0.001);

        // Walk the clock into the shield window (4 s down, then 1.6 s up),
        // with no players near so nothing else touches it.
        step(world, p, new PlayerState(1, "far", 1800, 100), 4.5);
        assertTrue(golem.shielded(), "the guard stance came up");
        double before = golem.health;
        golem.damage(50, 0);
        assertEquals(before, golem.health, 0.001, "shielded damage is absorbed");
        assertTrue((golem.statusBits() & Mob.STATUS_SHIELDED) != 0,
                "the shield glows on the wire");
    }

    // --- elemental statuses ------------------------------------------------------

    @Test
    void fireBurnsOverTimeAndIceChills() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        Mob sheep = world.spawnMob("sheep", 300, 60);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("ember_wand", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 100, 60);
        assertNotNull(world.playerShoot(mage, inv,
                sheep.x + sheep.def.size() / 2, sheep.y + sheep.def.size() / 2));
        for (int i = 0; i < 300 && sheep.burnTime <= 0; i++) {
            world.step(1.0 / 60, List.of(mage), p);
        }
        assertTrue(sheep.burnTime > 0, "the ember stuck");
        assertTrue((sheep.statusBits() & Mob.STATUS_BURNING) != 0);
        double afterHit = sheep.health;
        step(world, p, mage, 2.0);
        assertTrue(sheep.dead() || sheep.health < afterHit - 3,
                "burning ticks damage over time");

        Mob cow = world.spawnMob("cow", 500, 60);
        inv.clear();
        inv.add("frost_staff", 1);
        inv.select(0);
        mage.x = 340;
        assertNotNull(world.playerShoot(mage, inv,
                cow.x + cow.def.size() / 2, cow.y + cow.def.size() / 2));
        for (int i = 0; i < 300 && cow.chillTime <= 0; i++) {
            world.step(1.0 / 60, List.of(mage), p);
        }
        assertTrue(cow.chillTime > 0, "the frost stuck");
        assertTrue((cow.statusBits() & Mob.STATUS_CHILLED) != 0);
    }

    @Test
    void chilledMobsMoveSlower() {
        Level lvl = flatLevel();
        GameProfile p = profile();
        World world = new World(lvl);
        Mob quick = world.spawnMob("zombie", 400, 320);
        Mob cold = world.spawnMob("zombie", 400, 320);
        cold.chillTime = 30;
        PlayerState prey = new PlayerState(1, "prey", 250, 320);
        step(world, p, prey, 1.0);
        double quickMoved = Math.abs(400 - quick.x);
        double coldMoved = Math.abs(400 - cold.x);
        assertTrue(coldMoved < quickMoved * 0.7,
                "chill halves the chase: " + coldMoved + " vs " + quickMoved);
    }

    @Test
    void lightningChainsToTheNextMob() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false;
        Mob first = world.spawnMob("rabbit", 300, 60);
        Mob second = world.spawnMob("rabbit", 340, 60);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("storm_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 260, 60);
        assertNotNull(world.playerShoot(mage, inv,
                first.x + first.def.size() / 2, first.y + first.def.size() / 2));
        boolean chained = false;
        for (int i = 0; i < 300; i++) {
            world.step(1.0 / 60, List.of(mage), p);
            for (World.Impact im : world.pollImpacts()) {
                if ("chain".equals(im.key())) chained = true;
            }
            if (world.projectiles().isEmpty()) break;
        }
        assertTrue(chained, "the bolt arced to the second rabbit");
        assertTrue(first.dead(), "the struck rabbit fell");
        assertTrue(second.dead() || second.health < second.def.maxHealth(),
                "the chained rabbit took 60% damage");
    }

    // --- loot & wire -------------------------------------------------------------

    @Test
    void elementalMobsDropTheirEssences() {
        World world = new World(flatLevel());
        world.spawnMob("pyromancer", 200, 300);
        PlayerState hunter = new PlayerState(1, "hunter", 190, 300);
        for (int i = 0; i < 30 && !world.mobs().isEmpty(); i++) {
            world.playerAttack(hunter, 215, 315, 25);
        }
        assertTrue(world.mobs().isEmpty());
        assertTrue(world.items().stream().anyMatch(d -> "fire_essence".equals(d.key)),
                "pyromancers drop fire essence");
    }

    @Test
    void statusBitsRideTheWire() {
        Mob mob = new Mob(7, MobRegistry.standard().get("zombie"), 10, 20);
        mob.burnTime = 2;
        mob.poisonTime = 1;
        String json = Json.stringifyCompact(mob.toMap());
        EntityView view = EntityView.fromMap(Json.asObject(Json.parse(json)));
        assertEquals(7, view.id);
        assertTrue((view.status & Mob.STATUS_BURNING) != 0);
        assertTrue((view.status & Mob.STATUS_POISONED) != 0);
        assertEquals(0, view.status & Mob.STATUS_CHILLED);

        // Clean mobs omit the field entirely (wire stays lean), parsed as 0.
        Mob clean = new Mob(8, MobRegistry.standard().get("zombie"), 10, 20);
        assertFalse(Json.stringifyCompact(clean.toMap()).contains("\"e\""));
    }
}
