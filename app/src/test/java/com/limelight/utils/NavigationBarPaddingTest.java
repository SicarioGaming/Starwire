package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The padding UiHelper.applyStatusBarPadding() keeps at the bottom of a list for the navigation
 * bar (Moonlight X a5987f77). SettingsGestureBarTest checks it in Settings with gesture
 * navigation; these check the parts that test can't see.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class NavigationBarPaddingTest {
    // 48 dp at xhdpi, what 3-button navigation takes
    private static final int BUTTON_BAR = 96;

    // Counts its requests for insets, split by whether it was on screen for each
    private static class CountingView extends View {
        int requestsOnScreen;
        int requestsOffScreen;

        CountingView(Context context) {
            super(context);
        }

        @Override
        public void requestApplyInsets() {
            if (isAttachedToWindow()) {
                requestsOnScreen++;
            }
            else {
                requestsOffScreen++;
            }
            super.requestApplyInsets();
        }
    }

    /**
     * A fragment makes its view in onCreateView(), before the view is on screen, and asking for
     * insets then does nothing. So the ask has to wait until the view is attached.
     */
    @Test
    public void aViewPaddedBeforeItIsShownAsksForInsetsOnceShown() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CountingView view = new CountingView(activity);

        UiHelper.applyStatusBarPadding(view);
        assertFalse(view.isAttachedToWindow());
        assertEquals("asked while nothing can answer", 0, view.requestsOffScreen);

        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(view);
        assertEquals("didn't ask once on screen", 1, view.requestsOnScreen);
    }

    // With 3-button navigation the whole bar can be tapped, and the padding is the bar, as before
    @Test
    public void threeButtonNavigationKeepsItsPadding() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        UiHelper.applyStatusBarPadding(view);

        view.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, BUTTON_BAR))
                .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, BUTTON_BAR))
                .build());
        assertEquals(BUTTON_BAR, view.getPaddingBottom());
    }
}
