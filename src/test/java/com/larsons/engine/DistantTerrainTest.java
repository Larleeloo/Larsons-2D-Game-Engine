package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.SolidPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.ui.PlayerOptionsForm;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setting that draws the world past the view distance — what Distant
 * Horizons does for Minecraft, and what a player asked for here.
 *
 * <p><b>What is worth pinning.</b> Not that it draws something: that the
 * horizon is <em>continuous</em>. The interesting failure of a coarse pass is
 * not that it is coarse, it is the ring of bare sky it leaves between the
 * terrain you are standing on and the terrain on the horizon — the two passes
 * cover a round radius with square groups, and a group that straddles the
 * circle belongs to neither unless someone says so. So the test renders the
 * band of the frame just under the horizon and asks whether every column of it
 * has ground in it, against the same frame with the setting off, which does not.
 */
class DistantTerrainTest {

    private static final int TILE = 32;
    private static final int W = 480, H = 360;
    /** How far under the horizon the band starts, in pixels. */
    private static final int BAND_TOP = 15;
    private static final int BAND_BOTTOM = 45;

    /**
     * With the setting on, the ground runs all the way to the horizon; with it
     * off, it stops at the view distance and the rest is sky.
     */
    @Test
    void theGroundReachesTheHorizonOnlyWhenItIsTurnedOn() {
        Level lvl = plain(300);
        int skyWithout = skyPixelsInBand(lvl, 0);
        int skyWith = skyPixelsInBand(lvl, SolidPainter.DISTANT_VIEW_TILES);

        assertTrue(skyWithout > 1000,
                "with the setting off the world should end well short of the horizon,"
                        + " or this test is measuring nothing; saw " + skyWithout);
        assertEquals(0, skyWith,
                skyWith + " pixels of bare sky under the horizon — the coarse pass has"
                        + " left a gap where its square groups meet a round radius");
    }

    /** Off, it costs nothing at all: the pass queues not one face. */
    @Test
    void turnedOffItDrawsNothing() {
        Level lvl = plain(300);
        RecordingTarget quiet = record(lvl, 0);
        RecordingTarget loud = record(lvl, SolidPainter.DISTANT_VIEW_TILES);
        assertTrue(loud.count("fillPolygon") > quiet.count("fillPolygon"),
                "the coarse pass should add faces when it is on");

        // …and the detailed pass is untouched either way: what the near terrain
        // draws must not depend on a setting about the far terrain.
        SolidPainter painter = new SolidPainter();
        painter.setDistantTiles(SolidPainter.DISTANT_VIEW_TILES);
        RecordingTarget detail = new RecordingTarget(W, H);
        painter.begin(detail, eye(lvl), lvl, 0);
        painter.terrain();
        painter.flush();
        assertEquals(quiet.count("fillPolygon"), detail.count("fillPolygon"),
                "terrain() draws the same thing whether or not distant() follows it");
    }

