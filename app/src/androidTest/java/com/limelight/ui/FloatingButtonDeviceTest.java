package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.testing.ZteGameMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The floating menu button on a device: dragged by touches through the window, its spot is saved,
 * and on a new screen (the activity recreated, as the next stream's screen is) it starts in the
 * same place. A tap still only opens the menu. The stream screen needs a PC, so the real button
 * (floating_menu_button.xml) goes on the settings screen, set up the way Game sets it up. The
 * device's own saved spot is put back afterwards. FloatingButtonTest covers turns and resizes.
 * It skips where the phone maker's game mode crashes recreated screens (ZteGameMode).
 */
@RunWith(AndroidJUnit4.class)
public class FloatingButtonDeviceTest {
    private static final String[] KEYS = {"floating_button_landscape_x", "floating_button_landscape_y",
            "floating_button_portrait_x", "floating_button_portrait_y"};
    private static final long WAIT_MS = 5000;

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final SharedPreferences prefs =
            PreferenceManager.getDefaultSharedPreferences(instrumentation.getTargetContext());
    private final Map<String, Float> before = new HashMap<>();
    private final AtomicInteger taps = new AtomicInteger();
    private Activity activity;
    private View button;

    @Before
    public void setUp() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : KEYS) {
            Object value = prefs.getAll().get(key);
            if (value instanceof Float) {
                before.put(key, (Float) value);
            }
            editor.remove(key);
        }
        assertTrue(editor.commit());
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : KEYS) {
            Float value = before.get(key);
            if (value != null) {
                editor.putFloat(key, value);
            }
            else {
                editor.remove(key);
            }
        }
        assertTrue(editor.commit());
    }

    @Test
    public void aDraggedButtonStartsInTheSameSpotOnTheNextScreen() {
        // The next screen is this one recreated
        ZteGameMode.assumeScreensCanBeRecreated();
        try (ActivityScenario<StreamSettings> scenario = ActivityScenario.launch(StreamSettings.class)) {
            scenario.onActivity(this::addButton);
            waitFor("the button to be laid out", () -> button.isLaidOut());
            onMain(() -> {
                assertEquals("not where the layout puts it", 0, button.getTranslationX(), 0);
                assertEquals("not where the layout puts it", 0, button.getTranslationY(), 0);
            });

            // The room it moves in, and whether the window counts as portrait
            float[] screen = new float[3];
            onMain(() -> {
                View window = activity.getWindow().getDecorView();
                screen[0] = window.getWidth() - button.getWidth();
                screen[1] = window.getHeight() - button.getHeight();
                screen[2] = window.getHeight() > window.getWidth() ? 1 : 0;
            });
            float roomX = screen[0];
            float roomY = screen[1];

            // Halfway to the far corner
            float[] start = position();
            int dx = Math.round((roomX - start[0]) / 2);
            int dy = Math.round((roomY - start[1]) / 2);
            assertTrue("no room to drag the button", dx > 20 && dy > 20);
            drag(dx, dy);
            float[] dragged = position();
            assertEquals(start[0] + dx, dragged[0], 1);
            assertEquals(start[1] + dy, dragged[1], 1);
            assertEquals("a drag isn't a tap", 0, taps.get());

            float[] spot = PreferenceConfiguration.readFloatingButtonSpot(prefs, screen[2] == 1);
            assertEquals(dragged[0] / roomX, spot[0], 0.001f);
            assertEquals(dragged[1] / roomY, spot[1], 0.001f);

            // A new screen puts it back where it was dragged
            scenario.recreate();
            scenario.onActivity(this::addButton);
            waitFor("the button back in its spot", () -> button.isLaidOut() &&
                    Math.abs(button.getX() - dragged[0]) <= 1 && Math.abs(button.getY() - dragged[1]) <= 1);
            float[] again = position();
            assertEquals(dragged[0], again[0], 1);
            assertEquals(dragged[1], again[1], 1);

            // A tap opens the menu and leaves the button where it is
            tap();
            assertEquals(1, taps.get());
            float[] tapped = position();
            assertEquals(again[0], tapped[0], 0);
            assertEquals(again[1], tapped[1], 0);
        }
    }

    // The button as the stream screen has it: over the content, moving within the whole window
    private void addButton(Activity screenActivity) {
        activity = screenActivity;
        ViewGroup content = screenActivity.findViewById(android.R.id.content);
        button = LayoutInflater.from(screenActivity).inflate(R.layout.floating_menu_button, content, false);
        content.addView(button);
        FloatingButton floating = new FloatingButton(button, screenActivity.getWindow().getDecorView(),
                PreferenceManager.getDefaultSharedPreferences(screenActivity), taps::incrementAndGet);
        button.setVisibility(View.VISIBLE);
        floating.placeOnNextLayout();
    }

    private float[] position() {
        float[] xy = new float[2];
        onMain(() -> {
            xy[0] = button.getX();
            xy[1] = button.getY();
        });
        return xy;
    }

    // A finger on the button's middle, moved in steps, then lifted. Sent to the window's top view,
    // which hands it to whatever is under the finger, as with a real touch.
    private void drag(int dx, int dy) {
        onMain(() -> {
            float[] middle = middle();
            long down = SystemClock.uptimeMillis();
            send(down, down, MotionEvent.ACTION_DOWN, middle[0], middle[1]);
            int steps = 10;
            for (int i = 1; i <= steps; i++) {
                send(down, down + i * 20L, MotionEvent.ACTION_MOVE,
                        middle[0] + (float) dx * i / steps, middle[1] + (float) dy * i / steps);
            }
            send(down, down + (steps + 1) * 20L, MotionEvent.ACTION_UP, middle[0] + dx, middle[1] + dy);
        });
    }

    private void tap() {
        onMain(() -> {
            float[] middle = middle();
            long down = SystemClock.uptimeMillis();
            send(down, down, MotionEvent.ACTION_DOWN, middle[0], middle[1]);
            send(down, down + 50, MotionEvent.ACTION_UP, middle[0], middle[1]);
        });
    }

    // The button's middle, in the window
    private float[] middle() {
        int[] at = new int[2];
        button.getLocationInWindow(at);
        return new float[] {at[0] + button.getWidth() / 2f, at[1] + button.getHeight() / 2f};
    }

    private void send(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        activity.getWindow().getDecorView().dispatchTouchEvent(event);
        event.recycle();
    }

    private void onMain(Runnable check) {
        instrumentation.runOnMainSync(check);
    }

    private interface Check {
        boolean done();
    }

    // Layout follows the screen's next frame, so look until it has happened
    private void waitFor(String what, Check condition) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        boolean[] met = new boolean[1];
        do {
            instrumentation.waitForIdleSync();
            onMain(() -> met[0] = condition.done());
            if (met[0]) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("timed out after " + WAIT_MS + " ms waiting for " + what);
    }
}
