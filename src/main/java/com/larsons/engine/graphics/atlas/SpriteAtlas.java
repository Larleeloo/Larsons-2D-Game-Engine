package com.larsons.engine.graphics.atlas;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One texture holding many sprites, so consecutive sprite draws stop changing
 * texture.
 *
 * <p><b>Why this is the step the GPU case rests on.</b> A batching backend pays
 * per state change, not per call: it keeps appending quads to one vertex buffer
 * until something forces a flush, and the commonest thing that forces one is a
 * different texture. {@code DrawStats} already measures that as the merge ratio,
 * and the measurement said the world batches ~30× while the screens that draw
 * sprites — inventories, palettes, unit rows — batch barely at all, because
 * every icon is its own {@link BufferedImage} and therefore its own bind. A GL
 * backend built on that command stream would issue as many binds as Java2D
 * issues blits and win nothing. Packing those icons into one page makes the run
 * of draws share a texture, and the run collapses into one call.
 *
 * <p><b>Why not sort draws by texture instead.</b> It would achieve the same
 * merging and it is <em>unsafe</em> here: sprites overlap, the engine draws
 * back-to-front, and reordering overlapping draws under a painter's algorithm
 * changes what the player sees. Atlasing merges without reordering anything.
 *
 * <p><b>Atlasing is additive.</b> Registering a sprite copies its pixels into a
 * page; the original image stays valid and stays drawable. Nothing downstream
 * has to change hands, no factory has to change its return type, and any draw
 * the atlas cannot express — a warped {@code AffineTransform} blit, a sprite
 * too large to be worth a page — simply draws the original exactly as before.
 * That is what makes this step safe to land in one commit: the worst case is
 * the behaviour that already shipped.
 *
 * <p><b>Padding and UVs.</b> Every region is placed with a transparent gutter
 * of {@link #PAD} pixels on all four sides, and {@link Region}'s UVs cover the
 * sprite alone. Without the gutter, bilinear filtering on the GPU samples half
 * a texel past the edge and bleeds the neighbouring sprite in along the seam —
 * the single most common atlas bug, invisible on the Java2D path because Java2D
 * clamps to the source rectangle, and therefore not something the golden frames
 * can catch. The gutter is what makes the two backends agree; B9's GL parity
 * check is what proves it.
 *
 * <p><b>Pages grow rather than starting large.</b> A page is capped at
 * {@link #MAX_PAGE}×{@link #MAX_PAGE}, which is the size every target device
 * including the M1 Air accepts without question, but it starts at
 * {@link #MIN_PAGE} and doubles on demand. Allocating 16 MB up front to hold a
 * dozen 28-pixel palette swatches would be a memory regression dressed as an
 * optimisation.
 *
 * <p>Thread-safe. Packing is guarded; lookup is a lock-free read, because it
 * happens once per sprite draw and the render thread does thousands of those a
 * frame.
 */
public final class SpriteAtlas {

    /**
     * The largest page any backend will be asked to hold, per side.
     *
     * <p>2048 is the floor of what every target device supports — GL 3.3 core
     * guarantees at least 1024 and every real driver offers far more, but 2048
     * is the number that is true everywhere without a query. Spilling into a
     * second page costs one extra bind; a driver refusing the texture costs the
     * frame.
     */
    public static final int MAX_PAGE = 2048;

    /** A page's starting size, doubled on demand up to {@link #MAX_PAGE}. */
    public static final int MIN_PAGE = 256;

    /** Transparent gutter around every region, in pixels. See the class note. */
    public static final int PAD = 1;

    /**
     * Sprites larger than this per side are left out of the atlas.
     *
     * <p>Not a limitation — a judgement about what atlasing is for. The point
     * is to amortise a texture bind across many small draws; a 512-pixel sheet
     * already amortises its own bind, and packing it would eat a quarter of a
     * page to save nothing. Anything over this draws exactly as it did before.
     */
    public static final int MAX_SPRITE = 256;

    /**
     * How many pages this atlas will open before it stops taking sprites.
     *
     * <p>A backstop, not a budget. Reaching it means a game has more unique
     * generated art than an atlas built at load can usefully hold, and the
     * honest response is to stop growing and let the rest draw loose — which is
     * exactly the behaviour that shipped before this class existed.
     */
    public static final int MAX_PAGES = 4;

    // --- the shared atlas ------------------------------------------------------

    private static final SpriteAtlas SHARED = new SpriteAtlas();

    /**
     * Whether draws resolve a loose image to its region.
     *
     * <p>Separate from registration on purpose, and the separation is what
     * makes the before-and-after numbers in {@code RENDER_PLAN.md} worth
     * anything: flipping this switches the whole engine between "every sprite
     * is its own texture" and "sprites share a page" <em>in one process, on one
     * build, over the same frames</em>, with nothing else different and no
     * cache to invalidate. Gating registration instead would make the two
     * measurements depend on which ran first, since the factories bake lazily
     * and cache for ever.
     *
     * <p>{@code -Dlarsons.render.atlas=false} is also the one-flag workaround
     * for a player whose driver dislikes the packed path, in the same spirit as
     * {@code -Dlarsons.render.direct}.
     */
    private static volatile boolean routing =
            !"false".equalsIgnoreCase(System.getProperty("larsons.render.atlas", "true"));

    /** The atlas the engine's bake-time sprite factories register into. */
    public static SpriteAtlas shared() { return SHARED; }

    /**
     * The shared atlas's region for {@code image}, or {@code null} if it has
     * none — the lookup every {@code DrawTarget} does before drawing a sprite.
     */
    public static Region regionOf(BufferedImage image) {
        return routing ? SHARED.region(image) : null;
    }

    /** Whether draws currently resolve through the atlas. */
    public static boolean routing() { return routing; }

    /** Turn region routing on or off. See the field's note on why it exists. */
    public static void setRouting(boolean enabled) { routing = enabled; }

    // --- a page ----------------------------------------------------------------

    /**
     * One packed image: what a GL backend uploads once and binds once.
     *
     * <p>Mutable, and referenced by every {@link Region} on it, because a page
     * grows: the image object is replaced when it doubles, and a region holding
     * the old one would draw from a texture nothing writes to any more. Regions
     * therefore ask the page for its current image and current size, which also
     * means their UVs stay correct across a growth they never hear about.
     */
    public static final class Page {

        /**
         * The page's pixels and their dimensions, as one immutable value.
         *
         * <p>Three separate fields would be three separate reads, and growing
         * writes all three — so a draw racing a growth could pair the new image
         * with the old width and compute UVs for a texture that no longer has
         * those proportions. Publishing them together means a reader sees
         * either the whole old page or the whole new one, and both are
         * internally consistent.
         */
        private record Surface(BufferedImage image, int width, int height) {}

        private final int index;

        /** Written under the atlas's lock, read without one on the draw path. */
        private volatile Surface surface;

        /** Skyline segments, left to right: {@code {x, y, width}}, y = top of the run. */
        private final List<int[]> skyline = new ArrayList<>();

        private long usedPixels;

        private Page(int index, int size) {
            this.index = index;
            this.surface = new Surface(blank(size, size), size, size);
            skyline.add(new int[]{0, 0, size});
        }

        /** Which page this is in its atlas — the GL backend's texture slot. */
        public int index() { return index; }

        /** The packed pixels. Replaced when the page grows; never mutated in place. */
        public BufferedImage image() { return surface.image(); }

        public int width() { return surface.width(); }

        public int height() { return surface.height(); }

        /** Fraction of the page's area occupied by sprites and their gutters. */
        public double occupancy() {
            Surface s = surface;
            return usedPixels / (double) ((long) s.width() * s.height());
        }

        @Override
        public String toString() {
            return "page %d %d×%d, %.0f%% full"
                    .formatted(index, width(), height(), occupancy() * 100);
        }
    }

    // --- a region --------------------------------------------------------------

    /**
     * Where one sprite lives on a page.
     *
     * <p>Carries both forms deliberately. The pixel rectangle is what Java2D
     * needs — {@code drawImage} with a source rect is the same blit it was
     * already doing. The normalised UVs are what a GL backend needs, and they
     * are computed from the page's <em>current</em> size rather than stored, so
     * a page that doubles does not silently halve every UV on it.
     *
     * <p>The UVs address the sprite exactly, not the gutter. A GL backend
     * should still sample with clamping: a quad drawn at a non-integer scale
     * can ask for half a texel past the edge, and the gutter is what makes that
     * transparent instead of the sprite next door.
     */
    public record Region(Page page, int x, int y, int width, int height) {

        /** The page image to bind or blit from. */
        public BufferedImage image() { return page.image(); }

        public float u0() { return x / (float) page.surface.width(); }

        public float v0() { return y / (float) page.surface.height(); }

        public float u1() { return (x + width) / (float) page.surface.width(); }

        public float v1() { return (y + height) / (float) page.surface.height(); }

        @Override
        public String toString() {
            return "region %d×%d at (%d,%d) on page %d"
                    .formatted(width, height, x, y, page.index());
        }
    }

    // --- state -----------------------------------------------------------------

    private final List<Page> pages = new ArrayList<>();

    /**
     * Sprite image to its region.
     *
     * <p>A {@link ConcurrentHashMap} keyed by the image itself, which is
     * identity-keyed in practice: {@link BufferedImage} inherits {@code equals}
     * and {@code hashCode} from {@code Object}, so two distinct images never
     * collide however alike they look. That is the behaviour wanted — the
     * question being asked is "is this the very image I packed?" — and it comes
     * with lock-free reads on the draw path, which an {@code IdentityHashMap}
     * behind a lock would not.
     */
    private final Map<BufferedImage, Region> byImage = new ConcurrentHashMap<>();

    /** Cache key to its region, so a re-baked sprite reuses its slot. */
    private final Map<String, Region> byKey = new LinkedHashMap<>();

    private int rejected;

    /** A private atlas — tests build these; the engine uses {@link #shared()}. */
    public SpriteAtlas() {}

    // --- registration ----------------------------------------------------------

    /**
     * Pack {@code sprite} and remember it under {@code key}, returning where it
     * landed, or {@code null} if it could not be packed (too large, or the
     * atlas is full). A {@code null} answer is not a failure: the caller keeps
     * the loose image and every draw of it behaves exactly as before.
     *
     * <p>Registering a key twice — which is what happens when a sprite cache is
     * cleared and re-baked, as {@code Skins.clearCache()} does on a texture-pack
     * rescan — reuses the slot when the size matches rather than consuming a new
     * one, so repeated rescans cannot walk the atlas out of space. The previous
     * image's mapping is dropped in that case: anything still holding it draws
     * loose, which is correct, rather than drawing from a slot that now holds
     * somebody else's pixels.
     */
    public synchronized Region register(String key, BufferedImage sprite) {
        if (sprite == null) return null;
        int w = sprite.getWidth();
        int h = sprite.getHeight();
        if (w <= 0 || h <= 0 || w > MAX_SPRITE || h > MAX_SPRITE) {
            rejected++;
            return null;
        }

        Region existing = key == null ? null : byKey.get(key);
        if (existing != null && existing.width() == w && existing.height() == h) {
            byImage.values().removeIf(existing::equals);
            blit(sprite, existing);
            byImage.put(sprite, existing);
            return existing;
        }

        Region region = pack(w, h);
        if (region == null) {
            rejected++;
            return null;
        }
        blit(sprite, region);
        byImage.put(sprite, region);
        if (key != null) byKey.put(key, region);
        return region;
    }

    /** {@link #register(String, BufferedImage)} for a sprite with no cache key. */
    public Region register(BufferedImage sprite) {
        return register(null, sprite);
    }

    /** Where {@code sprite} was packed, or {@code null} if it never was. */
    public Region region(BufferedImage sprite) {
        return sprite == null ? null : byImage.get(sprite);
    }

    /** Where the sprite registered under {@code key} was packed. */
    public synchronized Region regionForKey(String key) {
        return byKey.get(key);
    }

    /** Every page, in the order they were opened. */
    public synchronized List<Page> pages() { return List.copyOf(pages); }

    /**
     * How many regions are packed.
     *
     * <p>Sprites, and since B6 glyph cells too — {@link GlyphAtlas} packs into
     * these same pages rather than opening a parallel atlas, because two
     * atlases would leave an icon-then-label row flushing between every pair.
     * Nothing here knows or needs to know which a region is.
     */
    public int size() { return byImage.size(); }

    /** How many sprites were offered and turned away — too large, or no room. */
    public synchronized int rejectedCount() { return rejected; }

    /** Forget everything, freeing the pages. Tests use this; the engine does not. */
    public synchronized void clear() {
        pages.clear();
        byImage.clear();
        byKey.clear();
        rejected = 0;
    }

    @Override
    public synchronized String toString() {
        if (pages.isEmpty()) return "empty atlas";
        StringBuilder sb = new StringBuilder(
                "%d regions over %d page%s".formatted(byImage.size(), pages.size(),
                        pages.size() == 1 ? "" : "s"));
        for (Page page : pages) sb.append(" [").append(page).append(']');
        if (rejected > 0) sb.append(", ").append(rejected).append(" not packed");
        return sb.toString();
    }

    // --- packing ---------------------------------------------------------------

    /** Find room for a {@code w}×{@code h} sprite plus its gutter, growing if need be. */
    private Region pack(int w, int h) {
        int need = PAD * 2;
        for (Page page : pages) {
            Region placed = place(page, w + need, h + need, w, h);
            if (placed != null) return placed;
        }
        // Nothing fits as things stand. Grow the newest page before opening
        // another: a second page is a second bind for ever, and doubling the
        // one already open costs a copy once. Only the newest is worth growing —
        // the pages behind it are the ones that already ran out of room.
        if (!pages.isEmpty()) {
            Page newest = pages.get(pages.size() - 1);
            while (grow(newest)) {
                Region placed = place(newest, w + need, h + need, w, h);
                if (placed != null) return placed;
            }
        }
        if (pages.size() >= MAX_PAGES) return null;

        int start = MIN_PAGE;
        while (start < w + need || start < h + need) start *= 2;
        if (start > MAX_PAGE) return null;
        Page page = new Page(pages.size(), start);
        pages.add(page);
        return place(page, w + need, h + need, w, h);
    }

    /**
     * Skyline bottom-left: put the box where its top edge sits lowest, and as
     * far left as that allows. Cheap, incremental, and good enough — the
     * alternative is sorting everything by height first, which a factory that
     * bakes on demand cannot do because it does not know what is coming.
     */
    private Region place(Page page, int boxW, int boxH, int spriteW, int spriteH) {
        int bestIndex = -1, bestY = Integer.MAX_VALUE, bestX = Integer.MAX_VALUE;
        for (int i = 0; i < page.skyline.size(); i++) {
            int y = fit(page, i, boxW);
            if (y < 0 || y + boxH > page.height()) continue;
            int x = page.skyline.get(i)[0];
            if (y < bestY || (y == bestY && x < bestX)) {
                bestIndex = i;
                bestY = y;
                bestX = x;
            }
        }
        if (bestIndex < 0) return null;

        raise(page, bestIndex, bestX, bestY, boxW, boxH);
        page.usedPixels += (long) boxW * boxH;
        return new Region(page, bestX + PAD, bestY + PAD, spriteW, spriteH);
    }

    /**
     * The lowest y a {@code boxW}-wide box can sit at, starting from segment
     * {@code from}, or {@code -1} if it runs off the page. The box rests on the
     * highest segment it spans, which is what keeps the skyline a skyline.
     */
    private static int fit(Page page, int from, int boxW) {
        List<int[]> skyline = page.skyline;
        int x = skyline.get(from)[0];
        if (x + boxW > page.width()) return -1;
        int y = 0;
        int left = boxW;
        for (int i = from; i < skyline.size() && left > 0; i++) {
            y = Math.max(y, skyline.get(i)[1]);
            left -= skyline.get(i)[2];
        }
        return left > 0 ? -1 : y;
    }

    /** Record that a box now occupies {@code (x, y, w, h)}, and tidy the skyline. */
    private static void raise(Page page, int index, int x, int y, int w, int h) {
        List<int[]> skyline = page.skyline;
        skyline.add(index, new int[]{x, y + h, w});

        // Trim or drop whatever the new segment now covers.
        for (int i = index + 1; i < skyline.size(); ) {
            int[] seg = skyline.get(i);
            int[] previous = skyline.get(i - 1);
            if (seg[0] >= previous[0] + previous[2]) break;
            int overlap = previous[0] + previous[2] - seg[0];
            if (overlap >= seg[2]) {
                skyline.remove(i);
            } else {
                seg[0] += overlap;
                seg[2] -= overlap;
                break;
            }
        }
        // Merge runs at the same height, or the list grows without bound.
        for (int i = 0; i < skyline.size() - 1; ) {
            int[] a = skyline.get(i);
            int[] b = skyline.get(i + 1);
            if (a[1] == b[1]) {
                a[2] += b[2];
                skyline.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /**
     * Double the page's smaller side, up to {@link #MAX_PAGE}. Everything
     * already packed keeps its coordinates, so no region has to be told.
     */
    private static boolean grow(Page page) {
        Page.Surface was = page.surface;
        boolean widen = was.width() <= was.height();
        int newWidth = widen ? was.width() * 2 : was.width();
        int newHeight = widen ? was.height() : was.height() * 2;
        if (newWidth > MAX_PAGE || newHeight > MAX_PAGE) return false;

        BufferedImage bigger = blank(newWidth, newHeight);
        Graphics2D g = bigger.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(was.image(), 0, 0, null);
        } finally {
            g.dispose();
        }
        // The skyline tiles [0, width), so widening leaves a strip nothing
        // knows about. Growing downward needs no such repair: the existing
        // segments already describe the whole width, just with more room above
        // the floor.
        if (widen) {
            int[] last = page.skyline.get(page.skyline.size() - 1);
            if (last[1] == 0) {
                last[2] += newWidth - was.width();
            } else {
                page.skyline.add(new int[]{was.width(), 0, newWidth - was.width()});
            }
        }
        page.surface = new Page.Surface(bigger, newWidth, newHeight);
        return true;
    }

    /**
     * Copy the sprite in with {@link AlphaComposite#Src}, not the default
     * {@code SrcOver}: the page starts transparent and the sprite's own alpha
     * has to survive the copy verbatim, or every translucent pixel in the
     * engine comes back slightly wrong through the atlas and nowhere else.
     */
    private static void blit(BufferedImage sprite, Region region) {
        Graphics2D g = region.image().createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(sprite, region.x(), region.y(), null);
        } finally {
            g.dispose();
        }
        // The page's pixels are not what they were. Anything holding a copy of
        // them — a GL texture, most obviously — has to be told, and this is the
        // only place they change without the image object changing with them.
        // Growing does not need the same call: it swaps in a new image, and a
        // cache keyed on the image misses that by itself.
        com.larsons.engine.graphics.draw.ImageRevision.changed(region.image());
    }

    private static BufferedImage blank(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
}
