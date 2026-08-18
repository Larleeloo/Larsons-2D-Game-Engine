package com.larsons.engine;

import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.combat.Melee;
import com.larsons.engine.combat.MeleeAction;
import com.larsons.engine.combat.MeleeProfile;
import com.larsons.engine.combat.MeleeProfiles;
import com.larsons.engine.combat.MeleeSounds;
import com.larsons.engine.combat.MeleeSprites;
import com.larsons.engine.combat.MeleeState;
import com.larsons.engine.combat.MeleeStyle;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Melee combat: the five moves every fighter has (swing, parry, lunge, dash,
 * and the held guard), the per-weapon timings they run on, what they do to the
 * world, and the sheets and sounds they resolve.
 *
 * <p>The through-line of these tests is that <em>one</em> machine runs
 * everywhere — the play scene, the creative play-test, the authoritative
 * server and every mob — so what is checked here is what happens in all of
 * them.
 */
@Timeout(30)
class MeleeCombatTest {

    private static final double DT = 1.0 / 60.0;

    /** No drop-in texture pack: the built-in art is the baseline. */
    @BeforeEach
    void withoutATexturePack(@TempDir Path tmp) {
        TexturePack.useDir(tmp.resolve("no-texture-pack").toString());
        MeleeProfiles.reset();
    }

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        TexturePack.useDir("");
        AssetLoader.clearCache();
        MeleeProfiles.reset();
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("Melee Test");
        p.audioEnabled = false;
        p.tileSize = 32;
        p.playerSize = 32;
        return p;
    }

    /** A flat registry-mode level: 40 wide, 8 tall, solid dirt floor. */
    private static Level flatLevel() {
        Level lvl = Level.empty("Flat", 40, 8, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles()[lvl.height - 1][c] = dirt;
        }
        lvl.spawnX = 64;
        lvl.spawnY = 128;
        return lvl;
    }

    private static PlayerState player(double x, double y) {
        PlayerState s = new PlayerState(1, "you", x, y);
        s.facing = Facing.EAST;
        return s;
    }

    // --- the moves themselves ------------------------------------------------------

    @Test
    void everyMoveRunsWindupThenActiveThenRecoveryAndEnds() {
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        for (MeleeAction action : MeleeAction.moves()) {
            if (action.held() || !sword.has(action)) continue;
            MeleeState melee = new MeleeState();
            assertTrue(melee.begin(action, sword, 100), action + " starts");
            MeleeProfile.Move move = sword.move(action);
            assertEquals(MeleeState.Phase.WINDUP, melee.phase(),
                    action + " commits before it does anything");

            stepFor(melee, sword, move.windup() + DT);
            assertEquals(MeleeState.Phase.ACTIVE, melee.phase(),
                    action + " opens its window after the wind-up");

            stepFor(melee, sword, move.active() + DT);
            assertEquals(MeleeState.Phase.RECOVER, melee.phase(),
                    action + " has a tail to be caught in");

            stepFor(melee, sword, move.recover() + DT);
            assertEquals(MeleeAction.NONE, melee.action(), action + " finishes");
            assertFalse(melee.busy());
        }
    }

    private static void stepFor(MeleeState melee, MeleeProfile weapon, double seconds) {
        for (int i = 0; i < Math.ceil(seconds / DT); i++) melee.step(weapon, DT);
    }

    @Test
    void aSwingLandsExactlyOnceHoweverManyTicksItTakes() {
        MeleeProfile hammer = MeleeProfiles.ofKey("war_hammer");
        MeleeState melee = new MeleeState();
        assertTrue(melee.begin(MeleeAction.SWING, hammer, 100));

        int strikes = 0;
        for (int i = 0; i < 600; i++) {
            melee.step(hammer, DT);
            if (melee.pollStrike()) strikes++;
        }
        assertEquals(1, strikes, "one swing, one hit window — never two");
    }

    @Test
    void movesRespectTheirOwnCooldownAndStaminaCost() {
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        MeleeState melee = new MeleeState();
        MeleeProfile.Move dash = sword.move(MeleeAction.DASH);

        assertTrue(melee.begin(MeleeAction.DASH, sword, 100));
        stepFor(melee, sword, dash.duration() + DT);
        assertFalse(melee.begin(MeleeAction.DASH, sword, 100),
                "still cooling down");
        stepFor(melee, sword, dash.cooldown());
        assertTrue(melee.begin(MeleeAction.DASH, sword, 100), "cooldown expired");

        MeleeState broke = new MeleeState();
        assertFalse(broke.begin(MeleeAction.DASH, sword, dash.stamina() - 1),
                "a dash you can't afford doesn't happen");
        assertTrue(broke.begin(MeleeAction.DASH, sword, dash.stamina()));
    }

    @Test
    void startingAMoveSpendsStaminaAndBeingBrokeStopsYou() {
        PlayerState me = player(64, 96);
        MeleeState melee = new MeleeState();
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        double cost = sword.move(MeleeAction.LUNGE).stamina();

        me.stamina = cost + 1;
        assertTrue(Melee.start(me, melee, sword, MeleeAction.LUNGE));
        assertEquals(1, me.stamina, 0.001, "the lunge was paid for");

        melee.reset();
        assertFalse(Melee.start(me, melee, sword, MeleeAction.LUNGE),
                "no stamina, no lunge");
        assertEquals(1, me.stamina, 0.001, "and nothing was charged for it");
    }

    @Test
    void aStaggeredFighterCannotAct() {
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        MeleeState melee = new MeleeState();
        assertTrue(melee.begin(MeleeAction.SWING, sword, 100));
        melee.stagger(MeleeState.PARRY_STAGGER);

        assertTrue(melee.staggered());
        assertEquals(MeleeAction.NONE, melee.action(), "the parried swing is spent");
        assertFalse(melee.begin(MeleeAction.SWING, sword, 100));
        stepFor(melee, sword, MeleeState.PARRY_STAGGER + DT);
        assertFalse(melee.staggered());
        assertTrue(melee.begin(MeleeAction.SWING, sword, 100), "back on your feet");
    }

    // --- the guard stance ----------------------------------------------------------

    @Test
    void theGuardStanceStaysUpUntilItIsReleased() {
        MeleeProfile shield = MeleeProfiles.ofKey("tower_shield");
        MeleeState melee = new MeleeState();

        melee.holdShield(true, shield, 100);
        assertFalse(melee.guarding(), "the shield has to come up first");
        stepFor(melee, shield, shield.move(MeleeAction.SHIELD).windup() + DT);
        assertTrue(melee.guarding());

        // It stays up for as long as the button does — five seconds of it.
        for (int i = 0; i < 300; i++) melee.step(shield, DT);
        assertTrue(melee.guarding(), "a held stance does not time out");

        melee.holdShield(false, shield, 100);
        assertFalse(melee.guarding(), "letting go drops it at once");
        stepFor(melee, shield, shield.move(MeleeAction.SHIELD).recover() + DT);
        assertEquals(MeleeAction.NONE, melee.action());
    }

    @Test
    void aRaisedGuardSoaksItsShareAndAParryCatchesEverything() {
        PlayerState me = player(64, 96);
        me.health = 100;

        // Nothing raised: the blow lands whole.
        assertEquals(20, me.takeBlow(20), 0.001);
        assertEquals(80, me.health, 0.001);

        // Guard up: the shield's fraction comes off.
        me.guarding = true;
        me.guardFraction = 0.5;
        assertEquals(10, me.takeBlow(20), 0.001, "half of it got through");
        assertEquals(70, me.health, 0.001);
        assertEquals(1, me.guardHits, "the guard rang");

        // Parry: caught outright, and it still counts as a ring.
        me.parrying = true;
        assertEquals(0, me.takeBlow(50), 0.001);
        assertEquals(70, me.health, 0.001, "a caught blow does nothing at all");
        assertEquals(2, me.guardHits);

        // Dash frames: avoided without even a clang.
        me.parrying = false;
        me.invulnerable = true;
        assertEquals(0, me.takeBlow(50), 0.001);
        assertEquals(2, me.guardHits, "you dodged it; nothing was blocked");
    }

    @Test
    void aBetterShieldBlocksMore() {
        double wood = MeleeProfiles.ofKey("wooden_shield").blockFraction();
        double tower = MeleeProfiles.ofKey("tower_shield").blockFraction();
        double aegis = MeleeProfiles.ofKey("aegis").blockFraction();
        assertTrue(wood < tower && tower < aegis,
                "rarity buys a better guard: " + wood + " < " + tower + " < " + aegis);
        assertTrue(aegis <= 0.85, "and never all of it");
        assertTrue(MeleeProfiles.fists().blockFraction()
                < MeleeProfiles.ofKey("wooden_shield").blockFraction(),
                "a forearm is not a shield");
    }

    // --- weapons decide how you fight ----------------------------------------------

    @Test
    void everyItemDerivesAFightingStyleFromWhatItPlainlyIs() {
        assertEquals(MeleeStyle.SWORD, MeleeProfiles.styleOf(item("iron_sword")));
        assertEquals(MeleeStyle.DAGGER, MeleeProfiles.styleOf(item("iron_dagger")));
        assertEquals(MeleeStyle.DAGGER, MeleeProfiles.styleOf(item("throwing_knife")));
        assertEquals(MeleeStyle.AXE, MeleeProfiles.styleOf(item("battle_axe")));
        assertEquals(MeleeStyle.SPEAR, MeleeProfiles.styleOf(item("iron_spear")));
        assertEquals(MeleeStyle.HAMMER, MeleeProfiles.styleOf(item("war_hammer")));
        assertEquals(MeleeStyle.SHIELD, MeleeProfiles.styleOf(item("tower_shield")));
        assertEquals(MeleeStyle.STAFF, MeleeProfiles.styleOf(item("wooden_bow")));
        // A pickaxe is a tool even though its name ends in "axe".
        assertEquals(MeleeStyle.TOOL, MeleeProfiles.styleOf(item("iron_pickaxe")));
        assertEquals(MeleeStyle.AXE, MeleeProfiles.styleOf(item("iron_axe")),
                "a woodcutting axe still chops like an axe");
        // Anything that isn't a weapon is a fist with something in it.
        assertEquals(MeleeStyle.FIST, MeleeProfiles.styleOf(item("apple")));
        assertSame(MeleeProfiles.fists(), MeleeProfiles.of(null), "empty hands");
    }

    @Test
    void weaponsDifferInTheWaysThatMatter() {
        MeleeProfile dagger = MeleeProfiles.ofKey("iron_dagger");
        MeleeProfile hammer = MeleeProfiles.ofKey("war_hammer");
        MeleeProfile spear = MeleeProfiles.ofKey("iron_spear");

        assertTrue(dagger.duration(MeleeAction.SWING) < hammer.duration(MeleeAction.SWING),
                "a knife is quicker than a maul");
        assertTrue(spear.reach() > dagger.reach(), "and a spear out-reaches both");
        assertTrue(hammer.knockback() > dagger.knockback(), "weight shows in the shove");
        assertTrue(hammer.arc() > spear.arc(), "a hammer sweeps, a spear pokes");
        assertTrue(spear.move(MeleeAction.LUNGE).damageScale()
                        > spear.move(MeleeAction.SWING).damageScale(),
                "a thrust is worth more than a poke");
    }

    @Test
    void anActionAWeaponLacksSimplyCannotBePerformed() {
        MeleeProfile pick = MeleeProfiles.ofKey("iron_pickaxe");
        assertFalse(pick.has(MeleeAction.LUNGE), "nobody fences with a pickaxe");
        assertTrue(pick.has(MeleeAction.SWING) && pick.has(MeleeAction.DASH));

        MeleeState melee = new MeleeState();
        assertFalse(melee.begin(MeleeAction.LUNGE, pick, 100));
        assertEquals(MeleeAction.NONE, melee.action());
        assertTrue(melee.begin(MeleeAction.SWING, pick, 100));
    }

    @Test
    void aGameTypeCanOverrideHowAnItemFights() {
        assertEquals(MeleeStyle.SWORD, MeleeProfiles.styleOf(item("iron_sword")));
        MeleeProfiles.setStyle("iron_sword", MeleeStyle.SPEAR);
        assertEquals(MeleeStyle.SPEAR, MeleeProfiles.styleOf(item("iron_sword")));
        assertEquals(MeleeStyle.SPEAR.reach(), MeleeProfiles.ofKey("iron_sword").reach(),
                0.001, "and the override is what resolves");

        // …or replace the timings outright.
        MeleeProfile custom = MeleeStyle.HAMMER.profile("iron_sword").withReach(999);
        MeleeProfiles.register(custom);
        assertEquals(999, MeleeProfiles.ofKey("iron_sword").reach(), 0.001);
    }

    private static ItemDef item(String key) {
        ItemDef def = ItemRegistry.standard().get(key);
        assertNotNull(def, key + " is a registered item");
        return def;
    }

    // --- what the moves do to the world --------------------------------------------

    @Test
    void aStrikeReachesAsFarAsTheWeaponSays() {
        World world = new World(flatLevel());
        // Player centre (80, 112); a sheep 100 px due east of it — inside a
        // spear's 86 px reach, well outside a dagger's 46.
        PlayerState me = player(64, 96);
        Mob far = world.spawnMob("sheep", 180 - 14, 112 - 14);
        assertNotNull(far);
        double aimX = 180, aimY = 112;

        MeleeProfile dagger = MeleeProfiles.ofKey("iron_dagger");
        assertFalse(world.meleeStrike(me, dagger, MeleeAction.SWING,
                aimX, aimY, 10).hit(), "a knife cannot reach that");

        MeleeProfile spear = MeleeProfiles.ofKey("iron_spear");
        World.MeleeHit hit = world.meleeStrike(me, spear, MeleeAction.SWING,
                aimX, aimY, 10);
        assertTrue(hit.hit(), "a spear can");
        assertSame(far, hit.mob());
        assertTrue(hit.damage() > 0);
    }

    @Test
    void aStrikeOnlyCatchesWhatIsInsideItsArc() {
        World world = new World(flatLevel());
        // Player centre (216, 112), aiming a short jab due east; a sheep 30 px
        // away but 60° off to the side, close enough to the point of impact
        // that only the weapon's arc decides whether it is caught.
        PlayerState me = player(200, 96);
        Mob beside = world.spawnMob("sheep", 231 - 14, 86 - 14);
        assertNotNull(beside);
        double aimX = 226, aimY = 112;

        MeleeProfile spear = MeleeProfiles.ofKey("iron_spear"); // 55° arc
        assertFalse(world.meleeStrike(me, spear, MeleeAction.SWING,
                        aimX, aimY, 10).hit(),
                "a thrust is too narrow to catch what is off to the side");

        MeleeProfile hammer = MeleeProfiles.ofKey("war_hammer"); // 140° arc
        assertTrue(world.meleeStrike(me, hammer, MeleeAction.SWING,
                        aimX, aimY, 10).hit(),
                "a hammer's sweep takes in the whole neighbourhood");
    }

    @Test
    void aLungeLandsHarderThanASwing() {
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        double swing = Melee.damage(10, sword, MeleeAction.SWING);
        double lunge = Melee.damage(10, sword, MeleeAction.LUNGE);
        assertTrue(lunge > swing, lunge + " should beat " + swing);
    }

    @Test
    void aMobsGuardCanCatchASwingAndLeaveTheAttackerStaggered() {
        World world = new World(flatLevel());
        PlayerState me = player(64, 96);
        // A Royal Guard: shield stance plus a Tower Shield, the best catcher
        // in the game. Its parry roll is seeded, so this is deterministic —
        // swing enough times and one of them is caught.
        Mob guard = world.spawnMob("royal_guard", 64 + 40, 96);
        assertNotNull(guard);
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");

        boolean everParried = false;
        for (int i = 0; i < 200 && !guard.dead(); i++) {
            guard.x = 64 + 40;   // keep it in reach while it tries to close
            guard.y = 96;
            World.MeleeHit hit = world.meleeStrike(me, sword, MeleeAction.SWING,
                    guard.x, guard.y, 1);
            if (hit.parried()) {
                everParried = true;
                assertEquals(0, hit.damage(), 0.001, "a caught blow deals nothing");
                break;
            }
            // The catch has its own cooldown; let it come back round.
            guard.step(world.level, List.of(me), true, true, 0.5);
        }
        assertTrue(everParried, "an armed guard catches blows sometimes");
    }

    @Test
    void aParryTurnsIncomingShotsAround() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        GameProfile p = profile();
        PlayerState me = player(200, 96);
        me.health = 100000; // survive the volley while we wait for one to catch
        Mob archer = world.spawnMob("skeleton_archer", 200 + 150, 96);
        assertNotNull(archer);

        Projectile shot = null;
        for (int i = 0; i < 1200 && shot == null; i++) {
            world.step(DT, List.of(me), p);
            for (Projectile pr : world.projectiles()) {
                if (pr.ownerId < 0) shot = pr;
            }
        }
        assertNotNull(shot, "the archer opened fire");

        // Catch it at the moment it reaches the guard.
        shot.x = me.x + 20;
        shot.y = me.y + 16;
        double before = shot.vx;
        MeleeProfile shield = MeleeProfiles.ofKey("tower_shield");
        assertEquals(1, world.parryProjectiles(me, shield), "the arrow was caught");
        assertEquals(-before, shot.vx, 0.001, "and sent back the way it came");
        assertEquals(me.id, shot.ownerId, "it is ours now");
        assertEquals(0, world.parryProjectiles(me, shield),
                "our own shot is not parried again");
    }

    // --- movement ------------------------------------------------------------------

    @Test
    void aDashCarriesTheFighterAndThenLetsGo() {
        Level lvl = flatLevel();
        GameProfile p = profile();
        p.gravityEnabled = false;
        PlayerState me = player(200, 96);
        MeleeState melee = new MeleeState();
        MeleeProfile sword = MeleeProfiles.ofKey("iron_sword");
        PlayerInput still = new PlayerInput(false, false, false, false, 1);

        assertTrue(Melee.start(me, melee, sword, MeleeAction.DASH));
        double startX = me.x;
        for (int i = 0; i < 60; i++) {
            Melee.step(me, melee, sword, "iron_sword", false, true, DT);
            PlayerPhysics.step(me, still, lvl, p, Perspective.THREE_D, DT);
        }
        assertTrue(me.x > startX + 40,
                "the dash carried us east without a movement key held");
        assertEquals(0, me.dashTime, 0.001, "and it ended on its own");

        double parked = me.x;
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(me, still, lvl, p, Perspective.THREE_D, DT);
        }
        assertEquals(parked, me.x, 0.001, "no lingering momentum");
    }

    @Test
    void aRaisedGuardSlowsYouDown() {
        Level lvl = flatLevel();
        GameProfile p = profile();
        PlayerInput right = new PlayerInput(false, true, false, false, 1);

        PlayerState free = player(64, 96);
        PlayerState guarded = player(64, 96);
        guarded.guarding = true;
        for (int i = 0; i < 30; i++) {
            PlayerPhysics.step(free, right, lvl, p, Perspective.SIDE_SCROLL, DT);
            PlayerPhysics.step(guarded, right, lvl, p, Perspective.SIDE_SCROLL, DT);
        }
        assertTrue(guarded.x < free.x, "behind a shield you are not chasing anybody");
    }

    // --- mobs fight the same way ---------------------------------------------------

    @Test
    void anArmedMobInheritsItsWeaponsTimings() {
        MobDef knight = MobRegistry.standard().get("knight");
        assertNotNull(knight);
        assertTrue(knight.armed());
        assertEquals("iron_sword", knight.weapon());

        MeleeProfile theirs = MeleeProfiles.forMob(knight);
        assertEquals(MeleeStyle.SWORD, theirs.style(), "it fights like the sword it holds");
        assertEquals(MeleeProfiles.ofKey("iron_sword").move(MeleeAction.SWING),
                theirs.move(MeleeAction.SWING),
                "the same timings a player gets from the same blade");

        // A bare animal fights with what it has: claws sized to the animal.
        MobDef troll = MobRegistry.standard().get("troll");
        assertFalse(troll.armed());
        assertEquals(MeleeStyle.HAMMER, MeleeProfiles.forMob(troll).style(),
                "a big animal hits like a big thing");
        assertEquals(MeleeStyle.DAGGER, MeleeProfiles.forMob(
                        MobRegistry.standard().get("rabbit")).style(),
                "a fast little one flicks");
    }

    @Test
    void aMobsAttackWindsUpBeforeItLands() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        GameProfile p = profile();
        Mob zombie = world.spawnMob("zombie", 200, 96);
        assertNotNull(zombie);
        PlayerState victim = player(200 + 20, 96);
        victim.health = 100;

        // The tick the AI decides to attack must not also be the tick it hits.
        int startedAt = -1;
        int hitTick = -1;
        for (int i = 0; i < 240 && hitTick < 0; i++) {
            zombie.step(lvl, List.of(victim), true, true, DT);
            if (startedAt < 0 && !zombie.meleeAction().isEmpty()) {
                startedAt = i;
                assertEquals(100, victim.health, 0.001,
                        "the wind-up has not landed anything yet");
            }
            if (victim.health < 100) hitTick = i;
        }
        assertTrue(startedAt >= 0, "the zombie threw a real move");
        assertTrue(hitTick > startedAt,
                "and it landed after the wind-up (" + startedAt + " → " + hitTick + ")");
    }

    @Test
    void aMobsBlowIsCaughtByAnOpenParry() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        Mob zombie = world.spawnMob("zombie", 200, 96);
        assertNotNull(zombie);
        PlayerState victim = player(200 + 20, 96);
        victim.health = 100;
        victim.parrying = true; // the window is open for the whole test

        for (int i = 0; i < 240; i++) zombie.step(lvl, List.of(victim), true, true, DT);
        assertEquals(100, victim.health, 0.001, "nothing got through");
        assertTrue(victim.guardHits > 0, "and something was caught");
    }

    @Test
    void aMobsMeleeMoveRidesTheWire() {
        Level lvl = flatLevel();
        World world = new World(lvl);
        Mob knight = world.spawnMob("knight", 200, 96);
        assertNotNull(knight);
        PlayerState victim = player(200 + 20, 96);
        for (int i = 0; i < 30 && knight.meleeAction().isEmpty(); i++) {
            knight.step(lvl, List.of(victim), true, true, DT);
        }
        assertFalse(knight.meleeAction().isEmpty(), "the knight is mid-move");

        Map<String, Object> wire = knight.toMap();
        EntityView view = EntityView.fromMap(wire);
        assertEquals(knight.meleeAction(), view.meleeAction);
        assertEquals(knight.meleeProgress(), view.meleeProgress, 0.001);
        assertEquals("iron_sword", view.weapon, "clients know what it is holding");
    }

    // --- the wire ------------------------------------------------------------------

    @Test
    void meleeIntentAndStanceSurviveTheWire() {
        PlayerInput in = new PlayerInput(false, true, false, false, 7);
        in.melee = MeleeAction.LUNGE.key();
        in.shield = true;
        PlayerInput back = PlayerInput.fromMap(in.toMap());
        assertEquals("lunge", back.melee);
        assertTrue(back.shield);

        // Absent when nothing is happening, so old messages keep parsing.
        PlayerInput quiet = new PlayerInput(false, false, false, false, 8);
        assertFalse(quiet.toMap().containsKey("ml"));
        assertFalse(quiet.toMap().containsKey("sd"));
        assertEquals("", PlayerInput.fromMap(quiet.toMap()).melee);
    }

    @Test
    void theMeleeStanceIsReplicatedToOtherPlayers() {
        PlayerState me = player(64, 96);
        me.meleeAction = "swing";
        me.meleeProgress = 0.42;
        me.heldKey = "frostmourne";
        me.guarding = true;
        me.guardHits = 3;

        PlayerState back = PlayerState.fromMap(me.toMap());
        assertEquals("swing", back.meleeAction);
        assertEquals(0.42, back.meleeProgress, 0.001);
        assertEquals("frostmourne", back.heldKey);
        assertTrue(back.guarding);
        assertEquals(3, back.guardHits);

        PlayerState quiet = player(64, 96);
        assertFalse(quiet.toMap().containsKey("ma"), "absent while nothing is happening");
        assertFalse(quiet.toMap().containsKey("hk"));
    }

    // --- art: the held object dresses the fighter ------------------------------------

    @Test
    void theHeldObjectBringsItsOwnWielderSheetAndFallsBackToIdle(@TempDir Path dir)
            throws IOException {
        // No wield art at all: the fighter's own sheet resolves as it always did.
        var bare = MeleeSprites.playerFrame("", "iron_sword", "swing", Facing.EAST,
                0, 0.5, 32, Color.BLUE);
        assertNotNull(bare.image(), "there is always something to draw");

        // A single wield/<item> sheet — "holding it, idle" — dresses every move.
        Skins.install(List.of(sheet("wield/iron_sword/idle",
                solid(dir, "wield_idle.png", Color.RED))));
        var idle = MeleeSprites.playerFrame("", "iron_sword", "idle", Facing.EAST,
                0, 0, 32, Color.BLUE);
        var swing = MeleeSprites.playerFrame("", "iron_sword", "swing", Facing.EAST,
                0, 0.5, 32, Color.BLUE);
        assertSame(idle.image(), swing.image(),
                "one sheet is enough: the swing borrows the idle art");
        assertNotSame(bare.image(), swing.image(), "…and it beat the plain player art");

        // Draw the swing and it wins over idle for that move alone.
        Skins.put(sheet("wield/iron_sword/swing",
                solid(dir, "wield_swing.png", Color.GREEN)));
        var drawn = MeleeSprites.playerFrame("", "iron_sword", "swing", Facing.EAST,
                0, 0.5, 32, Color.BLUE);
        assertNotSame(idle.image(), drawn.image(), "the swing sheet took over");
        assertSame(idle.image(), MeleeSprites.playerFrame("", "iron_sword", "parry",
                Facing.EAST, 0, 0.5, 32, Color.BLUE).image(),
                "…and only that move: a parry still borrows idle");

        // Empty-handed goes nowhere near the wield chain.
        assertNotSame(idle.image(), MeleeSprites.playerFrame("", "", "swing",
                Facing.EAST, 0, 0.5, 32, Color.BLUE).image());
    }

    @Test
    void aMobWearsItsWeaponsWielderSheetToo(@TempDir Path dir) throws IOException {
        assertNull(MeleeSprites.mobFrame("knight", "iron_sword", "swing",
                Facing.EAST, 0, 0.5), "no art anywhere: the caller draws its critter");

        Skins.install(List.of(sheet("wield/iron_sword/idle",
                solid(dir, "wield.png", Color.RED))));
        var frame = MeleeSprites.mobFrame("knight", "iron_sword", "swing",
                Facing.EAST, 0, 0.5);
        assertNotNull(frame, "the weapon dresses the mob holding it");

        // A sheet for the species itself beats nothing but loses to the weapon's.
        Skins.put(sheet("mob/knight/swing", solid(dir, "knight.png", Color.CYAN)));
        assertSame(frame.image(), MeleeSprites.mobFrame("knight", "iron_sword", "swing",
                Facing.EAST, 0, 0.5).image(), "what it holds has the first say");
        assertNotSame(frame.image(), MeleeSprites.mobFrame("knight", "", "swing",
                Facing.EAST, 0, 0.5).image(), "bare-handed, the species sheet draws");
    }

    @Test
    void theObjectItselfIsAnimatedPerMoveAndFallsBackToItsIcon(@TempDir Path dir)
            throws IOException {
        assertNull(MeleeSprites.heldFrame("iron_sword", "swing", 0, 0.5),
                "nothing assigned: the caller draws the procedural icon");

        Skins.install(List.of(sheet("item/iron_sword",
                solid(dir, "icon.png", Color.WHITE))));
        var icon = MeleeSprites.heldFrame("iron_sword", "swing", 0, 0.5);
        assertNotNull(icon, "the plain icon stands in for every move");

        Skins.put(sheet("item/iron_sword/swing", solid(dir, "blade.png", Color.YELLOW)));
        assertNotSame(icon, MeleeSprites.heldFrame("iron_sword", "swing", 0, 0.5),
                "…until the move has a sheet of its own");
        assertSame(icon, MeleeSprites.heldFrame("iron_sword", "parry", 0, 0.5),
                "which is only for that move");
    }

    @Test
    void aMoveSheetPlaysExactlyOnceAcrossTheMove(@TempDir Path dir) throws IOException {
        // A four-frame strip: progress 0..1 walks it start to finish.
        Path strip = dir.resolve("strip.png");
        BufferedImage img = new BufferedImage(128, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        for (int i = 0; i < 4; i++) {
            g.setColor(colors[i]);
            g.fillRect(i * 32, 0, 32, 32);
        }
        g.dispose();
        ImageIO.write(img, "png", strip.toFile());
        SkinDef def = new SkinDef("wield/iron_sword/swing", strip.toString(), 32, 32, 4, 10);
        Skins.install(List.of(def));

        assertEquals(colors[0].getRGB(),
                Skins.progressFrame(def.key, 0).getRGB(0, 0), "starts on frame 0");
        assertEquals(colors[3].getRGB(),
                Skins.progressFrame(def.key, 0.99).getRGB(0, 0), "ends on the last");
        assertEquals(colors[3].getRGB(),
                Skins.progressFrame(def.key, 5).getRGB(0, 0), "and clamps past the end");
        assertNull(Skins.progressFrame("wield/nothing/swing", 0.5));
    }

    private static SkinDef sheet(String key, Path file) {
        return new SkinDef(key, file.toString(), 32, 32, 1, 8);
    }

    private static Path solid(Path dir, String name, Color color) throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    // --- the catalogues know about the moves ------------------------------------------

    @Test
    void theArtCatalogueListsEveryMoveAndItsFallbacks() {
        // The graphics side's copy of the move list must not drift from the
        // combat side's — one of them is what the pack is documented with.
        assertEquals(MeleeAction.states(), PlayerSprites.COMBAT_STATES);
        for (String state : PlayerSprites.COMBAT_STATES) {
            assertTrue(PlayerSprites.ACTION_STATES.contains(state),
                    state + " is a skinnable player state");
            assertTrue(PlayerSprites.oneShot(state), state + " plays once");
            assertTrue(MeleeSprites.stateChain(state).contains("idle"),
                    state + " ends its fallback chain at idle");
        }
        assertFalse(PlayerSprites.oneShot("walk"), "walking loops");

        // wield/<item>/<move>/<dir> → items narrowing down to the plain sheet.
        List<String> paths = TextureKeys.paths("wield/iron_sword/swing/e");
        assertEquals(List.of("wield/iron_sword_swing_e", "wield/iron_sword_swing",
                "wield/iron_sword"), paths);
        // …and an item's own move sheet drops back to its icon.
        assertEquals(List.of("items/iron_sword_swing", "items/iron_sword"),
                TextureKeys.paths("item/iron_sword/swing"));
        assertTrue(TextureKeys.folders().contains(TextureKeys.WIELD));

        boolean listed = TextureKeys.all().stream()
                .anyMatch(e -> e.key().equals("wield/iron_sword/idle")
                        && e.states().contains("swing")
                        && e.directions().contains("e"));
        assertTrue(listed, "the pack's key list documents the wielder sheets");
    }

    @Test
    void theSoundCatalogueListsEveryMoveForEveryFighter() {
        assertEquals(MeleeAction.soundStates(), SoundKeys.MELEE_STATES);
        for (String state : SoundKeys.MELEE_STATES) {
            assertTrue(SoundKeys.PLAYER_STATES.contains(state), "player/" + state);
            assertTrue(SoundKeys.ITEM_STATES.contains(state), "item/…/" + state);
            assertTrue(SoundKeys.MOB_STATES.contains(state), "mob/…/" + state);
        }
        assertEquals("items/iron_sword_swing.wav",
                SoundKeys.preferredFile(SoundKeys.item("iron_sword", "swing")));
    }

    @Test
    void aMovesSoundsAreTriedFromTheObjectOutward() {
        String[] keys = MeleeSounds.playerStart("rogue", "iron_sword", MeleeAction.SWING);
        assertEquals(List.of("item/iron_sword/swing", "character/rogue/swing",
                        "player/swing", "character/rogue/attack", "player/attack"),
                List.of(keys));

        // Empty-handed and playing the default character: no phantom keys.
        assertEquals(List.of("player/dash"),
                List.of(MeleeSounds.playerStart("", "", MeleeAction.DASH)));
        // A mob asks for its own voice, then its generic attack.
        assertEquals(List.of("item/battle_axe/swing_hit", "mob/orc/swing_hit",
                        "mob/orc/attack_hit"),
                List.of(MeleeSounds.mobHit("orc", "battle_axe", MeleeAction.SWING)));
        // A dash has nothing to connect with, so it asks for nothing.
        assertEquals(0, MeleeSounds.playerHit("", "", MeleeAction.DASH).length);
    }

    @Test
    void everyMoveHasAKeyAnAnimationStateAndSounds() {
        for (MeleeAction a : MeleeAction.moves()) {
            assertFalse(a.key().isBlank());
            assertEquals(a, MeleeAction.byKey(a.key()));
            assertEquals(a, MeleeAction.byOrdinal(a.ordinal()));
            assertFalse(a.startSound().isBlank(), a + " announces itself");
        }
        assertEquals(MeleeAction.NONE, MeleeAction.byKey("nonsense"));
        assertEquals(MeleeAction.NONE, MeleeAction.byKey(null));
        assertEquals(MeleeAction.NONE, MeleeAction.byOrdinal(99));
        assertEquals("shield_down", MeleeAction.SHIELD.endSound());
        assertEquals("", MeleeAction.SWING.endSound(), "one-shots have nothing to lower");
    }
}
