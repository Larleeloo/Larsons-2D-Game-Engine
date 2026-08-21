package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SolidPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a block's texture actually lands in the first- and third-person views.
 *
 * <p><b>Why this is checked against arithmetic rather than against a picture.</b>
 * The bug this pins was reported as surfaces that "bend and fold in ways they
 * shouldn't", and both halves of that are invisible to the tests next door:
 * every face was the right shape, in the right order, with the right sheet on
 * it, and the sheet was <em>slid</em> across it by tens of pixels. Counting
 * draws cannot see that and a golden frame can only say that something moved.
 *
 * <p>So the floor is drawn with a texture whose four quadrants are four flat
 * colours, and every pixel of the result is compared with where a ray through
 * that pixel actually strikes the floor — the perspective answer, computed the
 * long way round, per pixel, with no reference to how the painter got there.
 * Pixels within a few of a colour boundary are skipped, because a boundary is
 * exactly where a rounding of half a pixel flips the answer and that is not
 * what is being asked; everything else has to agree. A texture slid across a
 * face fails this by thousands of pixels, and a texture drawn in patches that
 * do not quite meet fails it along every seam.
 */
class SolidTextureMapTest {

    private static final int TILE = 32;
    private static final int W = 480;
    private static final int H = 360;
    /** How far from a colour boundary a pixel has to be to be worth comparing. */
    private static final int GUARD = 3;
    /** How far down the floor to compare, in tiles — short of the fog. */
    private static final double COMPARE_TILES = 8;

    private static final int[] QUADRANTS = {
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00,
    };

    /**
     * The floor's texture lands where the perspective says it should, over the
     * whole frame — the near tiles included, which is where it did not.
     */
    @Test
    void theFloorsTextureLandsWherePerspectiveSaysItShould(@TempDir Path dir) throws Exception {
        Level lvl = flatFloor();
        String key = lvl.blocks.get("grass").textureKey();
        Skins.put(new SkinDef(key, quadrants(dir).toString(), TILE, TILE, 1, 0));
        try {
            EyeCamera eye = new EyeCamera(W, H);
            eye.place(6.5 * TILE, 9.5 * TILE, 0.9 * TILE);
            eye.look(0, Math.toRadians(-18)); // low and oblique: where it bent
            BufferedImage frame = render(lvl, eye);

            int compared = 0;
            int wrong = 0;
            for (int py = 0; py < H; py++) {
                for (int px = 0; px < W; px++) {
                    int want = floorColourAt(eye, px, py);
                    if (want == 0) continue; // off the floor, or too far to judge
                    // Skip the boundaries themselves: a colour edge, a tile
                    // edge and the black stroke along it all live inside a
                    // pixel or two of each other, and half a pixel of rounding
                    // decides them.
                    if (floorColourAt(eye, px - GUARD, py) != want
                            || floorColourAt(eye, px + GUARD, py) != want
                            || floorColourAt(eye, px, py - GUARD) != want
                            || floorColourAt(eye, px, py + GUARD) != want) {
                        continue;
                    }
                    compared++;
                    if (nearest(frame.getRGB(px, py)) != want) wrong++;
                }
            }
            assertTrue(compared > 40_000,
                    "the floor should fill most of the frame; compared " + compared);
            assertEquals(0, wrong, wrong + " of " + compared
                    + " floor pixels carry a different part of the texture than the"
                    + " perspective puts there");
        } finally {
            Skins.remove(key);
        }
    }

    /**
     * A face is split only when it needs it: the tile underfoot into patches,
     * the wall across the room into one.
     *
     * <p>Both halves matter. Splitting nothing is the bug that was reported;
     * splitting everything is the cost that made the first attempt at fixing it
     * unaffordable, and it is the common case — most of what a frame draws is
     * far enough away, or square enough on, to be a parallelogram already.
     */
    @Test
    void onlyTheFacesThatBendAreSplit(@TempDir Path dir) throws Exception {
        Level lvl = oneBlock();
        String key = lvl.blocks.get("grass").textureKey();
        Skins.put(new SkinDef(key, quadrants(dir).toString(), TILE, TILE, 1, 0));
        try {
            // Square on: from straight above, the block's top projects to a
            // rectangle, an affine map lands on all four of its corners, and
            // one blit covers it exactly.
            assertEquals(1, blits(lvl, 1.5 * TILE, 1.5 * TILE, 8 * TILE, -89),
                    "seen from directly above, a tile is already a rectangle");

            // Obliquely, from a little above it: the near edge of the same face
            // is drawn half as long again as its far one, which is the case no
            // affine map covers and the case the floor underfoot is always in.
            assertTrue(blits(lvl, 1.5 * TILE, 4 * TILE, 2 * TILE, -20) > 1,
                    "a face running away from the eye has to be drawn in patches");
        } finally {
            Skins.remove(key);
        }
    }

    /**
     * The block you are standing <em>against</em> keeps its texture.
     *
     * <p>This is the one case where the near plane cuts a face that is still
     * most of the screen, and it is the case a player reported as the textures
     * disappearing when they stood very close to something. Stand beside a wall
     * rather than facing it and the wall runs from in front of your eye to
     * behind it: two of its corners are behind the near plane, the quad cannot
     * be projected as a whole, and the rule used to be that such a face fell
     * back to a flat fill. So the nearest and largest face in the frame was the
     * one that lost its sheet, and it lost it exactly when you walked up to it.
     */
    @Test
    void theBlockYouAreStandingAgainstKeepsItsTexture(@TempDir Path dir) throws Exception {
        Level lvl = oneBlock();
        String key = lvl.blocks.get("grass").textureKey();
        Skins.put(new SkinDef(key, quadrants(dir).toString(), TILE, TILE, 1, 0));
        try {
            // A pace south of the block's south face, looking east along it.
            assertTrue(blits(lvl, 1.5 * TILE, 2 * TILE + 3, 0.8 * TILE,
                            Math.PI / 2, 0) > 0,
                    "the wall at your shoulder should still be drawn with its sheet");
        } finally {
            Skins.remove(key);
        }
    }

