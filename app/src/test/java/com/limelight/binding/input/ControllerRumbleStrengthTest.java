package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.preference.PreferenceManager;

import com.limelight.binding.input.driver.AbstractController;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The "Controller vibration strength" setting as a stream uses it. The PC's rumble goes through
 * ControllerHandler to a controller: here a stand-in for a pad on Starwire's own USB driver, which
 * keeps what it was told. The setting is read the way a stream reads it, from the saved settings.
 * RumbleStrengthTest covers the arithmetic, and VibratorRumbleStrengthTest the paths through
 * Android's own vibrators.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"})
public class ControllerRumbleStrengthTest {
    private ControllerHandler handler;
    private RecordingPad pad;

    // Keeps the last motor values as unsigned numbers, -1 until it's told any
    private static final class RecordingPad extends AbstractController {
        int low = -1, high = -1, leftTrigger = -1, rightTrigger = -1;

        RecordingPad(ControllerHandler listener) {
            super(1, listener, 0x045e, 0x02ea);
        }

        @Override
        public boolean start() {
            return true;
        }

        @Override
        public void stop() {}

        @Override
        public void rumble(short lowFreqMotor, short highFreqMotor) {
            low = lowFreqMotor & 0xFFFF;
            high = highFreqMotor & 0xFFFF;
        }

        @Override
        public void rumbleTriggers(short leftTrigger, short rightTrigger) {
            this.leftTrigger = leftTrigger & 0xFFFF;
            this.rightTrigger = rightTrigger & 0xFFFF;
        }
    }

    // A stream's controller handling with one pad plugged in. A null strength leaves the
    // setting as it is on a new install.
    private void startStream(Integer strength) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        if (strength != null) {
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                    .putInt("seekbar_controller_vibration_strength", strength).commit();
        }
        handler = new ControllerHandler(activity, null, null, PreferenceConfiguration.readPreferences(activity));
        pad = new RecordingPad(handler);
        handler.deviceAdded(pad);
    }

    @After
    public void endStream() {
        if (handler != null) {
            handler.destroy();
        }
    }

    @Test
    public void fullStrengthOnANewInstall() {
        startStream(null);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x1234);
        assertEquals(0xFFFF, pad.low);
        assertEquals(0x1234, pad.high);
        handler.handleRumbleTriggers((short) 0, (short) 0x8001, (short) 0x0101);
        assertEquals(0x8001, pad.leftTrigger);
        assertEquals(0x0101, pad.rightTrigger);
    }

    @Test
    public void fullStrengthLeavesRumbleAlone() {
        startStream(100);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x0001);
        assertEquals(0xFFFF, pad.low);
        assertEquals(0x0001, pad.high);
    }

    @Test
    public void halfStrengthHalvesRumble() {
        startStream(50);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0x8000);
        // 65535 / 2 is 32767.5, which rounds up
        assertEquals(0x8000, pad.low);
        assertEquals(0x4000, pad.high);
        handler.handleRumbleTriggers((short) 0, (short) 0x8000, (short) 0xFFFF);
        assertEquals(0x4000, pad.leftTrigger);
        assertEquals(0x8000, pad.rightTrigger);
    }

    @Test
    public void zeroStrengthTurnsRumbleOff() {
        startStream(0);
        handler.handleRumble((short) 0, (short) 0xFFFF, (short) 0xFFFF);
        assertEquals(0, pad.low);
        assertEquals(0, pad.high);
        handler.handleRumbleTriggers((short) 0, (short) 0xFFFF, (short) 0xFFFF);
        assertEquals(0, pad.leftTrigger);
        assertEquals(0, pad.rightTrigger);
    }

    // Rumble for another player's pad leaves this one alone, at any strength
    @Test
    public void otherPlayersRumbleDoesNotReachThisPad() {
        startStream(50);
        handler.handleRumble((short) 1, (short) 0xFFFF, (short) 0xFFFF);
        assertEquals(-1, pad.low);
        assertEquals(-1, pad.high);
    }
}
