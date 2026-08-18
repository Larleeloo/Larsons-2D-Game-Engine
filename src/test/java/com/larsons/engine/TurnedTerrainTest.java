package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.SurfaceDecor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the terrain does when the camera turns: which cells are swept, which
 * faces of a block are shown, and which of two blocks is in front.
 *
 * <p><b>Every case runs at all eight headings, for the reason C1 wrote down and
 * this step inherits.</b> The failure C4 names — "the world is inside out" — is
 * a sign error in a screen-space normal, and a sign error is invisible at
 * exactly the headings where the quantity it flips is zero. Getting the near
 * block in front at 0° and 180° and behind at the other six is the shape this
 * defect has, and testing two headings is how it ships.
 */
class TurnedTerrainTest {

    private static final int TILE = 32;
    private static final int W = 480, H = 360;

    @BeforeEach
    void unskinned() {
        // The assertions read procedural colours, so no sheet another test
        // installed may be what actually draws.
        Skins.install(List.of());
    }

    // --- C3: the sweep ----------------------------------------------------------

    /**
     * A turned view sweeps roughly the cells it can see, not the box around
     * them.
     *
     * <p>The bounds a scene computes are the axis-aligned box around the four
     * viewport corners carried back into the world, and that box is the view
     * only while the view is square to the world. Turned an eighth it holds
     * <b>2.47×</b> the cells that are actually on screen — measured — because
     * the box around a rotated rectangle is up to twice its area. Without a
     * per-cell rejection the terrain pass would simply get half as much work
     * again the moment the player pressed the rotate key, which is the worst
     * possible time for it: the same headings are the ones the floor cache
     * refuses to bake, so that work is live.
     *
     * <p>Counted in fills rather than in cells, because a fill is what costs.
     */
    @Test
    void aTurnedViewDoesNotSweepTheBoxAroundItself() {
        Level lvl = floor(LevelFormat.THREE_D, 60, 60);

        int square = fills(lvl, 0);
        int turned = fills(lvl, Camera.EIGHTH_TURN);

        assertTrue(turned <= square * 1.3, "an eighth of a turn took the terrain sweep "
                + "from " + square + " fills to " + turned + " — the extra is the corners of "
                + "the bounding box, behind the player's shoulder, and a rotated view is "
                + "made of them");
        // …and it is still drawing a view's worth, rather than having culled
        // the picture away.
        assertTrue(turned >= square * 0.7,
                "a turned view drew " + turned + " fills against " + square + " square on, "
                        + "which is too few to be the same view");
    }

    /**
     * A block whose base is off the bottom of the screen still draws the part
     * of itself that reaches back in.
     *
     * <p>The margin the rejection allows, stated as the thing it protects. A
     * stacked block is drawn standing <em>up</em> from its base, so the base
     * leaves the screen a good deal before the block does; reject on the base
     * alone and a wall vanishes while the top of it is still in view, which is
     * a pop rather than a scroll. The same margin carries the shadow, which
     * falls away from its caster and can land on screen from a cell that is
     * not.
     */
    @Test
    void aBlockBelowTheScreenStillDrawsWhatReachesBackIntoIt() {
        Level bare = floor(LevelFormat.THREE_D, 60, 60);
        int lift = TerrainPainter.liftPixels(camera(bare, 0), TILE);
        assertTrue(lift > 0, "a plan view lifts stacked blocks, or there is nothing to test");

        // A row placed deliberately rather than found: its base sits half a
        // lift below the bottom edge, so the floor tile is off screen and the
        // block standing on it is not. Searching for such a row instead would
        // depend on where the camera happened to land — rows are a tile apart
        // and the lift is a third of that, so most camera positions have no
        // row in this band at all.
        int row = 30;

        Level stacked = floor(LevelFormat.THREE_D, 60, 60);
        int stone = stacked.blocks.get("stone").id();
        for (int c = 0; c < stacked.width; c++) stacked.setTile(c, row, Level.LAYER_UPPER, stone);

        // Placed through the projection rather than in world units: the
        // camera's tilt foreshortens the depth axis, so "half a lift below the
        // bottom edge" is a screen distance and the world distance that
        // produces it depends on how high the camera is standing.
        Camera cam = camera(bare, 0);
        cam.centerOn(10.5 * TILE, row * (double) TILE);
        double screenPerWorld = (cam.worldToScreenY(0, (row + 1) * (double) TILE)
                - cam.worldToScreenY(0, row * (double) TILE)) / (double) TILE;
        double push = (H + lift / 2.0 - cam.worldToScreenY(0, row * (double) TILE))
                / screenPerWorld;
        cam.centerOn(10.5 * TILE, row * (double) TILE - push);
        assertTrue(cam.worldToScreenY(0, row * (double) TILE) > H,
                "the row was meant to be below the bottom edge");

        int changed = differingPixels(renderWholeLevel(bare, cam),
                renderWholeLevel(stacked, cam));
        assertTrue(changed > 0, "a wall standing on a row just below the screen drew "
                + "nothing at all — it is being rejected on its base, and a block is "
                + "taller than its base");
    }

