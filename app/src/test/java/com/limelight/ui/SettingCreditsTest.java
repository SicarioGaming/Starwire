package com.limelight.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The credit line a setting shows under the Predicted impact panel, for what came from another
 * Moonlight version. CoverGlowTest checks more of them on a device.
 */
public class SettingCreditsTest {
    @Test
    public void theTrackpadSettingCreditsMoonlightXForTheLeftEdgeSlider() {
        // As a trackpad, the left edge opens Moonlight X's brightness slider
        String credit = CoverGlowSettings.creditFor("checkbox_touchscreen_trackpad");
        assertNotNull(credit);
        assertTrue(credit, credit.contains("brightness slider"));
        assertTrue(credit, credit.contains("Moonlight X by MoreOrLessSoftware"));
    }
}
