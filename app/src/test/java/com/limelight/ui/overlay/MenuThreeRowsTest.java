package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * On a tablet held sideways the stream menu shows every tool at once: seven across in three rows,
 * nothing to scroll, and Close, Quit Session and Disconnect whole below them. The sheet grows to
 * hold seven tiles of the usual size. Phones and handhelds keep the usual sheet and scroll as
 * before, and so does a tablet with Android's Large font size, where seven tiles of the usual
 * size would break a label.
 *
 * Every tool is shown here: Fix PC Cursor, Brightness and USB devices too, 20 tiles. With
 * Sensitivity, and five across, the RedMagic Astra had 21 of them in five rows, and its grid
 * scrolled.
 *
 * Real graphics, so the labels are measured as on a device.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36)
public class MenuThreeRowsTest {
    // RoboDevices.SMALL_TABLET_LANDSCAPE: an 8.8-inch tablet held sideways, 2400 x 1504 px at
    // 360 dpi (RedMagic Astra)
    private static final String ASTRA = "w1067dp-h668dp-large-notlong-land-360dpi";
    // RoboDevices.TABLET_LANDSCAPE: 2560 x 1600 px at 320 dpi
    private static final String TABLET = "w1280dp-h800dp-xlarge-notlong-land-xhdpi";
    // RoboDevices.HANDHELD_16_9: 1920 x 1080 px at 320 dpi
    private static final String HANDHELD = "w960dp-h540dp-normal-long-land-xhdpi";

    // Keyboard to Menu Button, then the three that show only sometimes
    private static final int TOOLS = 20;

    private Activity activity;
    private FrameLayout frame;
    private OverlayMenuView menu;

    // The menu as the stream screen has it (activity_game's margins), opened with every tool shown
    private void openMenu() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        activity = controller.get();
        activity.setTheme(R.style.StreamTheme);
        frame = new FrameLayout(activity);
        LayoutInflater.from(activity).inflate(R.layout.activity_game, frame, true);
        activity.setContentView(frame);
        controller.start().resume().visible();
        menu = frame.findViewById(R.id.overlayMenuView);
        menu.setGameInfo("Desktop", "1920 x 1080, 120 FPS", 0xFF336699);
        menu.setMenuActionListener((OverlayMenuView.MenuActionListener) Proxy.newProxyInstance(
                OverlayMenuView.MenuActionListener.class.getClassLoader(),
                new Class<?>[] {OverlayMenuView.MenuActionListener.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false
                        : method.getName().equals("usbDevices") ? new ArrayList<>() : null));
        menu.setFixHostCursorShown(true);
        menu.setUsbDevicesShown(true);
        menu.show();
        // Long enough for the sheet's slide in to end
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    private ScrollView grid() {
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (menu.getChildAt(i) instanceof ScrollView) {
                return (ScrollView) menu.getChildAt(i);
            }
        }
        throw new AssertionError("the menu has no grid");
    }

    // The grid's rows of tiles
    private ViewGroup rows() {
        return (ViewGroup) grid().getChildAt(0);
    }

