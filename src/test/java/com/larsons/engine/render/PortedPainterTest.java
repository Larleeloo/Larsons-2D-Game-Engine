package com.larsons.engine.render;

import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.ui.ContainerPanel;
import com.larsons.engine.ui.Menu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The widgets B2 ported, asserted on as command sequences rather than pixels.
 *
 * <p><b>Why both this and the golden frames.</b> They fail on different things.
 * A golden catches a changed picture but is blind to a reordering of two draws
 * that happen not to overlap in the reference scene — which then breaks in a
 * real level where they do. This catches the order and the state balance, and
 * is blind to whether the result looks right. Neither is redundant, and the
 * plan (§7) says as much.
 *
 * <p>The properties pinned here are the ones a port actually breaks: scoped
 * state that is pushed and never popped, and a draw that moved to the wrong
 * side of the thing it is supposed to sit under or over.
 */
class PortedPainterTest {

    /** Net depth of a push/pop pair across a whole recording; must end at zero. */
    private static int balance(RecordingTarget target, String push, String pop) {
        int depth = 0, worst = 0;
        for (String op : target.ops()) {
            if (op.equals(push)) depth++;
            else if (op.equals(pop)) depth--;
            worst = Math.min(worst, depth);
        }
        assertTrue(worst >= 0, "'" + pop + "' ran ahead of '" + push + "'");
        return depth;
    }

    @Test
    void anOpeningContainerPanelScopesItsGrowthAndClosesIt() {
        // The panel scales and fades while it opens. Both are scoped state, and
        // a push left open leaks the transform into everything drawn after the
        // panel — which is the rest of the HUD.
        ItemRegistry items = ItemRegistry.standard();
        Level level = Level.empty("t", 8, 8, 32);
        level.setTile(2, 2, level.blocks.get("chest").id());
        level.openContainer(2, 2).add(new ItemStack("wood", 3));

        ContainerPanel panel = new ContainerPanel(level, 2, 2, "Chest", items);
        panel.tick(0.05);   // mid-open: scaled and faded, the interesting case

        RecordingTarget target = new RecordingTarget(960, 420);
        panel.render(target, 960, 420, 0.0);

        List<String> ops = target.ops();
        assertEquals("pushTransform", ops.get(0), "the growth is scoped first");
        assertEquals("pushAlpha", ops.get(1), "and the fade inside it");
        assertEquals("popTransform", ops.get(ops.size() - 1), "…and unwound last");
        assertEquals("popAlpha", ops.get(ops.size() - 2));
        assertEquals(0, balance(target, "pushTransform", "popTransform"));
        assertEquals(0, balance(target, "pushAlpha", "popAlpha"));
    }

    @Test
    void aFullyOpenContainerPanelScopesNothingAtAll() {
        // The other half of the same property: once open there is no animation
        // to scope, so a panel that still pushed state would be paying for a
        // batch break on every frame it is on screen for no reason.
        ItemRegistry items = ItemRegistry.standard();
        Level level = Level.empty("t", 8, 8, 32);
        level.setTile(2, 2, level.blocks.get("chest").id());

        ContainerPanel panel = new ContainerPanel(level, 2, 2, "Chest", items);
        panel.tick(0.5);    // fully open

        RecordingTarget target = new RecordingTarget(960, 420);
        panel.render(target, 960, 420, 0.0);

        assertEquals(0, target.count("pushTransform"));
        assertEquals(0, target.count("pushAlpha"));
        assertEquals(0, target.stats().stateChanges(),
                "a settled panel should issue no state changes at all");
    }

    @Test
    void aMenuDrawsItsScrollBarAfterTheRowsItScrolls() {
        // The bar sits over the rows. Drawn before them it is painted on and
        // then covered — invisible in a golden whose rows happen not to reach
        // the right margin, and missing in a real menu whose rows do.
        Menu menu = new Menu("Long");
        for (int i = 0; i < 40; i++) menu.add("Item " + i, () -> {});

        RecordingTarget target = new RecordingTarget(640, 400);
        menu.render(target, 640, 400);

        List<String> ops = target.ops();
        int lastRow = ops.lastIndexOf("drawText");
        int firstBarPiece = ops.indexOf("fillRoundRect");
        assertTrue(firstBarPiece > lastRow,
                "the scroll bar (" + firstBarPiece + ") must come after the last row ("
                        + lastRow + ")");
        assertEquals(2, target.count("fillRoundRect"), "one track, one thumb");
    }

    @Test
    void aShortMenuDrawsNoScrollBar() {
        Menu menu = new Menu("Short").add("One", () -> {}).add("Two", () -> {});

        RecordingTarget target = new RecordingTarget(640, 400);
        menu.render(target, 640, 400);

        assertEquals(0, target.count("fillRoundRect"),
                "a menu that fits must not draw a bar over itself");
    }

    @Test
    void theEscortPathIsDashedRatherThanEmulatedWithGaps() {
        // B1 added drawDashedLine for exactly this call site. If the port had
        // emulated the dashes by drawing many short solid segments, the picture
        // would be close enough for a golden and the command stream would be
        // thirty operations where two belong.
        Level level = Level.empty("t", 12, 9, 32);
        for (int i = 0; i < 3; i++) {
            level.entities.add(new Level.EntitySpawn(
                    com.larsons.engine.minigame.MiniGame.KIND_PATH,
                    "path_" + i, (2 + i * 3) * 32, 5 * 32));
        }
        Camera camera = new Camera(Perspective.SIDE_SCROLL, 480, 320);
        camera.tileSize = 32;
        camera.centerOn(6 * 32, 5 * 32);

        MiniGameView view = new MiniGameView();
        view.mode = MiniGameConfig.Mode.ESCORT;

        RecordingTarget target = new RecordingTarget(480, 320);
        com.larsons.engine.demo.MiniGameHudAccess.drawWorld(target, camera, level, view, 0.0);

        assertEquals(2, target.count("drawDashedLine"),
                "three waypoints are two dashed segments");
        assertEquals(0, target.count("drawLine"),
                "no segment of the path should be a plain line");
    }

    @Test
    void aPanelIssuesNoGradientsAndSoDoesNotBreakItsOwnBatches() {
        // Gradients break the batch on both sides (B1). None of the ported
        // widgets uses one, and this is what says so — if a later change
        // reaches for a gradient where a flat fill would do, the cost shows up
        // here rather than in a profile six months later.
        ItemRegistry items = ItemRegistry.standard();
        Level level = Level.empty("t", 8, 8, 32);
        level.setTile(2, 2, level.blocks.get("chest").id());
        ContainerPanel panel = new ContainerPanel(level, 2, 2, "Chest", items);
        panel.tick(0.5);

        RecordingTarget target = new RecordingTarget(960, 420);
        panel.render(target, 960, 420, 0.0);

        DrawStats stats = target.stats();
        assertEquals(0, stats.gradients());
        assertTrue(stats.mergeRatio() > 1.0,
                "a panel of flat shapes should merge at all, got " + stats.mergeRatio());
    }
}
