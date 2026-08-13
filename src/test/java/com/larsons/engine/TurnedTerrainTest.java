package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

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
        Level lvl = floor(LevelFormat.TOP_DOWN, 60, 60);

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
        Level bare = floor(LevelFormat.TOP_DOWN, 60, 60);
        int lift = TerrainPainter.liftPixels(camera(bare, 0), TILE);
        assertTrue(lift > 0, "a plan view lifts stacked blocks, or there is nothing to test");

        // A row placed deliberately rather than found: its base sits half a
        // lift below the bottom edge, so the floor tile is off screen and the
        // block standing on it is not. Searching for such a row instead would
        // depend on where the camera happened to land — rows are a tile apart
        // and the lift is a third of that, so most camera positions have no
        // row in this band at all.
        int row = 30;
        double centreY = row * (double) TILE - H / 2.0 - lift / 2.0;

        Level stacked = floor(LevelFormat.TOP_DOWN, 60, 60);
        int stone = stacked.blocks.get("stone").id();
        for (int c = 0; c < stacked.width; c++) stacked.setUpper(c, row, stone);

        Camera cam = camera(bare, 0);
        cam.centerOn(10.5 * TILE, centreY);
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
     * which a square tile does at every heading halfway between the cardinals,
     * and which an isometric tile does at the cardinals. Never three: a convex
     * quad extruded along a screen axis can never turn three of its edges
     * toward the same side.
     */
    @Test
    void aBlockShowsTheFacesTurnedTowardTheCamera() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC}) {
            Level lvl = floor(format, 20, 20);
            int stone = lvl.blocks.get("stone").id();
            lvl.setUpper(10, 10, stone);

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

                boolean diamond = (format == LevelFormat.ISOMETRIC) == (eighth % 2 == 0);
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
        for (LevelFormat format : new LevelFormat[]{LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC}) {
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
        for (LevelFormat format : new LevelFormat[]{LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC}) {
            for (int eighth = 0; eighth < 8; eighth++) {
                double yaw = eighth * Camera.EIGHTH_TURN;
                // Toward the viewer on the world plane: the opposite of the way
                // the camera is looking. One cell of it, at this heading.
                int stepX = (int) Math.round(-Math.sin(yaw));
                int stepY = (int) Math.round(Math.cos(yaw));

                Level lvl = floor(format, 20, 20);
                int farBlock = lvl.blocks.get("stone").id();
                int nearBlock = nearColoured(lvl, farBlock);
                lvl.setUpper(10, 10, farBlock);
                lvl.setUpper(10 + stepX, 10 + stepY, nearBlock);

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
