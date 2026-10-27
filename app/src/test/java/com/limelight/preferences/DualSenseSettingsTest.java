package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.view.InputDevice;

import com.limelight.R;
import com.limelight.binding.input.dualsense.DualSenseBluetooth;
import com.limelight.binding.input.dualsense.DualSenseStatus;
import com.limelight.binding.input.dualsense.FakeHidHost;
import com.limelight.binding.input.dualsense.PadMatcher;
import com.limelight.binding.input.dualsense.TriggerEffect;
import com.limelight.binding.input.dualsense.TriggerPreset;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

/**
 * Settings > Controllers > DualSense (Experimental) with a pad: the switch asks for Nearby
 * devices, the status line follows the checks, and Test sends one gun click to both triggers and
 * turns it off again. Android's Bluetooth is FakeHidHost, on Robolectric's paused main looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class DualSenseSettingsTest {
    private static final String PAD = "A0:5A:5C:00:00:01";

    private final FakeHidHost link = FakeHidHost.working(PAD, "DualSense Wireless Controller");
    private StreamSettings activity;
    private CheckBoxPreference toggle;
    private Preference status;
    private Preference test;

    @Before
    public void openSettings() {
        activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        idle();
        PreferenceFragment fragment = (PreferenceFragment) activity.getFragmentManager()
                .findFragmentById(R.id.stream_settings);
        // The group as StreamSettings sets it up, with the fake link in place of Android's
        new DualSenseSettings(fragment, (a, strength, listener) ->
                DualSenseBluetooth.forSettings(a, strength, listener, context -> link, Looper.getMainLooper()))
                .attach();
        toggle = (CheckBoxPreference) fragment.findPreference(PreferenceConfiguration.DUALSENSE_EFFECTS_PREF_STRING);
        status = fragment.findPreference("dualsense_status");
        test = fragment.findPreference("dualsense_test");
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String statusLine() {
        return String.valueOf(status.getSummary());
    }

    private boolean switchTo(boolean on) {
        boolean taken = toggle.getOnPreferenceChangeListener().onPreferenceChange(toggle, on);
        if (taken) {
            toggle.setChecked(on);
        }
        idle();
        return taken;
    }

    private void tapTest() {
        test.getOnPreferenceClickListener().onPreferenceClick(test);
        idle();
    }

    private void connectADualSense() {
        InputDevice device = ShadowInputDevice.makeInputDeviceNamed("DualSense Wireless Controller");
        shadowOf(device).setVendorId(PadMatcher.SONY_VENDOR_ID);
        shadowOf(device).setProductId(PadMatcher.DUALSENSE_PRODUCT_ID);
        ReflectionHelpers.setField(device, "mId", 7);
        ReflectionHelpers.setField(device, "mSources", InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK);
        shadowOf(activity.getSystemService(InputManager.class)).addInputDevice(device);
    }

    @Test
    public void testSendsOneGunClickAndTurnsItOff() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        connectADualSense();
        assertEquals(activity.getString(R.string.dualsense_status_off), statusLine());

        assertTrue(switchTo(true));
        assertEquals(activity.getString(R.string.dualsense_status_ready), statusLine());
        assertEquals("nothing is sent before Test", 0, link.calls.size());

        tapTest();
        assertEquals(1, link.calls.size());
        String gunClick = FakeHidHost.hex(TriggerPreset.GUN_CLICK.effect());
        assertEquals(gunClick, FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(gunClick, FakeHidHost.leftTrigger(link.lastTaken()));
        assertEquals(activity.getString(R.string.dualsense_test_sent), statusLine());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DualSenseSettings.TEST_HOLD_MS));
        assertEquals(2, link.calls.size());
        assertEquals(FakeHidHost.hex(TriggerEffect.off()), FakeHidHost.rightTrigger(link.lastTaken()));
        assertEquals(activity.getString(R.string.dualsense_status_ready), statusLine());

        assertTrue(switchTo(false));
        assertEquals(1, link.closes);
        assertEquals(activity.getString(R.string.dualsense_status_off), statusLine());
        tapTest();
        assertEquals("off means nothing is sent", 2, link.calls.size());
    }

    @Test
    public void withoutAPadTheStatusSaysSoAndTestSendsNothing() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        assertTrue(switchTo(true));
        assertEquals(activity.getString(R.string.dualsense_status_no_pad), statusLine());
        tapTest();
        assertEquals(0, link.calls.size());
    }

    @Test
    public void turningItOnAsksForNearbyDevicesFirst() {
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        assertFalse("stays off until the permission is allowed", switchTo(true));
        // A fragment's request goes out as Android's permission dialog, started for a result
        ShadowActivity.IntentForResult asked = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(DualSenseSettings.REQUEST_NEARBY_DEVICES, asked.requestCode);
        assertArrayEquals(new String[] {Manifest.permission.BLUETOOTH_CONNECT},
                asked.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"));
        assertEquals(0, link.opens);
    }

    @Test
    public void aStreamThatFellBackIsShownUnderTheStatus() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        connectADualSense();
        PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putString(PreferenceConfiguration.DUALSENSE_LAST_STATUS_PREF_STRING, DualSenseStatus.SEND_FAILED.name())
                .apply();
        assertTrue(switchTo(true));
        assertEquals(activity.getString(R.string.dualsense_status_ready) + "\n"
                + activity.getString(R.string.dualsense_status_last_stream,
                activity.getString(R.string.dualsense_status_send_failed)), statusLine());
    }
}
