package com.larsons.engine;

import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ultimate abilities: an Overwatch-style meter that fills with time and
 * (much faster) with damage dealt, spends itself in one cast, and resolves in
 * the one authoritative {@link World} — so an ability behaves the same in
 * single-player, the creative play-test, and on the dedicated server, in every
 * perspective.
 */
@Timeout(60)
class UltimateAbilityTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profileFor(Perspective p) {
        GameProfile profile = new GameProfile("ults");
        profile.perspective = p;
        profile.gravityEnabled = p == Perspective.SIDE_SCROLL;
        profile.mobsEnabled = true;
        profile.combatEnabled = true;
        profile.normalize();
        return profile;
    }

    /** A player with a charged ultimate, standing in the middle of a level. */
    private static PlayerState caster(String ultimateKey, Level lvl) {
        CharacterProfile p = new CharacterProfile("hero", "Hero");
        p.ultimateKey = ultimateKey;
        PlayerState s = new PlayerState(0, "Hero", lvl.width * 16.0, lvl.height * 16.0);
        p.applyTo(s);
        s.ultCharge = 1.0;
        return s;
    }

    private static Level arena(LevelFormat format) {
        return format.starterLevel("Arena", 60, 40, 32);
    }

    // --- the catalogue ------------------------------------------------------------

    @Test
    void theCatalogueCoversEveryAbilityKindWithReadableCopy() {
        List<Ultimate> all = Ultimates.all();
        assertTrue(all.size() >= 5 && all.size() <= 8,
                "5-8 abilities, had " + all.size());
        for (Ultimate.Kind kind : Ultimate.Kind.values()) {
            assertTrue(all.stream().anyMatch(u -> u.kind() == kind),
                    "an ability exists for " + kind);
        }
        for (Ultimate u : all) {
            assertFalse(u.name().isBlank(), u.key() + " has a name");
            assertFalse(u.description().isBlank(), u.key() + " explains itself");
            assertTrue(u.chargeSeconds() > 0, u.key() + " charges over time");
            assertTrue(u.chargePerDamage() > 0, u.key() + " charges from damage dealt");
            assertNotNull(u.color(), u.key() + " has a meter colour");
            assertEquals(u, Ultimates.get(u.key()));
            assertEquals(u.key(), Ultimates.keyForChoice(Ultimates.choiceForKey(u.key())),
                    u.key() + " round-trips through the profile editor's choices");
        }
    }

    // --- the meter -----------------------------------------------------------------

    @Test
    void theMeterFillsWithTimeAndFasterWithDamage() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        PlayerState s = caster("nova_burst", lvl);
        s.ultCharge = 0;
        Ultimate u = Ultimates.of(s);
        assertNotNull(u);

        Ultimates.charge(s, 1.0);
        double afterASecond = s.ultCharge;
        assertTrue(afterASecond > 0 && afterASecond < 0.1,
                "a second of play is a trickle, was " + afterASecond);

        Ultimates.chargeFromDamage(s, 50);
        assertTrue(s.ultCharge > afterASecond * 3,
                "fighting fills it far faster than waiting");

        // It never overfills, and only a full meter is ready.
        for (int i = 0; i < 500; i++) Ultimates.charge(s, 1.0);
        assertEquals(1.0, s.ultCharge, 0.0001);
        assertTrue(Ultimates.ready(s));
    }

    @Test
    void aCharacterWithoutAnUltimateNeverCharges() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        PlayerState s = caster(Ultimates.NONE, lvl);
        s.ultCharge = 0;
        Ultimates.charge(s, 10);
        Ultimates.chargeFromDamage(s, 500);
        assertEquals(0, s.ultCharge, 0.0001);
        assertFalse(Ultimates.ready(s));

        // Switching it off has the same effect without losing the choice.
        PlayerState off = caster("nova_burst", lvl);
        off.ultimateEnabled = false;
        off.ultCharge = 0;
        Ultimates.charge(off, 10);
        assertEquals(0, off.ultCharge, 0.0001);
        assertEquals("nova_burst", off.ultimateKey, "the choice is kept, just disabled");
    }

    @Test
    void firingSpendsTheWholeMeter() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        World world = new World(lvl);
        PlayerState s = caster("nova_burst", lvl);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);

        assertTrue(world.useUltimate(s, s.x + 100, s.y, profile));
        assertEquals(0, s.ultCharge, 0.0001, "casting spends the meter");
        assertFalse(world.useUltimate(s, s.x + 100, s.y, profile),
                "an empty meter can't fire again");
    }

    // --- the effects ---------------------------------------------------------------

    @Test
    void novaBurstDamagesEverythingAroundTheCasterInEveryPerspective() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = arena(format);
            World world = new World(lvl);
            GameProfile profile = profileFor(lvl.perspective);
            PlayerState s = caster("nova_burst", lvl);

            Mob near = world.spawnMob("slime", s.x + 40, s.y);
            Mob far = world.spawnMob("slime", s.x + 900, s.y);
            assertNotNull(near);
            assertNotNull(far);
            double nearStart = near.health, farStart = far.health;

            assertTrue(world.useUltimate(s, s.x, s.y, profile),
                    "the nova fires in " + format);
            assertTrue(near.health < nearStart,
                    "the nearby mob is hurt in " + format);
            assertEquals(farStart, far.health, 0.001,
                    "a mob outside the blast is untouched in " + format);
        }
    }

    @Test
    void overdriveIsSustainedAndBuffsSpeedDamageAndStamina() {
        Level lvl = arena(LevelFormat.TOP_DOWN);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);
        PlayerState s = caster("overdrive", lvl);

        assertTrue(world.useUltimate(s, s.x, s.y, profile));
        assertTrue(s.ultActive > 0, "Overdrive keeps running after it fires");
        assertEquals("overdrive", s.ultActiveKey);
        assertTrue(s.ultSpeedFactor > 1.0, "it makes you faster");
        assertTrue(s.ultDamageFactor > 1.0, "…and hit harder");
        assertTrue(s.ultTireless, "…and tireless");

        // It lapses on its own, putting every modifier back to neutral.
        for (int i = 0; i < 600; i++) {
            Ultimates.charge(s, 1 / 60.0);
            Ultimates.applyActiveEffects(s);
        }
        assertEquals(0, s.ultActive, 0.0001);
        assertEquals(1.0, s.ultSpeedFactor, 0.0001);
        assertEquals(1.0, s.ultDamageFactor, 0.0001);
        assertFalse(s.ultTireless);
    }

    @Test
    void bulwarkSoaksDamageAndHealsWhileItRuns() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);
        PlayerState s = caster("bulwark", lvl);

        assertTrue(world.useUltimate(s, s.x, s.y, profile));
        assertTrue(s.ultDamageResist > 0.5, "most incoming damage is shrugged off");

        // Damage routed through hurt() is reduced by exactly that fraction.
        double before = s.health;
        s.hurt(50);
        double taken = before - s.health;
        assertEquals(50 * (1 - s.ultDamageResist), taken, 0.001);

        // And it regenerates while it runs.
        s.health = 10;
        for (int i = 0; i < 60; i++) world.step(1 / 60.0, List.of(s), profile);
        assertTrue(s.health > 10, "Bulwark heals over its duration, was " + s.health);
    }

    @Test
    void blinkStrikeMovesTheCasterTowardTheAimAndHurtsWhatItPassesThrough() {
        Level lvl = arena(LevelFormat.ISOMETRIC);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.ISOMETRIC);
        PlayerState s = caster("blink_strike", lvl);
        double startX = s.x;

        Mob inTheWay = world.spawnMob("slime", s.x + 90, s.y);
        assertNotNull(inTheWay);
        double mobStart = inTheWay.health;

        assertTrue(world.useUltimate(s, s.x + 200, s.y, profile));
        assertTrue(s.x > startX + 50,
                "the caster dashes toward the aim, moved " + (s.x - startX));
        assertTrue(inTheWay.health < mobStart, "everything on the path is cut down");
    }

    @Test
    void timeDilationSlowsEverythingNearbyAndLifeSiphonHealsTheCaster() {
        Level lvl = arena(LevelFormat.TOP_DOWN);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);

        PlayerState slower = caster("time_dilation", lvl);
        Mob victim = world.spawnMob("slime", slower.x + 60, slower.y);
        assertNotNull(victim);
        assertTrue(world.useUltimate(slower, slower.x, slower.y, profile));
        assertTrue(victim.chillTime > 0, "everything nearby crawls");

        PlayerState vampire = caster("life_siphon", lvl);
        vampire.health = 20;
        Mob prey = world.spawnMob("slime", vampire.x + 50, vampire.y);
        assertNotNull(prey);
        double preyStart = prey.health;
        assertTrue(world.useUltimate(vampire, vampire.x, vampire.y, profile));
        for (int i = 0; i < 60; i++) world.step(1 / 60.0, List.of(vampire), profile);
        assertTrue(vampire.health > 20, "the drain heals the caster");
        assertTrue(prey.health < preyStart, "…at the prey's expense");
    }

    @Test
    void anOffensiveUltimateWaitsRatherThanBurningItselfWithCombatOff() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);
        profile.combatEnabled = false;
        PlayerState s = caster("nova_burst", lvl);

        assertFalse(world.useUltimate(s, s.x, s.y, profile),
                "a peaceful game type has nothing for it to do");
        assertEquals(1.0, s.ultCharge, 0.0001, "…so the meter is kept, not spent");
    }

    @Test
    void dyingClearsARunningUltimate() {
        Level lvl = arena(LevelFormat.SIDE_SCROLLER);
        World world = new World(lvl);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);
        profile.itemsEnabled = false;
        PlayerState s = caster("overdrive", lvl);

        assertTrue(world.useUltimate(s, s.x, s.y, profile));
        assertTrue(s.ultActive > 0);
        s.health = -1;
        world.step(1 / 60.0, List.of(s), profile);
        assertEquals(0, s.ultActive, 0.0001, "respawning ends the effect");
        assertEquals(1.0, s.ultSpeedFactor, 0.0001);
        assertEquals(s.maxHealth, s.health, 0.001, "…and restores the character's pools");
    }
}
