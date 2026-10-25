package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

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
import java.util.List;

/**
 * The brightness slider on the stream screen, from Moonlight X. A swipe in from the left edge
 * opens it (or the stream menu, or nothing, as the Left edge swipe action setting says), while a
 * tap at the edge goes to the game like any other. The stream menu opens it for a controller,
 * and it hides a couple of seconds after it was last changed.
 *
 * Game runs as in GameFloatingButtonTest: with no game in its Intent, onCreate sets up the stream
 * screen, the slider among it, and then finishes before anything connects. The test then gives
 * Game a RecordingConnection, which writes down what Game sends the PC, and keeps the input
 * grabbed, as in a stream. Touches come through the view behind the stream, as a finger's do on
 * a device.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameBrightnessTest {
    // The touch modes where a swipe in from the left edge opens the slider. In multi-touch mode,
    // touches from the view behind the stream go the way they do in direct touch.
    private static final int[] SWIPE_MODES = {Game.MOUSE_MODE_TRACKPAD_NATURAL, Game.MOUSE_MODE_TRACKPAD_GAMING,
            Game.MOUSE_MODE_MULTI_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED};

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private RecordingConnection pc;
    private View background;
    private View stream;
    private View slider;
    private View menu;
    private SeekBar bar;
    private int closedTimes;

    private void openStreamScreen(int touchMode) {
        openStreamScreen(touchMode, null);
    }

    // edgeSwipeAction: the Left edge swipe action setting, or null to leave it at its default
    private void openStreamScreen(int touchMode, String edgeSwipeAction) {
        if (edgeSwipeAction != null) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString("left_edge_swipe_action", edgeSwipeAction).commit();
        }
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
        bar = game.findViewById(R.id.brightnessSlider);
        idle();
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        pc.sent.clear();
        assertEquals("the slider starts hidden", View.GONE, slider.getVisibility());
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Moves Robolectric's clock on and runs what comes due, with no real waiting
    private static void advance(int ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    // The slider hides AUTO_HIDE_DELAY_MS (2 s) after the last change. Robolectric's clock moves
    // on a few ms while the touch or key is handed out, so the checks leave 100 ms either side.
    private void assertHidesTwoSecondsLater(String early) {
        advance(1900);
        assertTrue(early, showing());
        advance(200);
        assertEquals("the slider didn't hide two seconds after the change", View.GONE, slider.getVisibility());
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private float middleY() {
        return background.getHeight() / 2f;
    }

    // One finger's event, as Game's touch listener gets it from the view behind the stream
    private void finger(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        game.onTouch(background, event);
        event.recycle();
        idle();
    }

    // A tap, and the time a click takes to be released on the PC
    private void tap(float x, float y) {
        finger(MotionEvent.ACTION_DOWN, x, y);
        finger(MotionEvent.ACTION_UP, x, y);
        advance(1000);
    }

    // 40 dp from the left, inside the 80 dp strip the slider used to take
    private void tapTheLeftEdge() {
        tap(dp(40), middleY());
    }

    private void tapTheMiddle() {
        tap(background.getWidth() / 2f, middleY());
    }

    // From 10 dp in from the edge, 70 dp to the right at once: past the 60 dp the swipe needs, well
    // inside its 500 ms. Then on another 50 dp before the finger lifts.
    private void swipeInFromTheLeftEdge() {
        finger(MotionEvent.ACTION_DOWN, dp(10), middleY());
        finger(MotionEvent.ACTION_MOVE, dp(80), middleY());
        sentAtTheSwipe = new ArrayList<>(pc.sent);
        finger(MotionEvent.ACTION_MOVE, dp(130), middleY());
        finger(MotionEvent.ACTION_UP, dp(130), middleY());
        advance(1000);
    }

    // What the PC had been sent when the swipe reached its 60 dp
    private List<String> sentAtTheSwipe;

    private boolean showing() {
        return slider.getVisibility() == View.VISIBLE;
    }

    // A mouse button pressed and released on the PC: the left one, or the right one in direct
    // touch with the buttons swapped
    private boolean clicked() {
        return (pc.sent.contains("down1") && pc.sent.contains("up1"))
                || (pc.sent.contains("down3") && pc.sent.contains("up3"));
    }

    private static String mode(int touchMode) {
        return "touch mode " + touchMode + ": ";
    }

    @Test
    public void aTapAtTheLeftEdgeGoesToTheGameInEveryTouchMode() {
        for (int touchMode : SWIPE_MODES) {
            openStreamScreen(touchMode);
            tapTheLeftEdge();
            assertFalse(mode(touchMode) + "the tap opened the slider", showing());
            assertTrue(mode(touchMode) + "the tap wasn't a click on the PC: " + pc.sent, clicked());
        }
        // With touch off, the stream ignores it
        openStreamScreen(Game.MOUSE_MODE_DISABLED);
        tapTheLeftEdge();
        assertFalse("touch off: the tap opened the slider", showing());
        assertEquals(new ArrayList<String>(), pc.sent);
    }

    @Test
    public void aSwipeInFromTheLeftEdgeOpensTheSliderAndTheRestOfItGoesNoFurther() {
        for (int touchMode : SWIPE_MODES) {
            openStreamScreen(touchMode);
            swipeInFromTheLeftEdge();
            assertTrue(mode(touchMode) + "the swipe didn't open the slider", showing());
            // The finger carries on and lifts: the touch was cancelled where the swipe was seen
            assertEquals(mode(touchMode) + "the rest of the swipe reached the PC", sentAtTheSwipe, pc.sent);
        }
    }

    @Test
    public void aSlowSteepOrInnerSwipeLeavesTheSliderShut() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        // Longer than 500 ms to get 60 dp in
        finger(MotionEvent.ACTION_DOWN, dp(10), middleY());
        advance(600);
        finger(MotionEvent.ACTION_MOVE, dp(80), middleY());
        finger(MotionEvent.ACTION_UP, dp(80), middleY());
        assertFalse("a slow swipe opened the slider", showing());
        // More down than in
        finger(MotionEvent.ACTION_DOWN, dp(10), middleY() - dp(100));
        finger(MotionEvent.ACTION_MOVE, dp(80), middleY());
        finger(MotionEvent.ACTION_UP, dp(80), middleY());
        assertFalse("a steep swipe opened the slider", showing());
        // From 100 dp in, past the 80 dp strip at the edge
        finger(MotionEvent.ACTION_DOWN, dp(100), middleY());
        finger(MotionEvent.ACTION_MOVE, dp(170), middleY());
        finger(MotionEvent.ACTION_UP, dp(170), middleY());
        assertFalse("a swipe from further in opened the slider", showing());
    }

    @Test
    public void theSettingCanOpenTheStreamMenuInsteadOrNothing() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING, "overlay_menu");
        swipeInFromTheLeftEdge();
        assertEquals("the swipe didn't open the stream menu", View.VISIBLE, menu.getVisibility());
        assertFalse("the swipe opened the slider", showing());

        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING, "off");
        swipeInFromTheLeftEdge();
        assertFalse("the swipe opened the slider", showing());
        assertEquals("the swipe opened the stream menu", View.GONE, menu.getVisibility());
        assertTrue("the swipe didn't move the PC's cursor: " + pc.sent, pc.sent.contains("move"));
    }

    @Test
    public void aTouchOnTheStreamClosesTheSlider() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        swipeInFromTheLeftEdge();
        assertTrue(showing());
        pc.sent.clear();

        tapTheMiddle();
        assertEquals(View.GONE, slider.getVisibility());
        // The next one goes to the game again
        tapTheMiddle();
        assertTrue("the next tap wasn't a click on the PC: " + pc.sent, clicked());
    }

    @Test
    public void theSliderStaysUpUntilChangedThenHidesTwoSecondsAfter() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        swipeInFromTheLeftEdge();
        // It used to hide 3 s after it opened, changed or not
        advance(10_000);
        assertTrue("the slider hid before it was changed", showing());

        // A drag on the slider, to the middle of its track
        dragTheSliderTo(0.5f);
        assertEquals(50, bar.getProgress(), 2);
        assertEquals(bar.getProgress() / 100f, game.getWindow().getAttributes().screenBrightness, 0.001f);
        assertHidesTwoSecondsLater("the slider hid sooner than two seconds after the change");
    }

    // Down and up on the slider's track, at this share of its length. The track is rotated, so
    // the touch goes to the slider in its own coordinates.
    private void dragTheSliderTo(float share) {
        float x = bar.getPaddingLeft() + share * (bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
        float y = bar.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
            bar.dispatchTouchEvent(event);
            event.recycle();
        }
        idle();
    }

    // What the stream menu's Brightness tile does (Game's onShowBrightness): the slider, focused
    // for a controller, which gives focus back to the stream when it closes. Called by reflection,
    // like Game's private parts, so this test also builds against 0.2.1, where it fails.
    private void openFromTheMenu() {
        Object brightness = ReflectionHelpers.getField(game, "brightnessSliderView");
        ReflectionHelpers.callInstanceMethod(brightness, "showForController",
                ClassParameter.from(Runnable.class, (Runnable) () -> {
                    closedTimes++;
                    stream.requestFocus();
                }));
        idle();
        assertTrue("the slider didn't open", showing());
        assertTrue("the slider doesn't have the controller's focus", bar.hasFocus());
    }

    private static KeyEvent key(int action, int keyCode) {
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD);
    }

    // A press and release as the window hands them out: to the view with focus, then to the
    // activity if that view doesn't take it. True when both were taken.
    private boolean press(int keyCode) {
        boolean down = game.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode));
        boolean up = game.dispatchKeyEvent(key(KeyEvent.ACTION_UP, keyCode));
        idle();
        return down && up;
    }

    @Test
    public void fromTheMenuTheDpadStepsTheBrightnessByFivePercent() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        openFromTheMenu();
        assertEquals("starts on Auto", 0, bar.getProgress());

        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(5, bar.getProgress());
        assertEquals(0.05f, game.getWindow().getAttributes().screenBrightness, 0.001f);
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(10, bar.getProgress());
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(5, bar.getProgress());
        assertEquals("the level isn't kept for the next stream", 0.05f,
                game.getSharedPreferences("GameSettings", Context.MODE_PRIVATE).getFloat("streamBrightness", -1), 0.001f);

        // Down past the bottom is Auto, the system's own brightness
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(0, bar.getProgress());
        assertEquals(android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                game.getWindow().getAttributes().screenBrightness, 0.001f);
    }

    @Test
    public void aLevelSetByTouchSnapsOntoTheFivePercentSteps() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        openFromTheMenu();
        bar.setProgress(37);
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(40, bar.getProgress());
        bar.setProgress(37);
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(35, bar.getProgress());
        bar.setProgress(98);
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals("stops at 100%", 100, bar.getProgress());
    }

    @Test
    public void fromTheMenuNoInputReachesTheStreamWhileTheSliderIsUp() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        openFromTheMenu();
        // Keys that come to the activity rather than the focused slider, like the ones a
        // controller's own handling passes on, are taken too
        for (int keyCode : new int[] {KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SPACE}) {
            assertTrue("key " + keyCode + " went to the stream",
                    game.onKeyDown(keyCode, key(KeyEvent.ACTION_DOWN, keyCode)));
            assertTrue("key " + keyCode + " went to the stream",
                    game.onKeyUp(keyCode, key(KeyEvent.ACTION_UP, keyCode)));
        }
        assertTrue("a stick push went to the stream", game.onGenericMotionEvent(stick(0.2f)));
        assertTrue("the slider closed", showing());
        assertEquals(new ArrayList<String>(), pc.sent);
    }

    @Test
    public void aBAndBackCloseItAndTheirReleaseDoesntReachTheStream() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        int[] closers = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER};
        for (int i = 0; i < closers.length; i++) {
            int keyCode = closers[i];
            openFromTheMenu();
            assertTrue("key " + keyCode + " went to the stream", press(keyCode));
            assertEquals("key " + keyCode + " didn't close the slider", View.GONE, slider.getVisibility());
            assertEquals("focus didn't go back to the stream", i + 1, closedTimes);
            assertTrue(stream.hasFocus());
            // Closed, it takes no more keys, so the controller's input goes to the stream again.
            // (Game passes those to its controller handling, which needs a stream to run.)
            Object brightness = ReflectionHelpers.getField(game, "brightnessSliderView");
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                assertFalse("key " + keyCode + ": the slider kept the input after closing",
                        (Boolean) ReflectionHelpers.callInstanceMethod(brightness, "handleKeyEvent",
                                ClassParameter.from(KeyEvent.class, key(action, KeyEvent.KEYCODE_DPAD_UP))));
            }
        }
        assertEquals(new ArrayList<String>(), pc.sent);
    }

    // Whether this view, or any view inside it, shows exactly this text
    private static boolean hasText(View view, String text) {
        if (view instanceof android.widget.TextView) {
            return text.contentEquals(((android.widget.TextView) view).getText());
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean theStreamMenuHasBrightness() {
        ReflectionHelpers.callInstanceMethod(menu, "show");
        idle();
        assertEquals("the stream menu didn't open", View.VISIBLE, menu.getVisibility());
        return hasText(menu, context.getString(R.string.overlay_menu_brightness));
    }

    @Test
    public void theStreamMenuHasABrightnessTile() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        assertTrue("no Brightness tile in the stream menu", theStreamMenuHasBrightness());
    }

    @Test
    public void onAndroidTvTheStreamMenuLeavesBrightnessOut() {
        // The app can't change a TV's screen brightness
        shadowOf((UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE))
                .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        assertTrue("Game didn't see a TV", (Boolean) ReflectionHelpers.getField(game, "isAndroidTV"));
        assertFalse("the stream menu has a Brightness tile on a TV", theStreamMenuHasBrightness());
    }

    // A left stick (or d-pad hat) position, up negative
    private static MotionEvent stick(float y) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].setAxisValue(MotionEvent.AXIS_Y, y);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f,
                -1, 0, InputDevice.SOURCE_JOYSTICK, 0);
    }

    @Test
    public void theStickStepsOnceAPushThenRepeatsWhileHeld() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        openFromTheMenu();
        assertTrue(game.onGenericMotionEvent(stick(-1f)));
        assertEquals(5, bar.getProgress());
        assertTrue(game.onGenericMotionEvent(stick(-1f)));
        assertEquals("held, it stepped again at once", 5, bar.getProgress());
        advance(150);
        assertTrue(game.onGenericMotionEvent(stick(-1f)));
        assertEquals("held, it didn't repeat", 10, bar.getProgress());
        assertTrue(game.onGenericMotionEvent(stick(0f)));
        assertTrue(game.onGenericMotionEvent(stick(1f)));
        assertEquals("let go and pushed down, it didn't step at once", 5, bar.getProgress());
    }

    @Test
    public void fromTheMenuItAlsoHidesTwoSecondsAfterTheLastChange() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        openFromTheMenu();
        advance(10_000);
        assertTrue("the slider hid before it was changed", showing());

        // The second change, 1.5 s after the first, starts the two seconds again
        press(KeyEvent.KEYCODE_DPAD_UP);
        advance(1500);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertHidesTwoSecondsLater("the slider hid sooner than two seconds after the last change");
        assertEquals("focus didn't go back to the stream", 1, closedTimes);
        assertTrue(stream.hasFocus());
    }
}
