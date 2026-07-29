package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.LiquidSim;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch of gameplay fixes: smooth generated terrain at any size, the
 * water-exit jump, the bottom-of-map jump fix, always-on double jump, tool
 * durability, container inventories saved with levels, indestructible water,
 * falling sand, transparent-but-solid glass, and maze generation.
 */
class MechanicsFixesTest {

    private static final double DT = 1.0 / 60.0;

    private static GameProfile profile() {
        GameProfile p = new GameProfile("mechanics-test");
        p.gravityEnabled = true;
        p.tileSize = 32;
        p.playerSize = 32;
        return p;
    }

    // --- terrain smoothness --------------------------------------------------------

    @Test
    void generatedTerrainStaysSmoothOnTallMaps() {
        // Before the amplitude cap, hill height scaled with map height and
        // tall maps generated unclimbable vertical mountains. The chunked
        // generator exposes the raw heightmap (no room/shaft carving), so the
        // sky-to-terrain line — water counts as terrain, pools are flat —
        // measures the noise directly: steps stay jumpable and the total
        // relief stays amplitude-capped no matter the map height.
        Level lvl = LevelGenerator.generateChunked("smooth", 256, 512, 32, 1234L);
        int[] surface = new int[lvl.width];
        for (int c = 0; c < lvl.width; c++) {
            int r = 0;
            while (r < lvl.height && lvl.tileAt(c, r) == 0) r++;
            surface[c] = r;
        }
        int maxStep = 0, min = Integer.MAX_VALUE, max = 0;
        for (int c = 2; c < lvl.width - 2; c++) {
            maxStep = Math.max(maxStep, Math.abs(surface[c] - surface[c - 1]));
            min = Math.min(min, surface[c]);
            max = Math.max(max, surface[c]);
        }
        assertTrue(maxStep <= 8,
                "adjacent surface columns should differ by a few tiles, was " + maxStep);
        assertTrue(max - min <= 60,
                "total surface relief should be amplitude-capped, was " + (max - min));
    }

    // --- swimming ------------------------------------------------------------------

    @Test
    void playerCanJumpOutOfWaterAtTheSurface() {
        // A pool with a one-tile lip on the right; the old capped swim rise
        // speed couldn't clear it and the player bobbed forever.
        Level level = LevelLoader.parse("""
                {
                  "tileSize": 32,
                  "tileset": "registry",
                  "tiles": [
                    [0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0],
                    [0,4,4,4,0,0,0,0],
                    [1,4,4,4,1,1,1,1],
                    [1,1,1,1,1,1,1,1]
                  ]
                }
                """);
        GameProfile p = profile();
        // Swimming in the middle of the pool, holding up.
        PlayerState s = new PlayerState(1, "t", 64, 72);
        PlayerInput up = new PlayerInput(false, false, true, false, 1);
        double minY = s.y;
        for (int i = 0; i < 240; i++) {
            PlayerPhysics.step(s, up, level, p, Perspective.SIDE_SCROLL, DT);
            minY = Math.min(minY, s.y);
        }
        // The exit jump must lift the player's feet above the pool lip
        // (lip top y = 64; feet above it means y + 32 <= 64+eps at the apex).
        assertTrue(minY < 40,
                "water-exit jump should clear a one-tile lip, apex y was " + minY);
    }

    // --- bottom-of-map jumping -----------------------------------------------------

