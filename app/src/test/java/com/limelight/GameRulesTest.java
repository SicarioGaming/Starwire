package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

/**
 * The stream screen's decisions that don't need a device: which orientation it asks for, and
 * what a completed multi-finger tap does.
 */
public class GameRulesTest {
    private static final int LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    private static final int PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    private static final int ANY = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;

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
    public void threeFingersToggleTheKeyboardAndFourOpenTheMenu() {
        // Issue #6: the upstream Moonlight mapping — a three-finger tap brings up the
        // soft keyboard, four or more open the overlay menu (the floating button and
        // holding Select on a controller reach it too)
        assertEquals(Game.TAP_KEYBOARD, Game.multiFingerTapAction(3, 100));
        assertEquals(Game.TAP_MENU, Game.multiFingerTapAction(4, 100));
        assertEquals("more fingers still mean the menu", Game.TAP_MENU,
                Game.multiFingerTapAction(5, 100));
    }

    @Test
    public void slowGesturesAreNotTaps() {
        // Held past the threshold a gesture is just a touch, whatever the finger count
        assertEquals(Game.TAP_NONE,
                Game.multiFingerTapAction(3, Game.MULTI_FINGER_TAP_THRESHOLD));
        assertEquals(Game.TAP_NONE,
                Game.multiFingerTapAction(4, Game.MULTI_FINGER_TAP_THRESHOLD + 500));
    }

    @Test
    public void oneAndTwoFingerTouchesAreNotTaps() {
        // Single and paired fingers keep their normal touch meaning
        assertEquals(Game.TAP_NONE, Game.multiFingerTapAction(1, 50));
        assertEquals(Game.TAP_NONE, Game.multiFingerTapAction(2, 50));
    }

}
