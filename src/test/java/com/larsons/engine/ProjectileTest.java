package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.PlayScene;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the projectile system ported from the Side-Scroller
 * engine: the data-driven projectile registry and its item links, shooting
 * (ammo consumption, weapon damage), flight physics (gravity arcs vs straight
 * magic), mob hits, terrain impacts with recoverable drops, explosions with
 * area damage, feature-toggle gating, and the inventory move/swap/merge/drop
 * primitives the new inventory UI and protocol are built on.
 */
class ProjectileTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("Projectile Test");
        p.audioEnabled = false;
        return p;
    }

    /** A flat registry-mode level: 40 wide, 8 tall, solid dirt floor. */
    private static Level flatLevel() {
        Level lvl = Level.empty("Flat", 40, 8, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles()[lvl.height - 1][c] = dirt;
        }
        lvl.spawnX = 64;
        lvl.spawnY = 128;
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

    // --- registry + item links ---------------------------------------------------

    @Test
    void standardProjectilesExistAndItemLinksResolve() {
        ProjectileRegistry shots = ProjectileRegistry.standard();
        assertNotNull(shots.get("arrow"));
        assertNotNull(shots.get("fireball"));
        assertTrue(shots.get("fireball").glows(), "fireballs feed the lighting pass");
        assertTrue(shots.get("fireball").explosionRadius() > 0);
        assertEquals("arrow", shots.get("arrow").dropItem(), "arrows are recoverable");
        assertNull(shots.get("bogus"));

        // Every shooting item references a real projectile, and real ammo.
        ItemRegistry items = ItemRegistry.standard();
        for (ItemDef def : items.all()) {
            if (def.projectile() != null) {
                assertNotNull(shots.get(def.projectile()),
                        def.key() + " fires a registered projectile");
                if (def.ammo() != null) {
                    assertNotNull(items.get(def.ammo()),
                            def.key() + " consumes a registered item");
                }
            }
        }
        assertEquals("arrow", items.get("wooden_bow").projectile());
        assertEquals("arrow", items.get("wooden_bow").ammo(), "bows consume arrows");
        assertNull(items.get("fire_staff").ammo(), "staves fire freely");
        assertEquals("rock", items.get("rock").ammo(), "throwables consume themselves");
    }

    // --- shooting ------------------------------------------------------------------

    @Test
    void shootingConsumesAmmoAndUsesTheWeaponsDamage() {
        World world = new World(flatLevel());
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("wooden_bow", 1);
        inv.add("arrow", 2);
        inv.select(0);
        PlayerState archer = new PlayerState(1, "archer", 64, 64);

        Projectile shot = world.playerShoot(archer, inv, 400, 64);
        assertNotNull(shot, "bow + arrows = a shot");
        assertEquals("arrow", shot.def.key());
        assertEquals(6.0, shot.damage, 0.001, "the bow's damage rides the arrow");
        assertEquals(1, inv.totalOf("arrow"), "one arrow spent");
        assertTrue(shot.vx > 0 && Math.abs(shot.vy) < 0.001, "aimed flat, flies flat");

        assertNotNull(world.playerShoot(archer, inv, 400, 64));
        assertEquals(0, inv.totalOf("arrow"));
        assertNull(world.playerShoot(archer, inv, 400, 64), "no ammo, no shot");
        assertEquals(2, world.projectiles().size());
    }

    @Test
    void throwablesConsumeThemselvesAndSwordsDontShoot() {
        World world = new World(flatLevel());
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("rock", 3);
        inv.select(0);
        PlayerState thrower = new PlayerState(1, "thrower", 64, 64);

        assertNotNull(world.playerShoot(thrower, inv, 200, 40));
        assertEquals(2, inv.totalOf("rock"), "throwing a rock spends the rock");

        inv.clear();
        inv.add("iron_sword", 1);
        inv.select(0);
        assertNull(world.playerShoot(thrower, inv, 200, 40),
                "melee weapons don't shoot — callers fall back to the swing");
    }

    // --- flight physics -------------------------------------------------------------

    @Test
    void arrowsArcUnderGravityAndLandAsRecoverableDrops() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("longbow", 1);
        inv.add("arrow", 1);
        inv.select(0);
        PlayerState archer = new PlayerState(1, "archer", 64, 64);

        Projectile shot = world.playerShoot(archer, inv, 500, 64);
        assertNotNull(shot);
        for (int i = 0; i < 10; i++) world.step(1.0 / 60, List.of(archer), p);
        assertTrue(shot.vy > 0, "gravity pulls the arrow into an arc");

        List<World.Impact> impacts = stepUntilNoProjectiles(world, p, archer);
        assertEquals(1, impacts.size());
        assertEquals("arrow", impacts.get(0).key());
        assertFalse(impacts.get(0).explosion());
        assertEquals(1, world.items().size(), "the arrow lands as a pickup");
        assertEquals("arrow", world.items().get(0).key);
    }

    @Test
    void magicFliesStraightAndLeavesNoDrop() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("arcane_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 64, 64);

        Projectile bolt = world.playerShoot(mage, inv, 500, 64);
        assertNotNull(bolt, "staves need no ammo");
        for (int i = 0; i < 20; i++) world.step(1.0 / 60, List.of(mage), p);
        assertEquals(0.0, bolt.vy, 0.001, "no gravity on magic");

        stepUntilNoProjectiles(world, p, mage);
        assertTrue(world.items().isEmpty(), "magic leaves nothing to recover");
    }

    // --- hitting things --------------------------------------------------------------

    @Test
    void projectilesHitMobsWithTheWeaponsDamage() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false; // keep the target at spawn height
        Mob sheep = world.spawnMob("sheep", 300, 50);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("arcane_staff", 1); // damage 9, flies straight
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 64, 50);

        assertNotNull(world.playerShoot(mage, inv,
                sheep.x + sheep.def.size() / 2, sheep.y + sheep.def.size() / 2));
        List<World.Impact> impacts = stepUntilNoProjectiles(world, p, mage);
        assertEquals(1, impacts.size(), "the bolt connected");
        assertEquals(sheep.def.maxHealth() - 9, sheep.health, 0.001,
                "the staff's damage landed");
    }

    @Test
    void fireballsExplodeWithAreaDamage() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.gravityEnabled = false; // keep the targets at spawn height
        // Two rabbits (8 hp) close together: one fireball takes both.
        Mob first = world.spawnMob("rabbit", 300, 60);
        Mob second = world.spawnMob("rabbit", 310, 60);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("fire_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 64, 60);

        assertNotNull(world.playerShoot(mage, inv,
                first.x + first.def.size() / 2, first.y + first.def.size() / 2));
        List<World.Impact> impacts = stepUntilNoProjectiles(world, p, mage);
        assertEquals(1, impacts.size());
        assertTrue(impacts.get(0).explosion(), "fireballs report an explosion");
        assertTrue(first.dead() && second.dead(), "area damage caught both");
        assertTrue(world.mobs().isEmpty());
        assertEquals(2, world.items().size(), "both dropped loot");
    }

    @Test
    void combatToggleMakesProjectilesDecorative() {
        World world = new World(flatLevel());
        GameProfile p = profile();
        p.combatEnabled = false;
        p.gravityEnabled = false;
        Mob sheep = world.spawnMob("sheep", 300, 50);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("arcane_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 64, 50);

        assertNotNull(world.playerShoot(mage, inv,
                sheep.x + sheep.def.size() / 2, sheep.y + sheep.def.size() / 2));
        stepUntilNoProjectiles(world, p, mage);
        assertEquals(sheep.def.maxHealth(), sheep.health, 0.001,
                "combat off: the bolt passes through");
    }

    @Test
    void skeletonsDropArrows() {
        World world = new World(flatLevel());
        world.spawnMob("skeleton", 100, 128);
        PlayerState hunter = new PlayerState(1, "hunter", 90, 128);
        for (int i = 0; i < 20 && !world.mobs().isEmpty(); i++) {
            world.playerAttack(hunter, 115, 140, 50);
        }
        assertTrue(world.mobs().isEmpty());
        assertEquals(1, world.items().size());
        assertEquals("arrow", world.items().get(0).key, "skeletons are the arrow economy");
        assertEquals(3, world.items().get(0).count);
    }

    // --- inventory primitives ----------------------------------------------------------

    @Test
    void inventoryMovesMergeAndSwapStacks() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("apple", 10);      // slot 0
        inv.add("iron_sword", 1);  // slot 1

        assertTrue(inv.move(0, 3), "move into an empty slot");
        assertNull(inv.slot(0));
        assertEquals(10, inv.slot(3).count);

        assertTrue(inv.move(1, 3), "different items swap");
        assertEquals("apple", inv.slot(1).key);
        assertEquals("iron_sword", inv.slot(3).key);

        inv.add("apple", 10);      // merges into slot 1 -> 16 (max), then slot 0 -> 4
        assertEquals(16, inv.slot(1).count);
        assertEquals(4, inv.slot(0).count);
        assertFalse(inv.move(0, 1), "merging into a full stack does nothing");
        assertTrue(inv.move(1, 0), "same items merge up to maxStack");
        assertEquals(16, inv.slot(0).count);
        assertEquals(4, inv.slot(1).count, "the leftovers stay behind");

        assertFalse(inv.move(0, 0), "no-op moves are refused");
        assertFalse(inv.move(5, 7), "empty sources are refused");
        assertFalse(inv.move(-1, 40), "bounds are checked");
    }

    @Test
    void inventoryRemoveAtTakesFromOneSlot() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("dirt", 20);
        assertEquals(5, inv.removeAt(0, 5), "partial takes");
        assertEquals(15, inv.slot(0).count);
        assertEquals(15, inv.removeAt(0, 99), "over-asking drains the slot");
        assertNull(inv.slot(0));
        assertEquals(0, inv.removeAt(0, 1), "empty slots yield nothing");
    }

    // --- sprites + the full scene path -------------------------------------------------

    @Test
    void projectileSpritesRenderForEveryRegistryEntry() {
        for (ProjectileDef def : ProjectileRegistry.standard().all()) {
            assertNotNull(EntitySprites.projectile(def, 16));
        }
        for (ItemDef def : ItemRegistry.standard().all()) {
            assertNotNull(EntitySprites.item(def, 32)); // includes the throwable icon
        }
    }

    /**
     * End-to-end through the real scene, headlessly: the player picks a fire
     * staff off the ground, a click fires a glowing fireball (rendered,
     * trailed, fed to the lighting pass at night), it explodes, and the
     * inventory panel opens and takes clicks (pick up a stack, drop it
     * outside) — the same code paths a live game runs.
     */
    @Test
    void playSceneShootsAndWorksTheInventoryHeadlessly(@TempDir Path dir) throws IOException {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile p = profile();
        p.lightingEnabled = true;
        p.nightMode = true; // darkness > 0: the lighting feed (incl. glows) runs
        ctx.setProfile(p);

        Level lvl = flatLevel();
        lvl.entities.add(new Level.EntitySpawn("item", "fire_staff", lvl.spawnX, lvl.spawnY));
        Path levelFile = dir.resolve("shoot_test.json");
        Files.writeString(levelFile, lvl.toJson());

        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("play", new PlayScene(ctx, levelFile.toString()));
        scenes.register("menu", new com.larsons.engine.demo.MainMenuScene(ctx));
        scenes.setScene("play");

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        Component src = new Canvas();
        Runnable tick = () -> {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        };

        // Let the staff settle and get picked up (0.4 s pickup delay).
        for (int i = 0; i < 90; i++) tick.run();

        // Fire toward the upper right, then let the fireball fly and explode.
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, 520, 120, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, 520, 120, 1, false));
        input.mouseReleased(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, 0L, 0, 520, 120, 1, false));
        for (int i = 0; i < 300; i++) tick.run();

        // Open the inventory, pick the staff stack up, drop it outside the panel.
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_I, 'i'));
        tick.run();
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, 138, 102, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, 138, 102, 1, false));
        input.mouseReleased(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, 0L, 0, 138, 102, 1, false));
        tick.run(); // slot 0 now rides the cursor
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, 620, 340, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, 620, 340, 1, false));
        input.mouseReleased(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, 0L, 0, 620, 340, 1, false));
        for (int i = 0; i < 10; i++) tick.run(); // dropped into the world
        g.dispose();

        assertEquals("PlayScene", scenes.current().name(), "the whole flow ran without errors");
    }
}
