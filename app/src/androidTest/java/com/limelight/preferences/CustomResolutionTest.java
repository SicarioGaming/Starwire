package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.limelight.R;
import com.limelight.testing.Automation;
import com.limelight.testing.TestFiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Custom resolutions and frame rates, from Moonlight X: the Custom button takes any size or rate,
 * a saved one the device doesn't offer is kept, Unlock FPS is gone, and a game's settings take
 * custom values too. The dialog test saves a screenshot of the resolution list with its Custom
 * button in Android/data/(package)/files/custom-resolution.
 */
@RunWith(AndroidJUnit4.class)
public class CustomResolutionTest {
    private static final String APP_KEY = "test-pc:customres";
    // Sizes and rates no device offers, so they always show as custom entries
    private static final String ODD_RESOLUTION = "1234x567";
    private static final String ODD_FPS = "37";
    private static final long WAIT_MS = 5000;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    private String savedResolution;
    private String savedFps;
    private Integer savedBitrate;

    @Before
    public void rememberSettings() {
        savedResolution = prefs.getString("list_resolution", null);
        savedFps = prefs.getString("list_fps", null);
        savedBitrate = prefs.contains("seekbar_bitrate_kbps") ? prefs.getInt("seekbar_bitrate_kbps", 0) : null;
    }

