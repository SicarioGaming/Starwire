package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.view.MotionEvent;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

/**
 * The stream screen's decisions that don't need a device: which orientation it asks for, and
 * when a three-finger touch opens the overlay menu before the normal touch handling.
 */
public class GameRulesTest {
    private static final int LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    private static final int PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    private static final int ANY = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;

    private static final int[] ALL_MOUSE_MODES = {
            Game.MOUSE_MODE_MULTI_TOUCH, Game.MOUSE_MODE_DIRECT_TOUCH, Game.MOUSE_MODE_TRACKPAD_NATURAL,
            Game.MOUSE_MODE_TRACKPAD_GAMING, Game.MOUSE_MODE_DISABLED, Game.MOUSE_MODE_DIRECT_TOUCH_SWAPPED
    };

    // 1080p, one of the resolution options, so not "native"
    private static final int W = 1920;
    private static final int H = 1080;

    private static int orientation(int screenWidth, int screenHeight, boolean hinge, boolean osc,
                                   int streamWidth, int streamHeight) {
        return Game.streamOrientation(PreferenceConfiguration.isSquarishScreen(screenWidth, screenHeight),
                hinge, osc, streamWidth, streamHeight);
    }

    @Test
    public void phonesAndTabletsAlwaysAskForLandscape() {
        assertEquals("16:9 tablet", LANDSCAPE, orientation(2560, 1600, false, false, W, H));
        assertEquals("phone", LANDSCAPE, orientation(1080, 2400, false, false, W, H));
        // Even a native portrait resolution on a regular screen stays landscape, as before
        assertEquals("phone, native portrait", LANDSCAPE, orientation(1080, 2400, false, false, 1080, 2400));
    }

    @Test
    public void aFoldsCoverScreenKeepsLandscape() {
        // About a Galaxy Z Fold 6's cover screen: the device has a hinge, the screen is a tall phone's
        assertEquals(LANDSCAPE, orientation(968, 2376, true, false, W, H));
        assertEquals("with the on-screen controller", LANDSCAPE, orientation(968, 2376, true, true, W, H));
    }

    @Test
    public void aFoldsInnerScreenFollowsTheDevice() {
        // 76b84b24: forcing landscape here could hand the stream a rotated, shrunk surface
        assertTrue(PreferenceConfiguration.isSquarishScreen(1856, 2160));
        assertEquals(ANY, orientation(1856, 2160, true, false, W, H));
        // Whatever the other settings would have asked for
        assertEquals("with the on-screen controller", ANY, orientation(1856, 2160, true, true, W, H));
        assertEquals("native portrait", ANY, orientation(1856, 2160, true, false, 1856, 2160));
        assertEquals("native landscape", ANY, orientation(1856, 2160, true, false, 2160, 1856));
    }

    @Test
    public void squarishScreensWithoutAHingeKeepTheOldRules() {
        // A 5:4 screen, with no hinge sensor
        assertEquals("no reason to lock", ANY, orientation(1280, 1024, false, false, W, H));
        assertEquals("on-screen controller", LANDSCAPE, orientation(1280, 1024, false, true, W, H));
        assertEquals("native portrait", PORTRAIT, orientation(1280, 1024, false, false, 1024, 1280));
        assertEquals("native landscape", LANDSCAPE, orientation(1280, 1024, false, false, 1280, 1024));
        // A native resolution decides over the on-screen controller
        assertEquals("native portrait with the controller", PORTRAIT,
                orientation(1280, 1024, false, true, 1024, 1280));
    }

    @Test
    public void threeFingersOpenTheMenuInPanZoomAndTouchDisabledModes() {
        // d0beca57: these modes skip the normal three-finger handling, which left no way back to
        // the menu with the floating button hidden and no controller
        for (int mode : ALL_MOUSE_MODES) {
            assertTrue("pan/zoom, mode " + mode, Game.opensMenuBeforeTouchHandling(
                    true, mode, MotionEvent.ACTION_POINTER_DOWN, 3));
        }
        assertTrue("touch disabled", Game.opensMenuBeforeTouchHandling(
                false, Game.MOUSE_MODE_DISABLED, MotionEvent.ACTION_POINTER_DOWN, 3));
    }

    @Test
    public void otherModesLeaveThreeFingersToTheNormalHandling() {
        for (int mode : ALL_MOUSE_MODES) {
            if (mode == Game.MOUSE_MODE_DISABLED) {
                continue;
            }
            assertFalse("mode " + mode, Game.opensMenuBeforeTouchHandling(
                    false, mode, MotionEvent.ACTION_POINTER_DOWN, 3));
        }
    }

    @Test
    public void onlyTheThirdFingerGoingDownCounts() {
        for (int pointers : new int[] {1, 2, 4, 5}) {
            assertFalse(pointers + " fingers", Game.opensMenuBeforeTouchHandling(
                    true, Game.MOUSE_MODE_DISABLED, MotionEvent.ACTION_POINTER_DOWN, pointers));
        }
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
            assertFalse("action " + action, Game.opensMenuBeforeTouchHandling(
                    true, Game.MOUSE_MODE_DISABLED, action, 3));
        }
    }

    @Test
    public void edgeSwipeCompletesOnceFarEnoughIn() {
        // density 1: the finger has to get 60dp in from where it went down
        assertFalse(Game.edgeSwipeComplete(10, 500, 10 + 59, 500, 1));
        assertTrue(Game.edgeSwipeComplete(10, 500, 10 + 60, 500, 1));
        assertTrue(Game.edgeSwipeComplete(10, 500, 10 + 120, 500, 1));
        // The same distances on a denser screen ask for more pixels
        assertFalse(Game.edgeSwipeComplete(10, 500, 10 + 60, 500, 2));
        assertTrue(Game.edgeSwipeComplete(10, 500, 10 + 120, 500, 2));
    }

    @Test
    public void edgeSwipeIsSidewaysNotUpOrDown() {
        // A tap stays where it started, and a vertical scroll along the edge stays vertical:
        // neither may open the slider or the menu
        assertFalse(Game.edgeSwipeComplete(10, 500, 10, 500, 1));
        assertFalse(Game.edgeSwipeComplete(10, 500, 10 + 30, 500 + 40, 1));
        assertFalse(Game.edgeSwipeComplete(10, 500, 10 + 100, 500 + 120, 1));
        // A diagonal swipe still counts while it goes further in than up
        assertTrue(Game.edgeSwipeComplete(10, 500, 10 + 80, 500 + 79, 1));
        assertFalse(Game.edgeSwipeComplete(10, 500, 10 + 80, 500 + 80, 1));
        // Swiping out towards the edge never counts
        assertFalse(Game.edgeSwipeComplete(100, 500, 30, 500, 1));
    }
}