    private List<OverlayMenuButton> tiles() {
        List<OverlayMenuButton> tiles = new ArrayList<>();
        ViewGroup rows = rows();
        for (int r = 0; r < rows.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) rows.getChildAt(r);
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i) instanceof OverlayMenuButton) {
                    tiles.add((OverlayMenuButton) row.getChildAt(i));
                }
            }
        }
        return tiles;
    }

    private int dp(int px) {
        return Math.round(px / activity.getResources().getDisplayMetrics().density);
    }

    private void assertEveryToolShowsInThreeRows() {
        openMenu();
        assertTrue("the tools take " + rows().getChildCount() + " rows", rows().getChildCount() <= 3);
        assertTrue("the grid needs " + dp(rows().getHeight()) + " dp and shows " + dp(grid().getHeight())
                + ", so it scrolls", rows().getHeight() <= grid().getHeight());
        assertEquals("tools on the menu", TOOLS, tiles().size());
        for (OverlayMenuButton tile : tiles()) {
            assertShowsWhole(tile);
            assertLabelWhole(tile);
        }
        for (int label : new int[] {R.string.overlay_menu_close, R.string.overlay_menu_quit_session,
                R.string.overlay_menu_disconnect}) {
            View button = find(menu, activity.getString(label));
            assertNotNull("no " + activity.getString(label) + " on the menu", button);
            assertShowsWhole(button);
        }
        // The sheet takes at most 60% of the screen's width, so the game still shows beside it
        assertTrue("the sheet is " + dp(menu.getWidth()) + " dp of the screen's " + dp(frame.getWidth()),
                menu.getWidth() <= frame.getWidth() * 0.6f);
    }

    // On the screen, and none of it cut off by the grid, the sheet or the screen's edge
    private void assertShowsWhole(View view) {
        Rect shown = new Rect();
        String name = view instanceof OverlayMenuButton ? "\"" + label(view) + "\"" : "a button";
        assertTrue(name + " doesn't show", view.getGlobalVisibleRect(shown));
        assertEquals(name + " is cut off at the side", view.getWidth(), shown.width());
        assertEquals(name + " is cut off at the top or bottom", view.getHeight(), shown.height());
        assertTrue(name + " is off the screen", shown.right <= frame.getWidth() && shown.bottom <= frame.getHeight());
    }

    // Two lines at most, broken only between words, as TileColumns makes sure of
    private static void assertLabelWhole(OverlayMenuButton tile) {
        TextView text = labelView(tile);
        Layout layout = text.getLayout();
        assertNotNull("\"" + text.getText() + "\" isn't laid out", layout);
        CharSequence label = text.getText();
        assertTrue("\"" + label + "\" needs " + layout.getLineCount() + " lines", layout.getLineCount() <= 2);
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            char before = label.charAt(layout.getLineEnd(line) - 1);
            assertTrue("\"" + label + "\" breaks inside a word",
                    Character.isWhitespace(before) || before == '-' || before == '/');
        }
    }

    @Test
    @Config(qualifiers = ASTRA)
    public void anAstraShowsEveryToolInThreeRows() {
        assertEveryToolShowsInThreeRows();
    }

    @Test
    @Config(qualifiers = TABLET)
    public void aTabletShowsEveryToolInThreeRows() {
        assertEveryToolShowsInThreeRows();
    }

    @Test
    @Config(qualifiers = HANDHELD)
    public void aHandheldKeepsTheUsualSheet() {
        // Seven across would take more than 60% of its width, so it stays five across on 440 dp
        // and scrolls, as before
        openMenu();
        assertEquals(440, dp(menu.getWidth()));
        assertEquals(5, ((ViewGroup) rows().getChildAt(0)).getChildCount());
        assertTrue("the grid doesn't scroll", rows().getHeight() > grid().getHeight());
    }

    @Test
    @Config(qualifiers = ASTRA)
    public void anAstraWithLargeTextKeepsTheUsualSheet() {
        // With Android's Large font size (115%) a label no longer fits a tile of the usual size,
        // so the sheet stays 440 dp with five across or fewer, every label whole
        RuntimeEnvironment.setFontScale(1.15f);
        openMenu();
        assertEquals(440, dp(menu.getWidth()));
        int columns = ((ViewGroup) rows().getChildAt(0)).getChildCount();
        assertTrue(columns + " across", columns <= 5);
        for (OverlayMenuButton tile : tiles()) {
            assertLabelWhole(tile);
        }
    }

    private void press(int keyCode) {
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void assertSelected(int labelResId) {
        String label = activity.getString(labelResId);
        View button = find(menu, label);
        assertNotNull("no " + label + " on the menu", button);
        assertTrue(label + " isn't selected", button.isSelected());
    }

    @Test
    @Config(qualifiers = ASTRA)
    public void aControllerMovesThroughSevenAcross() {
        // Rows of seven: Keyboard to Monitors, Bitrate to PC Commands, then Task Manager to USB
        // devices, one short
        openMenu();
        assertSelected(R.string.overlay_menu_close);
        // Up from Close to the last tile, then up a row at a time to the top
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertSelected(R.string.overlay_menu_usb_devices);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertSelected(R.string.overlay_menu_fetch_clipboard);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertSelected(R.string.overlay_menu_shortcuts);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertSelected(R.string.overlay_menu_shortcuts);
        // Right to the end of the top row, and no further
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSelected(R.string.overlay_menu_monitors);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSelected(R.string.overlay_menu_monitors);
        // Down the seventh column. The last row has no seventh tile, so down goes to its last one
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSelected(R.string.overlay_menu_server_commands);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSelected(R.string.overlay_menu_usb_devices);
        // Left along the last row to its start, and no further
        for (int i = 0; i < 5; i++) {
            press(KeyEvent.KEYCODE_DPAD_LEFT);
        }
        assertSelected(R.string.overlay_menu_task_manager);
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        assertSelected(R.string.overlay_menu_task_manager);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertSelected(R.string.overlay_menu_bitrate);
        // Down from the last row to the bar: Close, then Quit Session and Disconnect below it
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSelected(R.string.overlay_menu_close);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSelected(R.string.overlay_menu_quit_session);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSelected(R.string.overlay_menu_disconnect);
    }

    private static TextView labelView(View button) {
        ViewGroup group = (ViewGroup) button;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof TextView) {
                return (TextView) group.getChildAt(i);
            }
        }
        throw new AssertionError("a tile with no label");
    }

    private static String label(View button) {
        return labelView(button).getText().toString();
    }

    // The menu button whose label is exactly this text
    private static View find(View view, String label) {
        if (view instanceof OverlayMenuButton) {
            return label.contentEquals(labelView(view).getText()) ? view : null;
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
