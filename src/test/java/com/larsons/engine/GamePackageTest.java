package com.larsons.engine;

import com.larsons.engine.config.GamePackage;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game-type export/import system: a whole game type (its profile, every
 * level, and the doors/custom content wiring them together) packages into one
 * {@code .larsonsengine} file and re-materializes on import, and the finalize
 * toggle stamps the packaged copy play-only. All headless — no window or jar.
 */
class GamePackageTest {

    /** Build a game type with two levels, a door, and custom content on disk. */
    private String seedGameType(Path gametypesDir, Path levelsRoot, String name) throws Exception {
        GameProfile p = new GameProfile(name);
        p.maxFps = 144;
        p.mobsEnabled = false;
        new GameTypeStore(gametypesDir.toString()).save(p);

        LevelStore levels = new LevelStore(levelsRoot.toString(), name);
        Level one = Level.empty("Level One", 12, 8, 32);
        one.setTile(2, 3, 1);
        one.settings = p.copy();
        levels.save(one);
        Level two = Level.empty("Level Two", 10, 10, 32);
        levels.save(two);

        // A door (per-game-type meta, travels with the levels).
        new DoorDirectory(levels).put(
                new DoorLink("door_1", "To Level Two", "Level Two", new Color(150, 105, 60)));
        // Custom content file (passed through verbatim on import).
        Files.write(levels.directory().resolve("custom.json"),
                "{\"blocks\":[],\"mobs\":[]}".getBytes());
        return name;
    }

    @Test
    void exportsAWholeGameTypeIntoOneFile(@TempDir Path tmp) throws Exception {
        Path gametypes = tmp.resolve("gametypes");
        Path levelsRoot = tmp.resolve("levels");
        Path out = tmp.resolve("out");
        seedGameType(gametypes, levelsRoot, "My Platformer");

        GameProfile profile = new GameTypeStore(gametypes.toString()).load("My Platformer");
        Path pkg = GamePackage.export(profile,
                new LevelStore(levelsRoot.toString(), "My Platformer"), out, true);

        assertTrue(Files.exists(pkg), "package file written");
        assertTrue(pkg.getFileName().toString().endsWith(GamePackage.EXTENSION),
                "uses the .larsonsengine extension");

        Map<String, Object> root = Json.asObject(Json.parse(Files.readString(pkg)));
        assertEquals(GamePackage.FORMAT_VERSION,
                ((Number) root.get(GamePackage.FORMAT_KEY)).intValue(), "format version stamped");

        Map<String, Object> gt = Json.asObject(root.get("gameType"));
        assertEquals("My Platformer", gt.get("name"));
        assertEquals(Boolean.TRUE, gt.get("finalized"), "finalize toggle stamped into the package");

        Map<String, Object> levels = Json.asObject(root.get("levels"));
        assertEquals(2, levels.size(), "both levels bundled");
        assertTrue(levels.containsKey("level_one") && levels.containsKey("level_two"),
                "levels keyed by file stem");
        assertTrue(root.containsKey("doors"), "doors travel with the game type");
        assertTrue(root.containsKey("custom"), "custom content travels with the game type");
    }

    @Test
    void importReMaterializesProfileLevelsAndMeta(@TempDir Path tmp) throws Exception {
        Path srcGt = tmp.resolve("src/gt");
        Path srcLv = tmp.resolve("src/lv");
        Path out = tmp.resolve("out");
        seedGameType(srcGt, srcLv, "My Platformer");
        GameProfile profile = new GameTypeStore(srcGt.toString()).load("My Platformer");
        Path pkg = GamePackage.export(profile,
                new LevelStore(srcLv.toString(), "My Platformer"), out, true);

        // Fresh destination — simulate a recipient's machine.
        Path dstGt = tmp.resolve("dst/gt");
        Path dstLv = tmp.resolve("dst/lv");
        String name = GamePackage.importFile(pkg, dstGt, dstLv, false);
        assertEquals("My Platformer", name, "import reports the game type name");

        GameProfile imported = new GameTypeStore(dstGt.toString()).load("My Platformer");
        assertTrue(imported.finalized, "imported game type is finalized (play-only)");
        assertEquals(144, imported.maxFps, "profile values survive the round trip");
        assertFalse(imported.mobsEnabled);

        LevelStore levels = new LevelStore(dstLv.toString(), "My Platformer");
        // The sidecars land in the same folder and are checked for below — what
        // this asserts is that they are not reported as levels. It used to expect
        // ["custom", "doors", "level_one", "level_two"], which pinned the bug that
        // put a game type's characters.json into lastLevelPath and left every
        // launch printing "Level is missing a 'tiles' array".
        assertEquals(List.of("level_one", "level_two"), levels.list(),
                "only the levels are levels");
        Level one = levels.load("level_one");
        assertEquals(1, one.tileAt(2, 3), "level tile data survives the round trip");

        assertTrue(Files.exists(levels.directory().resolve(DoorDirectory.FILE_NAME)),
                "doors.json re-materialized");
        assertTrue(Files.exists(levels.directory().resolve("custom.json")),
                "custom.json re-materialized");
    }

