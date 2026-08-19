package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SolidPainter;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.Viewpoint;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first- and third-person views: {@link EyeCamera}, {@link SolidPainter}
 * and the {@link Viewpoint} cycle they are chosen through.
 *
 * <p><b>What is worth pinning here, and what is not.</b> Whether the picture
 * <em>looks</em> right is not something a test can say, and this does not
 * pretend to: what it pins is the handful of properties that are exactly
 * checkable and that everything visible rests on.
 *
 * <ul>
 *   <li><b>The divide.</b> A perspective camera is a parallel one plus a
 *       division by depth, and that division is the whole feature. If twice as
 *       far is not half as big, nothing else about the view is worth
 *       looking at.</li>
 *   <li><b>The near plane.</b> Geometry behind the eye projects to a finite
 *       point on the <em>wrong</em> side of the screen, so it must be cut
 *       rather than dropped or trusted.</li>
 *   <li><b>Face exposure.</b> A buried face is never drawn. This is what makes
 *       the sweep affordable at all, and it is invisible when it breaks — the
 *       picture is identical and the frame is ten times the cost.</li>
 *   <li><b>The crosshair's march.</b> What you are looking at has to be the
 *       block you are looking at, including which of its faces, because that
 *       is the difference between placing a block against a wall and inside
 *       it.</li>
 * </ul>
 */
class SolidViewTest {

    private static final int TILE = 32;

    // --- the projection ---------------------------------------------------------

