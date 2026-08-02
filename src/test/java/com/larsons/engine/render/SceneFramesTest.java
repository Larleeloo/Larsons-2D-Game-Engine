package com.larsons.engine.render;

import com.larsons.engine.render.GoldenFrames.Frame;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties that keep {@link SceneFrames} honest as the engine grows.
 *
 * <p>A golden catalogue is only worth what it covers, and coverage rots
 * silently: a nineteenth scene lands, nobody adds a frame for it, and the
 * comparison stays green for ever while an entire screen goes unchecked. So the
 * catalogue's completeness is itself asserted here — every scene in the demo
 * package is either goldened or explicitly excused with a reason.
 */
class SceneFramesTest {

    private static final Path DEMO = Path.of("src/main/java/com/larsons/engine/demo");

    /** Scene class names on disk, which is the only list that cannot go stale. */
    private static Set<String> everySceneOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(DEMO)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Scene.java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** {@code scene-auto-battler-guide} names {@code AutoBattlerGuideScene}. */
    private static String classOf(String frameName) {
        StringBuilder sb = new StringBuilder();
        for (String word : frameName.substring("scene-".length()).split("-")) {
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.append("Scene").toString();
    }

    /**
     * Every scene is either in the catalogue or named in
     * {@link SceneFrames#NOT_GOLDENABLE}. Adding a scene and forgetting the
     * decision fails here rather than passing quietly.
     */
    @Test
    void everySceneIsEitherGoldenedOrExcused() throws IOException {
        Set<String> covered = new TreeSet<>(SceneFrames.NOT_GOLDENABLE);
        for (Frame frame : SceneFrames.all()) covered.add(classOf(frame.name()));

        Set<String> missing = new TreeSet<>(everySceneOnDisk());
        missing.removeAll(covered);

        assertEquals(Set.of(), missing,
                "these scenes have no golden frame and no stated reason not to have one — "
                        + "add them to SceneFrames.all(), or to NOT_GOLDENABLE with why: "
                        + missing);
    }

    /** And the reverse: an excuse for a scene that no longer exists is stale guidance. */
    @Test
    void nothingIsExcusedThatDoesNotExist() throws IOException {
        Set<String> onDisk = everySceneOnDisk();
        List<String> ghosts = SceneFrames.NOT_GOLDENABLE.stream()
                .filter(name -> !onDisk.contains(name))
                .toList();

        assertEquals(List.of(), ghosts,
                "NOT_GOLDENABLE names scenes that are gone: " + ghosts);
    }

    /**
     * A scene frame that draws almost nothing is a golden that proves almost
     * nothing, and the failure mode is quiet: a scene whose menu never got
     * built renders an empty backdrop and then matches its own empty reference
     * for ever. Every frame has to put real ink on the surface.
     *
     * <p>Measured in pixels rather than draw calls on purpose — a scene could
     * issue a hundred operations entirely off-screen, or in the background
     * colour, and this is the question the golden actually depends on.
     */
    @Test
    void everySceneFrameActuallyDrawsSomething() {
        StringBuilder thin = new StringBuilder();
        for (Frame frame : SceneFrames.all()) {
            BufferedImage image = GoldenFrames.render(frame);
            int background = image.getRGB(0, 0);
            long inked = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (image.getRGB(x, y) != background) inked++;
                }
            }
            double fraction = inked / (double) (image.getWidth() * image.getHeight());
            if (fraction < 0.01) {
                thin.append("\n  ").append(frame.name()).append(" covers only ")
                        .append(String.format("%.3f%%", fraction * 100))
                        .append(" of its surface");
            }
        }
        assertTrue(thin.isEmpty(),
                "these scene frames are too empty to be evidence of anything:" + thin);
    }
}
