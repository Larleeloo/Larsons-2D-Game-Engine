package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderContext;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the systems merged in from the Side-Scroller engine:
 * the data-driven block/mob/item registries, registry-mode levels (solidity,
 * mutation, JSON round-trips), inventory stacking, the mob AI state machine,
 * world simulation (mining drops, pickups, combat), the lighting shader
 * pass, and the creative editor scene.
 */
class WorldFeaturesTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("World Test");
        p.audioEnabled = false; // keep CI silent and deterministic
        return p;
    }

    /** A flat registry-mode level: 20 wide, 6 tall, solid dirt floor. */
    private static Level flatLevel() {
        Level lvl = Level.empty("Flat", 20, 6, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles[lvl.height - 1][c] = dirt;
        }
        lvl.spawnX = 64;
        lvl.spawnY = 128;
        return lvl;
    }

    // --- block registry ---------------------------------------------------------

    @Test
    void standardBlockRegistryHasStableIdsAndProperties() {
        BlockRegistry blocks = BlockRegistry.standard();
        assertEquals(1, blocks.get("dirt").id(), "ids are stable wire/level contracts");
        assertTrue(blocks.get("stone").solid());
        assertFalse(blocks.get("water").solid(), "water is passable");
        assertTrue(blocks.get("torch").emitsLight(), "lights are painted as blocks");
        assertFalse(blocks.get("torch").solid());
        assertEquals("coal", blocks.get("coal_ore").drops(), "ores drop their mineral");
        assertNull(blocks.get(9999), "unknown ids resolve to null, not a crash");
    }

    // --- level modes ------------------------------------------------------------

    @Test
    void registryLevelsDistinguishSolidFromPassable() {
        Level lvl = flatLevel();
        int water = BlockRegistry.standard().get("water").id();
        lvl.setTile(3, 0, water);
        assertTrue(lvl.solidAt(0, lvl.height - 1), "dirt floor is solid");
        assertFalse(lvl.solidAt(3, 0), "water isn't solid in registry mode");

        // Legacy palette levels keep the old rule: every tile is solid.
        Level legacy = LevelLoader.parse("""
                {"tiles": [[0,4],[1,1]]}
                """);
        assertFalse(legacy.registryTiles);
        assertTrue(legacy.solidAt(1, 0), "palette levels treat any tile as solid");
    }

    @Test
    void setTileValidatesBoundsAndBlockIds() {
        Level lvl = flatLevel();
        assertTrue(lvl.setTile(2, 2, 3));
        assertEquals(3, lvl.tileAt(2, 2));
        assertFalse(lvl.setTile(-1, 2, 3), "out of bounds is refused");
        assertFalse(lvl.setTile(2, 99, 3), "out of bounds is refused");
        assertFalse(lvl.setTile(2, 3, 9999), "unknown block ids are refused");
        assertTrue(lvl.setTile(2, 2, 0), "clearing always works");
    }

    @Test
    void levelsRoundTripThroughJsonInBothModes() {
        // Registry mode with entities + spawn, as the creative editor saves.
        Level lvl = flatLevel();
        lvl.setTile(4, 2, BlockRegistry.standard().get("torch").id());
        lvl.entities.add(new Level.EntitySpawn("mob", "zombie", 100, 50));
        lvl.entities.add(new Level.EntitySpawn("item", "apple", 200, 60));
        Level back = LevelLoader.parse(lvl.toJson());
        assertTrue(back.registryTiles, "registry marker survives");
        assertEquals(lvl.tileAt(4, 2), back.tileAt(4, 2));
        assertEquals(lvl.width, back.width);
        assertEquals(2, back.entities.size());
        assertEquals("mob", back.entities.get(0).kind);
        assertEquals("zombie", back.entities.get(0).type);
        assertEquals(lvl.spawnX, back.spawnX, 0.001);

        // Legacy palette mode: parse -> serialize -> parse is stable.
        Level legacy = LevelLoader.load("levels/sample_level.json");
        Level legacyBack = LevelLoader.parse(legacy.toJson());
        assertFalse(legacyBack.registryTiles);
        assertEquals(legacy.width, legacyBack.width);
        assertEquals(legacy.tileAt(0, legacy.height - 1), legacyBack.tileAt(0, legacy.height - 1));
        assertEquals(legacy.colorFor(1), legacyBack.colorFor(1), "palette colours survive");
    }

    // --- inventory ---------------------------------------------------------------

    @Test
    void inventoryStacksMergesAndSerializes() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        assertEquals(0, inv.add("dirt", 10), "everything fits");
        assertEquals(0, inv.add("dirt", 10));
        assertEquals(20, inv.totalOf("dirt"));
        assertEquals(20, inv.slot(0).count, "stacks merge up to maxStack");

        assertEquals(0, inv.add("iron_sword", 1));
        inv.select(1);
        assertEquals("iron_sword", inv.selectedDef().key(), "hotbar selection resolves items");

        assertEquals(5, inv.remove("dirt", 5));
        assertEquals(15, inv.totalOf("dirt"));

        List<Object> data = inv.toList();
        Inventory copy = new Inventory(ItemRegistry.standard());
        copy.fromList(data);
        assertEquals(15, copy.totalOf("dirt"));
        assertEquals(1, copy.totalOf("iron_sword"));

        assertEquals(3, inv.add("bogus_item", 3), "unknown items don't fit anywhere");
    }

    // --- mob AI -------------------------------------------------------------------

    @Test
    void hostileMobChasesAndHitsThePlayer() {
        Level lvl = flatLevel();
        Mob zombie = new Mob(1, MobRegistry.standard().get("zombie"), 300, 128);
        PlayerState player = new PlayerState(1, "prey", 320, 128);
        player.health = 100;

        double dt = 1.0 / 60;
        for (int i = 0; i < 240; i++) {
            zombie.step(lvl, List.of(player), true, true, dt);
        }
        assertTrue(zombie.state == Mob.AIState.CHASE || zombie.state == Mob.AIState.ATTACK,
                "player in detect range triggers the chase (was " + zombie.state + ")");
        assertTrue(player.health < 100, "attacks in range subtract player health");
    }

    @Test
    void combatToggleTurnsHostilesAmbient() {
        Level lvl = flatLevel();
        Mob zombie = new Mob(2, MobRegistry.standard().get("zombie"), 300, 128);
        PlayerState player = new PlayerState(1, "safe", 320, 128);
        double dt = 1.0 / 60;
        for (int i = 0; i < 240; i++) {
            zombie.step(lvl, List.of(player), true, false, dt); // combat off
        }
        assertNotEquals(Mob.AIState.CHASE, zombie.state);
        assertNotEquals(Mob.AIState.ATTACK, zombie.state);
        assertEquals(100.0, player.health, 0.001, "no combat, no damage");
    }

    @Test
    void passiveMobFleesWhenHurtAndDiesAtZero() {
        Mob sheep = new Mob(3, MobRegistry.standard().get("sheep"), 100, 128);
        assertFalse(sheep.damage(5, 90), "surviving damage doesn't kill");
        assertEquals(Mob.AIState.FLEE, sheep.state, "passives run when hurt");
        assertTrue(sheep.damage(1000, 90), "lethal damage reports the kill");
        assertTrue(sheep.dead());
    }

    // --- world simulation -----------------------------------------------------------

    @Test
    void miningDropsItemsAndPlayersPickThemUp() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        GameProfile p = profile();

        int floorRow = lvl.height - 1;
        Block mined = world.mineBlock(3, floorRow, true);
        assertNotNull(mined);
        assertEquals("dirt", mined.key());
        assertEquals(0, lvl.tileAt(3, floorRow), "the block is gone");
        assertEquals(1, world.items().size(), "its drop popped out");
        assertEquals("dirt", world.items().get(0).key);

        // A player standing at the drop picks it up once the delay passes.
        DroppedItem drop = world.items().get(0);
        PlayerState player = new PlayerState(1, "miner", drop.x, drop.y);
        String[] picked = {null};
        world.setPickupListener((who, key, count) -> picked[0] = key);
        for (int i = 0; i < 90 && !world.items().isEmpty(); i++) {
            player.x = world.items().get(0).x; // stay on top of the bouncing drop
            player.y = world.items().get(0).y;
            world.step(1.0 / 60, List.of(player), p);
        }
        assertEquals("dirt", picked[0], "pickup listener fired");
        assertTrue(world.items().isEmpty());
    }

    @Test
    void meleeSwingsKillMobsAndDropLoot() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        world.spawnMob("sheep", 100, 128);
        PlayerState player = new PlayerState(1, "hunter", 90, 128);

        Mob hit = null;
        for (int i = 0; i < 10 && !world.mobs().isEmpty(); i++) {
            hit = world.playerAttack(player, 110, 140, 50);
        }
        assertNotNull(hit, "swing toward the sheep connects");
        assertTrue(world.mobs().isEmpty(), "lethal damage removes the mob");
        assertEquals(1, world.items().size(), "passive mobs drop food");
        assertEquals("cooked_meat", world.items().get(0).key);
    }

    @Test
    void dayNightCurveMatchesTheProfile() {
        GameProfile p = profile();
        p.lightingEnabled = true;
        p.dayNightCycle = true;
        p.nightDarkness = 0.6;
        assertEquals(0.0, World.darknessFor(0.25, p), 0.001, "noon is fully lit");
        assertEquals(0.6, World.darknessFor(0.75, p), 0.001, "midnight hits nightDarkness");
        assertTrue(World.darknessFor(0.6, p) > 0, "evening is dimmer than noon");

        p.dayNightCycle = false;
        p.nightMode = true;
        assertEquals(0.6, World.darknessFor(0.0, p), 0.001, "fixed night uses the toggle");
        p.lightingEnabled = false;
        assertEquals(0.0, World.darknessFor(0.75, p), 0.001, "lighting off = no darkness");
    }

    // --- lighting pass ---------------------------------------------------------------

    @Test
    void lightingPassDarkensAndLightsCutThrough() {
        LightingPass pass = new LightingPass();
        int w = 64, h = 64;
        int[] src = new int[w * h];
        java.util.Arrays.fill(src, 0xFFFFFFFF); // white frame
        int[] dst = new int[w * h];
        ShaderContext ctx = new ShaderContext(w, h, 0f, 1f);

        // Daylight: identity.
        pass.setDarkness(0);
        pass.apply(src, dst, w, h, ctx);
        assertEquals(0xFFFFFFFF, dst[0]);

        // Full darkness, no ambient, no lights: black.
        pass.setDarkness(1);
        pass.setAmbient(0);
        pass.clearLights();
        pass.apply(src, dst, w, h, ctx);
        assertEquals(0xFF000000, dst[w * 32 + 32] & 0xFF000000);
        int corner = dst[0];
        assertTrue(((corner >> 16) & 0xFF) < 30, "unlit pixels go dark");

        // A light at the centre restores brightness there but not the corner.
        pass.addLight(32, 32, 40, Color.WHITE);
        pass.apply(src, dst, w, h, ctx);
        int centre = dst[w * 32 + 32];
        int edge = dst[8]; // top edge, far from the light
        assertTrue(((centre >> 16) & 0xFF) > 150, "lit pixel is bright");
        assertTrue(((centre >> 16) & 0xFF) > ((edge >> 16) & 0xFF) + 50,
                "brightness falls off away from the light");
    }

    @Test
    void lightingPassHonoursTheGlslContract() {
        LightingPass pass = new LightingPass();
        pass.setDarkness(0.5);
        assertEquals("lighting", pass.name());
        String glsl = pass.glsl();
        assertTrue(glsl.contains("uniform sampler2D uTexture"));
        assertTrue(glsl.contains("uLightPos"));
        assertTrue(glsl.contains("uDarkness"));
        assertTrue(pass.uniforms().containsKey("uDarkness"));
        assertTrue(pass.uniforms().containsKey("uLightCount"));
    }

    // --- level store + creative scene -------------------------------------------------

    @Test
    void levelStoreSavesAndLoadsPerGameType(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "My Platformer!");
        Level lvl = flatLevel();
        lvl.name = "Test Arena";
        lvl.entities.add(new Level.EntitySpawn("mob", "slime", 50, 60));

        Path file = store.save(lvl);
        assertTrue(file.toString().contains("my_platformer"), "levels nest under the game type");
        assertTrue(store.exists("Test Arena"));
        assertEquals(List.of("test_arena"), store.list());

        Level back = store.load("test_arena");
        assertEquals("Test Arena", back.name);
        assertTrue(back.registryTiles);
        assertEquals(1, back.entities.size());
        assertEquals("slime", back.entities.get(0).type);
    }

    @Test
    void levelCarriesItsOwnFeatureSettings() {
        // A level stores its own toggles so a game type can hold a diverse mix.
        Level lvl = flatLevel();
        lvl.settings = new GameProfile("ignored-name");
        lvl.settings.gravityEnabled = false;
        lvl.settings.lightingEnabled = true;
        lvl.settings.shadersEnabled = true;
        lvl.settings.maxFps = 90;

        Level back = LevelLoader.parse(lvl.toJson());
        assertNotNull(back.settings, "settings survive the JSON round-trip");
        assertFalse(back.settings.gravityEnabled);
        assertTrue(back.settings.lightingEnabled);
        assertTrue(back.settings.shadersEnabled);
        assertEquals(90, back.settings.maxFps);

        // Legacy levels (no settings) round-trip with none, so they keep using
        // the active game type's profile.
        assertNull(LevelLoader.parse(flatLevel().toJson()).settings);
    }

    @Test
    void loadingALevelAppliesItsSettingsButKeepsTheGameType() {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        GameProfile gameType = new GameProfile("My Game Type");
        gameType.texturePackDir = "packs/forest";
        gameType.gravityEnabled = true;
        gameType.lightingEnabled = false;
        ctx.setProfile(gameType);

        Level lvl = flatLevel();
        lvl.settings = new GameProfile("whatever");
        lvl.settings.gravityEnabled = false;   // a no-gravity level
        lvl.settings.lightingEnabled = true;

        ctx.applyLevelSettings(lvl.settings);

        // Toggles now come from the level...
        assertFalse(ctx.profile().gravityEnabled);
        assertTrue(ctx.profile().lightingEnabled);
        // ...but the game-type identity (folder + shared texture pack) stays.
        assertEquals("My Game Type", ctx.profile().name);
        assertEquals("packs/forest", ctx.profile().texturePackDir);

        // A legacy level (null settings) leaves the active profile untouched.
        ctx.applyLevelSettings(null);
        assertFalse(ctx.profile().gravityEnabled);
        assertEquals("My Game Type", ctx.profile().name);
    }

    @Test
    void playSceneAppliesTheLoadedLevelsOwnSettings(@TempDir Path dir) throws Exception {
        // The game type defaults to gravity ON; the level we load has it OFF.
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile gameType = profile(); // name "World Test", gravity on
        gameType.gravityEnabled = true;
        ctx.setProfile(gameType);

        Level lvl = flatLevel();
        lvl.name = "No Gravity Room";
        lvl.settings = gameType.copy();
        lvl.settings.gravityEnabled = false; // this level's own toggle
        Path levelFile = dir.resolve("no_gravity.json");
        Files.writeString(levelFile, lvl.toJson());
        gameType.lastLevelPath = levelFile.toString(); // what "Load Level" sets

        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("play", new com.larsons.engine.demo.PlayScene(ctx, levelFile.toString()));
        scenes.register("menu", new com.larsons.engine.demo.MainMenuScene(ctx));
        scenes.setScene("play");

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        for (int i = 0; i < 10; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(g, 0f);
        }
        g.dispose();

        // Playing the level switched gravity off, while the game type (folder)
        // identity stayed put.
        assertFalse(ctx.profile().gravityEnabled, "the level's own toggle wins over the game type default");
        assertEquals("World Test", ctx.profile().name, "the game type stays selected");
        assertEquals("PlayScene", scenes.current().name());
    }

    @Test
    void levelSelectSceneRendersItsViewsHeadlessly(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(profile()); // "World Test" — an empty (no-levels) game type

        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("levelselect", new com.larsons.engine.demo.LevelSelectScene(ctx));
        scenes.register("menu", new com.larsons.engine.demo.MainMenuScene(ctx));
        scenes.setScene("levelselect");

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        for (int i = 0; i < 10; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(g, 0f);
        }
        g.dispose();
        assertEquals("LevelSelectScene", scenes.current().name(),
                "the Load Level screen builds and renders without a levels folder");
    }

    @Test
    void creativeSceneEditsAndRendersHeadlessly(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(profile());

        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("creative", new CreativeScene(ctx));
        scenes.register("menu", new com.larsons.engine.demo.MainMenuScene(ctx));
        scenes.setScene("creative");

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        for (int i = 0; i < 20; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(g, 0f);
        }
        g.dispose();
        assertEquals("CreativeScene", scenes.current().name());
    }

    @Test
    void parallaxRendersWithoutAssets() {
        ParallaxBackground bg = new ParallaxBackground(new Color(24, 28, 38), 42);
        BufferedImage frame = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        bg.render(g, 0, 0, 320, 180);
        bg.render(g, 5000, 200, 320, 180); // large camera offsets tile seamlessly
        g.dispose();
    }
}
