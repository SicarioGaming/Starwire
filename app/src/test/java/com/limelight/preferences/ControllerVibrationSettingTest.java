package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import com.limelight.RepoFiles;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The "Controller vibration strength" row in the settings: under Controllers, a slider from 0 to
 * 100 percent that starts at full strength, the same default the stream uses when nothing is
 * saved (ControllerRumbleStrengthTest).
 */
public class ControllerVibrationSettingTest {
    private static final String KEY = "seekbar_controller_vibration_strength";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private static Element row() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList all = factory.newDocumentBuilder()
                .parse(RepoFiles.file("app/src/main/res/xml/preferences.xml"))
                .getElementsByTagName("*");
        Element row = null;
        int found = 0;
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (KEY.equals(e.getAttributeNS(ANDROID, "key"))) {
                row = e;
                found++;
            }
        }
        assertEquals(KEY + " rows in preferences.xml", 1, found);
        return row;
    }

    @Test
    public void aSliderUnderControllers() throws Exception {
        Element row = row();
        assertEquals("com.limelight.preferences.SeekBarPreference", row.getTagName());
        assertEquals("category_gamepad_settings", ((Element) row.getParentNode()).getAttributeNS(ANDROID, "key"));
        // Not tied to the vibration fallback, so it stays usable with that switched off
        assertEquals("", row.getAttributeNS(ANDROID, "dependency"));
    }

    @Test
    public void zeroToOneHundredPercentStartingAtFull() throws Exception {
        Element row = row();
        assertEquals("100", row.getAttributeNS(ANDROID, "defaultValue"));
        assertEquals("0", row.getAttributeNS(SEEKBAR, "min"));
        assertEquals("100", row.getAttributeNS(ANDROID, "max"));
        assertEquals("@string/title_seekbar_controller_vibration_strength", row.getAttributeNS(ANDROID, "title"));
    }

    // The slider moves in steps of 5 percent
    @Test
    public void movesInStepsOfFive() throws Exception {
        assertEquals("5", row().getAttributeNS(SEEKBAR, "step"));
    }
}
