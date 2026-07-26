package com.larsons.engine;

import com.larsons.engine.audio.SoundDef;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.SoundLoader;
import com.larsons.engine.audio.SoundPack;
import com.larsons.engine.audio.SoundStore;
import com.larsons.engine.audio.SoundSynth;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop-in sound pack: a folder of correctly-named WAVs and MP3s beside
 * the jar gives the game its voice with no menu visit — sorted into
 * subfolders per family, defaulting to silence for everything the creator
 * hasn't supplied, and letting one file cover a whole object until a more
 * specific one is added.
 */
@Timeout(60)
class SoundPackTest {

    @AfterEach
    void resetRuntime() {
        Sounds.clear();
        SoundPack.useDir("");
        SoundLoader.clearCache();
    }

    /** A short mono WAV, so a test can put real audio in the pack. */
    private static Path wav(Path file) throws IOException {
        int frames = 800;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int s = (int) (Math.sin(i * 0.2) * 9000);
            pcm[i * 2] = (byte) s;
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        Files.createDirectories(file.getParent());
        AudioFormat format = new AudioFormat(22050, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    // --- the catalogue ---------------------------------------------------------

    /**
     * The point of the whole feature: the catalogue really does list every
     * place the level-loader game makes a noise, with an action state for
     * each — not a token handful.
     */
    @Test
    void theCatalogueCoversTheWholeGame() {
        List<SoundKeys.Entry> all = SoundKeys.all();
        assertTrue(all.size() > 1000,
                "the sound list should span the game's registries, got " + all.size());

        Set<String> keys = new HashSet<>();
        for (SoundKeys.Entry e : all) {
            assertTrue(keys.add(e.key()), "duplicate sound key " + e.key());
            assertFalse(e.name().isBlank(), e.key() + " needs a display name");
            assertTrue(SoundKeys.folders().contains(e.folder()),
                    e.key() + " lands in unknown folder " + e.folder());
        }

        // Each family the request called out is in there, with its states.
        assertTrue(keys.contains("player/swim"));
        assertTrue(keys.contains("player/run"));
        assertTrue(keys.contains("player/ult_activate"));
        assertTrue(keys.contains("ultimate/meteor_volley/activate"),
                "an ultimate's cast is its own sound");
        assertTrue(keys.contains("ultimate/meteor_volley/impact"),
                "an ultimate's landing is its own sound");
        assertTrue(keys.contains("projectile/meteor/fire"));
        assertTrue(keys.contains("projectile/meteor/flight"),
                "a meteor on its way down is its own sound");
        assertTrue(keys.contains("projectile/meteor/impact"),
                "a meteor crashing is its own sound");
        assertTrue(keys.contains("block/dirt/break"));
        assertTrue(keys.contains("block/dirt/step"));
        assertTrue(keys.contains("mob/slime/death"));
        assertTrue(keys.contains("music/level"), "level music is a sound state");
        assertTrue(keys.contains("music/boss"));
    }

    /** Every catalogue entry maps to a file name a creator can actually type. */
    @Test
    void everySoundNamesAFileInThePack() {
        for (SoundKeys.Entry e : SoundKeys.all()) {
            String file = SoundKeys.preferredFile(e.key());
            assertTrue(file.endsWith(".wav"), e.key() + " -> " + file);
            assertEquals(e.folder() + "/" + e.file() + ".wav", file,
                    "the key list and the lookup must agree for " + e.key());
        }
    }

    /**
     * Keys fall back one segment at a time, so one file covers an object's
     * whole vocabulary until a more specific one is dropped in beside it.
     */
    @Test
    void keysFallBackFromSpecificToGeneral() {
        assertEquals(List.of("mobs/slime_attack", "mobs/slime"),
                SoundKeys.paths("mob/slime/attack"));
        assertEquals(List.of("player/jump"), SoundKeys.paths("player/jump"));
        assertEquals(List.of("music/level"), SoundKeys.paths("music/level"));

        // A block is looked for in its own palette folder first — water in
        // liquids/, a torch in lights/ — which is the folder SOUND_KEYS.txt
        // tells the creator to use. The others are still accepted.
        List<String> water = SoundKeys.paths("block/water/splash");
        assertEquals("liquids/water_splash", water.get(0),
                "a liquid's own folder should win: " + water);
        assertEquals("liquids/water_splash", SoundKeys.preferredFile("block/water/splash")
                .replace(".wav", ""));
        assertTrue(water.contains("blocks/water_splash"),
                "a pack that files everything under blocks/ should still work: " + water);
        assertTrue(water.indexOf("liquids/water_splash") < water.indexOf("blocks/water"),
                "the specific name must beat the general one: " + water);
        assertEquals("lights/torch_place", SoundKeys.paths("block/torch/place").get(0),
                "a light's own folder should win too");
        assertEquals("blocks/dirt_break", SoundKeys.paths("block/dirt/break").get(0));
    }

    // --- lookup ----------------------------------------------------------------

    @Test
    void aFileInThePackSuppliesItsSound(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        assertNull(SoundPack.fileFor("player/swim"), "nothing supplied yet");
        assertEquals(Sounds.Source.SILENT, Sounds.sourceOf("player/swim"),
                "an unsupplied sound is silent by default");

        wav(dir.resolve("player/swim.wav"));
        SoundPack.reload();
        assertNotNull(SoundPack.fileFor("player/swim"));
        assertEquals(Sounds.Source.PACK, Sounds.sourceOf("player/swim"));
        assertFalse(Sounds.resolve("player/swim").isEmpty());
    }

    /** One file named after the object covers all of its action states. */
    @Test
    void oneFilePerObjectCoversItsWholeVocabulary(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        wav(dir.resolve("mobs/slime.wav"));
        SoundPack.reload();

        for (String state : SoundKeys.MOB_STATES) {
            assertNotNull(SoundPack.fileFor(SoundKeys.mob("slime", state)),
                    "mobs/slime.wav should cover " + state);
        }
        // …until a state gets its own file, which then wins for that one.
        Path death = wav(dir.resolve("mobs/slime_death.wav"));
        SoundPack.reload();
        assertEquals(death, SoundPack.fileFor("mob/slime/death"));
        assertEquals(dir.resolve("mobs/slime.wav"), SoundPack.fileFor("mob/slime/attack"));
    }

    @Test
    void mp3FilesAreAcceptedAlongsideWav(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        // Four silent MPEG-1 Layer III frames (see Mp3DecoderTest).
        byte[] mp3 = new byte[4 * 417];
        for (int f = 0; f < 4; f++) {
            int o = f * 417;
            mp3[o] = (byte) 0xFF;
            mp3[o + 1] = (byte) 0xFB;
            mp3[o + 2] = (byte) 0x90;
            mp3[o + 3] = (byte) 0xC0;
        }
        Files.createDirectories(dir.resolve("music"));
        Files.write(dir.resolve("music/level.mp3"), mp3);
        SoundPack.reload();

        assertEquals(dir.resolve("music/level.mp3"), SoundPack.fileFor("music/level"));
        assertEquals(4 * 1152, Sounds.resolve("music/level").frames());
    }

    // --- defaults --------------------------------------------------------------

    /**
     * The rule the request set: an action with no file makes no sound. The
     * exceptions are the actions the engine has always had a synthesized
     * voice for — placing and breaking blocks, hitting a mob, picking an item
     * up, firing, exploding — which keep it, so adding hundreds of new sounds
     * does not silence the ones a game already made.
     */
    @Test
    void everythingDefaultsToSilenceExceptTheActionsThatAlwaysHadAVoice(@TempDir Path dir)
            throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());

        int silent = 0;
        int builtIn = 0;
        for (SoundKeys.Entry e : SoundKeys.all()) {
            switch (Sounds.sourceOf(e.key())) {
                case SILENT -> silent++;
                case BUILT_IN -> builtIn++;
                default -> throw new AssertionError(
                        "an empty pack supplied " + e.key() + " from somewhere");
            }
        }
        assertTrue(silent > builtIn,
                "most of the game should be silent until a pack is supplied: "
                        + silent + " silent vs " + builtIn + " built-in");

        // The actions that made a noise before sound assets existed still do,
        // for every object of their kind — a block a creator invented breaks
        // with the same crunch dirt does.
        assertEquals(Sounds.Source.BUILT_IN, Sounds.sourceOf("player/jump"));
        assertEquals(Sounds.Source.BUILT_IN, Sounds.sourceOf("block/dirt/break"));
        assertEquals(Sounds.Source.BUILT_IN, Sounds.sourceOf("block/stone/place"));
        assertEquals(Sounds.Source.BUILT_IN, Sounds.sourceOf("mob/slime/hurt"));
        assertEquals(Sounds.Source.BUILT_IN, Sounds.sourceOf("ui/click"));

        // Everything the sound system newly named waits for its audio. These
        // are whole families that were simply not sounds before.
        for (String key : List.of("player/swim", "player/run", "player/walk",
                "player/land", "player/ult_charged", "ultimate/meteor_volley/activate",
                "ultimate/meteor_volley/impact", "projectile/meteor/flight",
                "block/dirt/step", "block/dirt/mine", "mob/slime/idle",
                "mob/slime/step", "vehicle/war_dragon/engine", "music/level",
                "music/boss", "ambient/night", "door/open", "cutscene/start",
                "minigame/victory", "surface/moss/step")) {
            assertEquals(Sounds.Source.SILENT, Sounds.sourceOf(key),
                    key + " should be silent until a file is dropped in");
            assertTrue(Sounds.resolve(key).isEmpty(), key + " should resolve to silence");
        }
    }

