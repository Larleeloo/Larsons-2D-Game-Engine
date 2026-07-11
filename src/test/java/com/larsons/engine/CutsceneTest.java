package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutsceneDirector;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the creative mode's triggerable cutscenes: sprite-sheet
 * animation states, the sequential step player (show/say/move/anim/wait/
 * camera/hide), the trigger director (zone / interact / level start, once vs
 * re-armed), and level serialization round-trips.
 */
class CutsceneTest {

    // --- sprite-sheet animation states ------------------------------------------

    @Test
    void sheetAnimLoopsOrClampsItsFrames() {
        Cutscene.SheetAnim loop = new Cutscene.SheetAnim("walk.png", 32, 32, 4, 10, true);
        assertEquals(0, loop.frameAt(0));
        assertEquals(1, loop.frameAt(0.1));
        assertEquals(3, loop.frameAt(0.39));
        assertEquals(0, loop.frameAt(0.4), "looping wraps to the first frame");

        Cutscene.SheetAnim once = new Cutscene.SheetAnim("wave.png", 32, 32, 4, 10, false);
        assertEquals(3, once.frameAt(0.39));
        assertEquals(3, once.frameAt(5.0), "one-shot holds the last frame");

        Cutscene.SheetAnim still = new Cutscene.SheetAnim("s.png", 32, 32, 4, 0, true);
        assertEquals(0, still.frameAt(9.9), "0 fps = static frame 0");
    }

    @Test
    void actorStateFallsBackToIdleThenAnyState() {
        Cutscene.Actor a = new Cutscene.Actor("guide", "Guide", 48);
        a.states.put("walk", new Cutscene.SheetAnim("walk.png", 32, 32, 4, 10, true));
        assertEquals("walk.png", a.state("talk").sheet(), "no idle: any state serves");
        a.states.put("idle", new Cutscene.SheetAnim("idle.png", 32, 32, 2, 4, true));
        assertEquals("idle.png", a.state("talk").sheet(), "unknown states fall back to idle");
        assertEquals("walk.png", a.state("walk").sheet());
    }

    // --- the step player ----------------------------------------------------------

