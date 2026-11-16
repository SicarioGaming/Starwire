package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The open stream menu takes every key and controller event, so a button or key let go of while
 * it's open would stay held on the PC until it was pressed again. Every way of opening the menu
 * lets go of them first. GameBackKeyTest checks Back, a TV remote's Back and the floating button.
 * This checks the rest: the three-finger tap in every touch mode and with touch off, the left
 * edge swipe set to open the menu, and holding a controller's Select.
 *
 * Game runs as in GameBackKeyTest: with no game in its Intent, onCreate sets up the menu and then
 * finishes before anything connects, and each test sets up the controllers and the keyboard as
 * onCreate would, with a RecordingConnection for the PC. Touches come through the view behind the
 * stream, as in GameBrightnessTest. The clock is Robolectric's, so nothing takes real time.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameMenuLetsGoTest {
    // Every touch mode that takes touches
    private static final int[] TOUCH_MODES = {Game.MOUSE_MODE_TRACKPAD_NATURAL, Game.MOUSE_MODE_TRACKPAD_GAMING,
            Game.MOUSE_MODE_MULTI_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED};
    // Keys and buttons that come with no input device (device ID 0)
    private static final int NO_DEVICE = 0;

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private KeyRecordingConnection pc;
    private ControllerHandler controllers;
    private View background;
    private View menu;

    /** A RecordingConnection that also writes down which way each key went, and which key. */
    private static class KeyRecordingConnection extends RecordingConnection {
        KeyRecordingConnection(Context context) {
            super(context);
        }

        @Override
        public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags) {
            sent.add("key " + (keyDirection == KeyboardPacket.KEY_DOWN ? "down " : "up ")
                    + Integer.toHexString(keyMap & 0xFFFF));
        }
    }

    // edgeSwipeAction: the Left edge swipe action setting, or null to leave it at its default (off)
    private void openStreamScreen(int touchMode, String edgeSwipeAction) {
        closeControllers();
        if (edgeSwipeAction != null) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString("left_edge_swipe_action", edgeSwipeAction).commit();
        }
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();

        // What onCreate sets up after the point where a stream with no game stops. This isn't an
        // NVIDIA SHIELD, so the SHIELD's controller service isn't there for ControllerHandler.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        pc = new KeyRecordingConnection(context);
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        controllers = new ControllerHandler(game, pc, game, prefConfig);
        ReflectionHelpers.setField(game, "conn", pc);
        ReflectionHelpers.setField(game, "controllerHandler", controllers);
        ReflectionHelpers.setField(game, "keyboardTranslator", new KeyboardTranslator());
        ReflectionHelpers.callInstanceMethod(game, "setupOverlayMenu");
        // What the stream menu's Touch Mode picker does. It makes the touch handling anew, now
        // sending to the recording connection.
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        background = game.findViewById(R.id.backgroundTouchView);
        menu = game.findViewById(R.id.overlayMenuView);
        idle();
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        pc.sent.clear();
        assertEquals("the menu starts closed", View.GONE, menu.getVisibility());
    }

    @After
    public void closeControllers() {
        if (controllers != null) {
            controllers.destroy();
            controllers = null;
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Moves Robolectric's clock on and runs what comes due, with no real waiting
    private static void advance(int ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void key(int action, long downTime, int keyCode, int source) {
        game.dispatchKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, 0,
                NO_DEVICE, 0, 0, source));
    }

    // A controller's A, and Shift and W from a keyboard, held down. The PC gets A as 1000, and the
    // keys as 0x80 and the Windows key code: A0 for left Shift and 57 for W.
    private void holdAAndShiftW() {
        long downTime = SystemClock.uptimeMillis();
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD);
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_SHIFT_LEFT, InputDevice.SOURCE_KEYBOARD);
        key(KeyEvent.ACTION_DOWN, downTime, KeyEvent.KEYCODE_W, InputDevice.SOURCE_KEYBOARD);
        assertEquals(Arrays.asList("pad" + Integer.toHexString(ControllerPacket.A_FLAG),
                "key down 80a0", "key down 8057"), pc.sent);
        pc.sent.clear();
    }

    private void assertTheMenuOpenedAndThePcLetGo(String how) {
        assertEquals(how + " didn't open the menu", View.VISIBLE, menu.getVisibility());
        String lastPad = null;
        Set<String> keyUps = new HashSet<>();
        for (String input : pc.sent) {
            if (input.startsWith("pad")) {
                lastPad = input;
            }
            else if (input.startsWith("key up ")) {
                keyUps.add(input);
            }
        }
        assertEquals(how + ": the PC still holds A: " + pc.sent, "pad0", lastPad);
        assertEquals(how + ": the PC still holds Shift or W: " + pc.sent,
                new HashSet<>(Arrays.asList("key up 80a0", "key up 8057")), keyUps);
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private float across(float share) {
        return background.getWidth() * share;
    }

    private float middleY() {
        return background.getHeight() / 2f;
    }

    // Several fingers' event, as Game's touch listener gets it from the view behind the stream.
    // Each point is a finger's id, then its x and y. For a finger going down or up while others
    // stay, the action says which point it is (see pointer).
    private void fingers(int action, float[]... points) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[points.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[points.length];
        for (int i = 0; i < points.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = (int) points[i][0];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][1];
            coords[i].y = points[i][2];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, points.length, properties, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        game.onTouch(background, event);
        event.recycle();
        idle();
    }

    // ACTION_POINTER_DOWN or ACTION_POINTER_UP for the finger at this place in the event
    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    // Three fingers down one after another, and up again 100 ms after the third went down
    private void threeFingerTap() {
        float y = middleY();
        float[] one = {0, across(0.3f), y};
        float[] two = {1, across(0.5f), y};
        float[] three = {2, across(0.7f), y};
        fingers(MotionEvent.ACTION_DOWN, one);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), one, two, three);
        advance(100);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 2), one, two, three);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), one, two);
        fingers(MotionEvent.ACTION_UP, one);
    }

    // From 10 dp in from the edge, 70 dp to the right at once: past the 60 dp the swipe needs, well
    // inside its 500 ms. Then on another 50 dp before the finger lifts.
    private void swipeInFromTheLeftEdge() {
        fingers(MotionEvent.ACTION_DOWN, new float[] {0, dp(10), middleY()});
        fingers(MotionEvent.ACTION_MOVE, new float[] {0, dp(80), middleY()});
        fingers(MotionEvent.ACTION_MOVE, new float[] {0, dp(130), middleY()});
        fingers(MotionEvent.ACTION_UP, new float[] {0, dp(130), middleY()});
    }

    @Test
    public void theThreeFingerTapLetsGoInEveryTouchMode() {
        for (int touchMode : TOUCH_MODES) {
            openStreamScreen(touchMode, null);
            holdAAndShiftW();
            threeFingerTap();
            assertTheMenuOpenedAndThePcLetGo("touch mode " + touchMode + ": the three-finger tap");
        }
    }

    @Test
    public void theThreeFingerTapLetsGoInMultiTouchModeWhenThePcTakesNoTouches() {
        // Multi-touch mode works like direct touch then, and the tap goes the same way
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH, null);
        pc.takesTouches = false;
        holdAAndShiftW();
        threeFingerTap();
        assertTheMenuOpenedAndThePcLetGo("multi-touch with a PC that takes no touches: the three-finger tap");
    }

    @Test
    public void threeFingersLetGoWithTouchOff() {
        // With touch off, or in pan/zoom, the third finger going down opens the menu
        openStreamScreen(Game.MOUSE_MODE_DISABLED, null);
        holdAAndShiftW();
        threeFingerTap();
        assertTheMenuOpenedAndThePcLetGo("touch off: three fingers");
    }

    @Test
    public void theLeftEdgeSwipeLetsGoWhenItOpensTheMenu() {
        for (int touchMode : TOUCH_MODES) {
            openStreamScreen(touchMode, "overlay_menu");
            holdAAndShiftW();
            swipeInFromTheLeftEdge();
            assertTheMenuOpenedAndThePcLetGo("touch mode " + touchMode + ": the left edge swipe");
        }
    }

    @Test
    public void holdingSelectLetsGoOfKeysToo() {
        // It always let go of the controllers' buttons, but not of the keyboard's keys
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_NATURAL, null);
        holdAAndShiftW();
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        key(KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis(), KeyEvent.KEYCODE_BUTTON_SELECT,
                InputDevice.SOURCE_GAMEPAD);
        advance(prefConfig.overlayHoldDurationMs + 100);
        assertTheMenuOpenedAndThePcLetGo("holding Select");
    }
}
