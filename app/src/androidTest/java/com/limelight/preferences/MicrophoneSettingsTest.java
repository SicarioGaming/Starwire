package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.binding.audio.MicrophoneCaptureManager;
import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.audio.securemic.MicPairingStore;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.testing.Automation;
import com.limelight.testing.TestFiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The microphone settings: off unless chosen, the chosen microphone and destination are read back
 * (Starwire's encrypted receiver by default, and 0.2.0's unencrypted default moved to it once),
 * the right rows show for each destination, and the stream is only told to ask the host when the
 * microphone goes over the stream.
 */
@RunWith(AndroidJUnit4.class)
public class MicrophoneSettingsTest {
    private static final String TEST_PC = "starwire-test-mic-pc";

    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> saved;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Keep whatever settings the app had, and put them back afterwards
        saved = prefs.getAll();
    }

    @After
    public void tearDown() {
        new MicPairingStore(context).remove(TEST_PC);
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) editor.putFloat(e.getKey(), (Float) v);
            else if (v instanceof String) editor.putString(e.getKey(), (String) v);
        }
        editor.commit();
    }

    @Test
    public void offByDefault() {
        prefs.edit().remove(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING)
                .remove(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING).commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertFalse(config.enableMicrophone);
        assertEquals(0, config.microphoneDeviceId);
    }

    @Test
    public void readsTheChosenMicrophone() {
        prefs.edit().putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "7").commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertTrue(config.enableMicrophone);
        assertEquals(7, config.microphoneDeviceId);
    }

    @Test
    public void aBrokenChoiceFallsBackToTheDefaultMicrophone() {
        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "not a number").commit();
        assertEquals(0, PreferenceConfiguration.readPreferences(context).microphoneDeviceId);
    }

    @Test
    public void theStreamOnlyAsksForTheMicrophoneWhenItsOn() {
        StreamConfiguration off = new StreamConfiguration.Builder().build();
        assertFalse(off.getEnableMicrophone());
        StreamConfiguration on = new StreamConfiguration.Builder().setEnableMicrophone(true).build();
        assertTrue(on.getEnableMicrophone());
    }

    @Test
    public void theDestinationIsReadWithItsStreamNameAndPort() {
        // A fresh install: Starwire's receiver on the PC, encrypted (works with any host), on 6980
        prefs.edit().remove(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING)
                .remove(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING)
                .remove(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING)
                .remove(PreferenceConfiguration.VBAN_PORT_PREF_STRING).commit();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        assertEquals("starwire", config.microphoneTarget);
        assertEquals("Stream1", config.vbanStreamName);
        assertEquals(6980, config.vbanPort);

        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "host").commit();
        assertEquals("host", PreferenceConfiguration.readPreferences(context).microphoneTarget);
        // Something this version doesn't know is the default
        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "carrier pigeon").commit();
        assertEquals("starwire", PreferenceConfiguration.readPreferences(context).microphoneTarget);

        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban")
                .putString(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING, "Phone")
                .putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, " 6990 ").commit();
        config = PreferenceConfiguration.readPreferences(context);
        assertEquals("vban", config.microphoneTarget);
        assertEquals("Phone", config.vbanStreamName);
        assertEquals(6990, config.vbanPort);

        // A port that can't be used falls back to 6980
        for (String bad : new String[] {"0", "70000", "port"}) {
            prefs.edit().putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, bad).commit();
            assertEquals(bad, 6980, PreferenceConfiguration.readPreferences(context).vbanPort);
        }
    }

    // 0.2.0's default was "vban", unencrypted: updating moves it to the encrypted receiver once,
    // and choosing "Not encrypted" again afterwards sticks
    @Test
    public void the020DefaultMovesToTheEncryptedReceiverOnce() {
        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban")
                .remove(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING).commit();
        assertEquals("starwire", PreferenceConfiguration.readPreferences(context).microphoneTarget);
        assertEquals("starwire", prefs.getString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, null));
        assertTrue(prefs.getBoolean(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING, false));

        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban").commit();
        assertEquals("vban", PreferenceConfiguration.readPreferences(context).microphoneTarget);
        assertEquals("vban", PreferenceConfiguration.readPreferences(context).microphoneTarget);

        // The host stays the host
        prefs.edit().putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "host")
                .remove(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING).commit();
        assertEquals("host", PreferenceConfiguration.readPreferences(context).microphoneTarget);
    }

    @Test
    public void eachDestinationShowsItsOwnRows() {
        prefs.edit().putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putBoolean(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "starwire").commit();
        MicPairingStore store = new MicPairingStore(context);
        store.remove(TEST_PC);
        Automation.grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        boolean pairedBefore = !store.all().isEmpty();

        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                ListAdapter adapter = ((ListView) activity.findViewById(android.R.id.list)).getAdapter();
                Preference target = preference(adapter, "list_microphone_target");
                assertEquals(activity.getString(R.string.microphone_target_starwire), target.getSummary().toString());
                // The guide, pairing and the port; no stream name, no Apollo link
                assertTrue(isShown(adapter, "vban_setup_info"));
                assertTrue(isShown(adapter, "mic_pairing"));
                assertTrue(isShown(adapter, "text_vban_port"));
                assertFalse(isShown(adapter, "text_vban_stream_name"));
                assertFalse(isShown(adapter, "microphone_host_info"));
                assertEquals(activity.getString(R.string.summary_mic_setup_encrypted),
                        preference(adapter, "vban_setup_info").getSummary().toString());
                if (!pairedBefore) {
                    assertEquals(activity.getString(R.string.summary_mic_pairing_none),
                            preference(adapter, "mic_pairing").getSummary().toString());
                }
                assertTrue(positionOf(adapter, "list_microphone_target") < positionOf(adapter, "vban_setup_info"));
                assertTrue(positionOf(adapter, "vban_setup_info") < positionOf(adapter, "mic_pairing"));
                assertTrue(positionOf(adapter, "mic_pairing") < positionOf(adapter, "text_vban_port"));
            });

            // Pairing shows up in the row, by the PC's name
            assertTrue(store.save(new MicPairing(TEST_PC, "Test PC", new byte[8], new byte[32], new byte[8], "TEST-PC", 1)));
            scenario.onActivity(activity -> {
                ListAdapter adapter = ((ListView) activity.findViewById(android.R.id.list)).getAdapter();
                Preference target = preference(adapter, "list_microphone_target");
                target.getOnPreferenceChangeListener().onPreferenceChange(target, "starwire");
                String summary = preference(adapter, "mic_pairing").getSummary().toString();
                assertTrue(summary, summary.contains("Test PC"));
                assertTrue(summary, summary.startsWith("Paired with"));
            });
        }
    }

    // The pairing dialog without the network: the code is shown as the PC shows it, and a code
    // that can't be one is caught before anything is sent. (Pairing itself is MicPairingClientTest
    // on the JVM and a run against the receiver on a PC; this never sends a packet.)
    @Test
    public void thePairingDialogFormatsTheCodeAndCatchesBadOnes() {
        prefs.edit().putBoolean(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING, true).commit();
        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            SystemClock.sleep(1000);
            android.app.AlertDialog[] shown = new android.app.AlertDialog[1];
            scenario.onActivity(activity -> shown[0] = MicPairingDialog.show(activity, 6980, null));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                android.app.AlertDialog dialog = shown[0];
                assertTrue(dialog.isShowing());
                List<View> views = new ArrayList<>();
                collect(dialog.getWindow().getDecorView(), views);
                android.widget.EditText code = null;
                for (View v : views) {
                    if (v instanceof android.widget.EditText) {
                        code = (android.widget.EditText) v;
                    }
                }
                if (code == null) {
                    // No PCs on this device yet: it says to add one first
                    assertTrue(texts(views).contains(activity.getString(R.string.mic_pair_no_pcs)));
                    dialog.dismiss();
                    return;
                }
                code.setText("2491 650n2rbh");
                assertEquals("2491-650N-2RBH", code.getText().toString());
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                List<View> after = new ArrayList<>();
                collect(dialog.getWindow().getDecorView(), after);
                assertTrue(texts(after).toString(), texts(after).contains(activity.getString(R.string.mic_pair_bad_code)));
                assertTrue("still open to fix the code", dialog.isShowing());
                assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled());

                // A picture of the dialog, for a look
                View root = dialog.getWindow().getDecorView();
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(Math.max(1, root.getWidth()),
                        Math.max(1, root.getHeight()), android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bitmap));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        TestFiles.file(activity, "mic_pairing_dialog.png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                dialog.dismiss();
            });
        }
    }

    private static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out);
            }
        }
    }

    private static List<String> texts(List<View> views) {
        List<String> out = new ArrayList<>();
        for (View v : views) {
            if (v instanceof android.widget.TextView && v.getVisibility() == View.VISIBLE) {
                out.add(((android.widget.TextView) v).getText().toString());
            }
        }
        return out;
    }

    @Test
    public void vbanSettingsSayWhatToEnterOnThePc() {
        prefs.edit().putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putBoolean(PreferenceConfiguration.MICROPHONE_TARGET_MIGRATED_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_TARGET_PREF_STRING, "vban")
                .putString(PreferenceConfiguration.VBAN_STREAM_NAME_PREF_STRING, "Phone")
                .putString(PreferenceConfiguration.VBAN_PORT_PREF_STRING, "6990").commit();
        Automation.grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);

        try (ActivityScenario<StreamSettings> scenario =
                     ActivityScenario.launch(new Intent(context, StreamSettings.class))) {
            SystemClock.sleep(1500);
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(android.R.id.list);
                ListAdapter adapter = list.getAdapter();
                Preference target = preference(adapter, "list_microphone_target");
                Preference setup = preference(adapter, "vban_setup_info");

                assertEquals(activity.getString(R.string.microphone_target_vban), target.getSummary().toString());
                assertTrue(target.getSummary().toString().startsWith("Not encrypted"));
                // Only the VBAN rows show, and the steps carry the chosen name and port, after the warning
                assertTrue(setup.isEnabled());
                assertTrue(preference(adapter, "text_vban_stream_name").isEnabled());
                assertFalse(isShown(adapter, "microphone_host_info"));
                assertFalse(isShown(adapter, "mic_pairing"));
                String steps = setup.getSummary().toString();
                assertTrue(steps, steps.contains("Phone") && steps.contains("6990") && steps.contains("CABLE Output"));
                assertTrue(steps, steps.startsWith("Not encrypted: anyone on the same network can listen"));

                // Switching to the host swaps the rows (the list catches up on its next frame)
                target.getOnPreferenceChangeListener().onPreferenceChange(target, "host");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListAdapter adapter = ((ListView) activity.findViewById(android.R.id.list)).getAdapter();
                assertTrue(isShown(adapter, "microphone_host_info"));
                assertFalse(isShown(adapter, "vban_setup_info"));
                assertFalse(isShown(adapter, "text_vban_port"));
                assertFalse(isShown(adapter, "mic_pairing"));
                Preference target = preference(adapter, "list_microphone_target");
                target.getOnPreferenceChangeListener().onPreferenceChange(target, "vban");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListView list = activity.findViewById(android.R.id.list);
                ListAdapter adapter = list.getAdapter();
                // And back, each row in its place
                assertFalse(isShown(adapter, "microphone_host_info"));
                assertTrue(positionOf(adapter, "list_microphone_target") < positionOf(adapter, "vban_setup_info"));
                assertTrue(positionOf(adapter, "vban_setup_info") < positionOf(adapter, "text_vban_port"));

                // A picture of the microphone rows, for a look
                list.setSelection(positionOf(adapter, "checkbox_enable_microphone"));
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                View root = activity.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(metrics.widthPixels,
                        metrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bitmap));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        TestFiles.file(activity, "settings_microphone_vban.png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static int positionOf(ListAdapter adapter, String key) {
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                return i;
            }
        }
        throw new AssertionError(key + " isn't on the settings screen");
    }

    private static boolean isShown(ListAdapter adapter, String key) {
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                return true;
            }
        }
        return false;
    }

    private static Preference preference(ListAdapter adapter, String key) {
        return (Preference) adapter.getItem(positionOf(adapter, key));
    }

    @Test
    public void listingMicrophonesNeverFails() {
        // Whatever inputs the device has, the settings list must build without crashing
        assertNotNull(MicrophoneCaptureManager.getAvailableInputDevices(context));
    }
}