    private static Cutscene scripted() {
        Cutscene cs = new Cutscene("cs1", "Intro");
        Cutscene.Actor guide = new Cutscene.Actor("guide", "The Guide", 48);
        guide.states.put("idle", new Cutscene.SheetAnim("idle.png", 32, 32, 2, 4, true));
        guide.states.put("walk", new Cutscene.SheetAnim("walk.png", 32, 32, 4, 10, true));
        guide.states.put("talk", new Cutscene.SheetAnim("talk.png", 32, 32, 3, 6, true));
        guide.states.put("wave", new Cutscene.SheetAnim("wave.png", 32, 32, 5, 8, false));
        cs.actors.add(guide);
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SHOW, "guide", "", 100, 200, 0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "guide", "Welcome!", 0, 0, 1.0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.MOVE, "guide", "", 300, 200, 2.0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.ANIM, "guide", "wave", 0, 0, 0.5));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.WAIT, "", "", 0, 0, 0.5));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.CAMERA, "", "", 640, 480, 1.0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.HIDE, "guide", "", 0, 0, 0));
        return cs;
    }

    @Test
    void playerRunsTheScriptInOrder() {
        CutscenePlayer p = new CutscenePlayer(scripted(), 50, 60);
        CutscenePlayer.ActorView guide = p.actor("guide");
        assertNotNull(guide);

        // SHOW applies immediately at construction.
        assertTrue(guide.visible);
        assertEquals(100, guide.x);
        assertEquals("idle", guide.state);
        // The camera holds where playback started until a CAMERA step.
        assertEquals(50, p.cameraX());

        // Into the SAY: caption + speaker + talk state.
        p.update(0.1);
        assertEquals("Welcome!", p.caption());
        assertEquals("The Guide", p.speaker());
        assertEquals("talk", guide.state);

        // SAY ends after 1.0s; MOVE interpolates and plays the walk state.
        p.update(1.0); // t = 1.1 → 0.1s into the MOVE
        assertEquals("", p.caption());
        assertEquals("walk", guide.state);
        assertEquals(110, guide.x, 1e-6, "10% of the way to 300");
        assertFalse(guide.facingLeft, "moving right faces right");

        // MOVE completes; ANIM holds the one-shot wave for 0.5s.
        p.update(2.0); // t = 3.1 → 0.1s into the ANIM
        assertEquals(300, guide.x, 1e-6);
        assertEquals("wave", guide.state);

        // WAIT passes; CAMERA pans halfway.
        p.update(1.4); // t = 4.5 → 0.5s per: wait done, camera halfway
        assertEquals("wave", guide.state, "ANIM leaves its state in place");
        assertEquals((50 + 640) / 2.0, p.cameraX(), 1.0);

        // CAMERA lands, HIDE applies, script ends.
        p.update(0.6);
        assertEquals(640, p.cameraX(), 1e-6);
        assertFalse(guide.visible);
        assertTrue(p.finished());
    }

    @Test
    void moveRestoresThePreMoveStateAndFacing() {
        Cutscene cs = new Cutscene("cs", "c");
        Cutscene.Actor a = new Cutscene.Actor("a", "A", 32);
        a.states.put("idle", new Cutscene.SheetAnim("i.png", 16, 16, 1, 0, true));
        a.states.put("walk", new Cutscene.SheetAnim("w.png", 16, 16, 2, 8, true));
        cs.actors.add(a);
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SHOW, "a", "", 200, 0, 0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.MOVE, "a", "", 100, 0, 1.0));
        CutscenePlayer p = new CutscenePlayer(cs, 0, 0);
        p.update(0.5);
        CutscenePlayer.ActorView view = p.actor("a");
        assertEquals("walk", view.state);
        assertTrue(view.facingLeft, "moving left faces left");
        p.update(1.0);
        assertEquals(100, view.x, 1e-6);
        assertEquals("idle", view.state, "walk state restores after the move");
        assertTrue(p.finished());
    }

    @Test
    void skipAppliesEveryRemainingEffect() {
        CutscenePlayer p = new CutscenePlayer(scripted(), 50, 60);
        p.update(0.2); // somewhere inside the SAY
        p.skip();
        assertTrue(p.finished());
        assertEquals("", p.caption());
        CutscenePlayer.ActorView guide = p.actor("guide");
        assertFalse(guide.visible, "the final HIDE applied");
        assertEquals(300, guide.x, 1e-6, "the MOVE completed");
        assertEquals(640, p.cameraX(), 1e-6, "the camera pan completed");
    }

    @Test
    void sayWithoutDurationUsesTheDefaultHold() {
        Cutscene cs = new Cutscene("cs", "c");
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "", "Narration.", 0, 0, 0));
        CutscenePlayer p = new CutscenePlayer(cs, 0, 0);
        p.update(CutscenePlayer.DEFAULT_SAY_SECONDS - 0.1);
        assertEquals("Narration.", p.caption());
        assertEquals("", p.speaker(), "no actor: plain narration");
        p.update(0.2);
        assertTrue(p.finished());
    }

    // --- the trigger director -------------------------------------------------------

    private static Cutscene zone(String key, double x, double y, boolean once) {
        Cutscene cs = new Cutscene(key, key);
        cs.trigger = Cutscene.Trigger.ZONE;
        cs.x = x;
        cs.y = y;
        cs.radiusTiles = 2;
        cs.once = once;
        cs.steps.add(new Cutscene.Step(Cutscene.Op.WAIT, "", "", 0, 0, 1.0));
        return cs;
    }

    @Test
    void levelStartCutsceneFiresImmediatelyAndOnlyOnce() {
        Cutscene cs = new Cutscene("start", "Start");
        cs.trigger = Cutscene.Trigger.LEVEL_START;
        cs.once = false; // even re-triggerable level-start plays once per run
        cs.steps.add(new Cutscene.Step(Cutscene.Op.WAIT, "", "", 0, 0, 1.0));
        CutsceneDirector d = new CutsceneDirector(List.of(cs));
        assertSame(cs, d.checkTriggers(0, 0, false, 32, 0, 0));
        assertNotNull(d.active());
        d.skip();
        assertNull(d.checkTriggers(0, 0, false, 32, 0, 0));
    }

    @Test
    void zoneTriggerFiresOnEntryAndOncePerRun() {
        Cutscene cs = zone("z", 320, 320, true);
        CutsceneDirector d = new CutsceneDirector(List.of(cs));
        assertNull(d.checkTriggers(1000, 1000, false, 32, 0, 0), "far away: nothing");
        assertSame(cs, d.checkTriggers(330, 330, false, 32, 0, 0), "inside 2 tiles: fires");
        assertNull(d.checkTriggers(330, 330, false, 32, 0, 0), "one active at a time");
        d.skip();
        assertNull(d.checkTriggers(330, 330, false, 32, 0, 0), "once = never again");
        assertNull(d.checkTriggers(1000, 1000, false, 32, 0, 0));
        assertNull(d.checkTriggers(330, 330, false, 32, 0, 0));
    }

    @Test
    void reTriggerableZoneReArmsAfterLeaving() {
        Cutscene cs = zone("z", 320, 320, false);
        CutsceneDirector d = new CutsceneDirector(List.of(cs));
        assertSame(cs, d.checkTriggers(320, 320, false, 32, 0, 0));
        d.skip();
        assertNull(d.checkTriggers(322, 320, false, 32, 0, 0),
                "still standing inside: doesn't loop");
        assertNull(d.checkTriggers(1000, 320, false, 32, 0, 0), "stepped out");
        assertSame(cs, d.checkTriggers(320, 320, false, 32, 0, 0), "re-entry re-fires");
    }

    @Test
    void interactTriggerNeedsThePressInsideTheRadius() {
        Cutscene cs = new Cutscene("talk", "Talk");
        cs.trigger = Cutscene.Trigger.INTERACT;
        cs.x = 100;
        cs.y = 100;
        cs.radiusTiles = 1;
        cs.steps.add(new Cutscene.Step(Cutscene.Op.WAIT, "", "", 0, 0, 0.5));
        CutsceneDirector d = new CutsceneDirector(List.of(cs));
        assertNull(d.checkTriggers(105, 100, false, 32, 0, 0), "near but no press");
        assertNull(d.checkTriggers(500, 100, true, 32, 0, 0), "press but far");
        assertSame(cs, d.checkTriggers(105, 100, true, 32, 0, 0));
    }

    @Test
    void directorAdvancesAndClearsTheActivePlayback() {
        Cutscene cs = zone("z", 0, 0, true);
        CutsceneDirector d = new CutsceneDirector(List.of(cs));
        d.checkTriggers(0, 0, false, 32, 5, 6);
        assertNotNull(d.active());
        assertEquals(5, d.active().cameraX(), "camera holds where playback started");
        d.advance(0.4);
        assertNotNull(d.active(), "the 1s WAIT is still running");
        d.advance(0.7);
        assertNull(d.active(), "finished playback clears");
    }

    // --- level serialization ----------------------------------------------------------

    @Test
    void cutscenesRoundTripThroughLevelJson() {
        Level lvl = Level.empty("cut", 20, 12, 32);
        Cutscene cs = new Cutscene("cs1", "Intro");
        cs.trigger = Cutscene.Trigger.INTERACT;
        cs.x = 96;
        cs.y = 128;
        cs.radiusTiles = 3;
        cs.once = false;
        Cutscene.Actor guide = new Cutscene.Actor("guide", "The Guide", 64);
        guide.states.put("idle", new Cutscene.SheetAnim("sheets/idle.png", 24, 32, 6, 10, true));
        guide.states.put("wave", new Cutscene.SheetAnim("sheets/wave.png", 24, 32, 8, 12, false));
        cs.actors.add(guide);
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SHOW, "guide", "wave", 96, 128, 0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "guide", "Hi there!", 0, 0, 2.0));
        lvl.cutscenes.add(cs);

        Level loaded = LevelLoader.parse(lvl.toJson());
        assertEquals(1, loaded.cutscenes.size());
        Cutscene back = loaded.cutscenes.get(0);
        assertEquals("cs1", back.key);
        assertEquals("Intro", back.name);
        assertEquals(Cutscene.Trigger.INTERACT, back.trigger);
        assertEquals(96, back.x);
        assertEquals(128, back.y);
        assertEquals(3, back.radiusTiles);
        assertFalse(back.once);

        assertEquals(1, back.actors.size());
        Cutscene.Actor a = back.actors.get(0);
        assertEquals("guide", a.key);
        assertEquals("The Guide", a.name);
        assertEquals(64, a.sizePx);
        assertEquals(2, a.states.size());
        Cutscene.SheetAnim idle = a.states.get("idle");
        assertEquals("sheets/idle.png", idle.sheet());
        assertEquals(24, idle.frameWidth());
        assertEquals(32, idle.frameHeight());
        assertEquals(6, idle.frameCount());
        assertEquals(10, idle.fps());
        assertTrue(idle.loop());
        assertFalse(a.states.get("wave").loop());

        assertEquals(2, back.steps.size());
        assertEquals(Cutscene.Op.SHOW, back.steps.get(0).op());
        assertEquals("wave", back.steps.get(0).text());
        assertEquals(Cutscene.Op.SAY, back.steps.get(1).op());
        assertEquals("Hi there!", back.steps.get(1).text());
        assertEquals(2.0, back.steps.get(1).seconds());
    }

    @Test
    void malformedCutsceneDataLoadsWithSafeDefaults() {
        Cutscene cs = Cutscene.fromMap(java.util.Map.of(
                "trigger", "NOT_A_TRIGGER",
                "steps", List.of(java.util.Map.of("op", "NOT_AN_OP"))));
        assertEquals(Cutscene.Trigger.ZONE, cs.trigger, "unknown trigger → zone default");
        assertEquals(1, cs.steps.size());
        assertEquals(Cutscene.Op.WAIT, cs.steps.get(0).op(), "unknown op → harmless wait");

        // A level without cutscenes still parses (backwards compatibility).
        Level plain = LevelLoader.parse(Level.empty("p", 8, 8, 32).toJson());
        assertTrue(plain.cutscenes.isEmpty());
    }

    // --- off-screen rendering ---------------------------------------------------------

    @Test
    void painterDrawsActorsAndOverlayOffScreen() {
        CutscenePlayer p = new CutscenePlayer(scripted(), 100, 200);
        p.update(0.2); // inside the SAY: caption + letterbox on screen

        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Camera cam = new Camera(Perspective.SIDE_SCROLL, 320, 240);
        cam.tileSize = 32;
        cam.centerOn(100, 200); // the guide's anchor lands mid-screen
        CutscenePainter.drawActors(g, cam, p);
        CutscenePainter.drawOverlay(g, 320, 240, p);
        g.dispose();

        assertEquals(0xFF000000, img.getRGB(1, 1), "top letterbox bar is black");
        assertEquals(0xFF000000, img.getRGB(1, 239), "bottom letterbox bar is black");
        // The actor (48 px, feet at screen center) put pixels above the anchor
        // — its missing sheets fall back to the procedural stand-in figure.
        boolean actorPixels = false;
        for (int y = 72; y < 120 && !actorPixels; y++) {
            for (int x = 136; x < 184; x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    actorPixels = true;
                    break;
                }
            }
        }
        assertTrue(actorPixels, "the placeholder actor rendered");
    }

    @Test
    void unknownActorsInStepsAreIgnoredByThePlayer() {
        Cutscene cs = new Cutscene("cs", "c");
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SHOW, "ghost", "", 0, 0, 0));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.MOVE, "ghost", "", 50, 0, 0.2));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "ghost", "boo", 0, 0, 0.2));
        CutscenePlayer p = new CutscenePlayer(cs, 0, 0);
        p.update(0.1);
        p.update(0.2);
        p.update(0.2);
        assertTrue(p.finished(), "steps naming missing actors just pass through");
    }
}
