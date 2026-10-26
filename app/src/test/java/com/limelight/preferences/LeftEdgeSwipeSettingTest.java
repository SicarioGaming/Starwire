package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.RepoFiles;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Moonlight X's Left edge swipe action setting, which Moonlight X keeps on a settings screen
 * Starwire doesn't have. Here it's in the Overlay Menu section of the one settings screen. It's
 * Disabled by default, unlike in Moonlight X: a slow drag from near the edge, like moving the
 * cursor, opened the brightness slider, and the stream menu's Brightness tile opens it anyway.
 * What the stream does with each choice is checked in GameBrightnessTest.
 */
@RunWith(RobolectricTestRunner.class)
// Reading the stream's settings uses MoonBridge's audio layouts, and MoonBridge loads the native
// streaming library in its static block. Instrumenting it lets Robolectric skip
// loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class LeftEdgeSwipeSettingTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private final Context context = RuntimeEnvironment.getApplication();

    private static Element setting() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList all = factory.newDocumentBuilder()
                .parse(RepoFiles.file("app/src/main/res/xml/preferences.xml"))
                .getElementsByTagName("ListPreference");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.getAttributeNS(ANDROID, "key").equals("left_edge_swipe_action")) {
                return e;
            }
        }
        return null;
    }

    @Test
    public void theOverlayMenuSectionHasItDisabledByDefault() throws Exception {
        Element setting = setting();
        assertNotNull("no left_edge_swipe_action on the settings screen", setting);
        assertEquals("category_overlay_menu", ((Element) setting.getParentNode()).getAttributeNS(ANDROID, "key"));
        // What the settings screen saves for a new install, and shows when nothing is saved
        assertEquals("off", setting.getAttributeNS(ANDROID, "defaultValue"));
        assertEquals("@string/title_left_edge_swipe_action", setting.getAttributeNS(ANDROID, "title"));
        assertEquals("@array/left_edge_swipe_action_names", setting.getAttributeNS(ANDROID, "entries"));
        assertEquals("@array/left_edge_swipe_action_values", setting.getAttributeNS(ANDROID, "entryValues"));
    }

    @Test
    public void itOffersTheMenuTheSliderOrNothing() {
        assertArrayEquals(new String[] {"overlay_menu", "brightness", "off"},
                context.getResources().getStringArray(R.array.left_edge_swipe_action_values));
        assertArrayEquals(new String[] {"Overlay menu", "Brightness adjustment", "Disabled"},
                context.getResources().getStringArray(R.array.left_edge_swipe_action_names));
    }

    @Test
    public void theStreamTakesItAsDisabledWhenNothingIsSaved() {
        // As for someone coming from 0.2.1, which didn't have the setting, or anyone who never
        // opened it: the stream reads its own default, not the settings screen's
        assertEquals("off", PreferenceConfiguration.readPreferences(context).leftEdgeSwipeAction);
    }

    @Test
    public void aNewInstallSavesDisabled() {
        // What PcView does when Starwire first opens
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false);
        assertEquals("off", PreferenceManager.getDefaultSharedPreferences(context)
                .getString("left_edge_swipe_action", null));
        assertEquals("off", PreferenceConfiguration.readPreferences(context).leftEdgeSwipeAction);
    }

    @Test
    public void aChoiceSomeoneMadeIsKept() {
        for (String choice : new String[] {"brightness", "overlay_menu", "off"}) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString("left_edge_swipe_action", choice).commit();
            // The new install's defaults don't write over it either
            PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
            assertEquals(choice, PreferenceConfiguration.readPreferences(context).leftEdgeSwipeAction);
        }
    }
}
