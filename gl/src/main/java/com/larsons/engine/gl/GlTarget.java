package com.larsons.engine.gl;

import com.larsons.engine.graphics.atlas.GlyphAtlas;
import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * {@link DrawTarget} over OpenGL 3.3 core — the GPU backend.
 *
 * <p><b>This class is short for what it does, and that is the result of B1–B6
 * rather than a property of GL.</b> Every verb below is an append to one vertex
 * buffer against one shader, because the interface was sized by counting what
 * the engine really draws (B1), the painters and scenes were moved onto it
 * unchanged (B2, B3), the seam was sealed so nothing could drift back (B4), and
 * the art and the text were packed onto shared pages so consecutive draws name
 * the same texture (B5, B6). What is left here is a translation, which is what
 * the plan said this step would be if the six before it were done properly.
 *
 * <p><b>What breaks a batch, and what pointedly does not.</b> A texture change,
 * a clip, and the two stencil paths. Not alpha — it multiplies into the vertex
 * colour. Not a transform — it is applied on the CPU as vertices are appended.
 * Both are pushed and popped constantly by the entity and UI phases, and a
 * backend that flushed on either would report a merge ratio near one however
 * well the art was atlased.
 *
 * <p><b>Flat shapes sample a white texel on the sprite page</b>, not a texture
 * of their own — see {@code whitePixel} for why the difference is the whole
 * point rather than a detail. A rectangle, an icon and a label then name the
 * same texture, and a frame of label-box-label-box collapses into one batch.
 * B6 named five frames its own step could not move and left them as this
 * step's prediction to be judged on; the judgement is in
 * {@code RENDER_PLAN.md}.
 *
 * <p><b>Java2D appears here twice, both times for measurement, never to draw.</b>
 * A 1×1 scratch {@link Graphics2D} answers {@link #textWidth} and supplies the
 * {@link FontRenderContext} the glyph atlas rasterises against — because layout
 * must be bit-identical across backends or every UI in the game moves when the
 * renderer changes, which is B6's rule and it does not stop applying because
 * the backend did. And an unservable text run is baked to an image and drawn as
 * a quad, which is the GL equivalent of the fallback the Java2D path already
 * has. Neither is a seam: nothing outside this file can reach either one.
 */
public final class GlTarget implements DrawTarget, AutoCloseable {

    private final GlProgram program;
    private final GlBatch batch;
    private final GlTextures textures;
    private final DrawStats stats = new DrawStats();

    /** Logical size of the surface. Device pixels are these times the scale. */
    private int width;
    private int height;
    private double scale;

    // --- CPU-side state stacks -------------------------------------------------

    private final Deque<AffineTransform> transforms = new ArrayDeque<>();
    private AffineTransform transform = new AffineTransform();
    private boolean identity = true;
    private double m00 = 1, m10 = 0, m01 = 0, m11 = 1, m02 = 0, m12 = 0;

    private final Deque<Float> alphas = new ArrayDeque<>();
    private float alpha = 1f;

    /** Rectangular clips, in logical coordinates; {@code null} means unclipped. */
    private final Deque<int[]> scissors = new ArrayDeque<>();
    private int[] scissor;

    /** How the clip stack was pushed, so {@link #popClip()} undoes the right one. */
    private final Deque<Boolean> clipKinds = new ArrayDeque<>();

    /** Nesting depth of shape clips, and the stencil value that means "inside". */
    private int stencilLevel;

    // --- text ------------------------------------------------------------------

    private final BufferedImage metricsSurface =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    private Graphics2D metrics;
    private FontRenderContext fontContext;
    private GlyphAtlas.Glyphs glyphs;
    private Font glyphsFont;
    private int glyphsArgb;
    private GlyphAtlas.Cell[] cells = new GlyphAtlas.Cell[64];
    private int[] advances = new int[64];

    /**
     * Text runs the atlas would not serve, baked whole.
     *
     * <p>Bounded, and small on purpose: this holds the runs that need shaping —
     * right-to-left, combining marks — or that a font lays out as a unit rather
     * than a character at a time. A game whose UI is mostly such runs would
     * want a real shaper, not a bigger cache, and a cache large enough to hide
     * that from the profiler would be the wrong kind of help.
     */
    private final Map<String, BufferedImage> bakedRuns =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 128;
                }
            };

    /**
     * @param scale device pixels per logical pixel. This is what
     *              {@code DeviceProfile.displayScale} is for, and the one place
     *              it is the right question to ask — B6 found that the Java2D
     *              path should ask its destination instead, because it has one.
     *              This backend does not: there is no {@code Graphics2D} behind
     *              a GL surface to interrogate, so the scale has to be handed in.
     */
    public GlTarget(int width, int height, double scale) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.scale = scale <= 0 ? 1 : scale;

        this.program = new GlProgram();
        this.batch = new GlBatch();
        this.textures = new GlTextures();

        rebuildFontContext();
    }

    public GlTarget(int width, int height) {
        this(width, height, 1);
    }

    /**
     * Change the device scale — a window dragged from a 1× panel to a 2× one,
     * which changes the framebuffer under a window whose logical size never
     * moved.
     *
     * <p>Everything derived from the old scale goes with it: the glyph memo
     * points at cells rasterised in the old rendering context, and a baked run
     * is pixels at the old resolution. Keeping either would put yesterday's
     * resolution on today's panel, which is the soft-text failure B6 warned
     * about arriving by a different route.
     */
    public void scale(double newScale) {
        if (newScale <= 0 || newScale == scale) return;
        scale = newScale;
        rebuildFontContext();
        glyphs = null;
        glyphsFont = null;
        bakedRuns.clear();
    }

    private void rebuildFontContext() {
        if (metrics != null) metrics.dispose();
        metrics = metricsSurface.createGraphics();
        // The same two hints Java2DRenderer sets on every frame it composes.
        // Font metrics and the rendering context both read them, so a target
        // configured differently would lay text out differently from the
        // backend it is supposed to match.
        metrics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        metrics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (scale != 1) metrics.scale(scale, scale);
        fontContext = metrics.getFontRenderContext();
    }

    // --- frame lifecycle -------------------------------------------------------

    /**
     * Start a frame: reset every stack, the stats and the GL state this class
     * assumes.
     *
     * <p>State is set here rather than once at construction because the context
     * is shared — a shader chain, a readback, anything else that touches GL
     * between frames can leave blending or the stencil test somewhere else, and
     * a renderer that inherited it would draw one wrong frame and then look
     * fine, which is the hardest kind of bug to be handed.
     */
    public void beginFrame(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);

        transforms.clear();
        transform = new AffineTransform();
        setTransform(transform);
        alphas.clear();
        alpha = 1f;
        scissors.clear();
        clipKinds.clear();
        scissor = null;
        stencilLevel = 0;
        stats.reset();
        batch.resetDraws();
        batch.forgetTexture();

        glViewport(0, 0, (int) Math.round(this.width * scale),
                (int) Math.round(this.height * scale));
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_MULTISAMPLE);
        glEnable(GL_BLEND);
        // Straight alpha, SRC_OVER. The separate alpha term keeps the
        // destination's own alpha meaningful when the surface has one, which
        // Job A's ping-pong will care about and nothing here does yet.
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glColorMask(true, true, true, true);
        glStencilMask(0xFF);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
        glStencilMask(0x00);

        program.use(this.width, this.height);
    }

    /** Submit whatever is still open. Nothing is on screen until this runs. */
    public void endFrame() {
        batch.flush();
    }

    /** Draw calls issued this frame — what {@link DrawStats#batches()} predicts. */
    public int drawCalls() { return batch.draws(); }

    /** How many textures are resident, and how much was uploaded. */
    public String textureSummary() {
        return "%d textures, %d uploads, %.1f MB".formatted(
                textures.size(), textures.uploads(), textures.uploadedBytes() / 1048576.0);
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public DrawStats stats() { return stats; }

    // --- vertices --------------------------------------------------------------

    /** Colour with the current scoped alpha multiplied into it. */
    private int tint(int argb) {
        if (alpha >= 1f) return argb;
        int a = (argb >>> 24) & 0xFF;
        int scaled = Math.round(a * alpha);
        return (scaled << 24) | (argb & 0xFFFFFF);
    }

    private void setTransform(AffineTransform t) {
        transform = t;
        m00 = t.getScaleX();
        m10 = t.getShearY();
        m01 = t.getShearX();
        m11 = t.getScaleY();
        m02 = t.getTranslateX();
        m12 = t.getTranslateY();
        identity = t.isIdentity();
    }

    private void vertex(float x, float y, float u, float v, int argb) {
        if (identity) {
            batch.vertex(x, y, u, v, argb);
        } else {
            batch.vertex((float) (m00 * x + m01 * y + m02),
                    (float) (m10 * x + m11 * y + m12), u, v, argb);
        }
    }

    // --- the white texel -------------------------------------------------------

    /**
     * An opaque white square packed into the sprite pages, whose centre texel
     * every flat shape samples.
     *
     * <p><b>This is the difference between B8's first bullet working and
     * looking like it works.</b> The bullet says a flat shape should be two
     * triangles against a 1×1 white texture, "so shapes and sprites share one
     * shader and one batch". They share the shader either way. They share the
     * <em>batch</em> only if the white texel is on the page the sprites and
     * glyphs are already on — otherwise a rectangle between two labels is a
     * bind, exactly as it was before, and the whole arrangement buys a shader
     * switch that GL was not paying for anyway. Measured with a standalone 1×1:
     * `profile-overlay` 16 batches → 16, `minigame-hud` 8 → 8, `scene-play` 22
     * → 22, which is B6's prediction failing for the third time in the way this
     * document keeps warning about. Packed into the page instead, they move.
     *
     * <p>Four pixels square rather than one: B5 pads every region with a
     * transparent gutter, and sampling the centre of a 4×4 is unambiguously
     * white however the packer rounds and whatever a driver does at a texel
     * boundary. Sixteen pixels of a 2048×2048 page is not a budget worth
     * economising on.
     */
    private static final int WHITE_SIZE = 4;

    private SpriteAtlas.Region whitePixel;
    private boolean whiteResolved;
    private float whiteU = 0.5f;
    private float whiteV = 0.5f;

    /**
     * Bind whatever flat geometry should sample, and set the UVs to match.
     *
     * <p>Falls back to a standalone 1×1 when the atlas will not take the
     * region — {@code -Dlarsons.render.atlas=false}, or pages full. Flat shapes
     * then cost a bind each, which is what they cost before this step and is
     * still correct.
     */
    private void useFlatTexture() {
        if (!whiteResolved) {
            whiteResolved = true;
            BufferedImage white = new BufferedImage(
                    WHITE_SIZE, WHITE_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < WHITE_SIZE; y++) {
                for (int x = 0; x < WHITE_SIZE; x++) white.setRGB(x, y, 0xFFFFFFFF);
            }
            // Keyed, so a second target reuses the slot rather than consuming
            // another one — which matters because tests build these freely.
            whitePixel = SpriteAtlas.shared().register("gl.white", white);
        }
        if (whitePixel == null) {
            batch.useTexture(textures.white());
            whiteU = whiteV = 0.5f;
            return;
        }
        BufferedImage page = whitePixel.image();
        // Recomputed per use, not cached: a page that grows keeps every
        // region's pixel coordinates and halves its UVs, and a remembered pair
        // would go on addressing where the texel used to be.
        whiteU = (whitePixel.x() + WHITE_SIZE / 2f) / page.getWidth();
        whiteV = (whitePixel.y() + WHITE_SIZE / 2f) / page.getHeight();
        batch.useTexture(textures.of(page));
    }

    /** A flat-coloured triangle against the white texel. */
    private void flatTri(float ax, float ay, float bx, float by,
                         float cx, float cy, int argb) {
        useFlatTexture();
        vertex(ax, ay, whiteU, whiteV, argb);
        vertex(bx, by, whiteU, whiteV, argb);
        vertex(cx, cy, whiteU, whiteV, argb);
    }

    private void flatQuad(float x0, float y0, float x1, float y1, int argb) {
        flatTri(x0, y0, x1, y0, x1, y1, argb);
        flatTri(x0, y0, x1, y1, x0, y1, argb);
    }

    /**
     * One reusable sink rather than a lambda per fill.
     *
     * <p>A capturing lambda allocates, and this is called once per filled shape
     * — several hundred times a frame in a UI scene, every frame. That is
     * exactly the kind of per-draw allocation B5 had to remove from two sprite
     * factories before anything could be measured, and putting a fresh one back
     * in the hottest verb of the new backend would be a poor way to arrive at
     * B10's numbers.
     */
    private int sinkColour;
    private final GlPaths.Tri sink =
            (ax, ay, bx, by, cx, cy) -> flatTri(ax, ay, bx, by, cx, cy, sinkColour);

    private GlPaths.Tri flatSink(int argb) {
        sinkColour = argb;
        return sink;
    }

    // --- filled shapes ---------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>A real {@code glClear} where the frame is starting — opaque, unclipped,
     * untransformed, full alpha — and a rectangle otherwise. The two are not
     * interchangeable: {@code Java2DTarget.clear} is a {@code fillRect}, so it
     * honours the clip and composites a translucent colour, and a backend that
     * always cleared would wipe a scene that asked to tint one.
     */
    @Override
    public void clear(int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        boolean opaque = (argb >>> 24) == 0xFF;
        if (opaque && identity && scissor == null && stencilLevel == 0 && alpha >= 1f) {
            batch.flush();
            glClearColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            return;
        }
        flatQuad(0, 0, width, height, tint(argb));
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (w <= 0 || h <= 0) return;
        flatQuad(x, y, x + w, y + h, tint(argb));
    }

    @Override
    public void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (w <= 0 || h <= 0) return;
        if (arcW <= 0 || arcH <= 0) {
            flatQuad(x, y, x + w, y + h, tint(argb));
            return;
        }
        fillFlattened(new RoundRectangle2D.Float(x, y, w, h, arcW, arcH), tint(argb));
    }

    @Override
    public void fillOval(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (w <= 0 || h <= 0) return;
        fillFlattened(new Ellipse2D.Float(x, y, w, h), tint(argb));
    }

    @Override
    public void fillArc(int x, int y, int w, int h, int startDeg, int arcDeg, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (w <= 0 || h <= 0 || arcDeg == 0) return;
        fillFlattened(new Arc2D.Float(x, y, w, h, startDeg, arcDeg, Arc2D.PIE), tint(argb));
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (count < 3) return;
        float[] xy = new float[count * 2];
        for (int i = 0; i < count; i++) {
            xy[i * 2] = xs[i];
            xy[i * 2 + 1] = ys[i];
        }
        GlPaths.Contour contour = new GlPaths.Contour(xy, true);
        int colour = tint(argb);
        if (GlPaths.convex(contour)) {
            GlPaths.fan(contour, flatSink(colour));
        } else {
            stencilFill(List.of(contour), colour, boundsOf(xy));
        }
    }

    @Override
    public void fillShape(Shape shape, int argb) {
        if (shape == null) return;
        stats.record(DrawStats.Kind.SHAPE, null);
        Shape normalised = GlPaths.normalise(shape);
        List<GlPaths.Contour> contours = GlPaths.flatten(normalised);
        if (contours.isEmpty()) return;
        int colour = tint(argb);
        if (contours.size() == 1 && GlPaths.convex(contours.get(0))) {
            GlPaths.fan(contours.get(0), flatSink(colour));
            return;
        }
        stencilFill(contours, colour, normalised.getBounds2D());
    }

    /**
     * Fill a primitive by flattening it: a fan when the result is convex, the
     * stencil when it is not.
     *
     * <p>Everything the UI fills lands on the first branch — panels, buttons,
     * slots, meters, haloes. The second exists for a pie slice wider than a
     * half turn, which is concave, and for {@link #fillShape}.
     */
    private void fillFlattened(Shape shape, int colour) {
        List<GlPaths.Contour> contours = GlPaths.flatten(shape);
        if (contours.isEmpty()) return;
        if (contours.size() == 1 && GlPaths.convex(contours.get(0))) {
            GlPaths.fan(contours.get(0), flatSink(colour));
        } else {
            stencilFill(contours, colour, shape.getBounds2D());
        }
    }

    // --- outlines --------------------------------------------------------------

    @Override
    public void drawRect(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        strokeContour(new float[]{x, y, x + w, y, x + w, y + h, x, y + h},
                true, thickness, GlPaths.Cap.SQUARE, tint(argb));
    }

    @Override
    public void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH,
                              int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (arcW <= 0 || arcH <= 0) {
            strokeContour(new float[]{x, y, x + w, y, x + w, y + h, x, y + h},
                    true, thickness, GlPaths.Cap.SQUARE, tint(argb));
            return;
        }
        strokeShape(new RoundRectangle2D.Float(x, y, w, h, arcW, arcH),
                thickness, tint(argb));
    }

    @Override
    public void drawOval(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        strokeShape(new Ellipse2D.Float(x, y, w, h), thickness, tint(argb));
    }

    @Override
    public void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg,
                        int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        // OPEN, not PIE: the curve alone, without the two radii, which is what
        // Graphics2D.drawArc draws and what every cooldown ring in the engine
        // is asking for.
        strokeShape(new Arc2D.Float(x, y, w, h, startDeg, arcDeg, Arc2D.OPEN),
                thickness, tint(argb));
    }

    @Override
    public void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        if (count < 2) return;
        float[] xy = new float[count * 2];
        for (int i = 0; i < count; i++) {
            xy[i * 2] = xs[i];
            xy[i * 2 + 1] = ys[i];
        }
        strokeContour(xy, true, thickness, GlPaths.Cap.SQUARE, tint(argb));
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        strokeContour(new float[]{x1, y1, x2, y2}, false, thickness,
                GlPaths.Cap.SQUARE, tint(argb));
    }

    @Override
    public void drawDashedLine(int x1, int y1, int x2, int y2, int argb,
                               float thickness, float dash, float gap) {
        stats.record(DrawStats.Kind.SHAPE, null);
        GlPaths.dashedLine(x1, y1, x2, y2, Math.max(0.01f, thickness), dash, gap,
                flatSink(tint(argb)));
    }

    private void strokeContour(float[] xy, boolean closed, float thickness,
                               GlPaths.Cap cap, int colour) {
        GlPaths.stroke(new GlPaths.Contour(xy, closed),
                Math.max(0.01f, thickness), cap, flatSink(colour));
    }

    private void strokeShape(Shape shape, float thickness, int colour) {
        GlPaths.Tri sink = flatSink(colour);
        for (GlPaths.Contour contour : GlPaths.flatten(shape)) {
            GlPaths.stroke(contour, Math.max(0.01f, thickness),
                    GlPaths.Cap.SQUARE, sink);
        }
    }

    // --- gradients -------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Cut into up to three pieces along the gradient axis, because a
     * {@code GradientPaint} holds its end colours past its endpoints and a
     * clamped ramp is not an affine function of position. Inside the band it
     * is, so vertex interpolation reproduces it exactly rather than
     * approximately; outside it the piece is a flat fill. No texture, no second
     * shader, and the triangles go in the batch with everything else.
     */
    @Override
    public void fillLinearGradient(int x, int y, int w, int h,
                                   int x0, int y0, int argb0,
                                   int x1, int y1, int argb1) {
        stats.record(DrawStats.Kind.GRADIENT, null);
        if (w <= 0 || h <= 0) return;
        float[] rect = {x, y, x + w, y, x + w, y + h, x, y + h};
        float ax = x1 - x0, ay = y1 - y0;
        float lengthSq = ax * ax + ay * ay;
        if (lengthSq < 1e-9f) {
            shadedPolygon(rect, argb1, x0, y0, ax, ay, lengthSq, argb0, argb1, true);
            return;
        }
        // The two lines perpendicular to the axis at t = 0 and t = 1. Kept side
        // is to the left of a→b, which is why the second is stated backwards.
        float[] atStart = {x0, y0, x0 - ay, y0 + ax};
        float[] atEnd = {x1, y1, x1 + ay, y1 - ax};

        float[] before = GlPaths.clipHalfPlane(rect, new float[]{
                atStart[0], atStart[1], atStart[2], atStart[3]});
        float[] after = GlPaths.clipHalfPlane(rect, new float[]{
                atEnd[0], atEnd[1], atEnd[2], atEnd[3]});
        float[] middle = GlPaths.clipHalfPlane(
                GlPaths.clipHalfPlane(rect, new float[]{
                        atStart[2], atStart[3], atStart[0], atStart[1]}),
                new float[]{atEnd[2], atEnd[3], atEnd[0], atEnd[1]});

        int flat0 = tint(argb0), flat1 = tint(argb1);
        if (before.length >= 6) fanFlat(before, flat0);
        if (after.length >= 6) fanFlat(after, flat1);
        if (middle.length >= 6) {
            shadedPolygon(middle, 0, x0, y0, ax, ay, lengthSq, argb0, argb1, false);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Concentric rings, one per pair of stops, with the stop colours on the
     * ring's two edges. The interpolation across a ring is linear in radius,
     * which is what a {@code RadialGradientPaint} is between two stops, so the
     * only approximation left is the polygonal rim — and its segment count
     * comes from the same flatness bound every other curve here uses.
     */
    @Override
    public void fillRadialGradient(int cx, int cy, int radius,
                                   float[] fractions, int[] argbStops) {
        if (radius < 1 || fractions.length == 0
                || fractions.length != argbStops.length) return;
        stats.record(DrawStats.Kind.GRADIENT, null);
        int steps = GlPaths.arcSteps(radius, Math.PI * 2);
        useFlatTexture();

        float innerR = 0;
        int innerColour = tint(argbStops[0]);
        for (int s = 0; s < fractions.length; s++) {
            float outerR = Math.min(1f, Math.max(0f, fractions[s])) * radius;
            int outerColour = tint(argbStops[s]);
            if (outerR > innerR) {
                for (int i = 0; i < steps; i++) {
                    double a0 = Math.PI * 2 * i / steps;
                    double a1 = Math.PI * 2 * (i + 1) / steps;
                    float ix0 = cx + innerR * (float) Math.cos(a0);
                    float iy0 = cy + innerR * (float) Math.sin(a0);
                    float ix1 = cx + innerR * (float) Math.cos(a1);
                    float iy1 = cy + innerR * (float) Math.sin(a1);
                    float ox0 = cx + outerR * (float) Math.cos(a0);
                    float oy0 = cy + outerR * (float) Math.sin(a0);
                    float ox1 = cx + outerR * (float) Math.cos(a1);
                    float oy1 = cy + outerR * (float) Math.sin(a1);
                    vertex(ix0, iy0, whiteU, whiteV, innerColour);
                    vertex(ox0, oy0, whiteU, whiteV, outerColour);
                    vertex(ox1, oy1, whiteU, whiteV, outerColour);
                    vertex(ix0, iy0, whiteU, whiteV, innerColour);
                    vertex(ox1, oy1, whiteU, whiteV, outerColour);
                    vertex(ix1, iy1, whiteU, whiteV, innerColour);
                }
            }
            innerR = outerR;
            innerColour = outerColour;
        }
    }

    private void fanFlat(float[] xy, int argb) {
        GlPaths.fan(new GlPaths.Contour(xy, true), flatSink(argb));
    }

    /** A convex polygon whose vertex colours come from a position along an axis. */
    private void shadedPolygon(float[] xy, int flat, float x0, float y0,
                               float ax, float ay, float lengthSq,
                               int argb0, int argb1, boolean useFlat) {
        useFlatTexture();
        int n = xy.length / 2;
        for (int i = 1; i + 1 < n; i++) {
            emitShaded(xy, 0, flat, x0, y0, ax, ay, lengthSq, argb0, argb1, useFlat);
            emitShaded(xy, i, flat, x0, y0, ax, ay, lengthSq, argb0, argb1, useFlat);
            emitShaded(xy, i + 1, flat, x0, y0, ax, ay, lengthSq, argb0, argb1, useFlat);
        }
    }

    private void emitShaded(float[] xy, int i, int flat, float x0, float y0,
                            float ax, float ay, float lengthSq,
                            int argb0, int argb1, boolean useFlat) {
        float px = xy[i * 2], py = xy[i * 2 + 1];
        int colour;
        if (useFlat) {
            colour = tint(flat);
        } else {
            float t = ((px - x0) * ax + (py - y0) * ay) / lengthSq;
            colour = tint(lerpArgb(argb0, argb1, Math.max(0f, Math.min(1f, t))));
        }
        vertex(px, py, whiteU, whiteV, colour);
    }

    private static int lerpArgb(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return out;
    }

    // --- images ----------------------------------------------------------------

    @Override
    public void drawRegion(SpriteAtlas.Region region, int x, int y, int w, int h) {
        if (region == null) return;
        BufferedImage page = region.image();
        stats.record(DrawStats.Kind.IMAGE, page);
        quad(page, x, y, x + w, y + h,
                region.u0(), region.v0(), region.u1(), region.v1());
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y) {
        if (image == null) return;
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null) {
            drawRegion(region, x, y, region.width(), region.height());
            return;
        }
        stats.record(DrawStats.Kind.IMAGE, image);
        quad(image, x, y, x + image.getWidth(), y + image.getHeight(), 0, 0, 1, 1);
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y, int w, int h) {
        if (image == null) return;
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null) {
            drawRegion(region, x, y, w, h);
            return;
        }
        stats.record(DrawStats.Kind.IMAGE, image);
        quad(image, x, y, x + w, y + h, 0, 0, 1, 1);
    }

    @Override
    public void drawImage(BufferedImage image, int dx, int dy, int dw, int dh,
                          int sx, int sy, int sw, int sh) {
        if (image == null) return;
        // The same rule the Java2D backend applies: a sub-rectangle that stays
        // inside the sprite is the same sub-rectangle of its page, shifted; one
        // that reaches past it would pull in the gutter or the neighbour, so it
        // draws from the loose image, which is where those pixels honestly are.
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null && sx >= 0 && sy >= 0
                && sx + sw <= region.width() && sy + sh <= region.height()) {
            BufferedImage page = region.image();
            stats.record(DrawStats.Kind.IMAGE, page);
            float pw = page.getWidth(), ph = page.getHeight();
            quad(page, dx, dy, dx + dw, dy + dh,
                    (region.x() + sx) / pw, (region.y() + sy) / ph,
                    (region.x() + sx + sw) / pw, (region.y() + sy + sh) / ph);
            return;
        }
        stats.record(DrawStats.Kind.IMAGE, image);
        float iw = image.getWidth(), ih = image.getHeight();
        quad(image, dx, dy, dx + dw, dy + dh,
                sx / iw, sy / ih, (sx + sw) / iw, (sy + sh) / ih);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The four corners are transformed here and submitted as a quad, rather
     * than pushed as a GL matrix. A matrix per image would be a uniform change
     * per image, and a uniform change is a flush — so the one verb whose whole
     * purpose is drawing a great many isometric tiles would batch worst of all.
     */
    @Override
    public void drawImage(BufferedImage image, AffineTransform t) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        int w = image.getWidth(), h = image.getHeight();
        double[] pts = {0, 0, w, 0, w, h, 0, h};
        if (t != null) t.transform(pts, 0, pts, 0, 4);
        int colour = tint(0xFFFFFFFF);
        batch.useTexture(textures.of(image));
        vertex((float) pts[0], (float) pts[1], 0, 0, colour);
        vertex((float) pts[2], (float) pts[3], 1, 0, colour);
        vertex((float) pts[4], (float) pts[5], 1, 1, colour);
        vertex((float) pts[0], (float) pts[1], 0, 0, colour);
        vertex((float) pts[4], (float) pts[5], 1, 1, colour);
        vertex((float) pts[6], (float) pts[7], 0, 1, colour);
    }

    /** One textured quad, corners in logical pixels, UVs already normalised. */
    private void quad(BufferedImage image, float x0, float y0, float x1, float y1,
                      float u0, float v0, float u1, float v1) {
        int colour = tint(0xFFFFFFFF);
        batch.useTexture(textures.of(image));
        vertex(x0, y0, u0, v0, colour);
        vertex(x1, y0, u1, v0, colour);
        vertex(x1, y1, u1, v1, colour);
        vertex(x0, y0, u0, v0, colour);
        vertex(x1, y1, u1, v1, colour);
        vertex(x0, y1, u0, v1, colour);
    }

    // --- text ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p><b>This is the verb B5 and B6 were building towards.</b> The cells are
     * already on the sprite pages, so a label between two icons appends to the
     * open batch with no bind at all — which is why B6 packed into
     * {@code SpriteAtlas} instead of building a second atlas beside it, and why
     * the frames that gained nothing there are named as this step's to move.
     *
     * <p>Placement is the Java2D path's, deliberately and to the letter: per
     * character advances from {@link FontMetrics}, and a refusal to place
     * anything unless they sum to the run's own width. That property is what
     * makes drawing the glyphs one at a time the same operation as
     * {@code drawString} rather than an approximation of it, and
     * {@code GlyphAtlasTest} holds it at 0.00 on the other backend. A run that
     * fails it — needing shaping, or laid out as a unit — is baked whole and
     * drawn as one quad, because "unservable" must mean "drawn another way",
     * never "dropped".
     *
     * <p>The cells carry colour, which on this backend is the one avoidable
     * cost in the arrangement: GL could hold a coverage mask per glyph and
     * multiply the colour in at the vertex, where Java2D cannot. It is left as
     * it is because a second cell layout would mean the two backends packing
     * different pages, and every number B6 published is about the shared one.
     * If page pressure ever becomes real, this is the paragraph to come back to.
     */
    @Override
    public void drawText(String text, int x, int y, Font font, int argb) {
        if (text == null || text.isEmpty()) return;
        BufferedImage page = font == null || !GlyphAtlas.routing()
                ? null : resolveRun(text, font, argb);
        // Keyed exactly as the Java2D backend keys it, null font included, so
        // the two produce the same DrawStats from the same command stream and
        // B10 compares two backends rather than two instruments.
        stats.record(DrawStats.Kind.TEXT, page != null ? page : font);

        // A null font means "whatever the surface has", which on Java2D is the
        // Graphics2D's current font. The scratch surface here is the same kind
        // of object with the same default, so asking it gives the same answer.
        Font effective = font == null ? metrics.getFont() : font;
        if (page != null && placeRun(text, x, y, effective, page)) return;
        bakeRun(text, x, y, effective, argb);
    }

    /**
     * Pack every glyph of the run and return the page they share, or
     * {@code null} if they do not share one. Fills {@link #cells} on the way.
     */
    private BufferedImage resolveRun(String text, Font font, int argb) {
        int n = text.length();
        GlyphAtlas.Glyphs run = glyphsFor(font, argb);
        if (cells.length < n) cells = new GlyphAtlas.Cell[Math.max(64, n)];
        BufferedImage page = null;
        for (int i = 0; i < n; i++) {
            GlyphAtlas.Cell cell = run.cell(text.charAt(i));
            if (cell == null) return null;
            cells[i] = cell;
            if (cell.blank()) continue;
            BufferedImage image = cell.region().image();
            if (page == null) page = image;
            else if (page != image) return null;   // two pages is two binds
        }
        return page;
    }

    /** Emit one quad per glyph, or refuse the run and leave it to the bake. */
    private boolean placeRun(String text, int x, int y, Font font, BufferedImage page) {
        int n = text.length();
        FontMetrics fm = metrics.getFontMetrics(font);
        if (advances.length < n) advances = new int[Math.max(64, n)];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            advances[i] = fm.charWidth(text.charAt(i));
            sum += advances[i];
        }
        if (sum != fm.stringWidth(text)) return false;

        BufferedImage source = page;
        int colour = tint(0xFFFFFFFF);
        float pw = page.getWidth(), ph = page.getHeight();
        batch.useTexture(textures.of(source));
        int pen = x;
        for (int i = 0; i < n; i++) {
            GlyphAtlas.Cell cell = cells[i];
            if (!cell.blank()) {
                SpriteAtlas.Region region = cell.region();
                float gx = (float) (pen + cell.offsetX() / scale);
                float gy = (float) (y + cell.offsetY() / scale);
                float gw = (float) (region.width() / scale);
                float gh = (float) (region.height() / scale);
                vertexQuad(gx, gy, gx + gw, gy + gh,
                        region.x() / pw, region.y() / ph,
                        (region.x() + region.width()) / pw,
                        (region.y() + region.height()) / ph, colour);
            }
            pen += advances[i];
        }
        return true;
    }

    private void vertexQuad(float x0, float y0, float x1, float y1,
                            float u0, float v0, float u1, float v1, int colour) {
        vertex(x0, y0, u0, v0, colour);
        vertex(x1, y0, u1, v0, colour);
        vertex(x1, y1, u1, v1, colour);
        vertex(x0, y0, u0, v0, colour);
        vertex(x1, y1, u1, v1, colour);
        vertex(x0, y1, u0, v1, colour);
    }

    /**
     * The fallback: rasterise the whole run into an image and draw it as a
     * quad. Slow, cached, and correct for anything — including the shaped
     * scripts the glyph atlas refuses on purpose.
     */
    private void bakeRun(String text, int x, int y, Font font, int argb) {
        String key = font.getName() + '/' + font.getStyle() + '/' + font.getSize()
                + '/' + Integer.toHexString(argb) + '/' + text;
        BufferedImage baked = bakedRuns.get(key);
        int inkX, inkY;
        GlyphVector gv = font.createGlyphVector(fontContext, text);
        Rectangle ink = gv.getPixelBounds(fontContext, 0, 0);
        if (ink.width <= 0 || ink.height <= 0) return;
        inkX = ink.x;
        inkY = ink.y;
        if (baked == null) {
            baked = new BufferedImage(ink.width, ink.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = baked.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setFont(font);
                g.setColor(new java.awt.Color(argb, true));
                g.drawString(text, -ink.x, -ink.y);
            } finally {
                g.dispose();
            }
            bakedRuns.put(key, baked);
        }
        quad(baked, (float) (x + inkX / scale), (float) (y + inkY / scale),
                (float) (x + (inkX + ink.width) / scale),
                (float) (y + (inkY + ink.height) / scale), 0, 0, 1, 1);
    }

    private GlyphAtlas.Glyphs glyphsFor(Font font, int argb) {
        if (glyphs != null && argb == glyphsArgb && font.equals(glyphsFont)) return glyphs;
        glyphsFont = font;
        glyphsArgb = argb;
        glyphs = GlyphAtlas.shared().glyphs(font, argb, fontContext);
        return glyphs;
    }

    @Override
    public int textWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        return fontMetrics(font).stringWidth(text);
    }

    @Override
    public int textAscent(Font font) { return fontMetrics(font).getAscent(); }

    @Override
    public int textHeight(Font font) {
        FontMetrics fm = fontMetrics(font);
        return fm.getAscent() + fm.getDescent() + fm.getLeading();
    }

    /** Measurement never counts as drawing, so it does not touch the stats. */
    private FontMetrics fontMetrics(Font font) {
        return font == null ? metrics.getFontMetrics() : metrics.getFontMetrics(font);
    }

    // --- clipping --------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>A scissor rectangle, intersected with whatever is already pushed. The
     * cheap clip, and the one to reach for.
     *
     * <p>Under a rotation or a shear it is not a rectangle any more — Java2D
     * would clip to the transformed parallelogram — so the transform is
     * inspected and the awkward case is handed to the shape clip below rather
     * than approximated by its bounding box. An approximation here would show
     * up as a scene drawing slightly outside where it should, which reads as a
     * painter bug for as long as it takes to find.
     */
    @Override
    public void pushClip(int x, int y, int w, int h) {
        if (!axisAligned()) {
            pushClip(new Rectangle2D.Float(x, y, w, h));
            return;
        }
        stats.record(DrawStats.Kind.STATE, null);
        Point2D a = transform.transform(new Point2D.Float(x, y), null);
        Point2D b = transform.transform(new Point2D.Float(x + w, y + h), null);
        int rx = (int) Math.floor(Math.min(a.getX(), b.getX()));
        int ry = (int) Math.floor(Math.min(a.getY(), b.getY()));
        int rw = (int) Math.ceil(Math.abs(b.getX() - a.getX()));
        int rh = (int) Math.ceil(Math.abs(b.getY() - a.getY()));
        pushScissor(new int[]{rx, ry, rw, rh});
    }

    @Override
    public void pushClip(Shape shape) {
        stats.record(DrawStats.Kind.STATE, null);
        if (shape == null) {
            pushScissor(scissor);
            return;
        }
        batch.flush();
        // Contours stay in user space — the vertex path applies the transform,
        // as it does for every other verb — while the bounds the cover quad
        // uses are transformed here, because that quad is submitted in device
        // space and applying the transform to it a second time would slide the
        // cover off the coverage it is meant to reveal.
        List<GlPaths.Contour> contours = GlPaths.flatten(GlPaths.normalise(shape));
        Rectangle2D bounds = transformedBounds(shape);

        glEnable(GL_STENCIL_TEST);
        coveragePass(contours);

        // Raise the pixels that are both inside this shape and inside whatever
        // clip already applied, from the current level to the next one.
        glColorMask(false, false, false, false);
        glStencilMask(0x7F);
        glStencilFunc(GL_EQUAL, SCRATCH | stencilLevel, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        coverBounds(bounds);
        batch.flush();

        clearScratch();
        glColorMask(true, true, true, true);
        stencilLevel++;
        applyStencilTest();
        clipKinds.push(Boolean.TRUE);
    }

    @Override
    public void popClip() {
        if (clipKinds.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        if (clipKinds.pop()) {
            batch.flush();
            // Everything at the current level was raised by the matching push
            // and by nothing else, so lowering all of it is exact. The scissor
            // comes off first: it may have shrunk since the push, and a
            // decrement it cropped would strand pixels one level too high for
            // the rest of the frame.
            boolean hadScissor = glIsEnabled(GL_SCISSOR_TEST);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(false, false, false, false);
            glStencilMask(0x7F);
            glStencilFunc(GL_EQUAL, stencilLevel, 0x7F);
            glStencilOp(GL_KEEP, GL_KEEP, GL_DECR);
            coverBounds(new Rectangle2D.Float(0, 0, width, height));
            batch.flush();
            glColorMask(true, true, true, true);
            if (hadScissor) glEnable(GL_SCISSOR_TEST);
            stencilLevel--;
            applyStencilTest();
        } else {
            batch.flush();
            int[] previous = scissors.pop();
            scissor = previous.length == 0 ? null : previous;
            applyScissor();
        }
    }

    /**
     * Stand-in for "no clip" on the stack.
     *
     * <p>{@link ArrayDeque} rejects nulls and an unclipped target — the state
     * every frame starts in and most painters push their first clip from — is
     * exactly null. The same problem, and the same answer, as
     * {@code Java2DTarget.NO_CLIP}.
     */
    private static final int[] UNCLIPPED = new int[0];

    private void pushScissor(int[] rect) {
        batch.flush();
        scissors.push(scissor == null ? UNCLIPPED : scissor);
        scissor = scissor == null ? rect : (rect == null ? null : intersect(scissor, rect));
        clipKinds.push(Boolean.FALSE);
        applyScissor();
    }

    private static int[] intersect(int[] a, int[] b) {
        int x = Math.max(a[0], b[0]);
        int y = Math.max(a[1], b[1]);
        int right = Math.min(a[0] + a[2], b[0] + b[2]);
        int bottom = Math.min(a[1] + a[3], b[1] + b[3]);
        return new int[]{x, y, Math.max(0, right - x), Math.max(0, bottom - y)};
    }

    private void applyScissor() {
        if (scissor == null) {
            glDisable(GL_SCISSOR_TEST);
            return;
        }
        glEnable(GL_SCISSOR_TEST);
        // GL measures the scissor box from the bottom-left of the framebuffer;
        // every coordinate in this engine is measured from the top-left.
        int deviceH = (int) Math.round(height * scale);
        int x = (int) Math.round(scissor[0] * scale);
        int w = (int) Math.round(scissor[2] * scale);
        int h = (int) Math.round(scissor[3] * scale);
        int y = deviceH - (int) Math.round(scissor[1] * scale) - h;
        glScissor(x, y, Math.max(0, w), Math.max(0, h));
    }

    /** Whether the current transform maps rectangles to rectangles. */
    private boolean axisAligned() {
        return transform.getShearX() == 0 && transform.getShearY() == 0
                && transform.getScaleX() > 0 && transform.getScaleY() > 0;
    }

    // --- the stencil -----------------------------------------------------------

    /**
     * The high bit, reserved for path coverage.
     *
     * <p>The low seven hold the clip nesting level, and the two never share a
     * bit — which is what lets a shape be filled <em>inside</em> a shape clip
     * without either forgetting the other. The alternative arrangement, a
     * winding count in the same bits, cannot be masked apart: {@code GL_INCR}
     * carries across the whole value, so a count and a level in one byte
     * corrupt each other on the first overflow. Parity in one isolated bit does
     * not, which is why the fill rule here is even-odd and why
     * {@link GlPaths#normalise} exists to make that lossless.
     */
    private static final int SCRATCH = 0x80;

    /**
     * Fill contours through the stencil: rasterise coverage, cover the bounds
     * with the colour, put the stencil back.
     *
     * <p>Three passes and two clears for one fill, which is why the convex
     * fast path above exists and takes almost everything. What is left is the
     * terrain shadow union — one call a frame, where the whole point is that
     * overlapping shadows fill once instead of stacking — and a pie slice wider
     * than a half turn. Correctness beats speed on both, which is what the plan
     * asked for.
     */
    private void stencilFill(List<GlPaths.Contour> contours, int argb, Rectangle2D userBounds) {
        batch.flush();
        glEnable(GL_STENCIL_TEST);

        coveragePass(contours);

        glColorMask(true, true, true, true);
        glStencilMask(0x00);
        glStencilFunc(GL_EQUAL, SCRATCH | stencilLevel, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        // Cropped to the surface: the cover quad's only job is to touch every
        // pixel the coverage might have set, and a shape whose bounds run a few
        // thousand pixels off-screen would otherwise rasterise all of them to
        // find out they were not.
        Rectangle2D clipped = transformedBounds(userBounds).createIntersection(
                new Rectangle2D.Float(0, 0, width, height));
        coverBoundsColoured(clipped, argb);
        batch.flush();

        clearScratch();
        applyStencilTest();
    }

    /**
     * A user-space rectangle after the transform stack, still in the logical
     * pixels the projection is written in — the scale to device pixels belongs
     * to the viewport, and applying it here as well would double it.
     */
    private Rectangle2D transformedBounds(Shape userShape) {
        return identity ? userShape.getBounds2D()
                : transform.createTransformedShape(userShape).getBounds2D();
    }

    /** Write each contour's even-odd coverage into {@link #SCRATCH}. */
    private void coveragePass(List<GlPaths.Contour> contours) {
        clearScratch();
        glColorMask(false, false, false, false);
        glStencilMask(SCRATCH);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INVERT);
        useFlatTexture();
        GlPaths.Tri sink = flatSink(0xFFFFFFFF);
        for (GlPaths.Contour contour : contours) GlPaths.coverageFan(contour, sink);
        batch.flush();
    }

    private void clearScratch() {
        glStencilMask(SCRATCH);
        glClear(GL_STENCIL_BUFFER_BIT);
        glStencilMask(0x00);
    }

    /** Restore the stencil test the current clip nesting implies. */
    private void applyStencilTest() {
        glStencilMask(0x00);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        if (stencilLevel == 0) {
            glDisable(GL_STENCIL_TEST);
        } else {
            glEnable(GL_STENCIL_TEST);
            glStencilFunc(GL_EQUAL, stencilLevel, 0x7F);
        }
    }

    private void coverBounds(Rectangle2D bounds) {
        coverBoundsColoured(bounds, 0xFFFFFFFF);
    }

    private void coverBoundsColoured(Rectangle2D bounds, int argb) {
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) return;
        useFlatTexture();
        float x0 = (float) Math.floor(bounds.getMinX()) - 1;
        float y0 = (float) Math.floor(bounds.getMinY()) - 1;
        float x1 = (float) Math.ceil(bounds.getMaxX()) + 1;
        float y1 = (float) Math.ceil(bounds.getMaxY()) + 1;
        // Submitted past the transform: the coverage was already rasterised
        // through it, so applying it again would move the cover off the pixels
        // it exists to reveal.
        batch.vertex(x0, y0, whiteU, whiteV, argb);
        batch.vertex(x1, y0, whiteU, whiteV, argb);
        batch.vertex(x1, y1, whiteU, whiteV, argb);
        batch.vertex(x0, y0, whiteU, whiteV, argb);
        batch.vertex(x1, y1, whiteU, whiteV, argb);
        batch.vertex(x0, y1, whiteU, whiteV, argb);
    }

    private static Rectangle2D boundsOf(float[] xy) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < xy.length; i += 2) {
            minX = Math.min(minX, xy[i]);
            maxX = Math.max(maxX, xy[i]);
            minY = Math.min(minY, xy[i + 1]);
            maxY = Math.max(maxY, xy[i + 1]);
        }
        return new Rectangle2D.Float(minX, minY, maxX - minX, maxY - minY);
    }

    // --- scoped state ----------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Multiplied into the vertex colour, which is the reason this verb is on
     * the interface at all rather than a composite mode. It costs nothing, and
     * — the part that matters — it does not break the batch, so the entity
     * phase can fade thirty sprites individually and still submit them as one
     * draw.
     */
    @Override
    public void pushAlpha(float a) {
        stats.record(DrawStats.Kind.STATE, null);
        alphas.push(alpha);
        alpha *= Math.max(0f, Math.min(1f, a));
    }

    @Override
    public void popAlpha() {
        if (alphas.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        alpha = alphas.pop();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applied on the CPU as vertices are appended, for the same reason as
     * the alpha: a GL matrix is a uniform, a uniform change is a flush, and the
     * transform stack moves several times per entity.
     */
    @Override
    public void pushTransform(AffineTransform t) {
        stats.record(DrawStats.Kind.STATE, null);
        transforms.push(transform);
        AffineTransform combined = new AffineTransform(transform);
        if (t != null) combined.concatenate(t);
        setTransform(combined);
    }

    @Override
    public void popTransform() {
        if (transforms.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        setTransform(transforms.pop());
    }

    @Override
    public void close() {
        batch.close();
        textures.close();
        program.close();
        metrics.dispose();
    }
}
