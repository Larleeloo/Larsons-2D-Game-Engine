package com.larsons.engine.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java2D lives in {@code com.larsons.engine.graphics} and nowhere else.
 *
 * <p><b>Why a test and not a convention.</b> B1–B3 moved every painter, widget
 * and scene in the engine off {@link java.awt.Graphics2D} and onto
 * {@link com.larsons.engine.graphics.draw.DrawTarget}. That migration is worth
 * exactly as much as its weakest future commit: one {@code import
 * java.awt.Graphics2D} in a new scene, one helper that unwraps the frame's
 * target because it was quicker, and the GPU backend can no longer draw that
 * screen. The count would go back up silently, because nothing that runs on
 * every build would notice. This notices.
 *
 * <p><b>What it forbids, and why each one.</b> Scanning source text rather than
 * bytecode is deliberate — it needs no dependency, and the thing being
 * prevented is written in source. Comments and string literals are stripped
 * first, so this is a rule about code and prose about the migration stays
 * legal.
 *
 * <ul>
 *   <li><b>{@code graphicsOf}</b> — deleted in B4. It was static and took any
 *       {@code DrawTarget}, so one line at the top of a render method handed
 *       the whole body below it back to Java2D. The name is banned outright so
 *       nobody reintroduces the idea under its old name.</li>
 *   <li><b>{@code Java2DTarget}</b> — naming the concrete target is the other
 *       route to the same place, whether by {@code instanceof}, by cast, or by
 *       accepting one as a parameter. Backend-neutral code has no reason to
 *       know which backend it has.</li>
 *   <li><b>{@code .graphics()}</b> — what a caster would call next.</li>
 *   <li><b>{@code Graphics2D} itself</b> — the token, not just the import, so a
 *       fully-qualified {@code java.awt.Graphics2D} does not slip past. Three
 *       files are excused, and only for baking: see {@link #BAKES}.</li>
 * </ul>
 *
 * <p><b>Complementary to {@code SceneFramesTest.noSceneReachesPastTheDrawTarget},
 * not a substitute.</b> That test renders every scene through a target with no
 * Graphics2D behind it, so it sees what the code <em>does</em>, including
 * through a helper three classes away — but only along the paths a golden frame
 * happens to take. This one sees every line of every file and nothing it does
 * at runtime. Each covers the other's blind spot.
 */
class SealedSeamTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path ENGINE = MAIN.resolve("com/larsons/engine");

    /**
     * The Java2D backend itself, which is the one package allowed to name it.
     * Everything under here is either the seam ({@code graphics/draw}), the
     * renderer that presents a frame, or a bake-time sprite factory.
     */
    private static final Path BACKEND = ENGINE.resolve("graphics");

    /**
     * Files outside the backend that may still name {@code Graphics2D}, each
     * because it <em>bakes</em> an image rather than drawing a frame.
     *
     * <p>Baking is Java2D by definition and correctly so: it happens once at
     * load, into a {@link java.awt.image.BufferedImage} the caller owns, and
     * the result is handed to the engine as a texture that any backend can
     * draw. A GPU backend does not want these as draw calls; it wants the
     * finished image. So the rule for an excused file is not "trust it" but
     * {@link #bakeViolations}: it must actually call {@code createGraphics()},
     * and no {@code public} or {@code protected} member of it may so much as
     * mention {@code Graphics2D}. A bake that keeps its Graphics2D to itself is
     * fine; one that hands it out is a seam by another name.
     */
    private static final Map<String, String> BAKES = new TreeMap<>(Map.of(
            "com/larsons/engine/demo/CreativeScene.java",
            "bakes 24 palette icons at load; every use is img.createGraphics()",
            "com/larsons/engine/character/CharacterPicker.java",
            "bakes a profile's palette icon from its idle sprite",
            "com/larsons/engine/autobattler/AutoSprites.java",
            "bakes and caches HUD pips and stars, one image per key"));

    /** Names no code outside the Java2D backend may contain, and why. */
    private static final Map<String, String> FORBIDDEN = new TreeMap<>(Map.of(
            "graphicsOf", "deleted in B4 — unwrapping a DrawTarget is not a thing any more",
            "Java2DTarget", "backend-neutral code must not name a concrete backend",
            ".graphics()", "reaching the Graphics2D behind a target is the seam B4 closed",
            "Graphics2D", "draw through DrawTarget; bake through BufferedImage.createGraphics()"));

    // --- the checks ------------------------------------------------------------

    /**
     * The whole engine, minus its Java2D backend, is free of Java2D.
     *
     * <p>Reported as one list rather than one failure per file: a change that
     * breaks this usually breaks it in several places at once, and seeing all
     * of them is the difference between one fix and five rounds of it.
     */
    @Test
    void noCodeOutsideTheBackendNamesJava2D() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sealedSources()) {
            String rel = MAIN.relativize(file).toString().replace('\\', '/');
            violations.addAll(violations(rel, Files.readString(file)));
        }
        assertEquals(List.of(), violations,
                "the Java2D seam has been reopened:\n  " + String.join("\n  ", violations));
    }

    /**
     * The negative control, and the answer to "would this test fail if it were
     * broken?" — asked here on every build rather than confirmed once by hand.
     * Each forbidden form is fed to the same checker the scan uses.
     */
    @Test
    void theCheckerRejectsEveryFormItClaimsTo() {
        assertRejected("a scene importing Graphics2D",
                "import java.awt.Graphics2D;\nclass S { void render(DrawTarget t) {} }");
        assertRejected("a fully-qualified Graphics2D that never imports anything",
                "class S { void render(DrawTarget t) { java.awt.Graphics2D g = null; } }");
        assertRejected("unwrapping the frame's target",
                "class S { void render(DrawTarget t) { var g = Java2DTarget.graphicsOf(t); } }");
        assertRejected("casting to the concrete target",
                "class S { void render(DrawTarget t) { ((Java2DTarget) t).graphics(); } }");
    }

    /**
     * Prose about the migration stays legal. Without this the checker would be
     * unusable in the files that most need to explain themselves — and the
     * pressure would be to delete the explanation rather than fix the code.
     */
    @Test
    void commentsAndStringsMayStillSayTheWords() {
        assertAccepted("a javadoc reference",
                "/** Ported off {@link java.awt.Graphics2D} in B2. */\nclass S {}");
        assertAccepted("a trailing comment",
                "class S { void f() {} // this used to call graphicsOf(target)\n}");
        assertAccepted("a message naming what it forbids",
                "class S { String why() { return \"no Graphics2D here\"; } }");
        assertAccepted("a text block",
                "class S { String s = \"\"\"\n            Java2DTarget // not code\n            \"\"\"; }");
    }

    /**
     * An excuse for a file that no longer needs one is stale guidance, and the
     * next reader takes it as licence. Same rule {@code SceneFramesTest}
     * applies to its own exclusions.
     */
    @Test
    void everyBakeExcuseIsStillEarned() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String rel : BAKES.keySet()) {
            Path file = MAIN.resolve(rel);
            if (!Files.exists(file)) {
                stale.add(rel + " — excused, but the file is gone");
            } else if (!strip(Files.readString(file)).contains("Graphics2D")) {
                stale.add(rel + " — excused, but no longer names Graphics2D; drop the excuse");
            }
        }
        assertEquals(List.of(), stale, String.join("\n  ", stale));
    }

    /** And the scan has to be looking at something, or it proves nothing. */
    @Test
    void theScanCoversTheEngine() throws IOException {
        int scanned = sealedSources().size();
        assertTrue(scanned > 100,
                "expected the whole engine minus its backend; found " + scanned
                        + " files — has the source layout moved?");
    }

    // --- implementation --------------------------------------------------------

    private static List<Path> sealedSources() throws IOException {
        try (Stream<Path> files = Files.walk(ENGINE)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(BACKEND))
                    .sorted()
                    .toList();
        }
    }

    /** Every rule this test enforces, as a pure function of one file's text. */
    private static List<String> violations(String rel, String source) {
        String code = strip(source);
        List<String> found = new ArrayList<>();
        boolean bake = BAKES.containsKey(rel);
        for (Map.Entry<String, String> rule : FORBIDDEN.entrySet()) {
            String name = rule.getKey();
            if (bake && name.equals("Graphics2D")) continue;   // checked differently below
            int at = code.indexOf(name);
            if (at >= 0) {
                found.add("%s:%d names `%s` — %s"
                        .formatted(rel, lineOf(code, at), name, rule.getValue()));
            }
        }
        if (bake) found.addAll(bakeViolations(rel, code));
        return found;
    }

    /**
     * What an excused file still has to prove: that it bakes, and that its
     * Graphics2D never crosses its own boundary. The second half is the one
     * that matters — a private Graphics2D over an image the class made is
     * baking, and a {@code public} one is an API that spreads Java2D to
     * whoever calls it.
     */
    private static List<String> bakeViolations(String rel, String code) {
        List<String> found = new ArrayList<>();
        if (!code.contains("createGraphics()")) {
            found.add(rel + " is excused as a bake but never calls createGraphics()");
        }
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (!line.contains("Graphics2D")) continue;
            if (line.startsWith("public ") || line.startsWith("protected ")) {
                found.add("%s:%d exposes Graphics2D from a bake — keep it inside: %s"
                        .formatted(rel, i + 1, line));
            }
        }
        return found;
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }

    /**
     * Comments and string literals blanked out, newlines kept so line numbers
     * still mean something.
     *
     * <p>Hand-written rather than a regex because the cases that matter are
     * exactly the ones a regex gets wrong: a {@code "//"} inside a string, a
     * quote inside a comment, an escaped quote, and the text blocks the shader
     * sources use. Getting any of those wrong makes the scan either blind or
     * noisy, and a scan nobody trusts gets deleted.
     */
    private static String strip(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0, n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*'
                        && i + 1 < n && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i = Math.min(n, i + 3);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void assertRejected(String what, String source) {
        assertTrue(!violations("com/larsons/engine/demo/Fake.java", source).isEmpty(),
                "the checker let " + what + " through:\n" + source);
    }

    private static void assertAccepted(String what, String source) {
        assertEquals(List.of(), violations("com/larsons/engine/demo/Fake.java", source),
                "the checker rejected " + what + ", which is not code:\n" + source);
    }
}
