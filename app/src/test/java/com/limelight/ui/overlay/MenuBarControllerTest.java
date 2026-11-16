package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * A controller in the stream menu's bar. Close sits above Quit and Disconnect, except on screens
 * under 480 dp tall (a phone or a 1080p handheld held sideways), where all three share one row.
 * The device test that walks the whole menu (OverlayMenuViewTest#controllerMovesThroughTheGridAndTheBar)
 * pressed down from Close on every screen, and failed on the 1080p handheld legs, 432 dp tall.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class MenuBarControllerTest {
    private Activity activity;
    private OverlayMenuView menu;
    private final List<String> events = new ArrayList<>();

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        menu = new OverlayMenuView(activity);
        activity.setContentView(menu);
        // Records which of the menu's actions ran, in order
        menu.setMenuActionListener((OverlayMenuView.MenuActionListener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OverlayMenuView.MenuActionListener.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("equals") ? proxy == args[0]
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : "listener";
                    }
                    if (method.getName().equals("isToggleOn")) {
                        return false;
                    }
                    events.add(method.getName());
                    return null;
                }));
        menu.show();
        shadowOf(Looper.getMainLooper()).idle();
        // From Close up into the grid and back down, as the device test starts
        press(KeyEvent.KEYCODE_DPAD_UP);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("Close isn't selected", selected(R.string.overlay_menu_close));
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land") // a 1080p handheld: 1920x1080 at 400 dpi
    public void onAShortScreenCloseQuitAndDisconnectShareARow() {
        assertTrue(activity.getResources().getConfiguration().screenHeightDp < OverlayMenuView.COMPACT_HEIGHT_DP);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("down from Close left the one-row bar", selected(R.string.overlay_menu_close));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(selected(R.string.overlay_menu_quit_session));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(selected(R.string.overlay_menu_disconnect));
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue(selected(R.string.overlay_menu_close));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("onDisconnect", "onMenuClosed"), events);
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land") // a tablet
    public void onATallScreenQuitAndDisconnectSitBelowClose() {
        assertTrue(activity.getResources().getConfiguration().screenHeightDp >= OverlayMenuView.COMPACT_HEIGHT_DP);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(selected(R.string.overlay_menu_quit_session));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(selected(R.string.overlay_menu_disconnect));
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(selected(R.string.overlay_menu_close));
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("onDisconnect", "onMenuClosed"), events);
    }

    private void press(int keyCode) {
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private boolean selected(int labelResId) {
        View button = find(menu, activity.getString(labelResId));
        assertNotNull("no button " + activity.getString(labelResId), button);
        return button.isSelected();
    }

    // The menu button whose label is exactly this text
    private static View find(View view, String label) {
        if (view instanceof OverlayMenuButton) {
            ViewGroup button = (ViewGroup) view;
            for (int i = 0; i < button.getChildCount(); i++) {
                View child = button.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return view;
                }
            }
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
