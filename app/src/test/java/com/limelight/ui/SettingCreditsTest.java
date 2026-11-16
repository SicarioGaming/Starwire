package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

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

    // The settings borrowed in 0.3.0 name the same people as their row in the README's "Where
    // every feature comes from". USB devices has its own test (UsbCreditsTest).
    @Test
    public void theDualSenseSettingCreditsVPlusAndNightjar() throws Exception {
        namesTheSamePeople("checkbox_dualsense_effects", "DualSense trigger effects",
                "Moonlight V+", "qiin2333", "Nightjar", "Ramazan Kara");
    }

    @Test
    public void theBackOpensTheMenuSettingCreditsArtemisAndArtemide() throws Exception {
        namesTheSamePeople("checkbox_back_opens_menu", "Back opens the menu",
                "Artemis", "Yukino Song", "Artemide");
    }

    private static void namesTheSamePeople(String key, String feature, String... names) throws Exception {
        assertTrue("the credit is for a setting that isn't there",
                RepoFiles.read("app/src/main/res/xml/preferences.xml").contains("android:key=\"" + key + "\""));
        String credit = CoverGlowSettings.creditFor(key);
        assertNotNull(key + " has no credit line", credit);
        String readme = RepoFiles.read("README.md");
        int start = readme.indexOf("## Where every feature comes from");
        String features = readme.substring(start, readme.indexOf("\n## ", start));
        String row = null;
        for (String line : features.split("\n")) {
            if (line.startsWith("|") && line.contains(feature)) {
                row = line;
                break;
            }
        }
        assertNotNull("the README's feature tables have no " + feature + " row", row);
        for (String name : names) {
            assertTrue(key + "'s credit doesn't name " + name + ": " + credit, credit.contains(name));
            assertTrue("the README's " + feature + " row doesn't name " + name, row.contains(name));
        }
    }
}
