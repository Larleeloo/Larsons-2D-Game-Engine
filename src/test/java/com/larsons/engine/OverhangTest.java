package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PlayerPhysics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Columns with gaps in them — {@code HEIGHT_PLAN.md} Job O.
 *
 * <p>Every job before this one assumed a column is solid from the ground up.
 * That assumption bought a great deal: a single surface per cell, a movement
 * rule that needs no head-room, and a depth order Appendix C could <em>prove</em>
 * correct from cell depth alone. This is where it is given up, and each of
 * those three has to be paid for separately.
 */
@Timeout(60)
class OverhangTest {

    private static final int TILE = 32;
    private static final int CANVAS = 320;
    private static final Color ACTOR = new Color(255, 0, 255);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void unskinned() {
        Skins.install(List.of());
    }

    // --- O1: which surface holds you up ------------------------------------------

    /**
     * Under a bridge the ground is the ground; on it the ground is the deck.
     * One cell, two surfaces, and which one applies is decided by where the
     * body already is.
     */
    @Test
    void aCellUnderABridgeHasTwoSurfacesAndYourHeightPicksOne() {
        Level lvl = bridged();
        assertEquals(1, lvl.supportUnder(10, 10, 0),
                "standing on the ground, the ground is the floor");
        assertEquals(5, lvl.supportUnder(10, 10, 4),
                "standing on the deck, the ground is the deck");

        // And the physics agrees, from either height.
        double onFloor = PlayerPhysics.groundZ(lvl, 10.5 * TILE, 10.5 * TILE, 24, 0);
        assertEquals(0, onFloor, 1e-6, "a body on the floor stays on the floor");
        double onDeck = PlayerPhysics.groundZ(lvl, 10.5 * TILE, 10.5 * TILE, 24,
                lvl.surfaceZ(5));
        assertEquals(lvl.surfaceZ(5), onDeck, 1e-6, "a body on the deck stays on it");
    }

    /**
     * {@link Level#stackHeight} still counts from the ground, so it reports the
     * run under the bridge and not the bridge — which is what keeps every
     * heightfield caller honest rather than quietly wrong.
     */
    @Test
    void heightStillCountsFromTheGroundAndIgnoresWhatFloatsAboveIt() {
        Level lvl = bridged();
        assertEquals(1, lvl.stackHeight(10, 10),
                "the contiguous run from the floor is one block deep");
        assertEquals(4, lvl.topSolidLayer(10, 10),
                "and the deck is still the highest thing that would stop you");
    }

    // --- O2: head-room ------------------------------------------------------------

    /** A body walks under a bridge, and cannot walk into a gap it does not fit. */
    @Test
    void aBodyWalksUnderABridgeAndNotIntoAGapItCannotFit() {
        Level lvl = bridged();
        assertTrue(lvl.headRoom(10, 10, 1, 1),
                "there is a storey of clear air under the deck");
        assertFalse(PlayerPhysics.barrierAt(lvl, 10, 10, 0, 1),
                "so a body on the ground may walk under it");

        // Drop the deck to the first layer above the floor: no room left.
        Level tight = floored();
        int stone = tight.blocks.get("stone").id();
        for (int r = 0; r < tight.height; r++) tight.setTile(10, r, 1, stone);
        assertFalse(tight.headRoom(10, 10, 1, 1), "nothing fits in a filled layer");
        assertTrue(PlayerPhysics.barrierAt(tight, 10, 10, 0, 1),
                "and a body is stopped by it, which is what a wall is");
    }

    // --- O3: seeing the body under the roof ---------------------------------------

    /**
     * A roof between the camera and the player is found and drawn see-through.
     * Without it a player who walks indoors is a player who has vanished, which
     * is the reason overhangs are a job of their own rather than a flag.
     */
    @Test
    void aRoofBetweenTheCameraAndThePlayerIsCutAway() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = roofed(format);
            Camera cam = camera(lvl);
            Set<Long> hidden = TerrainPainter.cutaway(cam, lvl,
                    10.5 * TILE, 10.5 * TILE, 0);
            assertFalse(hidden.isEmpty(),
                    format + ": something stands between the camera and the player");

