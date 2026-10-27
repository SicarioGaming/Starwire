package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.limelight.R;
import com.limelight.RepoFiles;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * USB sharing's borrowed parts are credited in all three places together: the setting's credit
 * line (CoverGlowSettings), Settings > About > Credits (AboutCredits) and the README's "Where
 * every feature comes from": the exporter to Moonlight V+ (qiin2333), and usbipdcpp (yunsmall)
 * with its licence, LGPL-3.0.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UsbCreditsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private static void namesBoth(String where, String text) {
        for (String name : new String[] {"Moonlight V+", "qiin2333", "usbipdcpp", "yunsmall", "LGPL"}) {
            assertTrue(where + " doesn't name " + name + ": " + text, text.contains(name));
        }
    }

    @Test
    public void theSettingsCreditLine() throws Exception {
        String key = "checkbox_usb_share";
        assertTrue("the credit is for a setting that isn't there",
                RepoFiles.read("app/src/main/res/xml/preferences.xml").contains("android:key=\"" + key + "\""));
        String credit = CoverGlowSettings.creditFor(key);
        assertNotNull(credit);
        namesBoth("the setting's credit", credit);
    }

    @Test
    public void theAboutCreditsPage() {
        AboutCredits.Credit vplus = null;
        AboutCredits.Credit usbipdcpp = null;
        for (AboutCredits.Credit c : AboutCredits.ALL) {
            if (c.project.equals("Moonlight V+")) {
                vplus = c;
            }
            else if (c.project.equals("usbipdcpp")) {
                usbipdcpp = c;
            }
        }
        assertNotNull(vplus);
        assertNotNull(usbipdcpp);
        assertEquals("qiin2333", vplus.author);
        assertTrue(context.getString(vplus.usedFor).contains("USB exporter"));
        assertEquals("yunsmall", usbipdcpp.author);
        assertEquals(context.getString(R.string.credits_section_usb), context.getString(usbipdcpp.section));
        assertTrue(context.getString(usbipdcpp.usedFor).contains("LGPL"));
    }

    @Test
    public void theReadmesFeatureTable() throws Exception {
        String readme = RepoFiles.read("README.md");
        String features = readme.substring(readme.indexOf("## Where every feature comes from"));
        String row = null;
        for (String line : features.split("\n")) {
            if (line.startsWith("| USB devices (Experimental)")) {
                row = line;
                break;
            }
        }
        assertNotNull("the README's feature table has no USB devices row", row);
        namesBoth("the README's USB devices row", row);
    }
}
