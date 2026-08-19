package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeRename;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renaming a game type takes its levels with it.
 *
 * <p>A game type is a folder of levels filed under its name, so a rename that
 * only rewrites the profile is a rename that loses the levels: they are still
 * on disk, under a name nothing points at any more. That used to be the main
 * menu's job, next to a game-type editor whose name field did exactly the wrong
 * half of it — which is why the menu entry is gone and the editor calls
 * {@link GameTypeRename}.
 */
class GameTypeRenameTest {

    private static GameContext context(Path dir, String name) {
        GameContext ctx = new GameContext(null,
                new GameTypeStore(dir.resolve("gametypes").toString()));
        GameProfile p = new GameProfile(name);
        ctx.setProfile(p);
        ctx.save();
        return ctx;
    }

    private static LevelStore levels(Path dir, String gameType) {
        return new LevelStore(dir.resolve("levels").toString(), gameType);
    }

    @Test
    void renamingMovesTheLevelsAndTheProfile(@TempDir Path dir) {
        GameContext ctx = context(dir, "Old Name");
        LevelStore old = levels(dir, "Old Name");
        Level level = LevelFormat.THREE_D.starterLevel("Cave", 16, 16, 32);
        old.save(level);
        ctx.profile().lastLevelPath = old.fileFor("Cave").toString();

        // The rename itself, against the same folders the game uses.
        GameTypeRename.Result result = renameUnder(dir, ctx, "New Name");

        assertTrue(result.renamed(), result.message());
        assertEquals("New Name", ctx.profile().name);
        assertTrue(levels(dir, "New Name").exists("Cave"), "the level moved with the name");
        assertFalse(Files.exists(old.directory()), "and nothing was left behind");
        assertEquals(levels(dir, "New Name").fileFor("Cave").toString(),
                ctx.profile().lastLevelPath, "the last-played pointer followed it");
        assertTrue(Files.exists(ctx.store().fileFor("New Name")), "the profile is under the new name");
        assertFalse(Files.exists(ctx.store().fileFor("Old Name")), "and not under the old one");
    }

    @Test
    void renamingOntoAnExistingGameTypeIsRefused(@TempDir Path dir) {
        GameContext ctx = context(dir, "First");
        levels(dir, "First").save(LevelFormat.THREE_D.starterLevel("A", 16, 16, 32));
        levels(dir, "Second").save(LevelFormat.THREE_D.starterLevel("B", 16, 16, 32));

        GameTypeRename.Result result = renameUnder(dir, ctx, "Second");

        assertFalse(result.renamed(), "two game types may not merge into one folder");
        assertEquals("First", ctx.profile().name, "and the name is left alone");
        assertTrue(levels(dir, "First").exists("A"), "with its levels where they were");
        assertTrue(levels(dir, "Second").exists("B"), "and the other type's untouched");
    }

    @Test
    void anUnchangedNameDoesNothingAndSaysNothing(@TempDir Path dir) {
        GameContext ctx = context(dir, "Same");
        GameTypeRename.Result result = renameUnder(dir, ctx, "Same");
        assertFalse(result.renamed());
        assertEquals("", result.message(), "a name that did not change is not news");
    }

    /**
     * {@link GameTypeRename#apply} against the test's own folders, so a run
     * never moves the repository's levels or saves around.
     */
    private static GameTypeRename.Result renameUnder(Path dir, GameContext ctx, String to) {
        return GameTypeRename.apply(ctx, to, dir.resolve("levels").toString(),
                dir.resolve("saves").toString());
    }
}
