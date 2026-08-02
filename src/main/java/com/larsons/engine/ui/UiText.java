package com.larsons.engine.ui;

import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * Text fitting for the menu widgets: shortening a string so it stays inside the
 * space a screen actually has for it.
 *
 * <p>Screens are built from strings that are only known at runtime — the game
 * type the creator named, a level name, a file path typed into a field — so a
 * widget can never assume the copy handed to it is short enough. {@link Menu}
 * and {@link ConfigForm} run every label through here, which is what keeps a
 * label from being drawn over the control beside it or off the edge of the
 * screen.
 *
 * <p><b>Measuring is separated from whatever does the measuring.</b> Every
 * method here needs one thing from outside — how wide a string is — and there
 * are now two things that can answer: a {@link FontMetrics}, for code still
 * holding a {@code Graphics2D}, and a {@link DrawTarget} with a {@link Font},
 * for code that has been ported. Rather than two copies of each algorithm
 * (they are bisections, and a bisection copied twice is a bisection wrong
 * once), the algorithms take a {@link Measure} and the overloads are
 * one-liners.
 *
 * <p>This is not a compatibility shim of the kind B2 forbids: this class does
 * no drawing, so it has no backend to be tied to. Both overloads are permanent,
 * and a GL backend answers through the {@code DrawTarget} one — which is what
 * will keep layout identical across backends when B6 changes how glyphs are
 * rasterised but must not change where they land.
 */
public final class UiText {

    /** Marks where a string had to be cut. */
    public static final String ELLIPSIS = "…";

    /** How wide a string is, however the caller happens to be able to tell. */
    @FunctionalInterface
    public interface Measure {
        int width(String s);
    }

    private UiText() {}

    public static Measure by(FontMetrics fm) {
        return fm::stringWidth;
    }

    public static Measure by(DrawTarget target, Font font) {
        return s -> target.textWidth(s, font);
    }

    /**
     * {@code s} shortened from the right — {@code "Texture pack fold…"} — so it
     * draws no wider than {@code maxWidth}. Returns {@code s} untouched when it
     * already fits, and {@code ""} when not even the ellipsis does.
     */
    public static String fit(Measure measure, String s, int maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return "";
        if (measure.width(s) <= maxWidth) return s;
        int budget = maxWidth - measure.width(ELLIPSIS);
        if (budget < 0) return "";
        // Prefix widths only grow, so the longest prefix that still leaves room
        // for the ellipsis can be found by bisection instead of a char-by-char
        // walk — this runs for every visible row of every frame.
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (measure.width(s.substring(0, mid)) <= budget) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ELLIPSIS;
    }

    public static String fit(FontMetrics fm, String s, int maxWidth) {
        return fit(by(fm), s, maxWidth);
    }

    public static String fit(DrawTarget target, Font font, String s, int maxWidth) {
        return fit(by(target, font), s, maxWidth);
    }

    /**
     * {@code s} shortened from the left — {@code "…/units/hero_walk.png"} —
     * keeping the <em>end</em> on screen. This is what a text field shows: typing
     * appends, so the tail is the part being worked on.
     */
    public static String fitTail(Measure measure, String s, int maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return "";
        if (measure.width(s) <= maxWidth) return s;
        int budget = maxWidth - measure.width(ELLIPSIS);
        if (budget < 0) return "";
        // Suffix widths shrink as the start moves right; bisect for the first
        // start that fits.
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (measure.width(s.substring(mid)) <= budget) hi = mid; else lo = mid + 1;
        }
        return ELLIPSIS + s.substring(lo);
    }

    public static String fitTail(FontMetrics fm, String s, int maxWidth) {
        return fitTail(by(fm), s, maxWidth);
    }

    public static String fitTail(DrawTarget target, Font font, String s, int maxWidth) {
        return fitTail(by(target, font), s, maxWidth);
    }

    /**
     * Greedy word wrap of {@code text} into lines at most {@code maxWidth} wide.
     * A single word too long for a line is left on its own line rather than
     * broken, so callers that also cap the line count should run the result
     * through {@link #fit}.
     */
    public static List<String> wrap(Measure measure, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String probe = line.isEmpty() ? word : line + " " + word;
            if (measure.width(probe) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    public static List<String> wrap(FontMetrics fm, String text, int maxWidth) {
        return wrap(by(fm), text, maxWidth);
    }

    public static List<String> wrap(DrawTarget target, Font font, String text, int maxWidth) {
        return wrap(by(target, font), text, maxWidth);
    }

    /**
     * {@link #wrap} capped at {@code maxLines}, with the last line ellipsised
     * when the text did not fit in them.
     */
    public static List<String> wrap(Measure measure, String text, int maxWidth, int maxLines) {
        List<String> lines = wrap(measure, text, maxWidth);
        if (maxLines <= 0) return new ArrayList<>();
        if (lines.size() <= maxLines) return lines;
        // Everything past the cap is folded back onto the last kept line and
        // ellipsised there, so the "…" reads as "and there was more".
        List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
        String tail = String.join(" ", lines.subList(maxLines - 1, lines.size()));
        kept.set(maxLines - 1, fit(measure, tail, maxWidth));
        return kept;
    }

    public static List<String> wrap(FontMetrics fm, String text, int maxWidth, int maxLines) {
        return wrap(by(fm), text, maxWidth, maxLines);
    }

    public static List<String> wrap(DrawTarget target, Font font, String text,
                                    int maxWidth, int maxLines) {
        return wrap(by(target, font), text, maxWidth, maxLines);
    }
}
