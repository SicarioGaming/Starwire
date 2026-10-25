package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Virtual display setting, on the settings screen and in a game's App Settings, run by
 * Robolectric. A Reddit user looking for Apollo's virtual display didn't find it under its old
 * name, "Display on the PC". The stored values didn't change with the name, so a choice saved by
 * 0.2.1 still picks the same option. What the stream sends for each value is
 * VirtualDisplayLaunchTest.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block, and the settings read its
// audio constants. Instrumenting it lets Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class VirtualDisplaySettingTest {
    private static final String TITLE = "Virtual display";
    private static final String SUMMARY = "Apollo and Vibepollo can make a screen just for this device, "
            + "at the stream's resolution and frame rate. Host default leaves it to the PC's own setting.";
    private static final String HOST_DEFAULT = "Host default";
    private static final String ON = "On: a screen made for this device";
    private static final String OFF = "Off: the PC's main monitor";

    // The values 0.2.1 saved, written out here so a change to them fails this test
    private static final String[] VALUES_IN_0_2_1 = {"host", "virtual", "primary"};

    private final Context context = RuntimeEnvironment.getApplication();

    private ListPreference mainSetting() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        PreferenceFragment fragment = (PreferenceFragment)
                activity.getFragmentManager().findFragmentById(R.id.stream_settings);
        return (ListPreference) fragment.findPreference("virtual_display_mode");
    }

    private ListPreference perGameSetting(String appKey) {
        Intent intent = new Intent(context, AppStreamSettings.class)
                .putExtra(AppStreamSettings.EXTRA_APP_KEY, appKey)
                .putExtra(AppStreamSettings.EXTRA_APP_NAME, "Test Game");
        AppStreamSettings activity = Robolectric.buildActivity(AppStreamSettings.class, intent).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        PreferenceFragment fragment = (PreferenceFragment)
                activity.getFragmentManager().findFragmentById(R.id.stream_settings);
        return (ListPreference) fragment.findPreference("game_virtual_display_mode");
    }

    private static String[] strings(CharSequence[] texts) {
        String[] out = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            out[i] = texts[i].toString();
        }
        return out;
    }

    @Test
    public void theSettingsScreenNamesTheVirtualDisplay() {
        ListPreference setting = mainSetting();
        assertEquals(TITLE, setting.getTitle().toString());
        assertEquals(SUMMARY, setting.getSummary().toString());
        assertArrayEquals(new String[] {HOST_DEFAULT, ON, OFF}, strings(setting.getEntries()));
        assertArrayEquals(VALUES_IN_0_2_1, strings(setting.getEntryValues()));
        // Nothing saved yet: host default, which sends nothing
        assertEquals("host", setting.getValue());
    }

    @Test
    public void aChoiceSavedBy021StillPicksTheSameOption() {
        String[] names = {HOST_DEFAULT, ON, OFF};
        for (int i = 0; i < VALUES_IN_0_2_1.length; i++) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString("virtual_display_mode", VALUES_IN_0_2_1[i]).commit();
            ListPreference setting = mainSetting();
            assertEquals(VALUES_IN_0_2_1[i], setting.getValue());
            assertEquals(names[i], setting.getEntry().toString());
            // And the stream reads it as before
            assertEquals(VALUES_IN_0_2_1[i], PreferenceConfiguration.readPreferences(context).virtualDisplayMode);
        }
    }

    @Test
    public void aGamesSettingsNameTheVirtualDisplay() {
        String[] names = {HOST_DEFAULT, ON, OFF};
        for (int i = 0; i < VALUES_IN_0_2_1.length; i++) {
            String appKey = "test-pc:" + i;
            AppPreferences.AppSettings saved = new AppPreferences.AppSettings();
            saved.useGlobalSettings = false;
            saved.gameOptions.put("virtual_display_mode", VALUES_IN_0_2_1[i]);
            AppPreferences.saveAppSettings(context, appKey, saved);

            ListPreference setting = perGameSetting(appKey);
            assertEquals(TITLE, setting.getTitle().toString());
            assertArrayEquals(new String[] {"[Use global default]", HOST_DEFAULT, ON, OFF},
                    strings(setting.getEntries()));
            assertEquals(VALUES_IN_0_2_1[i], setting.getValue());
            // A game's list shows its choice as the summary
            assertEquals(names[i], setting.getSummary().toString());
        }
    }

    @Test
    public void aGameLeftOnTheGlobalSettingSaysSo() {
        ListPreference setting = perGameSetting("test-pc:global");
        assertEquals(TITLE, setting.getTitle().toString());
        assertEquals("", setting.getValue());
        assertEquals("[Use global default]", setting.getSummary().toString());
    }
}
