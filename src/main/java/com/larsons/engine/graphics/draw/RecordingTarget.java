package com.larsons.engine.graphics.draw;

import java.awt.Font;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link DrawTarget} that writes down what it was asked to draw instead of
 * drawing it.
 *
 * <p><b>What this buys.</b> Rendering code has been the least testable part of
 * the engine, because asserting on a painter meant standing up a window,
 * rasterizing, and comparing pixels — so in practice nobody asserted on it at
 * all. Against this, a painter's output is a list of values: that an isometric
 * tile emitted one transformed image and not four, that a shadow was drawn
 * before the block casting it, that a hidden row emitted nothing. Those are the
 * properties that actually break during a migration, and they are now cheap to
 * pin down, headless, in milliseconds.
 *
 * <p>It is also the check that a port changed nothing: record a painter through
 * the old path and the new one, and compare the command lists.
 *
 * <p>Not a mock — there is no expectation-setting and nothing to configure. It
 * records, and the test asks questions afterwards.
 */
public final class RecordingTarget implements DrawTarget {

    /** One recorded operation. */
    public sealed interface Cmd {

        /** The operation's name, matching the {@link DrawTarget} method. */
        String op();

        /** Solid-colour geometry. */
        record Shape(String op, int[] coords, int argb, float thickness) implements Cmd {}

        /** A textured draw; {@code transform} is set only for the warped form. */
        record Image(String op, BufferedImage image, int[] coords,
                     AffineTransform transform) implements Cmd {}

        /** A run of glyphs at a baseline. */
        record Text(String op, String text, int x, int y, Font font, int argb) implements Cmd {}

        /** A scoped state change: clip, alpha or transform, pushed or popped. */
        record State(String op, int[] coords, float alpha,
                     AffineTransform transform) implements Cmd {}
    }

    private final List<Cmd> commands = new ArrayList<>();
    private final DrawStats stats = new DrawStats();
    private final int width;
    private final int height;

    /** Text metrics are faked, so this needs no font system and no display. */
    private final int glyphWidth;
    private final int ascent;
    private final int lineHeight;

    public RecordingTarget(int width, int height) {
        this(width, height, 7, 11, 14);
    }

    public RecordingTarget(int width, int height, int glyphWidth, int ascent, int lineHeight) {
        this.width = width;
        this.height = height;
        this.glyphWidth = glyphWidth;
        this.ascent = ascent;
        this.lineHeight = lineHeight;
    }

    /** Everything recorded, in the order it was drawn. */
    public List<Cmd> commands() { return List.copyOf(commands); }

    /** Just the operation names, which is what most assertions are about. */
    public List<String> ops() {
        return commands.stream().map(Cmd::op).toList();
    }

    /** How many times {@code op} was issued. */
    public int count(String op) {
        return (int) commands.stream().filter(c -> c.op().equals(op)).count();
    }

    /** The recorded commands of one type, in order. */
    @SuppressWarnings("unchecked")
    public <T extends Cmd> List<T> ofType(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Cmd c : commands) {
            if (type.isInstance(c)) out.add((T) c);
        }
        return out;
    }

    /** Forget everything recorded so far. */
    public void clearRecording() {
        commands.clear();
        stats.reset();
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public DrawStats stats() { return stats; }

    // --- shapes ----------------------------------------------------------------

    @Override
    public void clear(int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("clear", new int[]{0, 0, width, height}, argb, 0f));
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("fillRect", new int[]{x, y, w, h}, argb, 0f));
    }

    @Override
    public void fillOval(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("fillOval", new int[]{x, y, w, h}, argb, 0f));
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("fillPolygon", interleave(xs, ys, count), argb, 0f));
    }

    @Override
    public void drawRect(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("drawRect", new int[]{x, y, w, h}, argb, thickness));
    }

    @Override
    public void drawOval(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("drawOval", new int[]{x, y, w, h}, argb, thickness));
    }

    @Override
    public void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("drawPolygon", interleave(xs, ys, count), argb, thickness));
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        commands.add(new Cmd.Shape("drawLine", new int[]{x1, y1, x2, y2}, argb, thickness));
    }

    // --- images ----------------------------------------------------------------

    @Override
    public void drawImage(BufferedImage image, int x, int y) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        commands.add(new Cmd.Image("drawImage", image, new int[]{x, y}, null));
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y, int w, int h) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        commands.add(new Cmd.Image("drawImageScaled", image, new int[]{x, y, w, h}, null));
    }

    @Override
    public void drawImage(BufferedImage image, int dx, int dy, int dw, int dh,
                          int sx, int sy, int sw, int sh) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        commands.add(new Cmd.Image("drawImageRegion", image,
                new int[]{dx, dy, dw, dh, sx, sy, sw, sh}, null));
    }

    @Override
    public void drawImage(BufferedImage image, AffineTransform transform) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        commands.add(new Cmd.Image("drawImageTransformed", image, new int[0],
                transform == null ? null : new AffineTransform(transform)));
    }

    // --- text ------------------------------------------------------------------

    @Override
    public void drawText(String text, int x, int y, Font font, int argb) {
        if (text == null || text.isEmpty()) return;
        stats.record(DrawStats.Kind.TEXT, font);
        commands.add(new Cmd.Text("drawText", text, x, y, font, argb));
    }

    @Override
    public int textWidth(String text, Font font) {
        return text == null ? 0 : text.length() * glyphWidth;
    }

    @Override public int textAscent(Font font) { return ascent; }

    @Override public int textHeight(Font font) { return lineHeight; }

    // --- scoped state ----------------------------------------------------------

    @Override
    public void pushClip(int x, int y, int w, int h) {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("pushClip", new int[]{x, y, w, h}, 1f, null));
    }

    @Override
    public void popClip() {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("popClip", new int[0], 1f, null));
    }

    @Override
    public void pushAlpha(float alpha) {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("pushAlpha", new int[0], alpha, null));
    }

    @Override
    public void popAlpha() {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("popAlpha", new int[0], 1f, null));
    }

    @Override
    public void pushTransform(AffineTransform transform) {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("pushTransform", new int[0], 1f,
                transform == null ? null : new AffineTransform(transform)));
    }

    @Override
    public void popTransform() {
        stats.record(DrawStats.Kind.STATE, null);
        commands.add(new Cmd.State("popTransform", new int[0], 1f, null));
    }

    /** Polygon points as {@code x0, y0, x1, y1, ...} so one array holds the shape. */
    private static int[] interleave(int[] xs, int[] ys, int count) {
        int[] out = new int[count * 2];
        for (int i = 0; i < count; i++) {
            out[i * 2] = xs[i];
            out[i * 2 + 1] = ys[i];
        }
        return out;
    }
}
