package com.larsons.engine.demo;

import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Color;
import java.awt.Font;

/**
 * The furniture every menu screen draws: a flat backdrop, a status line, and a
 * hint line along the bottom.
 *
 * <p><b>Why it is worth a class.</b> Nine scenes drew this by hand, and the
 * port made the duplication legible: the same {@code new Color(18, 18, 28)},
 * the same {@code new Font("SansSerif", Font.PLAIN, 14)}, the same 24px inset
 * and the same two baselines at 24 and 44 pixels off the bottom, written out
 * nine times. That is nine chances for one of them to drift by a pixel, and —
 * because a {@code Color} and a {@code Font} were constructed inside
 * {@code render} — eighteen allocations per screen per frame for values that
 * never change.
 *
 * <p>Deliberately not a widget framework. It holds the constants and the three
 * one-line draws that were genuinely identical everywhere; a scene whose status
 * line is its own thing (see {@code MultiplayerScene}, which needs a bold 15pt
 * line on a third baseline) keeps drawing it itself rather than growing a
 * parameter here for one caller.
 */
final class SceneChrome {

    private SceneChrome() {}

    /** The near-black every menu screen clears to. */
    static final Color BACKDROP = new Color(18, 18, 28);

    /** The one body font the bottom lines are set in. */
    static final Font BODY = new Font("SansSerif", Font.PLAIN, 14);

    /** Grey, for the hint line: present but not competing with the content. */
    static final Color HINT = new Color(120, 120, 140);

    /** Green, for a status line reporting that something worked. */
    static final Color OK = new Color(140, 200, 140);

    /** Amber, for a status line asking the player for something. */
    static final Color PROMPT = new Color(255, 210, 90);

    /** Left inset shared by both bottom lines. */
    private static final int INSET = 24;

    /** Fill the viewport with {@link #BACKDROP}. */
    static void backdrop(DrawTarget target, int width, int height) {
        target.fillRect(0, 0, width, height, BACKDROP);
    }

    /** The bottom line: what the controls do. */
    static void hint(DrawTarget target, int height, String text) {
        target.drawText(text, INSET, height - INSET, BODY, HINT);
    }

    /** The line above the hint: what just happened. */
    static void status(DrawTarget target, int height, String text, Color color) {
        target.drawText(text, INSET, height - 44, BODY, color);
    }
}
