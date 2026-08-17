package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.world.Block;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Draws a level's blocks as solid cubes seen through an {@link EyeCamera} —
 * the picture behind the first- and third-person {@link Viewpoint}s.
 *
 * <p><b>What this is, next to {@link TerrainPainter}.</b> That painter draws
 * the same blocks through the flat {@link Camera}: a floor of quads with the
 * stacked columns extruded up the screen, which is the right picture for a plan
 * view and is what every level in this engine was authored through. This one
 * draws the same volume from inside it. The level's data is unchanged and no
 * geometry is duplicated — a column is still a column — but every face is
 * projected individually and divided by its own depth, so a corridor narrows
 * and a wall you walk toward grows. That divide is the only real difference,
 * and it is the whole of why the result reads like Minecraft rather than like a
 * plan view zoomed in.
 *
 * <h2>How a frame is built</h2>
 *
 * <ol>
 *   <li>{@link #begin} paints the sky and empties the queue.</li>
 *   <li>{@link #terrain} sweeps the cells within the view distance and queues
 *       every block face that is <em>exposed</em> (a neighbour does not cover
 *       it) and <em>turned toward the eye</em>.</li>
 *   <li>{@link #billboard} queues an actor — drawn by the scene's ordinary 2D
 *       sprite code, under a transform; see that method.</li>
 *   <li>{@link #flush} sorts the queue far-to-near and draws it.</li>
 * </ol>
 *
 * <h2>Two culls, and why neither is a heuristic</h2>
 *
 * <p><b>Face exposure.</b> A face with a solid block against it can never be
 * seen, so it is never queued. This is what makes the sweep affordable: a
 * hillside of ten thousand blocks has a few hundred exposed faces, and the
 * ninety-odd percent that are buried cost one array read each to reject.
 *
 * <p><b>Back-facing.</b> Of the six faces of a box, the eye can see only those
 * whose outward normal points at it, and for an axis-aligned box that is a
 * comparison rather than a dot product: the top is visible when the eye is
 * above it, the north face when the eye is north of it. Both culls together
 * mean nothing hidden is ever projected.
 *
 * <h2>Depth, without a depth buffer</h2>
 *
 * <p>Requirement #4 says the JDK-only build is the one that must work, and
 * Java2D has no depth buffer — so this is a painter's algorithm, like every
 * other pass in the engine. What it sorts on is the distance from the eye to
 * the <em>nearest point of each face's box</em>, not to the face's centre.
 *
 * <p>That is a deliberate choice and the centre is the tempting wrong one. Side
 * faces are merged over vertical runs (a wall eight blocks tall is one quad,
 * not eight), so a face's centre can be a long way from the part of it that
 * does the occluding: a tall wall beside you has its centre far overhead while
 * its bottom is at your elbow. Distance to the box is distance to whatever part
 * of the face is actually nearest, which is the part that covers things — and
 * for axis-aligned geometry that has already been back-face culled, ordering by
 * it puts an occluder in front of what it occludes.
 *
 * <p>It is not a proof, and this class does not claim one: two faces that
 * interpenetrate on screen at a grazing angle can still be ordered wrongly.
 * Voxel geometry does not interpenetrate, which is why the case does not arise
 * in a level — and the geometry that <em>does</em> risk it, an actor's
 * billboard standing flat against a wall, is sorted on its own anchor and can
 * bleed a pixel or two into the wall behind it at very close range.
 *
 * <h2>Colour, not texture</h2>
 *
 * <p>Faces are filled with the block's own colour, shaded by which way they
 * face and faded into the fog by distance. They are not textured, and the
 * reason is not effort: a perspective quad's texture mapping is not affine, and
 * {@link DrawTarget#drawImage(java.awt.image.BufferedImage, AffineTransform)} —
 * the only warping blit the interface has — is. Splitting each face into
 * triangles and blitting each affinely is the PlayStation-1 answer and it warps
 * visibly at exactly the range you spend the most time at. So a block with a
 * texture pack behind it draws in its registered colour here, and every texture
 * the plan view ever drew it with is untouched — {@link TexturePack} and
 * {@link Skins} are not consulted by this painter at all.
 *
 * <p>Shading is the classic four-level scheme every blocky 3D game uses: the
 * top brightest, north/south next, east/west darker, the underside darkest,
 * which is what makes a cube read as a cube without a single light calculation.
 */
public final class SolidPainter {

    /**
     * How far the eye can see, in tiles.
     *
     * <p>Twenty, which is a little past Minecraft's own "short" render distance
     * and is what this engine can sweep and fill inside a 60 Hz budget through
     * Java2D — 2.8 ms a frame at 1280&times;720 over rolling terrain, measured.
     * It is a distance rather than a cell count because it has to mean the same
     * thing whatever a level's tile size is.
     */
    public static final int DEFAULT_VIEW_TILES = 20;

    /**
     * Where the fog starts, as a fraction of the view distance.
     *
     * <p>Late, and that is the tuning that matters: fog exists to hide the
     * <em>edge</em> of the view distance, not to be an effect. Started early it
     * washes out the wall across the room, and a player reads that as the
     * renderer being bad rather than as weather.
     */
    private static final double FOG_START = 0.72;

    // The four brightnesses. A cube reads as a cube because its faces differ,
    // and it takes no light source to say so.
    private static final double SHADE_TOP = 1.0;
    private static final double SHADE_NORTH_SOUTH = 0.80;
    private static final double SHADE_EAST_WEST = 0.62;
    private static final double SHADE_BOTTOM = 0.42;

    /** How far off the eye may be from a face before it stops drawing its edge. */
    private static final double EDGE_TILES = 6;

    /** The sky a level's backdrop is mixed toward; see {@link #begin}. */
    private static final Color DAYLIGHT = new Color(126, 172, 226);

    private DrawTarget target;
    private EyeCamera eye;
    private Level level;
    private int viewTiles = DEFAULT_VIEW_TILES;
    private double viewDistance;
    private double fogStart;
    private int fogArgb = 0xFF8FB6E0;

    /**
     * The frame's queue, pooled across frames.
     *
     * <p>A frame holds a few thousand faces and every one of them is the same
     * shape, so they are reused rather than allocated: {@link #used} is how
     * many of {@link #pool} this frame has claimed, and the rest are last
     * frame's, waiting. Allocating them per frame is a megabyte of garbage a
     * second at 60 Hz, which is the kind of cost that shows up as a stutter
     * rather than as a slower average.
     */
    private final List<Entry> pool = new ArrayList<>();
    private int used;

    /** Sort keys, {@code (depth, index)} packed into a long; see {@link #flush}. */
    private long[] order = new long[1024];

    // Scratch for one face's worth of projection, reused for the same reason
    // the pool exists.
    private final double[] eyeVerts = new double[4 * 3];
    private final double[] clipped = new double[8 * 3];
    private final double[] point = new double[3];

    /** One queued thing: a filled polygon, or an actor's sprite. */
    private static final class Entry {
        double depth;
        int argb;
        int count;
        final int[] xs = new int[8];
        final int[] ys = new int[8];
        boolean edge;
        int edgeArgb;
        // Billboard fields; sprite is null for a face.
        Runnable sprite;
        double screenX, screenY, scale, fade;
        int pivotX, pivotY;
    }

    /** How far the eye can see, in tiles. */
    public int viewTiles() { return viewTiles; }

    /** Set how far the eye can see, in tiles. */
    public void setViewTiles(int tiles) {
        this.viewTiles = Math.max(2, Math.min(256, tiles));
    }

    // --- a frame -----------------------------------------------------------

    /**
     * Start a frame: paint the sky and empty the queue.
     *
     * <p><b>The sky is tinted by the level rather than taken from it.</b> A
     * level's {@code background} is what shows where its terrain does not — a
     * backdrop behind a flat picture, and the engine's default for it is very
     * nearly black, which is a fine thing to see past a side-scroller's hills
     * and a terrible sky. There is no sky on a level to ask instead, so this
     * mixes the backdrop three-quarters of the way to a daylight blue: a level
     * authored red stays warm, a level authored teal stays cool, and none of
     * them come out as a black ceiling over a lit world. The fog the distance
     * fades into is that sky's own horizon colour, which is what makes the edge
     * of the view distance read as haze rather than as the place the world
     * stops.
     */
    public void begin(DrawTarget target, EyeCamera eye, Level level) {
        this.target = target;
        this.eye = eye;
        this.level = level;
        this.used = 0;
        this.viewDistance = viewTiles * (double) Math.max(1, level.tileSize);
        this.fogStart = viewDistance * FOG_START;

        Color backdrop = level.background != null ? level.background : DAYLIGHT;
        Color sky = mix(backdrop, DAYLIGHT, 0.75);
        Color horizon = mix(sky, Color.WHITE, 0.42);
        Color zenith = scale(sky, 0.78);
        Color ground = scale(sky, 0.44);
        this.fogArgb = horizon.getRGB();

        int w = eye.viewportWidth(), h = eye.viewportHeight();
        int horizonY = (int) Math.round(eye.horizonY());
        int split = Math.max(0, Math.min(h, horizonY));
        if (split > 0) {
            // The band above the horizon, deepening with height. Anchored on
            // the horizon's own row rather than on the top of the screen, so
            // looking up and down slides the gradient instead of squashing it.
            target.fillLinearGradient(0, 0, w, split,
                    0, horizonY - h, zenith.getRGB(), 0, horizonY, horizon.getRGB());
        }
        if (split < h) {
            target.fillLinearGradient(0, split, w, h - split,
                    0, horizonY, horizon.getRGB(), 0, horizonY + h / 2, ground.getRGB());
        }
    }

    /**
     * Queue every exposed, camera-facing block face within the view distance.
     *
     * <p>The sweep is a square of cells trimmed to a circle, because a square
     * of view distance is a third more cells than a circle of it and the corner
     * ones are the furthest away — the least worth drawing and the most of
     * them. Cells behind the eye are dropped too, but only when the pitch is
     * shallow enough for "behind" to mean anything: tilt the eye far enough and
     * the top of the frustum swings back over your own shoulder, and the exact
     * angle at which that starts is {@code 90° - fov/2}.
     */
    public void terrain() {
        if (level == null || eye == null) return;
        int ts = level.tileSize;
        if (ts <= 0) return;
        double reach = viewDistance;
        int c0 = Math.max(0, (int) Math.floor((eye.x() - reach) / ts));
        int c1 = Math.min(level.width - 1, (int) Math.floor((eye.x() + reach) / ts));
        int r0 = Math.max(0, (int) Math.floor((eye.y() - reach) / ts));
        int r1 = Math.min(level.height - 1, (int) Math.floor((eye.y() + reach) / ts));

        double fx = eye.forwardX(), fy = eye.forwardY();
        // Every ray of the frustum keeps a forward component while the eye is
        // tilted less than a quarter turn minus half the field of view; past
        // that the half-space test below would cull cells that are on screen.
        boolean cullBehind = Math.abs(eye.pitch()) + eye.fov() / 2
                < Math.toRadians(85);
        double behind = -1.5 * ts;
        double reachSq = reach * reach;

        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                double dx = (c + 0.5) * ts - eye.x();
                double dy = (r + 0.5) * ts - eye.y();
                if (dx * dx + dy * dy > reachSq) continue;
                if (cullBehind && dx * fx + dy * fy < behind) continue;
                column(c, r);
            }
        }
    }

    /** Every face of one cell's column that could be seen from here. */
    private void column(int col, int row) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;

        // The floor itself: a flat lid at z = 0, with no thickness — layer 0 is
        // the ground rather than a block standing on it (Level.surfaceZ). It is
        // skipped when a block sits on it, because that block's own underside
        // is the same quad and drawing both is two coplanar fills fighting.
        int floorId = level.tileAt(col, row);
        if (floorId > 0 && eye.z() > 0 && level.tileAt(col, row, 1) <= 0) {
            Color colour = level.colorFor(floorId);
            if (colour != null) {
                face(x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0, colour, SHADE_TOP);
            }
        }

        int depth = level.columnDepth(col, row);
        for (int base = 1; base < depth; ) {
            int id = level.tileAt(col, row, base);
            if (id <= 0) {
                base++;
                continue;
            }
            int top = base + 1;
            while (top < depth && level.tileAt(col, row, top) == id) top++;
            Block block = level.blocks.get(id);
            if (block != null) run(col, row, block, base, top, depth);
            base = top;
        }
    }

    /**
     * One run of identical blocks in a column, layers {@code [base, top)}.
     *
     * <p>Runs rather than blocks for the same reason {@link TerrainPainter}
     * draws them: a column of eight stones is one extrusion eight blocks tall,
     * and its side is one quad. Here it also halves the queue on any level with
     * walls in it.
     */
    private void run(int col, int row, Block block, int base, int top, int depth) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;
        // Layer 0 is the floor, so the block in layer 1 stands ON it at z = 0:
        // a run covering [base, top) spans heights [base - 1, top - 1).
        double z0 = (base - 1) * (double) ts;
        double z1 = (top - 1) * (double) ts;
        Color colour = block.color();

        if (eye.z() > z1 && (top >= depth || level.tileAt(col, row, top) <= 0)) {
            face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, colour, SHADE_TOP);
        }
        if (eye.z() < z0 && base > 1 && level.tileAt(col, row, base - 1) <= 0) {
            face(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, colour, SHADE_BOTTOM);
        }
        if (eye.y() < y0) sides(col, row, 0, -1, base, top, colour, SHADE_NORTH_SOUTH);
        if (eye.y() > y1) sides(col, row, 0, 1, base, top, colour, SHADE_NORTH_SOUTH);
        if (eye.x() > x1) sides(col, row, 1, 0, base, top, colour, SHADE_EAST_WEST);
        if (eye.x() < x0) sides(col, row, -1, 0, base, top, colour, SHADE_EAST_WEST);
    }

    /**
     * The part of one side of a run that the neighbouring column leaves
     * exposed, as one quad per uncovered span.
     *
     * <p>A run is split rather than drawn whole because the column next door
     * has a height of its own: a wall eight tall beside a wall three tall shows
     * five blocks of face and hides three, and drawing the whole eight would
     * paint the hidden part over whatever is standing in front of it.
     */
    private void sides(int col, int row, int dc, int dr, int base, int top,
                       Color colour, double shade) {
        int nc = col + dc, nr = row + dr;
        boolean open = nc < 0 || nr < 0 || nc >= level.width || nr >= level.height;
        int spanFrom = -1;
        for (int layer = base; layer <= top; layer++) {
            boolean clear = layer < top && (open || level.tileAt(nc, nr, layer) <= 0);
            if (clear) {
                if (spanFrom < 0) spanFrom = layer;
            } else if (spanFrom >= 0) {
                sideFace(col, row, dc, dr, spanFrom, layer, colour, shade);
                spanFrom = -1;
            }
        }
    }

    /** One quad on the {@code (dc, dr)} side, spanning layers {@code [from, to)}. */
    private void sideFace(int col, int row, int dc, int dr, int from, int to,
                          Color colour, double shade) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;
        double z0 = (from - 1) * (double) ts;
        double z1 = (to - 1) * (double) ts;
        if (dr < 0) {
            face(x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, colour, shade);
        } else if (dr > 0) {
            face(x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0, colour, shade);
        } else if (dc > 0) {
            face(x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, colour, shade);
        } else {
            face(x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0, colour, shade);
        }
    }

    /**
     * Project one quad, clip it against the near plane and queue it.
     *
     * <p>Everything that can reject the face is done before the projection —
     * the distance, and then the whole polygon being behind the eye — because
     * the projection is four divides and the rejections are comparisons.
     */
    private void face(double ax, double ay, double az, double bx, double by, double bz,
                      double cx, double cy, double cz, double dx, double dy, double dz,
                      Color colour, double shade) {
        if (colour == null) return;
        double minX = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        double maxX = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        double minY = Math.min(Math.min(ay, by), Math.min(cy, dy));
        double maxY = Math.max(Math.max(ay, by), Math.max(cy, dy));
        double minZ = Math.min(Math.min(az, bz), Math.min(cz, dz));
        double maxZ = Math.max(Math.max(az, bz), Math.max(cz, dz));
        double distance = boxDistance(minX, minY, minZ, maxX, maxY, maxZ);
        if (distance > viewDistance) return;

        eye.toEye(ax, ay, az, point);
        eyeVerts[0] = point[0];
        eyeVerts[1] = point[1];
        eyeVerts[2] = point[2];
        eye.toEye(bx, by, bz, point);
        eyeVerts[3] = point[0];
        eyeVerts[4] = point[1];
        eyeVerts[5] = point[2];
        eye.toEye(cx, cy, cz, point);
        eyeVerts[6] = point[0];
        eyeVerts[7] = point[1];
        eyeVerts[8] = point[2];
        eye.toEye(dx, dy, dz, point);
        eyeVerts[9] = point[0];
        eyeVerts[10] = point[1];
        eyeVerts[11] = point[2];

        int n = EyeCamera.clipNear(eyeVerts, 4, clipped);
        if (n < 3) return;

        Entry e = claim();
        e.sprite = null;
        e.depth = distance;
        e.count = n;
        int loX = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE;
        int loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            double right = clipped[i * 3], high = clipped[i * 3 + 1], d = clipped[i * 3 + 2];
            int sx = (int) Math.round(eye.screenX(right, d));
            int sy = (int) Math.round(eye.screenY(high, d));
            e.xs[i] = sx;
            e.ys[i] = sy;
            if (sx < loX) loX = sx;
            if (sx > hiX) hiX = sx;
            if (sy < loY) loY = sy;
            if (sy > hiY) hiY = sy;
        }
        // Entirely off screen: the entry was claimed but never accepted, so
        // returning here leaves it in the pool for the next face to reuse.
        if (hiX < 0 || hiY < 0 || loX > eye.viewportWidth() || loY > eye.viewportHeight()) {
            return;
        }
        e.argb = shadeFog(colour, shade, distance);
        // Every face is outlined, and the reason is a seam rather than a look.
        // Two faces that share a world edge project to the same screen edge,
        // and a scan-converted fill claims the pixels whose centres are inside
        // it — so along a shared edge that runs at an angle, a pixel centre can
        // fall inside neither, and the sky shows through the wall as a
        // one-pixel dash. Stroking each face in its own colour covers exactly
        // that half-pixel and nothing else.
        //
        // Near the eye the stroke is darkened instead, which is what separates
        // two faces of the same colour meeting at a corner. Only near: at a
        // distance a block is a few pixels across, the outline is most of them,
        // and the picture turns into a wireframe.
        e.edge = true;
        e.edgeArgb = distance < EDGE_TILES * level.tileSize
                ? shadeFog(colour, shade * 0.72, distance) : e.argb;
        keep();
    }

    /**
     * Distance from the eye to the nearest point of a box — the sort key; see
     * the class note on why this and not the centre.
     */
    private double boxDistance(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
        double dx = axisGap(eye.x(), minX, maxX);
        double dy = axisGap(eye.y(), minY, maxY);
        double dz = axisGap(eye.z(), minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisGap(double at, double lo, double hi) {
        return at < lo ? lo - at : at > hi ? at - hi : 0;
    }

    // --- actors ------------------------------------------------------------

    /**
     * Queue an actor's sprite, drawn by the scene's own 2D drawing code.
     *
     * <p><b>Why the sprite code is reused rather than replaced.</b> A mob is
     * not just an image: it is an image, plus a health bar, plus elemental
     * status tints, plus whatever it is holding, drawn by a method that has
     * grown all of that over time and that the plan view needs to go on using
     * unchanged. Writing a second copy of it against this camera would mean two
     * of everything, and the second one would be the one that quietly stopped
     * matching.
     *
     * <p>So the scene draws exactly what it always draws, and this puts a
     * transform under it. Sprites in this engine are drawn as an upright box
     * around a <em>ground contact point</em>, scaled by the flat camera's zoom
     * — so mapping that one point to where the perspective camera puts it, and
     * scaling about it by the ratio of the two projections, turns the whole
     * sprite into a correct billboard. A billboard is exactly a sprite that
     * always faces the viewer, which is what a flat sprite already was.
     *
     * @param wx      the anchor's world x — the sprite's ground contact point
     * @param wy      the anchor's world y
     * @param wz      the anchor's height above the floor
     * @param pivotX  where the caller's own drawing puts that anchor on screen
     * @param pivotY  the same, vertically, <em>including</em> any lift the
     *                caller applies for {@code wz}
     * @param pivotScale the scale the caller draws at (the flat camera's zoom)
     * @param draw    the caller's drawing, run at flush time
     */
    public void billboard(double wx, double wy, double wz, int pivotX, int pivotY,
                          double pivotScale, Runnable draw) {
        if (draw == null || pivotScale <= 0 || eye == null) return;
        if (!eye.project(wx, wy, wz, point)) return;
        double screenX = point[0], screenY = point[1], depth = point[2];
        double distance = Math.sqrt((wx - eye.x()) * (wx - eye.x())
                + (wy - eye.y()) * (wy - eye.y()) + (wz - eye.z()) * (wz - eye.z()));
        if (distance > viewDistance) return;
        Entry e = claim();
        e.sprite = draw;
        e.depth = distance;
        e.count = 0;
        e.screenX = screenX;
        e.screenY = screenY;
        e.scale = eye.scaleAt(depth) / pivotScale;
        e.pivotX = pivotX;
        e.pivotY = pivotY;
        // Actors are not fogged by blending — there is nothing to blend a
        // finished sprite with — so they fade instead, which reads the same
        // against a fogged background and costs one alpha push.
        e.fade = 1 - fogAmount(distance);
        keep();
    }

    // --- flushing ----------------------------------------------------------

    /**
     * Draw everything queued, far to near, and empty the queue.
     *
     * <p>Sorted through an array of {@code long}s rather than a comparator over
     * the entries: the distance is quantised into the high half and the entry's
     * index into the low half, so one {@link Arrays#sort(long[], int, int)} on
     * primitives does the whole job with no boxing and no allocation, and the
     * index in the low bits makes ties keep the order they were queued in.
     */
    public void flush() {
        if (used == 0) return;
        if (order.length < used) order = new long[Math.max(used, order.length * 2)];
        for (int i = 0; i < used; i++) {
            long q = quantise(pool.get(i).depth);
            order[i] = ((QUANTA_MAX - q) << 32) | i;
        }
        Arrays.sort(order, 0, used);
        for (int i = 0; i < used; i++) {
            draw(pool.get((int) (order[i] & 0xFFFFFFFFL)));
        }
        used = 0;
    }

    /** The largest quantised distance the sort key can hold. */
    private static final long QUANTA_MAX = 1L << 30;

    /**
     * A distance as a whole number of quarter world-units, which is a
     * hundred-and-twenty-eighth of a block at the engine's default tile size —
     * far finer than anything the sort can distinguish on screen.
     */
    private static long quantise(double distance) {
        long q = (long) (distance * 4);
        return q < 0 ? 0 : Math.min(q, QUANTA_MAX);
    }

    private void draw(Entry e) {
        if (e.sprite != null) {
            AffineTransform t = new AffineTransform();
            t.translate(e.screenX, e.screenY);
            t.scale(e.scale, e.scale);
            t.translate(-e.pivotX, -e.pivotY);
            boolean faded = e.fade < 0.999;
            if (faded) target.pushAlpha((float) Math.max(0, e.fade));
            target.pushTransform(t);
            e.sprite.run();
            target.popTransform();
            if (faded) target.popAlpha();
            return;
        }
        target.fillPolygon(e.xs, e.ys, e.count, e.argb);
        if (e.edge) target.drawPolygon(e.xs, e.ys, e.count, e.edgeArgb, 1f);
    }

    private Entry claim() {
        if (used == pool.size()) pool.add(new Entry());
        return pool.get(used);
    }

    /**
     * Accept the entry {@link #claim} handed out.
     *
     * <p>Claiming and accepting are separate so that a face which turns out to
     * be entirely off screen — which is most of what the sweep looks at near
     * the edge of the view — can simply return, leaving the entry in the pool
     * for the next one to fill in rather than costing an allocation and a
     * removal.
     */
    private void keep() {
        used++;
    }

    // --- colour ------------------------------------------------------------

    /** How much of the fog colour a face at this distance takes, in [0,1]. */
    private double fogAmount(double distance) {
        if (distance <= fogStart) return 0;
        double span = viewDistance - fogStart;
        if (span <= 0) return 1;
        return Math.max(0, Math.min(1, (distance - fogStart) / span));
    }

    /** A block's colour, shaded for its face and faded into the fog. */
    private int shadeFog(Color colour, double shade, double distance) {
        double fog = fogAmount(distance);
        int r = (int) Math.round(colour.getRed() * shade);
        int g = (int) Math.round(colour.getGreen() * shade);
        int b = (int) Math.round(colour.getBlue() * shade);
        int fr = (fogArgb >> 16) & 0xFF, fg = (fogArgb >> 8) & 0xFF, fb = fogArgb & 0xFF;
        r = (int) Math.round(r + (fr - r) * fog);
        g = (int) Math.round(g + (fg - g) * fog);
        b = (int) Math.round(b + (fb - b) * fog);
        // The block's own alpha is kept, so liquids and glass stay see-through.
        return (colour.getAlpha() << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static Color scale(Color c, double by) {
        return new Color(clamp((int) (c.getRed() * by)), clamp((int) (c.getGreen() * by)),
                clamp((int) (c.getBlue() * by)));
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(clamp((int) (a.getRed() + (b.getRed() - a.getRed()) * t)),
                clamp((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clamp((int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)));
    }

    // --- aiming ------------------------------------------------------------

    /**
     * What the eye is looking at, within {@code reach} world units — the
     * crosshair's answer, and the first-person replacement for
     * {@link TerrainPainter#pick}.
     *
     * <p>A grid march (the standard "amanatides and woo" traversal): step to
     * whichever of the three cell boundaries the ray reaches first, and test
     * the cell it lands in. Exact rather than sampled — a sampled ray at a
     * fixed step misses a block edge-on and cannot say which face it came in
     * through, and which face is the difference between placing a block on top
     * of a wall and placing it inside one.
     *
     * <p><b>The ground is a voxel here, and it is not one anywhere else.</b>
     * Layer 0 is a surface with no thickness — {@code Level.surfaceZ} puts the
     * top of a one-deep column at zero — so a march over the volume alone would
     * pass straight through the floor. It is given the box below zero it would
     * have if it were a block, which makes the floor's own top face something
     * the ray can strike, and returns the {@code layer 0} aim the rest of the
     * engine already understands.
     *
     * @return what is under the crosshair, or {@code null} if the ray reaches
     *         nothing within {@code reach}
     */
    public static TerrainPainter.Aim pick(EyeCamera eye, Level level, double reach) {
        Hit hit = march(eye, level, reach);
        if (hit == null) return null;
        int placeCol = hit.top ? hit.col : hit.fromCol;
        int placeRow = hit.top ? hit.row : hit.fromRow;
        return new TerrainPainter.Aim(hit.col, hit.row, hit.layer, hit.top,
                placeCol, placeRow);
    }

    /**
     * The world point the eye is looking at: where its ray first meets the
     * terrain, or the point {@code reach} away when it meets nothing.
     *
     * <p>What every aimed action in a first-person view takes as its target —
     * a shot, a melee swing, an ultimate. The plan view answers the same
     * question by inverting the projection under the mouse; here the mouse is
     * not where you are looking, the middle of the screen is.
     *
     * @return {@code {x, y, z}} in world coordinates, never {@code null}
     */
    public static double[] aimPoint(EyeCamera eye, Level level, double reach) {
        Hit hit = march(eye, level, reach);
        double t = hit != null ? hit.distance : reach;
        return new double[]{
                eye.x() + eye.dirX() * t,
                eye.y() + eye.dirY() * t,
                Math.max(0, eye.z() + eye.dirZ() * t)
        };
    }

    /** Where a march stopped: the cell struck, the face, and how far away. */
    private record Hit(int col, int row, int layer, boolean top,
                       int fromCol, int fromRow, double distance) {}

    private static Hit march(EyeCamera eye, Level level, double reach) {
        int ts = level == null ? 0 : level.tileSize;
        if (ts <= 0 || reach <= 0) return null;
        double dx = eye.dirX(), dy = eye.dirY(), dz = eye.dirZ();

        int col = (int) Math.floor(eye.x() / ts);
        int row = (int) Math.floor(eye.y() / ts);
        // The voxel index on the height axis. Layer L occupies heights
        // [(L-1)·ts, L·ts), so the box holding height z is index floor(z/ts)
        // and the layer standing in it is one more than that — which makes the
        // box below zero the ground, exactly as the note above says.
        int box = (int) Math.floor(eye.z() / ts);

        if (solid(level, col, row, box)) {
            // The eye is inside something. Nothing sensible is "in front of"
            // it, so this is what it is looking at.
            return new Hit(col, row, box + 1, true, col, row, 0);
        }

        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tNextX = boundary(eye.x(), dx, col, ts);
        double tNextY = boundary(eye.y(), dy, row, ts);
        double tNextZ = boundary(eye.z(), dz, box, ts);
        double stridX = stepX == 0 ? Double.POSITIVE_INFINITY : ts / Math.abs(dx);
        double stridY = stepY == 0 ? Double.POSITIVE_INFINITY : ts / Math.abs(dy);
        double stridZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ts / Math.abs(dz);

        int fromCol = col, fromRow = row;
        int layers = level.layerCount();
        while (true) {
            double t;
            boolean vertical = false;
            fromCol = col;
            fromRow = row;
            if (tNextX <= tNextY && tNextX <= tNextZ) {
                t = tNextX;
                col += stepX;
                tNextX += stridX;
            } else if (tNextY <= tNextZ) {
                t = tNextY;
                row += stepY;
                tNextY += stridY;
            } else {
                t = tNextZ;
                box += stepZ;
                tNextZ += stridZ;
                vertical = true;
            }
            if (t > reach) return null;
            if (col < 0 || row < 0 || col >= level.width || row >= level.height) return null;
            // Out of the world along the height axis, and heading further out.
            if (stepZ > 0 && box > layers) return null;
            if (stepZ < 0 && box < -1) return null;
            if (solid(level, col, row, box)) {
                return new Hit(col, row, box + 1, vertical && stepZ < 0,
                        fromCol, fromRow, t);
            }
        }
    }

    /** How far along the ray the current cell's boundary on one axis is. */
    private static double boundary(double at, double d, int cell, int ts) {
        if (d > 0) return ((cell + 1) * (double) ts - at) / d;
        if (d < 0) return (cell * (double) ts - at) / d;
        return Double.POSITIVE_INFINITY;
    }

    /** Whether the box at this grid position stops a ray; see {@link #march}. */
    private static boolean solid(Level level, int col, int row, int box) {
        if (box < -1) return false;
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return false;
        return level.tileAt(col, row, box + 1) > 0;
    }
}
