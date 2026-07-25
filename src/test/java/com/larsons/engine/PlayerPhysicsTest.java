package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic movement step shared by single-player, client prediction,
 * and the server (requirement #3). Determinism is the property everything
 * else relies on, so it gets its own test.
 */
class PlayerPhysicsTest {

    private static final double DT = 1.0 / 60.0;

    /** 10x6 tiles, solid floor on the bottom row, 32px tiles. */
    private static Level floorLevel() {
        return LevelLoader.parse("""
                {
                  "tileSize": 32,
                  "tiles": [
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [1,1,1,1,1,1,1,1,1,1]
                  ],
                  "spawn": { "x": 64, "y": 96 }
                }
                """);
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("physics-test");
        p.gravityEnabled = true;
        p.playerSize = 32;
        return p;
    }

    @Test
    void gravityPullsThePlayerOntoTheFloorAndStops() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 0);

        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 300; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        // Floor row starts at y=160; a 32px player rests at y=128.
        assertEquals(128, s.y, 0.001, "player should land exactly on the floor");
        assertEquals(0, s.vy, 0.001, "vertical velocity should be zero at rest");
    }

    @Test
    void jumpLaunchesUpAndLandsBack() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 128); // standing on the floor

        PlayerInput jump = new PlayerInput(false, false, true, false, 1);
        PlayerPhysics.step(s, jump, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < 0, "jump should set upward velocity");
        assertTrue(s.y < 128, "player should leave the ground");

        PlayerInput idle = new PlayerInput();
        double peak = s.y;
        for (int i = 0; i < 300; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
            peak = Math.min(peak, s.y);
        }
        assertTrue(peak < 60, "jump should gain meaningful height, peaked at " + peak);
        assertEquals(128, s.y, 0.001, "player should land back on the floor");
    }

    @Test
    void horizontalMovementMatchesSpeedAndSetsFacing() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 128);

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        PlayerPhysics.step(s, right, level, p, Perspective.SIDE_SCROLL, DT);
        assertEquals(64 + PlayerPhysics.SPEED * DT, s.x, 1e-9);
        assertTrue(!s.facingLeft && s.moving);

        PlayerInput left = new PlayerInput(true, false, false, false, 2);
        PlayerPhysics.step(s, left, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.facingLeft);
    }

    @Test
    void topDownMovesFreelyOnBothAxesWithoutGravity() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        PlayerPhysics.step(s, right, level, p, Perspective.TOP_DOWN, DT);
        assertEquals(64 + PlayerPhysics.SPEED * DT, s.x, 1e-9);
        assertEquals(64, s.y, 1e-9);

        PlayerInput up = new PlayerInput(false, false, true, false, 2);
        PlayerPhysics.step(s, up, level, p, Perspective.TOP_DOWN, DT);
        assertEquals(64 - PlayerPhysics.SPEED * DT, s.y, 1e-9, "up walks on the plane");
        assertEquals(0, s.vy, 1e-9, "no gravity in top-down");
    }

    /**
     * A diagonal on the plane covers the same distance per second as an axis
     * does: without normalizing, moving up-right in a top-down or isometric
     * level travelled √2 times as fast as moving right.
     */
    @Test
    void topDownDiagonalsAreNotFasterThanTheAxes() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);

        PlayerInput upRight = new PlayerInput(false, true, true, false, 1);
        PlayerPhysics.step(s, upRight, level, p, Perspective.TOP_DOWN, DT);

        double dx = s.x - 64, dy = s.y - 64;
        assertEquals(PlayerPhysics.SPEED * DT, Math.hypot(dx, dy), 1e-9,
                "diagonal step covers one step's distance");
        assertEquals(dx, -dy, 1e-9, "and splits it evenly between the axes");
    }

    /**
     * Sprinting is a plan-view act in every direction: holding shift while
     * walking "north" (which a side-scroller has no equivalent of) both speeds
     * the player up and spends stamina.
     */
    @Test
    void topDownSprintAppliesToVerticalMovement() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);
        double stamina = s.stamina;

        PlayerInput sprintUp = new PlayerInput(false, false, true, false, 1);
        sprintUp.sprint = true;
        PlayerPhysics.step(s, sprintUp, level, p, Perspective.TOP_DOWN, DT);

        assertEquals(64 - PlayerPhysics.SPEED * PlayerPhysics.SPRINT_FACTOR * DT, s.y, 1e-9);
        assertTrue(s.stamina < stamina, "sprinting north spends stamina");
    }

    @Test
    void movementIsClampedToTheLevelBounds() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 0, 64);

        PlayerInput left = new PlayerInput(true, false, false, false, 1);
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(s, left, level, p, Perspective.TOP_DOWN, DT);
        }
        assertEquals(0, s.x, 1e-9, "left edge clamps");

        PlayerInput right = new PlayerInput(false, true, false, false, 2);
        for (int i = 0; i < 600; i++) {
            PlayerPhysics.step(s, right, level, p, Perspective.TOP_DOWN, DT);
        }
        assertEquals(10 * 32 - 32, s.x, 1e-9, "right edge clamps to width - playerSize");
    }

    @Test
    void identicalInputsProduceIdenticalTrajectories() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState a = new PlayerState(1, "a", 64, 0);
        PlayerState b = new PlayerState(2, "b", 64, 0);

        for (int i = 0; i < 240; i++) {
            PlayerInput in = new PlayerInput(i % 60 < 30, i % 60 >= 30, i % 90 == 0, false, i);
            PlayerPhysics.step(a, in, level, p, Perspective.SIDE_SCROLL, DT);
            PlayerPhysics.step(b, in, level, p, Perspective.SIDE_SCROLL, DT);
            assertEquals(a.x, b.x, 0.0, "x must be bit-identical at step " + i);
            assertEquals(a.y, b.y, 0.0, "y must be bit-identical at step " + i);
            assertEquals(a.vy, b.vy, 0.0, "vy must be bit-identical at step " + i);
        }
    }
}
