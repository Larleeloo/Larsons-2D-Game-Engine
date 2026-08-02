package com.larsons.engine.demo;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.level.Level;
import com.larsons.engine.minigame.MiniGameView;

import java.awt.Graphics2D;

/**
 * A public door onto {@link MiniGameHud}, which is package-private and should
 * stay that way.
 *
 * <p>The golden-frame harness lives in {@code com.larsons.engine.render} and
 * needs to draw the mini-game HUD. The alternatives were to widen
 * {@code MiniGameHud} to public for a test's benefit — enlarging the engine's
 * API surface to suit its own test suite, which is backwards — or to put the
 * whole harness in {@code demo}, which would bury a general instrument inside
 * one package. A short shim in the test tree is the cheaper of the three.
 *
 * <p>Its parameter types follow {@code MiniGameHud}'s own, so it moves from
 * {@code Graphics2D} to {@code DrawTarget} when B2 ports the HUD, and the
 * harness that calls it does not have to care in between.
 */
public final class MiniGameHudAccess {

    private MiniGameHudAccess() {}

    public static void drawWorld(Graphics2D g, Camera camera, Level level,
                                 MiniGameView view, double animClock) {
        MiniGameHud.drawWorld(g, camera, level, view, animClock);
    }

    public static void drawHud(Graphics2D g, int vw, int vh,
                               MiniGameView view, int localPlayerId) {
        MiniGameHud.drawHud(g, vw, vh, view, localPlayerId);
    }
}
