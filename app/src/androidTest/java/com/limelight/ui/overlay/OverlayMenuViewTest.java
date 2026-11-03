package com.limelight.ui.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the Starwire overlay menu buttons reach the right actions, without a stream.
 */
@RunWith(AndroidJUnit4.class)
public class OverlayMenuViewTest {
    private Context context;
    private OverlayMenuView menu;
    private RecordingListener listener;

    private static class RecordingListener implements OverlayMenuView.MenuActionListener {
        final List<String> events = new ArrayList<>();
        final java.util.Set<Integer> on = new java.util.HashSet<>();
        short[] lastKeys;

        private void flip(int toggle) {
            if (!on.remove(toggle)) {
                on.add(toggle);
            }
        }

        @Override public void onDisconnect() { events.add("disconnect"); }
        @Override public void onQuitSession() { events.add("quit"); }
        @Override public void onToggleStats() { events.add("stats"); flip(OverlayMenuView.TOGGLE_STATS); }
        @Override public void onToggleMouseEmulation() { events.add("mouseEmulation"); flip(OverlayMenuView.TOGGLE_MOUSE_EMULATION); }
        @Override public void onShowKeyboard() { events.add("keyboard"); }
        @Override public void onSendGuideButton() { events.add("guide"); }
        @Override public void onCustomCommand(CustomCommand command) { events.add("custom"); }
        @Override public void onMenuOpened() { events.add("opened"); }
        @Override public void onMenuClosed() { events.add("closed"); }
        @Override public void onSendKeys(short[] keys) { events.add("keys"); lastKeys = keys; }
        @Override public void onRotateScreen() { events.add("rotate"); }
        @Override public void onToggleOnscreenController() { events.add("onscreenController"); }
        @Override public void onToggleFloatingButton() { events.add("floatingButton"); flip(OverlayMenuView.TOGGLE_FLOATING_BUTTON); }
        @Override public void onTogglePanZoom() { events.add("panZoom"); }
        @Override public void onSelectMouseMode() { events.add("mouseMode"); }
        @Override public void onToggleTouchSensitivity() { events.add("sensitivity"); }
        @Override public void onUploadClipboard() { events.add("uploadClipboard"); }
        @Override public void onFetchClipboard() { events.add("fetchClipboard"); }
        @Override public void onShowServerCommands() { events.add("serverCommands"); }
        @Override public void onToggleFullKeyboard() { events.add("fullKeyboard"); }
        @Override public void onChangeBitrate() { events.add("bitrate"); }
        @Override public void onFixHostCursor() { events.add("fixHostCursor"); }
        @Override public boolean isToggleOn(int toggle) { return on.contains(toggle); }
    }

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        listener = new RecordingListener();
        onMain(() -> {
            menu = new OverlayMenuView(context);
            menu.setMenuActionListener(listener);
            menu.buildMenu();
        });
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    private String str(int resId) {
        return context.getString(resId);
    }