    // --- fixtures ------------------------------------------------------------------

    private static Level flatFloor() {
        Level lvl = Level.empty("texture-map", 12, 12, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        lvl.fillFloor(lvl.blocks.get("grass").id());
        GameProfile settings = new GameProfile("texture-map");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    /** A sheet whose four quadrants say which way round it went on. */
    private static Path quadrants(Path dir) throws Exception {
        BufferedImage sheet = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        int half = TILE / 2;
        g.setColor(new Color(QUADRANTS[0]));
        g.fillRect(0, 0, half, half);
        g.setColor(new Color(QUADRANTS[1]));
        g.fillRect(half, 0, half, half);
        g.setColor(new Color(QUADRANTS[2]));
        g.fillRect(half, half, half, half);
        g.setColor(new Color(QUADRANTS[3]));
        g.fillRect(0, half, half, half);
        g.dispose();
        Path file = dir.resolve("quadrants.png");
        ImageIO.write(sheet, "png", file.toFile());
        return file;
    }

    private static BufferedImage render(Level lvl, EyeCamera eye) {
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        // Nearest neighbour, so a pixel carries one texel rather than a blend of
        // four — otherwise every comparison is against a colour that is in the
        // sheet nowhere.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        SolidPainter painter = new SolidPainter();
        painter.begin(Java2DTarget.unsized(g), eye, lvl, 0);
        painter.terrain();
        painter.flush();
        g.dispose();
        return frame;
    }

    /**
     * Which quadrant of the sheet the floor carries at a pixel, worked out from
     * the perspective rather than from the painter: the ray through the pixel,
     * where it strikes {@code z = 0}, and which quarter of that tile it is in.
     *
     * @return the colour, or zero where the ray misses the floor, leaves the
     *         level, or lands too far off to be judged against the fog
     */
    private static int floorColourAt(EyeCamera eye, int px, int py) {
        if (px < 0 || py < 0 || px >= W || py >= H) return 0;
        double f = eye.focal();
        double right = (px + 0.5 - eye.centreX()) / f;
        double high = -(py + 0.5 - eye.centreY()) / f;
        // The inverse of EyeCamera#toEye, on a ray of depth one.
        double sinPitch = Math.sin(eye.pitch()), cosPitch = Math.cos(eye.pitch());
        double sinYaw = Math.sin(eye.yaw()), cosYaw = Math.cos(eye.yaw());
        double forward = -high * sinPitch + cosPitch;
        double dz = high * cosPitch + sinPitch;
        double dx = right * cosYaw + forward * sinYaw;
        double dy = right * sinYaw - forward * cosYaw;
        if (dz >= -1e-9) return 0; // level with the eye or above it: no floor

        double t = -eye.z() / dz; // the floor's top face is z = 0
        double wx = eye.x() + t * dx, wy = eye.y() + t * dy;
        if (Math.hypot(wx - eye.x(), wy - eye.y()) > COMPARE_TILES * TILE) return 0;
        int col = (int) Math.floor(wx / TILE), row = (int) Math.floor(wy / TILE);
        if (col < 0 || row < 0 || col >= 12 || row >= 12) return 0;
        double u = wx / TILE - col, v = wy / TILE - row;
        return QUADRANTS[v < 0.5 ? (u < 0.5 ? 0 : 1) : (u < 0.5 ? 3 : 2)];
    }

    /** Which of the sheet's four colours a drawn pixel is closest to. */
    private static int nearest(int rgb) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int candidate : QUADRANTS) {
            long dr = ((rgb >> 16) & 0xFF) - ((candidate >> 16) & 0xFF);
            long dg = ((rgb >> 8) & 0xFF) - ((candidate >> 8) & 0xFF);
            long db = (rgb & 0xFF) - (candidate & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** One block on an empty floorless level, so one face is one count. */
    private static Level oneBlock() {
        Level lvl = Level.empty("texture-map-one", 3, 8, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        GameProfile settings = new GameProfile("texture-map-one");
        settings.verticality = true;
        lvl.settings = settings;
        lvl.setTile(1, 1, 1, lvl.blocks.get("grass").id());
        return lvl;
    }

    /** How many times the sheet is put down for a frame of {@code lvl}. */
    private static int blits(Level lvl, double x, double y, double z, double pitchDegrees) {
        return blits(lvl, x, y, z, 0, Math.toRadians(pitchDegrees));
    }

    private static int blits(Level lvl, double x, double y, double z,
                             double yaw, double pitch) {
        EyeCamera eye = new EyeCamera(W, H);
        eye.place(x, y, z);
        eye.look(yaw, pitch);
        RecordingTarget target = new RecordingTarget(W, H);
        SolidPainter painter = new SolidPainter();
        painter.begin(target, eye, lvl, 0);
        painter.terrain();
        painter.flush();
        return target.ofType(RecordingTarget.Cmd.Image.class).size();
    }
}
