package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
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

/**
 * The brightness slider on the stream screen, from Moonlight X: the left edge opens it only with
 * the touchscreen as a trackpad, the stream menu opens it for a controller, and it hides a couple
 * of seconds after it was last changed.
 *
 * Game runs as in GameFloatingButtonTest: with no game in its Intent, onCreate sets up the stream
 * screen, the slider among it, and then finishes before anything connects. The test then lets go
 * of the input, as Ctrl+Alt+Shift+Z does in a stream, so Game's touch and key handling hand back
 * everything they would send to the PC and answer false. onTouch or onKeyDown answering true
 * means something took the input before it could reach the game.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameBrightnessTest {
    private static final int[] TRACKPAD_MODES = {Game.MOUSE_MODE_TRACKPAD_NATURAL, Game.MOUSE_MODE_TRACKPAD_GAMING};
    // The touch modes where a touch goes to the PC where it lands
    private static final int[] WHERE_IT_LANDS_MODES = {Game.MOUSE_MODE_MULTI_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH,
            Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED, Game.MOUSE_MODE_DISABLED};

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private View stream;
    private View slider;
    private SeekBar bar;
    private int closedTimes;

    private void openStreamScreen(int touchMode) {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        // What the stream menu's Touch Mode picker does
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        ReflectionHelpers.callInstanceMethod(game, "setInputGrabState", ClassParameter.from(boolean.class, false));
        stream = game.findViewById(R.id.surfaceView);
        slider = game.findViewById(R.id.brightnessSliderContainer);
        bar = game.findViewById(R.id.brightnessSlider);
        idle();
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

    // A finger going down on the stream, as Game's touch listener gets it
    private boolean touchDown(float x, float y) {
        MotionEvent event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean taken = game.onTouch(stream, event);
        event.recycle();
        idle();
        return taken;
    }

    // 40 dp from the left, inside the 80 dp strip the slider used to take in every touch mode
    private boolean touchTheLeftEdge() {
        return touchDown(dp(40), stream.getHeight() / 2f);
    }

    private boolean touchTheMiddle() {
        return touchDown(stream.getWidth() / 2f, stream.getHeight() / 2f);
    }

    private boolean showing() {
        return slider.getVisibility() == View.VISIBLE;
    }

    @Test
    public void whereTouchesLandOnThePcTheLeftEdgeGoesToTheGame() {
        for (int mode : WHERE_IT_LANDS_MODES) {
            openStreamScreen(mode);
            assertFalse("touch mode " + mode + ": the left edge didn't reach the game", touchTheLeftEdge());
            assertEquals("touch mode " + mode + ": the slider opened", View.GONE, slider.getVisibility());
        }
    }

    @Test
    public void asATrackpadTheLeftEdgeOpensTheSliderAndKeepsTheTouch() {
        for (int mode : TRACKPAD_MODES) {
            openStreamScreen(mode);
            assertFalse("touch mode " + mode + ": the middle didn't reach the game", touchTheMiddle());
            assertFalse("touch mode " + mode + ": the middle opened the slider", showing());
            assertTrue("touch mode " + mode + ": the left edge reached the game", touchTheLeftEdge());
            assertTrue("touch mode " + mode + ": the left edge didn't open the slider", showing());
        }
    }

    @Test
    public void aTouchOnTheStreamClosesTheSliderAndGoesNoFurther() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        touchTheLeftEdge();
        assertTrue(showing());

        assertTrue("the touch that closed the slider reached the game", touchTheMiddle());
        assertEquals(View.GONE, slider.getVisibility());
        // The next one goes to the game again
        assertFalse(touchTheMiddle());
    }

    @Test
    public void theSliderStaysUpUntilChangedThenHidesTwoSecondsAfter() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        touchTheLeftEdge();
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
