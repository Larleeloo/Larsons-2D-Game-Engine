package com.larsons.engine.graphics.draw;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A count of how many times an image's pixels have been rewritten, for backends
 * that keep a copy of them.
 *
 * <p><b>Why this has to exist, and why B8 is where it was found.</b> Java2D
 * draws from a {@link BufferedImage} directly, so it sees every write to one
 * the instant it happens and nothing in this engine ever had to announce a
 * repaint. A GPU backend uploads the pixels to a texture and draws from
 * <em>that</em>, and the copy does not update itself. Two places in the engine
 * repaint an image in place rather than allocating a new one, both for good
 * reasons:
 *
 * <ul>
 *   <li>{@link com.larsons.engine.graphics.atlas.SpriteAtlas} packs sprites and
 *       glyph cells into a page lazily, on the first frame that draws them.</li>
 *   <li>{@link com.larsons.engine.graphics.TerrainCache} rebuilds a chunk into
 *       the image it already has, because at a third of a megabyte per chunk on
 *       a level with liquids in it, allocating a fresh one each time made the
 *       cache cost more than it saved.</li>
 * </ul>
 *
 * <p>Both are right, and both are invisible to anything holding a copy. Without
 * this, the GL backend draws the terrain the player walked past ten seconds ago
 * — which is exactly what it did, and what the parity comparison caught on
 * {@code scene-play} at 14.67 mean error against a bar of 3.58.
 *
 * <p><b>Cheap to ask, cheap to ignore.</b> {@link #epoch()} moves whenever any
 * image anywhere is repainted, so a backend that remembers the epoch it last
 * checked at can skip the per-image lookup entirely on the overwhelmingly
 * common frame where nothing was rebaked. Announcing a change is a map
 * increment on a path that is already doing a full {@code createGraphics} and
 * a re-render.
 *
 * <p><b>Weakly keyed and identity-based.</b> {@link BufferedImage} inherits
 * {@code equals} from {@code Object}, so a {@link WeakHashMap} of them is an
 * identity map that lets a discarded chunk image go — which matters, because
 * the alternative is a registry that pins every image the engine ever repainted
 * for the life of the process.
 */
public final class ImageRevision {

    private ImageRevision() {}

    private static final Map<BufferedImage, Integer> REVISIONS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final AtomicInteger EPOCH = new AtomicInteger();

    /**
     * Announce that {@code image}'s pixels have been rewritten.
     *
     * <p>Call this from the code that did the rewriting, not from the code that
     * asked for it: the writer is the only one that knows it happened, and a
     * caller that has to remember is a caller that eventually will not.
     */
    public static void changed(BufferedImage image) {
        if (image == null) return;
        REVISIONS.merge(image, 1, Integer::sum);
        EPOCH.incrementAndGet();
    }

    /** How many times {@code image} has been announced as changed. */
    public static int of(BufferedImage image) {
        if (image == null) return 0;
        Integer revision = REVISIONS.get(image);
        return revision == null ? 0 : revision;
    }

    /**
     * A counter that moves whenever any image is repainted — the cheap "has
     * anything at all changed since I last looked?" a per-draw path can afford.
     */
    public static int epoch() { return EPOCH.get(); }

    /** How many images are being tracked. Tests assert this does not grow. */
    public static int tracked() { return REVISIONS.size(); }
}
