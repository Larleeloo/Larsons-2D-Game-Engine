package com.larsons.engine;

import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sprite atlas (B5): the packing itself, and the promise that packing a
 * sprite does not change it.
 *
 * <p><b>Two separate things are at stake and they fail differently.</b> A
 * packing bug puts two sprites on top of each other, which is loud and easy to
 * see. A <em>parity</em> bug draws the right sprite one pixel off, or with its
 * alpha composited once too often, in one of the two backends — which looks
 * like nothing at all until somebody compares screenshots. So the packer is
 * checked as geometry, and the drawing is checked by rendering the same sprite
 * both ways and subtracting.
 */
class SpriteAtlasTest {

    @AfterEach
    void restoreRouting() {
        // The shared atlas is process-wide state, and several tests here turn
        // its routing off to measure the difference. Leaving it off would make
        // every test that ran afterwards measure the engine as it was before
        // this step — and pass.
        SpriteAtlas.setRouting(true);
    }

    /** A sprite with a recognisable pattern, so a misplaced copy is visible. */
    private static BufferedImage sprite(int w, int h, Color tint) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(tint);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            g.drawLine(0, 0, w - 1, h - 1);
            // A translucent corner, because SrcOver instead of Src in the copy
            // would round-trip an opaque sprite perfectly and quietly wreck
            // this one.
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(0, 0, Math.max(1, w / 4), Math.max(1, h / 4));
        } finally {
            g.dispose();
        }
        return img;
    }

    // --- packing ---------------------------------------------------------------

    @Test
    void everyRegionLandsInsideItsPageAndOverlapsNoOther() {
        SpriteAtlas atlas = new SpriteAtlas();
        List<SpriteAtlas.Region> placed = new ArrayList<>();
        // Deliberately ragged sizes: a packer that only works on a uniform grid
        // is a packer that works on the test and not on the engine, whose
        // sprites run from 8-pixel pips to 128-pixel unit figures.
        int[] sizes = {8, 13, 16, 24, 31, 32, 48, 64, 7, 96, 12, 40};
        for (int i = 0; i < 200; i++) {
            int w = sizes[i % sizes.length];
            int h = sizes[(i * 5 + 3) % sizes.length];
            SpriteAtlas.Region region = atlas.register("s" + i, sprite(w, h, Color.RED));
            assertNotNull(region, "sprite " + i + " (" + w + "×" + h + ") was not packed");
            placed.add(region);
        }

        for (SpriteAtlas.Region r : placed) {
            assertTrue(r.x() >= SpriteAtlas.PAD && r.y() >= SpriteAtlas.PAD,
                    r + " starts inside the gutter");
            assertTrue(r.x() + r.width() + SpriteAtlas.PAD <= r.page().width(),
                    r + " runs off the right of its page");
            assertTrue(r.y() + r.height() + SpriteAtlas.PAD <= r.page().height(),
                    r + " runs off the bottom of its page");
        }
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                SpriteAtlas.Region a = placed.get(i), b = placed.get(j);
                if (a.page() != b.page()) continue;
                assertFalse(overlap(a, b), a + " overlaps " + b);
            }
        }
    }

    /** Do the two regions share a pixel, gutters included? */
    private static boolean overlap(SpriteAtlas.Region a, SpriteAtlas.Region b) {
        int pad = SpriteAtlas.PAD;
        return a.x() - pad < b.x() + b.width() + pad
                && b.x() - pad < a.x() + a.width() + pad
                && a.y() - pad < b.y() + b.height() + pad
                && b.y() - pad < a.y() + a.height() + pad;
    }

    @Test
    void aPageStartsSmallAndGrowsRatherThanClaimingTheMaximum() {
        // 16 MB allocated to hold a dozen 28-pixel palette swatches would be a
        // memory regression wearing an optimisation's clothes.
        SpriteAtlas atlas = new SpriteAtlas();
        atlas.register("one", sprite(28, 28, Color.BLUE));
        assertEquals(SpriteAtlas.MIN_PAGE, atlas.pages().get(0).width(),
                "one small sprite should not open a full-size page");

        for (int i = 0; i < 400; i++) atlas.register("s" + i, sprite(32, 32, Color.BLUE));
        assertTrue(atlas.pages().get(0).width() > SpriteAtlas.MIN_PAGE,
                "the page never grew, so where did 400 sprites go?");
        assertTrue(atlas.pages().get(0).width() <= SpriteAtlas.MAX_PAGE,
                "a page grew past the size every driver is guaranteed to accept");
    }

    @Test
    void growingAPageLeavesEveryRegionWhereItWas() {
        // The failure this rules out is silent and total: a region holds the
        // page image it was packed into, the page doubles into a new image, and
        // every sprite packed before the growth draws from a texture nothing
        // writes to any more.
        SpriteAtlas atlas = new SpriteAtlas();
        BufferedImage first = sprite(24, 24, Color.GREEN);
        SpriteAtlas.Region region = atlas.register("first", first);
        BufferedImage pageBefore = region.image();

        for (int i = 0; i < 400; i++) atlas.register("s" + i, sprite(32, 32, Color.BLUE));

        assertTrue(region.image() != pageBefore, "the page never grew — the test proves nothing");
        assertPixelsMatch(first, region);
        assertEquals(region.x() / (float) region.page().width(), region.u0(), 1e-6,
                "UVs must be read off the page's current size, not the size it was packed at");
    }

    @Test
    void aSpriteTooLargeToBeWorthPackingIsLeftAlone() {
        SpriteAtlas atlas = new SpriteAtlas();
        int tooBig = SpriteAtlas.MAX_SPRITE + 1;
        assertNull(atlas.register("huge", sprite(tooBig, 16, Color.RED)),
                "a sheet wider than the limit should be turned away, not packed");
        assertEquals(1, atlas.rejectedCount());
        assertEquals(List.of(), atlas.pages(), "nothing was packed, so no page should exist");
    }

    @Test
    void sprintingPastOnePageSpillsOntoAnother() {
        SpriteAtlas atlas = new SpriteAtlas();
        for (int i = 0; i < 60; i++) {
            assertNotNull(atlas.register("big" + i,
                    sprite(SpriteAtlas.MAX_SPRITE, SpriteAtlas.MAX_SPRITE, Color.RED)));
        }
        assertTrue(atlas.pages().size() > 1,
                "a full page must spill onto a second rather than refuse the sprite");
        assertEquals(SpriteAtlas.MAX_PAGE, atlas.pages().get(0).width(),
                "the first page should have grown to the cap before a second opened");
    }

    @Test
    void reBakingASpriteReusesItsSlotInsteadOfConsumingANewOne() {
        // Skins.clearCache() re-slices every sheet on a texture-pack rescan. If
        // each rescan claimed fresh space, a player pressing rescan would walk
        // the atlas out of room and the sprites would silently stop batching.
        SpriteAtlas atlas = new SpriteAtlas();
        SpriteAtlas.Region first = atlas.register("item/wood", sprite(32, 32, Color.RED));
        for (int i = 0; i < 50; i++) {
            BufferedImage rebaked = sprite(32, 32, Color.BLUE);
            SpriteAtlas.Region again = atlas.register("item/wood", rebaked);
            assertEquals(first.x(), again.x(), "the slot moved on a re-bake");
            assertEquals(first.y(), again.y(), "the slot moved on a re-bake");
            assertPixelsMatch(rebaked, again);
        }
        assertEquals(1, atlas.size(),
                "the superseded images are still mapped — a stale one would draw "
                        + "whatever now occupies its old slot");
    }

    // --- parity ----------------------------------------------------------------

    @Test
    void aPackedSpriteHasTheSamePixelsItWentInWith() {
        SpriteAtlas atlas = new SpriteAtlas();
        BufferedImage translucent = sprite(37, 21, new Color(200, 90, 60, 160));
        assertPixelsMatch(translucent, atlas.register("t", translucent));
    }

    @Test
    void everyRegionIsFencedOffByATransparentGutter() {
        // The single most common atlas bug: without the gutter, bilinear
        // filtering on the GPU samples half a texel past the edge and drags the
        // neighbouring sprite in along the seam. Java2D clamps to the source
        // rectangle and never shows it, so no golden frame can catch this —
        // only a check on the pixels themselves, here, and B9's GL parity pass.
        SpriteAtlas atlas = new SpriteAtlas();
        List<SpriteAtlas.Region> regions = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            regions.add(atlas.register("s" + i, sprite(16 + (i % 5) * 8, 24, Color.RED)));
        }
        for (SpriteAtlas.Region r : regions) {
            BufferedImage page = r.image();
            for (int x = r.x() - 1; x <= r.x() + r.width(); x++) {
                assertTransparent(page, x, r.y() - 1, r);
                assertTransparent(page, x, r.y() + r.height(), r);
            }
            for (int y = r.y() - 1; y <= r.y() + r.height(); y++) {
                assertTransparent(page, r.x() - 1, y, r);
                assertTransparent(page, r.x() + r.width(), y, r);
            }
        }
    }

    @Test
    void drawingThroughTheAtlasPutsTheSamePixelsOnTheScreen() {
        // The whole promise of the step in one assertion: routing a draw through
        // a packed page is a change of texture binding, not a change of picture.
        BufferedImage icon = EntitySprites.item(ItemRegistry.standard().get("wood"), 32);
        assertNotNull(SpriteAtlas.regionOf(icon),
                "the item factory no longer registers with the atlas");

        SpriteAtlas.setRouting(false);
        BufferedImage loose = renderIcon(icon);
        SpriteAtlas.setRouting(true);
        BufferedImage packed = renderIcon(icon);

        assertImagesIdentical(loose, packed);
    }

    /** Draw one icon four ways a call site really draws one, onto one surface. */
    private static BufferedImage renderIcon(BufferedImage icon) {
        BufferedImage surface = new BufferedImage(160, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = surface.createGraphics();
        try {
            Java2DTarget target = new Java2DTarget(g, 160, 80);
            target.drawImage(icon, 4, 4);                       // natural size
            target.drawImage(icon, 40, 4, 48, 48);              // scaled up
            target.drawImage(icon, 96, 4, -24, 24);             // mirrored
            target.drawImage(icon, 4, 56, 16, 16, 8, 8, 16, 16); // a sub-rectangle
        } finally {
            g.dispose();
        }
        return surface;
    }

    // --- what it is all for ----------------------------------------------------

    @Test
    void aRunOfDifferentSpritesBecomesOneBatch() {
        // The measurement the GPU case rests on, on the smallest case that
        // shows it: an inventory row of distinct icons was one texture bind per
        // icon and is now one for the row.
        List<BufferedImage> icons = new ArrayList<>();
        for (var def : ItemRegistry.standard().all().subList(0, 5)) {
            icons.add(EntitySprites.item(def, 24));
        }

        SpriteAtlas.setRouting(false);
        DrawStats before = drawRow(icons);
        SpriteAtlas.setRouting(true);
        DrawStats after = drawRow(icons);

        assertEquals(before.operations(), after.operations(),
                "atlasing must not add or drop a single draw");
        assertEquals(icons.size(), before.batches(),
                "before: every icon is its own texture and so its own draw call");
        assertEquals(1, after.batches(),
                "after: one page, one bind, one draw call for the row");
    }

    private static DrawStats drawRow(List<BufferedImage> icons) {
        RecordingTarget target = new RecordingTarget(320, 64);
        for (int i = 0; i < icons.size(); i++) target.drawImage(icons.get(i), i * 24, 8);
        return target.stats();
    }

    @Test
    void theSameSpriteAskedForTwiceIsTheSameObject() {
        // Not a caching nicety — a batching backend keys on the image, so a
        // factory handing back a fresh object each call hands it a texture it
        // has never seen, every frame, for ever. DirectionalSprites.frame() did
        // exactly that before this step.
        BufferedImage first = com.larsons.engine.graphics.DirectionalSprites
                .frame(32, Color.BLUE, com.larsons.engine.graphics.Facing.SOUTH, 1);
        BufferedImage again = com.larsons.engine.graphics.DirectionalSprites
                .frame(32, Color.BLUE, com.larsons.engine.graphics.Facing.SOUTH, 1);

        assertSame(first, again, "a per-call copy cannot be batched and cannot be atlased");
        assertNotNull(SpriteAtlas.regionOf(first), "the player's own sprite is not atlased");
    }

    @Test
    void turningTheAtlasOffPutsEveryDrawBackTheWayItWas() {
        // The escape hatch has to be real: -Dlarsons.render.atlas=false is what
        // a player with a driver bug is told to try, and what the before-and-
        // after measurement in RENDER_PLAN.md switches between.
        BufferedImage icon = EntitySprites.block(
                com.larsons.engine.level.Level.empty("t", 4, 4, 32).blocks.get("stone"), 24);

        SpriteAtlas.setRouting(false);
        RecordingTarget off = new RecordingTarget(64, 64);
        off.drawImage(icon, 0, 0);
        assertEquals("drawImage", off.ops().get(0), "routing is off; the loose image should draw");

        SpriteAtlas.setRouting(true);
        RecordingTarget on = new RecordingTarget(64, 64);
        on.drawImage(icon, 0, 0);
        assertEquals("drawRegion", on.ops().get(0), "routing is on; the page should draw");
    }

    // --- helpers ---------------------------------------------------------------

    private static void assertTransparent(BufferedImage page, int x, int y,
                                          SpriteAtlas.Region region) {
        if (x < 0 || y < 0 || x >= page.getWidth() || y >= page.getHeight()) return;
        assertEquals(0, page.getRGB(x, y) >>> 24,
                "the gutter at (" + x + "," + y + ") beside " + region + " is not transparent");
    }

    private static void assertPixelsMatch(BufferedImage sprite, SpriteAtlas.Region region) {
        assertNotNull(region);
        BufferedImage page = region.image();
        for (int y = 0; y < sprite.getHeight(); y++) {
            for (int x = 0; x < sprite.getWidth(); x++) {
                assertEquals(sprite.getRGB(x, y), page.getRGB(region.x() + x, region.y() + y),
                        "packed pixel (" + x + "," + y + ") differs");
            }
        }
    }

    private static void assertImagesIdentical(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertEquals(a.getRGB(x, y), b.getRGB(x, y),
                        "drawing through the atlas changed pixel (" + x + "," + y + ")");
            }
        }
    }
}