    @Test
    void nonFinalizedExportStaysEditable(@TempDir Path tmp) throws Exception {
        Path gt = tmp.resolve("gt");
        Path lv = tmp.resolve("lv");
        Path out = tmp.resolve("out");
        seedGameType(gt, lv, "Sandbox");
        GameProfile profile = new GameTypeStore(gt.toString()).load("Sandbox");

        Path pkg = GamePackage.export(profile, new LevelStore(lv.toString(), "Sandbox"), out, false);
        Map<String, Object> gtMap = Json.asObject(
                Json.asObject(Json.parse(Files.readString(pkg))).get("gameType"));
        assertEquals(Boolean.FALSE, gtMap.get("finalized"), "not finalized when the toggle is off");

        // Exporting must not lock the creator's own in-memory copy.
        assertFalse(profile.finalized, "the local copy stays editable after a finalized export");
        GamePackage.export(profile, new LevelStore(lv.toString(), "Sandbox"), out, true);
        assertFalse(profile.finalized, "still editable — finalize only affects the package");
    }

    @Test
    void skipsAlreadyInstalledButCanOverwrite(@TempDir Path tmp) throws Exception {
        Path srcGt = tmp.resolve("s/gt");
        Path srcLv = tmp.resolve("s/lv");
        Path out = tmp.resolve("out");
        seedGameType(srcGt, srcLv, "Game");
        GameProfile profile = new GameTypeStore(srcGt.toString()).load("Game");
        Path pkg = GamePackage.export(profile, new LevelStore(srcLv.toString(), "Game"), out, false);

        Path gt = tmp.resolve("d/gt");
        Path lv = tmp.resolve("d/lv");
        assertEquals("Game", GamePackage.importFile(pkg, gt, lv, false), "first install");
        assertNull(GamePackage.importFile(pkg, gt, lv, false),
                "second install is skipped — a player's local copy is never clobbered");
        assertEquals("Game", GamePackage.importFile(pkg, gt, lv, true),
                "overwrite forces a re-install");
    }

    @Test
    void importAllScansAFolderOnce(@TempDir Path tmp) throws Exception {
        Path srcGt = tmp.resolve("s/gt");
        Path srcLv = tmp.resolve("s/lv");
        Path dropIn = tmp.resolve("dropin");
        Files.createDirectories(dropIn);
        seedGameType(srcGt, srcLv, "Alpha");
        seedGameType(srcGt, srcLv, "Beta");
        GameTypeStore store = new GameTypeStore(srcGt.toString());
        GamePackage.export(store.load("Alpha"), new LevelStore(srcLv.toString(), "Alpha"), dropIn, false);
        GamePackage.export(store.load("Beta"), new LevelStore(srcLv.toString(), "Beta"), dropIn, false);

        Path gt = tmp.resolve("d/gt");
        Path lv = tmp.resolve("d/lv");
        List<String> first = GamePackage.importAll(dropIn, gt, lv);
        assertEquals(List.of("Alpha", "Beta"), first.stream().sorted().toList(),
                "both packages install on the first scan");
        assertTrue(GamePackage.importAll(dropIn, gt, lv).isEmpty(),
                "a re-scan installs nothing new");
    }

    @Test
    void ignoresFilesThatArentPackages(@TempDir Path tmp) throws Exception {
        Path bogus = tmp.resolve("notreally" + GamePackage.EXTENSION);
        Files.writeString(bogus, "{\"hello\":\"world\"}");
        assertNull(GamePackage.importFile(bogus, tmp.resolve("gt"), tmp.resolve("lv"), false),
                "a JSON file that isn't a package is rejected, not imported");
    }

    @Test
    void packageFileNameSanitizesTheGameTypeName() {
        assertEquals("my_platformer" + GamePackage.EXTENSION,
                GamePackage.packageFileName("My Platformer!"));
    }