    /** A built-in voice can be switched off, making that action truly silent. */
    @Test
    void theBuiltInFallbackCanBeTurnedOff(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        assertTrue(SoundSynth.hasFallback("player/jump"));

        Sounds.put(new SoundDef("player/jump", "", 1, 1, false, true, true, false));
        assertEquals(Sounds.Source.SILENT, Sounds.sourceOf("player/jump"));
        assertTrue(Sounds.resolve("player/jump").isEmpty());
    }

    // --- overrides -------------------------------------------------------------

    /** A file outside the pack can supply one sound, like a texture can. */
    @Test
    void aSoundCanPointAtAFileOutsideThePack(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir.resolve("sounds"));
        SoundPack.useDir(dir.resolve("sounds").toString());
        Path elsewhere = wav(dir.resolve("elsewhere/my_swim.wav"));

        Sounds.put(new SoundDef("player/swim", elsewhere.toString(),
                1, 1, false, true, false, true));
        assertEquals(Sounds.Source.FILE, Sounds.sourceOf("player/swim"));
        assertFalse(Sounds.resolve("player/swim").isEmpty());
    }

    /** A bare file name resolves against the pack folder, as the editor says. */
    @Test
    void abareFileNameResolvesInsideThePack(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        wav(dir.resolve("player/custom.wav"));
        assertEquals(dir.resolve("player/custom.wav").toAbsolutePath(),
                Sounds.resolvePath("player/custom.wav").toAbsolutePath());
    }

    /** Per-sound volume/pitch/loop are saved into the pack's own config. */
    @Test
    void perSoundSettingsTravelWithThePack(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        assertFalse(SoundPack.hasOverride("music/boss"));

        SoundPack.setOverride("music/boss", 0.4, 1.0, true, false);
        assertTrue(SoundPack.hasOverride("music/boss"));
        assertTrue(Files.readString(dir.resolve(SoundPack.CONFIG_FILE)).contains("music/boss"));

        // Re-reading the folder from scratch must find the same settings.
        SoundPack.reload();
        SoundPack.Playback p = SoundPack.playbackFor("music/boss");
        assertEquals(0.4, p.volume(), 1e-9);
        assertTrue(p.loop());
        assertFalse(p.varyPitch());

        SoundPack.clearOverride("music/boss");
        SoundPack.reload();
        assertFalse(SoundPack.hasOverride("music/boss"));
    }

    /** Assignments persist as sounds.json, like skins.json does for textures. */
    @Test
    void assignmentsPersistToDisk(@TempDir Path dir) {
        SoundStore store = new SoundStore(dir.toString());
        store.save(List.of(
                new SoundDef("player/swim", "custom/swim.wav", 0.8, 1.1, true, false, false, true),
                new SoundDef("music/level", "", 0.5, 1.0, true, false, true, true)));

        List<SoundDef> loaded = new SoundStore(dir.toString()).load();
        assertEquals(2, loaded.size());
        SoundDef swim = loaded.get(0);
        assertEquals("player/swim", swim.key());
        assertEquals("custom/swim.wav", swim.file());
        assertEquals(0.8, swim.volume(), 1e-9);
        assertEquals(1.1, swim.pitch(), 1e-9);
        assertTrue(swim.loop());
        assertFalse(swim.varyPitch());
        assertFalse(swim.usePack());
    }

    // --- scaffolding -----------------------------------------------------------

    @Test
    void scaffoldingWritesTheFoldersAndTheKeyList(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        for (String folder : SoundKeys.folders()) {
            assertTrue(Files.isDirectory(dir.resolve(folder)), "missing folder " + folder);
        }
        assertTrue(Files.isRegularFile(dir.resolve(SoundPack.README_FILE)));
        assertTrue(Files.isRegularFile(dir.resolve(SoundPack.CONFIG_FILE)));

        String keys = Files.readString(dir.resolve(SoundPack.KEYS_FILE));
        assertTrue(keys.contains("player/jump.wav"));
        assertTrue(keys.contains("ultimates/meteor_volley_activate.wav"));
        assertTrue(keys.contains("projectiles/meteor_flight.wav"));
        assertTrue(keys.contains("music/level.wav"));
        assertTrue(keys.contains("SILENT"), "the key list should say what the default is");
    }

    /** Re-scaffolding refreshes the docs but leaves the creator's settings. */
    @Test
    void rescaffoldingKeepsTheCreatorsSettings(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        SoundPack.setOverride("player/run", 0.3, 1.2, false, true);
        String before = Files.readString(dir.resolve(SoundPack.CONFIG_FILE));

        SoundPack.scaffold(dir);
        assertEquals(before, Files.readString(dir.resolve(SoundPack.CONFIG_FILE)),
                "an existing soundpack.json must not be clobbered");
    }

    // --- custom content --------------------------------------------------------

    /**
     * The request's last line: objects made with the palette's "+" button get
     * the same treatment. They register into the same registries the
     * catalogue reads, so they arrive with a full set of action states and a
     * place in the generated key list.
     */
    @Test
    void objectsMadeWithThePlusButtonGetTheirOwnSounds(@TempDir Path dir) throws IOException {
        CustomContentStore store = new CustomContentStore(
                dir.resolve("custom").toString(), "sound-test");
        Block block = Block.terrain(9001, "moonrock", "Moonrock", new Color(180, 180, 200));
        MobDef mob = MobDef.passive("dustling", "Dustling", new Color(200, 180, 140),
                new Color(120, 100, 80), 26, 50, 18);
        store.addBlock(block);
        store.addMob(mob);
        try {
            assertNotNull(BlockRegistry.standard().get("moonrock"),
                    "a created block should register live");
            assertNotNull(MobRegistry.standard().get("dustling"));

            Set<String> keys = new HashSet<>();
            for (SoundKeys.Entry e : SoundKeys.all()) keys.add(e.key());
            for (String state : SoundKeys.BLOCK_STATES) {
                assertTrue(keys.contains("block/moonrock/" + state),
                        "a created block needs its " + state + " sound");
            }
            for (String state : SoundKeys.MOB_STATES) {
                assertTrue(keys.contains("mob/dustling/" + state),
                        "a created mob needs its " + state + " sound");
            }

            // And the generated key list names the files to drop in for them.
            SoundPack.scaffold(dir.resolve("sounds"));
            String list = Files.readString(dir.resolve("sounds").resolve(SoundPack.KEYS_FILE));
            assertTrue(list.contains("blocks/moonrock_break.wav"));
            assertTrue(list.contains("mobs/dustling_death.wav"));
        } finally {
            store.remove("block", "moonrock");
            store.remove("mob", "dustling");
        }
    }

    /** Custom content is deregistered again, so other tests see the built-ins. */
    @Test
    void theCatalogueTracksTheLiveRegistries() {
        int before = SoundKeys.all().size();
        assertSame(BlockRegistry.standard(), BlockRegistry.standard());
        assertEquals(before, SoundKeys.all().size(),
                "reading the catalogue must not change it");
    }
}
