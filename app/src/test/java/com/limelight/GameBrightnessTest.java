package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.UiModeManager;
import android.content.ComponentName;
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

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

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
    // touches reach the PC as touches; GameMultiTouchTest has more on that mode.
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

    // Several fingers' event. Each point is a finger's id, then its x and y. For a finger going
    // down or up while others stay, the action says which point it is (see pointer).
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
    // touch with the buttons swapped. In multi-touch mode, a touch that went down and up.
    private boolean clicked() {
        return (pc.sent.contains("down1") && pc.sent.contains("up1"))
                || (pc.sent.contains("down3") && pc.sent.contains("up3"))
                || (pc.sent.contains("touch" + MoonBridge.LI_TOUCH_EVENT_DOWN)
                        && pc.sent.contains("touch" + MoonBridge.LI_TOUCH_EVENT_UP));
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

    // A steady drag to the right from this far in from the left edge, 3 dp every 16 ms frame:
    // about 190 dp a second, like moving the cursor. It ends this far from where it started,
    // with the finger still down.
    private void dragRightFrom(float startDp, float byDp) {
        finger(MotionEvent.ACTION_DOWN, dp(startDp), middleY());
        for (float moved = 3; moved <= byDp; moved += 3) {
            advance(16);
            finger(MotionEvent.ACTION_MOVE, dp(startDp + moved), middleY());
        }
    }

    private int countStartingWith(String prefix) {
        int count = 0;
        for (String input : pc.sent) {
            if (input.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void aDragFromTheEdgeOpensTheSliderOnlyAt60DpInAndItsStartReachesThePc() {
        for (int touchMode : SWIPE_MODES) {
            // Lifted 57 dp from where it started, short of the 60 dp the swipe needs
            openStreamScreen(touchMode);
            dragRightFrom(20, 57);
            finger(MotionEvent.ACTION_UP, dp(77), middleY());
            advance(1000);
            assertFalse(mode(touchMode) + "a 57 dp drag from the edge opened the slider", showing());
            assertFalse(mode(touchMode) + "the drag didn't reach the PC", pc.sent.isEmpty());

            // The same drag carried on to 60 dp opens it, about 300 ms after it started, slow as
            // it is. Until then it went to the PC as any drag does, and any mouse button it held
            // there is let go.
            pc.sent.clear();
            dragRightFrom(20, 60);
            assertTrue(mode(touchMode) + "a 60 dp drag from the edge didn't open the slider", showing());
            assertFalse(mode(touchMode) + "the start of the drag didn't reach the PC", pc.sent.isEmpty());
            assertEquals(mode(touchMode) + "a mouse button was left held on the PC: " + pc.sent,
                    countStartingWith("down"), countStartingWith("up"));
            // In multi-touch mode the drag was a touch, which the PC is told to forget
            if (touchMode == Game.MOUSE_MODE_MULTI_TOUCH) {
                assertEquals(mode(touchMode) + "the touch was left down on the PC: " + pc.sent,
                        "touch" + MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, pc.sent.get(pc.sent.size() - 1));
            }
            finger(MotionEvent.ACTION_UP, dp(80), middleY());
        }
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
    public void noPartOfTheTouchThatClosesTheSliderReachesThePc() {
        // What reached the PC, for every touch mode, before saying which went wrong
        List<String> leaks = new ArrayList<>();
        for (int touchMode : SWIPE_MODES) {
            // Opened from the stream menu, the touch handling hasn't been told to drop anything
            openStreamScreen(touchMode);
            openFromTheMenu();
            tapTheMiddle();
            assertEquals(mode(touchMode) + "the tap didn't close the slider", View.GONE, slider.getVisibility());
            if (!pc.sent.isEmpty()) {
                leaks.add(mode(touchMode) + "the closing tap sent " + pc.sent);
            }

            // Again after a tap on the stream, this time closed by a drag
            tapTheMiddle();
            openFromTheMenu();
            pc.sent.clear();
            float x = background.getWidth() / 2f;
            finger(MotionEvent.ACTION_DOWN, x, middleY());
            finger(MotionEvent.ACTION_MOVE, x + dp(25), middleY());
            finger(MotionEvent.ACTION_MOVE, x + dp(50), middleY());
            finger(MotionEvent.ACTION_UP, x + dp(50), middleY());
            advance(1000);
            if (!pc.sent.isEmpty()) {
                leaks.add(mode(touchMode) + "the closing drag sent " + pc.sent);
            }

            // And by two fingers, the first lifting before the second: the touch lasts until
            // the last finger lifts
            tapTheMiddle();
            openFromTheMenu();
            pc.sent.clear();
            float y = middleY();
            fingers(MotionEvent.ACTION_DOWN, new float[] {0, x, y});
            fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[] {0, x, y}, new float[] {1, x + dp(100), y});
            fingers(MotionEvent.ACTION_MOVE, new float[] {0, x + dp(20), y}, new float[] {1, x + dp(120), y});
            fingers(pointer(MotionEvent.ACTION_POINTER_UP, 0), new float[] {0, x + dp(20), y}, new float[] {1, x + dp(120), y});
            fingers(MotionEvent.ACTION_MOVE, new float[] {1, x + dp(150), y});
            fingers(MotionEvent.ACTION_UP, new float[] {1, x + dp(150), y});
            advance(1000);
            assertEquals(mode(touchMode) + "two fingers didn't close the slider", View.GONE, slider.getVisibility());
            if (!pc.sent.isEmpty()) {
                leaks.add(mode(touchMode) + "the closing two-finger touch sent " + pc.sent);
            }

            // The next tap is a click again
            pc.sent.clear();
            tapTheMiddle();
            assertTrue(mode(touchMode) + "the next tap wasn't a click on the PC: " + pc.sent, clicked());
        }
        assertEquals("the touch that closed the slider reached the PC", new ArrayList<String>(), leaks);
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
        // As in Moonlight X, which leaves it out on a TV
        shadowOf((UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE))
                .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        assertTrue("Game didn't see a TV", (Boolean) ReflectionHelpers.getField(game, "isAndroidTV"));
        assertFalse("the stream menu has a Brightness tile on a TV", theStreamMenuHasBrightness());
    }

    // A gamepad button as Android hands it out, repeat 1 and up while it's held. Device 0, so
    // Game's controller handling takes it for the gamepad it doesn't know yet.
    private static KeyEvent pad(long downTime, long eventTime, int action, int keyCode, int repeat) {
        return new KeyEvent(downTime, eventTime, action, keyCode, repeat, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD);
    }

    // Game's controller handling, sending to the recording connection as in a stream. There's no
    // SHIELD accessory service to bind to, as on any device but an NVIDIA SHIELD. The caller
    // destroys it.
    private ControllerHandler useControllerHandling() {
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        PreferenceConfiguration prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        ControllerHandler controllers = new ControllerHandler(game, pc, game, prefConfig);
        ReflectionHelpers.setField(game, "controllerHandler", controllers);
        return controllers;
    }

    @Test
    public void holdingTheButtonThatClosesTheSliderSendsThePcNothing() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        ControllerHandler controllers = useControllerHandling();
        try {
            for (int keyCode : new int[] {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B}) {
                openFromTheMenu();
                long down = SystemClock.uptimeMillis();
                assertTrue(game.dispatchKeyEvent(pad(down, down, KeyEvent.ACTION_DOWN, keyCode, 0)));
                idle();
                assertEquals("key " + keyCode + " didn't close the slider", View.GONE, slider.getVisibility());
                // Held, Android repeats the press until it's let go
                for (int repeat = 1; repeat <= 3; repeat++) {
                    game.dispatchKeyEvent(pad(down, down + 400 + 50 * repeat, KeyEvent.ACTION_DOWN, keyCode, repeat));
                }
                game.dispatchKeyEvent(pad(down, down + 700, KeyEvent.ACTION_UP, keyCode, 0));
                idle();
                assertEquals("key " + keyCode + ": the PC got the button that closed the slider",
                        new ArrayList<String>(), pc.sent);

                // The next press is the game's, and it's let go there too
                long next = SystemClock.uptimeMillis();
                game.dispatchKeyEvent(pad(next, next, KeyEvent.ACTION_DOWN, keyCode, 0));
                game.dispatchKeyEvent(pad(next, next + 100, KeyEvent.ACTION_UP, keyCode, 0));
                idle();
                assertTrue("key " + keyCode + ": the next press didn't reach the PC: " + pc.sent,
                        pc.sent.size() >= 2 && !pc.sent.get(0).equals("pad0"));
                assertEquals("key " + keyCode + ": the next press was left held: " + pc.sent,
                        "pad0", pc.sent.get(pc.sent.size() - 1));
                pc.sent.clear();
            }
        } finally {
            controllers.destroy();
        }
    }

    @Test
    public void aNewPressReachesThePcWhenTheClosingButtonsReleaseWentElsewhere() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        ControllerHandler controllers = useControllerHandling();
        try {
            for (int keyCode : new int[] {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B}) {
                openFromTheMenu();
                long down = SystemClock.uptimeMillis();
                assertTrue(game.dispatchKeyEvent(pad(down, down, KeyEvent.ACTION_DOWN, keyCode, 0)));
                idle();
                assertEquals("key " + keyCode + " didn't close the slider", View.GONE, slider.getVisibility());

                // Its release never comes to Game. The next press is the game's, held a moment
                // and let go there.
                advance(1000);
                long next = SystemClock.uptimeMillis();
                game.dispatchKeyEvent(pad(next, next, KeyEvent.ACTION_DOWN, keyCode, 0));
                game.dispatchKeyEvent(pad(next, next + 400, KeyEvent.ACTION_DOWN, keyCode, 1));
                game.dispatchKeyEvent(pad(next, next + 500, KeyEvent.ACTION_UP, keyCode, 0));
                idle();
                assertTrue("key " + keyCode + ": the new press didn't reach the PC: " + pc.sent,
                        pc.sent.size() >= 2 && !pc.sent.get(0).equals("pad0"));
                assertEquals("key " + keyCode + ": the new press was left held: " + pc.sent,
                        "pad0", pc.sent.get(pc.sent.size() - 1));
                pc.sent.clear();
            }
        } finally {
            controllers.destroy();
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