    // --- forward compatibility -----------------------------------------------------
    // These lock in the behaviour that keeps a package exported today loadable in
    // future builds: versioned files, readers that default what's missing and
    // ignore what's unknown, and best-effort handling of newer schema versions.

    private Path writePackage(Path dir, String json) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve("pkg" + GamePackage.EXTENSION);
        Files.writeString(p, json);
        return p;
    }

    @Test
    void minimalOlderShapedPackageImportsWithDefaults(@TempDir Path tmp) throws Exception {
        // A package carrying only the keys a much older/leaner exporter might
        // have written — everything else must fall back to sane defaults.
        Path pkg = writePackage(tmp.resolve("in"), """
                {
                  "larsonsengine": 1,
                  "name": "Legacy",
                  "gameType": { "name": "Legacy" },
                  "levels": { "intro": { "tileset": "registry", "tiles": [[0,1],[0,0]] } }
                }
                """);
        String name = GamePackage.importFile(pkg, tmp.resolve("gt"), tmp.resolve("lv"), false);
        assertEquals("Legacy", name);

        GameProfile p = new GameTypeStore(tmp.resolve("gt").toString()).load("Legacy");
        assertEquals(120, p.maxFps, "missing fields take GameProfile defaults");
        assertTrue(p.mobsEnabled);
        assertFalse(p.finalized);
        Level intro = new LevelStore(tmp.resolve("lv").toString(), "Legacy").load("intro");
        assertEquals(1, intro.tileAt(1, 0), "the level still loads");
    }

    @Test
    void unknownFutureFieldsAreIgnoredNotFatal(@TempDir Path tmp) throws Exception {
        // Fields a future version might add — at the top level, inside the game
        // type, and inside a level — must be ignored, not crash the import.
        Path pkg = writePackage(tmp.resolve("in"), """
                {
                  "larsonsengine": 1,
                  "name": "Future",
                  "somethingAddedLater": { "x": 1 },
                  "gameType": { "name": "Future", "maxFps": 90, "warpDriveEnabled": true },
                  "levels": { "room": { "tileset": "registry", "tiles": [[2]], "quantumFoam": 42 } }
                }
                """);
        String name = GamePackage.importFile(pkg, tmp.resolve("gt"), tmp.resolve("lv"), false);
        assertEquals("Future", name);

        GameProfile p = new GameTypeStore(tmp.resolve("gt").toString()).load("Future");
        assertEquals(90, p.maxFps, "known fields still read");
        Level room = new LevelStore(tmp.resolve("lv").toString(), "Future").load("room");
        assertEquals(2, room.tileAt(0, 0), "the level with unknown extras still loads");
    }

    @Test
    void newerSchemaVersionImportsBestEffort(@TempDir Path tmp) throws Exception {
        // A package from a build newer than this one: we can't know its shape,
        // but tolerant readers let it import rather than being refused outright.
        Path pkg = writePackage(tmp.resolve("in"), """
                {
                  "larsonsengine": 999,
                  "name": "FromTheFuture",
                  "gameType": { "name": "FromTheFuture", "finalized": true, "hyperMode": true },
                  "levels": { "a": { "tileset": "registry", "tiles": [[0]] } }
                }
                """);
        String name = GamePackage.importFile(pkg, tmp.resolve("gt"), tmp.resolve("lv"), false);
        assertEquals("FromTheFuture", name, "a newer-versioned package still imports");
        GameProfile p = new GameTypeStore(tmp.resolve("gt").toString()).load("FromTheFuture");
        assertTrue(p.finalized, "the fields it shares with us are still honoured");
    }

    @Test
    void currentExportsRecordTheFormatVersionForFutureMigration(@TempDir Path tmp) throws Exception {
        // The exported file must carry a version, so a future build knows which
        // migrations to run — the linchpin of the forward-compatibility promise.
        Path gt = tmp.resolve("gt"), lv = tmp.resolve("lv"), out = tmp.resolve("out");
        seedGameType(gt, lv, "Versioned");
        Path pkg = GamePackage.export(new GameTypeStore(gt.toString()).load("Versioned"),
                new LevelStore(lv.toString(), "Versioned"), out, false);
        Map<String, Object> root = Json.asObject(Json.parse(Files.readString(pkg)));
        assertTrue(root.get(GamePackage.FORMAT_KEY) instanceof Number,
                "every export is stamped with its schema version");
        assertEquals(GamePackage.FORMAT_VERSION,
                ((Number) root.get(GamePackage.FORMAT_KEY)).intValue());
    }
}
