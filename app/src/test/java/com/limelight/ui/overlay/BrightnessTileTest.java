package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;
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
 * The stream menu's Brightness tile, which opens Moonlight X's brightness slider: a tile like its
 * neighbours, the last of the built-in ones, and it closes the menu before the slider opens so
 * the slider gets the controller. Game's side (the slider itself) is in GameBrightnessTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class BrightnessTileTest {
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
    }

    private String label(int resId) {
        return activity.getString(resId);
    }

    // The menu's tiles, in order
    private List<OverlayMenuButton> tiles() {
        List<OverlayMenuButton> tiles = new ArrayList<>();
        collect(menu, tiles);
        return tiles;
    }

    private static void collect(View view, List<OverlayMenuButton> tiles) {
        if (view instanceof OverlayMenuButton) {
            // The tiles, in the grid's ScrollView, not the bar's pills below it
            for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof ScrollView) {
                    tiles.add((OverlayMenuButton) view);
                    break;
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), tiles);
            }
        }
    }

    private static String text(OverlayMenuButton button) {
        for (int i = 0; i < button.getChildCount(); i++) {
            if (button.getChildAt(i) instanceof TextView) {
                return ((TextView) button.getChildAt(i)).getText().toString();
            }
        }
        return null;
    }

    private OverlayMenuButton brightness() {
        for (OverlayMenuButton tile : tiles()) {
            if (label(R.string.overlay_menu_brightness).equals(text(tile))) {
                return tile;
            }
        }
        return null;
    }

    private void press(int keyCode) {
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    @Test
    public void theMenuHasABrightnessTileAfterTheOtherBuiltInOnes() {
        OverlayMenuButton tile = brightness();
        assertNotNull("no Brightness tile in the stream menu", tile);
        List<OverlayMenuButton> tiles = tiles();
        // No custom commands are saved here, so it's the grid's last tile, after Menu Button
        assertSame(tile, tiles.get(tiles.size() - 1));
        assertEquals(label(R.string.overlay_menu_floating_button), text(tiles.get(tiles.size() - 2)));
        assertFalse("Brightness opens the slider, it doesn't switch anything", tile.isToggle());
        assertTrue(tile.isFocusable());
    }

    @Test
    public void tappingBrightnessClosesTheMenuThenOpensTheSlider() {
        assertTrue(brightness().performClick());
        assertEquals(List.of("onMenuClosed", "onShowBrightness"), events);
        assertEquals(View.GONE, menu.getVisibility());
    }

    @Test
    public void aControllerReachesBrightnessFromCloseAndOpensItWithA() {
        // The menu opens on Close; up goes to the grid's last tile
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("Brightness isn't selected", brightness().isSelected());
        press(KeyEvent.KEYCODE_BUTTON_A);
        assertEquals(List.of("onMenuClosed", "onShowBrightness"), events);
        assertEquals(View.GONE, menu.getVisibility());
    }
}
