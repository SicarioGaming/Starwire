package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The credit line a setting shows under the Predicted impact panel, for what came from another
 * Moonlight version. CoverGlowTest checks more of them on a device.
 */
public class SettingCreditsTest {
    @Test
    public void theLeftEdgeSwipeSettingCreditsMoonlightX() {
        assertEquals("From Moonlight X by MoreOrLessSoftware", CoverGlowSettings.creditFor("left_edge_swipe_action"));
    }

    @Test
    public void theTrackpadSettingIsMoonlightsOwnAgain() {
        // It used to credit Moonlight X for the left edge slider, which now has a setting of its own
        // and works in the other touch modes too
        assertNull(CoverGlowSettings.creditFor("checkbox_touchscreen_trackpad"));
    }
}
