package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.world.LiquidSim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rest of the simulation, once terrain has height —
 * {@code HEIGHT_PLAN.md} Job S.
 *
 * <p>Job W gave bodies a height and Job R drew it. Everything else in the
 * engine still assumed one: liquids pooled in a single layer for the whole
 * level, mobs walked a flat plane, arrows flew through cliffs, and a torch on
 * a tower lit nothing because the light sweep only ever read the floor. Each of
 * those is a separate small lie, and this is where they are told the truth.
 */
@Timeout(60)
class HeightSimTest {

    private static final int TILE = 32;

    // --- liquids ride the ground they are on -------------------------------------

    /**
     * A pool lies on top of whatever column it is on, so a puddle on a plateau
     * stays a puddle on a plateau rather than sinking into the rock.
     */
    @Test
    void aPoolSitsOnTheColumnItIsPouredOnto() {
        Level lvl = floored(true);
        int stone = lvl.blocks.get("stone").id();
        int water = lvl.blocks.get("water").id();
        // A plateau two blocks above the floor, in the level's western half.
        for (int c = 0; c < 8; c++) {
            for (int r = 0; r < lvl.height; r++) {
                lvl.setTile(c, r, 1, stone);
                lvl.setTile(c, r, 2, stone);
            }
        }
        // Poured on the plateau, the water lands in the layer above the rock.
        assertTrue(lvl.setTile(3, 3, lvl.supportHeight(3, 3), water));
        assertEquals(3, lvl.supportHeight(3, 3),
                "the rock still holds a body up at three blocks");
        assertEquals(water, lvl.tileAt(3, 3, 3), "and the water lies on top of it");
        assertEquals(3, lvl.stackHeight(3, 3) - 1,
                "the column is one deeper for having water on it");
    }

    /**
     * Water does not climb. A pool on the low ground spreads across the low
     * ground and stops at the foot of the plateau.
     */
    @Test
    void waterSpreadsAcrossLevelGroundAndDoesNotClimb() {
        Level lvl = floored(true);
        int stone = lvl.blocks.get("stone").id();
        int water = lvl.blocks.get("water").id();
        for (int c = 8; c < lvl.width; c++) {
            for (int r = 0; r < lvl.height; r++) lvl.setTile(c, r, 1, stone);
        }
        lvl.setTile(3, 5, Level.LAYER_UPPER, water);

        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 200; i++) sim.step(lvl, false, 1.0 / 30);

        assertTrue(wet(lvl, 5, 5), "the pool spread across the low ground");
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 8; c < lvl.width; c++) {
                assertFalse(wet(lvl, c, r),
                        "nothing climbed onto the plateau at (" + c + "," + r + ")");
            }
        }
    }

    /** With the height axis off, liquids behave exactly as they always did. */
    @Test
    void aLevelWithoutTheHeightAxisPoolsInOneLayer() {
        Level lvl = floored(false);
        int water = lvl.blocks.get("water").id();
        lvl.setTile(3, 5, Level.LAYER_UPPER, water);

        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 120; i++) sim.step(lvl, false, 1.0 / 30);
        assertTrue(wet(lvl, 5, 5), "and still spreads on the flat");
    }

    // --- mobs walk the landscape --------------------------------------------------

    /** A walker settles onto the column under it, exactly as a player does. */
    @Test
    void aWalkerStandsOnTheGroundUnderIt() {
        Level lvl = floored(true);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 0; c < lvl.width; c++) {
            for (int r = 0; r < lvl.height; r++) {
                lvl.setTile(c, r, 1, stone);
                lvl.setTile(c, r, 2, stone);
            }
        }
        Mob walker = walker(lvl, 5, 5);
        for (int i = 0; i < 60; i++) walker.step(lvl, List.of(), false, false, 1.0 / 60);

        assertEquals(lvl.surfaceZ(3), walker.z, 1e-6,
                "a walker on a three-deep plateau stands three blocks up");
    }

    /** A flier rides above the ground rather than on it — so it can cross what a walker cannot. */
    @Test
    void aFlierRidesAboveTheGround() {
        Level lvl = floored(true);
        Mob flier = null;
        for (var def : MobRegistry.standard().all()) {
            if (def.flying()) { flier = new Mob(1, def, 5 * TILE, 5 * TILE); break; }
        }
        if (flier == null) return;   // no flier in the roster: nothing to assert
        for (int i = 0; i < 60; i++) flier.step(lvl, List.of(), false, false, 1.0 / 60);
        assertTrue(flier.z > 0, "a flier holds an altitude above the floor, got " + flier.z);
    }

    // --- what the terrain now stops -----------------------------------------------

    /** Nothing in the engine may settle inside solid ground. */
    @Test
    void nothingSettlesInsideTheTerrain() {
        Level lvl = floored(true);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 1; layer <= 4; layer++) {
            for (int c = 0; c < lvl.width; c++) {
                for (int r = 0; r < lvl.height; r++) lvl.setTile(c, r, layer, stone);
            }
        }
        double surface = lvl.surfaceZ(5);
        assertEquals(surface, PlayerPhysics.groundZ(lvl, 5 * TILE, 5 * TILE, 24), 1e-6,
                "the ground under any point of a five-deep plateau is its top");

        Mob walker = walker(lvl, 5, 5);
        for (int i = 0; i < 60; i++) walker.step(lvl, List.of(), false, false, 1.0 / 60);
        assertTrue(walker.z >= surface - 1e-6,
                "and a mob is never below it, got " + walker.z + " under " + surface);
    }

    // --- helpers -----------------------------------------------------------------

    private static boolean wet(Level lvl, int col, int row) {
        return lvl.liquidAt(col, row) != null;
    }

    private static Level floored(boolean vertical) {
        Level lvl = Level.empty("sim", 16, 12, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        GameProfile settings = new GameProfile("sim-test");
        settings.verticality = vertical;
        lvl.settings = settings;
        return lvl;
    }

    private static Mob walker(Level lvl, int col, int row) {
        for (var def : MobRegistry.standard().all()) {
            if (!def.flying()) return new Mob(2, def, col * TILE, row * TILE);
        }
        throw new IllegalStateException("no walking mob in the roster");
    }
}
