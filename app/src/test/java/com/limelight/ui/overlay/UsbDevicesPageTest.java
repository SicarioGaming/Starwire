package com.limelight.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The stream menu's USB devices page, driven with a controller as on a handheld: into the page
 * from the main grid, down the devices, A on one, B back. The share flow behind it is
 * UsbShareControllerTest's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UsbDevicesPageTest {
    private Activity activity;
    private OverlayMenuView menu;
    private final List<OverlayMenuView.UsbItem> items = new ArrayList<>();
    private final List<String> chosen = new ArrayList<>();
    private boolean closed;

    private final class Listener implements OverlayMenuView.MenuActionListener {
        @Override public void onDisconnect() { }
        @Override public void onQuitSession() { }
        @Override public void onToggleStats() { }
        @Override public void onToggleMouseEmulation() { }
        @Override public void onShowKeyboard() { }
        @Override public void onSendGuideButton() { }
        @Override public void onCustomCommand(CustomCommand command) { }
        @Override public void onMenuClosed() { closed = true; }
        @Override public void onSendKeys(short[] keys) { }
        @Override public void onRotateScreen() { }
        @Override public void onToggleOnscreenController() { }
        @Override public void onToggleFloatingButton() { }
        @Override public void onTogglePanZoom() { }
        @Override public void onSelectMouseMode() { }
        @Override public void onToggleTouchSensitivity() { }
        @Override public void onUploadClipboard() { }
        @Override public void onFetchClipboard() { }
        @Override public void onShowServerCommands() { }
        @Override public void onToggleFullKeyboard() { }
        @Override public void onChangeBitrate() { }
        @Override public void onFixHostCursor() { }
        @Override public void onShowBrightness() { }
        @Override public boolean isToggleOn(int toggle) { return false; }

        @Override
        public List<OverlayMenuView.UsbItem> usbDevices() {
            return new ArrayList<>(items);
        }

        @Override
        public void onUsbDevice(String key) {
            chosen.add(key);
        }
    }

    @Before
    public void openMenu() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        menu = new OverlayMenuView(activity);
        frame.addView(menu);
        activity.setContentView(frame);
        menu.setMenuActionListener(new Listener());
        items.add(new OverlayMenuView.UsbItem("pad", "Test pad", "Checked when you share it"));
        items.add(new OverlayMenuView.UsbItem("keyboard", "Test keyboard", "Asks you first"));
    }

    private void show() {
        menu.show();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void press(int keyCode) {
        long now = SystemClock.uptimeMillis();
        menu.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 1, 0, 0,
                InputDevice.SOURCE_GAMEPAD));
        menu.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, 1, 0, 0,
                InputDevice.SOURCE_GAMEPAD));
        shadowOf(Looper.getMainLooper()).idle();
    }

    // The menu's button whose label is this text, or null
    private static OverlayMenuButton button(View root, String label) {
        if (root instanceof OverlayMenuButton) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView && label.contentEquals(((TextView) child).getText())) {
                    return (OverlayMenuButton) root;
                }
            }
            return null;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                OverlayMenuButton found = button(((ViewGroup) root).getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private OverlayMenuButton button(String label) {
        return button(menu, label);
    }

    private String str(int id) {
        return activity.getString(id);
    }

    @Test
    public void hiddenUnlessTheSettingIsOn() {
        show();
        assertNull(button(str(R.string.overlay_menu_usb_devices)));
    }

    @Test
    public void aControllerGoesIntoThePageAndBack() {
        menu.setUsbDevicesShown(true);
        show();
        assertTrue(button(str(R.string.overlay_menu_close)).isSelected());

        // Up from Close is the last tile, USB devices
        press(KeyEvent.KEYCODE_DPAD_UP);
        OverlayMenuButton usb = button(str(R.string.overlay_menu_usb_devices));
        assertNotNull(usb);
        assertTrue(usb.isSelected());

        // A opens the page, with the first device selected
        press(KeyEvent.KEYCODE_BUTTON_A);
        assertTrue(button("Test pad").isSelected());
        assertNotNull(button("Checked when you share it"));
        assertNotNull(button(str(R.string.overlay_menu_back)));
        assertNull(button(str(R.string.overlay_menu_usb_devices)));

        // Down to the keyboard, and A chooses it; the menu stays open for its state
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(button("Test keyboard").isSelected());
        assertFalse(button("Test pad").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_A);
        assertEquals(List.of("keyboard"), chosen);
        assertEquals(View.VISIBLE, menu.getVisibility());
        assertFalse(closed);

        // B goes back to the main page, then closes the menu
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertNotNull(button(str(R.string.overlay_menu_usb_devices)));
        assertNull(button("Test pad"));
        press(KeyEvent.KEYCODE_BUTTON_B);
        assertTrue(closed);
    }

    // The share flow's changes show at once, and the selection stays on the device
    @Test
    public void aRefreshKeepsTheSelection() {
        menu.setUsbDevicesShown(true);
        show();
        button(str(R.string.overlay_menu_usb_devices)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(button("Test keyboard").isSelected());

        items.set(1, new OverlayMenuView.UsbItem("keyboard", "Test keyboard", "Connecting to the PC"));
        menu.refreshUsbDevices();
        assertNotNull(button("Connecting to the PC"));
        assertNull(button("Asks you first"));
        assertTrue(button("Test keyboard").isSelected());

        // Unplugged: the selection moves to what's there
        items.remove(1);
        menu.refreshUsbDevices();
        assertNull(button("Test keyboard"));
        assertTrue(button(str(R.string.overlay_menu_back)).isSelected());
    }

    // The selection follows the device, not its place: a device before it unplugged, or another
    // plugged in, and the next A still acts on the one that was selected. Back stays on Back.
    @Test
    public void aRefreshFollowsTheSelectedDevice() {
        items.add(new OverlayMenuView.UsbItem("headset", "Test headset", "Can be shared"));
        menu.setUsbDevicesShown(true);
        show();
        button(str(R.string.overlay_menu_usb_devices)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(button("Test headset").isSelected());

        items.remove(0);
        menu.refreshUsbDevices();
        assertTrue(button("Test headset").isSelected());
        press(KeyEvent.KEYCODE_BUTTON_A);
        assertEquals(List.of("headset"), chosen);

        // Down to Back, then a device comes
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(button(str(R.string.overlay_menu_back)).isSelected());
        items.add(0, new OverlayMenuView.UsbItem("pad", "Test pad", "Checked when you share it"));
        menu.refreshUsbDevices();
        assertTrue(button(str(R.string.overlay_menu_back)).isSelected());
        assertFalse(button("Test headset").isSelected());
    }

    @Test
    public void withNothingPluggedIn() {
        items.clear();
        menu.setUsbDevicesShown(true);
        show();
        button(str(R.string.overlay_menu_usb_devices)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        OverlayMenuButton none = button(str(R.string.usb_share_none_plugged_in));
        assertTrue(none.isSelected());
        press(KeyEvent.KEYCODE_BUTTON_A);
        assertTrue(chosen.isEmpty());
        assertTrue(button(str(R.string.usb_share_none_plugged_in)).isSelected());
    }

    // A refresh with the page closed, or on another page, leaves the menu alone
    @Test
    public void aRefreshElsewhereDoesNothing() {
        menu.setUsbDevicesShown(true);
        menu.refreshUsbDevices();
        assertEquals(View.GONE, menu.getVisibility());
        show();
        menu.refreshUsbDevices();
        assertTrue(button(str(R.string.overlay_menu_close)).isSelected());
        assertNull(button("Test pad"));
    }
}
