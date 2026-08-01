package com.larsons.engine;

import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.DisplayCap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame cap is two decisions: what a level allows (authored, travels with
 * the level) and what this machine should run at (its display, unknowable to
 * the level's author). The level bounds; the display picks inside the bounds.
 */
class DisplayCapTest {

    /** A device reporting {@code hz}; the other fields do not affect the cap. */
    private static DeviceProfile display(int hz) {
        return new DeviceProfile("Test OS", "1.0", "aarch64", 8, 2048,
                "21", "Test", "Metal", 1440, 900, hz, 2.0);
    }

    @Test
    void theDisplayRefreshRateIsUsedWhenTheLevelAllowsIt() {
        assertEquals(60, DisplayCap.forDisplay(display(60), 30, 144));
        assertEquals(120, DisplayCap.forDisplay(display(120), 30, 144));
        assertEquals(144, DisplayCap.forDisplay(display(144), 30, 144));
    }

    @Test
    void theLevelsCeilingWins() {
        // A 144 Hz monitor opening a level deliberately capped at 60 runs at
        // 60 — the author's look is not overridden by better hardware.
        assertEquals(60, DisplayCap.forDisplay(display(144), 30, 60));
    }

    @Test
    void theLevelsFloorWins() {
        assertEquals(45, DisplayCap.forDisplay(display(30), 45, 120));
    }

    @Test
    void anUnknownRefreshRateAssumesACommonPanel() {
        // Virtual displays and some Linux drivers report nothing. Guessing high
        // would spend a weak machine's budget on frames nobody sees.
        assertEquals(DisplayCap.ASSUMED_REFRESH_HZ,
                DisplayCap.forDisplay(display(0), 10, 240));
        assertEquals(DisplayCap.ASSUMED_REFRESH_HZ,
                DisplayCap.forDisplay(null, 10, 240));
    }

    @Test
    void crossedBoundsFavourTheFloor() {
        // A floor was set for a reason; a ceiling left at a default should not
        // silently undercut it.
        assertEquals(90, DisplayCap.forDisplay(display(144), 90, 60));
    }

    @Test
    void theCapIsNeverZeroOrNegative() {
        assertTrue(DisplayCap.forDisplay(display(60), 0, 0) >= 1);
        assertTrue(DisplayCap.forDisplay(display(0), -5, -1) >= 1);
    }

    @Test
    void aFixedLevelRateIsHonouredOnEveryMachine() {
        // min == max is how a level asks for one exact rate.
        for (int hz : new int[]{0, 30, 60, 120, 144, 240}) {
            assertEquals(30, DisplayCap.forDisplay(display(hz), 30, 30),
                    "a level pinned to 30 should run at 30 on a " + hz + " Hz panel");
        }
    }

    @Test
    void theMachineNeverRendersFasterThanItsPanel() {
        // The property that motivates the whole thing.
        for (int hz : new int[]{30, 60, 75, 120, 144}) {
            int cap = DisplayCap.forDisplay(display(hz), 10, 240);
            assertEquals(hz, cap, "should track the panel when the level allows it");
        }
    }

    @Test
    void descriptionsNameTheNumberAndItsSource() {
        String text = DisplayCap.describe(display(60), 30, 144);
        assertTrue(text.contains("60 FPS"), text);
        assertTrue(text.contains("60 Hz"), text);
        assertTrue(text.contains("30-144"), text);

        String unknown = DisplayCap.describe(display(0), 30, 144);
        assertTrue(unknown.contains("assuming"), unknown);
    }

    @Test
    void theRuleDescriptionCarriesNoValuesThatCouldGoStale() {
        // A settings form builds its note rows once, so the note must not
        // quote a range the user is about to edit.
        String rule = DisplayCap.describeRule(display(60));
        assertTrue(rule.contains("60 Hz"), rule);
        assertTrue(rule.contains("per machine"), rule);
        assertTrue(rule.contains("clamped into the range above"), rule);
    }

    @Test
    void detectingThisMachineWorksHeadless() {
        int cap = DisplayCap.forThisMachine(30, 120);
        assertTrue(cap >= 30 && cap <= 120, "cap should land in range, was " + cap);
    }
}
