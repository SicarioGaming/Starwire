package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;

/**
 * The Shortcuts and Monitors pages, chosen with a controller as fast as it can press, open at
 * their top with their first tile in sight, on a short screen where the main grid scrolls.
 *
 * The review asked whether the scroll that carried on over the USB devices page (fixed by
 * OverlayMenuView's gridScroll.fling(0), shown by ActivityScreensTest's streamMenuUsbDevices)
 * also hit these two pages. These tests pass with and without that line: here it doesn't.
 */
@RunWith(RobolectricTestRunner.class)
// A small 16:9 handheld held sideways (RoboDevices.HANDHELD_SMALL), where the grid scrolls
@Config(sdk = 36, qualifiers = "w667dp-h375dp-normal-long-land-xhdpi")
public class MenuPageScrollTest {
    private OverlayMenuView menu;

    private void openMenu() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        Activity activity = controller.get();
        activity.setTheme(R.style.StreamTheme);
        FrameLayout frame = new FrameLayout(activity);
        LayoutInflater.from(activity).inflate(R.layout.activity_game, frame, true);
        activity.setContentView(frame);
        controller.start().resume().visible();
        menu = frame.findViewById(R.id.overlayMenuView);
        menu.setGameInfo("Desktop", "1920 x 1080, 120 FPS", 0xFF336699);
        menu.setMenuActionListener((OverlayMenuView.MenuActionListener) Proxy.newProxyInstance(
                OverlayMenuView.MenuActionListener.class.getClassLoader(),
                new Class<?>[] {OverlayMenuView.MenuActionListener.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null));
        menu.show();
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Long enough for the sheet's slide and any scroll to end
    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    private void press(int keyCode) {
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private int gridIndex() {
        return ReflectionHelpers.getField(menu, "gridIndex");
    }

    private ScrollView grid() {
        return ReflectionHelpers.getField(menu, "gridScroll");
    }

    // As fast as a controller can: from Close up to the main grid, along it to the tile and A,
    // with no time passing between presses, as in ActivityScreensTest's streamMenuUsbDevices
    private void chooseQuickly(int labelRes) {
        openMenu();
        List<String> labels = ReflectionHelpers.getField(menu, "gridLabels");
        int target = labels.indexOf(menu.getContext().getString(labelRes));
        int columns = ReflectionHelpers.getField(menu, "columns");
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("the grid doesn't scroll on this screen", grid().getScrollY() > 0);
        while (gridIndex() / columns > target / columns) {
            press(KeyEvent.KEYCODE_DPAD_UP);
        }
        while (gridIndex() != target) {
            press(gridIndex() > target ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
        }
        press(KeyEvent.KEYCODE_BUTTON_A);
        settle();
    }

    @Test
    public void shortcutsOpensAtItsTop() {
        chooseQuickly(R.string.overlay_menu_shortcuts);
        assertEquals("the Shortcuts page", 1, (int) ReflectionHelpers.getField(menu, "page"));
        assertEquals(0, grid().getScrollY());
        assertEquals(0, gridIndex());
    }

    @Test
    public void monitorsOpensAtItsTop() {
        chooseQuickly(R.string.overlay_menu_monitors);
        assertEquals("the Monitors page", 2, (int) ReflectionHelpers.getField(menu, "page"));
        assertEquals(0, grid().getScrollY());
        assertEquals(0, gridIndex());
    }
}