    /** A point straight down the view axis lands in the middle of the screen. */
    @Test
    void theViewAxisPassesThroughTheCentreOfTheScreen() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.place(100, 100, 40);
        for (double yaw = 0; yaw < Math.PI * 2; yaw += Math.PI / 4) {
            for (double pitch = -1.0; pitch <= 1.0; pitch += 0.5) {
                eye.look(yaw, pitch);
                double t = 250;
                double[] out = new double[3];
                assertTrue(eye.project(100 + eye.dirX() * t, 100 + eye.dirY() * t,
                        40 + eye.dirZ() * t, out), "in front of the eye");
                assertEquals(eye.centreX(), out[0], 1e-6, "screen x at yaw " + yaw);
                assertEquals(eye.centreY(), out[1], 1e-6, "screen y at pitch " + pitch);
                assertEquals(t, out[2], 1e-6, "depth is distance along the view axis");
            }
        }
    }

    /**
     * Twice as far away is half as big — the divide itself, which is the one
     * thing this camera does that {@code Camera} does not.
     */
    @Test
    void twiceAsFarIsHalfAsBig() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.place(0, 0, 0);
        eye.look(0, 0); // north is -y
        double[] near = new double[3], far = new double[3];
        assertTrue(eye.project(TILE, -100, 0, near));
        assertTrue(eye.project(TILE, -200, 0, far));
        double nearOffset = near[0] - eye.centreX();
        double farOffset = far[0] - eye.centreX();
        assertEquals(nearOffset / 2, farOffset, 1e-6, "half the offset at twice the depth");
        assertEquals(2, eye.scaleAt(100) / eye.scaleAt(200), 1e-9, "half the scale");
    }

    /** Yaw is clockwise from north, which is {@code -y} — the same as {@code Camera}. */
    @Test
    void yawIsClockwiseFromNorthLikeTheFlatCamera() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.look(0, 0);
        assertEquals(0, eye.dirX(), 1e-9);
        assertEquals(-1, eye.dirY(), 1e-9, "heading zero faces north, which is -y");
        eye.look(Math.PI / 2, 0);
        assertEquals(1, eye.dirX(), 1e-9, "a quarter turn clockwise faces east");
        assertEquals(0, eye.dirY(), 1e-9);
    }

    /** Looking up drops the horizon down the screen, and vice versa. */
    @Test
    void theHorizonMovesOppositeTheTilt() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.look(0, 0);
        assertEquals(eye.centreY(), eye.horizonY(), 1e-9, "level: through the centre");
        eye.look(0, Math.toRadians(20));
        assertTrue(eye.horizonY() > eye.centreY(), "looking up puts the horizon lower");
        eye.look(0, Math.toRadians(-20));
        assertTrue(eye.horizonY() < eye.centreY(), "looking down puts it higher");
    }

    /** The tilt stops short of straight up, where a heading stops meaning anything. */
    @Test
    void theTiltIsBounded() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.look(0, Math.PI);
        assertEquals(EyeCamera.MAX_PITCH, eye.pitch(), 1e-9);
        eye.look(0, -Math.PI);
        assertEquals(-EyeCamera.MAX_PITCH, eye.pitch(), 1e-9);
    }

    /**
     * A quad straddling the near plane is <em>cut</em>, not dropped: the half
     * in front still draws, and every vertex that comes back is in front.
     */
    @Test
    void theNearPlaneCutsRatherThanDrops() {
        // right, high, depth — two vertices in front of the eye, two behind.
        double[] quad = {
                -10, 10, 50,
                10, 10, 50,
                10, -10, -50,
                -10, -10, -50};
        double[] out = new double[8 * 3];
        int n = EyeCamera.clipNear(quad, 4, out);
        assertEquals(4, n, "two kept, two crossings");
        for (int i = 0; i < n; i++) {
            assertTrue(out[i * 3 + 2] >= EyeCamera.NEAR - 1e-9,
                    "vertex " + i + " is in front of the near plane");
        }
    }

    /** A polygon wholly behind the eye comes back empty rather than mirrored. */
    @Test
    void aPolygonBehindTheEyeIsDroppedEntirely() {
        double[] quad = {-10, 10, -50, 10, 10, -50, 10, -10, -20, -10, -10, -20};
        assertEquals(0, EyeCamera.clipNear(quad, 4, new double[8 * 3]));
    }

    /** A single point behind the eye is refused, so nothing projects it. */
    @Test
    void aPointBehindTheEyeIsRefused() {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.place(0, 0, 0);
        eye.look(0, 0);
        assertFalse(eye.project(0, 500, 0, new double[3]), "500 units south, looking north");
    }

    // --- the cycle --------------------------------------------------------------

    /** On a plane, [F5] visits every stop and comes back to where it started. */
    @Test
    void theCycleVisitsEveryViewAndReturns() {
        Viewpoint at = Viewpoint.PLAN;
        for (int i = 0; i < Viewpoint.values().length - 1; i++) {
            Viewpoint next = at.next(true);
            assertTrue(next != at, "the cycle moves");
            at = next;
        }
        assertSame(Viewpoint.PLAN, at.next(true), "and comes back round");
    }

    /** A side-scroller has no height axis to stand an eye in, so it has one view. */
    @Test
    void aSideScrollerStaysFlat() {
        assertSame(Viewpoint.PLAN, Viewpoint.PLAN.next(false));
        for (Viewpoint v : Viewpoint.values()) {
            assertEquals(v == Viewpoint.PLAN, v.availableIn(false), v + " without a height axis");
            assertTrue(v.availableIn(true), v + " with one");
        }
    }

    /** First person is the only view that hides the body, and the only solid one at zero range. */
    @Test
    void onlyFirstPersonHidesTheBody() {
        for (Viewpoint v : Viewpoint.values()) {
            assertEquals(v != Viewpoint.FIRST_PERSON, v.showsSelf(), v + " draws the body");
            assertEquals(v != Viewpoint.PLAN, v.solid(), v + " is drawn through the eye");
        }
        assertEquals(0, Viewpoint.FIRST_PERSON.distanceTiles(), 1e-9);
        assertTrue(Viewpoint.THIRD_PERSON_BACK.distanceTiles() > 0);
        assertTrue(Viewpoint.THIRD_PERSON_FRONT.reversed(), "the front view looks back");
        assertFalse(Viewpoint.THIRD_PERSON_BACK.reversed());
    }

    // --- what gets drawn --------------------------------------------------------

    /**
     * One block on an empty plane draws exactly the two faces the eye can see —
     * its top and the side turned toward the viewer — and nothing else.
     *
     * <p>Counted rather than eyeballed because the count is the whole of the
     * culling: six faces exist, four are turned away, and a renderer that drew
     * them would look identical and cost three times as much.
     */
    @Test
    void aBlockDrawsOnlyTheFacesTurnedTowardTheEye() {
        Level lvl = bare(4, 4);
        lvl.setTile(1, 1, 1, stone(lvl));
        RecordingTarget target = paint(lvl, eyeAt(1.5 * TILE, 3.0 * TILE, 48, 0, 0));
        assertEquals(2, target.count("fillPolygon"),
                "the top (the eye is above it) and the south face (the eye is south)");
    }

    /**
     * Standing below a block shows its underside instead of its top: the same
     * two comparisons, answered the other way.
     */
    @Test
    void theUndersideShowsFromBelow() {
        Level lvl = bare(4, 4);
        lvl.setTile(1, 1, 4, stone(lvl)); // layer 4 floats at z = 96..128
        RecordingTarget target = paint(lvl,
                eyeLookingAt(1.5 * TILE, 3.0 * TILE, 8, 1.5 * TILE, 1.5 * TILE, 112));
        assertEquals(2, target.count("fillPolygon"), "the underside and the south face");
    }

    /**
     * A face with a block against it is not drawn — the cull that makes a
     * hillside affordable.
     *
     * <p>Both arrangements are two blocks and both are seen from the same
     * place. Pressed together, the face between them is gone and only three
     * faces are left; the same two blocks apart show four. The difference is
     * exactly the buried face.
     */
    @Test
    void aBuriedFaceIsNeverDrawn() {
        Level pressed = bare(6, 6);
        pressed.setTile(2, 2, 1, stone(pressed));
        pressed.setTile(2, 1, 1, stone(pressed)); // directly behind the first
        RecordingTarget together = paint(pressed,
                eyeAt(2.5 * TILE, 4.5 * TILE, 48, 0, 0));

        Level apart = bare(6, 6);
        apart.setTile(2, 2, 1, stone(apart));
        apart.setTile(2, 0, 1, stone(apart)); // a cell of daylight between them
        RecordingTarget separate = paint(apart, eyeAt(2.5 * TILE, 4.5 * TILE, 48, 0, 0));

        assertEquals(4, separate.count("fillPolygon"), "two tops and two south faces");
        assertEquals(3, together.count("fillPolygon"), "the face between them is gone");
    }

    /**
     * A column is one quad per block down its side, not one quad for the whole
     * run — <b>the price of the painter's order being exact</b>.
     *
     * <p>Merging a column of identical blocks into a single tall face halves
     * the queue and costs the thing the queue is for: a face spanning several
     * cells of the height axis has no single place in an order built out of
     * cell distances ({@code SolidPainter.cellOrder}), so a wall is nearer than
     * the block at its foot and further than the block at its top at the same
     * time, and whichever number it is given, something in front of part of it
     * sorts behind. Per block, every face belongs to exactly one cell and the
     * order is a proof rather than a heuristic.
     */
    @Test
    void aColumnDrawsOneFacePerBlockSoEveryFaceHasACellOfItsOwn() {
        for (int height = 1; height <= 6; height++) {
            Level lvl = bare(4, 4);
            for (int layer = 1; layer <= height; layer++) lvl.setTile(1, 1, layer, stone(lvl));
            RecordingTarget target = paint(lvl,
                    eyeLookingAt(1.5 * TILE, 3.5 * TILE, height * TILE + 48,
                            1.5 * TILE, 1.5 * TILE, height * TILE / 2.0));
            assertEquals(1 + height, target.count("fillPolygon"),
                    "a column " + height + " tall is one top and " + height + " side faces");
        }
    }

    /**
     * A block with a texture is drawn with its texture, once per block.
     *
     * <p>The solid views used to draw every face in the block's registered
     * colour and consult no texture at all, so a level dressed by a texture
     * pack was a level of flat colours the moment a player pressed F5. The
     * "once per block" half matters as much as the "with its texture" half: a
     * sheet stretched over a merged run is one brick eight blocks high.
     */
    @Test
    void aTexturedBlockIsDrawnWithItsSheetOncePerBlock(@TempDir Path dir) throws Exception {
        Level lvl = bare(6, 6);
        for (int layer = 1; layer <= 3; layer++) lvl.setTile(2, 2, layer, stone(lvl));
        String key = lvl.blocks.get("stone").textureKey();
        BufferedImage sheet = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        Path file = dir.resolve("stone.png");
        ImageIO.write(sheet, "png", file.toFile());
        try {
            Skins.put(new SkinDef(key, file.toString(), 16, 16, 1, 0));
            // Above the column and south of it, so its top and its three
            // southern faces are all turned toward the eye.
            RecordingTarget target = paint(lvl,
                    eyeLookingAt(2.5 * TILE, 6.5 * TILE, 5 * TILE,
                            2.5 * TILE, 2.5 * TILE, 1.5 * TILE));
            long blits = target.ofType(RecordingTarget.Cmd.Image.class).stream()
                    .filter(i -> i.op().equals("drawImageTransformed"))
                    .count();
            assertEquals(4, blits,
                    "three side faces and the top, each with its own copy of the sheet");
        } finally {
            Skins.remove(key);
        }
    }

    /** Nothing but sky is drawn for a level with nothing in it. */
    @Test
    void anEmptyLevelDrawsNoGeometry() {
        RecordingTarget target = new RecordingTarget(400, 300);
        SolidPainter painter = new SolidPainter();
        painter.begin(target, eyeAt(3 * TILE, 3 * TILE, 48, 0, 0), bare(6, 6));
        painter.terrain();
        painter.flush();
        assertEquals(0, target.count("fillPolygon"));
        assertTrue(target.count("fillLinearGradient") > 0, "but the sky is still painted");
    }

    /**
     * Every face is drawn behind whatever is in front of it: far first, near
     * last, which is the whole of the painter's algorithm here.
     *
     * <p>Three separate blocks in a line away from the eye, each with a cell of
     * daylight around it so none of their faces are culled against each other.
     * They are the same size and the same height, so the nearer one is the
     * wider one on screen — and reading the widths back in the order they were
     * drawn must never narrow.
     */
    @Test
    void facesAreDrawnFarToNear() {
        Level lvl = bare(8, 12);
        for (int row = 1; row <= 5; row += 2) lvl.setTile(3, row, 1, stone(lvl));
        RecordingTarget target = paint(lvl, eyeAt(3.5 * TILE, 9.5 * TILE, 16, 0, 0));

        int previous = 0;
        int seen = 0;
        for (RecordingTarget.Cmd.Shape shape : shapes(target)) {
            int width = span(shape.coords());
            assertTrue(width >= previous,
                    "face " + seen + " is at least as wide as the one drawn before it");
            previous = width;
            seen++;
        }
        assertEquals(3, seen, "one south face each, drawn far to near");
    }

    // --- the crosshair ------------------------------------------------------------

    /** Looking at a wall picks the wall, and offers the cell in front of it to build in. */
    @Test
    void theCrosshairPicksTheFaceItIsLookingAt() {
        Level lvl = walled();
        EyeCamera eye = eyeAt(8.5 * TILE, 8.5 * TILE, 16, 0, 0); // looking north

        TerrainPainter.Aim aim = SolidPainter.pick(eye, lvl, 200);
        assertNotNull(aim, "the wall is 112 units away");
        assertEquals(8, aim.col());
        assertEquals(4, aim.row(), "the wall's own cell, not the floor behind it");
        assertEquals(1, aim.layer());
        assertFalse(aim.top(), "the ray came in through the side");
        assertEquals(8, aim.placeCol());
        assertEquals(5, aim.placeRow(), "a block placed here goes in front of the wall");
    }

    /** Out of reach is nothing at all, however solid what is beyond it. */
    @Test
    void nothingIsPickedBeyondReach() {
        Level lvl = walled();
        assertNull(SolidPainter.pick(eyeAt(8.5 * TILE, 8.5 * TILE, 16, 0, 0), lvl, 100));
    }

    /**
     * Looking down picks the floor: layer zero, through its top face.
     *
     * <p>The floor is the one piece of a level that is a surface rather than a
     * box — {@code Level.surfaceZ} puts the top of a one-deep column at zero —
     * so the march has to give it the box it would have had, or a ray aimed at
     * the ground goes straight through the world.
     */
    @Test
    void lookingDownPicksTheFloor() {
        Level lvl = walled();
        EyeCamera eye = eyeAt(8.5 * TILE, 8.5 * TILE, 40, 0, -EyeCamera.MAX_PITCH);
        TerrainPainter.Aim aim = SolidPainter.pick(eye, lvl, 200);
        assertNotNull(aim);
        assertEquals(0, aim.layer(), "the floor is layer zero");
        assertTrue(aim.top(), "struck through its top face");
        assertEquals(8, aim.col());
        assertEquals(8, aim.row());
        assertEquals(aim.col(), aim.placeCol(), "a top-face hit builds upward in place");
        assertEquals(aim.row(), aim.placeRow());
    }

    /** The aimed-at point is on the thing struck, and at reach when nothing is. */
    @Test
    void theAimPointIsWhereTheRayStops() {
        Level lvl = walled();
        EyeCamera eye = eyeAt(8.5 * TILE, 8.5 * TILE, 16, 0, 0);
        double[] hit = SolidPainter.aimPoint(eye, lvl, 400);
        assertEquals(8.5 * TILE, hit[0], 1e-6, "straight ahead, so x does not move");
        assertEquals(5 * TILE, hit[1], 1e-6, "the wall's south face");

        // Turned round, with nothing in front of it inside the level.
        eye.look(Math.PI, 0);
        double[] open = SolidPainter.aimPoint(eye, lvl, 64);
        assertEquals(8.5 * TILE + 64, open[1], 1e-6, "the far end of the reach");
    }

    /** The march is bounded even aimed along an axis it can never leave. */
    @Test
    void aRayThatMeetsNothingStops() {
        Level lvl = bare(8, 8);
        EyeCamera eye = eyeAt(4 * TILE, 4 * TILE, 4 * TILE, 0, 0);
        assertNull(SolidPainter.pick(eye, lvl, 10_000));
        eye.look(0, EyeCamera.MAX_PITCH);
        assertNull(SolidPainter.pick(eye, lvl, 10_000), "straight up, out of the world");
    }

    // --- fixtures ------------------------------------------------------------------

    /** A plan-view level with a height axis and nothing in it — not even a floor. */
    private static Level bare(int w, int h) {
        Level lvl = Level.empty("solid-test", w, h, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        GameProfile settings = new GameProfile("solid-test");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    /** A floored level with a wall three blocks high along row 4. */
    private static Level walled() {
        Level lvl = bare(16, 16);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        for (int col = 0; col < 16; col++) {
            for (int layer = 1; layer <= 3; layer++) lvl.setTile(col, 4, layer, stone(lvl));
        }
        return lvl;
    }

    private static int stone(Level lvl) {
        return lvl.blocks.get("stone").id();
    }

    private static EyeCamera eyeAt(double x, double y, double z, double yaw, double pitch) {
        EyeCamera eye = new EyeCamera(400, 300);
        eye.place(x, y, z);
        eye.look(yaw, pitch);
        return eye;
    }

    /**
     * An eye at one point aimed at another. Counting faces means the faces have
     * to be <em>on screen</em>, and a fixed heading that happens to point past
     * the thing under test would count zero of them and read as a culling bug.
     */
    private static EyeCamera eyeLookingAt(double x, double y, double z,
                                          double atX, double atY, double atZ) {
        double dx = atX - x, dy = atY - y, dz = atZ - z;
        // Forward is (sin yaw, -cos yaw), so the heading of a direction is
        // atan2 of its east component against its northward one.
        return eyeAt(x, y, z, Math.atan2(dx, -dy), Math.atan2(dz, Math.hypot(dx, dy)));
    }

    /**
     * One frame of {@code lvl} through {@code eye}, recorded rather than drawn.
     * The sky is painted too, but it is a gradient rather than a polygon, so it
     * never lands in what these tests count.
     */
    private static RecordingTarget paint(Level lvl, EyeCamera eye) {
        RecordingTarget target = new RecordingTarget(eye.viewportWidth(), eye.viewportHeight());
        SolidPainter painter = new SolidPainter();
        painter.begin(target, eye, lvl);
        painter.terrain();
        painter.flush();
        return target;
    }

    private static java.util.List<RecordingTarget.Cmd.Shape> shapes(RecordingTarget target) {
        return target.ofType(RecordingTarget.Cmd.Shape.class).stream()
                .filter(s -> s.op().equals("fillPolygon"))
                .toList();
    }

    /** How wide a recorded polygon is on screen. */
    private static int span(int[] coords) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < coords.length; i += 2) {
            lo = Math.min(lo, coords[i]);
            hi = Math.max(hi, coords[i]);
        }
        return hi - lo;
    }
}
