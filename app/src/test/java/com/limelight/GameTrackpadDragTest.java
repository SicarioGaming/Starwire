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

/**
 * What the PC gets for each gesture in the two trackpad modes, in order: "down1" and "up1" for
 * the left button, "down3" and "up3" for the right one, "move" and "scroll".
 *
 * A drag has to reach the PC as the left button pressed, the moves, then the left button let go
 * where the drag ends, and nothing else. In the gaming trackpad mode, a long press then a drag
 * used to hold the right button instead, since "Long-press to right-click" is on by default. So
 * the PC got a right-button drag, with a right-click where it ended, and nothing was dragged
 * (GitHub issue #5).
 *
 * Game runs as in GameMultiTouchTest: with no game in its Intent, onCreate sets up the stream
 * screen and finishes before anything connects, and the test gives it a RecordingConnection. The
 * fingers go through Game's window, as on a device. Robolectric's clock only moves when the test
 * moves it, so the long press (650 ms) and the taps (250 ms) are timed exactly.
 */
@RunWith(RobolectricTestRunner.class)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w960dp-h540dp-land-xhdpi") // a 1080p handheld
public class GameTrackpadDragTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private RecordingConnection pc;
    private View background;

    // With "Long-press to right-click" as it is by default, on
    private void openStreamScreen(int touchMode) {
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();
        pc = new RecordingConnection(context);
        ReflectionHelpers.setField(game, "conn", pc);
        // What the stream menu's Touch Mode picker does. It makes the touch handling anew, now
        // sending to the recording connection.
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode", ClassParameter.from(int.class, touchMode));
        background = game.findViewById(R.id.backgroundTouchView);
        idle();
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        pc.sent.clear();
    }

    private void openStreamScreenWithLongPressRightClickOff(int touchMode) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_long_press_right_click", false).commit();
        openStreamScreen(touchMode);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Moves Robolectric's clock on and runs what comes due, with no real waiting
    private static void advance(int ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private float across(float share) {
        return background.getWidth() * share;
    }

    private float down(float share) {
        return background.getHeight() * share;
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

    private void finger(int action, float x, float y) {
        fingers(action, at(0, x, y));
    }

    // Held still for 700 ms, past the 650 ms a long press takes, then moved down a list in two
    // steps of a tenth of the screen and lifted there. Then a second for anything still to come.
    private void longPressAndDrag() {
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.4f));
        advance(16);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.5f));
        advance(16);
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.5f));
        advance(1000);
    }

    // Held still for 700 ms, then lifted in the same place
    private void longPress() {
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.3f));
        advance(1000);
    }

    private void tap() {
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.3f));
        advance(1000);
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    // Each button that went down went up again, before it went down again
    private void assertNoButtonLeftDown() {
        List<String> held = new ArrayList<>();
        for (String input : pc.sent) {
            if (input.startsWith("down")) {
                assertEquals("a button went down twice without an up between: " + pc.sent,
                        false, held.contains(input.substring(4)));
                held.add(input.substring(4));
            }
            else if (input.startsWith("up")) {
                held.remove(input.substring(2));
            }
        }
        assertEquals("a button was left down on the PC: " + pc.sent, list(), held);
    }

    @Test
    public void aLongPressThenADragIsALeftButtonDragThatEndsWhereTheFingerLifts() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        longPressAndDrag();
        assertEquals(list("down1", "move", "move", "up1"), pc.sent);
    }

    @Test
    public void aLongPressLiftedInPlaceIsStillARightClick() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        longPress();
        assertEquals(list("down3", "up3"), pc.sent);
    }

    // A finger held down wobbles a little. Six pixels is inside the 20 a tap allows, and the
    // cursor would move four.
    @Test
    public void aLongPressThatWobblesIsARightClickWhereTheLongPressWas() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.3f) + 6);
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.3f) + 6);
        advance(1000);
        assertEquals(list("down3", "up3"), pc.sent);
    }

    @Test
    public void aLongPressThatWobblesThenDragsPressesWhereTheLongPressWas() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.3f) + 6);
        advance(16);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.4f));
        advance(16);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.5f));
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.5f));
        advance(1000);
        assertEquals(list("down1", "move", "move", "up1"), pc.sent);
    }

    @Test
    public void withLongPressRightClickOffALongPressHoldsTheLeftButton() {
        openStreamScreenWithLongPressRightClickOff(Game.MOUSE_MODE_TRACKPAD_GAMING);
        longPressAndDrag();
        assertEquals(list("down1", "move", "move", "up1"), pc.sent);

        pc.sent.clear();
        longPress();
        assertEquals(list("down1", "up1"), pc.sent);
    }

    @Test
    public void aTapIsALeftClick() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        tap();
        assertEquals(list("down1", "up1"), pc.sent);
    }

    @Test
    public void aFingerMovedWithoutALongPressOnlyMovesTheCursor() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.4f));
        advance(700);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.5f));
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.5f));
        advance(1000);
        assertEquals(list("move", "move"), pc.sent);
    }

    @Test
    public void aTwoFingerTapIsARightClick() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        float[] one = at(0, across(0.4f), down(0.5f));
        float[] two = at(1, across(0.6f), down(0.5f));
        fingers(MotionEvent.ACTION_DOWN, one);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), one, two);
        fingers(MotionEvent.ACTION_UP, one);
        advance(1000);
        assertEquals(list("down3", "up3"), pc.sent);
    }

    @Test
    public void twoFingersMovedTogetherScroll() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        fingers(MotionEvent.ACTION_DOWN, at(0, across(0.4f), down(0.3f)));
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), at(0, across(0.4f), down(0.3f)),
                at(1, across(0.6f), down(0.3f)));
        fingers(MotionEvent.ACTION_MOVE, at(0, across(0.4f), down(0.4f)), at(1, across(0.6f), down(0.4f)));
        fingers(MotionEvent.ACTION_MOVE, at(0, across(0.4f), down(0.5f)), at(1, across(0.6f), down(0.5f)));
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), at(0, across(0.4f), down(0.5f)),
                at(1, across(0.6f), down(0.5f)));
        fingers(MotionEvent.ACTION_UP, at(0, across(0.4f), down(0.5f)));
        advance(1000);
        assertEquals(list("scroll", "scroll"), pc.sent);
    }

    @Test
    public void aSecondFingerTappedDuringALongPressIsOneRightClick() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        float[] one = at(0, across(0.4f), down(0.5f));
        float[] two = at(1, across(0.6f), down(0.5f));
        fingers(MotionEvent.ACTION_DOWN, one);
        advance(700);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), one, two);
        advance(1000);
        fingers(MotionEvent.ACTION_UP, one);
        advance(1000);
        assertNoButtonLeftDown();
        assertEquals(list("down3", "up3"), pc.sent);
    }

    @Test
    public void aDragTheSystemCancelsLetsGoOfTheLeftButton() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.4f));
        finger(MotionEvent.ACTION_CANCEL, across(0.5f), down(0.4f));
        advance(1000);
        assertEquals(list("down1", "move", "up1"), pc.sent);
    }

    @Test
    public void aLongPressTheSystemCancelsSendsNothing() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(700);
        finger(MotionEvent.ACTION_CANCEL, across(0.5f), down(0.3f));
        advance(1000);
        assertEquals(list(), pc.sent);
    }

    @Test
    public void noGestureLeavesAButtonDown() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_GAMING);
        longPressAndDrag();
        longPress();
        tap();
        assertNoButtonLeftDown();
    }

    @Test
    public void theNaturalTrackpadsDoubleTapThenDragIsALeftButtonDrag() {
        openStreamScreen(Game.MOUSE_MODE_TRACKPAD_NATURAL);
        // A tap, and the second touch within the 230 ms a click is held for
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.3f));
        advance(100);
        finger(MotionEvent.ACTION_DOWN, across(0.5f), down(0.3f));
        advance(16);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.4f));
        advance(16);
        finger(MotionEvent.ACTION_MOVE, across(0.5f), down(0.5f));
        // Held still before lifting, so the drag doesn't fling on past the spot
        advance(100);
        finger(MotionEvent.ACTION_UP, across(0.5f), down(0.5f));
        advance(1000);
        assertEquals(list("down1", "move", "move", "up1"), pc.sent);
    }
}
