package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch menu lists games by name.
 *
 * <p>It used to list each one as {@code "<name>   (<perspective>)"}, which was
 * an enum constant on a player-facing screen — and, worse, a <em>level's</em>
 * property standing in for a folder of levels that can hold several kinds. A
 * game type holding a side-scrolling dungeon and a 3D overworld could only
 * display one of the two, and whichever it displayed was wrong about the other.
 */
class StartupMenuTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static StartupScene startupWith(Path dir, GameProfile... profiles) {
        GameTypeStore store = new GameTypeStore(dir.toString());
        for (GameProfile p : profiles) store.save(p);
        StartupScene scene = new StartupScene(new GameContext(null, store));
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register("startup", scene);
        scenes.setScene("startup");
        return scene;
    }

    private static List<String> labels(StartupScene scene) {
        return scene.menu().items().stream().map(MenuItem::text).toList();
    }

    @Test
    void gamesAreListedByNameAlone(@TempDir Path dir) {
        GameProfile side = new GameProfile("Frostmarch");
        side.perspective = Perspective.SIDE_SCROLL;
        GameProfile solid = new GameProfile("Sunken Halls");
        solid.perspective = Perspective.THREE_D;

        List<String> rows = labels(startupWith(dir, side, solid));

        assertTrue(rows.contains("Frostmarch"), rows.toString());
        assertTrue(rows.contains("Sunken Halls"), rows.toString());
        for (String row : rows) {
            assertFalse(row.contains("SIDE_SCROLL") || row.contains("THREE_D")
                            || row.contains("TOP_DOWN") || row.contains("ISOMETRIC"),
                    "the launch menu is naming a level type: " + row);
        }
    }

    @Test
    void theCreateRowSaysCreateNewGame(@TempDir Path dir) {
        List<String> rows = labels(startupWith(dir));
        assertTrue(rows.contains("+ Create New Game"), rows.toString());
        assertFalse(rows.contains("+ Create New Game Type"), rows.toString());
    }

    /**
     * Nothing is bundled any more: the two sample game types shipped in
     * {@code resources/gametypes/} are gone, so a first run offers to create one
     * rather than opening someone else's.
     */
    @Test
    void aFreshInstallOffersNoPrebuiltGames(@TempDir Path dir) {
        StartupScene scene = startupWith(dir);
        assertEquals("+ Create New Game", labels(scene).get(0),
                "with no game types saved, creating one is the first thing on offer");
        assertTrue(new GameTypeStore().listProfiles().isEmpty()
                        || !bundles(new GameTypeStore().listProfiles()),
                "the engine must not ship Platformer / Top Down Adventure any more");
    }

    private static boolean bundles(List<GameProfile> profiles) {
        return profiles.stream().anyMatch(p ->
                "Platformer".equals(p.name) || "Top Down Adventure".equals(p.name));
    }
}
