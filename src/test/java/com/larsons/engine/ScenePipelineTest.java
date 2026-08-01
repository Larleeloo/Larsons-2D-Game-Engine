package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks that a real scene's drawing actually reaches the frame's
 * {@link com.larsons.engine.graphics.draw.DrawTarget}.
 *
 * <p><b>Why this exists.</b> A profile taken on an M1 Air came back with no
 * draw-call section at all, which can mean two very different things: that the
 * build predates the counting, or that the scene is drawing round the target
 * instead of through it. Those need telling apart without a laptop in the room,
 * so the property is pinned here: a scene that paints terrain must leave
 * operations on the frame's target, and if it ever stops doing so this fails
 * rather than quietly reporting zero.
 */
class ScenePipelineTest {

    /** Run a scene for a few frames against one frame-level target, as Engine does. */
    private static DrawStats renderFrames(String name, Object scene, int frames) {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Pipeline Test"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register(name, (com.larsons.engine.scene.Scene) scene);
        scenes.setScene(name);

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(800, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        Java2DTarget target = new Java2DTarget(g, 800, 480);
        try {
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                scenes.update(1.0 / 120.0, input);
                scenes.render(target, 0f);
            }
        } finally {
            g.dispose();
        }
        return target.stats();
    }

    @Test
    void aTerrainSceneLeavesOperationsOnTheFrameTarget() {
        // CreativeScene paints a level through the ported terrain painter, so
        // its work has to show up in the frame's own draw count. If this is
        // zero, a profile taken on any machine will report no draw calls and
        // the GPU decision loses the number it turns on.
        DrawStats stats = renderFrames("creative", new CreativeScene(
                new GameContext(null, new GameTypeStore())), 20);

        assertTrue(stats.operations() > 0,
                "the scene drew nothing through the frame target — draw counting is "
                        + "disconnected, and a profile would report none");
    }

    @Test
    void theFrameTargetSeesImagesOnceTerrainIsCached() {
        // The baked floor arrives as blits rather than per-cell fills, so a
        // cached scene should be issuing image operations.
        DrawStats stats = renderFrames("creative", new CreativeScene(
                new GameContext(null, new GameTypeStore())), 30);

        assertTrue(stats.operations() > 0, "nothing was drawn at all");
        assertTrue(stats.batches() > 0, "operations were counted but never batched");
    }
}
