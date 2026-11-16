package com.limelight.binding.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.lights.Light;
import android.os.CombinedVibration;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.shadows.ShadowInputEvent;
import org.robolectric.shadows.ShadowVibrator;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Controller vibration strength" setting on the paths that go through Android's own
 * vibrators: a pad whose two motors Android drives (how a DualSense or a G8+ over Bluetooth
 * rumbles on Android 12 and later), a pad with trigger motors, a pad with no motors when this
 * device vibrates in its place, and the on-screen controls. ControllerRumbleStrengthTest covers a
 * pad on Starwire's own USB driver, and RumbleStrengthTest the arithmetic.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        shadows = {VibratorRumbleStrengthTest.ShadowPadMotors.class, VibratorRumbleStrengthTest.ShadowPadLights.class})
public class VibratorRumbleStrengthTest {
    private static final String STRENGTH = "seekbar_controller_vibration_strength";

    private Activity activity;
    private ControllerHandler handler;
    // Robolectric keeps one record for every vibrator, so this is also what the pads' motors report
    private ShadowVibrator motors;

    /**
     * A pad's motors as Android lists them, which Robolectric has no stand-in for. It keeps the
     * last vibration it was asked to play.
     */
    @Implements(className = "android.hardware.input.InputDeviceVibratorManager", isInAndroidSdk = false)
    public static class ShadowPadMotors {
        static int[] ids = {};
        static CombinedVibration played;
        static boolean cancelled;

        @Implementation
        protected void __constructor__(int deviceId) {}

        @Implementation
        protected int[] getVibratorIds() {
            return ids.clone();
        }

        // Every motor reports amplitude control when the test turns it on
        @Implementation
        protected Vibrator getVibrator(int id) {
            return RuntimeEnvironment.getApplication().getSystemService(Vibrator.class);
        }

        @Implementation
        protected void vibrate(int uid, String opPkg, CombinedVibration effect, String reason,
                               VibrationAttributes attributes) {
            played = effect;
            cancelled = false;
        }

        @Implementation
        protected void cancel() {
            played = null;
            cancelled = true;
        }
    }

    // A pad with no lights. Robolectric's lights service gives back no list at all.
    @Implements(className = "android.hardware.input.InputDeviceLightsManager", isInAndroidSdk = false)
    public static class ShadowPadLights {
        @Implementation
        protected List<Light> getLights() {
            return new ArrayList<>();
        }
    }

    @Before
    public void setUp() {
        ShadowPadMotors.ids = new int[0];
        ShadowPadMotors.played = null;
        ShadowPadMotors.cancelled = false;
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        motors = Shadows.shadowOf(activity.getSystemService(VibratorManager.class).getDefaultVibrator());
    }

    @After
    public void endStream() {
        if (handler != null) {
            handler.destroy();
        }
    }

    private SharedPreferences.Editor settings() {
        return PreferenceManager.getDefaultSharedPreferences(activity).edit();
    }

    // A stream's controller handling, reading the saved settings the way a stream does
    private void startStream() {
        handler = new ControllerHandler(activity, null, null, PreferenceConfiguration.readPreferences(activity));
    }

    // A Bluetooth pad with these motors joins the stream as player 1
    private void connectPad(int... motorIds) {
        connectPad(true, motorIds);
    }

