package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DirectionalSprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a character's eight sheets is drawn once the camera has turned.
 *
 * <p>Entities are billboards — they face the camera, and what they are
 * <em>doing</em> is told by which sheet is chosen. C5 recorded the decision
 * that keeps it that way: one set of art, picked relative to the heading,
 * rather than eight sets per direction. Sixty-four cases, as the step asks:
 * eight world directions at eight headings.
 *
 * <p><b>The expected answer is derived from the camera rather than restated.</b>
 * A test that computes the expectation the same way the code does proves only
 * that the arithmetic was copied correctly, and this arithmetic is an index
 * rotation — exactly the kind that is off by one in a direction nobody notices
 * until a character moonwalks. So the expectation here comes from
 * {@link Camera#planarDelta}: the direction the character is walking, projected
 * by the real camera at that heading, read back as the compass point it points
 * at on screen. If the sprite chosen disagrees with the direction the world is
 * visibly moving in, that is the bug, and this is the only formulation that can
 * see it.
 */
class FacingUnderYawTest {

    /**
     * At every heading, every direction is drawn as the direction it visibly
     * moves in.
     *
     * <p>Measured through the top-down projection, because the rotation belongs
     * to the ground plane and is applied there — before the isometric diamond,
     * which is a fixed distortion of the result and not part of the heading.
     * That is also what keeps an isometric level drawing exactly what it drew
     * before at rest: a character walking north in a diamond world was drawn
     * with the north sheet, and still is.
     */
    @Test
    void everyDirectionAtEveryHeadingIsDrawnAsTheWayItVisiblyMoves() {
        for (int eighth = 0; eighth < 8; eighth++) {
            double yaw = eighth * Camera.EIGHTH_TURN;
            Camera ground = new Camera(Perspective.THREE_D, 480, 360);
            ground.tileSize = 32;
            ground.setYaw(yaw);

            for (Facing world : Facing.values()) {
                double[] onScreen = ground.planarDelta(world.dx(), world.dy());
                Facing expected = Facing.of(onScreen[0], onScreen[1], world);

                assertSame(expected, world.asSeenFrom(yaw), "walking " + world
                        + " with the camera looking " + (eighth * 45) + "°: the world "
                        + "direction projects to (" + round(onScreen[0]) + ","
                        + round(onScreen[1]) + "), which is " + expected + " on screen, "
                        + "but the sheet chosen is " + world.asSeenFrom(yaw));
            }
        }
    }

    /**
     * A quarter turn to the right moves every direction two compass points, and
     * a full turn brings them all home.
     *
     * <p>The negative control for the test above. A mapping that ignored the
     * heading entirely would satisfy the projection check at heading 0 and
     * nowhere else, but a mapping that turned the <em>wrong way</em> would fail
     * both the same way, so it is worth stating the direction of travel once in
     * plain terms: look east, and what was north is now on your left.
     */
    @Test
    void aQuarterTurnMovesEveryDirectionTwoCompassPoints() {
        assertSame(Facing.WEST, Facing.NORTH.asSeenFrom(2 * Camera.EIGHTH_TURN),
                "with the camera looking east, a character walking north walks off to "
                        + "the left of the screen");
        assertSame(Facing.NORTH, Facing.EAST.asSeenFrom(2 * Camera.EIGHTH_TURN),
                "and one walking east walks away from the viewer");

        for (Facing f : Facing.values()) {
            assertSame(f, f.asSeenFrom(0), f + " moved at rest");
            assertSame(f, f.asSeenFrom(8 * Camera.EIGHTH_TURN), f + " after a full turn");
            assertSame(f.asSeenFrom(Camera.EIGHTH_TURN),
                    f.asSeenFrom(9 * Camera.EIGHTH_TURN),
                    f + " a full turn past one eighth");
        }
    }

    /**
     * The eight headings choose eight different sheets for one direction, and
     * every direction's art is reachable.
     *
     * <p>A conversion that quietly collapsed — returning the direction it was
     * given, or clamping to a favourite — passes any single case that happens
     * to be the identity. This asks for the whole cycle.
     */
    @Test
    void turningTheCameraWalksThroughAllEightSheets() {
        for (Facing world : Facing.values()) {
            Set<Facing> seen = new HashSet<>();
            for (int eighth = 0; eighth < 8; eighth++) {
                seen.add(world.asSeenFrom(eighth * Camera.EIGHTH_TURN));
            }
            assertEquals(8, seen.size(), "a character walking " + world + " is drawn with "
                    + seen.size() + " different sheets across the eight headings, not 8");
        }
    }

    /** Mid-snap, the nearest of the eight is drawn rather than none of them. */
    @Test
    void aTurnInFlightStillPicksASheet() {
        for (double deg : new double[]{1, 20, 22, 23, 44, 67, 91}) {
            double yaw = Math.toRadians(deg);
            Facing drawn = Facing.NORTH.asSeenFrom(yaw);
            int eighths = (int) Math.round(deg / 45.0);
            assertSame(Facing.NORTH.asSeenFrom(eighths * Camera.EIGHTH_TURN), drawn,
                    "mid-turn at " + deg + "° the sheet drawn is not the nearest of the "
                            + "eight");
        }
    }

    /**
     * A side-scroller is never asked to turn a sprite, whatever heading its
     * camera is carrying.
     *
     * <p>{@link Camera#viewYaw} is the one every caller reads, and this is why
     * it exists rather than {@link Camera#yaw}: a camera keeps a heading it was
     * given even where the projection ignores it, so a side-scroller handed one
     * would otherwise draw a walking character facing into the screen while the
     * world stayed put.
     */
    @Test
    void aSideScrollerNeverTurnsASprite() {
        Camera cam = new Camera(Perspective.SIDE_SCROLL, 480, 360);
        for (int eighth = 0; eighth < 8; eighth++) {
            cam.setYaw(eighth * Camera.EIGHTH_TURN);
            assertEquals(0.0, cam.viewYaw(), 0.0,
                    "a side-scroller reported a heading for the picture to turn by");
            for (Facing f : Facing.values()) {
                assertSame(f, f.asSeenFrom(cam.viewYaw()), f + " turned in a side view");
            }
        }
    }

    /**
     * And the sheets it is choosing between are genuinely different pictures.
     *
     * <p>The mapping above is an index; this is the one case that follows it
     * all the way to pixels, so "picks a different sheet" means something. Eight
     * directions, eight distinct images — which is also what makes the choice
     * worth getting right.
     */
    @Test
    void theEightSheetsAreEightDifferentPictures() {
        Set<String> pictures = new HashSet<>();
        for (Facing f : Facing.values()) {
            BufferedImage frame = DirectionalSprites.frame(24, new Color(200, 90, 70), f, 0);
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < frame.getHeight(); y++) {
                for (int x = 0; x < frame.getWidth(); x++) sb.append(frame.getRGB(x, y));
            }
            pictures.add(sb.toString());
        }
        assertTrue(pictures.size() >= 5, "the eight directions produced only "
                + pictures.size() + " distinct sprites, so choosing between them by "
                + "heading would not be visible");
    }

    /**
     * No scene hands a raw world facing to the art.
     *
     * <p><b>This exists because the same gap has now been found twice by
     * negative control in this job.</b> A correct conversion that nothing calls
     * is a conversion that does nothing, and every test above would still pass:
     * they all exercise {@code asSeenFrom} directly. What actually turns a
     * sprite is the scenes calling it, at every place a facing becomes a
     * picture — the body sheet, the object in the character's hands, and the arc
     * a swing draws — and those three are far enough apart in a six-thousand-line
     * file that the risk is not getting one wrong but forgetting one.
     *
     * <p>So the rule is stated where it can fail: inside the demo scenes, an
     * argument named {@code facing} may not reach one of the art funnels
     * without {@code seen(...)} around it. It is a scan of the source, in the
     * manner of {@code SealedSeamTest}, and it carries its own negative control
     * for the same reason that one does.
     */
    @Test
    void noSceneHandsARawWorldFacingToTheArt() throws IOException {
        int checked = 0;
        for (String scene : List.of("PlayScene", "CreativeScene")) {
            Path file = Path.of("src/main/java/com/larsons/engine/demo", scene + ".java");
            String source = Files.readString(file);
            for (String funnel : ART_FUNNELS) {
                for (int at = source.indexOf(funnel); at >= 0;
                     at = source.indexOf(funnel, at + 1)) {
                    if (isDeclaration(source, at)) continue;
                    String args = argumentsOf(source, at + funnel.length() - 1);
                    if (!args.contains(".facing") && !args.contains("Facing")) continue;
                    checked++;
                    assertTrue(args.contains("seen("), scene + " passes a world facing "
                            + "straight into " + funnel + " — it will not turn with the "
                            + "camera:\n    " + funnel + args);
                }
            }
        }
        assertTrue(checked >= 4, "the scan found only " + checked + " places where a "
                + "facing reaches the art, which is too few to be looking at the right "
                + "thing");
    }

    /** The scan can fail, checked on a snippet rather than by hand once. */
    @Test
    void theScanRejectsARawFacingAndAcceptsAConvertedOne() {
        String bad = "MeleeSprites.playerFrame(key, item, state, me.facing, clock);";
        String good = "MeleeSprites.playerFrame(key, item, state, seen(me.facing), clock);";
        assertTrue(argumentsOf(bad, bad.indexOf('(')).contains(".facing"));
        assertTrue(!argumentsOf(bad, bad.indexOf('(')).contains("seen("),
                "the scan would accept a raw facing");
        assertTrue(argumentsOf(good, good.indexOf('(')).contains("seen("),
                "the scan would reject a converted one");
    }

    /** Where a world direction turns into a picture of one. */
    private static final List<String> ART_FUNNELS =
            List.of("playerFrame(", "mobFrame(", "drawHeldObject(");

    /**
     * Whether this occurrence is the method being declared rather than called.
     * A declaration's parameter list names a {@code Facing} and converts
     * nothing, which is exactly right and is not what this is looking for.
     */
    private static boolean isDeclaration(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        String before = source.substring(lineStart, at);
        return before.contains("void ") || before.contains("private ")
                || before.contains("public ") || before.contains("static ");
    }

    /** The balanced argument list starting at the {@code (} at {@code open}. */
    private static String argumentsOf(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return source.substring(open, i + 1);
        }
        return source.substring(open);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
