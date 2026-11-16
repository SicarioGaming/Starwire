package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Region;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

/**
 * With the floating button off, the stream menu shows whole over the stream however it's opened
 * (issue #14: on an AYN Odin 2 Portal its left part was cut off).
 *
 * Over a SurfaceView, Android tells SurfaceFlinger which parts of the window are see-through, and
 * the stream shows there instead of the window. ViewRootImpl works that region out only when it
 * lays the screen out, from where each view is at that moment. The menu is laid out as its slide
 * in from the right begins, and the slide moves it without another layout, so its left part stayed
 * see-through. The floating button hid this: Android leaves room for a view's shadow, and the
 * button's 9999 dp elevation takes the whole screen out of the region.
 *
 * Game runs as in GameMenuLetsGoTest. Robolectric has no surfaces, so the stream's SurfaceView is
 * made to do what it does on a device once it has drawn: ask for the region and add itself to it.
 * The region checked is the one ViewRootImpl last worked out, which on a device it hands to
 * SurfaceFlinger. The clock is Robolectric's, so the slide takes no real time.
 */
@RunWith(RobolectricTestRunner.class)
// Real graphics, so the region's sums are done for real
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// MoonBridge loads the native streaming library in its static block. Instrumenting it lets
// Robolectric skip loadLibrary and stub the native calls.
@Config(sdk = 36, instrumentedPackages = {"com.limelight.nvstream.jni"},
        qualifiers = "w768dp-h432dp-land-400dpi") // a 1080p handheld: 1920x1080 at 400 dpi
public class GameMenuShowsWholeTest {
    // Longer than the menu's slide in, 320 ms
    private static final int SLIDE_MS = 500;

    private final Context context = RuntimeEnvironment.getApplication();

    private Game game;
    private ControllerHandler controllers;
    private PreferenceConfiguration prefConfig;
    private View background;
    private View menu;
    // Frames drawn with the menu open, and the first one where part of it was see-through
    private int framesDrawn;
    private String firstSeeThroughFrame;

    // edgeSwipeAction: the Left edge swipe action setting, or null to leave it at its default (off)
    private void openStreamScreen(String edgeSwipeAction) {
        // As in the issue: the menu opens from the controller, not from the floating button
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_enable_floating_button", false).commit();
        if (edgeSwipeAction != null) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString("left_edge_swipe_action", edgeSwipeAction).commit();
        }
        game = Robolectric.buildActivity(Game.class, new Intent(context, Game.class)).create().visible().get();

