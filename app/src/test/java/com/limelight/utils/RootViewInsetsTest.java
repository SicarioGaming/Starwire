package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * What UiHelper.notifyNewRootView() does with the window insets on a phone or tablet (Android 10
 * and later, not TVs): the PC list, the games screen and the settings screens all use it. It pads
 * the screen for the status bar and makes the navigation bar see-through when it can't be tapped.
 *
 * On the Android 11 phone emulators, the device tests that open those screens waited forever for
 * the app's main thread to have nothing to do (2026-10-23). Setting a window flag sends the
 * window's attributes to the system again even when the flag was already set, and the screen set
 * it on every insets pass. If Android answers each send with another insets pass, that never
 * ends. That Android 11 does so is a guess, not checked: these run on Android 16 in Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class RootViewInsetsTest {
    // 48 dp at xhdpi, what 3-button navigation takes
    private static final int BUTTON_BAR = 96;
    // Enough to show a loop: the screen needs one send at most
    private static final int ECHO_LIMIT = 10;

    /** Counts the times its window's attributes go out, and can answer each with an insets pass. */
    public static class EchoingActivity extends Activity {
        int sends;
        WindowInsets echo;

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
            super.onWindowAttributesChanged(params);
            sends++;
            if (echo != null && sends < ECHO_LIMIT) {
                findViewById(android.R.id.content).dispatchApplyWindowInsets(echo);
            }
        }
    }

    private static WindowInsets insets(int tappableBottom) {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, BUTTON_BAR))
                .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, tappableBottom))
                .build();
    }

    private static boolean translucentNavigation(Activity activity) {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0;
    }

    private static EchoingActivity screen() {
        EchoingActivity activity = Robolectric.buildActivity(EchoingActivity.class).setup().get();
        UiHelper.notifyNewRootView(activity);
        activity.sends = 0;
        return activity;
    }

    // 3-button navigation can be tapped, so the bar is see-through; gesture navigation can't
    @Test
    public void theNavigationBarIsSeeThroughOnlyWhereItCanBeTapped() {
        EchoingActivity activity = screen();
        activity.findViewById(android.R.id.content).dispatchApplyWindowInsets(insets(BUTTON_BAR));
        assertTrue(translucentNavigation(activity));
        activity.findViewById(android.R.id.content).dispatchApplyWindowInsets(insets(0));
        assertFalse(translucentNavigation(activity));
    }

    @Test
    public void theWindowIsOnlySentAgainWhenTheBarChanges() {
        EchoingActivity activity = screen();
        for (int pass = 0; pass < 3; pass++) {
            activity.findViewById(android.R.id.content).dispatchApplyWindowInsets(insets(BUTTON_BAR));
        }
        assertEquals("sends for three passes with the same insets", 1, activity.sends);
    }

    @Test
    public void aSystemThatAnswersEachSendWithMoreInsetsStillSettles() {
        EchoingActivity activity = screen();
        activity.echo = insets(BUTTON_BAR);
        activity.findViewById(android.R.id.content).dispatchApplyWindowInsets(activity.echo);
        assertEquals("sends before it settled (" + ECHO_LIMIT + " means it never would)", 1, activity.sends);
        assertTrue(translucentNavigation(activity));
    }
}