            // The player is visible through it.
            int seen = actorPixels(lvl, cam, hidden);
            int buried = actorPixels(lvl, cam, Set.of());
            assertTrue(seen > buried, format
                    + ": the cutaway shows more of them than the roof does — "
                    + seen + " vs " + buried);
        }
    }

    /** With nothing overhead, nothing is cut away. */
    @Test
    void anOpenSkyCutsNothingAway() {
        Level lvl = floored();
        Camera cam = camera(lvl);
        assertTrue(TerrainPainter.cutaway(cam, lvl, 10.5 * TILE, 10.5 * TILE, 0).isEmpty(),
                "an open field hides nobody");
    }

    // --- O4: the depth order a gap breaks -----------------------------------------

    /**
     * The configuration Appendix C's proof does not reach: an actor on the
     * ground with a bridge deck over them, in a cell whose ground is drawn
     * before the actor and whose deck must be drawn after.
     *
     * <p>Keyed on the column's own top — the obvious reading — the deck and the
     * ground it spans are one entry, so the actor either covers the whole
     * bridge or is covered by all of it. Keyed on what each run <em>stands
     * on</em>, the ground sorts below the actor and the deck above, and both
     * are right at once.
     */
    @Test
    void anActorUnderABridgeIsBehindTheDeckAndInFrontOfTheGround() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = bridged(format);
            Camera cam = camera(lvl);
            // Standing on the ground under the deck, the deck covers them.
            int under = actorPixels(lvl, cam, Set.of(), 10.5 * TILE, 10.5 * TILE, 0);
            // Standing on the deck, nothing covers them at all.
            int above = actorPixels(lvl, cam, Set.of(), 10.5 * TILE, 10.5 * TILE,
                    lvl.surfaceZ(5));
            assertTrue(above > under, format
                    + ": on the deck they are more visible than under it — "
                    + above + " vs " + under);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static Level floored() {
        return floored(LevelFormat.TOP_DOWN);
    }

    private static Level floored(LevelFormat format) {
        Level lvl = Level.empty("overhang", 20, 20, TILE);
        lvl.setFormat(format);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        GameProfile settings = new GameProfile("overhang-test");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    /** A deck at layer 4, with clear air between it and the floor. */
    private static Level bridged() {
        return bridged(LevelFormat.TOP_DOWN);
    }

    private static Level bridged(LevelFormat format) {
        Level lvl = floored(format);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 6; c <= 14; c++) {
            for (int r = 6; r <= 14; r++) lvl.setTile(c, r, 4, stone);
        }
        return lvl;
    }

    /** A roof low enough to be between the camera and someone under it. */
    private static Level roofed(LevelFormat format) {
        Level lvl = floored(format);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 4; c <= 16; c++) {
            for (int r = 4; r <= 16; r++) lvl.setTile(c, r, 2, stone);
        }
        return lvl;
    }

    private static Camera camera(Level lvl) {
        Camera cam = new Camera(lvl.perspective, CANVAS, CANVAS);
        cam.tileSize = lvl.tileSize;
        cam.centerOn(10.5 * TILE, 10.5 * TILE);
        return cam;
    }

    private static int actorPixels(Level lvl, Camera cam, Set<Long> cut) {
        return actorPixels(lvl, cam, cut, 10.5 * TILE, 10.5 * TILE, 0);
    }

    /** How much of an actor-sized marker survives a frame of this terrain. */
    private static int actorPixels(Level lvl, Camera cam, Set<Long> cut,
                                   double footX, double footY, double z) {
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Java2DTarget target = Java2DTarget.unsized(g);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam,
                new int[]{0, 0, lvl.width - 1, lvl.height - 1}, 0.5, pass, null, null,
                null, cut);
        int lift = (int) Math.round(z * cam.zoom * cam.liftScale());
        int[] feet = new int[2];
        cam.worldToScreen(footX, footY, feet);
        pass.at(TerrainPainter.standingDepth(cam, TILE, footX, footY),
                TerrainPainter.standingLayer(lvl, z),
                cam.worldToScreenY(footX, footY),
                () -> target.fillRect(feet[0] - 12, feet[1] - lift - 24, 24, 24, ACTOR));
        pass.flush();
        g.dispose();
        int seen = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (canvas.getRGB(x, y) == ACTOR.getRGB()) seen++;
            }
        }
        return seen;
    }
}