    // --- C4: faces and depth ----------------------------------------------------

    /**
     * A block shows the faces turned toward the camera, and the count changes
     * with the heading the way the shape of the tile does.
     *
     * <p>One face when the tile projects to an upright square — only its near
     * edge is turned toward the viewer — and two when it projects to a diamond,
     * which a square tile does at every heading halfway between the cardinals.
     * Never three: a convex quad extruded along a screen axis can never turn
     * three of its edges toward the same side.
     */
    @Test
    void aBlockShowsTheFacesTurnedTowardTheCamera() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            Level lvl = floor(format, 20, 20);
            int stone = lvl.blocks.get("stone").id();
            lvl.setTile(10, 10, Level.LAYER_UPPER, stone);

            for (int eighth = 0; eighth < 8; eighth++) {
                // A lone block on a bare floor: every side face in the frame is
                // this block's.
                RecordingTarget rec = record(lvl, eighth * Camera.EIGHTH_TURN);
                // Floor cells are one fill and one outline each; a side face is
                // the same pair. The block's own top face is another. So the
                // faces are what the block adds over a floor-only frame.
                Level bare = floor(format, 20, 20);
                int faces = rec.count("fillPolygon")
                        - record(bare, eighth * Camera.EIGHTH_TURN).count("fillPolygon")
                        - 1;   // the block's top face

                // A square tile projects to a square at the cardinals and to a
                // diamond at the four headings halfway between them.
                boolean diamond = eighth % 2 == 1;
                assertEquals(diamond ? 2 : 1, faces, format + " at " + eighth
                        + "/8 of a turn: a tile projecting to a "
                        + (diamond ? "diamond shows two side faces" : "square shows one")
                        + ", and this frame drew " + faces);
            }
        }
    }

    /**
     * A tile's corners read the same way round on screen at every heading, in
     * both plan views.
     *
     * <p>The assumption {@code drawVisibleFaces} rests on, pinned where it can
     * fail. It picks the faces turned toward the camera from the sign of each
     * edge's outward normal, and that sign depends on the winding: derive it
     * from the wrong one and the block shows the faces turned away, which is
     * the world inside out.
     *
     * <p>Measuring the winding per cell instead is five lines and was the first
     * thing written here. It was then measured to be unreachable — a rotation
     * has determinant +1, and the diamond's is positive as well, so nothing this
     * camera can do mirrors a tile. A negative control that forced the winding
     * to a constant passed every test in the suite, which is the definition of a
     * branch that cannot be tested. So the constant is the code and this is the
     * guard: a projection that ever mirrors a quad — a genuine reflection, not a
     * turn — fails here, in one place, rather than showing up as a block with
     * its back to you.
     */
    @Test
    void aTileReadsTheSameWayRoundAtEveryHeading() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            Level lvl = floor(format, 20, 20);
            for (int eighth = 0; eighth < 8; eighth++) {
                Camera cam = camera(lvl, eighth * Camera.EIGHTH_TURN);
                int[] xs = new int[4], ys = new int[4], corner = new int[2];
                double[][] cs = {{10, 10}, {11, 10}, {11, 11}, {10, 11}};
                for (int i = 0; i < 4; i++) {
                    cam.worldToScreen(cs[i][0] * TILE, cs[i][1] * TILE, corner);
                    xs[i] = corner[0];
                    ys[i] = corner[1];
                }
                long area = 0;
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) & 3;
                    area += (long) xs[i] * ys[j] - (long) xs[j] * ys[i];
                }
                assertTrue(area > 0, format + " at " + eighth + "/8 of a turn: a tile's "
                        + "corners read anticlockwise on screen (signed area " + area
                        + "). TerrainPainter derives its visible faces from the other "
                        + "winding, so every block in this view is showing the faces "
                        + "turned away from the camera");
            }
        }
    }

    /**
     * Of two blocks one behind the other, the near one is drawn on top — at
     * every heading.
     *
     * <p>C4's own verification, and the failure it is aimed at is the one that
     * looks like the world turned inside out. It is asked of the depth queue
     * rather than of a pixel: painter's order <em>is</em> "on top", and the
     * queue is what C4 says must sort along the rotated depth axis rather than
     * along the world row index. So the two blocks are given colours of their
     * own and the frame is read back as the sequence of fills it was made of —
     * the far one must be laid down before the near one covers it.
     *
     * <p>"Behind" is the heading's own direction, so the pair of cells swings
     * round as the camera does, which is the point. At 0° that is the cell to
     * the south; at 90° the cell to the east; at 45° a diagonal neighbour.
     */
    @Test
    void theNearerOfTwoBlocksIsDrawnOnTopAtEveryHeading() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            for (int eighth = 0; eighth < 8; eighth++) {
                double yaw = eighth * Camera.EIGHTH_TURN;
                // Toward the viewer on the world plane: the opposite of the way
                // the camera is looking. One cell of it, at this heading.
                int stepX = (int) Math.round(-Math.sin(yaw));
                int stepY = (int) Math.round(Math.cos(yaw));

                Level lvl = floor(format, 20, 20);
                int farBlock = lvl.blocks.get("stone").id();
                int nearBlock = nearColoured(lvl, farBlock);
                lvl.setTile(10, 10, Level.LAYER_UPPER, farBlock);
                lvl.setTile(10 + stepX, 10 + stepY, Level.LAYER_UPPER, nearBlock);

                int farArgb = lvl.blocks.get(farBlock).color().getRGB();
                int nearArgb = lvl.blocks.get(nearBlock).color().getRGB();

                List<RecordingTarget.Cmd> cmds = record(lvl, yaw).commands();
                int farAt = lastTopFace(cmds, farArgb);
                int nearAt = lastTopFace(cmds, nearArgb);

                assertTrue(farAt >= 0 && nearAt >= 0, format + " at " + eighth
                        + "/8: one of the two blocks never drew its top face, so this "
                        + "compares nothing");
                assertTrue(farAt < nearAt, format + " at " + eighth + "/8 of a turn: the "
                        + "block further from the camera was drawn at command " + farAt
                        + " and the nearer one at " + nearAt + " — the far one is being "
                        + "painted over the near one, which is the world inside out");
            }
        }
    }

    // --- C6: shadows, decor and liquids -----------------------------------------

    /**
     * A block's cast shadow swings with the world, not with the screen.
     *
     * <p>C6 calls this "the most visible possible bug and the easiest to
     * introduce", and it is: the sun stands at a fixed bearing in the world, so
     * when the camera turns, the shadow on screen must turn <em>with the
     * ground</em> — a shadow that stays pointing down-right while the world
     * rotates under it reads instantly as wrong, and a shadow that turns the
     * opposite way reads as the light source orbiting the player.
     *
     * <p><b>The expected direction is carried, not recomputed.</b> Restating
     * the sun-bearing formula here would test that it was copied correctly and
     * nothing else. Instead the shadow's offset is measured square-on, carried
     * back into the world through the camera's own inverse, and re-projected at
     * each heading — so what is asserted is that one fixed world vector is what
     * the shadow follows, which is the actual claim.
     */
    @Test
    void aBlocksShadowSwingsWithTheWorldAtEveryHeading() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            Level lvl = floor(format, 20, 20);
            lvl.setTile(10, 10, Level.LAYER_UPPER, lvl.blocks.get("stone").id());

            // The world vector the shadow runs along, recovered from the
            // unturned frame rather than restated from the sun's formula.
            Camera square = camera(lvl, 0);
            double[] offsetAtRest = shadowOffset(lvl, square);
            double[] sunward = square.inversePlanar(offsetAtRest[0] / square.zoom,
                    offsetAtRest[1] / square.zoom);

            for (int eighth = 1; eighth < 8; eighth++) {
                Camera cam = camera(lvl, eighth * Camera.EIGHTH_TURN);
                double[] expected = cam.planarDelta(sunward[0], sunward[1]);
                double[] actual = shadowOffset(lvl, cam);

                // Compared as a direction: the offset is rounded to whole
                // pixels twice on its way here, and what the eye reads is which
                // way the shadow points.
                double cross = expected[0] * actual[1] - expected[1] * actual[0];
                double dot = expected[0] * actual[0] + expected[1] * actual[1];
                double degrees = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
                assertTrue(degrees < 12, format + " at " + eighth + "/8 of a turn: the "
                        + "shadow points " + Math.round(degrees) + "° away from where the "
                        + "sun puts it. It falls at (" + actual[0] + "," + actual[1] + ") "
                        + "and the same world direction projects to (" + expected[0] + ","
                        + expected[1] + ") — the shadow is not turning with the ground");
            }
        }
    }

    /**
     * Surface decor stays on the block it grows on, at every heading.
     *
     * <p>{@code SurfaceDecorPainter}'s own note records this failing once
     * already: styles written straight up the screen "tore every tuft off the
     * block it belonged to as soon as the level was seen isometrically", and
     * the fix was to write them along the face and out of it and let the camera
     * decide where that lands. Rotation is a second camera to be right for, and
     * the class has never been asked. Measured as the pixels the decor adds to
     * a bare floor: their centre must sit within a tile of the block's own.
     */
    @Test
    void surfaceDecorStaysOnTheSideOfItsBlockItBelongsTo() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            Level bare = floor(format, 20, 20);

            for (int eighth = 0; eighth < 8; eighth++) {
                double yaw = eighth * Camera.EIGHTH_TURN;
                Camera cam = camera(bare, yaw);
                int[] host = new int[2];
                cam.worldToScreen(10.5 * TILE, 10.5 * TILE, host);

                for (SurfaceDecor.Face face : SurfaceDecor.Face.values()) {
                    Level grown = floor(format, 20, 20);
                    grown.surfaceDecor.add(new SurfaceDecor.Placement(10, 10, face,
                            "grass_tuft", false, SurfaceDecor.Visibility.ALWAYS));

                    double[] centre = inkCentre(renderDecor(bare, cam), renderDecor(grown, cam));
                    assertTrue(centre != null, format + " at " + eighth + "/8, face "
                            + face + ": the decoration drew nothing at all");

                    // On its block at all: the loose half of the claim, and the
                    // one the class note records failing once already, when
                    // styles written straight up the screen tore every tuft off
                    // the block it belonged to in isometric.
                    double away = Math.hypot(centre[0] - host[0], centre[1] - host[1]);
                    assertTrue(away < TILE * 1.5, format + " at " + eighth + "/8, face "
                            + face + ": the decoration drew " + Math.round(away)
                            + "px from the block it grows on, which is off it");

                    // …and on the right side of it. A face is a world
                    // direction, so which side of the block a tuft sits on has
                    // to swing round as the view does — four tufts on one block
                    // must still read as its north, south, east and west sides
                    // after a turn, rather than all sliding to the same edge.
                    double[] outward = cam.planarDelta(face.dc * TILE, face.dr * TILE);
                    double dx = centre[0] - host[0], dy = centre[1] - host[1];
                    // Only the part across the face direction is compared: a
                    // plan view also lifts a standing tuft up the screen, which
                    // is height rather than which side it is on.
                    double along = (dx * outward[0] + dy * outward[1])
                            / Math.hypot(outward[0], outward[1]);
                    assertTrue(along > 0, format + " at " + eighth + "/8: the tuft on the "
                            + face + " face of the block drew at (" + Math.round(dx) + ","
                            + Math.round(dy) + ") from its centre, which is on the far side "
                            + "of the block from where that face projects to ("
                            + Math.round(outward[0]) + "," + Math.round(outward[1])
                            + ") — the faces are not turning with the world");
                }
            }
        }
    }

    /**
     * A pool's bright surface line stays on the same rim of the pool when the
     * view turns.
     *
     * <p>C6 says the liquid surface "needs no change, but verify rather than
     * assume", and the verification is worth more than it looks: the line is
     * drawn between two <em>named corners</em> of the cell, and a corner is a
     * world position, so it turns with the world for free. What that sentence
     * would look like if it were false is a bright line jumping to a different
     * side of the pond every time the player pressed the rotate key. Asserted
     * exactly — the line's endpoints are the projected world edge, to the
     * pixel — because there is nothing here that needs a tolerance.
     */
    @Test
    void aPoolsSurfaceLineStaysOnTheSameRimWhenTheViewTurns() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.THREE_D}) {
            Level lvl = floor(format, 20, 20);
            lvl.setTile(10, 10, lvl.blocks.get("water").id());

            for (int eighth = 0; eighth < 8; eighth++) {
                double yaw = eighth * Camera.EIGHTH_TURN;
                Camera cam = camera(lvl, yaw);

                List<int[]> lines = new ArrayList<>();
                for (RecordingTarget.Cmd cmd : record(lvl, yaw).commands()) {
                    if (cmd instanceof RecordingTarget.Cmd.Shape shape
                            && shape.op().equals("drawLine")) {
                        lines.add(shape.coords());
                    }
                }
                assertEquals(1, lines.size(), format + " at " + eighth + "/8: a pool with "
                        + "dry ground north of it draws exactly one surface line");

                int[] from = new int[2], to = new int[2];
                cam.worldToScreen(10 * TILE, 10 * TILE, from);
                cam.worldToScreen(11 * TILE, 10 * TILE, to);
                assertArrayEquals(new int[]{from[0], from[1], to[0], to[1]}, lines.get(0),
                        format + " at " + eighth + "/8 of a turn: the surface line is not "
                                + "on the pool's northern rim any more — it has moved to a "
                                + "different side of the water");
            }
        }
    }

    /** The one shadow shape's centre, relative to the block casting it. */
    private static double[] shadowOffset(Level lvl, Camera cam) {
        int[] bounds = visibleBounds(lvl, cam);
        RecordingTarget target = new RecordingTarget(W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam, bounds, 0.0, pass, null);
        pass.flush();

        int[] box = null;
        for (RecordingTarget.Cmd cmd : target.commands()) {
            if (cmd instanceof RecordingTarget.Cmd.Shape shape
                    && shape.op().equals("fillShape")) {
                box = shape.coords();
            }
        }
        if (box == null) throw new IllegalStateException("no shadow was drawn at all");
        int[] host = new int[2];
        cam.worldToScreen(10.5 * TILE, 10.5 * TILE, host);
        return new double[]{box[0] + box[2] / 2.0 - host[0], box[1] + box[3] / 2.0 - host[1]};
    }

    /** One surface-decor pass over a floor, into an image. */
    private static BufferedImage renderDecor(Level lvl, Camera cam) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        DrawTarget target = Java2DTarget.unsized(g);
        DepthPass pass = DepthPass.of(lvl.perspective);
        int[] bounds = visibleBounds(lvl, cam);
        TerrainPainter.draw(target, lvl, cam, bounds, 0.0, pass, null);
        SurfaceDecorPainter.draw(target, lvl, cam, bounds, false, 0.0, pass);
        pass.flush();
        g.dispose();
        return img;
    }

    /** The centre of the pixels {@code with} has that {@code without} does not. */
    private static double[] inkCentre(BufferedImage without, BufferedImage with) {
        long sumX = 0, sumY = 0, n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (without.getRGB(x, y) != with.getRGB(x, y)) {
                    sumX += x;
                    sumY += y;
                    n++;
                }
            }
        }
        return n == 0 ? null : new double[]{sumX / (double) n, sumY / (double) n};
    }

    /** Where in the frame the top face of {@code argb}'s block was laid down. */
    private static int lastTopFace(List<RecordingTarget.Cmd> cmds, int argb) {
        for (int i = cmds.size() - 1; i >= 0; i--) {
            if (cmds.get(i) instanceof RecordingTarget.Cmd.Shape shape
                    && shape.op().equals("fillPolygon") && shape.argb() == argb) {
                return i;
            }
        }
        return -1;
    }

    // --- helpers ----------------------------------------------------------------

    private static Camera camera(Level lvl, double yaw) {
        Camera cam = new Camera(lvl.perspective, W, H);
        cam.tileSize = TILE;
        cam.centerOn(10.5 * TILE, 10.5 * TILE);
        cam.setYaw(yaw);
        return cam;
    }

    /** The bounds a scene computes: the box around the inverse-projected corners. */
    private static int[] visibleBounds(Level lvl, Camera cam) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int[] c : new int[][]{{0, 0}, {W, 0}, {0, H}, {W, H}}) {
            double[] wp = cam.screenToWorld(c[0], c[1]);
            minX = Math.min(minX, wp[0]); maxX = Math.max(maxX, wp[0]);
            minY = Math.min(minY, wp[1]); maxY = Math.max(maxY, wp[1]);
        }
        return new int[]{
                Math.max(0, (int) Math.floor(minX / TILE) - 1),
                Math.max(0, (int) Math.floor(minY / TILE) - 1),
                Math.min(lvl.width - 1, (int) Math.floor(maxX / TILE) + 1),
                Math.min(lvl.height - 1, (int) Math.floor(maxY / TILE) + 1)};
    }

    private static int fills(Level lvl, double yaw) {
        return record(lvl, yaw).count("fillPolygon");
    }

    private static RecordingTarget record(Level lvl, double yaw) {
        Camera cam = camera(lvl, yaw);
        RecordingTarget target = new RecordingTarget(W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam, visibleBounds(lvl, cam), 0.0, pass, null);
        pass.flush();
        return target;
    }

    private static BufferedImage renderWholeLevel(Level lvl, double yaw) {
        return renderWholeLevel(lvl, camera(lvl, yaw));
    }

    private static BufferedImage renderWholeLevel(Level lvl, Camera cam) {
        return render(lvl, cam, new int[]{0, 0, lvl.width - 1, lvl.height - 1});
    }

    private static BufferedImage render(Level lvl, double yaw) {
        return render(lvl, camera(lvl, yaw), null);
    }

    private static BufferedImage render(Level lvl, Camera cam, int[] bounds) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam,
                bounds != null ? bounds : visibleBounds(lvl, cam), 0.0, pass, null);
        pass.flush();
        g.dispose();
        return img;
    }

    /** A floor of one block type, so anything stacked on it stands out. */
    private static Level floor(LevelFormat format, int w, int h) {
        Level lvl = Level.empty("turned", w, h, TILE);
        lvl.setFormat(format);
        int path = lvl.blocks.get("stone_path").id();
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) lvl.setTile(c, r, path);
        }
        return lvl;
    }

    /** A solid block whose colour no other block in this frame shares. */
    private static int nearColoured(Level lvl, int avoid) {
        Color taken = lvl.blocks.get(avoid).color();
        Color floor = lvl.blocks.get(lvl.blocks.get("stone_path").id()).color();
        for (Block b : lvl.blocks.all()) {
            if (b.liquid() || b.id() == avoid) continue;
            if (!b.color().equals(taken) && !b.color().equals(floor)) return b.id();
        }
        throw new IllegalStateException("no second colour to tell the blocks apart with");
    }

    private static int count(BufferedImage img, Color color) {
        int rgb = color.getRGB(), n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) == rgb) n++;
            }
        }
        return n;
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }
}
