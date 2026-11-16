package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.ui.overlay.OverlayMenuButton;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Trackpad sensitivity, in Settings under Touch & mouse, sets the pointer speed in both trackpad
 * modes. A stream starts at that speed, with the stream menu's Sensitivity button on, unless the
 * setting is 100%. The button switches to 100%, the usual speed, and back: each press makes the
 * touch handling anew and says which speed it switched to. The menu stays open, and the button
 * shows on or off.
 *
 * Game is created for a game, as in GameQuitTest, so onCreate gets as far as the touch handling
 * and gives the menu Game's own listener. It never connects: the stream starts once its surface
 * exists, and Robolectric never makes one. The host is a documentation address that nothing
 * contacts.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameTrackpadSensitivityTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private OverlayMenuView menu;

    // The stream screen for a game, with Trackpad sensitivity at this percent and the stream menu
    // open. The touchscreen trackpad setting is on by default, so the stream starts in the gaming
    // trackpad mode.
    private void openStream(int sensitivity) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt("seekbar_touch_sensitivity", sensitivity).commit();
        // The services a stream binds to answer with nothing here: Starwire's USB controller
        // driver, and SHIELD's controller extensions
        ShadowApplication app = shadowOf(RuntimeEnvironment.getApplication());
        app.declareComponentUnbindable(new ComponentName(context, UsbDriverService.class));
        app.declareComponentUnbindable(new ComponentName("com.nvidia.blakepairing",
                "com.nvidia.blakepairing.AccessoryService"));

        Intent intent = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_APP_ID, 1)
                .putExtra(Game.EXTRA_APP_NAME, "Hades II")
                .putExtra(Game.EXTRA_HOST, "192.0.2.1")
                .putExtra(Game.EXTRA_PC_UUID, "test-pc")
                .putExtra(Game.EXTRA_PC_NAME, "Test PC")
                .putExtra(Game.EXTRA_UNIQUEID, "0123456789ABCDEF");
        game = Robolectric.buildActivity(Game.class, intent).create().get();
        idle();
        assertFalse("the stream screen is up", game.isFinishing());
        menu = game.findViewById(R.id.overlayMenuView);
        menu.show();
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // The first finger's touch handling
    private Object firstFinger() {
        return ReflectionHelpers.<Object[]>getField(game, "touchContextMap")[0];
    }

    // The pointer's speed in the gaming trackpad mode: 1 is 100%
    private double gamingTrackpadSpeed() {
        Object finger = firstFinger();
        assertTrue("not the gaming trackpad: " + finger, finger instanceof RelativeTouchContext);
        return ReflectionHelpers.<Double>getField(finger, "sensitivity");
    }

    // The same in the natural trackpad mode, which has one for each direction
    private float naturalTrackpadSpeed() {
        Object finger = firstFinger();
        assertTrue("not the natural trackpad: " + finger, finger instanceof TrackpadContext);
        float across = ReflectionHelpers.<Float>getField(finger, "sensitivityX");
        assertEquals("up and down", across, ReflectionHelpers.<Float>getField(finger, "sensitivityY"), 0f);
        return across;
    }

    private OverlayMenuButton sensitivityButton() {
        View button = findButton(menu, game.getString(R.string.overlay_menu_touch_sensitivity));
        assertNotNull("no Sensitivity on the stream menu", button);
        return (OverlayMenuButton) button;
    }

    // A tap on the Sensitivity button, which leaves the menu open
    private void pressSensitivity() {
        assertTrue(sensitivityButton().performClick());
        idle();
        assertEquals("the stream menu closed", View.VISIBLE, menu.getVisibility());
    }

    @Test
    public void theButtonSwitchesFromTheSettingTo100AndBack() {
        openStream(150);
        assertEquals("the stream didn't start at the setting's speed", 1.5, gamingTrackpadSpeed(), 0);
        assertTrue("Sensitivity starts off", sensitivityButton().isToggleOn());

        Object before = firstFinger();
        pressSensitivity();
        assertNotSame("the touch handling wasn't made anew", before, firstFinger());
        assertEquals(1.0, gamingTrackpadSpeed(), 0);
        assertEquals(game.getString(R.string.touch_sensitivity_off), ShadowToast.getTextOfLatestToast());
        assertFalse("Sensitivity still shows on", sensitivityButton().isToggleOn());

        pressSensitivity();
        assertEquals(1.5, gamingTrackpadSpeed(), 0);
        assertEquals(game.getString(R.string.touch_sensitivity_on, 150), ShadowToast.getTextOfLatestToast());
        assertTrue("Sensitivity shows off", sensitivityButton().isToggleOn());
    }

    @Test
    public void theNaturalTrackpadTakesTheSpeedTheButtonPicked() {
        openStream(50);
        pressSensitivity();
        // What the stream menu's Touch Mode picker does
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode",
                ClassParameter.from(int.class, Game.MOUSE_MODE_TRACKPAD_NATURAL));
        assertEquals(1f, naturalTrackpadSpeed(), 0f);

        pressSensitivity();
        assertEquals(0.5f, naturalTrackpadSpeed(), 0f);
    }

    @Test
    public void at100PercentTheButtonStartsOff() {
        openStream(100);
        assertEquals(1.0, gamingTrackpadSpeed(), 0);
        assertFalse("Sensitivity starts on", sensitivityButton().isToggleOn());
        // On, it uses the setting, which is the usual speed
        pressSensitivity();
        assertEquals(1.0, gamingTrackpadSpeed(), 0);
        assertEquals(game.getString(R.string.touch_sensitivity_on, 100), ShadowToast.getTextOfLatestToast());
        assertTrue(sensitivityButton().isToggleOn());
    }

    // Finds the menu button whose label is exactly this text
    private static View findButton(View root, String label) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return root;
                }
            }
            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