    // Finds the menu button whose label is exactly this text
    private static View findButton(View root, String label) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return root;
                }
            }
            return null;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findButton(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void click(String label) {
        View button = findButton(menu, label);
        assertNotNull("Missing menu button: " + label, button);
        onMain(button::performClick);
    }

    @Test
    public void talkBackSkipsTheCoverBesideTheGameName() {
        // The header: the cover, then the game's name and stream line
        ViewGroup header = (ViewGroup) menu.getChildAt(0);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, header.getChildAt(0).getImportantForAccessibility());
        assertTrue(header.getChildAt(1) instanceof ViewGroup);
    }

    @Test
    public void fullKeyboardSitsInTheTopRowNextToKeyboard() {
        View keyboard = findButton(menu, str(com.limelight.R.string.overlay_menu_keyboard));
        View fullKeyboard = findButton(menu, str(com.limelight.R.string.overlay_menu_full_keyboard));
        assertTrue("Full Keyboard isn't in the same row as Keyboard", keyboard.getParent() == fullKeyboard.getParent());
    }

    @Test
    public void mainRowHasAllArtemideButtons() {
        int[] labels = {
            com.limelight.R.string.overlay_menu_shortcuts,
            com.limelight.R.string.overlay_menu_mouse_mode,
            com.limelight.R.string.overlay_menu_pan_zoom,
            com.limelight.R.string.overlay_menu_touch_sensitivity,
            com.limelight.R.string.overlay_menu_upload_clipboard,
            com.limelight.R.string.overlay_menu_fetch_clipboard,
            com.limelight.R.string.overlay_menu_server_commands,
            com.limelight.R.string.overlay_menu_task_manager,
            com.limelight.R.string.overlay_menu_rotate_screen,
            com.limelight.R.string.overlay_menu_onscreen_controller,
            com.limelight.R.string.overlay_menu_floating_button,
            com.limelight.R.string.overlay_menu_disconnect,
            com.limelight.R.string.overlay_menu_quit_session,
        };
        for (int label : labels) {
            assertNotNull("Missing menu button: " + str(label), findButton(menu, str(label)));
        }
    }

    @Test
    public void newButtonsCallTheirActions() {
        click(str(com.limelight.R.string.overlay_menu_pan_zoom));
        click(str(com.limelight.R.string.overlay_menu_touch_sensitivity));
        click(str(com.limelight.R.string.overlay_menu_upload_clipboard));
        click(str(com.limelight.R.string.overlay_menu_fetch_clipboard));
        click(str(com.limelight.R.string.overlay_menu_rotate_screen));
        click(str(com.limelight.R.string.overlay_menu_onscreen_controller));
        click(str(com.limelight.R.string.overlay_menu_floating_button));
        click(str(com.limelight.R.string.overlay_menu_full_keyboard));

        assertTrue(listener.events.contains("fullKeyboard"));
        assertTrue(listener.events.contains("panZoom"));
        assertTrue(listener.events.contains("sensitivity"));
        assertTrue(listener.events.contains("uploadClipboard"));
        assertTrue(listener.events.contains("fetchClipboard"));
        assertTrue(listener.events.contains("rotate"));
        assertTrue(listener.events.contains("onscreenController"));
        assertTrue(listener.events.contains("floatingButton"));
    }

    @Test
    public void togglesStayOpenAndShowTheirState() {
        listener.on.add(OverlayMenuView.TOGGLE_FLOATING_BUTTON);
        onMain(() -> menu.buildMenu());
        OverlayMenuButton stats = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_toggle_stats));
        OverlayMenuButton floating = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_floating_button));
        OverlayMenuButton guide = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_guide));
        assertTrue(stats.isToggle());
        assertFalse(stats.isToggleOn());
        assertTrue(floating.isToggleOn());
        assertFalse("one-shot buttons have no switch", guide.isToggle());

        click(str(com.limelight.R.string.overlay_menu_toggle_stats));
        click(str(com.limelight.R.string.overlay_menu_floating_button));
        click(str(com.limelight.R.string.overlay_menu_mouse_emulation));
        assertTrue(stats.isToggleOn());
        assertFalse(floating.isToggleOn());
        assertTrue(((OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_mouse_emulation))).isToggleOn());
        assertFalse("toggles keep the menu open", listener.events.contains("closed"));
    }

    @Test
    public void screenReadersHearSwitchesAndTheirState() {
        listener.on.add(OverlayMenuView.TOGGLE_FLOATING_BUTTON);
        onMain(() -> menu.buildMenu());
        OverlayMenuButton stats = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_toggle_stats));
        OverlayMenuButton floating = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_floating_button));
        OverlayMenuButton guide = (OverlayMenuButton) findButton(menu, str(com.limelight.R.string.overlay_menu_guide));
        onMain(() -> {
            android.view.accessibility.AccessibilityNodeInfo off = stats.createAccessibilityNodeInfo();
            assertTrue(off.isCheckable());
            assertFalse(off.isChecked());
            assertTrue(off.getClassName().toString().endsWith("Switch"));
            assertTrue(floating.createAccessibilityNodeInfo().isChecked());
            assertFalse("one-shot buttons aren't switches", guide.createAccessibilityNodeInfo().isCheckable());
        });
    }

    @Test
    public void dialogButtonsCloseTheMenuFirst() {
        // Touch Mode and PC Commands open a dialog, so the menu closes before the action runs
        click(str(com.limelight.R.string.overlay_menu_mouse_mode));
        assertEquals(List.of("closed", "mouseMode"), listener.events);

        listener.events.clear();
        onMain(() -> menu.buildMenu());
        click(str(com.limelight.R.string.overlay_menu_server_commands));
        assertEquals(List.of("closed", "serverCommands"), listener.events);
    }

    @Test
    public void taskManagerSendsCtrlShiftEsc() {
        click(str(com.limelight.R.string.overlay_menu_task_manager));
        assertArrayEquals(new short[]{OverlayMenuView.VK_LCONTROL, OverlayMenuView.VK_LSHIFT, OverlayMenuView.VK_ESCAPE},
                listener.lastKeys);
    }

    @Test
    public void shortcutsPageSendsKeysAndGoesBack() {
        click(str(com.limelight.R.string.overlay_menu_shortcuts));

        // The row now shows the shortcuts instead of the main actions
        assertNull(findButton(menu, str(com.limelight.R.string.overlay_menu_pan_zoom)));
        assertNotNull(findButton(menu, str(com.limelight.R.string.overlay_menu_back)));

        click(str(com.limelight.R.string.overlay_menu_send_win_d));
        assertArrayEquals(new short[]{OverlayMenuView.VK_LWIN, OverlayMenuView.VK_D}, listener.lastKeys);

        // Sending a shortcut closes the menu; reopening it starts on the main row
        onMain(() -> menu.show());
        assertNotNull(findButton(menu, str(com.limelight.R.string.overlay_menu_shortcuts)));

        // Back (the Android back key) returns to the main row instead of closing the menu
        click(str(com.limelight.R.string.overlay_menu_shortcuts));
        onMain(() -> menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        assertNotNull(findButton(menu, str(com.limelight.R.string.overlay_menu_pan_zoom)));
    }

    @Test
    public void monitorsPageSwitchesWithVibepolloShortcuts() {
        click(str(com.limelight.R.string.overlay_menu_monitors));
        click(context.getString(com.limelight.R.string.overlay_menu_monitor_n, 1));
        assertArrayEquals(new short[]{OverlayMenuView.VK_LCONTROL, OverlayMenuView.VK_LMENU,
                OverlayMenuView.VK_LSHIFT, OverlayMenuView.VK_F1}, listener.lastKeys);

        onMain(() -> menu.show());
        click(str(com.limelight.R.string.overlay_menu_monitors));
        click(context.getString(com.limelight.R.string.overlay_menu_monitor_n, 3));
        assertArrayEquals(new short[]{OverlayMenuView.VK_LCONTROL, OverlayMenuView.VK_LMENU,
                OverlayMenuView.VK_LSHIFT, (short) (OverlayMenuView.VK_F1 + 2)}, listener.lastKeys);

        // Back from the Monitors page returns to the main row
        onMain(() -> menu.show());
        click(str(com.limelight.R.string.overlay_menu_monitors));
        click(str(com.limelight.R.string.overlay_menu_back));
        assertNotNull(findButton(menu, str(com.limelight.R.string.overlay_menu_pan_zoom)));
    }

    @Test
    public void bitrateOpensItsPickerAfterClosingTheMenu() {
        click(str(com.limelight.R.string.overlay_menu_bitrate));
        assertEquals(List.of("closed", "bitrate"), listener.events);
    }

    private void press(int keyCode) {
        onMain(() -> menu.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)));
    }

    private boolean selected(int labelResId) {
        return findButton(menu, str(labelResId)).isSelected();
    }

    @Test
    public void controllerMovesThroughTheGridAndTheBar() {
        // Saved custom commands would add tiles after the built-in ones
        org.junit.Assume.assumeTrue(new CustomCommandsManager(context).getCommands().isEmpty());

        // Up from Close goes into the grid's last tile, and back down returns to Close
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(selected(com.limelight.R.string.overlay_menu_floating_button));
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(selected(com.limelight.R.string.overlay_menu_close));

        // Up from the last tile lands one row higher. The grid has two to five columns,
        // depending on the screen's width and font size (the tiles in the first row say how many)
        int[] tiles = {
                com.limelight.R.string.overlay_menu_keyboard, com.limelight.R.string.overlay_menu_full_keyboard,
                com.limelight.R.string.overlay_menu_mouse_emulation, com.limelight.R.string.overlay_menu_toggle_stats,
                com.limelight.R.string.overlay_menu_guide, com.limelight.R.string.overlay_menu_shortcuts,
                com.limelight.R.string.overlay_menu_monitors, com.limelight.R.string.overlay_menu_bitrate,
                com.limelight.R.string.overlay_menu_mouse_mode, com.limelight.R.string.overlay_menu_pan_zoom,
                com.limelight.R.string.overlay_menu_touch_sensitivity, com.limelight.R.string.overlay_menu_upload_clipboard,
                com.limelight.R.string.overlay_menu_fetch_clipboard, com.limelight.R.string.overlay_menu_server_commands,
                com.limelight.R.string.overlay_menu_task_manager, com.limelight.R.string.overlay_menu_rotate_screen,
                com.limelight.R.string.overlay_menu_onscreen_controller, com.limelight.R.string.overlay_menu_floating_button,
        };
        int columns = ((ViewGroup) findButton(menu, str(tiles[0])).getParent()).getChildCount();
        assertTrue(columns + " columns", columns >= 2 && columns <= 5);
        int last = tiles.length - 1;
        press(KeyEvent.KEYCODE_DPAD_UP);
        press(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(selected(tiles[last - columns]));
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue(selected(tiles[last - columns - 1]));

        // Down twice: the bottom row, then Close, which sits above Quit and Disconnect
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(selected(tiles[last - 1]));
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(selected(com.limelight.R.string.overlay_menu_close));
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(selected(com.limelight.R.string.overlay_menu_quit_session));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(selected(com.limelight.R.string.overlay_menu_disconnect));
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(List.of("disconnect", "closed"), listener.events);
    }

    @Test
    public void fixPcCursorShowsOnlyWhenOffered() {
        String fix = str(com.limelight.R.string.overlay_menu_fix_host_cursor);
        assertNull("Fix PC Cursor shows without being offered", findButton(menu, fix));

        onMain(() -> {
            menu.setFixHostCursorShown(true);
            menu.buildMenu();
        });
        click(fix);
        assertEquals(List.of("fixHostCursor", "closed"), listener.events);

        onMain(() -> {
            menu.setFixHostCursorShown(false);
            menu.buildMenu();
        });
        assertNull(findButton(menu, fix));
    }

    @Test
    public void everyShortcutSendsKeys() {
        String[] shortcuts = {
            str(com.limelight.R.string.overlay_menu_send_esc),
            str(com.limelight.R.string.overlay_menu_send_f11),
            str(com.limelight.R.string.overlay_menu_send_alt_f4),
            str(com.limelight.R.string.overlay_menu_send_alt_enter),
            str(com.limelight.R.string.overlay_menu_send_ctrl_v),
            str(com.limelight.R.string.overlay_menu_send_win),
            str(com.limelight.R.string.overlay_menu_send_win_d),
            str(com.limelight.R.string.overlay_menu_send_win_g),
            str(com.limelight.R.string.overlay_menu_send_ctrl_alt_tab),
            str(com.limelight.R.string.overlay_menu_send_shift_tab),
            str(com.limelight.R.string.overlay_menu_send_win_shift_left),
            str(com.limelight.R.string.overlay_menu_send_ctrl_alt_shift_f1),
            str(com.limelight.R.string.overlay_menu_send_ctrl_alt_shift_f12),
            str(com.limelight.R.string.overlay_menu_send_win_alt_b),
        };
        for (String shortcut : shortcuts) {
            listener.lastKeys = null;
            onMain(() -> menu.show());
            click(str(com.limelight.R.string.overlay_menu_shortcuts));
            click(shortcut);
            assertNotNull("No keys sent for " + shortcut, listener.lastKeys);
            assertTrue("Empty key combo for " + shortcut, listener.lastKeys.length > 0);
        }
    }
}