    /**
     * A coarse box never lands on top of the detailed terrain.
     *
     * <p>This is the failure that would matter: the two passes overlap by a
     * ring on purpose — square groups cannot tile a round radius without it —
     * and if one of those boxes sorted in front of the ground at the player's
     * feet, the player would be standing on a grey slab. Compared against the
     * same frame with the coarse pass simply not run, so the near terrain, the
     * fog and the light are identical and the only thing that can have changed
     * a pixel the detailed pass drew is a box drawn over it.
     *
     * <p>The cell ordering gets this right on its own for the scenes here, and
     * {@code DISTANT_ORDER} is what makes it right for the ones it does not:
     * a group's sort key is its centre cell's, and a group is drawn whenever
     * any part of it is outside the detailed radius, so a box can carry a key
     * from inside a ring it is mostly outside of. This test would not catch
     * that on its own — it is here to catch the visible fault, whatever caused
     * it.
     */
    @Test
    void aCoarseBoxIsNeverDrawnOverTheGroundUnderfoot() {
        // Rolling rather than flat, so the boxes near the boundary have height
        // to put in front of something: on a plain they are all lids at z = 0
        // and could not cover anything even if they were sorted wrongly.
        Level lvl = rolling(300);
        BufferedImage both = frame(lvl, SolidPainter.DISTANT_VIEW_TILES, true);
        BufferedImage detailOnly = frame(lvl, SolidPainter.DISTANT_VIEW_TILES, false);
        int horizon = (int) Math.round(eye(lvl).horizonY());
        // Everything from well below the horizon down: the detailed sweep's own
        // ground, all of which it drew in both frames.
        // Only where the detailed pass actually drew: a pixel it left as sky is
        // one the coarse pass is *supposed* to fill, which is the whole point of
        // it, and counting those would be measuring the feature as a fault.
        BufferedImage bare = frame(Level.empty("nothing", 4, 4, TILE), 0);
        int changed = 0;
        for (int y = horizon + 40; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (detailOnly.getRGB(x, y) == bare.getRGB(x, y)) continue;
                if (both.getRGB(x, y) != detailOnly.getRGB(x, y)) changed++;
            }
        }
        assertEquals(0, changed,
                changed + " pixels of the near ground were repainted by the coarse pass");
    }

    /** The player's own setting reaches the painter, and starts off. */
    @Test
    void theSettingIsAPlayerSettingAndIsOffByDefault() {
        PlayerSettings fresh = new PlayerSettings();
        assertFalse(fresh.distantTerrain,
                "the machine that needs this setting is the one that cannot afford it on");

        fresh.distantTerrain = true;
        assertTrue(PlayerSettings.fromMap(fresh.toMap()).distantTerrain,
                "and it has to survive being saved, or it is a setting for one session");

        assertNotNull(PlayerOptionsForm.build("Options", fresh, null, null)
                        .options().stream()
                        .filter(i -> i.label().toLowerCase().contains("distant"))
                        .findFirst().orElse(null),
                "…and be on the options screen, which is where a player was told to look");
    }

    // --- fixtures ------------------------------------------------------------------

    /** A flat plain wider than the coarse pass can see across. */
    private static Level plain(int size) {
        Level lvl = Level.empty("distant", size, size, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        lvl.fillFloor(lvl.blocks.get("grass").id());
        GameProfile settings = new GameProfile("distant");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    /**
     * The same plain with a range of hills standing on it out past the detailed
     * distance — so the coarse boxes have height to put in front of something,
     * while the ground the test looks at stays flat and covered.
     */
    private static Level rolling(int size) {
        Level lvl = plain(size);
        int stone = lvl.blocks.get("stone").id();
        int mid = size / 2;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (Math.hypot(c - mid, r - mid) < 26) continue; // near ground: flat
                int h = 2 + (int) Math.round(2 * Math.sin(c / 7.0) * Math.cos(r / 6.0));
                for (int layer = 1; layer <= h; layer++) lvl.setTile(c, r, layer, stone);
            }
        }
        return lvl;
    }

    private static EyeCamera eye(Level lvl) {
        EyeCamera eye = new EyeCamera(W, H);
        eye.place(lvl.width / 2.0 * TILE, lvl.height / 2.0 * TILE, 3 * TILE);
        eye.look(0, 0); // level, so the horizon is the middle row exactly
        return eye;
    }

    private static RecordingTarget record(Level lvl, int distantTiles) {
        RecordingTarget target = new RecordingTarget(W, H);
        SolidPainter painter = new SolidPainter();
        painter.setDistantTiles(distantTiles);
        painter.begin(target, eye(lvl), lvl, 0);
        painter.terrain();
        painter.distant();
        painter.flush();
        return target;
    }

    private static BufferedImage frame(Level lvl, int distantTiles) {
        return frame(lvl, distantTiles, true);
    }

    private static BufferedImage frame(Level lvl, int distantTiles, boolean coarse) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        SolidPainter painter = new SolidPainter();
        painter.setDistantTiles(distantTiles);
        painter.begin(Java2DTarget.unsized(g), eye(lvl), lvl, 0);
        painter.terrain();
        if (coarse) painter.distant();
        painter.flush();
        g.dispose();
        return img;
    }

    /**
     * How many pixels of the band under the horizon are still sky — measured
     * against the same frame with nothing in the level at all, so "sky" means
     * the backdrop this camera actually painted rather than a colour guessed at.
     */
    private static int skyPixelsInBand(Level lvl, int distantTiles) {
        BufferedImage drawn = frame(lvl, distantTiles);
        BufferedImage bare = frame(Level.empty("nothing", 4, 4, TILE), 0);
        int horizon = (int) Math.round(eye(lvl).horizonY());
        int sky = 0;
        for (int y = horizon + BAND_TOP; y <= Math.min(H - 1, horizon + BAND_BOTTOM); y++) {
            for (int x = 0; x < W; x++) {
                if (drawn.getRGB(x, y) == bare.getRGB(x, y)) sky++;
            }
        }
        return sky;
    }

}