    @Test
    void playerCanJumpFromTheLevelBottomEdge() {
        // No floor at all: the player clamps to the level's bottom edge, which
        // used to fail the ground probe and lock out jumping forever.
        Level level = LevelLoader.parse("""
                {
                  "tileSize": 32,
                  "tiles": [
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0]
                  ]
                }
                """);
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 32, 0);
        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 200; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        double restY = s.y;
        PlayerInput jump = new PlayerInput(false, false, false, false, 1);
        jump.jump = true;
        PlayerPhysics.step(s, jump, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < 0, "the level's bottom edge should count as ground");
        assertTrue(s.y < restY, "the player should leave the bottom edge");
    }

    // --- double jump ---------------------------------------------------------------

    @Test
    void doubleJumpIsAlwaysActiveAndItemsAddMore() {
        Level level = LevelLoader.parse("""
                {
                  "tileSize": 32,
                  "tiles": [
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0],
                    [0,0,0,0],
                    [1,1,1,1]
                  ]
                }
                """);
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 32, 0);
        PlayerInput idleSettle = new PlayerInput();
        for (int i = 0; i < 200; i++) {
            PlayerPhysics.step(s, idleSettle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        PlayerInput held = new PlayerInput(false, false, false, false, 1);
        held.jump = true;
        PlayerPhysics.step(s, held, level, p, Perspective.SIDE_SCROLL, DT); // ground jump
        assertTrue(s.vy < 0);
        // Let the jump decay a little, then press again mid-air.
        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 30; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        double vyBefore = s.vy;
        PlayerInput airPress = new PlayerInput(false, false, false, false, 2);
        airPress.jump = true;
        PlayerPhysics.step(s, airPress, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < vyBefore && s.vy < 0, "the double jump should re-launch mid-air");

        // A second air press without items does nothing…
        for (int i = 0; i < 10; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        double vyNoTriple = s.vy;
        PlayerPhysics.step(s, airPress, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy >= vyNoTriple - 30, "no triple jump without a special item");

        // …but a Feather Charm (bonusAirJumps = 1) unlocks the triple jump.
        s.bonusAirJumps = 1;
        PlayerPhysics.step(s, airPress, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < -300, "a special item should grant the third jump");
    }

    @Test
    void jumpItemsGrantAirJumpBonuses() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        assertEquals(0, inv.airJumpBonus());
        inv.add("feather_charm", 1);
        assertEquals(1, inv.airJumpBonus());
        inv.add("sky_totem", 1);
        assertEquals(3, inv.airJumpBonus());
        inv.add("wings_of_icarus", 1);
        assertTrue(inv.airJumpBonus() > 1000, "wings jump effectively forever");
    }

    // --- tool durability -----------------------------------------------------------

    @Test
    void toolsWearOutAndBreakCompletely() {
        ItemDef pick = ItemRegistry.standard().get("wooden_pickaxe");
        assertNotNull(pick);
        assertTrue(pick.maxDurability() > 0, "tools carry a durability budget");

        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("wooden_pickaxe", 1);
        inv.select(0);
        for (int i = 0; i < pick.maxDurability() - 1; i++) {
            assertFalse(inv.damageSelected(1), "should survive until the budget runs out");
        }
        assertTrue(inv.damageSelected(1), "the final point of wear breaks the tool");
        assertNull(inv.slot(0), "a broken tool is gone");
    }

    // --- containers ----------------------------------------------------------------

    @Test
    void containerContentsSaveAndLoadWithTheLevel() {
        Level lvl = Level.empty("boxes", 8, 8, 32);
        int chest = lvl.blocks.get("chest").id();
        lvl.setTile(3, 3, chest);
        List<ItemStack> box = lvl.openContainer(3, 3);
        box.add(new ItemStack("apple", 5));
        box.add(new ItemStack("iron_sword", 1));

        Level loaded = LevelLoader.parse(lvl.toJson());
        List<ItemStack> restored = loaded.containerAt(3, 3);
        assertNotNull(restored, "container contents should ride the level JSON");
        assertEquals(2, restored.size());
        assertEquals("apple", restored.get(0).key);
        assertEquals(5, restored.get(0).count);
    }

    @Test
    void minedChestSpillsItsContents() {
        Level lvl = Level.empty("spill", 8, 8, 32);
        lvl.setTile(3, 6, lvl.blocks.get("chest").id());
        lvl.openContainer(3, 6).add(new ItemStack("apple", 3));
        World world = new World(lvl);
        Block mined = world.mineBlock(3, 6, true);
        assertNotNull(mined);
        assertNull(lvl.containerAt(3, 6), "the container data goes with the block");
        assertTrue(world.items().stream().anyMatch(i -> "apple".equals(i.key)),
                "stored items should spill into the world");
    }

    // --- water ---------------------------------------------------------------------

    @Test
    void waterCannotBeMinedButCanBeBuiltOver() {
        Level lvl = Level.empty("pool", 8, 8, 32);
        int water = lvl.blocks.get("water").id();
        lvl.setTile(2, 2, water);
        World world = new World(lvl);
        assertNull(world.mineBlock(2, 2, true), "liquids are not minable");
        assertNull(world.continueMining(2, 2, null, true, 10.0));
        assertEquals(water, lvl.tileAt(2, 2), "the water is still there");
        assertTrue(world.placeBlock(2, 2, lvl.blocks.get("stone").id()),
                "placing a block over water displaces it");
    }

    // --- falling blocks --------------------------------------------------------------

    @Test
    void sandFallsUntilSupported() {
        Level lvl = Level.empty("sandbox", 6, 6, 32);
        int sand = lvl.blocks.get("sand").id();
        int stone = lvl.blocks.get("stone").id();
        for (int c = 0; c < 6; c++) lvl.setTile(c, 5, stone);
        lvl.setTile(2, 0, sand);
        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 40; i++) sim.step(lvl, true, 0.25);
        assertEquals(0, lvl.tileAt(2, 0), "the sand should have left its start cell");
        assertEquals(sand, lvl.tileAt(2, 4), "the sand should rest on the stone floor");
        assertTrue(BlockRegistry.standard().get("sand").falling());
        assertTrue(BlockRegistry.standard().get("gravel").falling());
        assertFalse(BlockRegistry.standard().get("stone").falling());
    }

    // --- glass ---------------------------------------------------------------------

    @Test
    void glassIsSolidAndTransparent() {
        Block glass = BlockRegistry.standard().get("glass");
        assertTrue(glass.solid(), "glass panes should block movement");
        assertTrue(glass.color().getAlpha() < 255, "glass should render translucent");
    }

    // --- mazes ---------------------------------------------------------------------

    @Test
    void generatedMazeIsFullyConnected() {
        Level maze = LevelGenerator.generateMaze("maze", 41, 41, 32, 77L,
                Perspective.TOP_DOWN);
        assertEquals(Perspective.TOP_DOWN, maze.perspective);
        // Flood-fill from the spawn: every carved room cell (odd, odd) must be
        // reachable, or the maze has sealed-off sections.
        boolean[][] seen = new boolean[maze.width][maze.height];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int sc = (int) (maze.spawnX / maze.tileSize), sr = (int) (maze.spawnY / maze.tileSize);
        seen[sc][sr] = true;
        queue.add(new int[]{sc, sr});
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int nc = cur[0] + d[0], nr = cur[1] + d[1];
                if (nc < 0 || nc >= maze.width || nr < 0 || nr >= maze.height) continue;
                if (seen[nc][nr] || maze.solidAt(nc, nr)) continue;
                seen[nc][nr] = true;
                queue.add(new int[]{nc, nr});
            }
        }
        for (int c = 1; c < maze.width; c += 2) {
            for (int r = 1; r < maze.height; r += 2) {
                if (!maze.solidAt(c, r)) {
                    assertTrue(seen[c][r], "room cell (" + c + "," + r + ") unreachable");
                }
            }
        }
        // The exit chest (gold key) exists somewhere. A plan-view maze stands
        // its chest on the floor, so it is in the stacked layer.
        boolean chest = false;
        int chestId = maze.blocks.get("chest").id();
        for (int c = 0; c < maze.width && !chest; c++) {
            for (int r = 0; r < maze.height && !chest; r++) {
                chest = maze.tileAt(c, r) == chestId || maze.upperAt(c, r) == chestId;
            }
        }
        assertTrue(chest, "mazes should place at least the exit chest");
    }

    // --- player sizing ---------------------------------------------------------------

    @Test
    void playerHitboxIsSlightlySmallerThanOneBlock() {
        GameProfile p = new GameProfile("sizing");
        p.tileSize = 48;
        p.playerSize = 90;
        p.normalize();
        assertTrue(p.playerSize < p.tileSize,
                "the player hitbox stays under one block so one-tile gaps fit");
        assertEquals(GameProfile.playerSizeFor(48), p.playerSize,
                "the hitbox locks to the player-tile fraction of the tile size");
        assertEquals(43, p.playerSize, "0.9 of a 48px tile, floored");
    }
}
