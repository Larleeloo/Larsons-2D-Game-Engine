package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.gen.WorldTerrain;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
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
 * other pass in the engine. What it sorts on is <b>how many cells away from the
 * eye's own cell a face's cell is</b>, counted along the three axes
 * ({@link #cellOrder}).
 *
 * <p>That is exact rather than approximate, and the argument is three lines
 * long: along any straight ray each coordinate moves monotonically, so the sum
 * of the three cell distances never decreases along it; so a face hit before
 * another has a sum no larger; so drawing in decreasing order of that sum puts
 * every occluder over what it occludes. Two faces that tie cannot occlude each
 * other, because no ray reaches both.
 *
 * <p>It rests on one thing: <b>a face belongs to exactly one cell</b>. That is
 * why side faces are drawn per block rather than merged up a column — see
 * {@link #block}. Sorting merged runs by their nearest corner was the previous
 * scheme, and it is wrong in exactly the case players report: a tall wall whose
 * nearest corner is at your elbow drawn over the block standing halfway along
 * it.
 *
 * <h2>Texture, and the shade baked into it</h2>
 *
 * <p>A face is drawn with the block's own sheet — the same
 * {@link TextureKeys#BLOCKS_TOP top} and {@link TextureKeys#BLOCKS_SIDE side}
 * pools the plan view resolves, with the same fallbacks — mapped onto the
 * projected quad in <b>patches</b>, each one small enough that an affine blit
 * covers it: see {@link #drawTextured}. One affine map over a whole face is
 * what a player sees as the surface bending and folding, because three corners
 * decide such a map and the divide puts the fourth somewhere else entirely;
 * halving a face until each piece is nearly a parallelogram costs a handful of
 * blits on the tiles underfoot and one on everything else. The same halving is
 * what carries a texture onto the face you are standing <em>against</em>: a
 * patch with a corner behind your own eye cannot be projected, so it is cut
 * down until the part that can be is, rather than the face giving up its
 * texture — which is what it used to do, and always on the nearest face in the
 * frame. Faces a few pixels across keep the flat fill: at that size a sheet is
 * a smear of its own average colour, which is what the fill is.
 *
 * <p>Shading is the classic four-level scheme every blocky 3D game uses: the
 * top brightest, north/south next, east/west darker, the underside darkest,
 * which is what makes a cube read as a cube without a single light calculation.
 * On a textured face it is baked into the sheet ({@link SolidTextures}) rather
 * than washed over it, so no face is ever drawn twice to be lit.
 *
 * <p><b>What it costs.</b> Nine milliseconds a frame at 1280&times;720 against
 * two and a half for the same view untextured, measured over an open plain at
 * eye level — which is this pass's worst case rather than its average, since
 * every face in it is floor seen at a glancing angle and floor at a glancing
 * angle is the only thing that needs many patches. It is Java2D's warped blit
 * that costs it, near enough in proportion to the pixels covered, so the
 * tolerance below buys quality far more cheaply than it looks like it should.
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
     * How far the coarse pass reaches when a player turns it on, in tiles.
     *
     * <p>Six times the detailed distance, which is the point of the setting:
     * anything less and the horizon is still close enough to read as a wall of
     * fog. It is affordable at that range only because what it draws is one box
     * per {@linkplain #distantGroup group of cells} — see {@link #distant}.
     */
    public static final int DISTANT_VIEW_TILES = 120;

    /**
     * The furthest the detailed sweep may be set to, in tiles.
     *
     * <p>192, which is what a generated world's render-distance slider tops out
     * at. It is a long way past what this renderer keeps at 60 Hz and that is
     * the point of a slider: the machine at the far end of it is not the one
     * the default was measured on.
     */
    public static final int MAX_VIEW_TILES =
            com.larsons.engine.world.gen.TerrainSettings.MAX_RENDER_DISTANCE;

    /**
     * How far the world is drawn block by block by default, in tiles.
     *
     * <p><b>The number that decides whether a long render distance is a
     * setting or a slideshow.</b> What the detailed sweep costs grows with the
     * <em>area</em> it covers, so four times the distance is sixteen times the
     * faces however well each one is culled: measured over generated terrain at
     * 1280&times;720, a frame took 17.7&nbsp;ms at a render distance of
     * twenty-four, 44 at forty-eight, 154 at ninety-six and <b>602 at a hundred
     * and ninety-two</b>. Past this the same world is drawn by the coarse pass
     * instead, whose cost is a few thousand quads <em>whatever</em> the
     * distance is, because a box is drawn at the size it subtends rather than
     * at the size it is ({@link #distant}). The same four distances then cost
     * 13.7, 21, 30 and <b>34</b>&nbsp;ms.
     *
     * <p><b>Four chunks</b>, and the number is a measurement rather than a
     * taste. What the sweep costs on one machine, over generated terrain,
     * against the same view: two chunks 2.4&nbsp;ms, four 10.3, six 26.9,
     * eight 53.5. It squares, because the disc does. Four is where it still
     * fits inside a frame with the rest of the game in it, and everything past
     * it is drawn by {@link #distant} for about a millisecond however far the
     * render distance goes — which is why the render distance can be ninety
     * chunks and this cannot.
     *
     * <p>It is the near half of the two-number arrangement every game with a
     * real view distance ends up with — Minecraft's render distance beside
     * Distant Horizons' — and the reason both numbers exist is that they are
     * answering different questions: how far can you see, and how much of that
     * is worth a block each. The slider goes to the render distance's own
     * ceiling for a machine that can spend it.
     */
    public static final int DEFAULT_DETAIL_TILES =
            4 * com.larsons.engine.world.gen.TerrainSettings.BLOCKS_PER_CHUNK;

    /** The least the detailed sweep may be squeezed to, in tiles — one chunk. */
    public static final int MIN_DETAIL_TILES =
            com.larsons.engine.world.gen.TerrainSettings.BLOCKS_PER_CHUNK;

    /**
     * How far decorations are drawn by default, in tiles.
     *
     * <p>Scenery — a flower, a tuft of grass, a mushroom — is a billboard and a
     * stem each, and there are more of them on a hillside than there are blocks
     * of the hillside. They are also the first thing that stops being legible
     * with distance: past a dozen chunks a flower is one pixel of green that
     * costs the same as a flower you can see. So they get their own reach, and
     * it is shorter than the ground's.
     *
     * <p>Never further than the detail distance, whatever this says: a
     * decoration stands on a block, and past the detail distance there are no
     * blocks for it to stand on — only the landforms that stand in for them.
     */
    public static final int DEFAULT_DECOR_TILES =
            12 * com.larsons.engine.world.gen.TerrainSettings.BLOCKS_PER_CHUNK;

    /** The furthest the coarse horizon may be set to, in tiles. */
    public static final int MAX_DISTANT_TILES =
            com.larsons.engine.world.gen.TerrainSettings.MAX_DISTANT_DISTANCE;

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

    /**
     * How many pixels across a face has to be before it is worth texturing.
     *
     * <p>Under this a sheet is a smear of its own average colour, which is what
     * the flat fill already draws — for a fraction of the cost and without
     * asking the backend to warp anything.
     */
    private static final int MIN_TEXTURE_PIXELS = 4;

    /**
     * How far a patch's corners may sit from the affine map that draws it,
     * in pixels, before the patch is split in two.
     *
     * <p><b>Measured, not predicted from the depth.</b> The obvious criterion —
     * how much the depth changes across the face — is the wrong one, and wrong
     * in the direction that matters: the error it produces is that fraction of
     * the face's <em>screen size</em>, so a tile a few paces away whose depth
     * varies by a seventh is out by fifteen pixels while a distant wall at the
     * same ratio is out by one. Judging the projected corners instead asks the
     * question the eye is asking.
     *
     * <p>What is measured is the quad's <em>parallelogram defect</em>:
     * {@code A − B + C − D}, which is zero exactly when the four projected
     * corners form a parallelogram and an affine map can therefore land on all
     * four. A least-squares fit spreads that defect over the corners as a
     * quarter of it each, so four pixels here is one pixel of error — about
     * what a 32-pixel sheet blown up to fill a tile underfoot can show at all.
     *
     * <p>Four rather than two because two costs a third again as much and looks
     * the same: halving the tolerance doubles the patches, and the pass is paid
     * for by the pixels those patches cover rather than by how many of them
     * there are, so the second half of that trade buys almost nothing.
     */
    private static final double MAX_PATCH_DEFECT = 4.0;

    /**
     * How many times a face may be halved, so how many patches it may cost:
     * two to the sixth, sixty-four, and only for a face filling the screen.
     *
     * <p>A limit on the <em>depth</em> rather than on the count, which is the
     * distinction that took a rewrite to find. A shared budget is spent by
     * whichever patch happens to come up first, and on a floor tile that is the
     * far half — the half that bends least and needs it least. The near half
     * then finds the budget gone and is drawn at a defect of fifty pixels,
     * which is a triangular tear along its own edge, which is the artefact
     * this was reported as. A depth limit is per patch, so each half of a face
     * is refined on its own merits and only what bends costs anything.
     */
    private static final int MAX_SPLIT_DEPTH = 6;

    /**
     * How small a patch may get on screen before splitting it stops paying.
     *
     * <p>A quad's defect is bounded by its own perimeter, so a patch this size
     * is inside the tolerance whatever else is true of it, and halving it again
     * buys nothing for the price of another blit.
     */
    private static final double MIN_PATCH_PIXELS = 8;

    /**
     * How many times a patch may be halved to get it clear of the near plane,
     * on top of whatever the bending costs.
     *
     * <p>Counted separately from the splitting above because it is answering a
     * different question — <em>can</em> this be projected, not <em>how well</em>
     * — and a face that spends six halvings finding the near plane would
     * otherwise have nothing left to spend on the bend. In practice the sheet
     * runs out first: a 32-pixel texture cannot be halved past five times an
     * axis, and the cut stops there whatever this says.
     */
    private static final int MAX_NEAR_CUTS = 12;

    /** How many numbers one patch takes on {@link #work}. */
    private static final int STRIDE = 6;

    /**
     * What a coarse box's sort key carries so that it is always behind the
     * detailed terrain — more than any cell distance a level can hold, and far
     * short of the key's own ceiling.
     */
    private static final long DISTANT_ORDER = 1L << 20;

    /** Cells to a box in the ring that meets the detailed terrain. */
    private static final int NEAR_RING_CELLS = 4;

    /** Where the near ring gives way to the far one, in detailed distances. */
    private static final double RING_SPLIT = 3;

    /**
     * How many rings the horizon is drawn in before it gives up.
     *
     * <p>Each ring reaches {@link #RING_SPLIT} times as far as the one inside
     * it, so five of them cover two hundred times the detailed distance — well
     * past the furthest the settings offer. The cap is there so that a
     * pathological render distance cannot spin here, not because five is a
     * meaningful number of rings.
     */
    private static final int MAX_RINGS = 5;

    /**
     * How wide a coarse box is as a fraction of how far away it is — the
     * angular size every ring is drawn at. See {@link #groupFor}.
     */
    private static final double RING_ANGLE = 0.2;

    /** Half a plant stem's width, as a fraction of a tile. */
    private static final double STEM_HALF_WIDTH = 0.055;

    /** How far up the cell a plant's stem reaches, as a fraction of a tile. */
    private static final double STEM_HEIGHT = 0.45;

    /** Half a plant billboard's width, as a fraction of a tile. */
    private static final double PLANT_HALF_WIDTH = 0.42;

    /** Where a plant's billboard starts and ends up the cell, in tiles. */
    private static final double PLANT_BASE = 0.10;
    private static final double PLANT_TOP = 0.95;

    /** How many pixels across a procedural plant sprite is drawn. */
    private static final int PLANT_SPRITE_PIXELS = 32;

    /** The green a plant's stem is drawn in, whatever colour its head is. */
    private static final Color STEM_GREEN = new Color(72, 128, 62);

    /** The patch of ground under an actor; see {@link #groundShadow}. */
    private static final Color SHADOW = new Color(0, 0, 0, 90);

    /** The sky a level's backdrop is mixed toward; see {@link #begin}. */
    private static final Color DAYLIGHT = new Color(126, 172, 226);

    private DrawTarget target;
    private EyeCamera eye;
    private Level level;
    private int viewTiles = DEFAULT_VIEW_TILES;
    private int distantTiles;
    /**
     * How far the world is drawn a block at a time, in tiles. Uncapped by
     * default so that a caller which never mentions it gets exactly the picture
     * this painter drew before there was a coarse pass to hand the rest to.
     */
    private int detailTiles = MAX_VIEW_TILES;
    /** How far decorations are drawn, in tiles; see {@link #DEFAULT_DECOR_TILES}. */
    private int decorTiles = MAX_VIEW_TILES;
    private double viewDistance;
    private double distantDistance;
    /** {@link #detailTiles} in world units, never past {@link #viewDistance}. */
    private double detailDistance;
    /** {@link #decorTiles} in world units, never past {@link #detailDistance}. */
    private double decorDistance;
    /**
     * How far a face may be and still be queued, which is the detailed reach
     * for the ordinary sweep and the whole horizon while {@link #distant} runs.
     */
    private double faceReach;
    /**
     * Added to every face's sort key while {@link #distant} runs, so a coarse
     * box is always behind everything drawn in detail — including the detailed
     * cells of the ring it overlaps, which are the only ones it could argue
     * with. See {@link #cellOrder}.
     */
    private long orderBias;
    private double fogStart;
    private int fogArgb = 0xFF8FB6E0;
    /** Where in an animated texture's cycle this frame is. */
    private double animClock;

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

    /**
     * One coarse box's answer from {@link Level#groupTop}: the tallest layer in
     * the group, its block id, and the lowest layer in the same group.
     */
    private final int[] groupScratch = new int[3];

    /** One column's worth of layers to look at; see {@link Level#visibleLayers}. */
    private final int[] layerScratch = new int[Level.MAX_LAYERS];

    /**
     * Which block ids hide what is behind them, indexed by id — rebuilt once a
     * frame in {@link #begin}. See {@link #covers(int)}.
     */
    private boolean[] coversById = new boolean[0];
    /** The blocks themselves, indexed the same way; see {@link #blockOf}. */
    private Block[] blockById = new Block[0];
    /** The registry {@link #coversById} was built from, and its revision then. */
    private com.larsons.engine.world.BlockRegistry coversFrom;
    private int coversCount = -1;

    /** The cell the eye is standing in, so the faces around it are never culled. */
    private int eyeCol, eyeRow, eyeLayer;

    // Where the camera is looking from and at while something stands between
    // the two; see setCutaway. cutRadius <= 0 means the pass is off.
    private double cutX, cutY, cutZ, cutRadius;

    // Scratch for one face's worth of projection, reused for the same reason
    // the pool exists.
    private final double[] eyeVerts = new double[4 * 3];
    private final double[] clipped = new double[8 * 3];
    private final double[] point = new double[3];
    /** The face a textured blit is kept inside; refilled per face. */
    private final java.awt.Polygon clip = new java.awt.Polygon();
    /** One patch's projected corners, and the map that puts the sheet on them. */
    private final double[] patchX = new double[4];
    private final double[] patchY = new double[4];
    private final AffineTransform patchMap = new AffineTransform();
    /**
     * The patches of one face still to be drawn or split — the texture bounds
     * they cover and how many splits deep they are, five numbers each.
     *
     * <p>A stack rather than a grid because the splitting is adaptive: a face
     * bends where the divide bends it, and a uniform grid spends its patches
     * evenly over a face that does not. Depth-first, so it only ever holds one
     * patch per level.
     */
    private final int[] work = new int[STRIDE * (MAX_SPLIT_DEPTH + MAX_NEAR_CUTS + 2)];
    /** Per-frame texture lookups, so a texture key is resolved once a frame. */
    private final java.util.Map<String, BufferedImage> textures = new java.util.HashMap<>();

    /** Per-frame sheets by block id, and the frame they were resolved in. */
    private BufferedImage[] topById = new BufferedImage[0];
    private BufferedImage[] sideById = new BufferedImage[0];
    private int[] sheetFrame = new int[0];
    /** Which frame this is, counted so a stale entry above needs no clearing. */
    private int frameSeq;

    /** One queued thing: a filled polygon, or an actor's sprite. */
    private static final class Entry {
        /**
         * What the painter sorts on: how many cells away from the eye's own
         * cell this face's cell is, counted along the three axes. See
         * {@link #cellOrder}.
         */
        long order;
        int argb;
        int count;
        final int[] xs = new int[8];
        final int[] ys = new int[8];
        boolean edge;
        int edgeArgb;
        /**
         * Whether the face's own colour is painted under its texture.
         *
         * <p>True for a block face, where the fill is the backing that stops a
         * fraction of a pixel of sky showing through a wall. False for a
         * billboard — a plant's sprite is mostly transparent, and a coloured
         * quad behind it is a coloured quad.
         */
        boolean fill = true;
        /** The face's texture, or {@code null} for a flat fill. */
        BufferedImage texture;
        /**
         * The face in world coordinates — its four corners, in the order they
         * were queued — so a textured face can be re-projected in patches at
         * draw time. Only meaningful with a texture.
         */
        final double[] quad = new double[4 * 3];
        /** Fog over a textured face, {@code 0} when it is close enough not to need any. */
        int fogOverlay;
        // Billboard fields; sprite is null for a face.
        Runnable sprite;
        double screenX, screenY, scale, fade;
        int pivotX, pivotY;
    }

    /** How far the eye can see, in tiles. */
    public int viewTiles() { return viewTiles; }

    /**
     * Whether a point on the world plane is inside this frame's view distance.
     *
     * <p>For callers that have their own list to walk — the scenery painters,
     * which hold every decoration in the level and would otherwise project all
     * of them to find out that ten are on screen. {@link #billboard} answers the
     * same question for what it is handed; this lets the walk stop earlier.
     */
    public boolean inRange(double wx, double wy) {
        if (eye == null) return false;
        double dx = wx - eye.x(), dy = wy - eye.y();
        // The decoration reach rather than the view distance. Two reasons, and
        // the second is the one that bites: past the detail distance the ground
        // is landforms, which sort behind everything the detailed pass queued,
        // so a tuft of grass out there would be drawn in front of the hill it
        // is standing behind rather than hidden by it. And scenery is the first
        // thing that stops being worth its cost with distance.
        return dx * dx + dy * dy <= decorDistance * decorDistance;
    }

    /** Set how far the eye can see, in tiles. */
    public void setViewTiles(int tiles) {
        this.viewTiles = Math.max(2, Math.min(MAX_VIEW_TILES, tiles));
    }

    /** How far the world is drawn block by block, in tiles. */
    public int detailTiles() { return detailTiles; }

    /**
     * Set how far the world is drawn a block at a time. Everything between
     * this and {@link #setViewTiles the view distance} is drawn by the coarse
     * pass instead — see {@link #DEFAULT_DETAIL_TILES} for why the two are
     * separate numbers, and {@link #distant()} for what the far one draws.
     *
     * <p>Never further than the view distance: a detail radius past the edge of
     * the world you can see is a number with nothing to do.
     */
    public void setDetailTiles(int tiles) {
        this.detailTiles = Math.max(MIN_DETAIL_TILES, Math.min(MAX_VIEW_TILES, tiles));
    }

    /** How far decorations are drawn, in tiles. */
    public int decorTiles() { return decorTiles; }

    /**
     * Set how far scenery is drawn — flowers, grass, the level's decor objects.
     * Clamped to the detail distance when the frame is built, because a
     * decoration stands on a block. See {@link #DEFAULT_DECOR_TILES}.
     */
    public void setDecorTiles(int tiles) {
        this.decorTiles = Math.max(1, Math.min(MAX_VIEW_TILES, tiles));
    }

    /** How far the coarse pass reaches, in tiles; {@code 0} when it is off. */
    public int distantTiles() { return distantTiles; }

    /**
     * Draw the world beyond {@link #viewTiles} as coarse boxes, out to
     * {@code tiles}. Zero, or anything inside the detailed distance, turns it
     * off. See {@link #distant}.
     */
    public void setDistantTiles(int tiles) {
        this.distantTiles = tiles <= 0 ? 0 : Math.max(2, Math.min(MAX_DISTANT_TILES, tiles));
    }

    /**
     * Draw whatever stands between the eye and {@code (x, y, z)} see-through,
     * out to {@code radius} world units either side of the line between them.
     *
     * <p><b>What this is for.</b> A camera pulled back from the player is a
     * camera with the world in the way — a wall as you back into a doorway, the
     * roof of the room you are standing in, the hillside you have just walked
     * behind. Every third-person game answers one of two ways: pull the camera
     * in until nothing is in the way, or leave it where it is and stop drawing
     * what is. The mouse-look views take the first ({@code PlayScene.placeEye}),
     * because their arm is short and a wall a pace behind you is a wall; the
     * plan view takes this one, because its arm is a dozen tiles and pulling in
     * that far is not a plan view any more, it is a shove into first person
     * every time you walk indoors.
     *
     * <p>Faded rather than dropped: a ghost of the wall in front of you still
     * says the wall is there, and a hole in the world says nothing.
     *
     * <p>Set per frame, and cleared by {@link #begin} — a caller that stops
     * asking stops getting it.
     */
    public void setCutaway(double x, double y, double z, double radius) {
        this.cutX = x;
        this.cutY = y;
        this.cutZ = z;
        this.cutRadius = radius;
    }

    /** How much of a face survives the cutaway: 1 for all of it, 0 for none. */
    private double cutawayAlpha(double x, double y, double z) {
        if (cutRadius <= 0) return 1;
        double ax = cutX - eye.x(), ay = cutY - eye.y(), az = cutZ - eye.z();
        double lenSq = ax * ax + ay * ay + az * az;
        if (lenSq <= 1e-9) return 1;
        double t = ((x - eye.x()) * ax + (y - eye.y()) * ay + (z - eye.z()) * az) / lenSq;
        // Only what is actually between the two. Behind the eye there is
        // nothing to see through, and past the player the wall is scenery.
        if (t <= 0.02 || t >= 1) return 1;
        double px = eye.x() + ax * t, py = eye.y() + ay * t, pz = eye.z() + az * t;
        double off = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py)
                + (z - pz) * (z - pz));
        if (off >= cutRadius) return 1;
        // Fully out along the middle of the line and fading back in at the
        // edge, so the hole has a soft rim rather than a cut circle in it.
        double edge = off / cutRadius;
        return CUTAWAY_MIN + (1 - CUTAWAY_MIN) * edge * edge;
    }

    /**
     * How much of a face is left where the cutaway is at its strongest. Not
     * zero: a wall you can see the outline of is a wall, and one that has
     * simply gone is a hole in the world.
     */
    private static final double CUTAWAY_MIN = 0.13;

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
        begin(target, eye, level, 0);
    }

    /**
     * {@link #begin(DrawTarget, EyeCamera, Level)} at a point in time, so
     * animated block textures (water, lava, anything a pack gave more than one
     * frame) play here as they do in the plan view.
     */
    public void begin(DrawTarget target, EyeCamera eye, Level level, double animClock) {
        this.target = target;
        this.eye = eye;
        this.level = level;
        this.animClock = animClock;
        if (++frameSeq == Integer.MAX_VALUE) {
            frameSeq = 1;
            java.util.Arrays.fill(sheetFrame, 0);
        }
        this.textures.clear();
        java.util.Arrays.fill(shadedFrom, 0, shadedCount, null);
        java.util.Arrays.fill(shadedTo, 0, shadedCount, null);
        this.shadedCount = 0;
        this.used = 0;
        int ts = Math.max(1, level.tileSize);
        this.viewDistance = viewTiles * (double) ts;
        this.distantDistance = distantTiles > viewTiles ? distantTiles * (double) ts : 0;
        this.detailDistance = Math.min(viewDistance, detailTiles * (double) ts);
        this.decorDistance = Math.min(detailDistance, decorTiles * (double) ts);
        this.faceReach = detailDistance;
        this.orderBias = 0;
        this.cutRadius = 0;
        this.eyeCol = (int) Math.floor(eye.x() / ts);
        this.eyeRow = (int) Math.floor(eye.y() / ts);
        this.eyeLayer = (int) Math.floor(eye.z() / ts) + 1;
        buildCoversTable();
        // Emptied here rather than only in the sweep, so a caller that draws the
        // horizon without the detailed pass in front of it culls against this
        // frame's terrain instead of against the last frame's.
        clearHorizon();
        // Fog is measured against the furthest thing that will be drawn, which
        // is the coarse horizon when there is one. That is not bookkeeping: fog
        // exists to hide the edge of what is drawn, so pushing the edge out
        // has to push the fog out with it — otherwise turning the distant
        // terrain on would draw a mountain range and then paint it out.
        this.fogStart = horizon() * FOG_START;

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
     * Fill {@link #coversById} from this level's block registry, when it is not
     * already the table for that registry as it now stands.
     *
     * <p>Rebuilt on a change rather than every frame: the registry is a couple
     * of hundred entries and a scan of it is nothing beside a frame, but it is
     * also nothing that ever needs doing twice. What says it changed is the
     * registry's own {@linkplain
     * com.larsons.engine.world.BlockRegistry#revision() revision} rather than
     * how many blocks it holds — reshaping a block into a plant replaces a row
     * in place, so the count says nothing and this table stayed wrong for the
     * rest of the session.
     */
    private void buildCoversTable() {
        int revision = level.blocks.revision();
        if (level.blocks == coversFrom && revision == coversCount) return;
        coversFrom = level.blocks;
        coversCount = revision;
        java.util.List<Block> all = level.blocks.all();
        int top = 0;
        for (Block b : all) top = Math.max(top, b.id());
        if (coversById.length < top + 1) coversById = new boolean[top + 1];
        if (blockById.length < top + 1) blockById = new Block[top + 1];
        if (sheetFrame.length < top + 1) {
            sheetFrame = new int[top + 1];
            topById = new BufferedImage[top + 1];
            sideById = new BufferedImage[top + 1];
        }
        java.util.Arrays.fill(sheetFrame, frameSeq - 1);   // nothing resolved yet
        // A level in palette mode has no block definitions at all, and its tile
        // ids are opaque paint: every one of them covers, which is what the
        // default of "no entry" has to mean rather than "not opaque".
        java.util.Arrays.fill(coversById, true);
        java.util.Arrays.fill(blockById, null);
        coversById[0] = false;
        for (Block b : all) {
            coversById[b.id()] = b.covers();
            blockById[b.id()] = b;
        }
    }

    /**
     * The block an id names, from the same table {@link #covers} reads — one
     * array index where the column sweep used to hash an {@code Integer} per
     * layer of every cell it drew.
     */
    private Block blockOf(int id) {
        if (id <= 0) return null;
        if (id < blockById.length) return blockById[id];
        return level.blocks.get(id);
    }

    /**
     * Queue every exposed, camera-facing block face the eye can actually see.
     *
     * <p>The sweep is a square of cells trimmed to a circle, because a square of
     * view distance is a third more cells than a circle of it and the corner
     * ones are the furthest away — the least worth drawing and the most of
     * them. Two culls then run over what is left, and between them they are why
     * a long render distance is affordable at all:
     *
     * <ul>
     *   <li><b>The frustum</b> ({@link EyeCamera#boxVisible}). A screen is a
     *       wedge of the disc and not the disc — about a quarter of it at an
     *       ordinary field of view — and this used to be a half-space test
     *       against the heading, which keeps everything <em>beside</em> you as
     *       well as everything in front. Four exact planes drop three cells in
     *       four before anything about their contents is read.</li>
     *   <li><b>Line of sight</b> ({@link #hiddenBehindHorizon}). A column of
     *       ground standing between the eye and a cell hides that cell, and a
     *       heightfield says so without tracing anything: sweep outward, and
     *       remember for each direction how high the terrain has already
     *       reached. Everything behind and below that line is not drawn. Inside
     *       a room it removes the world outside the walls; in mountains it
     *       removes the far side of the ridge; on an open plain it removes
     *       nothing, correctly, because on an open plain you can see the
     *       plain.</li>
     * </ul>
     *
     * <p><b>What the two are worth.</b> Over generated terrain at
     * 1280&times;720, measured: 12&nbsp;ms a frame becomes 9 at the default
     * render distance of twenty tiles, 60 becomes 20 at sixty-four, 256 becomes
     * 34 at a hundred and twenty-eight, and 688&nbsp;ms becomes 47 at the
     * slider's own ceiling of a hundred and ninety-two. The far end of that is
     * the point: a long render distance used to be a setting that could not be
     * used, and what it costs now grows with how much of the world can be seen
     * rather than with the area of the disc.
     *
     * <p><b>Which is why the sweep runs in rings.</b> Line of sight is only
     * sound if a cell is tested against occluders that are actually in front of
     * it, so the cells are visited nearest first — squares of growing radius
     * about the eye's own cell, rather than the rows of a rectangle. Chebyshev
     * rings are only approximately sorted by distance (a ring's corner is
     * further than the next ring's edge), so the horizon also remembers how far
     * away the column that set it was, and a cell nearer than that is drawn
     * whatever the height says.
     */
    public void terrain() {
        if (level == null || eye == null) return;
        // Drawn from the world as it stands, not from a world this frame builds
        // for itself. A generated chunk is tens of milliseconds, the sweep asks
        // for one every time the view distance reaches ground the streamer has
        // not got to yet, and it asks on the loop thread — so the frame that
        // walks over a chunk line used to pay for a chunk, or several. What is
        // missing this frame is drawn next frame, at the far edge of the view,
        // in the fog. See Level.setBuildOnRead.
        boolean building = level.setBuildOnRead(false);
        try {
            sweep();
        } finally {
            level.setBuildOnRead(building);
        }
    }

    /** {@link #terrain()}, once the world has been told not to grow under it. */
    private void sweep() {
        int ts = level.tileSize;
        if (ts <= 0) return;
        double reach = detailDistance;
        int c0 = Math.max(0, (int) Math.floor((eye.x() - reach) / ts));
        int c1 = Math.min(level.width - 1, (int) Math.floor((eye.x() + reach) / ts));
        int r0 = Math.max(0, (int) Math.floor((eye.y() - reach) / ts));
        int r1 = Math.min(level.height - 1, (int) Math.floor((eye.y() + reach) / ts));
        if (c0 > c1 || r0 > r1) return;

        clearHorizon();
        double reachSq = reach * reach;
        int ec = Math.max(c0, Math.min(c1, (int) Math.floor(eye.x() / ts)));
        int er = Math.max(r0, Math.min(r1, (int) Math.floor(eye.y() / ts)));
        int rings = Math.max(Math.max(ec - c0, c1 - ec), Math.max(er - r0, r1 - er));
        for (int k = 0; k <= rings; k++) {
            if (k == 0) {
                cell(ec, er, reachSq);
                continue;
            }
            int left = ec - k, right = ec + k, up = er - k, down = er + k;
            for (int c = Math.max(c0, left); c <= Math.min(c1, right); c++) {
                if (up >= r0) cell(c, up, reachSq);
                if (down <= r1) cell(c, down, reachSq);
            }
            for (int r = Math.max(r0, up + 1); r <= Math.min(r1, down - 1); r++) {
                if (left >= c0) cell(left, r, reachSq);
                if (right <= c1) cell(right, r, reachSq);
            }
        }
    }

    /**
     * One cell of the sweep: cull it, draw it, and let it raise the horizon
     * behind itself.
     */
    private void cell(int col, int row, double reachSq) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;
        double dx = x0 + ts * 0.5 - eye.x(), dy = y0 + ts * 0.5 - eye.y();
        double centreSq = dx * dx + dy * dy;
        if (centreSq > reachSq) return;

        int depth = level.columnDepth(col, row);
        // The whole column plus a tile of headroom, which is what a plant's
        // billboard and an actor's shadow can add to a cell's own height.
        double topZ = Math.max(0, depth) * (double) ts + ts;
        if (!eye.boxVisible(x0, y0, -ts, x1, y1, topZ)) return;

        double centre = Math.sqrt(centreSq);
        double near = length(axisGap(eye.x(), x0, x1), axisGap(eye.y(), y0, y1));
        double far = length(Math.max(Math.abs(eye.x() - x0), Math.abs(eye.x() - x1)),
                Math.max(Math.abs(eye.y() - y0), Math.abs(eye.y() - y1)));
        double angle = pseudoAngle(dx, dy);
        if (hiddenBehindHorizon(angle, near, far, topZ, ts)) return;

        column(col, row);
        raiseHorizon(angle, centre, groundedTopZ(col, row, depth), ts);
    }

    /**
     * {@code √(x² + y²)}, which is what {@link Math#hypot} means and about
     * twenty times what it costs.
     *
     * <p>That method's contract is that it never overflows and never loses
     * precision to an intermediate square, which it pays for with a branchy
     * scaled algorithm that the JIT does not intrinsify on every platform. The
     * distances here are tile counts on a level whose coordinates fit in an
     * {@code int}, so the overflow it is guarding against cannot happen — and
     * the guard was being paid for twice per cell of a sweep that visits a
     * hundred thousand of them.
     */
    private static double length(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    /**
     * How many directions the horizon is remembered in.
     *
     * <p>A thousand, which is finer than a cell subtends at any distance this
     * pass draws at: a tile at the furthest render distance the settings offer
     * is about a three-hundredth of a radian across, and a sector here is a
     * six-hundredth. Finer would cost the sweep more array reads than the
     * culling saves; coarser would let a doorway's worth of visible world be
     * rounded away into the wall beside it.
     */
    private static final int HORIZON_SECTORS = 1024;

    /**
     * How high the terrain has already reached in each direction, as a
     * <em>slope</em> — {@code (height − eye height) / horizontal distance} —
     * and how far away the column that set it was.
     *
     * <p>Slopes rather than angles because nothing here needs an angle: they
     * order the same way, and an arc tangent per cell per sector is the one
     * cost this cull cannot afford.
     */
    private final double[] horizonSlope = new double[HORIZON_SECTORS];
    private final double[] horizonAt = new double[HORIZON_SECTORS];

    /**
     * The widest half-span a cell may have, in {@link #pseudoAngle} units,
     * before the line-of-sight test gives up and draws it.
     *
     * <p>Four tenths of a unit is a twentieth of the circle either side of the
     * cell's middle. A cell that subtends more than that is a cell you are
     * standing next to, which is never the one worth culling, and testing it
     * would walk a tenth of the buffer to find that out.
     */
    private static final double MAX_TEST_SPAN = 0.4;

    private void clearHorizon() {
        java.util.Arrays.fill(horizonSlope, Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(horizonAt, Double.POSITIVE_INFINITY);
    }

    /**
     * A direction on the ground plane as a number in {@code [0, 4)}, increasing
     * with the true bearing.
     *
     * <p>The "diamond angle": the same ordering {@link Math#atan2} gives, for
     * two divides and no transcendental. Only the ordering is used — the
     * buffer is a bucketing of directions, not a measurement of them — and
     * {@link #SPAN_SKEW} carries the one place that distinction leaks.
     */
    private static double pseudoAngle(double dx, double dy) {
        double sum = Math.abs(dx) + Math.abs(dy);
        if (sum <= 0) return 0;
        double p = dx / sum;
        return dy < 0 ? 3 + p : 1 - p;
    }

    /**
     * How much a true angle can be stretched by {@link #pseudoAngle}, per
     * radian. The diamond angle runs at up to one unit per radian along the
     * axes and as little as a half at the diagonals; using the larger for a
     * span that must not be under-stated and the smaller for one that must not
     * be over-stated is what keeps both uses of it safe.
     */
    private static final double SPAN_SKEW = 1.0;
    private static final double SPAN_SKEW_SAFE = 0.5;

    /**
     * Whether a column is entirely behind terrain already drawn in front of it.
     *
     * <p>The test is the reason the sweep can afford a long render distance,
     * and it is exact for a heightfield rather than a guess. Take the highest
     * point of this cell and the shortest distance to it: that is the steepest
     * line of sight anything in the cell could occupy. If, in <em>every</em>
     * direction the cell can be seen along, the ground has already come up
     * higher than that line — and did so nearer than this cell is — then no ray
     * that reaches this cell got past the ground, and there is nothing here to
     * draw.
     *
     * <p><b>Which distance the steepest line uses depends on which side of the
     * eye the cell's top is.</b> Above the eye the line is steepest at the
     * cell's <em>near</em> edge, because the rise is divided by less. Below it
     * the numerator is negative, so the same division makes the angle
     * <em>more</em> negative, and the shallowest — that is, highest — line to a
     * cell below you is the one to its far edge. Using the near distance for
     * both is the one way this test can be wrong in the direction that matters:
     * it would understate how high a low cell can be seen along, and cull
     * something the player is looking straight at.
     *
     * @param near the shortest horizontal distance to the cell
     * @param far  the longest, which is the one a cell below the eye is seen
     *             along most shallowly
     * @param topZ the highest point anything in this cell reaches
     * @param width how wide the thing being tested is on the ground, in world
     *              units — one tile for a cell of the sweep, a whole group for
     *              a box of the coarse pass
     */
    private boolean hiddenBehindHorizon(double angle, double near, double far, double topZ,
                                        double width) {
        if (near < width) return false;   // standing in it, or against it
        double span = SPAN_SKEW * (width * 0.7072 / near);
        if (span > MAX_TEST_SPAN) return false;
        double slope = (topZ - eye.z()) / Math.max(1e-6, topZ >= eye.z() ? near : far);
        int from = sectorOf(angle - span), to = sectorOf(angle + span);
        int count = Math.floorMod(to - from, HORIZON_SECTORS);
        for (int i = 0; i <= count; i++) {
            int s = (from + i) % HORIZON_SECTORS;
            if (horizonAt[s] > near || horizonSlope[s] < slope) return false;
        }
        return true;
    }

    /**
     * Record that this column blocks every line of sight below its own top, in
     * the directions it certainly covers.
     *
     * <p>Certainly, and only certainly: the span written is narrower than the
     * cell's silhouette and the height is measured at the cell's <em>centre</em>
     * rather than at its near edge, so a ray counted as blocked really did pass
     * through the column. Under-claiming costs a few cells that could have been
     * culled; over-claiming would delete something the player can see, which is
     * the failure a cull is not allowed to have.
     */
    private void raiseHorizon(double angle, double centre, double topZ, double width) {
        if (topZ == Double.NEGATIVE_INFINITY) return;
        if (centre < width * 0.5) return;
        double span = SPAN_SKEW_SAFE * (width * 0.4 / centre);
        double slope = (topZ - eye.z()) / centre;
        int from = sectorOf(angle - span), to = sectorOf(angle + span);
        int count = Math.floorMod(to - from, HORIZON_SECTORS);
        for (int i = 0; i <= count; i++) {
            int s = (from + i) % HORIZON_SECTORS;
            if (slope > horizonSlope[s]) {
                horizonSlope[s] = slope;
                horizonAt[s] = centre;
            }
        }
    }

    private static int sectorOf(double pseudo) {
        return Math.floorMod((int) Math.floor(pseudo * (HORIZON_SECTORS / 4.0)),
                HORIZON_SECTORS);
    }

    /**
     * The height of the unbroken run of opaque blocks standing on this cell's
     * floor — how high this column occludes to, and no higher.
     *
     * <p><b>Not the top of the column.</b> The classic heightfield answer would
     * be, and it is wrong in exactly the two places this engine has: a cave
     * mouth is a column whose middle is empty under a mountain of rock, and
     * treating it as solid to the summit paints out the whole world seen
     * through it. So the run is measured from the ground and stops at the first
     * gap. It stops at the first thing you can see <em>through</em> as well —
     * water, glass — because a lake's surface hides nothing behind it.
     *
     * @return the world height the column occludes up to, or
     *         {@link Double#NEGATIVE_INFINITY} when it occludes nothing at all
     */
    private double groundedTopZ(int col, int row, int depth) {
        if (!covers(level.tileAt(col, row, Level.LAYER_GROUND))) {
            return Double.NEGATIVE_INFINITY;   // a hole in the floor
        }
        int grounded = Math.min(level.groundedDepth(col, row), Math.max(0, depth - 1));
        // Only the run's own lid has to be checked for transparency: a pane
        // buried inside a hillside is not a thing anyone can look through, and
        // walking the whole run to find one would cost more than the cull saves.
        while (grounded > 0 && !covers(level.tileAt(col, row, grounded))) grounded--;
        return grounded * (double) level.tileSize;
    }

    /** How far the furthest thing this frame draws can be. */
    private double horizon() {
        return Math.max(viewDistance, distantDistance);
    }

    /**
     * Queue the world beyond the detailed distance as coarse boxes — the
     * engine's answer to a landscape that otherwise ends in fog thirty paces
     * out, and to a render distance nothing could afford a block at a time.
     *
     * <p><b>Where it starts and where it stops.</b> It starts at
     * {@link #setDetailTiles the detail distance}, so the world between there
     * and the render distance is drawn <em>here</em> rather than not at all;
     * and it stops at the further of the render distance and whatever extra
     * horizon {@link #setDistantTiles} asked for. That is the whole reason a
     * render distance of a hundred and ninety-two blocks is now a setting a
     * machine can hold a frame rate at: the detailed sweep's cost is bounded by
     * the detail radius, and everything past it costs a few thousand quads
     * whatever the number says.
     *
     * <p><b>What is drawn.</b> One box per square group of cells, standing on
     * the floor, as tall as the tallest column in the group and in that
     * column's own colour: a hill reads as a hill and a wall as a wall, for a
     * few hundred flat quads over the whole horizon rather than the hundred
     * thousand block faces the detailed sweep would queue at that range. No
     * textures — a 32-pixel sheet stretched over four tiles is not that block,
     * it is a smear — no per-block faces, and the sides only where the eye is
     * on that side.
     *
     * <p><b>In rings, each coarser than the one inside it.</b> One group size
     * over the whole distance is wrong at both ends: fine enough to meet the
     * detailed terrain without a visible step is tens of thousands of groups
     * out at the horizon, and coarse enough to afford the horizon puts a
     * staircase of eight-tile steps right where the player can see what it
     * should have been. So each ring reaches {@link #RING_SPLIT} times as far
     * as the last and is drawn at {@link #groupFor} that distance — the same
     * trade Distant Horizons makes and for the same reason. The error stays
     * roughly one <em>angular</em> size all the way out, and so does the cost
     * per ring, which is what lets the horizon reach two thousand blocks for
     * about what it used to spend on a hundred and twenty.
     *
     * <p><b>Every ring overlaps the one inside it</b> rather than abutting it:
     * a box is drawn whenever any part of its group falls outside the inner
     * radius, so the boundary is covered twice and never missed. It has to be —
     * a group is square and the radius is round, so groups that straddle the
     * circle would otherwise leave a gap the width of a group, which is a
     * ring of bare sky between the terrain you are standing on and the terrain
     * on the horizon. What resolves the overlap is {@link #orderBias}: each
     * ring sorts strictly behind the ring inside it, and all of them behind the
     * detailed pass, whatever the cell arithmetic says.
     */
    public void distant() {
        if (level == null || eye == null) return;
        int ts = level.tileSize;
        if (ts <= 0) return;
        double outermost = horizon();
        double inner = detailDistance;
        if (outermost <= inner) return;
        // As with the detailed sweep: what the horizon draws is the world that
        // is already there. A coarse box asks the level for the tallest column
        // in a group of cells, and on a generated world that is answered from
        // sampled heights rather than from chunks — but an ordinary level's
        // groupTop reads cells, and a level that is both would otherwise build
        // ground two thousand blocks away to put a box on it.
        com.larsons.engine.world.gen.WorldLod lod = level.lod();
        boolean building = level.setBuildOnRead(false);
        try {
            for (int behind = 1; inner < outermost && behind <= MAX_RINGS; behind++) {
                double outer = Math.min(outermost, inner * RING_SPLIT);
                if (lod != null) lodBand(lod, inner, outer, behind);
                else ring(groupFor(inner), inner, outer, behind);
                inner = outer;
            }
        } finally {
            level.setBuildOnRead(building);
        }
    }

    /**
     * How wide one sample of the level-of-detail terrain is drawn, as a
     * fraction of how far away it is.
     *
     * <p>The whole level-of-detail policy in one number: a sample subtends this
     * much wherever it is, so what the far field costs is a function of the
     * <em>screen</em> rather than of the distance, and pushing the horizon from
     * four hundred blocks to four thousand adds rings that each cost what the
     * first one did. A tenth of a radian is about a fifteenth of the screen's
     * width at an ordinary field of view — coarse, which is what a landscape
     * two thousand blocks away is entitled to be, and finer than the fifth of a
     * radian this drew before {@link com.larsons.engine.world.gen.WorldLod}
     * made the samples cheap enough to merge.
     */
    private static final double LOD_SAMPLE_ANGLE = 0.10;

    /**
     * One band of the level-of-detail terrain: the tiles between {@code inner}
     * and {@code outer}, at the level whose samples are the right size there.
     *
     * <p>Nearest tile first, so the line-of-sight cull is testing against
     * ground that is actually in front — the same order and the same buffer the
     * detailed sweep uses, which by now holds the silhouette of everything
     * drawn in detail.
     */
    private void lodBand(com.larsons.engine.world.gen.WorldLod lod,
                         double inner, double outer, int behind) {
        int ts = level.tileSize;
        int lodLevel = com.larsons.engine.world.gen.WorldLod.levelFor(inner, ts,
                LOD_SAMPLE_ANGLE);
        int cells = com.larsons.engine.world.gen.WorldLod.tileCells(lodLevel);
        double side = cells * (double) ts;
        int across = lod.tilesAcross(lodLevel);
        int t0c = Math.max(0, (int) Math.floor((eye.x() - outer) / side));
        int t1c = Math.min(across - 1, (int) Math.floor((eye.x() + outer) / side));
        int t0r = Math.max(0, (int) Math.floor((eye.y() - outer) / side));
        int t1r = Math.min(across - 1, (int) Math.floor((eye.y() + outer) / side));
        if (t0c > t1c || t0r > t1r) return;

        faceReach = outer;
        orderBias = DISTANT_ORDER * behind;
        try {
            int ec = Math.max(t0c, Math.min(t1c, (int) Math.floor(eye.x() / side)));
            int er = Math.max(t0r, Math.min(t1r, (int) Math.floor(eye.y() / side)));
            int rings = Math.max(Math.max(ec - t0c, t1c - ec), Math.max(er - t0r, t1r - er));
            for (int k = 0; k <= rings; k++) {
                if (k == 0) {
                    lodTile(lod, lodLevel, ec, er, side, inner, outer);
                    continue;
                }
                int left = ec - k, right = ec + k, up = er - k, down = er + k;
                for (int tc = Math.max(t0c, left); tc <= Math.min(t1c, right); tc++) {
                    if (up >= t0r) lodTile(lod, lodLevel, tc, up, side, inner, outer);
                    if (down <= t1r) lodTile(lod, lodLevel, tc, down, side, inner, outer);
                }
                for (int tr = Math.max(t0r, up + 1); tr <= Math.min(t1r, down - 1); tr++) {
                    if (left >= t0c) lodTile(lod, lodLevel, left, tr, side, inner, outer);
                    if (right <= t1c) lodTile(lod, lodLevel, right, tr, side, inner, outer);
                }
            }
        } finally {
            faceReach = detailDistance;
            orderBias = 0;
        }
    }

    /** One tile of a band: cull the whole tile, then draw the boxes it merged into. */
    private void lodTile(com.larsons.engine.world.gen.WorldLod lod, int lodLevel,
                         int tc, int tr, double side, double inner, double outer) {
        int ts = level.tileSize;
        double x0 = tc * side, y0 = tr * side;
        double cx = x0 + side / 2 - eye.x(), cy = y0 + side / 2 - eye.y();
        // A tile is square and the band is round, so a tile is kept when any of
        // it falls in the band rather than when its middle does — otherwise the
        // boundary between two bands is a ring of missing world a tile wide.
        double nearest = length(axisGap(eye.x(), x0, x0 + side),
                axisGap(eye.y(), y0, y0 + side));
        if (nearest > outer) return;
        if (length(Math.abs(cx) + side / 2, Math.abs(cy) + side / 2) <= inner) return;
        // The whole tile against the frustum before a single box of it is read:
        // this is what a thousand samples costs when the tile is behind you.
        if (!eye.boxVisible(x0, y0, 0, x0 + side, y0 + side,
                level.layerCount() * (double) ts)) {
            return;
        }
        int[] mesh = lod.tile(lodLevel, tc, tr);
        if (mesh == null) return;   // still being built; it arrives in a frame or two
        int stride = com.larsons.engine.world.gen.WorldLod.BOX_STRIDE;
        for (int i = 0; i + stride <= mesh.length; i += stride) {
            lodBox(mesh[i], mesh[i + 1], mesh[i + 2], mesh[i + 3],
                    mesh[i + 4], mesh[i + 5], inner, outer);
        }
    }

    /**
     * One merged box of level-of-detail ground, as a solid.
     *
     * <p><b>Its height is exact over its whole footprint</b>, which the coarse
     * pass this replaced could not say: that drew a fixed group of cells at the
     * height of the <em>tallest</em> column in it, so it could neither be
     * trusted to occlude (a spike would have culled the valley behind it) nor
     * drawn without inventing ground that is not there. A greedy-meshed box is
     * a rectangle the ground is level across, so it occludes at its own height
     * and draws at its own height, and both are the truth.
     */
    private void lodBox(int c0, int r0, int c1, int r1, int layer, int id,
                        double inner, double outer) {
        if (c0 >= c1 || r0 >= r1) return;
        int ts = level.tileSize;
        double x0 = c0 * (double) ts, x1 = c1 * (double) ts;
        double y0 = r0 * (double) ts, y1 = r1 * (double) ts;
        double nearest = length(axisGap(eye.x(), x0, x1), axisGap(eye.y(), y0, y1));
        if (nearest > outer) return;
        // Inside the band's inner edge is somebody else's to draw — the band
        // within, or the detailed sweep.
        double dx = (x0 + x1) / 2 - eye.x(), dy = (y0 + y1) / 2 - eye.y();
        if (length(Math.abs(dx) + (x1 - x0) / 2, Math.abs(dy) + (y1 - y0) / 2) <= inner) {
            return;
        }
        double z1 = layer * (double) ts;
        if (!eye.boxVisible(x0, y0, 0, x1, y1, z1 + ts)) return;

        double far = length(Math.max(Math.abs(eye.x() - x0), Math.abs(eye.x() - x1)),
                Math.max(Math.abs(eye.y() - y0), Math.abs(eye.y() - y1)));
        double angle = pseudoAngle(dx, dy);
        double width = Math.max(x1 - x0, y1 - y0);
        if (hiddenBehindHorizon(angle, nearest, far, z1 + ts, width)) return;

        Color colour = level.colorFor(id);
        if (colour == null) return;
        int col = (c0 + c1) / 2, row = (r0 + r1) / 2;
        if (eye.z() > z1) {
            face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    colour, SHADE_TOP, null);
        }
        if (z1 > 0) {
            if (eye.y() < y0) {
                face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y0, 0, x0, y0, 0,
                        colour, SHADE_NORTH_SOUTH, null);
            }
            if (eye.y() > y1) {
                face(col, row, layer, x1, y1, z1, x0, y1, z1, x0, y1, 0, x1, y1, 0,
                        colour, SHADE_NORTH_SOUTH, null);
            }
            if (eye.x() > x1) {
                face(col, row, layer, x1, y0, z1, x1, y1, z1, x1, y1, 0, x1, y0, 0,
                        colour, SHADE_EAST_WEST, null);
            }
            if (eye.x() < x0) {
                face(col, row, layer, x0, y1, z1, x0, y0, z1, x0, y0, 0, x0, y1, 0,
                        colour, SHADE_EAST_WEST, null);
            }
        }
        // Level across its whole footprint, so this is exact rather than the
        // under-claim a group of cells had to settle for.
        raiseHorizon(angle, length(dx, dy), z1, Math.min(x1 - x0, y1 - y0));
    }

    /**
     * How many cells to a box in a ring that starts {@code inner} away.
     *
     * <p><b>Proportional to the distance, not a constant.</b> A box should
     * subtend about the same angle wherever it is — that is the whole argument
     * for rings — and a box of a fixed number of cells does not: four cells is
     * a tenth of the screen at forty paces and a pixel at four hundred. Fixing
     * the cell count instead fixes the <em>cost</em> to the distance, and badly:
     * at the default render distance the near ring is six hundred boxes, and at
     * a render distance of 192 the same constant makes it six thousand, which
     * is the difference between a horizon and a slideshow.
     *
     * <p>The factor is chosen so that this reproduces the constants the rings
     * were originally tuned with at the default render distance — four cells at
     * twenty, sixteen at sixty — and goes on meaning the same thing at any
     * other.
     */
    private int groupFor(double innerDistance) {
        double tiles = innerDistance / Math.max(1, level.tileSize);
        return Math.max(NEAR_RING_CELLS, (int) Math.round(tiles * RING_ANGLE));
    }

    /**
     * One ring of coarse boxes, {@code group} cells to a box.
     *
     * <p><b>Nearest group first, for the same reason the detailed sweep visits
     * cells in rings:</b> line of sight is only sound if a box is tested
     * against terrain that is actually in front of it. The horizon buffer
     * arrives here already carrying the silhouette of everything the detailed
     * sweep drew, which is what removes the far side of the valley the player
     * is standing in — and on a landscape that is most of the ring.
     */
    private void ring(int group, double inner, double outer, int behindDetail) {
        if (outer <= inner) return;
        int ts = level.tileSize;
        double span = group * (double) ts;
        int g0c = Math.max(0, (int) Math.floor((eye.x() - outer) / span));
        int g1c = Math.min((level.width - 1) / group, (int) Math.floor((eye.x() + outer) / span));
        int g0r = Math.max(0, (int) Math.floor((eye.y() - outer) / span));
        int g1r = Math.min((level.height - 1) / group, (int) Math.floor((eye.y() + outer) / span));
        if (g0c > g1c || g0r > g1r) return;

        faceReach = outer;
        orderBias = DISTANT_ORDER * behindDetail;
        try {
            int ec = Math.max(g0c, Math.min(g1c, (int) Math.floor(eye.x() / span)));
            int er = Math.max(g0r, Math.min(g1r, (int) Math.floor(eye.y() / span)));
            int rings = Math.max(Math.max(ec - g0c, g1c - ec), Math.max(er - g0r, g1r - er));
            for (int k = 0; k <= rings; k++) {
                if (k == 0) {
                    distantGroup(ec, er, group, span, inner, outer);
                    continue;
                }
                int left = ec - k, right = ec + k, up = er - k, down = er + k;
                for (int gc = Math.max(g0c, left); gc <= Math.min(g1c, right); gc++) {
                    if (up >= g0r) distantGroup(gc, up, group, span, inner, outer);
                    if (down <= g1r) distantGroup(gc, down, group, span, inner, outer);
                }
                for (int gr = Math.max(g0r, up + 1); gr <= Math.min(g1r, down - 1); gr++) {
                    if (left >= g0c) distantGroup(left, gr, group, span, inner, outer);
                    if (right <= g1c) distantGroup(right, gr, group, span, inner, outer);
                }
            }
        } finally {
            faceReach = detailDistance;
            orderBias = 0;
        }
    }

    /** One group of a ring: cull it, and draw the box that stands for it. */
    private void distantGroup(int gc, int gr, int cells, double span,
                              double inner, double outer) {
        double half = span / 2;
        double dx = (gc + 0.5) * span - eye.x();
        double dy = (gr + 0.5) * span - eye.y();
        if (dx * dx + dy * dy > outer * outer) return;
        // Kept when any corner of the group is outside the inner radius, not
        // when its centre is: a square group straddling a round boundary
        // belongs to both sides, and dropping it from this one leaves a gap
        // the width of a group.
        if (length(Math.abs(dx) + half, Math.abs(dy) + half) <= inner) return;
        // The frustum, exactly, where this used to keep everything in the
        // forward half-plane: a horizon ring is thousands of groups and three
        // quarters of them are beside or behind the screen. The box is given
        // the level's full height, since how tall the group is has not been
        // asked yet.
        double gx = gc * span, gy = gr * span;
        int ts = level.tileSize;
        if (!eye.boxVisible(gx, gy, 0, gx + span, gy + span,
                level.layerCount() * (double) ts)) {
            return;
        }
        distantBox(gc, gr, cells, span, dx, dy);
    }

    /** One coarse box: the tallest column in a group of cells, as a solid. */
    private void distantBox(int gc, int gr, int group, double span, double dx, double dy) {
        int ts = level.tileSize;
        int c0 = gc * group, r0 = gr * group;
        int c1 = Math.min(level.width, c0 + group), r1 = Math.min(level.height, r0 + group);
        if (c0 >= c1 || r0 >= r1) return;

        // Asked of the level rather than swept here, because a generated world
        // answers it from sampled heights: the horizon reaches two thousand
        // blocks, and generating the sixteen million columns inside that to
        // find out how tall they are would cost more than drawing the world in
        // detail. An ordinary level does the sweep this used to do.
        if (!level.groupTop(c0, r0, c1, r1, groupScratch)) return;
        int tallest = groupScratch[0];
        int tallestId = groupScratch[1];

        Color colour = level.colorFor(tallestId);
        if (colour == null) return;
        double x0 = c0 * (double) ts, x1 = c1 * (double) ts;
        double y0 = r0 * (double) ts, y1 = r1 * (double) ts;
        double z1 = tallest * (double) ts;
        int col = (c0 + c1) / 2, row = (r0 + r1) / 2;

        // Line of sight, the same test the detailed sweep makes and against the
        // same buffer — which by now holds the silhouette of everything drawn
        // in detail, and of the nearer boxes of this ring. It is worth far more
        // here than the quad count suggests: a coarse box is enormous on
        // screen, so what the horizon saves is pixels rather than draws, and
        // the hillside in front of the player hides most of the ring behind it.
        // Asked once the height is known, because the height is the whole of
        // what the test is about.
        double near = length(axisGap(eye.x(), x0, x1), axisGap(eye.y(), y0, y1));
        double far = length(Math.max(Math.abs(eye.x() - x0), Math.abs(eye.x() - x1)),
                Math.max(Math.abs(eye.y() - y0), Math.abs(eye.y() - y1)));
        double angle = pseudoAngle(dx, dy);
        if (hiddenBehindHorizon(angle, near, far, z1 + ts, span)) return;
        // …and it raises the horizon in its turn, at the height of the
        // *lowest* ground in the group rather than the highest. A box is drawn
        // at the height of its tallest column, and claiming a whole group is
        // solid to the top of one spike in it would cull the valley behind that
        // spike — which is the one failure a cull is not allowed to have. The
        // low mark under-claims by construction, and a range of hills is still
        // a range of hills.
        raiseHorizon(angle, length(dx, dy), groupScratch[2] * (double) ts, span);

        if (eye.z() > z1) {
            face(col, row, tallest, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    colour, SHADE_TOP, null);
        }
        if (z1 <= 0) return; // flat ground: the lid is the whole of it
        if (eye.y() < y0) {
            face(col, row, tallest, x0, y0, z1, x1, y0, z1, x1, y0, 0, x0, y0, 0,
                    colour, SHADE_NORTH_SOUTH, null);
        }
        if (eye.y() > y1) {
            face(col, row, tallest, x1, y1, z1, x0, y1, z1, x0, y1, 0, x1, y1, 0,
                    colour, SHADE_NORTH_SOUTH, null);
        }
        if (eye.x() > x1) {
            face(col, row, tallest, x1, y0, z1, x1, y1, z1, x1, y1, 0, x1, y0, 0,
                    colour, SHADE_EAST_WEST, null);
        }
        if (eye.x() < x0) {
            face(col, row, tallest, x0, y1, z1, x0, y0, z1, x0, y0, 0, x0, y1, 0,
                    colour, SHADE_EAST_WEST, null);
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
        if (floorId > 0 && eye.z() > 0 && open(col, row, 1, floorId)) {
            Color colour = level.colorFor(floorId);
            if (colour != null) {
                Block ground = blockOf(floorId);
                face(col, row, 0, x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0,
                        colour, SHADE_TOP, topTexture(ground));
            }
        }

        int depth = level.columnDepth(col, row);
        // The column's own mesh, where there is one: which faces of which
        // layers exist, worked out when the ground last changed rather than
        // now. See WorldTerrain.columnFaces — it is the largest single thing
        // this pass does not do any more.
        int[] mesh = level.columnFaces(col, row);
        if (mesh != null) {
            for (int packed : mesh) {
                int layer = (packed >>> WorldTerrain.FACE_BITS) & WorldTerrain.LAYER_MASK;
                if (layer >= depth) break;
                int id = packed >>> (WorldTerrain.FACE_BITS + WorldTerrain.LAYER_BITS);
                Block block = blockOf(id);
                if (block == null) continue;
                if (block.plant()) plant(col, row, block, layer);
                else meshed(col, row, block, layer, packed & FACE_MASK);
            }
            return;
        }
        // Every layer of the column on a level whose columns are a handful of
        // blocks deep: there is nothing to save by picking through a stack of
        // three, and nothing to cache it on.
        int count = level.visibleLayers(col, row, layerScratch);
        for (int i = 0; i < count; i++) {
            int layer = layerScratch[i];
            int id = level.tileAt(col, row, layer);
            if (id <= 0) continue;
            Block block = blockOf(id);
            if (block == null) continue;
            if (block.plant()) plant(col, row, block, layer);
            else block(col, row, block, layer, depth);
        }
    }

    /** Every bit of a packed face entry that is a face. */
    private static final int FACE_MASK = (1 << WorldTerrain.FACE_BITS) - 1;

    /**
     * The faces of one block, from the column's cached mesh: which of them
     * <em>exist</em> is read rather than worked out, and which of them face the
     * eye is still asked here.
     *
     * <p><b>The two questions are different and only one of them can be
     * cached.</b> Whether a face is exposed is a fact about six cells of the
     * world and changes only when one of them does; whether it points at the
     * eye changes every time the player moves, and is one comparison. Splitting
     * them is what makes a chunk mesh worth keeping — see
     * {@link WorldTerrain#columnFaces}.
     */
    private void meshed(int col, int row, Block block, int layer, int mask) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;
        double z0 = (layer - 1) * (double) ts;
        double z1 = layer * (double) ts;
        Color colour = block.color();
        // The eye's own cell is open whatever is standing in it. The mesh
        // cannot know that — it is a fact about where somebody is, not about
        // the world — so the handful of cells around the eye put the faces back
        // by hand. Only the handful: this is nine cells of the hundred thousand
        // a sweep visits.
        if (Math.abs(col - eyeCol) <= 1 && Math.abs(row - eyeRow) <= 1) {
            int id = block.id();
            if (open(col, row, layer + 1, id)) mask |= WorldTerrain.FACE_UP;
            if (layer > 1 && open(col, row, layer - 1, id)) mask |= WorldTerrain.FACE_DOWN;
            if (open(col, row - 1, layer, id)) mask |= WorldTerrain.FACE_NORTH;
            if (open(col, row + 1, layer, id)) mask |= WorldTerrain.FACE_SOUTH;
            if (open(col + 1, row, layer, id)) mask |= WorldTerrain.FACE_EAST;
            if (open(col - 1, row, layer, id)) mask |= WorldTerrain.FACE_WEST;
        }
        BufferedImage top = null, side = null;
        if ((mask & (WorldTerrain.FACE_UP | WorldTerrain.FACE_DOWN)) != 0) {
            top = topTexture(block);
        }
        if ((mask & (WorldTerrain.FACE_NORTH | WorldTerrain.FACE_SOUTH
                | WorldTerrain.FACE_EAST | WorldTerrain.FACE_WEST)) != 0) {
            side = sideTexture(block);
        }
        if ((mask & WorldTerrain.FACE_UP) != 0 && eye.z() > z1) {
            face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    colour, SHADE_TOP, top);
        }
        // Layer 1 stands on the floor, which is a surface rather than a cell:
        // its underside is the same quad as the floor's own lid, and drawing
        // both is two coplanar fills fighting over the pixels.
        if ((mask & WorldTerrain.FACE_DOWN) != 0 && layer > 1 && eye.z() < z0) {
            face(col, row, layer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0,
                    colour, SHADE_BOTTOM, top);
        }
        if ((mask & WorldTerrain.FACE_NORTH) != 0 && eye.y() < y0) {
            face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                    colour, SHADE_NORTH_SOUTH, side);
        }
        if ((mask & WorldTerrain.FACE_SOUTH) != 0 && eye.y() > y1) {
            face(col, row, layer, x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0,
                    colour, SHADE_NORTH_SOUTH, side);
        }
        if ((mask & WorldTerrain.FACE_EAST) != 0 && eye.x() > x1) {
            face(col, row, layer, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0,
                    colour, SHADE_EAST_WEST, side);
        }
        if ((mask & WorldTerrain.FACE_WEST) != 0 && eye.x() < x0) {
            face(col, row, layer, x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0,
                    colour, SHADE_EAST_WEST, side);
        }
    }

    /**
     * A growing thing: a stem standing in the middle of the cell, and a sprite
     * on it turned to face the eye. See {@link Block.Shape#PLANT}.
     *
     * <p><b>The two halves are drawn differently on purpose.</b> The stem is
     * ordinary geometry — a narrow box, four faces of it, back-face culled and
     * sorted like any other block — so it stays exactly where it was planted
     * and you can walk round it. The sprite is one quad built square to the
     * view, so it turns with the camera and never goes edge-on; it is queued
     * through the same {@link #face} the terrain uses, so it sorts against the
     * terrain by the cell it stands in and needs no second pass.
     *
     * <p>The sprite quad carries no backing fill and no outline. Both exist to
     * stop a fraction of a pixel of sky showing through a wall, and a plant is
     * mostly sky by design — filled and outlined it would be a coloured card
     * with a flower printed on it.
     */
    private void plant(int col, int row, Block block, int layer) {
        int ts = level.tileSize;
        double cx = (col + 0.5) * ts, cy = (row + 0.5) * ts;
        // Scenery has its own reach, and it is shorter than the ground's: a
        // plant is a stem and a billboard where a block is one quad, there are
        // more of them on a hillside than there are blocks of hillside, and at
        // a dozen chunks a flower is a green pixel that costs what a flower
        // costs. See DEFAULT_DECOR_TILES.
        double px = cx - eye.x(), py = cy - eye.y();
        if (px * px + py * py > decorDistance * decorDistance) return;
        double z0 = (layer - 1) * (double) ts;
        double half = ts * STEM_HALF_WIDTH;
        double stemTop = z0 + ts * STEM_HEIGHT;
        Color stem = STEM_GREEN;

        // The stalk. Only the two faces turned toward the eye are queued, by
        // the same comparison a cube's are — a box this narrow is still a box.
        if (eye.y() < cy - half) {
            face(col, row, layer, cx - half, cy - half, stemTop, cx + half, cy - half, stemTop,
                    cx + half, cy - half, z0, cx - half, cy - half, z0,
                    stem, SHADE_NORTH_SOUTH, null);
        }
        if (eye.y() > cy + half) {
            face(col, row, layer, cx + half, cy + half, stemTop, cx - half, cy + half, stemTop,
                    cx - half, cy + half, z0, cx + half, cy + half, z0,
                    stem, SHADE_NORTH_SOUTH, null);
        }
        if (eye.x() > cx + half) {
            face(col, row, layer, cx + half, cy - half, stemTop, cx + half, cy + half, stemTop,
                    cx + half, cy + half, z0, cx + half, cy - half, z0,
                    stem, SHADE_EAST_WEST, null);
        }
        if (eye.x() < cx - half) {
            face(col, row, layer, cx - half, cy + half, stemTop, cx - half, cy - half, stemTop,
                    cx - half, cy - half, z0, cx - half, cy + half, z0,
                    stem, SHADE_EAST_WEST, null);
        }

        // The foliage, square to the view: the quad's horizontal axis is the
        // eye's own right vector, so it is never seen edge-on however the
        // camera turns, and its vertical axis is the world's up, so a flower
        // stands up rather than leaning back as you look down at it.
        BufferedImage sprite = plantSprite(block);
        if (sprite == null) return;
        double rx = eye.rightX() * ts * PLANT_HALF_WIDTH;
        double ry = eye.rightY() * ts * PLANT_HALF_WIDTH;
        double base = z0 + ts * PLANT_BASE;
        double top = z0 + ts * PLANT_TOP;
        Entry e = face(col, row, layer,
                cx - rx, cy - ry, top, cx + rx, cy + ry, top,
                cx + rx, cy + ry, base, cx - rx, cy - ry, base,
                block.color(), SHADE_TOP, sprite);
        if (e == null) return;
        e.fill = false;
        e.edge = false;
        // Fog over a sprite would paint the whole quad, transparent parts
        // included, which is a rectangle of haze hanging in the air. A plant is
        // small enough that losing its share of the fog is invisible.
        e.fogOverlay = 0;
    }

    /** A plant's billboard: the pack's sheet where there is one, else drawn. */
    private BufferedImage plantSprite(Block block) {
        BufferedImage sheet = frame(block.textureKey());
        if (sheet != null) return sheet;
        return BlockSprites.procedural(block, PLANT_SPRITE_PIXELS);
    }

    /**
     * The faces of the one block in {@code layer} of this column that the eye
     * could see: exposed (nothing solid against them) and turned toward it.
     *
     * <p><b>One block at a time, not one run at a time.</b> This used to merge
     * a column of identical blocks into a single tall quad, which halved the
     * queue and cost two things worth more than that. A merged face spans
     * several cells of the height axis, so it has no single place in the
     * painter's order — a wall eight blocks tall is nearer than the block at
     * its foot and further than the block at its top at the same time, and
     * whatever one number it is given, some block in front of part of it sorts
     * behind. That is the "not all vertices understand what is in front of
     * what" a player sees as a wall drawn over the thing standing against it.
     * The other is texture: a stretched sheet over an eight-block wall is one
     * brick eight blocks high (the same defect the plan view had). Per block,
     * both answers fall out: every face is one cell, so {@link #cellOrder}
     * places it exactly, and every face is one block, so its texture is the
     * block's.
     */
    private void block(int col, int row, Block block, int layer, int depth) {
        int ts = level.tileSize;
        double x0 = col * (double) ts, x1 = x0 + ts;
        double y0 = row * (double) ts, y1 = y0 + ts;
        // Layer 0 is the floor, so the block in layer 1 stands ON it at z = 0.
        double z0 = (layer - 1) * (double) ts;
        double z1 = layer * (double) ts;
        Color colour = block.color();
        BufferedImage top = topTexture(block);
        BufferedImage side = sideTexture(block);

        int id = block.id();
        if (eye.z() > z1 && (layer + 1 >= depth || open(col, row, layer + 1, id))) {
            face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                    colour, SHADE_TOP, top);
        }
        if (eye.z() < z0 && layer > 1 && open(col, row, layer - 1, id)) {
            face(col, row, layer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0,
                    colour, SHADE_BOTTOM, top);
        }
        if (eye.y() < y0 && open(col, row - 1, layer, id)) {
            face(col, row, layer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                    colour, SHADE_NORTH_SOUTH, side);
        }
        if (eye.y() > y1 && open(col, row + 1, layer, id)) {
            face(col, row, layer, x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0,
                    colour, SHADE_NORTH_SOUTH, side);
        }
        if (eye.x() > x1 && open(col + 1, row, layer, id)) {
            face(col, row, layer, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0,
                    colour, SHADE_EAST_WEST, side);
        }
        if (eye.x() < x0 && open(col - 1, row, layer, id)) {
            face(col, row, layer, x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0,
                    colour, SHADE_EAST_WEST, side);
        }
    }

    /**
     * Whether the neighbouring box leaves this face visible, so it is worth
     * queueing.
     *
     * <p><b>Not "is the neighbour empty".</b> That was the test, and it is
     * wrong in two ways that a player finds in the first minute. A neighbour
     * you can see <em>through</em> — a pane of glass, a pool of water — does not
     * hide the face behind it, and treating it as if it did makes a wall vanish
     * when somebody glazes it. A neighbour that does not <em>fill</em> its cell
     * does not either, and that one is worse: a flower occupies a cell you can
     * walk into, so a player standing in one had every face around them culled
     * as covered and could look straight out through the world. The block
     * itself answers both ({@link Block#covers()}).
     *
     * <p><b>And not "is the neighbour opaque" either.</b> Two cells of the same
     * block have no surface between them however see-through that block is:
     * the inside of a lake is water, not a stack of panes, and drawing the
     * faces between them lays the same translucent blue down forty times and
     * turns a pool into ink. So a neighbour of {@code selfId} hides this face
     * as surely as a wall does. {@code WorldTerrain.visibleLayers} makes the
     * same distinction, which is what keeps the two passes agreeing about which
     * faces exist at all.
     *
     * <p>The eye's own cell is always open, whatever is standing in it. That is
     * belt and braces rather than a second rule — a cell the eye can be inside
     * is by definition not a covering one — but it costs a comparison and it is
     * the case the bug was reported from.
     *
     * @param selfId the block whose face this is, or {@code 0} to ask only
     *               whether the neighbour covers
     */
    private boolean open(int col, int row, int layer, int selfId) {
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return true;
        if (col == eyeCol && row == eyeRow && layer == eyeLayer) return true;
        int id = level.tileAt(col, row, layer);
        if (id == selfId && id > 0) return false;
        return !covers(id);
    }

    /**
     * Whether block {@code id} hides what is behind it, from a table built once
     * a frame.
     *
     * <p>A lookup rather than {@code level.blocks.get(id).covers()} because this
     * runs up to six times per block face and a map lookup per neighbour is a
     * measurable share of the sweep. The table is the registry's own, rebuilt
     * there when the set of blocks changes — including when a block is reshaped
     * in place, which a count of the registry cannot notice and which this used
     * to keep a stale answer for.
     */
    private boolean covers(int id) {
        if (id <= 0) return false;
        if (id < coversById.length) return coversById[id];
        return level.blocks.covers(id);
    }

    /**
     * The sheet a block's top face is drawn with, or {@code null} for the flat
     * colour — the same pools and the same fallbacks the plan view resolves
     * ({@link TerrainPainter}), so a texture pack dresses a block once and it
     * is that block in every view.
     */
    private BufferedImage topTexture(Block block) {
        if (block == null) return null;
        int id = block.id();
        if (id <= 0 || id >= sheetFrame.length) {
            return texture(block.topTextureKey(), block.textureKey());
        }
        if (sheetFrame[id] != frameSeq) resolveSheets(block, id);
        return topById[id];
    }

    /** The sheet a block's side faces are drawn with; see {@link #topTexture}. */
    private BufferedImage sideTexture(Block block) {
        if (block == null) return null;
        int id = block.id();
        if (id <= 0 || id >= sheetFrame.length) {
            return texture(block.sideTextureKey(), block.textureKey());
        }
        if (sheetFrame[id] != frameSeq) resolveSheets(block, id);
        return sideById[id];
    }

    /**
     * Work out both of a block's sheets, once per block per frame.
     *
     * <p><b>Resolving them per face was the most expensive thing the sweep
     * did</b>, and none of the cost was the lookup anyone would have suspected.
     * A block's texture key is <em>built</em> on every ask —
     * {@code "block/" + key + "_top"} — so a face cost two string
     * concatenations, two hashes of the results, and two map probes, several
     * times per cell, for an answer that is the same for every face of that
     * block in the whole frame. Measured over generated terrain it was more
     * than a quarter of the sweep. Indexed by block id here, with the frame
     * number beside it so nothing has to be cleared: an entry from last frame
     * is simply not this frame's, which also keeps animated sheets moving.
     */
    private void resolveSheets(Block block, int id) {
        topById[id] = texture(block.topTextureKey(), block.textureKey());
        sideById[id] = texture(block.sideTextureKey(), block.textureKey());
        sheetFrame[id] = frameSeq;
    }

    /** A face's frame, falling back to the block's one flat sheet; cached per frame. */
    private BufferedImage texture(String faceKey, String flatKey) {
        BufferedImage face = frame(faceKey);
        return face != null ? face : frame(flatKey);
    }

    private BufferedImage frame(String key) {
        if (key == null) return null;
        if (textures.containsKey(key)) return textures.get(key);
        BufferedImage img = Skins.frame(key, animClock);
        textures.put(key, img);
        return img;
    }

    /**
     * {@code sheet} baked for a face with this much light on it, remembered for
     * the rest of the frame.
     *
     * <p>{@link SolidTextures#shaded} is a synchronized method over a
     * least-recently-used map, which is the right shape for a cache that
     * outlives a frame and the wrong one to enter <b>per face</b>: a lock, a
     * hash and a list splice, tens of thousands of times a frame, for an answer
     * that cannot change while the frame is being drawn. There are four shades
     * and a level has a handful of sheets, so a frame asks for a couple of
     * dozen distinct bakes and then asks for them again and again.
     */
    private BufferedImage shaded(BufferedImage sheet, double shade) {
        if (sheet == null) return null;
        // Two arrays rather than a map: the answer is found by identity in a
        // list a few entries long, which is quicker than hashing the pair and
        // allocates nothing.
        for (int i = 0; i < shadedCount; i++) {
            if (shadedFrom[i] == sheet && shadedShade[i] == shade) return shadedTo[i];
        }
        BufferedImage baked = SolidTextures.shaded(sheet, shade);
        if (shadedCount < shadedFrom.length) {
            shadedFrom[shadedCount] = sheet;
            shadedShade[shadedCount] = shade;
            shadedTo[shadedCount] = baked;
            shadedCount++;
        }
        return baked;
    }

    /** This frame's bakes: {@link #shaded}. */
    private final BufferedImage[] shadedFrom = new BufferedImage[64];
    private final BufferedImage[] shadedTo = new BufferedImage[64];
    private final double[] shadedShade = new double[64];
    private int shadedCount;

    /**
     * Project one quad, clip it against the near plane and queue it.
     *
     * <p>Everything that can reject the face is done before the projection —
     * the distance, and then the whole polygon being behind the eye — because
     * the projection is four divides and the rejections are comparisons.
     */
    private Entry face(int col, int row, int layer,
                      double ax, double ay, double az, double bx, double by, double bz,
                      double cx, double cy, double cz, double dx, double dy, double dz,
                      Color colour, double shade, BufferedImage texture) {
        if (colour == null) return null;
        double minX = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        double maxX = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        double minY = Math.min(Math.min(ay, by), Math.min(cy, dy));
        double maxY = Math.max(Math.max(ay, by), Math.max(cy, dy));
        double minZ = Math.min(Math.min(az, bz), Math.min(cz, dz));
        double maxZ = Math.max(Math.max(az, bz), Math.max(cz, dz));
        double distance = boxDistance(minX, minY, minZ, maxX, maxY, maxZ);
        if (distance > faceReach) return null;

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
        if (n < 3) return null;

        Entry e = claim();
        e.sprite = null;
        e.texture = null;
        e.fill = true;
        e.fogOverlay = 0;
        e.order = cellOrder(col, row, layer);
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
            return null;
        }
        // Anything standing between the camera and what it is looking at is
        // drawn see-through, so walking indoors is not walking into a wall of
        // roof (see setCutaway). Measured at the face's own middle, which is
        // the one point of it every corner is within half a tile of.
        double seen = cutawayAlpha((minX + maxX) / 2, (minY + maxY) / 2,
                (minZ + maxZ) / 2);
        e.argb = shadeFog(colour, shade, distance);
        if (seen < 1) {
            e.argb = (e.argb & 0x00FFFFFF)
                    | (((int) Math.round(((e.argb >>> 24) & 0xFF) * seen)) << 24);
        }
        // A face cut by the near plane is textured too, and that it was not is
        // what a player standing against a block saw as the texture falling
        // off it. The face nearest you is the one most likely to have a corner
        // behind your own eye — the floor tile you are standing on always does
        // — and the part of it you can see is nowhere near that corner. What
        // the near plane rules out is projecting the whole quad at once, and
        // nothing here does that any more: the corners are kept in world space
        // and the patches that cannot be projected are dropped one at a time
        // (see drawTextured).
        // A ghosted face keeps its colour and loses its sheet: a texture is
        // blitted rather than blended, so it would come back at full strength
        // over the faded fill and undo the whole effect.
        if (texture != null && seen >= 1
                && (hiX - loX) >= MIN_TEXTURE_PIXELS && (hiY - loY) >= MIN_TEXTURE_PIXELS) {
            // The block's own sheet, shaded for this face. The face is kept in
            // *world* coordinates rather than as one screen-space transform:
            // how it is mapped is decided at draw time, in patches, because one
            // affine map over a whole perspective quad is what made surfaces
            // bend (see drawTextured).
            e.texture = shaded(texture, shade);
            e.quad[0] = ax; e.quad[1] = ay; e.quad[2] = az;
            e.quad[3] = bx; e.quad[4] = by; e.quad[5] = bz;
            e.quad[6] = cx; e.quad[7] = cy; e.quad[8] = cz;
            e.quad[9] = dx; e.quad[10] = dy; e.quad[11] = dz;
            double fog = fogAmount(distance);
            if (fog > 0.02) {
                e.fogOverlay = ((int) Math.round(255 * Math.min(1, fog)) << 24)
                        | (fogArgb & 0x00FFFFFF);
            }
        }
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
        //
        // …and only on an opaque face. The outline is there to cover a
        // half-pixel of background between two fills; on something you can see
        // through — a pane of glass, water, the patch of shade under an actor —
        // there is no seam to cover and the stroke doubles the alpha along the
        // edge, which draws a hard border around a soft thing.
        e.edge = colour.getAlpha() >= 255 && seen >= 1;
        e.edgeArgb = distance < EDGE_TILES * level.tileSize
                ? shadeFog(colour, shade * 0.72, distance) : e.argb;
        keep();
        return e;
    }

    /**
     * How far the cell holding a face is from the eye's own cell, counted a
     * cell at a time along each axis — <b>the painter's order, and it is exact
     * for this geometry rather than a good guess.</b>
     *
     * <p>Take any straight ray out of the eye. Each of its three coordinates
     * moves monotonically along it, so each of {@code |col − eyeCol|},
     * {@code |row − eyeRow|} and {@code |box − eyeBox|} can only stay the same
     * or grow as the ray travels: their sum never decreases. So if a ray hits
     * face A before face B, A's cell has a sum no larger than B's — which is
     * exactly the property a painter's algorithm needs. Drawing in decreasing
     * order of this number therefore puts every occluder on top of everything
     * it occludes, with no exceptions to argue about, and two faces that tie
     * cannot occlude one another at all (no ray reaches both).
     *
     * <p>This replaced sorting on the Euclidean distance to the nearest point
     * of each face's box, which is a reasonable heuristic and is wrong in
     * exactly the cases players notice: a long face whose nearest corner is at
     * your elbow sorts in front of the small block standing halfway down it.
     * The cost of the exact rule is that faces have to be one cell each
     * ({@link #block}), which is why that changed with this.
     *
     * @param layer the block's layer; the floor lid passes {@code 0}
     */
    private long cellOrder(int col, int row, int layer) {
        int ts = level.tileSize;
        int eyeCol = (int) Math.floor(eye.x() / ts);
        int eyeRow = (int) Math.floor(eye.y() / ts);
        // Layer L occupies the height box [L−1, L); the eye is in the box its
        // own height falls in, which is what makes the two comparable.
        int eyeBox = (int) Math.floor(eye.z() / ts);
        return orderBias + Math.abs((long) col - eyeCol) + Math.abs((long) row - eyeRow)
                + Math.abs((long) (layer - 1) - eyeBox);
    }

    /**
     * Distance from the eye to the nearest point of a box — what the fog and
     * the view-distance cull are measured with.
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
        if (distance > detailDistance) return;   // see inRange
        Entry e = claim();
        e.sprite = draw;
        e.texture = null;
        e.fogOverlay = 0;
        e.order = cellOrder((int) Math.floor(wx / Math.max(1, level.tileSize)),
                (int) Math.floor(wy / Math.max(1, level.tileSize)),
                (int) Math.floor(wz / Math.max(1, level.tileSize)) + 1);
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

    /**
     * A soft dark patch on the ground under an actor — the solid views' shadow.
     *
     * <p><b>Why an actor gets one and the terrain does not.</b> A block's own
     * faces already say where it is: the four-level shading makes a cube read
     * as a cube, and a wall meeting a floor is two differently lit surfaces.
     * A billboard has none of that. It is a flat picture standing in the air,
     * and without something on the ground beneath it there is no way to tell a
     * character standing on the floor from one hovering a block above it — the
     * plan view has cast this shadow since it grew a height axis, and the solid
     * views were the ones drawing characters with nothing under them at all.
     *
     * <p>Queued as an ordinary face at the cell it lands in, so the terrain in
     * front of it covers it exactly as it covers everything else, and lifted a
     * hair off the floor so it does not fight the floor's own quad for the same
     * pixels.
     *
     * @param wx     the actor's ground contact point
     * @param wy     the same, on the other axis
     * @param groundZ the height of the surface it is standing over
     * @param radius how wide the patch is, in world units
     */
    public void groundShadow(double wx, double wy, double groundZ, double radius) {
        if (eye == null || level == null || radius <= 0) return;
        if (eye.z() <= groundZ) return; // seen from below, a floor patch is nothing
        int ts = Math.max(1, level.tileSize);
        double r = Math.min(radius, ts * 0.9);
        double z = groundZ + ts * 0.02;
        int col = (int) Math.floor(wx / ts), row = (int) Math.floor(wy / ts);
        int layer = (int) Math.floor(z / ts) + 1;
        face(col, row, layer,
                wx - r, wy - r, z, wx + r, wy - r, z,
                wx + r, wy + r, z, wx - r, wy + r, z,
                SHADOW, SHADE_TOP, null);
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
            long q = Math.max(0, Math.min(QUANTA_MAX, pool.get(i).order));
            order[i] = ((QUANTA_MAX - q) << 32) | i;
        }
        Arrays.sort(order, 0, used);
        for (int i = 0; i < used; i++) {
            draw(pool.get((int) (order[i] & 0xFFFFFFFFL)));
        }
        used = 0;
    }

    /** The largest cell distance the sort key can hold. */
    private static final long QUANTA_MAX = 1L << 30;

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
        // The face's own colour goes down first whether or not it is textured.
        // On a textured face it is the backing: a patch's affine map covers its
        // patch to within a fraction of a pixel rather than exactly, and a
        // fraction of a pixel of the block's own colour is invisible where a
        // fraction of a pixel of sky, seen through a wall, is not.
        if (e.fill) target.fillPolygon(e.xs, e.ys, e.count, e.argb);
        if (e.texture != null) {
            drawTextured(e);
            if (e.fogOverlay != 0) target.fillPolygon(e.xs, e.ys, e.count, e.fogOverlay);
        }
        if (e.edge) target.drawPolygon(e.xs, e.ys, e.count, e.edgeArgb, 1f);
    }

    /**
     * Draw a face's texture onto it, in patches.
     *
     * <p><b>Why not one blit.</b> The only warping blit {@link DrawTarget} has
     * is affine, and a perspective quad is not an affine image of a rectangle:
     * three corners decide an affine map and the fourth lands wherever the
     * divide put it. Used over a whole face that is what a player sees as the
     * surface <em>bending</em> — the texture shears away from the geometry it
     * is painted on, worst on exactly the faces that run away from the eye,
     * which is most of the floor and every wall seen at an angle. Clipping it
     * to the face hides the overspill and turns the rest into a fold.
     *
     * <p><b>What is drawn instead.</b> The face is halved, in its own texture
     * coordinates, until every piece of it <em>is</em> nearly a parallelogram
     * on screen — until its {@linkplain #MAX_PATCH_DEFECT defect} is under a
     * couple of pixels — and an affine map over such a patch is the perspective
     * map, to within that. A face square-on to the eye is already a
     * parallelogram and is one patch, which is the common case and costs
     * exactly what one blit did before; a floor tile stretching away underfoot
     * is a handful. Each patch takes the slice of the sheet that belongs to it,
     * so the texture stays continuous across the joins.
     *
     * <p><b>Which edge is halved</b> is whichever is longer <em>on screen</em>,
     * not whichever is longer on the sheet. Splitting one axis over and over
     * looks like the answer — a floor recedes along one axis, so strips across
     * it ought to do — and it converges far too slowly to afford: the defect of
     * a strip falls in proportion to how many strips there are, so a tile
     * underfoot whose near edge is a hundred pixels wider than its far one
     * wants fifty of them. Halving the longer edge takes both axes down
     * together and gets to the same place in a dozen.
     *
     * <p>The map for a patch is the affine that fits its four projected corners
     * <em>least-squares</em> rather than the one pinned to three of them: the
     * residual is then a quarter of the defect at each corner instead of the
     * whole of it at one, and it is the same size on both sides of a join,
     * which is what {@link #blit} needs to be able to close them.
     */
    private void drawTextured(Entry e) {
        int w = e.texture.getWidth(), h = e.texture.getHeight();
        double[] q = e.quad;
        // The face's own axes: P(u, v) = A + u·(B − A) + v·(D − A). A block
        // face is a rectangle, so this covers it exactly.
        double ux = q[3] - q[0], uy = q[4] - q[1], uz = q[5] - q[2];
        double vx = q[9] - q[0], vy = q[10] - q[1], vz = q[11] - q[2];

        // The whole face, waiting to be drawn or split.
        push(0, w, 0, h, 0, 0, 0);
        int top = STRIDE;
        // Whether this face turned out to need more than one patch, which is
        // the same question as whether it has any joins to close — and the
        // reason the clip is pushed here rather than around the whole call. A
        // face drawn in one patch has nothing to overlap, so it is not grown,
        // so it does not overhang its own outline, so it needs no clip; and
        // that is most of a frame's faces. Paying Java2D's most expensive verb
        // on all of them to serve the few costs a couple of milliseconds a
        // frame, measured, which is most of a mid-range machine's headroom.
        boolean split = false;
        boolean clipped = false;

        while (top >= STRIDE) {
            top -= STRIDE;
            int sx0 = work[top], sx1 = work[top + 1];
            int sy0 = work[top + 2], sy1 = work[top + 3];
            int depth = work[top + 4], nearCuts = work[top + 5];
            if (sx1 <= sx0 || sy1 <= sy0) continue;
            boolean divisible = (sx1 - sx0 > 1 || sy1 - sy0 > 1)
                    && top + 2 * STRIDE <= work.length;
            double u0 = sx0 / (double) w, u1 = sx1 / (double) w;
            double v0 = sy0 / (double) h, v1 = sy1 / (double) h;
            double d0 = patchCorner(q, ux, uy, uz, vx, vy, vz, u0, v0, 0);
            double d1 = patchCorner(q, ux, uy, uz, vx, vy, vz, u1, v0, 1);
            double d2 = patchCorner(q, ux, uy, uz, vx, vy, vz, u1, v1, 2);
            double d3 = patchCorner(q, ux, uy, uz, vx, vy, vz, u0, v1, 3);
            double nearest = Math.min(Math.min(d0, d1), Math.min(d2, d3));
            double furthest = Math.max(Math.max(d0, d1), Math.max(d2, d3));
            if (furthest <= EyeCamera.NEAR) continue; // wholly behind the eye
            if (nearest <= EyeCamera.NEAR) {
                // <b>Cut down to the part that is in front, rather than giving
                // up on the face.</b> A patch with a corner behind the near
                // plane cannot be projected at all — the divide changes sign —
                // but the face it belongs to is mostly in front, and dropping
                // the whole of it is what a player standing against a block saw
                // as the texture disappearing. Halving the axis the depth
                // changes along walks the split toward the plane: every cut
                // leaves one child entirely in front, which is drawn, and one
                // that straddles a little less than its parent did. What is
                // finally left is a sliver a texel wide against the near plane,
                // which is behind the player's own nose.
                if (divisible && nearCuts < MAX_NEAR_CUTS) {
                    boolean alongU = sx1 - sx0 > 1
                            && (sy1 - sy0 <= 1
                                    || Math.abs(d1 - d0) >= Math.abs(d3 - d0));
                    halve(sx0, sx1, sy0, sy1, alongU, depth, nearCuts + 1, top);
                    top += 2 * STRIDE;
                    split = true;
                }
                continue;
            }
            // How far this patch is from being a parallelogram, which is how
            // far an affine map over it would land from its own corners.
            double defectX = patchX[0] - patchX[1] + patchX[2] - patchX[3];
            double defectY = patchY[0] - patchY[1] + patchY[2] - patchY[3];
            double defect = Math.hypot(defectX, defectY);
            double alongEdge = edge(0, 1), downEdge = edge(0, 3);
            boolean splittable = depth < MAX_SPLIT_DEPTH && divisible
                    && Math.max(alongEdge, downEdge) > MIN_PATCH_PIXELS;
            if (splittable && defect > MAX_PATCH_DEFECT) {
                boolean alongU = sx1 - sx0 > 1
                        && (sy1 - sy0 <= 1 || alongEdge >= downEdge);
                halve(sx0, sx1, sy0, sy1, alongU, depth + 1, nearCuts, top);
                top += 2 * STRIDE;
                split = true;
                continue;
            }
            if (split && !clipped) {
                clip.reset();
                for (int i = 0; i < e.count; i++) clip.addPoint(e.xs[i], e.ys[i]);
                target.pushClip(clip);
                clipped = true;
            }
            blit(e.texture, sx0, sy0, sx1 - sx0, sy1 - sy0,
                    split ? defect / 4 + 0.5 : 0);
        }
        if (clipped) target.popClip();
    }

    /** Put both halves of a patch on the stack at {@code at}, in place of it. */
    private void halve(int sx0, int sx1, int sy0, int sy1, boolean alongU,
                       int depth, int nearCuts, int at) {
        if (alongU) {
            int mid = (sx0 + sx1) / 2;
            push(sx0, mid, sy0, sy1, depth, nearCuts, at);
            push(mid, sx1, sy0, sy1, depth, nearCuts, at + STRIDE);
        } else {
            int mid = (sy0 + sy1) / 2;
            push(sx0, sx1, sy0, mid, depth, nearCuts, at);
            push(sx0, sx1, mid, sy1, depth, nearCuts, at + STRIDE);
        }
    }

    /** Put a patch's texture bounds and its two split counts on the stack. */
    private void push(int sx0, int sx1, int sy0, int sy1, int depth, int nearCuts, int at) {
        work[at] = sx0;
        work[at + 1] = sx1;
        work[at + 2] = sy0;
        work[at + 3] = sy1;
        work[at + 4] = depth;
        work[at + 5] = nearCuts;
    }

    /** How long the patch edge between two of its projected corners is. */
    private double edge(int from, int to) {
        return Math.hypot(patchX[to] - patchX[from], patchY[to] - patchY[from]);
    }

    /**
     * Project the patch corner at ({@code u}, {@code v}) into
     * {@link #patchX}/{@link #patchY} slot {@code at}.
     *
     * @return how far in front of the eye it is; the screen coordinates are
     *         written only when that is past the near plane, since past it
     *         there are no screen coordinates to write — the divide changes
     *         sign and a point behind you comes out in front of you, mirrored
     */
    private double patchCorner(double[] q, double ux, double uy, double uz,
                               double vx, double vy, double vz,
                               double u, double v, int at) {
        eye.toEye(q[0] + ux * u + vx * v, q[1] + uy * u + vy * v,
                q[2] + uz * u + vz * v, point);
        double depth = point[2];
        if (depth > EyeCamera.NEAR) {
            patchX[at] = eye.screenX(point[0], depth);
            patchY[at] = eye.screenY(point[1], depth);
        }
        return depth;
    }

    /**
     * Blit the sheet's ({@code sx}, {@code sy}, {@code sw}, {@code sh}) region
     * onto the patch in {@link #patchX}/{@link #patchY}.
     *
     * <p><b>No clip per patch, and no seams either.</b> The obvious way to keep
     * a patch inside its own outline is to clip to it, and it is the wrong one
     * twice over: it is the most expensive verb {@link DrawTarget} has — four
     * and a half milliseconds a frame of the ten this pass took, measured — and
     * it <em>opens</em> the joins rather than closing them, because a fit that
     * lands a quarter of the defect short of a shared corner, cut off at
     * exactly that short line, leaves the sliver between the two patches
     * showing whatever was under them. Instead the sheet is drawn where the map
     * puts it and the patch is grown, about its own centre, by
     * {@code margin} — the error the fit is known to have, a quarter of the
     * defect, plus half a pixel for the rounding. Neighbours then overlap by
     * that much instead of parting by it, and a pixel of a texture over itself
     * is nothing to look at. The face's own clip, pushed once by
     * {@link #drawTextured} and only for a face that has joins at all, catches
     * the outermost patches where they hang over the edge.
     *
     * @param margin how far to grow the patch, in pixels; zero for a face drawn
     *               whole, which has no neighbour to meet and so is left to
     *               land exactly where the fit puts it
     */
    private void blit(BufferedImage sheet, int sx, int sy, int sw, int sh, double margin) {
        // The least-squares affine over the patch's four corners: the u and v
        // edges averaged, and the origin placed so the fit is centred.
        double bx = ((patchX[1] + patchX[2]) - (patchX[0] + patchX[3])) / 2;
        double by = ((patchY[1] + patchY[2]) - (patchY[0] + patchY[3])) / 2;
        double cx = ((patchX[3] + patchX[2]) - (patchX[0] + patchX[1])) / 2;
        double cy = ((patchY[3] + patchY[2]) - (patchY[0] + patchY[1])) / 2;
        double mx = (patchX[0] + patchX[1] + patchX[2] + patchX[3]) / 4;
        double my = (patchY[0] + patchY[1] + patchY[2] + patchY[3]) / 4;

        // Grown outward by what the fit misses its corners by, so that patches
        // meet in an overlap rather than in a gap. Each axis is grown on its own
        // length: a strip four pixels across and two hundred long needs the same
        // half-pixel on both of its ends, which one shared scale cannot give it.
        double along = Math.hypot(bx, by), down = Math.hypot(cx, cy);
        if (margin > 0 && along > 1e-6 && down > 1e-6) {
            double growU = 1 + 2 * margin / along;
            double growV = 1 + 2 * margin / down;
            bx *= growU;
            by *= growU;
            cx *= growV;
            cy *= growV;
        }
        double ox = mx - (bx + cx) / 2;
        double oy = my - (by + cy) / 2;
        // …expressed in the sheet's own pixel coordinates, so the region can be
        // drawn where it sits in the sheet rather than being sliced out first.
        double m00 = bx / sw, m10 = by / sw;
        double m01 = cx / sh, m11 = cy / sh;
        patchMap.setTransform(m00, m10, m01, m11,
                ox - m00 * sx - m01 * sy, oy - m10 * sx - m11 * sy);

        target.pushTransform(patchMap);
        target.drawImage(sheet, sx, sy, sw, sh, sx, sy, sw, sh);
        target.popTransform();
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
        double span = horizon() - fogStart;
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
        return pick(eye, level, reach, eye.dirX(), eye.dirY(), eye.dirZ());
    }

    /**
     * {@link #pick(EyeCamera, Level, double)} along a ray of the caller's own
     * choosing rather than along the view axis — what a view that still aims
     * with a visible pointer needs, where the ray goes through the pixel under
     * the cursor instead of through the middle of the screen.
     */
    public static TerrainPainter.Aim pick(EyeCamera eye, Level level, double reach,
                                          double dx, double dy, double dz) {
        Hit hit = march(eye, level, reach, dx, dy, dz);
        if (hit == null) return null;
        // The cell the ray was in when it met the face — on all three axes, not
        // just the two on the ground plane. That third one is the difference
        // between placing a block against the wall you are looking at and
        // dropping it on top of the column the wall belongs to, and it is what
        // "blocks place at the bottom of the stack" was: the layer was thrown
        // away here and re-derived from the column further down.
        return new TerrainPainter.Aim(hit.col, hit.row, hit.layer, hit.top,
                hit.fromCol, hit.fromRow, hit.fromLayer);
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
        return aimPoint(eye, level, reach, eye.dirX(), eye.dirY(), eye.dirZ());
    }

    /** {@link #aimPoint(EyeCamera, Level, double)} along a ray of the caller's own. */
    public static double[] aimPoint(EyeCamera eye, Level level, double reach,
                                    double dx, double dy, double dz) {
        Hit hit = march(eye, level, reach, dx, dy, dz);
        double t = hit != null ? hit.distance : reach;
        return new double[]{
                eye.x() + dx * t,
                eye.y() + dy * t,
                Math.max(0, eye.z() + dz * t)
        };
    }

    /** Where a march stopped: the cell struck, the face, and how far away. */
    private record Hit(int col, int row, int layer, boolean top,
                       int fromCol, int fromRow, int fromLayer, double distance) {}

    private static Hit march(EyeCamera eye, Level level, double reach,
                             double dx, double dy, double dz) {
        int ts = level == null ? 0 : level.tileSize;
        if (ts <= 0 || reach <= 0) return null;

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
            return new Hit(col, row, box + 1, true, col, row, box + 1, 0);
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

        int fromCol = col, fromRow = row, fromBox = box;
        int layers = level.layerCount();
        while (true) {
            double t;
            boolean vertical = false;
            fromCol = col;
            fromRow = row;
            fromBox = box;
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
                        fromCol, fromRow, fromBox + 1, t);
            }
        }
    }

    /** How far along the ray the current cell's boundary on one axis is. */
    private static double boundary(double at, double d, int cell, int ts) {
        if (d > 0) return ((cell + 1) * (double) ts - at) / d;
        if (d < 0) return (cell * (double) ts - at) / d;
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Whether the height box at ({@code col}, {@code row}, {@code box}) is
     * filled — the same question the ray march asks, for callers placing
     * something in the world rather than shooting at it.
     *
     * <p>What an eye needs before it is put somewhere: an eye inside a block
     * sees the inside of a block, which is every face around it turned away and
     * therefore nothing at all.
     */
    public static boolean filled(Level level, int col, int row, int box) {
        return solid(level, col, row, box);
    }

    /**
     * Whether the box at this grid position is filled with something that would
     * <em>blind</em> an eye standing in it — a whole opaque block, and not a
     * flower or a pane of glass.
     *
     * <p>The question {@link #filled} was being asked for and does not answer.
     * An eye inside a block sees nothing because every face around it is turned
     * away; an eye inside a flower sees the world, because a flower is a stem
     * and a sprite. Ducking out of one is a camera lurching downward for no
     * reason a player can see.
     */
    public static boolean opaque(Level level, int col, int row, int box) {
        if (box < -1) return false;
        if (level == null) return false;
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return false;
        int id = level.tileAt(col, row, box + 1);
        if (id <= 0) return false;
        Block b = level.blocks.get(id);
        return b == null || b.covers();
    }

    /** Whether the box at this grid position stops a ray; see {@link #march}. */
    private static boolean solid(Level level, int col, int row, int box) {
        if (box < -1) return false;
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return false;
        return level.tileAt(col, row, box + 1) > 0;
    }
}
