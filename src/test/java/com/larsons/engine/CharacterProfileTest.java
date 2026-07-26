package com.larsons.engine;

import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.character.Characters;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Character profiles: creator-made playable characters with their own skins
 * and traits, added like any other custom object, persisted with the game
 * type, offered per level by its creator's roster, and actually felt in the
 * simulation.
 */
@Timeout(60)
class CharacterProfileTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void resetRegistry() {
        Characters.reset();
    }

    private static CharacterProfile sprinter() {
        CharacterProfile p = new CharacterProfile("sprinter", "Sprinter");
        p.body = new Color(200, 60, 60);
        p.speed = 1.8;
        p.sprintEnabled = true;
        p.airJumps = 2;
        p.jumpHeight = 1.4;
        p.maxHealth = 60;
        p.maxMana = 20;
        p.maxStamina = 200;
        p.ultimateKey = "overdrive";
        return p;
    }

    private static GameProfile profileFor(Perspective p) {
        GameProfile profile = new GameProfile("chars");
        profile.perspective = p;
        profile.gravityEnabled = p == Perspective.SIDE_SCROLL;
        profile.normalize();
        return profile;
    }

    // --- the registry -------------------------------------------------------------

    @Test
    void theBuiltInCharacterIsAlwaysAvailable() {
        assertTrue(Characters.exists(CharacterProfile.DEFAULT_KEY));
        assertNotNull(Characters.getOrDefault("nobody"),
                "an unknown key falls back to the default character");
        assertFalse(Characters.unregister(CharacterProfile.DEFAULT_KEY),
                "the built-in character can't be removed");
    }

    @Test
    void profilesPersistWithTheGameTypeAndReRegisterOnLoad(@TempDir Path tmp) {
        CharacterStore store = new CharacterStore(tmp.toString(), "My Game");
        store.loadAndRegister();
        store.add(sprinter());
        assertTrue(Files.exists(store.file()), "characters.json is written beside the levels");
        assertTrue(store.isCustom("sprinter"));

        // A fresh session re-reads them.
        Characters.reset();
        assertFalse(Characters.exists("sprinter"));
        CharacterStore reopened = new CharacterStore(tmp.toString(), "My Game");
        reopened.loadAndRegister();
        CharacterProfile back = Characters.get("sprinter");
        assertNotNull(back, "the saved profile registers again");
        assertEquals(1.8, back.speed, 0.001);
        assertEquals(2, back.airJumps);
        assertEquals(60, back.maxHealth, 0.001);
        assertEquals("overdrive", back.ultimateKey);
        assertEquals(new Color(200, 60, 60), back.body);

        // Deleting takes it out of the file and the registry alike.
        assertTrue(reopened.remove("sprinter"));
        assertFalse(Characters.exists("sprinter"));
        CharacterStore third = new CharacterStore(tmp.toString(), "My Game");
        third.loadAndRegister();
        assertFalse(Characters.exists("sprinter"), "the deletion stuck");
    }

    @Test
    void loadingAGameTypeNeverLeaksAnotherTypesCharacters(@TempDir Path tmp) {
        CharacterStore first = new CharacterStore(tmp.toString(), "First");
        first.loadAndRegister();
        first.add(sprinter());
        assertTrue(Characters.exists("sprinter"));

        new CharacterStore(tmp.toString(), "Second").loadAndRegister();
        assertFalse(Characters.exists("sprinter"),
                "switching game types resets the roster to that type's own");
        assertTrue(Characters.exists(CharacterProfile.DEFAULT_KEY));
    }

    @Test
    void outOfRangeTraitsAreClampedIntoPlayableOnes() {
        CharacterProfile p = new CharacterProfile("odd", "Odd");
        p.speed = 99;
        p.jumpHeight = -4;
        p.airJumps = 50;
        p.maxHealth = 0;
        p.ultimateKey = "an_ability_from_a_newer_build";
        p.normalize();
        assertEquals(3.0, p.speed, 0.001);
        assertEquals(0.25, p.jumpHeight, 0.001);
        assertEquals(8, p.airJumps);
        assertEquals(1, p.maxHealth, 0.001);
        assertEquals(Ultimates.NONE, p.ultimateKey,
                "an unknown ability degrades to none rather than breaking the character");
    }

    // --- the level roster ---------------------------------------------------------

    @Test
    void aLevelOffersTheRosterItsCreatorChose(@TempDir Path tmp) {
        Characters.register(sprinter());
        CharacterProfile tank = new CharacterProfile("tank", "Tank");
        tank.speed = 0.7;
        tank.maxHealth = 250;
        Characters.register(tank);

        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Roster", 30, 20, 32);
        lvl.characters.add("tank");
        List<CharacterProfile> roster = Characters.rosterFor(lvl.characters);
        assertEquals(1, roster.size());
        assertEquals("tank", roster.get(0).key);

        // An empty roster means "every character", so no level is unplayable.
        lvl.characters.clear();
        assertEquals(Characters.all().size(), Characters.rosterFor(lvl.characters).size());

        // …and so does a roster naming only characters that no longer exist.
        lvl.characters.add("deleted_character");
        assertEquals(Characters.all().size(), Characters.rosterFor(lvl.characters).size());
    }

    @Test
    void theRosterSavesAndLoadsWithTheLevel(@TempDir Path tmp) throws Exception {
        Characters.register(sprinter());
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Arena", 24, 24, 32);
        lvl.characters.add("sprinter");
        lvl.characters.add(CharacterProfile.DEFAULT_KEY);

        Path file = tmp.resolve("arena.json");
        Files.writeString(file, lvl.toJson());
        Level back = LevelLoader.load(file.toString());
        assertEquals(List.of("sprinter", CharacterProfile.DEFAULT_KEY), back.characters);

        // A level saved before rosters existed simply has none.
        Level legacy = LevelFormat.SIDE_SCROLLER.starterLevel("Old", 24, 24, 32);
        Path legacyFile = tmp.resolve("old.json");
        Files.writeString(legacyFile, legacy.toJson());
        assertTrue(LevelLoader.load(legacyFile.toString()).characters.isEmpty());
    }

    // --- traits reach the simulation -----------------------------------------------

    @Test
    void traitsResizeThePoolsAndDriveThePhysics() {
        PlayerState s = new PlayerState(0, "", 0, 0);
        sprinter().applyTo(s);
        assertEquals(60, s.maxHealth, 0.001);
        assertEquals(60, s.health, 0.001, "a character starts at their own full health");
        assertEquals(200, s.maxStamina, 0.001);
        assertEquals(20, s.maxMana, 0.001);
        assertEquals(2, s.airJumps);
        assertEquals("sprinter", s.characterKey);
        assertEquals("overdrive", s.ultimateKey);

        // Speed: the same input carries a faster character further.
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Speed", 60, 30, 32);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);
        PlayerState fast = new PlayerState(0, "", 300, 300);
        sprinter().applyTo(fast);
        PlayerState normal = new PlayerState(1, "", 300, 300);
        CharacterProfile.defaultProfile().applyTo(normal);
        for (int i = 0; i < 30; i++) {
            PlayerInput in = new PlayerInput(false, true, false, false, i);
            PlayerPhysics.step(fast, in, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
            PlayerPhysics.step(normal, in, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        }
        assertTrue(fast.x > normal.x + 20,
                "the 1.8x character should be well ahead, was " + fast.x + " vs " + normal.x);
    }

    @Test
    void aCharacterWithoutSprintNeverSprints() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Sprint", 60, 30, 32);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);

        CharacterProfile plodder = new CharacterProfile("plodder", "Plodder");
        plodder.sprintEnabled = false;
        PlayerState no = new PlayerState(0, "", 300, 300);
        plodder.applyTo(no);
        PlayerState yes = new PlayerState(1, "", 300, 300);
        CharacterProfile.defaultProfile().applyTo(yes);

        for (int i = 0; i < 30; i++) {
            PlayerInput in = new PlayerInput(false, true, false, false, i);
            in.sprint = true;
            PlayerPhysics.step(no, in, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
            PlayerPhysics.step(yes, in, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        }
        assertTrue(yes.x > no.x + 10, "only the sprint-capable character speeds up");
        assertEquals(no.maxStamina, no.stamina, 0.001,
                "a character who can't sprint never spends stamina on it");
    }

    @Test
    void airJumpsAndJumpHeightComeFromTheProfile() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Jump", 40, 20, 32);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);

        CharacterProfile grounded = new CharacterProfile("grounded", "Grounded");
        grounded.airJumps = 0;
        PlayerState s = new PlayerState(0, "", 100, (lvl.height - 3) * 32.0);
        grounded.applyTo(s);
        // Fall onto the floor, then jump.
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(s, new PlayerInput(false, false, false, false, i),
                    lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        }
        PlayerInput jump = new PlayerInput(false, false, false, false, 100);
        jump.jump = true;
        PlayerPhysics.step(s, jump, lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertTrue(s.vy < 0, "space jumps from the ground");
        // Mid-air, a character with no air jumps gets nothing more.
        double risingVy = s.vy;
        PlayerInput second = new PlayerInput(false, false, false, false, 101);
        second.jump = true;
        PlayerPhysics.step(s, second, lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertTrue(s.vy > risingVy, "no air jump: gravity keeps eating the rise");

        // Jump height scales the take-off velocity.
        CharacterProfile springy = new CharacterProfile("springy", "Springy");
        springy.jumpHeight = 2.0;
        PlayerState high = new PlayerState(0, "", 100, (lvl.height - 3) * 32.0);
        springy.applyTo(high);
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(high, new PlayerInput(false, false, false, false, i),
                    lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        }
        PlayerInput bigJump = new PlayerInput(false, false, false, false, 200);
        bigJump.jump = true;
        PlayerPhysics.step(high, bigJump, lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertTrue(high.vy < -PlayerPhysics.JUMP * 1.5,
                "a 2x jump height leaves the ground much faster, was " + high.vy);
    }

    @Test
    void theChosenCharacterRidesAlongOnTheWire() {
        PlayerState s = new PlayerState(7, "Ana", 10, 20);
        sprinter().applyTo(s);
        s.ultCharge = 0.5;
        PlayerState back = PlayerState.fromMap(s.toMap());
        assertEquals("sprinter", back.characterKey);
        assertEquals(60, back.maxHealth, 0.001);
        assertEquals(200, back.maxStamina, 0.001);
        assertEquals(20, back.maxMana, 0.001);
        assertEquals(0.5, back.ultCharge, 0.001);

        // A default-character message stays as small as it always was.
        PlayerState plain = new PlayerState(1, "Bo", 0, 0);
        assertFalse(plain.toMap().containsKey("ch"), "no character key to send");
        assertFalse(plain.toMap().containsKey("mh"), "no resized pools to send");
        assertEquals("", PlayerState.fromMap(plain.toMap()).characterKey);
    }
}
