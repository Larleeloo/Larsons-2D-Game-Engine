package com.larsons.engine;

import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop-in texture pack: a folder of correctly-named sprite sheets beside
 * the jar reskins the game with no menu visit — sorted into palette-category
 * subfolders, played at one universal frame size/length/rate that any single
 * texture can override, and always deferring to the built-in art when the
 * pack has nothing for a key.
 */
@Timeout(60)
class TexturePackTest {

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        TexturePack.useDir("");
        AssetLoader.clearCache();
    }

    /** A 3-frame 32x32 sheet (red, green, blue) at the pack's default spec. */
    private static Path sheet(Path file) throws IOException {
        BufferedImage img = new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
        for (int i = 0; i < colors.length; i++) {
            g.setColor(colors[i]);
            g.fillRect(i * 32, 0, 32, 32);
        }
        g.dispose();
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    @Test
    void scaffoldWritesTheFoldersConfigAndKeyList(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));

        for (String folder : TexturePack.folders()) {
            assertTrue(Files.isDirectory(root.resolve(folder)),
                    "a folder per palette category: " + folder);
        }
        assertTrue(Files.exists(root.resolve(TexturePack.README_FILE)));

        String keys = Files.readString(root.resolve(TexturePack.KEYS_FILE));
        assertTrue(keys.contains("blocks/dirt.png"), "every block is named");
        assertTrue(keys.contains("block/dirt"), "…alongside its texture key");
        assertTrue(keys.contains("mobs/"), "mobs get their own folder");
        assertTrue(keys.contains("player/idle.png"), "so does the player, per action state");

        // The universal spec: 32x32 frames, 3 frames, 3 fps.
        String config = Files.readString(root.resolve(TexturePack.CONFIG_FILE));
        assertTrue(config.contains("\"frameWidth\": 32"), config);
        assertTrue(config.contains("\"frameCount\": 3"), config);
        assertTrue(config.contains("\"fps\": 3"), config);

        // Re-scaffolding refreshes the docs but never clobbers frame settings.
        TexturePack.useDir(root.toString());
        TexturePack.setDefaults(64, 64, 4, 8);
        TexturePack.scaffold(root);
        TexturePack.reload();
        assertEquals(64, TexturePack.defaults().width(), "the creator's spec survives");
        assertEquals(4, TexturePack.defaults().count());
    }

    @Test
    void sheetsAreFoundByNameAndPlayAtTheUniversalSpec(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        sheet(root.resolve("blocks/dirt.png"));
        TexturePack.useDir(root.toString());

        SkinDef def = TexturePack.lookup("block/dirt");
        assertNotNull(def, "blocks/dirt.png backs block/dirt");
        assertEquals(TexturePack.DEFAULT_FRAME_SIZE, def.frameWidth);
        assertEquals(TexturePack.DEFAULT_FRAME_COUNT, def.frameCount);
        assertEquals(TexturePack.DEFAULT_FPS, def.fps);
        assertTrue(def.usePack);

        // Nothing installed in Skins: the pack alone reskins the block, and
        // the 3 fps / 3 frame spec animates it.
        assertEquals(Color.RED.getRGB(), Skins.frame("block/dirt", 0).getRGB(16, 16));
        assertEquals(Color.GREEN.getRGB(), Skins.frame("block/dirt", 0.4).getRGB(16, 16));
        assertEquals(Color.BLUE.getRGB(), Skins.frame("block/dirt", 0.7).getRGB(16, 16));

        // A key the pack has no file for keeps its built-in art.
        assertNull(TexturePack.lookup("block/stone"));
        assertNull(Skins.frame("block/stone", 0));
    }

    @Test
    void fileNamesFollowThePaletteCategoryAndObject() {
        assertEquals("blocks/dirt.png", TexturePack.fileNameFor("block/dirt"));
        assertEquals("mobs/slime_walk.png", TexturePack.fileNameFor("mob/slime/walk"));
        assertEquals("items/iron_sword.png", TexturePack.fileNameFor("item/iron_sword"));
        assertEquals("decor/oak_tree.png", TexturePack.fileNameFor("decor/oak_tree"));
        assertEquals("block_decor/moss.png", TexturePack.fileNameFor("surface/moss"));
        assertEquals("player/idle.png",
                TexturePack.fileNameFor(PlayerSprites.stateKey("idle")));
        assertEquals("units/squire.png", TexturePack.fileNameFor("unit/squire"));

        // A liquid or a light may be filed under its own palette folder.
        assertTrue(TextureKeys.paths("block/water").contains("liquids/water"));
        assertTrue(TextureKeys.paths("block/torch").contains("lights/torch"));
    }

    @Test
    void oneMobSheetCoversEveryStateUntilAStateHasItsOwn(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        sheet(root.resolve("mobs/slime.png"));
        TexturePack.useDir(root.toString());

        for (String state : TextureKeys.MOB_STATES) {
            assertNotNull(TexturePack.lookup("mob/slime/" + state),
                    "mobs/slime.png reskins " + state);
        }
        // A per-state sheet wins for that state alone.
        Path walk = sheet(root.resolve("mobs/slime_walk.png"));
        TexturePack.reload();
        assertEquals(walk.toString().replace('\\', '/'),
                TexturePack.lookup("mob/slime/walk").sheet);
        assertEquals(root.resolve("mobs/slime.png").toString().replace('\\', '/'),
                TexturePack.lookup("mob/slime/idle").sheet);
    }

    @Test
    void oneTextureCanOverrideTheUniversalSpec(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        sheet(root.resolve("blocks/dirt.png"));
        TexturePack.useDir(root.toString());

        assertFalse(TexturePack.hasOverride("block/dirt"));
        TexturePack.setOverride("block/dirt", 32, 32, 1, 0);
        assertTrue(TexturePack.hasOverride("block/dirt"), "this one texture is a still image");
        assertEquals(1, TexturePack.framesFor("block/dirt").count());
        assertEquals(TexturePack.DEFAULT_FRAME_COUNT, TexturePack.framesFor("block/stone").count(),
                "everything else keeps the universal spec");

        // It lives in the pack, so it travels with the folder.
        TexturePack.reload();
        assertEquals(1, TexturePack.framesFor("block/dirt").count());
        assertTrue(Files.readString(root.resolve(TexturePack.CONFIG_FILE))
                .contains("block/dirt"));

        // Settings back at the universal spec are not an exception at all.
        TexturePack.setOverride("block/dirt", TexturePack.DEFAULT_FRAME_SIZE,
                TexturePack.DEFAULT_FRAME_SIZE, TexturePack.DEFAULT_FRAME_COUNT,
                TexturePack.DEFAULT_FPS);
        assertFalse(TexturePack.hasOverride("block/dirt"));
    }

    @Test
    void eachObjectCanOptOutOfThePackOrPointSomewhereElse(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        sheet(root.resolve("blocks/dirt.png"));
        Path elsewhere = sheet(tmp.resolve("elsewhere/mine.png"));
        TexturePack.useDir(root.toString());

        // Default: the pack supplies it.
        assertEquals(root.resolve("blocks/dirt.png").toString().replace('\\', '/'),
                Skins.effective("block/dirt").sheet);

        // Pack off + a path: that sheet, wherever it lives.
        Skins.put(new SkinDef("block/dirt", elsewhere.toString(), 32, 32, 3, 3, false));
        assertEquals(elsewhere.toString(), Skins.effective("block/dirt").sheet);

        // Pack on with a path: the pack wins, the path is the fallback.
        Skins.put(new SkinDef("block/dirt", elsewhere.toString(), 32, 32, 3, 3, true));
        assertEquals(root.resolve("blocks/dirt.png").toString().replace('\\', '/'),
                Skins.effective("block/dirt").sheet);
        Skins.put(new SkinDef("block/stone", elsewhere.toString(), 32, 32, 3, 3, true));
        assertEquals(elsewhere.toString(), Skins.effective("block/stone").sheet,
                "no pack file for this key -> the creator's own sheet");

        // Pack off with no path: back to the built-in art.
        Skins.put(new SkinDef("block/dirt", "", 32, 32, 3, 3, false));
        assertNull(Skins.effective("block/dirt"));
        assertNull(Skins.frame("block/dirt", 0));
    }

    @Test
    void savedSkinsFromBeforeTexturePacksStayExplicit() {
        SkinDef legacy = SkinDef.fromMap(new SkinDef("item/sword", "s.png", 32, 32, 1, 0)
                .toMap());
        assertFalse(legacy.usePack, "an assignment made by hand keeps rendering as it did");
        assertTrue(SkinDef.fromMap(
                new SkinDef("item/sword", "s.png", 32, 32, 1, 0, true).toMap()).usePack,
                "the pack switch round-trips through skins.json");
    }

    @Test
    void paletteIconsShowTheTextureThatActuallyRenders(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        sheet(root.resolve("blocks/dirt.png"));
        Path elsewhere = sheet(tmp.resolve("elsewhere/mine.png"));
        TexturePack.useDir(root.toString());

        BufferedImage builtIn = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);

        // Nothing assigned and nothing in the pack: the built-in art stands.
        assertSame(builtIn, Skins.icon("block/stone", builtIn, 40),
                "an unskinned object keeps its default icon");

        // The pack supplies it: the icon is the sheet's first frame, sized to
        // the palette swatch.
        BufferedImage packed = Skins.icon("block/dirt", builtIn, 40);
        assertNotSame(builtIn, packed);
        assertEquals(40, packed.getWidth());
        assertEquals(40, packed.getHeight());
        assertEquals(Color.RED.getRGB(), packed.getRGB(20, 20), "frame 0 of the pack sheet");

        // Change the texture and the icon changes with it.
        Skins.put(new SkinDef("block/stone", elsewhere.toString(), 32, 32, 3, 3, false));
        assertNotSame(builtIn, Skins.icon("block/stone", builtIn, 40),
                "an assigned sheet replaces the default icon too");
    }

    @Test
    void theKeyListNamesEveryObjectTheGameCanReskin() {
        List<TextureKeys.Entry> all = TextureKeys.all();
        assertFalse(all.isEmpty());
        for (TextureKeys.Entry e : all) {
            assertTrue(TextureKeys.paths(e.key()).contains(e.folder() + "/" + e.file()),
                    e.key() + " resolves to the file the key list advertises");
            assertFalse(e.name().isBlank(), e.key() + " has a readable name");
        }
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("block/")));
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("mob/")));
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("item/")));
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("player/")));
        // The plan views look at faces a side-scroller never shows, so those
        // faces are listed as files of their own.
        assertTrue(all.stream().anyMatch(e -> e.folder().equals(TextureKeys.BLOCKS_TOP)));
        assertTrue(all.stream().anyMatch(e -> e.folder().equals(TextureKeys.BLOCKS_SIDE)));
    }

    /**
     * Blocks have a separate texture pool for the plan-view perspectives: the
     * top face a top-down or isometric level looks down at, and the side face a
     * stacked block turns toward the camera. Neither is required — both fall
     * back to the block's one side-scroll sheet — so a pack can supply as much
     * or as little of the pool as it likes.
     */
    @Test
    void blocksHaveTheirOwnTopAndSideFacesInThePlanViews(@TempDir Path tmp) throws IOException {
        Block stone = BlockRegistry.standard().get("stone");
        assertTrue(stone.topTexture() && stone.sideTexture(),
                "a built-in block accepts both faces");

        // Each face leads with its own pool and then falls through to blocks/.
        assertEquals(TextureKeys.BLOCKS_TOP + "/stone",
                TextureKeys.paths(stone.topTextureKey()).get(0));
        assertEquals(TextureKeys.BLOCKS_SIDE + "/stone",
                TextureKeys.paths(stone.sideTextureKey()).get(0));
        assertTrue(TextureKeys.paths(stone.topTextureKey()).contains("blocks/stone"));
        assertTrue(TextureKeys.paths(stone.sideTextureKey()).contains("blocks/stone"));

        // With only the flat sheet in the pack, every face resolves to it.
        TexturePack.scaffold(tmp);
        TexturePack.useDir(tmp.toString());
        sheet(tmp.resolve("blocks/stone.png"));
        TexturePack.reload();
        assertNotNull(Skins.frame(stone.textureKey(), 0));
        String flatSheet = Skins.effective(stone.textureKey()).sheet;
        assertEquals(flatSheet, Skins.effective(stone.topTextureKey()).sheet,
                "with no top of its own, the top falls back to the flat sheet");
        assertEquals(flatSheet, Skins.effective(stone.sideTextureKey()).sheet,
                "and so does the side");

        // Drop a top face in and only the top changes.
        sheet(tmp.resolve(TextureKeys.BLOCKS_TOP + "/stone.png"));
        TexturePack.reload();
        assertTrue(Skins.effective(stone.topTextureKey()).sheet
                        .replace('\\', '/').contains(TextureKeys.BLOCKS_TOP + "/stone"),
                "the top face now comes from the plan-view pool");
        assertEquals(flatSheet, Skins.effective(stone.sideTextureKey()).sheet,
                "and the side still falls back to the flat sheet");
    }

    /** A block may declare it has neither plan-view face, and still draw. */
    @Test
    void aBlockCanDeclareItHasNeitherPlanViewFace() {
        Block plain = new Block(9001, "plain", "Plain", Color.GRAY, true, 0, null,
                null, false, 0, 1, null, false, false, false);
        assertEquals(plain.textureKey(), plain.topTextureKey());
        assertEquals(plain.textureKey(), plain.sideTextureKey());

        Block topOnly = plain.withFaceTextures(true, false);
        assertEquals("block/plain/top", topOnly.topTextureKey());
        assertEquals("block/plain", topOnly.sideTextureKey());
    }
}
