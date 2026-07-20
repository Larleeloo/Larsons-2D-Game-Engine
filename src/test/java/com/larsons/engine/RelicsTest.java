package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.crafting.Recipe;
import com.larsons.engine.crafting.RecipeRegistry;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the expanded magical item & relic catalog: passive
 * accessories that reshape movement (speed, slow fall, flight, magnetism,
 * melee power), [F]-activated relics (Nova Crystal, Tremor Totem), the
 * Phoenix Feather death-cheat, terrain-shattering bombs and harvest orbs,
 * the Warp Staff's owner-teleport, and salvo weapons — all resolved by the
 * shared World/PlayerPhysics simulation, so they behave identically offline
 * and on the authoritative multiplayer server.
 */
class RelicsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("Relic Test");
        p.audioEnabled = false;
        return p;
    }

    /** A flat registry-mode level: floor across the bottom, open sky above. */
    private static Level flatLevel(int width, int height) {
        Level lvl = Level.empty("Flat", width, height, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles[lvl.height - 1][c] = dirt;
        }
        return lvl;
    }

    private static List<World.Impact> stepUntilNoProjectiles(World world, GameProfile p,
                                                             PlayerState player) {
        List<World.Impact> impacts = new ArrayList<>();
        for (int i = 0; i < 1200 && !world.projectiles().isEmpty(); i++) {
            world.step(1.0 / 60, List.of(player), p);
            impacts.addAll(world.pollImpacts());
        }
        assertTrue(world.projectiles().isEmpty(), "projectiles resolve, they don't linger");
        return impacts;
    }

    // --- passive relics ----------------------------------------------------------

    @Test
    void passivesFlowFromInventoryToPlayerState() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        PlayerState s = new PlayerState(1, "p", 0, 0);
        inv.applyPassivesTo(s, true);
        assertEquals(1.0, s.speedFactor, 0.001);
        assertFalse(s.slowFall);
        assertFalse(s.canFly);
        assertEquals(0, s.pickupBonus, 0.001);
        assertEquals(0, s.meleeBonus, 0.001);

        inv.add("hermes_boots", 1);
        inv.add("gravity_amulet", 1);
        inv.add("aether_wings", 1);
        inv.add("magnet_charm", 1);
        inv.add("power_gauntlet", 1);
        inv.add("feather_charm", 1);
        inv.applyPassivesTo(s, true);
        assertTrue(s.speedFactor > 1.2, "Hermes Boots speed things up");
        assertTrue(s.slowFall);
        assertTrue(s.canFly);
        assertTrue(s.pickupBonus > 0);
        assertTrue(s.meleeBonus > 0);
        assertEquals(1, s.bonusAirJumps);

        // The items toggle turns every passive off (server honours it too).
        inv.applyPassivesTo(s, false);
        assertEquals(1.0, s.speedFactor, 0.001);
        assertFalse(s.canFly);
        assertEquals(0, s.bonusAirJumps);
    }

    @Test
    void hermesBootsOutrunBareFeet() {
        Level lvl = flatLevel(80, 8);
        GameProfile p = profile();
        PlayerInput right = new PlayerInput(false, true, false, false, 1);

        PlayerState walker = new PlayerState(1, "walker", 64, 160);
        PlayerState runner = new PlayerState(2, "runner", 64, 160);
        runner.speedFactor = 1.35;
        for (int i = 0; i < 120; i++) {
            PlayerPhysics.step(walker, right, lvl, p, p.perspective, 1.0 / 60);
            PlayerPhysics.step(runner, right, lvl, p, p.perspective, 1.0 / 60);
        }
        assertTrue(runner.x > walker.x + 50,
                "boots cover more ground: " + runner.x + " vs " + walker.x);
    }

    @Test
    void gravityAmuletDriftsAndAetherWingsClimb() {
        Level lvl = flatLevel(40, 30);
        GameProfile p = profile();
        PlayerInput idle = new PlayerInput(false, false, false, false, 1);

        PlayerState floater = new PlayerState(1, "floater", 200, 100);
        floater.slowFall = true;
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(floater, idle, lvl, p, p.perspective, 1.0 / 60);
        }
        assertTrue(floater.vy <= PlayerPhysics.SLOW_FALL_MAX + 0.001,
                "slow fall caps terminal velocity, got " + floater.vy);

        PlayerState flier = new PlayerState(2, "flier", 200, 700);
        flier.canFly = true;
        PlayerInput up = new PlayerInput(false, false, true, false, 2);
        for (int i = 0; i < 180; i++) {
            PlayerPhysics.step(flier, up, lvl, p, p.perspective, 1.0 / 60);
        }
        assertTrue(flier.y < 450, "holding up flies the player upward, y=" + flier.y);
    }

    @Test
    void magnetCharmVacuumsFromFarther() {
        Level lvl = flatLevel(40, 8);
        GameProfile p = profile();
        p.gravityEnabled = false;
        World world = new World(lvl);
        List<String> picked = new ArrayList<>();
        world.setPickupListener((player, key, count) -> picked.add(key));

        PlayerState player = new PlayerState(1, "p", 100, 100);
        world.spawnItem("apple", 1, 200, 100).pickupDelay = 0; // 100 px away
        for (int i = 0; i < 30; i++) world.step(1.0 / 60, List.of(player), p);
        assertTrue(picked.isEmpty(), "100 px is out of bare-handed reach");

        player.pickupBonus = 120; // the Magnet Charm's reach
        for (int i = 0; i < 30; i++) world.step(1.0 / 60, List.of(player), p);
        assertEquals(List.of("apple"), picked, "the charm pulls it in");
    }

    @Test
    void phoenixFeatherCheatsDeathOnce() {
        Level lvl = flatLevel(40, 8);
        GameProfile p = profile();
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("phoenix_feather", 1);
        world.setItemConsumer((player, key) -> inv.remove(key, 1) >= 1);

        PlayerState hero = new PlayerState(1, "hero", 300, 100);
        hero.health = -1; // something terrible happened
        world.step(1.0 / 60, List.of(hero), p);
        assertEquals(PlayerState.MAX_HEALTH * 0.5, hero.health, 0.001,
                "the feather revives at half health");
        assertEquals(300, hero.x, 32, "revived in place, not at spawn");
        assertEquals(0, world.pollDeaths(), "a revive is not a death");
        assertEquals(0, inv.totalOf("phoenix_feather"), "the feather burned up");
        assertTrue(world.pollImpacts().stream().anyMatch(im -> "revive".equals(im.key())));

        // No feather left: the next death is a real one.
        hero.health = -1;
        world.step(1.0 / 60, List.of(hero), p);
        assertEquals(1, world.pollDeaths());
        assertEquals(PlayerState.MAX_HEALTH, hero.health, 0.001);
    }

    // --- active relics -----------------------------------------------------------

    @Test
    void novaCrystalDetonatesAroundThePlayer() {
        Level lvl = flatLevel(40, 8);
        GameProfile p = profile();
        World world = new World(lvl);
        Mob near = world.spawnMob("rabbit", 340, 100);
        Mob far = world.spawnMob("rabbit", 900, 100);
        PlayerState caster = new PlayerState(1, "caster", 320, 100);

        assertTrue(world.useRelic(caster, "nova_crystal", p));
        assertTrue(caster.mana < PlayerState.MAX_MANA, "the nova costs mana");
        assertTrue(near.dead(), "the nearby rabbit was caught in the blast");
        assertFalse(far.dead(), "the distant one was not");
        assertTrue(world.pollImpacts().stream().anyMatch(im -> "nova".equals(im.key())));

        caster.mana = 5;
        assertFalse(world.useRelic(caster, "nova_crystal", p), "no mana, no nova");
        assertNull(World.relicManaCost("iron_sword"), "swords are not relics");
    }

    @Test
    void tremorTotemShattersTheGroundIntoDrops() {
        Level lvl = flatLevel(40, 8);
        GameProfile p = profile();
        World world = new World(lvl);
        PlayerState caster = new PlayerState(1, "caster", 320, 200);

        assertTrue(world.useRelic(caster, "tremor_totem", p));
        assertTrue(world.pollBlockChanges().size() >= 2,
                "the shattered tiles broadcast as authoritative block changes");
        assertFalse(world.items().isEmpty(), "shattered blocks drop their items");
        assertTrue(world.pollImpacts().stream().anyMatch(im -> "tremor".equals(im.key())));

        // World protection: with block editing off the totem refuses.
        GameProfile noEdit = profile();
        noEdit.blockEditingEnabled = false;
        PlayerState blocked = new PlayerState(2, "blocked", 640, 200);
        assertFalse(new World(flatLevel(40, 8)).useRelic(blocked, "tremor_totem", noEdit));
    }

    // --- terrain-shattering & warp projectiles ----------------------------------

    @Test
    void bombsCraterTerrainAndHonourTheEditingToggle() {
        Level lvl = flatLevel(60, 10);
        GameProfile p = profile();
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("bomb", 2);
        inv.select(0);
        PlayerState bomber = new PlayerState(1, "bomber", 100, 200);

        int floorRow = lvl.height - 1;
        assertNotNull(world.playerShoot(bomber, inv, 300, floorRow * 32 + 16));
        List<World.Impact> impacts = stepUntilNoProjectiles(world, p, bomber);
        assertTrue(impacts.stream().anyMatch(World.Impact::explosion));
        assertFalse(world.pollBlockChanges().isEmpty(), "the blast cratered the floor");
        boolean holed = false;
        for (int c = 0; c < lvl.width; c++) {
            if (lvl.tiles[floorRow][c] == 0) holed = true;
        }
        assertTrue(holed, "floor tiles were destroyed");

        // Same bomb with editing off: bang, but the world stays intact.
        Level safe = flatLevel(60, 10);
        GameProfile noEdit = profile();
        noEdit.blockEditingEnabled = false;
        World safeWorld = new World(safe);
        Inventory inv2 = new Inventory(safeWorld.itemTypes);
        inv2.add("bomb", 1);
        inv2.select(0);
        assertNotNull(safeWorld.playerShoot(bomber, inv2, 300, floorRow * 32 + 16));
        stepUntilNoProjectiles(safeWorld, noEdit, bomber);
        for (int c = 0; c < safe.width; c++) {
            assertTrue(safe.tiles[floorRow][c] != 0, "protected floor survives bombs");
        }
    }

    @Test
    void harvestOrbMinesWithoutHurtingAnyone() {
        Level lvl = flatLevel(60, 10);
        GameProfile p = profile();
        World world = new World(lvl);
        Mob sheep = world.spawnMob("sheep", 280, 240);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("harvest_staff", 1);
        inv.select(0);
        PlayerState farmer = new PlayerState(1, "farmer", 100, 200);

        int floorRow = lvl.height - 1;
        assertNotNull(world.playerShoot(farmer, inv, 300, floorRow * 32 + 16));
        stepUntilNoProjectiles(world, p, farmer);
        assertFalse(world.items().isEmpty(), "the orb harvested the tiles into drops");
        assertEquals(sheep.def.maxHealth(), sheep.health, 0.001,
                "the harvest orb harms nothing");
    }

    @Test
    void warpStaffTeleportsTheCasterToTheImpact() {
        Level lvl = flatLevel(60, 10);
        GameProfile p = profile();
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("warp_staff", 1);
        inv.select(0);
        PlayerState caster = new PlayerState(1, "caster", 100, 240);

        int floorRow = lvl.height - 1;
        assertNotNull(world.playerShoot(caster, inv, 900, floorRow * 32 - 8));
        List<World.Impact> impacts = stepUntilNoProjectiles(world, p, caster);
        assertTrue(impacts.stream().anyMatch(im -> "warp".equals(im.key())));
        assertTrue(caster.x > 500, "the caster followed the bolt, x=" + caster.x);
    }

    // --- salvo weapons -----------------------------------------------------------

    @Test
    void meteorStaffCallsASalvoFromTheSky() {
        Level lvl = flatLevel(60, 30);
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("meteor_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 100, 800);

        assertNotNull(world.playerShoot(mage, inv, 700, 850));
        assertEquals(3, world.projectiles().size(), "three meteors per cast");
        for (Projectile meteor : world.projectiles()) {
            assertEquals("meteor", meteor.def.key());
            assertTrue(meteor.y < 850, "meteors start above the aim point");
            assertTrue(meteor.vy > 0, "and rain downward");
        }
        assertTrue(mage.mana < PlayerState.MAX_MANA, "the salvo costs mana");
    }

    @Test
    void scatterBowFansThreeArrowsForOne() {
        World world = new World(flatLevel(60, 10));
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("scatter_bow", 1);
        inv.add("arrow", 1);
        inv.select(0);
        PlayerState archer = new PlayerState(1, "archer", 100, 200);

        assertNotNull(world.playerShoot(archer, inv, 600, 200));
        assertEquals(3, world.projectiles().size(), "three arrows per draw");
        assertEquals(0, inv.totalOf("arrow"), "for a single arrow spent");
        assertNull(world.playerShoot(archer, inv, 600, 200), "no ammo, no fan");
    }

    // --- catalog sanity ----------------------------------------------------------

    @Test
    void everyNewDefinitionRendersAndResolves() {
        ItemRegistry items = ItemRegistry.standard();
        ProjectileRegistry shots = ProjectileRegistry.standard();
        for (ItemDef def : items.all()) {
            assertNotNull(EntitySprites.item(def, 32));
            if (def.projectile() != null) {
                assertNotNull(shots.get(def.projectile()),
                        def.key() + " fires a registered projectile");
            }
        }
        for (ProjectileDef def : shots.all()) {
            assertNotNull(EntitySprites.projectile(def, 16));
            if (def.dropItem() != null) {
                assertNotNull(items.get(def.dropItem()),
                        def.key() + " lands as a registered item");
            }
        }
        for (VehicleDef def : VehicleRegistry.standard().all()) {
            assertNotNull(EntitySprites.vehicle(def, 48));
        }
        // Elemental staves declare their schools.
        assertEquals(ProjectileDef.Element.FIRE, shots.get("ember_bolt").element());
        assertEquals(ProjectileDef.Element.ICE, shots.get("frost_orb").element());
        assertEquals(ProjectileDef.Element.LIGHTNING, shots.get("lightning_bolt").element());
        assertEquals(ProjectileDef.Element.POISON, shots.get("poison_glob").element());
        assertTrue(shots.get("mega_bomb").breakRadius() > shots.get("bomb").breakRadius());
    }

    @Test
    void newRecipesCraftRegisteredItemsFromRegisteredItems() {
        ItemRegistry items = ItemRegistry.standard();
        for (Recipe recipe : RecipeRegistry.standard().all()) {
            assertNotNull(items.get(recipe.output()),
                    "recipe output must exist: " + recipe.output());
            for (Recipe.Ingredient ing : recipe.inputs()) {
                assertNotNull(items.get(ing.key()),
                        "recipe input must exist: " + ing.key()
                                + " (for " + recipe.output() + ")");
            }
        }
    }
}