        // What onCreate sets up after the point where a stream with no game stops. This isn't an
        // NVIDIA SHIELD, so the SHIELD's controller service isn't there for ControllerHandler.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"));
        RecordingConnection pc = new RecordingConnection(context);
        prefConfig = ReflectionHelpers.getField(game, "prefConfig");
        controllers = new ControllerHandler(game, pc, game, prefConfig);
        ReflectionHelpers.setField(game, "conn", pc);
        ReflectionHelpers.setField(game, "controllerHandler", controllers);
        ReflectionHelpers.setField(game, "keyboardTranslator", new KeyboardTranslator());
        ReflectionHelpers.callInstanceMethod(game, "setupOverlayMenu");
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode",
                ClassParameter.from(int.class, Game.MOUSE_MODE_TRACKPAD_NATURAL));

        // On a device the SurfaceView asks for the region when it's attached, and adds itself to
        // it once it has drawn
        SurfaceView stream = game.findViewById(R.id.surfaceView);
        ReflectionHelpers.setField(SurfaceView.class, stream, "mDrawFinished", true);
        stream.getParent().requestTransparentRegion(stream);

        background = game.findViewById(R.id.backgroundTouchView);
        menu = game.findViewById(R.id.overlayMenuView);
        // Well past the start of Robolectric's clock, so a finger lifted isn't taken for the end
        // of a 3-finger tap, which Game times from 0 until there has been one
        advance(5000);
        assertEquals("the floating button is off", View.GONE,
                game.findViewById(R.id.floatingMenuButton).getVisibility());
        assertEquals("the menu starts closed", View.GONE, menu.getVisibility());
        assertTrue("the stream doesn't show through the window", seeThrough().contains(dp(100), middleY()));
        checkEveryFrame();
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

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int middleY() {
        return background.getHeight() / 2;
    }

    // The part of the window SurfaceFlinger leaves out, showing the stream there instead
    private Region seeThrough() {
        Object viewRoot = game.getWindow().getDecorView().getParent();
        return new Region(ReflectionHelpers.<Region>getField(viewRoot, "mPreviousTransparentRegion"));
    }

    // The part of the menu, where it is now, that's see-through; empty when none of it is
    private Region menuSeeThrough() {
        int[] at = new int[2];
        menu.getLocationInWindow(at);
        Region part = seeThrough();
        part.op(at[0], at[1], at[0] + menu.getWidth(), at[1] + menu.getHeight(), Region.Op.INTERSECT);
        return part;
    }

    // Checks each frame drawn while the menu is open: Robolectric's clock moves on by more than a
    // frame at a time here, so a frame is the only sure place to look during the slide in
    private void checkEveryFrame() {
        game.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(() -> {
            if (menu.getVisibility() == View.VISIBLE && !menu.isLayoutRequested()) {
                framesDrawn++;
                Region part = menuSeeThrough();
                if (!part.isEmpty() && firstSeeThroughFrame == null) {
                    int[] at = new int[2];
                    menu.getLocationInWindow(at);
                    firstSeeThroughFrame = "the stream shows through " + part.getBounds() + " of the menu, "
                            + menu.getWidth() + " px wide from " + at[0] + ", in frame " + framesDrawn;
                }
            }
            return true;
        });
    }

    // Through the slide in and after: no frame of the menu is see-through, and once it's in place
    // the stream still shows through beside it
    private void assertTheWholeMenuShows(String how) {
        advance(SLIDE_MS);
        assertEquals(how + " didn't open the menu", View.VISIBLE, menu.getVisibility());
        assertTrue(how + ": the menu was never drawn", framesDrawn > 0);
        assertEquals(how + ": the slide in didn't end", 0f, menu.getTranslationX(), 0f);
        Region part = menuSeeThrough();
        assertTrue(how + ": the stream shows through " + part.getBounds() + " of the menu, from "
                + menu.getLeft() + " to " + menu.getRight(), part.isEmpty());
        assertEquals(how + ": during the slide in", null, firstSeeThroughFrame);
        assertTrue(how + ": the stream no longer shows through beside the menu",
                seeThrough().contains(dp(100), middleY()));
    }

    private void holdSelect() {
        long now = SystemClock.uptimeMillis();
        game.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 0, 0,
                0, 0, 0, InputDevice.SOURCE_GAMEPAD));
        // The menu opens when the hold is long enough, then lays out on the next frame
        advance(prefConfig.overlayHoldDurationMs);
    }

    @Test
    public void holdingSelectShowsTheWholeMenu() {
        openStreamScreen(null);
        holdSelect();
        assertTheWholeMenuShows("holding Select");
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi") // 1920x1080 at 320 dpi
    public void holdingSelectShowsTheWholeMenuAt320Dpi() {
        openStreamScreen(null);
        holdSelect();
        assertTheWholeMenuShows("holding Select at 320 dpi");
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi") // 1920x1080 at 480 dpi
    public void holdingSelectShowsTheWholeMenuAt480Dpi() {
        openStreamScreen(null);
        holdSelect();
        assertTheWholeMenuShows("holding Select at 480 dpi");
    }

    @Test
    public void backShowsTheWholeMenu() {
        // Back opens the menu by default. The navigation bar's Back button and the back gesture:
        openStreamScreen(null);
        long downTime = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            game.dispatchKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD));
        }
        assertTheWholeMenuShows("Back");
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

    @Test
    public void theThreeFingerTapShowsTheWholeMenu() {
        // Three fingers down one after another, and up again 100 ms after the third went down
        openStreamScreen(null);
        float y = middleY();
        float[] one = {0, background.getWidth() * 0.3f, y};
        float[] two = {1, background.getWidth() * 0.5f, y};
        float[] three = {2, background.getWidth() * 0.7f, y};
        fingers(MotionEvent.ACTION_DOWN, one);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), one, two);
        fingers(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), one, two, three);
        advance(100);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 2), one, two, three);
        fingers(pointer(MotionEvent.ACTION_POINTER_UP, 1), one, two);
        fingers(MotionEvent.ACTION_UP, one);
        assertTheWholeMenuShows("the three-finger tap");
    }

    @Test
    public void theLeftEdgeSwipeShowsTheWholeMenu() {
        // Set to open the menu. From 10 dp in from the edge, 70 dp to the right at once: past the
        // 60 dp the swipe needs, well inside its 500 ms. Then on another 50 dp before the finger
        // lifts.
        openStreamScreen("overlay_menu");
        fingers(MotionEvent.ACTION_DOWN, new float[] {0, dp(10), middleY()});
        fingers(MotionEvent.ACTION_MOVE, new float[] {0, dp(80), middleY()});
        fingers(MotionEvent.ACTION_MOVE, new float[] {0, dp(130), middleY()});
        fingers(MotionEvent.ACTION_UP, new float[] {0, dp(130), middleY()});
        assertTheWholeMenuShows("the left edge swipe");
    }
}
