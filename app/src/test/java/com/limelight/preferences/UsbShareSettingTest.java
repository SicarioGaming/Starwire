package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.binding.usb.share.UsbShareBackends;
import com.limelight.binding.usb.share.UsbShareExporter;
import com.limelight.binding.usb.share.UsbShareTunnel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * "USB devices (Experimental)" under Controllers: off until you turn it on, and only there when
 * the phone has USB host support and the build can share (UsbShareBackends).
 */
@RunWith(RobolectricTestRunner.class)
// The settings read MoonBridge's audio constants; instrumenting it skips loading the native library
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class UsbShareSettingTest {
    private final Context context = RuntimeEnvironment.getApplication();

    // Backends that can't be used: the setting only asks whether there are any
    private static final class Backends implements UsbShareBackends.Factory {
        final boolean supported;

        Backends(boolean supported) {
            this.supported = supported;
        }

        @Override public boolean isSupported() { return supported; }
        @Override
        public UsbShareExporter exporter(Context context, UsbShareExporter.Listener listener) {
            throw new AssertionError();
        }

        @Override
        public UsbShareTunnel tunnel(Context context, UsbShareTunnel.Exporter exporter,
                                     UsbShareTunnel.Listener listener) {
            throw new AssertionError();
        }
    }

    @After
    public void putTheBuildsBackendsBack() {
        UsbShareBackends.setForTests(null);
    }

    private CheckBoxPreference setting(boolean usbHost) {
        shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_USB_HOST, usbHost);
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        PreferenceFragment settings = (PreferenceFragment) activity.getFragmentManager()
                .findFragmentById(R.id.stream_settings);
        return (CheckBoxPreference) settings.findPreference(PreferenceConfiguration.USB_SHARE_PREF_STRING);
    }

    @Test
    public void offUntilYouTurnItOn() {
        UsbShareBackends.setForTests(new Backends(true));
        CheckBoxPreference setting = setting(true);
        assertNotNull(setting);
        assertFalse(setting.isChecked());
        assertEquals(context.getString(R.string.title_checkbox_usb_share), setting.getTitle().toString());
        assertEquals("category_gamepad_settings", setting.getParent().getKey());
        assertFalse(PreferenceConfiguration.readPreferences(context).usbShare);

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceConfiguration.USB_SHARE_PREF_STRING, true).commit();
        assertEquals(true, PreferenceConfiguration.readPreferences(context).usbShare);
    }

    // This build has no exporter or tunnel yet, so there's nothing for the setting to do
    @Test
    public void hiddenWithoutBackends() {
        assertNull(UsbShareBackends.get());
        assertNull(setting(true));
    }

    @Test
    public void hiddenOnPhonesThatCantShare() {
        UsbShareBackends.setForTests(new Backends(false));
        assertNull(setting(true));
        UsbShareBackends.setForTests(new Backends(true));
        assertNull(setting(false));
    }
}
