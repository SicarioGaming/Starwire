package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * Error and progress dialogs are often posted from a network thread, so they can reach the UI
 * thread after their screen is gone. Android can destroy a screen without finishing it, when it
 * recreates it or reclaims it in the background. isFinishing() is still false then, and a dialog
 * shown for it would belong to a screen that's gone. What a phone does with one hasn't been
 * tried: Robolectric doesn't model the window manager, so these tests check the dialog isn't
 * shown at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class LateDialogTest {
    // A screen that's up, then one Android has destroyed without finishing it
    private static ActivityController<Activity> screen() {
        return Robolectric.buildActivity(Activity.class).setup();
    }

    private static Activity destroyedScreen() {
        ActivityController<Activity> screen = screen();
        screen.pause().stop().destroy();
        Activity activity = screen.get();
        assertTrue(activity.isDestroyed());
        assertFalse("destroyed, not finished, as when Android recreates it", activity.isFinishing());
        return activity;
    }

    // Posts from another thread, as the network code does, then lets the UI thread run it
    private static void fromNetworkThread(Runnable post) throws InterruptedException {
        Thread network = new Thread(post, "LateDialogTest");
        network.start();
        network.join();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void anErrorForADestroyedScreenIsNotShown() throws InterruptedException {
        Activity activity = destroyedScreen();
        fromNetworkThread(() -> Dialog.displayDialog(activity, "Connection error", "The PC went away", false));
        assertNull("no dialog on a destroyed screen", ShadowDialog.getLatestDialog());
    }

    @Test
    public void aSpinnerForADestroyedScreenIsNotShown() throws InterruptedException {
        Activity activity = destroyedScreen();
        fromNetworkThread(() -> SpinnerDialog.displayDialog(activity, "Connecting", "Please wait", false));
        assertNull("no spinner on a destroyed screen", ShadowDialog.getLatestDialog());
    }

    // The checks above mustn't hide dialogs for a screen that's still there
    @Test
    public void anErrorForAScreenThatsUpIsShown() throws InterruptedException {
        Activity activity = screen().get();
        fromNetworkThread(() -> Dialog.displayDialog(activity, "Connection error", "The PC went away", false));
        android.app.Dialog shown = ShadowDialog.getLatestDialog();
        assertTrue(shown != null && shown.isShowing());
    }

    @Test
    public void aSpinnerForAScreenThatsUpIsShown() throws InterruptedException {
        Activity activity = screen().get();
        fromNetworkThread(() -> SpinnerDialog.displayDialog(activity, "Connecting", "Please wait", false));
        android.app.Dialog shown = ShadowDialog.getLatestDialog();
        assertTrue(shown != null && shown.isShowing());
    }
}
