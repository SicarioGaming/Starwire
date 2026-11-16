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
 * What the PC gets for a finger in direct touch, the mode "Use touchscreen as trackpad" off starts
 * a stream in, with the time each input goes out. A long press here holds one finger still for
 * 1.5 seconds with no move at all, then lifts it. That's what a tablet's own touch panel recorded
 * for a long press that the PC took as a left click, with no right-click.
 *
 * Direct touch used to press the left button 100 ms after the finger went down, as in Moonlight.
 * At 650 ms the long press let go of it and pressed the right button. Version 0.2.1 did the same.
 * Vibepollo's input.cpp, from Sunshine, holds a left button release back for 10 ms once the cursor
 * is placed by position, as direct touch places it. A right button press inside that time becomes
 * a right button down and up at once, while the left button is still down, and the right button's
 * own release is dropped. Going by that source, Vibepollo passed on a right-click inside a left
 * click that was still held.
 *
 * Now nothing is pressed until the touch is a tap, a drag or a long press. A long press sends only
 * the right button. A finger lifted before then without moving away is a left click as it lifts.
 * One that moves more than 30 pixels first presses the left button where it went down, and drags.
 *
 * Game runs as in GameTrackpadDragTest: with no game in its Intent, onCreate sets up the stream
 * screen and finishes before anything connects, and the test gives it a connection that writes
 * down what it's sent. The fingers go through Game's window, as on a device. Robolectric's clock
 * only moves when the test moves it, so the 650 ms long press and the other times are exact.
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
    // When the test's first finger went down, on Robolectric's clock. Each input is timed from it.
    private long startTime = -1;
    // When the fingers on the screen now started to go down, for their events
    private long downTime;

    // As RecordingConnection, and also each mouse input and touch with the time it went out, in
    // ms after the first finger went down: "100 down1", "100 pos 1200,450 of 2400x1503" for the
    // cursor placed at that spot on a stream of that size, or "100 scroll 120" for a scroll down
    private class TimedConnection extends RecordingConnection {
        final List<String> timed = new ArrayList<>();

        TimedConnection(Context context) {
            super(context);
        }

        private void note(String input) {
            timed.add((SystemClock.uptimeMillis() - startTime) + " " + input);
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
            note("scroll " + amount);
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

    // Where the first finger goes down, on the view behind the stream
    private float x() {
        return background.getWidth() * 0.5f;
    }

    private float y() {
        return background.getHeight() * 0.3f;
    }

    // A finger's id, then how far across and down it is from where the first finger goes down
    private static float[] at(int id, float dx, float dy) {
        return new float[] {id, dx, dy};
    }

    // ACTION_POINTER_DOWN or ACTION_POINTER_UP for the finger at this place in the event
    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    // The fingers' event as Game's window hands it out, to the view under them. For a finger
    // going down or up while others stay, the action says which point it is.
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
            coords[i].x = origin[0] + x() + points[i][1];
            coords[i].y = origin[1] + y() + points[i][2];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now;
            if (startTime < 0) {
                startTime = now;
            }
        }
        MotionEvent event = MotionEvent.obtain(downTime, now, action, points.length, properties, coords, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        game.dispatchTouchEvent(event);
        event.recycle();
        idle();
    }

    // The first finger, this far across and down from where it went down
    private void finger(int action, float dx, float dy) {
        fingers(action, at(0, dx, dy));
    }

    // The first finger, where it went down
    private void finger(int action) {
        finger(action, 0, 0);
    }

    // Down, held still with no move, and lifted in the same place this long after. Then a second
    // for anything still to come.
    private void stillPress(int heldMs) {
        finger(MotionEvent.ACTION_DOWN);
        advance(heldMs);
        finger(MotionEvent.ACTION_UP);
        advance(1000);
    }

    private void longPress() {
        stillPress(HELD_MS);
    }

    // Where the cursor goes for a finger this far across and down from where the first one went
    // down. The stream view fills the screen here, since no stream has set its size, so the spot
    // on it is the spot on the screen.
    private String placed(float dx, float dy) {
        return String.format(Locale.ROOT, "pos %d,%d of %dx%d", (int) (x() + dx), (int) (y() + dy),
                stream.getWidth(), stream.getHeight());
    }

    private String placedAtTheFinger() {
        return placed(0, 0);
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    // The cursor placed at the finger and the right button pressed at the long press, at 650 ms,
    // and the right button let go when the finger lifts. Nothing before the long press, and no
    // left button at all.
    private List<String> rightClickOnly() {
        return list("650 " + placedAtTheFinger(), "650 down3", HELD_MS + " up3");
    }

    @Test
    public void aLongPressPressesTheRightButtonUntilTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        longPress();
        assertEquals(rightClickOnly(), pc.timed);
    }

    // The setting is for the gaming trackpad mode. Direct touch right-clicks either way.
    @Test
    public void aLongPressIsTheSameWithLongPressRightClickOff() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_long_press_right_click", false).commit();
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        longPress();
        assertEquals(rightClickOnly(), pc.timed);
    }

    // Multi-touch mode sends the finger as a touch, held for as long as the finger is down. What
    // a long press does then is up to the PC.
    @Test
    public void inMultiTouchModeALongPressIsOneTouchHeldOnThePc() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        longPress();
        assertEquals(list("0 touch1", HELD_MS + " touch2"), pc.timed);
        assertEquals(list("down 0 at 0.50,0.30", "up 0 at 0.50,0.30"), pc.touches);
    }

    @Test
    public void inMultiTouchModeAPcThatTakesNoTouchesGetsDirectTouchsRightClick() {
        openStreamScreen(Game.MOUSE_MODE_MULTI_TOUCH);
        pc.takesTouches = false;
        longPress();
        assertEquals(rightClickOnly(), pc.timed);
    }

    // Lifted within 100 ms, before the left button used to go down. That was a click as the finger
    // lifted already.
    @Test
    public void aQuickTapIsALeftClickWhenTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        stillPress(50);
        assertEquals(list("50 " + placedAtTheFinger(), "50 down1", "150 up1"), pc.timed);
    }

    // The left button is let go 100 ms after it went down, as for a quick tap, so a game that only
    // looks at the buttons now and then still sees the click
    @Test
    public void aStillPressShorterThanALongPressIsALeftClickWhenTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        stillPress(300);
        assertEquals(list("300 " + placedAtTheFinger(), "300 down1", "400 up1"), pc.timed);
    }

    // A finger held down drifts a little. 25 pixels is inside the 30 a long press allows, and the
    // right-click is where the finger went down.
    @Test
    public void aLongPressThatMoves25PixelsIsStillALongPress() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(200);
        finger(MotionEvent.ACTION_MOVE, 25, 0);
        advance(HELD_MS - 200);
        finger(MotionEvent.ACTION_UP, 25, 0);
        advance(1000);
        assertEquals(rightClickOnly(), pc.timed);
    }

    // Moved 40 pixels at 200 ms, before the long press, then on across the screen
    @Test
    public void aDragHoldsTheLeftButtonFromWhereTheFingerWentDown() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(200);
        finger(MotionEvent.ACTION_MOVE, 40, 0);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, 80, 0);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, 120, 0);
        advance(16);
        finger(MotionEvent.ACTION_UP, 120, 0);
        advance(1000);
        assertEquals(list("200 " + placedAtTheFinger(), "200 down1", "200 " + placed(40, 0),
                "216 " + placed(80, 0), "232 " + placed(120, 0), "248 up1"), pc.timed);
    }

    // As before, a move after the long press drags with the right button. A wobble of 10 pixels
    // leaves the cursor where it is. Once the finger is more than 20 pixels from where it went
    // down, the cursor follows it until it lifts, back toward the start too.
    @Test
    public void aLongPressThenAMoveIsARightButtonDrag() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(700);
        finger(MotionEvent.ACTION_MOVE, 10, 0);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, 50, 0);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, 100, 0);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, 10, 0);
        advance(16);
        finger(MotionEvent.ACTION_UP, 10, 0);
        advance(1000);
        assertEquals(list("650 " + placedAtTheFinger(), "650 down3", "716 " + placed(50, 0),
                "732 " + placed(100, 0), "748 " + placed(10, 0), "764 up3"), pc.timed);
    }

    // Two still presses of 150 ms, 150 ms apart, the second 10 pixels across from the first. The
    // cursor stays where the first click was, so both clicks land in the same place, as a
    // double-click needs.
    @Test
    public void theSecondTapOfADoubleTapClicksWhereTheFirstDid() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(150);
        finger(MotionEvent.ACTION_UP);
        advance(150);
        finger(MotionEvent.ACTION_DOWN, 10, 0);
        advance(150);
        finger(MotionEvent.ACTION_UP, 10, 0);
        advance(1000);
        assertEquals(list("150 " + placedAtTheFinger(), "150 down1", "250 up1", "450 down1", "550 up1"),
                pc.timed);
    }

    // A long press that starts as the second tap of a double tap, 150 ms after a quick tap and 10
    // pixels across from it. The right button goes down where the tap's click was, as a second
    // tap's click would.
    @Test
    public void aLongPressRightAfterATapRightClicksWhereTheTapClicked() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(50);
        finger(MotionEvent.ACTION_UP);
        advance(150);
        finger(MotionEvent.ACTION_DOWN, 10, 0);
        advance(HELD_MS);
        finger(MotionEvent.ACTION_UP, 10, 0);
        advance(1000);
        assertEquals(list("50 " + placedAtTheFinger(), "50 down1", "150 up1", "850 down3",
                (200 + HELD_MS) + " up3"), pc.timed);
    }

    @Test
    public void withTheButtonsSwappedAStillPressIsARightClickWhenTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED);
        stillPress(300);
        assertEquals(list("300 " + placedAtTheFinger(), "300 down3", "400 up3"), pc.timed);
    }

    @Test
    public void withTheButtonsSwappedALongPressHoldsTheLeftButtonUntilTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED);
        longPress();
        assertEquals(list("650 " + placedAtTheFinger(), "650 down1", HELD_MS + " up1"), pc.timed);
    }

    // A second finger at 300 ms, 300 pixels across, then both move down 40 pixels twice, which
    // scrolls. The first finger used to have pressed the left button by then, and let it go as
    // the second came down.
    @Test
    public void aSecondFingerBeforeTheLongPressScrollsWithNoClick() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        fingers(MotionEvent.ACTION_DOWN, at(0, 0, 0));
        advance(300);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), at(0, 0, 0), at(1, 300, 0));
        advance(16);
        fingers(MotionEvent.ACTION_MOVE, at(0, 0, 40), at(1, 300, 40));
        advance(16);
        fingers(MotionEvent.ACTION_MOVE, at(0, 0, 80), at(1, 300, 80));
        advance(16);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), at(0, 0, 80), at(1, 300, 80));
        fingers(MotionEvent.ACTION_UP, at(0, 0, 80));
        advance(1000);
        assertEquals(list("316 scroll 120", "332 scroll 120"), pc.timed);
    }

    // Android takes the touch for a gesture of its own before the long press. Nothing was
    // pressed, so there's nothing to let go of.
    @Test
    public void aTouchTheSystemCancelsBeforeTheLongPressSendsNothing() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(300);
        finger(MotionEvent.ACTION_CANCEL);
        advance(1000);
        assertEquals(list(), pc.timed);
    }

    @Test
    public void aLongPressTheSystemCancelsLetsGoOfTheRightButton() {
        openStreamScreen(Game.MOUSE_MODE_DIRECT_TOUCH);
        finger(MotionEvent.ACTION_DOWN);
        advance(1000);
        finger(MotionEvent.ACTION_CANCEL);
        advance(1000);
        assertEquals(list("650 " + placedAtTheFinger(), "650 down3", "1000 up3"), pc.timed);
    }
}
