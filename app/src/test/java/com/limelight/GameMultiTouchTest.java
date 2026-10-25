package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.VirtualControllerElement;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multi-touch mode on the stream screen: each finger goes to the PC as a touch of its own, when
 * the PC's host takes touches. It used to go the way it does in direct touch, as mouse clicks,
 * since the check for it only took touches on the stream view, and fingers never land there: the
 * stream view lets them through to the view behind it.
 *
 * Game runs as in GameBrightnessTest: with no game in its Intent, onCreate sets up the stream
 * screen and finishes before anything connects, and the test gives it a RecordingConnection. The
 * fingers here go through Game's window, as on a device, so they reach whichever view is under
 * them.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameMultiTouchTest {
    // The touch modes that send the PC the mouse, as before
    private static final int[] MOUSE_MODES = {Game.MOUSE_MODE_DIRECT_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED,
            Game.MOUSE_MODE_TRACKPAD_NATURAL, Game.MOUSE_MODE_TRACKPAD_GAMING};

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private RecordingConnection pc;
    private View background;
    private View stream;
    private View slider;
    private View menu;

    private void openStreamScreen(int touchMode) {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        pc = new RecordingConnection(context);
        ReflectionHelpers.setField(game, "conn", pc);
        // What the stream menu's Touch Mode picker does. It makes the touch handling anew, now
        // sending to the recording connection.
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        background = game.findViewById(R.id.backgroundTouchView);
        stream = game.findViewById(R.id.surfaceView);
        slider = game.findViewById(R.id.brightnessSliderContainer);
        menu = game.findViewById(R.id.overlayMenuView);
        idle();
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        pc.sent.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Moves Robolectric's clock on and runs what comes due, with no real waiting
    private static void advance(int ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
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

    // A finger's id, then where it is on the view behind the stream, which fills the screen
    private static float[] at(int id, float x, float y) {
        return new float[] {id, x, y};
    }

    // ACTION_POINTER_DOWN or ACTION_POINTER_UP for the finger at this place in the event
    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    // The fingers' event as Game's window hands it out, to the views under the fingers. For a
    // finger going down or up while others stay, the action says which point it is.
    private void fingers(int action, float[]... points) {
        int[] origin = new int[2];
        background.getLocationInWindow(origin);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[points.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[points.length];
        for (int i = 0; i < points.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = (int) points[i][0];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = origin[0] + points[i][1];
            coords[i].y = origin[1] + points[i][2];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, points.length, properties, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        game.dispatchTouchEvent(event);
        event.recycle();
        idle();
    }

    private void tap(float x, float y) {
        fingers(MotionEvent.ACTION_DOWN, at(0, x, y));
        fingers(MotionEvent.ACTION_UP, at(0, x, y));
        advance(1000);
    }

    private boolean sliderShowing() {
        return slider.getVisibility() == View.VISIBLE;
    }

    private boolean menuShowing() {
        return menu.getVisibility() == View.VISIBLE;
    }

    // Anything the PC got that wasn't a touch: a mouse click or move, a key or a gamepad
    private List<String> notTouches() {
        List<String> others = new ArrayList<>();
        for (String input : pc.sent) {
            if (!input.startsWith("touch")) {
                others.add(input);
            }
        }
        return others;
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    @Test
    public void oneFingerReachesThePcAsATouchWhereItIsOnTheStream() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.25f), middleY()));
        fingers(MotionEvent.ACTION_MOVE, at(0, across(0.5f), middleY()));
        fingers(MotionEvent.ACTION_UP, at(0, across(0.5f), middleY()));
        advance(1000);
        assertEquals("the PC got the mouse, not a touch", list(), notTouches());
        assertEquals(list("down 0 at 0.25,0.50", "move 0 at 0.50,0.50", "up 0 at 0.50,0.50"), pc.touches);
    }

    @Test
    public void twoFingersReachThePcAsTwoTouches() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        float y = middleY();
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.4f), y));
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), at(0, across(0.4f), y), at(1, across(0.6f), y));
        fingers(MotionEvent.ACTION_MOVE, at(0, across(0.3f), y), at(1, across(0.7f), y));
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 0), at(0, across(0.3f), y), at(1, across(0.7f), y));
        fingers(MotionEvent.ACTION_MOVE, at(1, across(0.9f), y));
        fingers(MotionEvent.ACTION_UP, at(1, across(0.9f), y));
        advance(1000);
        assertEquals("the PC got the mouse, not touches", list(), notTouches());
        assertEquals(list("down 0 at 0.40,0.50", "down 1 at 0.60,0.50",
                "move 0 at 0.30,0.50", "move 1 at 0.70,0.50",
                "up 0 at 0.30,0.50", "move 1 at 0.90,0.50", "up 1 at 0.90,0.50"), pc.touches);
    }

    // Two fingers move apart, from 20% of the screen apart to 60%, then lift
    private void pinchOut() {
        float y = middleY();
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.4f), y));
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), at(0, across(0.4f), y), at(1, across(0.6f), y));
        for (int step = 1; step <= 4; step++) {
            float apart = 0.05f * step;
            fingers(MotionEvent.ACTION_MOVE, at(0, across(0.4f - apart), y), at(1, across(0.6f + apart), y));
        }
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 0), at(0, across(0.2f), y), at(1, across(0.8f), y));
        fingers(MotionEvent.ACTION_UP, at(1, across(0.8f), y));
        advance(1000);
    }

    @Test
    public void aPinchGoesToThePcRatherThanZoomingTheStream() {
        // Pinch to zoom is on by default, and not used in multi-touch mode
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        pinchOut();
        assertEquals("the pinch zoomed the stream", 1f, stream.getScaleX(), 0f);
        assertEquals(list(), notTouches());
        // Each finger down, 4 moves of both, and each finger up
        assertEquals("the pinch didn't all reach the PC: " + pc.touches, 12, pc.touches.size());

        // In direct touch the same pinch zooms the stream, as before
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        pinchOut();
        assertTrue("direct touch: the pinch didn't zoom the stream", stream.getScaleX() > 1.5f);
    }

    @Test
    public void aTouchIsPlacedOnTheStreamWhenItDoesntFillTheScreen() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        // A 4:3 stream on a 16:9 screen, with a bar down each side
        ((StreamView) stream).setDesiredAspectRatio(4.0 / 3.0);
        stream.requestLayout();
        idle();
        assertTrue("the stream fills the screen", stream.getX() > dp(100));

        tap(stream.getX() + stream.getWidth() * 0.25f, stream.getY() + stream.getHeight() * 0.75f);
        // In the bar to the right of the stream, it's put on the stream's right edge
        tap(stream.getX() + stream.getWidth() + dp(20), middleY());
        assertEquals(list("down 0 at 0.25,0.75", "up 0 at 0.25,0.75", "down 0 at 1.00,0.50", "up 0 at 1.00,0.50"),
                pc.touches);
    }

    @Test
    public void aTapAtTheLeftEdgeReachesThePcAsATouch() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        // 40 dp from the left, inside the 80 dp strip the edge swipe starts in
        tap(dp(40), middleY());
        assertFalse("the tap opened the brightness slider", sliderShowing());
        assertEquals(list(), notTouches());
        assertEquals(list("touch1", "touch2"), pc.sent);
    }

    // From 10 dp in from the edge, 70 dp to the right at once: past the 60 dp the swipe needs, well
    // inside its 500 ms. Then on another 50 dp before the finger lifts.
    private void swipeInFromTheLeftEdge() {
        fingers(MotionEvent.ACTION_DOWN, at(0, dp(10), middleY()));
        fingers(MotionEvent.ACTION_MOVE, at(0, dp(80), middleY()));
        fingers(MotionEvent.ACTION_MOVE, at(0, dp(130), middleY()));
        fingers(MotionEvent.ACTION_UP, at(0, dp(130), middleY()));
        advance(1000);
    }

    @Test
    public void aSwipeInFromTheLeftEdgeOpensTheSliderAndTakesTheTouchBackFromThePc() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        swipeInFromTheLeftEdge();
        assertTrue("the swipe didn't open the brightness slider", sliderShowing());
        // The touch started on the PC, and the PC is told to forget it once it's a swipe.
        // The rest of it goes no further.
        assertEquals(list("touch1", "touch7"), pc.sent);

        // The touch that closes the slider doesn't reach the PC; the next one does
        pc.sent.clear();
        tap(across(0.5f), middleY());
        assertFalse("the tap didn't close the slider", sliderShowing());
        assertEquals(list(), pc.sent);
        tap(across(0.5f), middleY());
        assertEquals(list("touch1", "touch2"), pc.sent);
    }

    @Test
    public void theSwipeCanOpenTheStreamMenuInMultiTouchModeToo() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("left_edge_swipe_action", "overlay_menu").commit();
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        swipeInFromTheLeftEdge();
        assertTrue("the swipe didn't open the stream menu", menuShowing());
        assertEquals(list("touch1", "touch7"), pc.sent);
    }

    // Three fingers down one after another, then up, this long after the third went down
    private void threeFingers(int heldMs) {
        float y = middleY();
        float[] one = at(0, across(0.3f), y);
        float[] two = at(1, across(0.5f), y);
        float[] three = at(2, across(0.7f), y);
        fingers(MotionEvent.ACTION_DOWN, one);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), one, two, three);
        advance(heldMs);
        fingers(MotionEvent.ACTION_MOVE, one, two, three);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 2), one, two, three);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), one, two);
        fingers(MotionEvent.ACTION_UP, one);
    }

    @Test
    public void aThreeFingerTapStillOpensTheStreamMenu() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        threeFingers(100);
        assertTrue("the three-finger tap didn't open the stream menu", menuShowing());
        // The first two fingers reached the PC. The third tells it to forget them, and the
        // rest of the touch goes no further, as in the other touch modes.
        assertEquals(list("down 0 at 0.30,0.50", "down 1 at 0.50,0.50", "cancel all 0 at 0.00,0.00"), pc.touches);
        assertEquals(list(), notTouches());
    }

    @Test
    public void threeFingersHeldLeaveTheMenuShutAndSendThePcNoMore() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        threeFingers(400);
        assertFalse("three fingers held opened the stream menu", menuShowing());
        assertEquals(list("touch1", "touch1", "touch7"), pc.sent);

        // The next touch goes to the PC again
        pc.sent.clear();
        tap(across(0.5f), middleY());
        assertEquals(list("touch1", "touch2"), pc.sent);
    }

    // The fingers' event handed straight to Game's touch listener, at their places on the view
    // behind the stream, with these flags. Robolectric's window loses an event's flags, so a
    // finger the system cancelled (FLAG_CANCELED) has to come this way.
    private void straightToGame(int action, int flags, float[]... points) {
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
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
        assertEquals("the event lost its flags", flags, event.getFlags());
        game.onTouch(background, event);
        event.recycle();
        idle();
    }

    // A quick three-finger tap, straight to Game, whose last finger lifts with these flags
    private void threeFingerTapStraightToGame(int lastFingerFlags) {
        float y = middleY();
        float[] one = at(0, across(0.3f), y);
        float[] two = at(1, across(0.5f), y);
        float[] three = at(2, across(0.7f), y);
        straightToGame(MotionEvent.ACTION_DOWN, 0, one);
        straightToGame(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 0, one, two);
        straightToGame(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), 0, one, two, three);
        straightToGame(pointer(MotionEvent.ACTION_POINTER_UP, 2), 0, one, two, three);
        straightToGame(pointer(MotionEvent.ACTION_POINTER_UP, 1), 0, one, two);
        straightToGame(MotionEvent.ACTION_UP, lastFingerFlags, one);
    }

    @Test
    public void aThreeFingerTapWhoseLastFingerTheSystemCancelledLeavesTheMenuShut() {
        // On Android 13 and later, Game leaves the menu shut when the last finger's up is marked
        // as cancelled, as it does in the other touch modes
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        threeFingerTapStraightToGame(MotionEvent.FLAG_CANCELED);
        assertFalse("a finger the system cancelled opened the stream menu", menuShowing());
        assertEquals(list("down 0 at 0.30,0.50", "down 1 at 0.50,0.50", "cancel all 0 at 0.00,0.00"), pc.touches);

        // The same tap with the finger lifted as usual opens it
        threeFingerTapStraightToGame(0);
        assertTrue("the three-finger tap didn't open the stream menu", menuShowing());
    }

    // A finger just above the screen, as the stream view hands it to Game, half way across and
    // down the stream
    private void hover() {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = stream.getWidth() / 2f;
        coords[0].y = stream.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1, properties, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        game.onGenericMotion(stream, event);
        event.recycle();
        idle();
    }

    @Test
    public void onceATouchThePcWasToldToForgetIsOverTheFingersReachItAgain() {
        // A finger hovering between touches doesn't start a touch, so it shows whether the
        // touch taken back from the PC is over, however it ended
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        float y = middleY();
        float[] one = at(0, across(0.3f), y);
        float[] two = at(1, across(0.5f), y);
        float[] three = at(2, across(0.7f), y);
        // Three fingers held, then lifted
        threeFingers(400);
        hover();
        // Three fingers, then the system cancels the touch
        fingers(MotionEvent.ACTION_DOWN, one);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), one, two, three);
        fingers(MotionEvent.ACTION_CANCEL, one, two, three);
        hover();
        List<String> threeFingersTakenBack =
                list("down 0 at 0.30,0.50", "down 1 at 0.50,0.50", "cancel all 0 at 0.00,0.00");
        List<String> expected = new ArrayList<>(threeFingersTakenBack);
        expected.add("hover 0 at 0.50,0.50");
        expected.addAll(threeFingersTakenBack);
        expected.add("hover 0 at 0.50,0.50");
        assertEquals(expected, pc.touches);
        assertFalse("three fingers held opened the stream menu", menuShowing());
    }

    @Test
    public void withAPcThatTakesNoTouchesMultiTouchWorksAsDirectTouch() {
        // As with GeForce Experience
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        pc.takesTouches = false;
        tap(across(0.5f), middleY());
        assertTrue("the tap wasn't a click on the PC: " + pc.sent,
                pc.sent.contains("down1") && pc.sent.contains("up1"));

        // The swipe and the three-finger tap work as in direct touch
        swipeInFromTheLeftEdge();
        assertTrue("the swipe didn't open the brightness slider", sliderShowing());
        tap(across(0.5f), middleY());
        threeFingers(100);
        assertTrue("the three-finger tap didn't open the stream menu", menuShowing());
        assertEquals(list(), pc.touches);
    }

    @Test
    public void theOtherTouchModesStillSendTheMouse() {
        for (int touchMode : MOUSE_MODES) {
            openStreamScreen(touchMode);
            tap(across(0.5f), middleY());
            assertEquals("touch mode " + touchMode + " sent a touch", list(), pc.touches);
            // A left click, or a right one in direct touch with the buttons swapped
            assertTrue("touch mode " + touchMode + ": the tap wasn't a click on the PC: " + pc.sent,
                    (pc.sent.contains("down1") && pc.sent.contains("up1"))
                            || (pc.sent.contains("down3") && pc.sent.contains("up3")));
        }

        // With touch off, nothing reaches the PC
        openStreamScreen(Game.MOUSE_MODE_DISABLED);
        tap(across(0.5f), middleY());
        assertEquals(list(), pc.sent);
    }

    @Test
    public void theOtherTouchModesSendNoTouchForTheSwipeOrTheThreeFingerTap() {
        for (int touchMode : MOUSE_MODES) {
            openStreamScreen(touchMode);
            swipeInFromTheLeftEdge();
            assertTrue("touch mode " + touchMode + ": the swipe didn't open the brightness slider", sliderShowing());
            // Closes the slider
            tap(across(0.5f), middleY());
            threeFingers(100);
            assertTrue("touch mode " + touchMode + ": the three-finger tap didn't open the stream menu",
                    menuShowing());
            assertEquals("touch mode " + touchMode + " sent a touch", list(), pc.touches);
        }
    }

    @Test
    public void panZoomModeStillTakesTheFingersInMultiTouchMode() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        togglePanZoom();
        pinchOut();
        assertTrue("the pinch didn't zoom the stream", stream.getScaleX() > 1.5f);
        tap(across(0.5f), middleY());
        assertEquals(list(), pc.sent);
    }

    // What the stream menu's Pan/Zoom tile does
    private void togglePanZoom() {
        ReflectionHelpers.callInstanceMethod(game, "togglePanZoomMode");
        idle();
    }

    // What the stream menu's Touch Mode picker does, and the Touch Sensitivity tile with the
    // mode it's in
    private void pickTouchMode(int touchMode) {
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        idle();
    }

    @Test
    public void turningOnPanZoomWithAFingerDownTakesTheTouchBackFromThePc() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.5f), middleY()));
        // Tapped with the other hand, while the finger stays on the stream
        togglePanZoom();
        fingers(MotionEvent.ACTION_MOVE, at(0, across(0.6f), middleY()));
        fingers(MotionEvent.ACTION_UP, at(0, across(0.6f), middleY()));
        advance(1000);
        assertEquals("the touch was left down on the PC",
                list("down 0 at 0.50,0.50", "cancel all 0 at 0.00,0.00"), pc.touches);

        // Turned off again, it sends the PC nothing more, and the next tap reaches the PC
        togglePanZoom();
        tap(across(0.5f), middleY());
        assertEquals(list("down 0 at 0.50,0.50", "cancel all 0 at 0.00,0.00",
                "down 0 at 0.50,0.50", "up 0 at 0.50,0.50"), pc.touches);
        assertEquals(list(), notTouches());
    }

    @Test
    public void pickingATouchModeWithAFingerDownTakesTheTouchBackFromThePc() {
        // Each mode in the picker, and multi-touch itself, which the Touch Sensitivity tile picks
        int[] touchModes = {Game.MOUSE_MODE_DIRECT_TOUCH, Game.MOUSE_MODE_TRACKPAD_NATURAL,
                Game.MOUSE_MODE_TRACKPAD_GAMING, Game.MOUSE_MODE_DISABLED,
                Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED, Game.MOUSE_MODE_MULTI_TOUCH};
        for (int touchMode : touchModes) {
            openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
            fingers(MotionEvent.ACTION_DOWN, at(0, across(0.5f), middleY()));
            pickTouchMode(touchMode);
            fingers(MotionEvent.ACTION_UP, at(0, across(0.5f), middleY()));
            advance(1000);
            // In multi-touch mode the rest of the touch goes no further, as after a swipe
            assertEquals("touch mode " + touchMode + ": the touch wasn't taken back from the PC",
                    list("down 0 at 0.50,0.50", "cancel all 0 at 0.00,0.00"), pc.touches);
        }
    }

    @Test
    public void changingModesSendsNothingWhenThePcHasNoTouchDown() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        // After a tap
        tap(across(0.5f), middleY());
        togglePanZoom();
        togglePanZoom();
        pickTouchMode(Game.MOUSE_MODE_MULTI_TOUCH);
        // After a touch the system cancels, which the PC is told to forget
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.5f), middleY()));
        fingers(MotionEvent.ACTION_CANCEL, at(0, across(0.5f), middleY()));
        advance(1000);
        togglePanZoom();
        togglePanZoom();
        pickTouchMode(Game.MOUSE_MODE_DIRECT_TOUCH);
        assertEquals(list("down 0 at 0.50,0.50", "up 0 at 0.50,0.50",
                "down 0 at 0.50,0.50", "cancel all 0 at 0.00,0.00"), pc.touches);
    }

    @Test
    public void aFingerOnTheOnScreenControlsPressesItsButtonWhileAnotherTouchesThePc() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        // The on-screen controls, as Game sets them up for a stream, sending to the recording
        // connection through Game's controller handling. There's no SHIELD accessory service
        // to bind to, as on any device but an NVIDIA SHIELD.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        ControllerHandler controllers = new ControllerHandler(game, pc, game, prefConfig);
        ReflectionHelpers.setField(game, "controllerHandler", controllers);
        try {
            VirtualController onScreen = new VirtualController(controllers, (FrameLayout) stream.getParent(), game);
            onScreen.refreshLayout();
            onScreen.show();
            ReflectionHelpers.setField(game, "virtualController", onScreen);
            idle();
            View buttonA = null;
            for (VirtualControllerElement element : onScreen.getElements()) {
                if ((int) ReflectionHelpers.getField(element, "elementId") == VirtualControllerElement.EID_A) {
                    buttonA = element;
                }
            }
            assertNotNull("no A button on screen", buttonA);
            int[] buttonSpot = new int[2];
            buttonA.getLocationInWindow(buttonSpot);
            int[] origin = new int[2];
            background.getLocationInWindow(origin);
            float aX = buttonSpot[0] - origin[0] + buttonA.getWidth() / 2f;
            float aY = buttonSpot[1] - origin[1] + buttonA.getHeight() / 2f;
            pc.sent.clear();

            // A thumb on A, then a finger on the stream, then both lift
            float[] thumb = at(0, aX, aY);
            float[] finger = at(1, across(0.5f), middleY());
            fingers(MotionEvent.ACTION_DOWN, thumb);
            fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), thumb, finger);
            fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), thumb, finger);
            fingers(MotionEvent.ACTION_UP, thumb);
            advance(1000);
            assertEquals("the finger on the stream", list("down 1 at 0.50,0.50", "up 1 at 0.50,0.50"), pc.touches);
            List<String> pad = notTouches();
            assertTrue("A wasn't pressed on the PC: " + pc.sent, pad.contains("pad1000"));
            assertEquals("A was left held on the PC: " + pc.sent, "pad0", pad.get(pad.size() - 1));
        } finally {
            controllers.destroy();
        }
    }
}