    // A pad joins the stream as player 1. One that isn't external is built into this device.
    private void connectPad(boolean external, int... motorIds) {
        ShadowPadMotors.ids = motorIds;
        InputDevice pad = ShadowInputDevice.makeInputDeviceNamed("Test pad");
        // Robolectric's InputDevice has no setter for this
        ReflectionHelpers.setField(pad, "mIsExternal", external);

        // Any key from the pad sets it up. One the stream ignores keeps anything from being sent.
        KeyEvent key = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 7, 0, 0,
                InputDevice.SOURCE_GAMEPAD);
        ((ShadowInputEvent) Shadow.extract(key)).setDevice(pad);
        handler.handleButtonDown(key);
    }

    // What a pad's motors play: each id with its amplitude, 1 to 255
    private static CombinedVibration motorsAt(int... idsAndAmplitudes) {
        CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
        for (int i = 0; i < idsAndAmplitudes.length; i += 2) {
            combo.addVibrator(idsAndAmplitudes[i], VibrationEffect.createOneShot(60000, idsAndAmplitudes[i + 1]));
        }
        return combo.combine();
    }

    // Two motors: Android lists the high-frequency (right) one first. A motor gets the top 8 bits.
    @Test
    public void bluetoothPadAtFullStrength() {
        motors.setHasAmplitudeControl(true);
        settings().putInt(STRENGTH, 100).commit();
        startStream();
        connectPad(10, 11);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x8000);
        assertEquals(motorsAt(10, 0x80, 11, 0xFF), ShadowPadMotors.played);
    }

    @Test
    public void bluetoothPadAtHalfStrength() {
        motors.setHasAmplitudeControl(true);
        settings().putInt(STRENGTH, 50).commit();
        startStream();
        connectPad(10, 11);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x8000);
        assertEquals(motorsAt(10, 0x40, 11, 0x80), ShadowPadMotors.played);
    }

    @Test
    public void bluetoothPadAtZeroStrength() {
        motors.setHasAmplitudeControl(true);
        settings().putInt(STRENGTH, 0).commit();
        startStream();
        connectPad(10, 11);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0xFFFF);
        assertNull(ShadowPadMotors.played);
        assertTrue(ShadowPadMotors.cancelled);
    }

    // Four motors: the main two, then the left and right triggers. The trigger rumble plays the
    // main motors again from what the pad kept, so this also shows they were kept scaled once.
    @Test
    public void triggerMotorsAtHalfStrength() {
        motors.setHasAmplitudeControl(true);
        settings().putInt(STRENGTH, 50).commit();
        startStream();
        connectPad(10, 11, 12, 13);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x8000);
        handler.handleRumbleTriggers((short) 0, (short) 0x8000, (short) 0xFFFF);
        assertEquals(motorsAt(10, 0x40, 11, 0x80, 12, 0x40, 13, 0x80), ShadowPadMotors.played);
    }

    // This device's motor can't set its strength here, so Starwire pulses it: on for this many
    // milliseconds of every 20. Full rumble keeps it on, half is 11 of 20.
    private static final long[] PHONE_FULL = {0, 20, 0};
    private static final long[] PHONE_HALF = {0, 11, 9};

    // Controls built into this device, like a gaming handheld's, rumble with its own motor
    @Test
    public void builtInControlsAtHalfStrength() {
        motors.setHasAmplitudeControl(false);
        settings().putInt(STRENGTH, 50).commit();
        startStream();
        connectPad(false);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0);
        assertArrayEquals(PHONE_HALF, motors.getPattern());
    }

    @Test
    public void padWithNoMotorsFallsBackToThisDeviceAtTheStrength() {
        motors.setHasAmplitudeControl(false);
        settings().putInt(STRENGTH, 50).putBoolean("checkbox_vibrate_fallback", true).commit();
        startStream();
        connectPad();
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0);
        assertArrayEquals(PHONE_HALF, motors.getPattern());
    }

    // "Adjust emulated rumble intensity" applies on top: 50% of 50% is a quarter, 7 of 20
    @Test
    public void fallbackIntensityAppliesOnTop() {
        motors.setHasAmplitudeControl(false);
        settings().putInt(STRENGTH, 50).putBoolean("checkbox_vibrate_fallback", true)
                .putInt("seekbar_vibrate_fallback_strength", 50).commit();
        startStream();
        connectPad();
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0);
        assertArrayEquals(new long[]{0, 7, 13}, motors.getPattern());
    }

    // With no pad, rumble for player 1 comes from the on-screen controls and plays on this device
    private void startStreamWithOnScreenControls(int strength) {
        motors.setHasAmplitudeControl(false);
        settings().putInt(STRENGTH, strength).putBoolean("checkbox_show_onscreen_controls", true).commit();
        startStream();
    }

    @Test
    public void onScreenControlsAtFullStrength() {
        startStreamWithOnScreenControls(100);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0);
        assertArrayEquals(PHONE_FULL, motors.getPattern());
    }

    @Test
    public void onScreenControlsAtHalfStrength() {
        startStreamWithOnScreenControls(50);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0);
        assertArrayEquals(PHONE_HALF, motors.getPattern());
    }

    @Test
    public void onScreenControlsAtZeroStrength() {
        startStreamWithOnScreenControls(0);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0xFFFF);
        assertTrue(motors.isCancelled());
        assertFalse(motors.isVibrating());
    }
}
