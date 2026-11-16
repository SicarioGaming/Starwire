package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

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
import java.util.Locale;

/**
 * A long press in direct touch, the mode "Use touchscreen as trackpad" off starts a stream in,
 * and what the PC gets for it, with the time each input goes out. One finger goes down, holds
 * still for 1.5 seconds with no move at all, and lifts. That's what a tablet's own touch panel
 * recorded for a long press that the PC took as a left click, with no right-click.
 *
 * Direct touch presses the left button 100 ms after the finger goes down, at the finger. At
 * 650 ms the long press lets go of it and presses the right button, which goes up when the
 * finger lifts, as in Moonlight. Version 0.2.1 sent the same. So the right-click does leave the
 * tablet; this test keeps it that way.
 *
 * Game runs as in GameTrackpadDragTest: with no game in its Intent, onCreate sets up the stream
 * screen and finishes before anything connects, and the test gives it a connection that writes
 * down what it's sent. The finger goes through Game's window, as on a device. Robolectric's
 * clock only moves when the test moves it, so the 100 ms and 650 ms timers run on time.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w1067dp-h668dp-land-360dpi") // a 2400x1504 tablet at 360 dpi; 668 dp is 1503 pixels
public class GameDirectTouchLongPressTest {
    private static final int HELD_MS = 1500;

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private TimedConnection pc;
    private View background;
    private View stream;
    // When the finger went down, on Robolectric's clock
    private long downTime;

    // As RecordingConnection, and also each mouse input and touch with the time it went out, in
    // ms after the finger went down: "100 down1", or "100 pos 1200,450 of 2400x1503" for the
    // cursor placed at that spot on a stream of that size
    private class TimedConnection extends RecordingConnection {
        final List<String> timed = new ArrayList<>();

        TimedConnection(Context context) {
            super(context);
        }

        private void note(String input) {
            timed.add((SystemClock.uptimeMillis() - downTime) + " " + input);
        }

        @Override
        public void sendMouseButtonDown(byte button) {
            super.sendMouseButtonDown(button);
            note("down" + button);
        }

        @Override
        public void sendMouseButtonUp(byte button) {
            super.sendMouseButtonUp(button);
            note("up" + button);
        }

        @Override
        public void sendMousePosition(short x, short y, short width, short height) {
            super.sendMousePosition(x, y, width, height);
            note(String.format(Locale.ROOT, "pos %d,%d of %dx%d", x, y, width, height));
        }

        @Override
        public void sendMouseMove(short dx, short dy) {
            super.sendMouseMove(dx, dy);
            note("move");
        }

        @Override
        public void sendMouseHighResScroll(short amount) {
            super.sendMouseHighResScroll(amount);
            note("scroll");
        }

        @Override
        public int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressureOrDistance,
                                  float contactAreaMajor, float contactAreaMinor, short rotation) {
            int result = super.sendTouchEvent(eventType, pointerId, x, y, pressureOrDistance,
                    contactAreaMajor, contactAreaMinor, rotation);
            if (takesTouches) {
                note("touch" + eventType);
            }
            return result;
        }
    }

    // With "Long-press to right-click" as it is by default, on
    private void openStreamScreen(int touchMode) {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        pc = new TimedConnection(context);
        ReflectionHelpers.setField(game, "conn", pc);
        // What the stream menu's Touch Mode picker does. It makes the touch handling anew, now
        // sending to the recording connection.
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        background = game.findViewById(R.id.backgroundTouchView);
        stream = game.findViewById(R.id.surfaceView);
        idle();
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        pc.sent.clear();
        pc.timed.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Moves Robolectric's clock on and runs what comes due, with no real waiting
    private static void advance(int ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private float x() {
        return background.getWidth() * 0.5f;
    }

    private float y() {
        return background.getHeight() * 0.3f;
    }

    // One finger's event as Game's window hands it out, to the view under it
    private void finger(int action) {
        int[] origin = new int[2];
        background.getLocationInWindow(origin);
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = origin[0] + x();
        coords[0].y = origin[1] + y();
        coords[0].pressure = 1;
        coords[0].size = 1;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now;
        }
        MotionEvent event = MotionEvent.obtain(downTime, now, action, 1, properties, coords, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        game.dispatchTouchEvent(event);
        event.recycle();
        idle();
    }

    // Down, held still with no move, and lifted in the same place. Then a second for anything
    // still to come.
    private void longPress() {
        finger(MotionEvent.ACTION_DOWN);
        advance(HELD_MS);
        finger(MotionEvent.ACTION_UP);
        advance(1000);
    }

    // Where the cursor goes for the finger. The stream view fills the screen here, since no
    // stream has set its size, so the spot on it is the spot on the screen.
    private String placedAtTheFinger() {
        return String.format(Locale.ROOT, "pos %d,%d of %dx%d", (int) x(), (int) y(),
                stream.getWidth(), stream.getHeight());
    }

    // The left click from 100 ms to the long press, then the right button held until the finger
    // lifts
    private List<String> leftClickThenRightClick() {
        return Arrays.asList("100 " + placedAtTheFinger(), "100 down1", "650 up1", "650 down3",
                HELD_MS + " up3");
    }

    @Test
    public void aLongPressPressesTheRightButtonUntilTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        longPress();
        assertEquals(leftClickThenRightClick(), pc.timed);
    }

    // The setting is for the gaming trackpad mode. Direct touch right-clicks either way.
    @Test
    public void aLongPressIsTheSameWithLongPressRightClickOff() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_long_press_right_click", false).commit();
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        longPress();
        assertEquals(leftClickThenRightClick(), pc.timed);
    }

    // Multi-touch mode sends the finger as a touch, held for as long as the finger is down. What
    // a long press does then is up to the PC.
    @Test
    public void inMultiTouchModeALongPressIsOneTouchHeldOnThePc() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        longPress();
        assertEquals(Arrays.asList("0 touch1", HELD_MS + " touch2"), pc.timed);
        assertEquals(Arrays.asList("down 0 at 0.50,0.30", "up 0 at 0.50,0.30"), pc.touches);
    }

    @Test
    public void inMultiTouchModeAPcThatTakesNoTouchesGetsDirectTouchsRightClick() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        pc.takesTouches = false;
        longPress();
        assertEquals(leftClickThenRightClick(), pc.timed);
    }
}
