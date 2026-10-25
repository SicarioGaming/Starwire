package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

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
 * Starwire doesn't have. Here it's in the Overlay Menu section of the one settings screen. What
 * the stream does with each choice is checked in GameBrightnessTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class LeftEdgeSwipeSettingTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

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
    public void theOverlayMenuSectionHasItWithBrightnessByDefault() throws Exception {
        Element setting = setting();
        assertNotNull("no left_edge_swipe_action on the settings screen", setting);
        assertEquals("category_overlay_menu", ((Element) setting.getParentNode()).getAttributeNS(ANDROID, "key"));
        // As in Moonlight X, and as the stream reads it when nothing is saved
        assertEquals("brightness", setting.getAttributeNS(ANDROID, "defaultValue"));
        assertEquals("@string/title_left_edge_swipe_action", setting.getAttributeNS(ANDROID, "title"));
        assertEquals("@array/left_edge_swipe_action_names", setting.getAttributeNS(ANDROID, "entries"));
        assertEquals("@array/left_edge_swipe_action_values", setting.getAttributeNS(ANDROID, "entryValues"));
    }

    @Test
    public void itOffersTheMenuTheSliderOrNothing() {
        Context context = RuntimeEnvironment.getApplication();
        assertArrayEquals(new String[] {"overlay_menu", "brightness", "off"},
                context.getResources().getStringArray(R.array.left_edge_swipe_action_values));
        assertArrayEquals(new String[] {"Overlay menu", "Brightness adjustment", "Disabled"},
                context.getResources().getStringArray(R.array.left_edge_swipe_action_names));
    }
}
