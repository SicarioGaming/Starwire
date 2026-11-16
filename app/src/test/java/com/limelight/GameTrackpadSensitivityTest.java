package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TrackpadContext;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Trackpad sensitivity, in Settings under Touch & mouse, sets the pointer speed in both
 * trackpad modes. The stream menu's Sensitivity item, which switched it off and on during a
 * stream, is gone, so the setting always applies.
 *
 * Game runs as in GameTrackpadDragTest, and the touch mode is picked as the stream menu's Touch
 * Mode picker does.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameTrackpadSensitivityTest {
    private final Context context = RuntimeEnvironment.getApplication();

    // The first finger's touch handling, once the stream screen is open in this touch mode
    private Object firstFinger(int sensitivity, int touchMode) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("seekbar_touch_sensitivity", sensitivity).commit();
        Game game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        ReflectionHelpers.setField(game, "conn", new RecordingConnection(context));
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        return ReflectionHelpers.<Object[]>getField(game, "touchContextMap")[0];
    }

    @Test
    public void theNaturalTrackpadUsesTheSetting() {
        Object finger = firstFinger(150, Game.MOUSE_MODE_TRACKPAD_NATURAL);
        assertTrue(finger instanceof TrackpadContext);
        assertEquals(1.5f, ReflectionHelpers.<Float>getField(finger, "sensitivityX"), 0f);
        assertEquals(1.5f, ReflectionHelpers.<Float>getField(finger, "sensitivityY"), 0f);
    }

    @Test
    public void theGamingTrackpadUsesTheSetting() {
        Object finger = firstFinger(50, Game.MOUSE_MODE_TRACKPAD_GAMING);
        assertTrue(finger instanceof RelativeTouchContext);
        assertEquals(0.5, ReflectionHelpers.<Double>getField(finger, "sensitivity"), 0);
    }

    @Test
    public void at100PercentThePointerKeepsItsSpeed() {
        Object finger = firstFinger(100, Game.MOUSE_MODE_TRACKPAD_NATURAL);
        assertEquals(1f, ReflectionHelpers.<Float>getField(finger, "sensitivityX"), 0f);
    }
}