    @After
    public void putSettingsBack() {
        SharedPreferences.Editor editor = prefs.edit();
        if (savedResolution == null) {
            editor.remove("list_resolution");
        }
        else {
            editor.putString("list_resolution", savedResolution);
        }
        if (savedFps == null) {
            editor.remove("list_fps");
        }
        else {
            editor.putString("list_fps", savedFps);
        }
        if (savedBitrate == null) {
            editor.remove("seekbar_bitrate_kbps");
        }
        else {
            editor.putInt("seekbar_bitrate_kbps", savedBitrate);
        }
        editor.commit();
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE).edit()
                .remove(APP_KEY).commit();
    }

    private static int count(CharSequence[] items, String item) {
        int count = 0;
        for (CharSequence candidate : items) {
            if (item.contentEquals(candidate)) {
                count++;
            }
        }
        return count;
    }

    private static PreferenceFragment settingsOf(StreamSettings activity) {
        return (PreferenceFragment) activity.getFragmentManager().findFragmentById(R.id.stream_settings);
    }

    @Test
    public void savedValuesTheDeviceDoesNotOfferAreKept() {
        prefs.edit().putString("list_resolution", ODD_RESOLUTION).putString("list_fps", ODD_FPS).commit();

        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                PreferenceFragment fragment = settingsOf(activity);
                ListPreference resolutionPref = (ListPreference) fragment.findPreference("list_resolution");
                ListPreference fpsPref = (ListPreference) fragment.findPreference("list_fps");

                // Opening Settings no longer resets them to something the device lists
                assertEquals(ODD_RESOLUTION, resolutionPref.getValue());
                assertEquals(1, count(resolutionPref.getEntryValues(), ODD_RESOLUTION));
                assertEquals(1, count(resolutionPref.getEntries(),
                        context.getString(R.string.resolution_custom_entry, ODD_RESOLUTION)));
                assertEquals(ODD_FPS, fpsPref.getValue());
                assertEquals(1, count(fpsPref.getEntries(),
                        context.getString(R.string.fps_custom_entry, ODD_FPS)));

                // Unlock FPS is replaced by the custom frame rates
                assertNull(fragment.findPreference("checkbox_unlock_fps"));
            });

            // The stream gets the custom values as they are
            PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
            assertEquals(1234, config.width);
            assertEquals(567, config.height);
            assertEquals(Integer.parseInt(ODD_FPS), config.fps);

            // Picking a standard value drops the custom entry
            scenario.onActivity(activity -> {
                ListPreference resolutionPref = (ListPreference) settingsOf(activity).findPreference("list_resolution");
                resolutionPref.setValue("1280x720");
                assertEquals(0, count(resolutionPref.getEntryValues(), ODD_RESOLUTION));
            });
        }
    }

    @Test
    public void theCustomButtonTakesAnySizeAndRate() {
        prefs.edit().putString("list_resolution", "1920x1080").putString("list_fps", "60").commit();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // The settings screen's background keeps moving, so don't wait long for it to go idle
        long idleTimeout = Configurator.getInstance().getWaitForIdleTimeout();
        Configurator.getInstance().setWaitForIdleTimeout(1000);

        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            // The resolution list has a Custom button
            UiObject2 custom = openListWithCustomButton(scenario, device, "list_resolution");
            File shots = TestFiles.file(context, "custom-resolution");
            shots.mkdirs();
            device.takeScreenshot(new File(shots, "resolution-dialog.png"));
            custom.click();

            List<UiObject2> inputs = waitForInputs(device, 2);
            // Too wide: the dialog says so and stays open, and nothing is saved
            inputs.get(0).setText("9000");
            inputs.get(1).setText("900");
            dialogButton(device, "button1").click();
            device.waitForIdle();
            assertEquals(2, device.findObjects(By.clazz(EditText.class)).size());
            assertEquals("1920x1080", prefs.getString("list_resolution", null));

            inputs.get(0).setText("1600");
            dialogButton(device, "button1").click();
            assertClosed(device, "the custom resolution dialog");
            assertEquals("1600x900", prefs.getString("list_resolution", null));
            // The bitrate follows the new size, as it does for the listed ones
            assertEquals(PreferenceConfiguration.getDefaultBitrate("1600x900", "60"),
                    prefs.getInt("seekbar_bitrate_kbps", 0));
            scenario.onActivity(activity -> {
                ListPreference resolutionPref = (ListPreference) settingsOf(activity).findPreference("list_resolution");
                assertEquals(1, count(resolutionPref.getEntries(),
                        context.getString(R.string.resolution_custom_entry, "1600x900")));
            });

            // The same for the frame rate: 0 isn't taken, 37 is
            openListWithCustomButton(scenario, device, "list_fps").click();
            inputs = waitForInputs(device, 1);
            inputs.get(0).setText("0");
            dialogButton(device, "button1").click();
            device.waitForIdle();
            assertEquals(1, device.findObjects(By.clazz(EditText.class)).size());
            assertEquals("60", prefs.getString("list_fps", null));

            inputs.get(0).setText(ODD_FPS);
            dialogButton(device, "button1").click();
            assertClosed(device, "the custom frame rate dialog");
            assertEquals(ODD_FPS, prefs.getString("list_fps", null));
            assertEquals(PreferenceConfiguration.getDefaultBitrate("1600x900", ODD_FPS),
                    prefs.getInt("seekbar_bitrate_kbps", 0));
        }
        finally {
            Configurator.getInstance().setWaitForIdleTimeout(idleTimeout);
        }
    }

    // Taps the setting's row, as a person would, and returns its dialog's Custom button
    private UiObject2 openListWithCustomButton(ActivityScenario<StreamSettings> scenario, UiDevice device, String key) {
        scenario.onActivity(activity -> {
            ListView list = activity.findViewById(android.R.id.list);
            ListAdapter adapter = list.getAdapter();
            for (int i = 0; i < adapter.getCount(); i++) {
                Object item = adapter.getItem(i);
                if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                    list.performItemClick(null, i, adapter.getItemId(i));
                    return;
                }
            }
            throw new AssertionError(key + " isn't on the settings screen");
        });
        UiObject2 custom = device.wait(Until.findObject(By.res("android", "button3")), WAIT_MS);
        assertNotNull(key + " has no Custom button", custom);
        assertEquals(context.getString(R.string.seekbar_custom).toUpperCase(Locale.ROOT),
                custom.getText().toUpperCase(Locale.ROOT));
        return custom;
    }

    // A button of the custom value dialog. Its keyboard opens with it, and on Android TV 14
    // (Gboard) the keyboard took the screen: the buttons were out of sight, UI Automator found
    // none, and the test stopped with a NullPointerException. A person puts the keyboard away with
    // back first, and so does this, only when the keyboard is what hides the button.
    private static UiObject2 dialogButton(UiDevice device, String id) {
        UiObject2 button = device.findObject(By.res("android", id));
        if (button == null && keyboardShowing()) {
            device.pressBack();
            button = device.wait(Until.findObject(By.res("android", id)), WAIT_MS);
        }
        if (button == null) {
            fail("the dialog's " + id + " isn't on screen (" + whatsOpen(device) + ")");
        }
        return button;
    }

    private static boolean keyboardShowing() {
        for (AccessibilityWindowInfo window : Automation.uiAutomation().getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true;
            }
        }
        return false;
    }

    private static void assertClosed(UiDevice device, String dialog) {
        // Android 6's emulator left the frame rate dialog open (2026-10-23) and its log was gone
        // by the time the run ended, so the message says what was on screen
        if (!device.wait(Until.gone(By.clazz(EditText.class)), WAIT_MS)) {
            fail(dialog + " didn't close (" + whatsOpen(device) + ")");
        }
    }

    // The dialog's fields, keyboard and OK button as the screen has them, for a failure message
    private static String whatsOpen(UiDevice device) {
        StringBuilder seen = new StringBuilder();
        for (UiObject2 input : device.findObjects(By.clazz(EditText.class))) {
            seen.append("field \"").append(input.getText()).append("\", ");
        }
        seen.append(keyboardShowing() ? "keyboard showing, " : "no keyboard, ");
        UiObject2 ok = device.findObject(By.res("android", "button1"));
        seen.append(ok == null ? "no OK button" : "OK button at " + ok.getVisibleBounds());
        return seen.toString();
    }

    private static List<UiObject2> waitForInputs(UiDevice device, int count) {
        assertTrue("the custom value dialog didn't open",
                device.wait(Until.hasObject(By.clazz(EditText.class)), WAIT_MS));
        List<UiObject2> inputs = device.findObjects(By.clazz(EditText.class));
        assertEquals(count, inputs.size());
        return inputs;
    }

    @Test
    public void gameSettingsTakeCustomValuesAndClearBackToTheDefault() {
        AppPreferences.AppSettings settings = new AppPreferences.AppSettings();
        settings.useGlobalSettings = false;
        settings.resolution = ODD_RESOLUTION;
        settings.fps = Integer.parseInt(ODD_FPS);
        settings.gameOptions.put("jitter_buffer", "smooth");
        AppPreferences.saveAppSettings(context, APP_KEY, settings);

        Intent intent = new Intent(context, AppStreamSettings.class)
                .putExtra(AppStreamSettings.EXTRA_APP_KEY, APP_KEY)
                .putExtra(AppStreamSettings.EXTRA_APP_NAME, "Test Game");
        try (ActivityScenario<AppStreamSettings> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                AppStreamSettings.AppSettingsFragment fragment = (AppStreamSettings.AppSettingsFragment)
                        activity.getFragmentManager().findFragmentById(R.id.stream_settings);
                ListPreference resolutionPref = (ListPreference) fragment.findPreference("list_app_resolution");
                ListPreference fpsPref = (ListPreference) fragment.findPreference("list_app_fps");
                ListPreference jitterPref = (ListPreference) fragment.findPreference("game_jitter_buffer");

                assertEquals(ODD_RESOLUTION, resolutionPref.getValue());
                assertEquals(context.getString(R.string.resolution_custom_entry, ODD_RESOLUTION),
                        resolutionPref.getSummary().toString());
                assertEquals(ODD_FPS, fpsPref.getValue());
                assertEquals("[Use global default]", resolutionPref.getEntries()[0].toString());
                assertEquals("smooth", jitterPref.getValue());

                // Clear goes back to the global setting
                resolutionPref.setValue("");
                fragment.saveSettings();
            });
        }

        AppPreferences.AppSettings saved = AppPreferences.getAppSettings(context, APP_KEY);
        assertNull(saved.resolution);
        assertEquals(Integer.parseInt(ODD_FPS), saved.fps);

        // The game streams at its own frame rate and jitter buffer, and the global resolution
        PreferenceConfiguration config = AppPreferences.getEffectivePreferences(context, APP_KEY, null, false);
        assertEquals(Integer.parseInt(ODD_FPS), config.fps);
        assertEquals(PreferenceConfiguration.JITTER_BUFFER_SMOOTH, config.jitterBuffer);
        assertEquals(PreferenceConfiguration.readPreferences(context).width, config.width);
    }
}
