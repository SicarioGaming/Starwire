package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The settings list with gesture navigation, where the see-through gesture bar covers the bottom
 * of the screen but has nothing in it to tap. Its last row has to scroll clear of the bar, while
 * the list still runs behind it (Moonlight X a5987f77).
 */
@RunWith(RobolectricTestRunner.class)
// 720 x 1280 px. The settings read MoonBridge's audio constants, and MoonBridge loads the native
// streaming library in its static block. Instrumenting it lets Robolectric skip loadLibrary.
@Config(sdk = 36, qualifiers = "w360dp-h640dp-normal-notlong-port-xhdpi",
        instrumentedPackages = {"com.limelight.nvstream.jni"})
public class SettingsGestureBarTest {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final int STATUS_BAR = 48;
    // 24 dp at this density, about what a phone's gesture bar takes
    private static final int GESTURE_BAR = 48;

    private static WindowInsets gestureNavigation() {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, GESTURE_BAR))
                // The status bar can be tapped; the gesture bar can't
                .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, STATUS_BAR, 0, 0))
                .build();
    }

    private static void layOut(View window) {
        window.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, WIDTH, HEIGHT);
    }

    private static int bottomInWindow(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight();
    }

    @Test
    public void theLastSettingScrollsClearOfAGestureBar() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        View window = activity.getWindow().getDecorView();

        // Given to the content view, as a device does once the screen is attached. Robolectric's
        // own window has no bars, and its decor would handle the insets differently.
        activity.findViewById(android.R.id.content).dispatchApplyWindowInsets(gestureNavigation());
        layOut(window);

        // To the end of the list, as far as a fling can go
        ListView list = activity.findViewById(android.R.id.list);
        list.setSelection(list.getCount() - 1);
        layOut(window);
        shadowOf(Looper.getMainLooper()).idle();
        layOut(window);

        assertEquals("the last setting is on screen", list.getCount() - 1, list.getLastVisiblePosition());
        View lastRow = list.getChildAt(list.getChildCount() - 1);
        int clear = HEIGHT - GESTURE_BAR;
        assertTrue("the last setting ends at " + bottomInWindow(lastRow) + " px, under the gesture bar from "
                + clear + " px", bottomInWindow(lastRow) <= clear);

        // The list itself still reaches the bottom of the screen, so it scrolls behind the bar
        assertEquals(HEIGHT, bottomInWindow(list));
    }
}
