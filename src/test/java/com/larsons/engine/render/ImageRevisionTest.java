package com.larsons.engine.render;

import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.graphics.draw.ImageRevision;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image repainted in place says so, for backends that keep a copy.
 *
 * <p><b>This exists because the absence of it drew the wrong level.</b> Java2D
 * blits from the {@link BufferedImage}, so it sees every write; a GPU backend
 * uploads once and draws from a texture, and two places in this engine rewrite
 * an image rather than allocating a new one — the sprite atlas packing a page
 * lazily, and {@code TerrainCache} rebuilding a chunk into the image it already
 * has. B8's parity comparison caught the second at 14.67 mean error on
 * `scene-play`, which was the GL backend faithfully drawing terrain from a
 * level three frames earlier.
 *
 * <p>The checks below are on the mechanism rather than on the backend, because
 * the backend's test needs a driver and this one does not — and because the
 * property that has to hold is the announcement, which is core's job.
 */
class ImageRevisionTest {

    @Test
    void anUntouchedImageIsAtRevisionZero() {
        assertEquals(0, ImageRevision.of(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)));
        assertEquals(0, ImageRevision.of(null), "a null image must not throw");
    }

    @Test
    void everyAnnouncementMovesTheImageAndTheEpoch() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        int epoch = ImageRevision.epoch();
        ImageRevision.changed(image);
        assertEquals(1, ImageRevision.of(image));
        assertTrue(ImageRevision.epoch() > epoch,
                "the epoch is what lets a backend skip the per-image lookup; "
                        + "it has to move or the skip is permanent");
        ImageRevision.changed(image);
        assertEquals(2, ImageRevision.of(image));
    }

    @Test
    void imagesAreTrackedByIdentityNotByContent() {
        BufferedImage one = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        BufferedImage two = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ImageRevision.changed(one);
        assertEquals(0, ImageRevision.of(two),
                "two images that look alike are not the same image, and a backend "
                        + "keyed on identity must not be told otherwise");
    }

    /**
     * The atlas is the case that matters most, because a page is drawn from on
     * every frame and packed into on the first frame that needs a new glyph.
     */
    @Test
    void packingASpriteAnnouncesThePageItLandedOn() {
        SpriteAtlas atlas = new SpriteAtlas();
        SpriteAtlas.Region first = atlas.register("one", solid(8, 8));
        assertNotNull(first, "the fixture needs a packed sprite to say anything");
        BufferedImage page = first.image();
        int before = ImageRevision.of(page);

        atlas.register("two", solid(8, 8));
        assertTrue(ImageRevision.of(page) > before,
                "a second sprite was blitted into the page and nothing was told — "
                        + "a GL texture uploaded before it would never show it");
    }

    private static BufferedImage solid(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) image.setRGB(x, y, 0xFF808080);
        }
        return image;
    }
}
